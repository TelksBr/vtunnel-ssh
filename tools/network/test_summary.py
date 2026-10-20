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
