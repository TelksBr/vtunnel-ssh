"""Bounded private-lab stream counters; a blocking send never owns the snapshot lock."""
import json
import socket
import struct
import threading
import time


def linux_queues(destination):
    import fcntl
    import termios
    state = {}
    for name, command in [("outq", termios.TIOCOUTQ), ("notsent", 0x894B)]:
        # Linux UAPI SIOCOUTQNSD=0x894B: write_seq-snd_nxt; OUTQ includes unacked.
        try:
            value = struct.unpack("i", fcntl.ioctl(destination, command, struct.pack("i", 0)))[0]
            if value < 0:
                raise ValueError("negative queue size")
            state[name] = value
        except (OSError, ValueError) as error:
            state[name] = None
            state[name + "_error"] = getattr(error, "errno", type(error).__name__)
        state[name + "_sample_ns"] = time.monotonic_ns()
    return state


class RelayStream:
    def __init__(self, destination, queues=linux_queues):
        self.destination, self.queues = destination, queues
        self.lock = threading.Lock()
        self.received = self.completed = self.pending = self.uncertain_upper = 0
        self.failed = self.write_eof = self.receiving = False

    def copy(self, source):
        try:
            while True:
                with self.lock:
                    self.receiving = True
                data = source.recv(65536)
                with self.lock:
                    self.receiving = False
                    self.received += len(data)
                if not data:
                    break
                view = memoryview(data)
                while view:
                    with self.lock:
                        self.pending = len(view)
                    count = self.destination.send(view)
                    if count <= 0:
                        raise OSError("zero-length relay send")
                    with self.lock:
                        self.completed += count
                        self.pending = 0
                    view = view[count:]
        except OSError:
            with self.lock:
                self.failed = True
                self.uncertain_upper = max(self.uncertain_upper, self.completed + self.pending)
                self.pending = 0
                self.receiving = False
        finally:
            with self.lock:
                self.write_eof = True
            try:
                self.destination.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    def snapshot(self):
        started = time.monotonic_ns()
        with self.lock:
            lower = self.completed
            upper = max(lower + self.pending, self.uncertain_upper)
            state = dict(received=self.received,
                         received_upper=self.received + (65536 if self.receiving else 0),
                         accepted_lower=lower, accepted_upper=upper,
                         send_in_progress=self.pending > 0,
                         failed=self.failed, write_eof=self.write_eof,
                         snapshot_started_ns=started)
            # Queries only, never a blocking socket read/write while holding this lock.
            state.update(self.queues(self.destination))
            for field, queue in [("acked", "outq"), ("sent", "notsent")]:
                size = state.get(queue)
                state[field + "_lower"] = None if size is None else max(0, lower - size)
                state[field + "_upper"] = None if size is None else max(0, upper - size)
            state["snapshot_finished_ns"] = time.monotonic_ns()
            return state


class RelayRegistry:
    def __init__(self):
        self.lock = threading.Lock()
        self.generation = 0
        self.current = None

    def attach(self, stream):
        with self.lock:
            self.generation += 1
            self.current = stream

    def snapshot(self):
        with self.lock:
            generation, stream = self.generation, self.current
        if stream is None:
            return dict(available=False, generation=generation)
        return dict(available=True, generation=generation, **stream.snapshot())


def serve_snapshots(path, registry):
    path.unlink(missing_ok=True)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
        listener.bind(str(path))
        listener.listen()
        while True:
            client, _ = listener.accept()
            with client:
                client.settimeout(1)
                try:
                    if client.recv(16).strip() == b"SNAPSHOT":
                        client.sendall((json.dumps(registry.snapshot()) + "\n").encode())
                except OSError:
                    pass


def query_snapshot(path):
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(1)
            client.connect(str(path))
            client.sendall(b"SNAPSHOT\n")
            with client.makefile("rb") as reader:
                return json.loads(reader.readline(16384))
    except (OSError, ValueError) as error:
        return dict(available=False, error=type(error).__name__)
