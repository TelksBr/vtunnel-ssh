"""Temporary Windows LAN bridge into the private WSL lab, restricted to one dev device IP."""
import argparse
import ipaddress
import socket
import threading


def pump(source, destination):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            destination.sendall(data)
    except OSError:
        pass
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def serve(bind, allowed, local, target):
    with socket.create_server((bind, local)) as listener:
        while True:
            client, peer = listener.accept()
            if peer[0] != allowed:
                client.close()
                continue
            def connect(client):
                try:
                    with client, socket.create_connection(("127.0.0.1", target), timeout=5) as server:
                        server.settimeout(None)
                        for endpoint in (client, server):
                            endpoint.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        reverse = threading.Thread(target=pump, args=(client, server), daemon=True)
                        reverse.start()
                        pump(server, client)
                        reverse.join(timeout=2)
                except OSError:
                    client.close()
            threading.Thread(target=connect, args=(client,), daemon=True).start()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, type=ipaddress.IPv4Address)
    parser.add_argument("--device", required=True, type=ipaddress.IPv4Address)
    args = parser.parse_args()
    for local, target in [(20080, 19080), (20084, 19084)]:
        threading.Thread(target=serve, args=(str(args.bind), str(args.device), local, target), daemon=True).start()
    threading.Event().wait()
