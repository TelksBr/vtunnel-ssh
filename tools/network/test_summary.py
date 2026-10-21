import unittest
from summarize import summarize


class SummaryValidityTest(unittest.TestCase):
    def test_negative_remote_goodput_is_rejected_instead_of_becoming_a_regression(self):
        with self.assertRaises(ValueError):
            summarize([{"direction": "upload", "remote_upload_mbps": -30.85}])

    def test_regressed_counter_is_rejected_even_if_rate_is_positive(self):
        with self.assertRaises(ValueError):
            summarize([{"direction": "upload", "remote_upload_mbps": 100,
                        "upload_before": 200, "upload_after": 100}])

    def test_timeout_stage_survives_without_inventing_an_rtt(self):
        row = dict(direction="upload", remote_upload_mbps=3, profile="mobile-jitter",
                   label="on", seed=1, seconds=12, cpu_ms=30, samples=0, timeouts=1,
                   load_errors=0, rtt_p50_ms=None, rtt_p95_ms=None,
                   adjacent_rtt_jitter_ms=None,
                   echo_events=[dict(stage="FLUSHING", timeout=True, flush_ms=None)])
        result = summarize([row])[0]
        self.assertIsNone(result["rtt_p95_ms"])
        self.assertEqual({"FLUSHING": 1}, result["timeout_stages"])

    def test_legacy_timeouts_are_explicitly_unclassified(self):
        row = dict(direction="upload", remote_upload_mbps=3, profile="mobile-jitter",
                   label="old", seed=1, seconds=12, cpu_ms=30, samples=0, timeouts=1,
                   load_errors=0, rtt_p50_ms=None, rtt_p95_ms=None,
                   adjacent_rtt_jitter_ms=None)
        self.assertEqual({"UNCLASSIFIED": 1}, summarize([row])[0]["timeout_stages"])
