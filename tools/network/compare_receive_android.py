"""Run the same stalled-consumer probe on a development Android via ADB reverse.

Uses only a temporary local SSH fixture. Does not install an APK or access a public VPS.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, choices=range(1, 7), default=3)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / 'receive.jsonl').exists():
        raise SystemExit('Use a fresh output directory')
    adb = ['adb', '-s', args.serial]
    libraries = {'before': args.baseline.resolve(), 'after': args.candidate.resolve()}
    classes = args.output / 'classes'
    classes.mkdir()
    source = root / 'tools/network/ReceiveIsolationProbe.java'
    subprocess.run(['javac', '--release', '8', '-cp', str(libraries['before']), '-d', str(classes), str(source)], check=True)
    probe = args.output / 'probe.jar'
    subprocess.run(['jar', '--create', '--file', str(probe), '-C', str(classes), '.'], check=True)
    remote = '/data/local/tmp/vtunnel-receive-vpn5'
    remote_raw = remote + '/receive.jsonl'
    subprocess.run(adb + ['shell', 'mkdir', '-p', remote], check=True)
    # Only this harness's exact files; no recursive deletion.
    subprocess.run(adb + ['shell', 'rm', '-f', remote_raw], check=True)
    ready, stop = args.output / 'fixture.json', args.output / 'stop'
    phases = []
    port = None
    try:
        for label, library in libraries.items():
            dex = args.output / (label + '.zip')
            command = [str(args.sdk / 'build-tools/37.0.0/d8.bat'), '--min-api', '25', '--lib',
                       str(args.sdk / 'platforms/android-37.0/android.jar'), '--output', str(dex), str(library), str(probe)]
            subprocess.run(command, check=True)
            subprocess.run(adb + ['push', str(dex), remote + '/' + label + '.zip'], check=True)
        with (args.output / 'fixture.log').open('w') as log:
            fixture = subprocess.Popen(['python', str(root / 'tools/loopback_ssh.py'), '--receive-isolation',
                                        '--ready-file', str(ready), '--stop-file', str(stop)], stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if ready.exists():
                        break
                    if fixture.poll() is not None:
                        raise RuntimeError('Fixture exited')
                    time.sleep(.1)
                config = json.loads(ready.read_text())
                port = str(config['port'])
                subprocess.run(adb + ['reverse', 'tcp:' + port, 'tcp:' + port], check=True)
                with (args.output / 'phases.log').open('w') as output:
                    for cipher in ['aes256-ctr', 'aes128-gcm@openssh.com']:
                        for repetition in range(args.repetitions):
                            order = ['before', 'after'] if repetition % 2 == 0 else ['after', 'before']
                            for label in order:
                                phase = {'label': label, 'cipher': cipher, 'repetition': repetition}
                                phases.append(phase)
                                print(json.dumps(phase), flush=True)
                                output.write(json.dumps(phase) + '\n')
                                output.flush()
                                # Password is fixture-only and never recorded in the outputs.
                                command = ('VTUNNEL_SSH_TEST_PASSWORD=' + config['password'] + ' CLASSPATH='
                                           + remote + '/' + label + '.zip app_process /system/bin '
                                           + 'com.jcraft.jsch.ReceiveIsolationProbe ' + label + ' ' + port
                                           + ' ' + cipher + ' ' + remote_raw)
                                subprocess.run(adb + ['shell', command], stdout=output, stderr=subprocess.STDOUT,
                                               check=False, timeout=90)
                                time.sleep(1)
                subprocess.run(adb + ['pull', remote_raw, str(args.output / 'receive.jsonl')], check=True)
            finally:
                stop.touch()
                fixture.wait(timeout=10)
                ready.unlink(missing_ok=True)
    finally:
        if port is not None:
            subprocess.run(adb + ['reverse', '--remove', 'tcp:' + port], check=False)
        subprocess.run(adb + ['shell', 'rm', '-f', remote_raw, remote + '/before.zip', remote + '/after.zip'], check=False)
    rows = [json.loads(line) for line in (args.output / 'receive.jsonl').read_text().splitlines()]
    if len(rows) != len(phases) or [(r['label'], r['cipher']) for r in rows] != [(p['label'], p['cipher']) for p in phases]:
        raise RuntimeError('Incomplete or out-of-order Android phases')
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    metadata = {'phases': phases, 'library_sha256': {label: digest(path) for label, path in libraries.items()},
                'dex_sha256': {label: digest(args.output / (label + '.zip')) for label in libraries},
                'probe_sha256': digest(probe), 'source_sha256': digest(source),
                'fixture_sha256': digest(root / 'tools/loopback_ssh.py'),
                'model': subprocess.check_output(adb + ['shell', 'getprop', 'ro.product.model']).decode().strip(),
                'android': subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.version.release']).decode().strip(),
                'scope': 'Android app_process -> ADB reverse -> local SSH fixture. No APK install, TUN, public VPS or mobile radio.'}
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if any(not r['complete'] or not r['integrity_verified'] for r in rows if r['label'] == 'after'):
        raise RuntimeError('Android candidate failure(s); all phases retained')
    print('Android comparison complete:', len(rows), 'phases')


if __name__ == '__main__':
    main()
