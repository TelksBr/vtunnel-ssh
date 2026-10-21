import unittest
from queue_summary import classify_echo


class QueueSummaryTest(unittest.TestCase):
    def test_missing_sequence_means_request_not_seen_at_destination_by_snapshot(self):
        result = classify_echo(3, {"server_now_ns": 100},
                               {"available": True, "received_sequence": 2, "sent_sequence": 2})
        self.assertEqual("NOT_RECEIVED_AT_DESTINATION", result["stage"])
        self.assertIsNone(result["receive_after_marker_ms"])

    def test_received_is_not_automatically_sent(self):
        result = classify_echo(3, {"server_now_ns": 1000000},
                               {"available": True, "received_sequence": 3, "received_ns": 3000000,
                                "sent_sequence": 2, "sent_ns": 100, "server_now_ns": 4000000})
        self.assertEqual("RECEIVED_NOT_SENT", result["stage"])
        self.assertEqual(2, result["receive_after_marker_ms"])
        self.assertIsNone(result["service_ms"])

    def test_service_time_uses_only_server_clock(self):
        result = classify_echo(3, {"server_now_ns": 1000000},
                               {"available": True, "received_sequence": 3, "received_ns": 3000000,
                                "sent_sequence": 3, "sent_ns": 3500000, "server_now_ns": 5000000})
        self.assertEqual("SENT_AT_DESTINATION", result["stage"])
        self.assertEqual(.5, result["service_ms"])
        self.assertEqual(1.5, result["sent_age_ms"])

    def test_unavailable_snapshot_is_not_treated_as_missing_request(self):
        result = classify_echo(1, {}, {"available": False})
        self.assertEqual("OBSERVER_UNAVAILABLE", result["stage"])
