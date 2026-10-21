import json
import struct
import tempfile
import unittest
from pathlib import Path
import echo_observer


class EchoObserverTest(unittest.TestCase):
    def test_fragmented_request_is_observed_only_when_complete_and_echoed_exactly(self):
        frame = struct.pack(">Q", 7) + bytes(range(24))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "echo.json"
            ticks = iter([100, 200])
            journal = echo_observer.EchoJournal(path, clock=lambda: next(ticks))
            class Peer:
                chunks = iter([frame[:3], frame[3:17], frame[17:], b""])
                sent = bytearray()
                def recv(self, size):
                    return next(self.chunks)
                def sendall(self, data):
                    state = json.loads(path.read_text())
                    self_test.assertEqual(7, state["received_sequence"])
                    self_test.assertIsNone(state["sent_sequence"])
                    self.sent.extend(data)
            self_test = self
            peer = Peer()
            echo_observer.serve_echo(peer, journal)
            self.assertEqual(frame, peer.sent)
            state = json.loads(path.read_text())
            self.assertEqual((7, 100, 7, 200), tuple(state[key] for key in
                             ["received_sequence", "received_ns", "sent_sequence", "sent_ns"]))
            self.assertEqual(1, state["received_count"])
            self.assertEqual(1, state["sent_count"])

    def test_failed_send_is_not_reported_as_a_response(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "echo.json"
            journal = echo_observer.EchoJournal(path, clock=lambda: 100)
            class Peer:
                def recv(self, size):
                    return struct.pack(">Q", 9) + bytes(24)
                def sendall(self, data):
                    raise OSError("fixture closed")
            with self.assertRaises(OSError):
                echo_observer.serve_echo(Peer(), journal)
            state = json.loads(path.read_text())
            self.assertEqual(9, state["received_sequence"])
            self.assertIsNone(state["sent_ns"])
            self.assertEqual(0, state["sent_count"])

    def test_pending_new_request_does_not_inherit_previous_send(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "echo.json"
            journal = echo_observer.EchoJournal(path)
            journal.record("received", 1)
            journal.record("sent", 1)
            journal.record("received", 2)
            state = json.loads(path.read_text())
            self.assertEqual(2, state["received_sequence"])
            self.assertEqual(1, state["sent_sequence"])
            self.assertEqual(2, state["received_count"])
            self.assertEqual(1, state["sent_count"])

    def test_missing_journal_is_explicit_not_a_zero_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            state = echo_observer.snapshot(Path(directory) / "missing.json")
            self.assertFalse(state["available"])
            self.assertNotIn("received_ns", state)
