import concurrent.futures
import pathlib
import struct
import sys
import tempfile
import threading
import time
import unittest
import lab


@unittest.skipUnless(sys.platform == "linux", "The network lab uses Linux flock")
class CounterSnapshotTest(unittest.TestCase):
    def test_snapshot_waits_until_writer_has_finished_both_counters(self):
        import fcntl

        ready = threading.Event()
        release = threading.Event()
        started = threading.Event()
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "counters.bin"
            path.write_bytes(struct.pack("<QQ", 900, 1800))

            def writer():
                with path.open("r+b") as stream:
                    fcntl.flock(stream, fcntl.LOCK_EX)
                    # Deliberately expose the intermediate state of an in-place update.
                    stream.write(bytes(16))
                    stream.flush()
                    ready.set()
                    if not release.wait(3):
                        raise TimeoutError("Reader did not release the test writer")
                    stream.seek(0)
                    stream.write(struct.pack("<QQ", 1000, 2000))
                    stream.flush()
                    fcntl.flock(stream, fcntl.LOCK_UN)

            def reader():
                started.set()
                return lab.read_counters(path)

            with concurrent.futures.ThreadPoolExecutor(2) as pool:
                writing = pool.submit(writer)
                self.assertTrue(ready.wait(2))
                reading = pool.submit(reader)
                try:
                    self.assertTrue(started.wait(2))
                    time.sleep(0.1)
                    self.assertFalse(reading.done(), "A torn snapshot must never be observable")
                finally:
                    release.set()
                writing.result(2)
                self.assertEqual((1000, 2000), reading.result(2))
