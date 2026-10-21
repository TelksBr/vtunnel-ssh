"""Local-only SSH echo fixture. Requires Paramiko; never connects to a real VPS."""
import argparse
import hashlib
import json
import secrets
import socket
import threading
import time
from pathlib import Path

import paramiko


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ready-file", type=Path, required=True)
    parser.add_argument("--stop-file", type=Path, required=True)
    parser.add_argument("--small-window", action="store_true",
                        help="32 KiB peer window with irregular consumption for partial-credit tests")
    parser.add_argument("--duplex-window", action="store_true",
                        help="Enable duplex fixture: withhold upload consumption until receive credit arrives")
    args = parser.parse_args()
    password = secrets.token_hex(24)
    host_key = paramiko.RSAKey.generate(2048)
    public_key_file = Path(__file__).resolve().parents[1] / "src/test/resources/docker/id_rsa.pub"
    authorized = public_key_file.read_text().split()[1]
    transports = []

    class Server(paramiko.ServerInterface):
        def __init__(self):
            self.destinations = {}

        def get_allowed_auths(self, username):
            return "publickey,password,keyboard-interactive"

        def check_auth_password(self, username, supplied):
            return paramiko.AUTH_SUCCESSFUL if username == "vtunnel-test" and supplied == password else paramiko.AUTH_FAILED

        def check_auth_publickey(self, username, key):
            return paramiko.AUTH_SUCCESSFUL if username == "vtunnel-test" and key.get_base64() == authorized else paramiko.AUTH_FAILED

        def check_channel_direct_tcpip_request(self, chanid, origin, destination):
            if destination == ("echo.vtunnel.test", 7) or (args.duplex_window and destination == ("duplex.vtunnel.test", 7)):
                self.destinations[chanid] = destination[0]
                return paramiko.OPEN_SUCCEEDED
            return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def duplex(channel):
        try:
            channel.settimeout(10)
            # 3 MiB exceeds half the client's 4 MiB window. Do not consume the
            # upload (and replenish its 32 KiB window) until the client's update
            # arrives. Paramiko's live out_window_size is fixture instrumentation.
            block = bytes((i * 31 + 7) & 255 for i in range(16384))
            for _ in range(192):
                channel.sendall(block)
            deadline = time.monotonic() + 10
            while channel.out_window_size <= 2 * 1024 * 1024:
                if channel.closed:
                    return
                if time.monotonic() >= deadline:
                    raise TimeoutError("Client receive-window update did not arrive")
                time.sleep(.001)
            received = bytearray()
            while len(received) < 128 * 1024:
                chunk = channel.recv(min(4093, 128 * 1024 - len(received)))
                if not chunk:
                    raise EOFError("Truncated duplex upload")
                received.extend(chunk)
            channel.sendall(hashlib.sha256(received).digest())
            channel.shutdown_write()
        finally:
            channel.close()

    def echo(channel):
        try:
            while data := channel.recv(4093 if args.small_window else 16384):
                channel.sendall(data)
            channel.shutdown_write()
        finally:
            channel.close()

    def serve(client):
        transport = paramiko.Transport(client, default_window_size=32768) if args.small_window or args.duplex_window else paramiko.Transport(client)
        transports.append(transport)
        try:
            transport.add_server_key(host_key)
            server = Server()
            transport.start_server(server=server)
            while transport.is_active() and not args.stop_file.exists():
                channel = transport.accept(0.2)
                if channel is not None:
                    handler = duplex if server.destinations.get(channel.get_id()) == "duplex.vtunnel.test" else echo
                    threading.Thread(target=handler, args=(channel,), daemon=True).start()
        finally:
            transport.close()

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        listener.settimeout(0.2)
        args.ready_file.write_text(json.dumps({"port": listener.getsockname()[1], "password": password}))
        print("Local SSH echo fixture ready", flush=True)
        try:
            while not args.stop_file.exists():
                try:
                    client, _ = listener.accept()
                except socket.timeout:
                    continue
                threading.Thread(target=serve, args=(client,), daemon=True).start()
        finally:
            for transport in transports:
                transport.close()
            args.ready_file.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
