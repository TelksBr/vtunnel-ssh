import socket
import unittest
from unittest.mock import Mock
from relay_buffers import configure_send_buffer


class RelayBuffersTest(unittest.TestCase):
    def test_default_does_not_override_kernel_and_reports_actual_values(self):
        server = Mock()
        server.getsockopt.side_effect = [2626560, 131072]
        result = configure_send_buffer(server, 0)
        server.setsockopt.assert_not_called()
        self.assertEqual(2626560, result["actual_send_buffer_at_connect"])

    def test_request_is_not_mistaken_for_actual_kernel_buffer(self):
        server = Mock()
        server.getsockopt.side_effect = [65536, 131072]
        result = configure_send_buffer(server, 32768)
        server.setsockopt.assert_called_once_with(socket.SOL_SOCKET, socket.SO_SNDBUF, 32768)
        self.assertEqual(32768, result["requested_send_buffer"])
        self.assertEqual(65536, result["actual_send_buffer_at_connect"])

    def test_invalid_request_does_not_mutate_socket(self):
        server = Mock()
        with self.assertRaises(ValueError):
            configure_send_buffer(server, -1)
        server.setsockopt.assert_not_called()
