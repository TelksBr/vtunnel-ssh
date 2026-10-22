"""Bounded last-event journal for the PRIVATE lab; server clocks never mix with Android clocks."""
import json
import os
import struct
import threading
import time


class EchoJournal:
    def __init__(self, path, clock=time.monotonic_ns):
        self.path = path
        self.clock = clock
        self.lock = threading.Lock()
        self.state = dict(received_sequence=None, received_ns=None, received_count=0,
                          sent_sequence=None, sent_ns=None, sent_count=0)
        self._save()

    def _save(self):
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.state))
        os.replace(temporary, self.path)

    def record(self, stage, sequence):
        if stage not in ("received", "sent"):
            raise ValueError("Unknown echo stage")
        with self.lock:
            self.state[stage + "_sequence"] = sequence
            self.state[stage + "_ns"] = self.clock()
            self.state[stage + "_count"] += 1
            self._save()


def serve_echo(client, journal):
    pending = bytearray()
    while True:
        data = client.recv(65536)
        if not data:
            return
        pending.extend(data)
        while len(pending) >= 32:
            frame = bytes(pending[:32])
            del pending[:32]
            sequence = struct.unpack(">Q", frame[:8])[0]
            journal.record("received", sequence)
            client.sendall(frame)
            # Successful sendall means local acceptance, not receipt by the Android client.
            journal.record("sent", sequence)


def snapshot(path):
    try:
        state = json.loads(path.read_text())
    except FileNotFoundError:
        state = dict(available=False)
    else:
        state["available"] = True
    state["server_now_ns"] = time.monotonic_ns()
    return state
