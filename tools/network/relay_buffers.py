"""Private-lab transport buffer control. Never configures production sockets."""
import socket


def configure_send_buffer(server, requested):
    if not isinstance(requested, int) or not 0 <= requested <= 1048576:
        raise ValueError("Invalid private relay buffer size")
    if requested:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, requested)
    return dict(requested_send_buffer=requested,
                actual_send_buffer_at_connect=server.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF),
                actual_receive_buffer_at_connect=server.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF),
                copy_chunk_bytes=65536)
