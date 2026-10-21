import socket
import sys
import time
from stream_relay import linux_queues
import tempfile
import threading
import unittest
from pathlib import Path
from stream_relay import RelayStream


class RelayStreamTest(unittest.TestCase):
    def test_partial_sends_preserve_bytes_and_count_only_completed_acceptance(self):
        class Source:
            chunks = iter([b"abcdefgh", b"ijkl", b""])
            def recv(self, size): return next(self.chunks)
        class Destination:
            received = bytearray()
            def send(self, data):
                take = min(3, len(data))
                self.received.extend(data[:take])
                return take
            def shutdown(self, how): pass
        destination = Destination()
        stream = RelayStream(destination, queues=lambda _: dict(outq=0, notsent=0))
        stream.copy(Source())
        self.assertEqual(b"abcdefghijkl", destination.received)
        state = stream.snapshot()
        self.assertEqual(12, state["received"])
        self.assertEqual(12, state["accepted_lower"])
        self.assertEqual(12, state["accepted_upper"])

    def test_snapshot_does_not_wait_for_blocked_send_or_claim_zero_acceptance(self):
        entered, release = threading.Event(), threading.Event()
        class Source:
            def recv(self, size): return b"abcdefgh"
        class Destination:
            def send(self, data):
                entered.set()
                if not release.wait(2): raise AssertionError("fixture drain timeout")
                raise OSError("may have partially accepted bytes")
            def shutdown(self, how): pass
        stream = RelayStream(Destination(), queues=lambda _: dict(outq=5, notsent=4))
        thread = threading.Thread(target=stream.copy, args=(Source(),))
        thread.start()
        try:
            self.assertTrue(entered.wait(1))
            state = stream.snapshot()
            self.assertEqual(0, state["accepted_lower"])
            self.assertEqual(8, state["accepted_upper"])
            self.assertEqual(5, state["outq"])
            self.assertTrue(state["send_in_progress"])
        finally:
            release.set()
            thread.join(2)
        self.assertFalse(thread.is_alive())
        state = stream.snapshot()
        self.assertEqual(8, state["accepted_upper"], "Failure must preserve partial-write uncertainty")
        self.assertTrue(state["failed"])

    def test_missing_kernel_values_stay_unknown(self):
        stream = RelayStream(object(), queues=lambda _: dict(outq=None, notsent=None, error="unsupported"))
        state = stream.snapshot()
        self.assertIsNone(state["acked_lower"])
        self.assertIsNone(state["sent_lower"])

    @unittest.skipUnless(sys.platform == "linux", "Linux TCP ioctl integration")
    def test_real_tcp_queues_are_observed_and_acknowledged_after_read(self):
        with socket.create_server(("127.0.0.1", 0)) as listener:
            with socket.create_connection(listener.getsockname()) as sender:
                receiver, _ = listener.accept()
                with receiver:
                    data = bytes(range(256)) * 16
                    sender.sendall(data)
                    received = bytearray()
                    while len(received) < len(data):
                        received.extend(receiver.recv(len(data)-len(received)))
                    self.assertEqual(data, received)
                    deadline = time.monotonic() + 2
                    state = linux_queues(sender)
                    while state["outq"] != 0 and time.monotonic() < deadline:
                        time.sleep(.01)
                        state = linux_queues(sender)
                    self.assertEqual(0, state["outq"])
                    self.assertEqual(0, state["notsent"])
