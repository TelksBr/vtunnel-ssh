import unittest
from stream_classify import classify


def sample(**overrides):
    transport = dict(available=True, generation=4, received=200, received_upper=200,
                     accepted_lower=200, accepted_upper=200, sent_lower=180, sent_upper=180,
                     acked_lower=180, acked_upper=180, failed=False, write_eof=False)
    transport.update(overrides)
    return dict(range=dict(start=100,end=132,failed=False,writes=1,completed_writes=1),
                local=dict(valid=True), remote=dict(received_sequence=None, relay_transport=transport))


class StreamClassificationTest(unittest.TestCase):
    def test_echo_not_fully_consumed_by_relay_is_distinct_from_tcp_queue(self):
        result = classify(sample(received=70,received_upper=80,accepted_lower=70,accepted_upper=70),1,4)
        self.assertEqual("BEFORE_RELAY_COPY",result)

    def test_pending_send_is_not_assumed_accepted(self):
        self.assertEqual("RELAY_USERSPACE",classify(sample(accepted_lower=80,accepted_upper=90),1,4))
        self.assertEqual("BOUNDARY_UNCERTAIN",classify(sample(accepted_lower=100,accepted_upper=160,
                         sent_lower=90,sent_upper=150,acked_lower=90,acked_upper=150),1,4))

    def test_unsent_and_sent_unacked_are_separate(self):
        self.assertEqual("RELAY_TCP_UNSENT",classify(sample(sent_lower=70,sent_upper=80,acked_lower=60,acked_upper=60),1,4))
        self.assertEqual("TCP_SENT_NOT_FULLY_ACKED",classify(sample(acked_lower=70,acked_upper=80),1,4))

    def test_ack_is_not_reported_as_destination_receipt(self):
        self.assertEqual("TCP_ACKED_DESTINATION_PENDING",classify(sample(),1,4))
        value = sample()
        value["remote"]["received_sequence"] = 1
        self.assertEqual("DESTINATION_RECEIVED",classify(value,1,4))

    def test_missing_kernel_values_or_new_connection_are_unknown(self):
        self.assertEqual("BOUNDARY_UNCERTAIN",classify(sample(sent_lower=None,sent_upper=None,acked_lower=None,acked_upper=None),1,4))
        self.assertEqual("CONNECTION_CHANGED",classify(sample(generation=5),1,4))

    def test_failed_or_pending_client_packet_does_not_claim_a_sent_packet(self):
        value = sample()
        value["range"]["failed"] = True
        self.assertEqual("INVALID_CLIENT_TRACE",classify(value,1,4))
        value["range"] = None
        self.assertEqual("NO_TRANSPORT_PACKET_YET",classify(value,1,4))
