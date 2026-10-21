"""Compare the duplex credit cycle on local encrypted SSH; not a speed/jitter benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, choices=range(1, 7), default=3)
    parser.add_argument('--cipher', choices=['aes256-ctr', 'aes128-gcm@openssh.com'], default='aes256-ctr')
    parser.add_argument('--read-delay-ms', type=int, choices=range(0, 11), default=0)
    parser.add_argument('--metrics', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    libraries = {'before': args.baseline.resolve(), 'after': args.candidate.resolve()}
    for path in libraries.values():
        if not path.is_file():
            raise SystemExit('Library JAR missing: ' + str(path))
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = args.output / 'duplex.jsonl'
    if raw.exists():
        raise SystemExit('Use a fresh output directory')
    classes = args.output / 'probe-classes'
    classes.mkdir(exist_ok=True)
    probe_source = root / 'tools/network/DuplexWindowProbe.java'
    subprocess.run(['javac', '--release', '8', '-cp', str(libraries['before']),
                    '-d', str(classes), str(probe_source)], check=True)
    probe = args.output / 'duplex-probe.jar'
    subprocess.run(['jar', '--create', '--file', str(probe), '-C', str(classes), '.'], check=True)
    ready, stop = args.output / 'fixture.json', args.output / 'stop'
    env = os.environ.copy()
    phases = []
    with (args.output / 'fixture.log').open('w') as fixture_log:
        fixture = subprocess.Popen(['python', str(root / 'tools/loopback_ssh.py'),
                                    '--duplex-window', '--ready-file', str(ready),
                                    '--stop-file', str(stop)], stdout=fixture_log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if ready.exists():
                    break
                if fixture.poll() is not None:
                    raise RuntimeError('Fixture exited')
                time.sleep(.1)
            config = json.loads(ready.read_text())
            env['VTUNNEL_SSH_TEST_PASSWORD'] = config['password']
            with (args.output / 'phases.log').open('w') as log:
                for repetition in range(args.repetitions):
                    order = ['before', 'after'] if repetition % 2 == 0 else ['after', 'before']
                    for label in order:
                        phases.append({'repetition': repetition, 'label': label})
                        print(f'Pair {repetition + 1}: {label}', flush=True)
                        log.write(json.dumps(phases[-1]) + '\n')
                        log.flush()
                        subprocess.run(['java', '-Dvtunnel.duplex.cipher=' + args.cipher,
                                        '-Dvtunnel.duplex.metrics=' + str(args.metrics).lower(),
                                        '-Dvtunnel.duplex.readDelay=' + str(args.read_delay_ms),
                                        '-cp', str(libraries[label]) + os.pathsep + str(probe),
                                        'com.jcraft.jsch.DuplexWindowProbe', label, str(config['port']),
                                        str(raw)], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
                        time.sleep(.5)
        finally:
            stop.touch()
            fixture.wait(timeout=10)
            ready.unlink(missing_ok=True)
    rows = [json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
    if len(rows) != 2 * args.repetitions or [r['label'] for r in rows] != [p['label'] for p in phases]:
        raise RuntimeError('Incomplete or out-of-order paired result')
    summary = []
    for label in ['before', 'after']:
        group = [r for r in rows if r['label'] == label]
        times = [r['elapsed_ms'] for r in group if r['complete']]
        if any(r['complete'] and not r['integrity_verified'] for r in group):
            raise RuntimeError('Unverified completed transfer')
        summary.append({'label': label, 'executions': len(group),
                        'completed': sum(r['complete'] for r in group),
                        'timeouts': sum(r['timeout'] for r in group),
                        'errors': sum(not r['complete'] and not r['timeout'] for r in group),
                        'completed_elapsed_ms': None if not times else {
                            'median': statistics.median(times), 'min': min(times), 'max': max(times)}})
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    metadata = {'phases': phases, 'java_version': subprocess.check_output(
        ['java', '-version'], stderr=subprocess.STDOUT).decode(),
        'binaries_sha256': {label: digest(path) for label, path in libraries.items()},
        'probe_sha256': digest(probe), 'probe_source_sha256': digest(probe_source),
        'fixture_source_sha256': digest(root / 'tools/loopback_ssh.py'),
        'cipher': args.cipher, 'mac': 'AEAD' if 'gcm' in args.cipher else 'hmac-sha2-512',
        'flow_metrics_enabled': args.metrics, 'read_delay_ms': args.read_delay_ms,
        'scope': 'Loopback JVM, synthetic receive-credit dependency, 4 MiB window/1 MiB pipe; not VPN/mobile/Speedtest'}
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(summary))
    if any(s['errors'] for s in summary):
        raise RuntimeError('Transfer error(s); inspect the preserved raw results')


if __name__ == '__main__':
    main()
