"""Compare stalled consumers against the private OpenSSH lab, JVM and optional Android.

Run on Windows with JDK tools and WSL. No public servers or production credentials.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--serial')
    parser.add_argument('--sdk', type=Path)
    parser.add_argument('--repetitions', type=int, choices=range(1, 7), default=3)
    parser.add_argument('--platform', choices=['both', 'jvm', 'android'], default='both')
    parser.add_argument('--diagnostics', action='store_true')
    args = parser.parse_args()
    if args.serial and not args.sdk:
        parser.error('--sdk is required for Android')
    if args.platform == 'android' and not args.serial:
        parser.error('--serial is required for Android')
    root = Path(__file__).resolve().parents[2]
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / 'source').exists():
        raise SystemExit('Use a fresh output directory')
    source = args.output / 'source'
    source.mkdir()
    for name in ['ReceiveIsolationProbe.java', 'lab.py', 'echo_observer.py']:
        shutil.copyfile(root / 'tools/network' / name, source / name)
    libraries = {'before': args.baseline.resolve(), 'after': args.candidate.resolve()}
    classes = args.output / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '--release', '8', '-cp', str(libraries['before']), '-d', str(classes),
                    str(source / 'ReceiveIsolationProbe.java')], check=True)
    probe = args.output / 'probe.jar'
    subprocess.run(['jar', '--create', '--file', str(probe), '-C', str(classes), '.'], check=True)
    wsl = ['wsl', '-u', 'root', '-e']
    linux = lambda path: subprocess.check_output(wsl + ['wslpath', '-a', str(path)], text=True).strip()
    lab = linux(root / 'tools/network/lab.py')
    linux_probe = linux(probe)
    linux_libraries = {label: linux(path) for label, path in libraries.items()}
    adb = ['adb', '-s', args.serial] if args.serial else None
    remote = '/data/local/tmp/vtunnel-receive-openssh-vpn5'
    remote_raw = remote + '/receive.jsonl'
    key = args.output / 'temporary-client-key'
    android_port = None
    phases = []
    result_rows = []
    bridge = None
    reset = ('import socket; s=socket.create_connection(("127.0.0.1",19084),3); s.settimeout(10); '
             's.sendall(b"RESET unshaped 1700\\n"); '
             'r=s.makefile("rb").readline(); s.close(); '
             'assert r==b"OK\\n",r')
    try:
        addresses = json.loads(subprocess.check_output(wsl + ['ip', '-j', '-4', 'addr', 'show', 'dev', 'eth0'], text=True))
        wsl_address = next(a['local'] for a in addresses[0]['addr_info'] if a['family'] == 'inet')
        routes = json.loads(subprocess.check_output(wsl + ['ip', '-j', 'route', 'show', 'default'], text=True))
        windows_peer = routes[0]['gateway']
        subprocess.run(wsl + ['env', 'VTUNNEL_LAB_RELAY_BIND=' + wsl_address,
                              'VTUNNEL_LAB_RELAY_PEER=' + windows_peer, 'python3', lab, 'up'], check=True)
        subprocess.run(wsl + ['python3', lab, 'start-control'], check=True)
        time.sleep(.5)
        if adb:
            # Avoid the opaque Windows wslrelay localhost forwarder. Own this bridge and its lifetime.
            bridge = socket.create_server(('127.0.0.1', 0))
            bridge.settimeout(.25)
            bridge_port = bridge.getsockname()[1]
            def pump(source, destination):
                try:
                    while data := source.recv(65536): destination.sendall(data)
                except OSError:
                    pass
                finally:
                    try: destination.shutdown(socket.SHUT_WR)
                    except OSError: pass
            def connection(client):
                try:
                    with client, socket.create_connection((wsl_address, 19080), timeout=5) as server:
                        server.settimeout(None)
                        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        server.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        reverse = threading.Thread(target=pump, args=(client, server), daemon=True)
                        reverse.start()
                        pump(server, client)
                        reverse.join(timeout=2)
                except OSError:
                    client.close()
            def accept():
                while bridge.fileno() >= 0:
                    try: client, _ = bridge.accept()
                    except socket.timeout: continue
                    except OSError: return
                    threading.Thread(target=connection, args=(client,), daemon=True).start()
            threading.Thread(target=accept, daemon=True).start()
            subprocess.run(wsl + ['cp', '/tmp/vtunnel-ssh-network/client_key', linux(key)], check=True)
            subprocess.run(adb + ['shell', 'mkdir', '-p', remote], check=True)
            subprocess.run(adb + ['shell', 'rm', '-f', remote_raw], check=True)
            subprocess.run(adb + ['push', str(key), remote + '/client_key'], check=True)
            subprocess.run(adb + ['shell', 'chmod', '600', remote + '/client_key'], check=True)
            for label, library in libraries.items():
                dex = args.output / (label + '.zip')
                subprocess.run([str(args.sdk / 'build-tools/37.0.0/d8.bat'), '--min-api', '25', '--lib',
                                str(args.sdk / 'platforms/android-37.0/android.jar'), '--output', str(dex),
                                str(library), str(probe)], check=True)
                subprocess.run(adb + ['push', str(dex), remote + '/' + label + '.zip'], check=True)
            inventory = subprocess.check_output(adb + ['reverse', '--list'], text=True)
            android_port = next(str(port) for port in range(45080, 45100) if 'tcp:' + str(port) not in inventory)
            subprocess.run(adb + ['reverse', 'tcp:' + android_port, 'tcp:' + str(bridge_port)], check=True)
        with (args.output / 'phases.log').open('w') as log:
            platforms = (['jvm'] + (['android'] if adb else [])) if args.platform == 'both' else [args.platform]
            for platform in platforms:
                for cipher in ['aes256-ctr', 'aes128-gcm@openssh.com']:
                    for repetition in range(args.repetitions):
                        order = ['before', 'after'] if repetition % 2 == 0 else ['after', 'before']
                        for label in order:
                            phase = {'platform': platform, 'cipher': cipher, 'repetition': repetition, 'label': label}
                            phases.append(phase)
                            print(json.dumps(phase), flush=True)
                            log.write(json.dumps(phase) + '\n')
                            log.flush()
                            subprocess.run(wsl + ['python3', '-c', reset], check=True, timeout=15, stdout=log, stderr=subprocess.STDOUT)
                            if platform == 'jvm':
                                raw = args.output / 'jvm.jsonl'
                                command = wsl + ['env', 'VTUNNEL_SSH_TEST_KEY=/tmp/vtunnel-ssh-network/client_key',
                                    'java', '-Dvtunnel.receive.openssh=true',
                                    '-Dvtunnel.receive.diagnostics=' + str(args.diagnostics).lower(), '-cp',
                                    linux_libraries[label] + ':' + linux_probe,
                                    'com.jcraft.jsch.ReceiveIsolationProbe', label, '22022', cipher, linux(raw)]
                            else:
                                command = adb + ['shell', 'VTUNNEL_SSH_TEST_KEY=' + remote + '/client_key CLASSPATH='
                                    + remote + '/' + label + '.zip app_process -Dvtunnel.receive.openssh=true '
                                    '-Dvtunnel.receive.diagnostics=' + str(args.diagnostics).lower()
                                    + ' -Dvtunnel.receive.host=127.0.0.1 /system/bin com.jcraft.jsch.ReceiveIsolationProbe '
                                    + label + ' ' + android_port + ' ' + cipher + ' ' + remote_raw]
                            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=90, check=False)
                            phase['exit'] = result.returncode
                            time.sleep(.5)
                if platform == 'android':
                    subprocess.run(adb + ['pull', remote_raw, str(args.output / 'android.jsonl')], check=True)
                rows = [json.loads(line) for line in (args.output / (platform + '.jsonl')).read_text().splitlines() if line.strip()]
                expected = [p for p in phases if p['platform'] == platform]
                if len(rows) != len(expected) or [(r['label'], r['cipher']) for r in rows] != [(p['label'], p['cipher']) for p in expected]:
                    raise RuntimeError('Incomplete or out-of-order ' + platform + ' comparison')
                result_rows.extend(rows)
    finally:
        if android_port:
            subprocess.run(adb + ['reverse', '--remove', 'tcp:' + android_port], check=False)
        if bridge:
            bridge.close()
        if adb:
            subprocess.run(adb + ['shell', 'rm', '-f', remote_raw, remote + '/client_key',
                                   remote + '/before.zip', remote + '/after.zip'], check=False)
        key.unlink(missing_ok=True)
        subprocess.run(wsl + ['python3', lab, 'down'], check=False)
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    metadata = {'backend': 'OpenSSH in the private Linux namespace', 'phases': phases,
                'diagnostics': args.diagnostics,
                'library_sha256': {label: digest(path) for label, path in libraries.items()},
                'probe_sha256': digest(probe), 'source_sha256': {p.name: digest(p) for p in source.iterdir()},
                'jvm_path': 'Linux JVM -> direct TCP/veth -> OpenSSH -> local finite download/echo',
                'android_path': 'Android app_process -> ADB reverse -> owned Windows loopback bridge -> WSL eth0 relay restricted to Windows peer -> unshaped veth -> OpenSSH',
                'scope': '8 MiB finite download, 2 MiB upload echo, 80 scheduled 32-byte echoes, 1500 ms consumer stall. No TUN, public VPS or real mobile radio.'}
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if any(not r['complete'] or not r['integrity_verified'] for r in result_rows if r['label'] == 'after'):
        raise RuntimeError('Candidate failure(s); raw phases retained')
    print('OpenSSH comparison complete:', len(result_rows), 'phases')


if __name__ == '__main__':
    main()
