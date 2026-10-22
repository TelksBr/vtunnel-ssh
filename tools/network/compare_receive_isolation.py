"""AB/BA comparison of a stalled consumer on encrypted loopback SSH, with raw samples."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, choices=range(1, 7), default=3)
    parser.add_argument('--cipher', choices=['aes256-ctr', 'aes128-gcm@openssh.com'], default='aes256-ctr')
    parser.add_argument('--diagnostics', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = args.output / 'receive.jsonl'
    if raw.exists():
        raise SystemExit('Use a fresh output directory')
    libraries = {'before': args.baseline.resolve(), 'after': args.candidate.resolve()}
    for library in libraries.values():
        if not library.is_file():
            raise SystemExit('Missing library: ' + str(library))
    classes = args.output / 'classes'
    classes.mkdir()
    source = root / 'tools/network/ReceiveIsolationProbe.java'
    snapshot = args.output / 'source'
    snapshot.mkdir()
    frozen_source = snapshot / source.name
    frozen_source.write_bytes(source.read_bytes())
    frozen_fixture = snapshot / 'loopback_ssh.py'
    frozen_fixture.write_bytes((root / 'tools/loopback_ssh.py').read_bytes())
    subprocess.run(['javac', '--release', '8', '-cp', str(libraries['before']), '-d', str(classes), str(frozen_source)], check=True)
    probe = args.output / 'receive-probe.jar'
    subprocess.run(['jar', '--create', '--file', str(probe), '-C', str(classes), '.'], check=True)
    ready, stop = args.output / 'fixture.json', args.output / 'stop'
    env = os.environ.copy()
    phases = []
    with (args.output / 'fixture.log').open('w') as fixture_log:
        fixture = subprocess.Popen(['python', str(root / 'tools/loopback_ssh.py'), '--receive-isolation',
                                    '--ready-file', str(ready), '--stop-file', str(stop)],
                                   stdout=fixture_log, stderr=subprocess.STDOUT)
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
                        subprocess.run(['java', '-Dvtunnel.receive.diagnostics=' + str(args.diagnostics).lower(),
                                        '-cp', str(libraries[label]) + os.pathsep + str(probe),
                                        'com.jcraft.jsch.ReceiveIsolationProbe', label, str(config['port']), args.cipher,
                                        str(raw)], env=env, stdout=log, stderr=subprocess.STDOUT, check=False,
                                       timeout=90)
                        time.sleep(.5)
        finally:
            stop.touch()
            fixture.wait(timeout=10)
            ready.unlink(missing_ok=True)
    rows = [json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
    if len(rows) != 2 * args.repetitions or [r['label'] for r in rows] != [p['label'] for p in phases]:
        raise RuntimeError('Incomplete or out-of-order phases')
    summary = []
    for label in libraries:
        group = [r for r in rows if r['label'] == label]
        complete = [r for r in group if r['complete']]
        median = lambda key: statistics.median(r[key] for r in complete) if complete else None
        summary.append({'label': label, 'phases': len(group),
                        'complete': sum(r['complete'] for r in group),
                        'median_echo_before_resume': statistics.median(r['echo_before_resume'] for r in group),
                        'median_phase_p50_ms': median('rtt_p50_ms'),
                        'median_phase_p95_ms': median('rtt_p95_ms'),
                        'median_scheduled_p95_ms': median('scheduled_p95_ms')})
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    metadata = {'phases': phases, 'java_version': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT).decode(),
                'library_sha256': {label: digest(path) for label, path in libraries.items()},
                'probe_sha256': digest(probe), 'source_sha256': digest(frozen_source),
                'fixture_sha256': digest(frozen_fixture), 'cipher': args.cipher,
                'diagnostics': args.diagnostics,
                'scope': 'Loopback SSH, 1500 ms forced consumer stall; 8 MiB download + 2 MiB upload echo + 80 scheduled 32-byte echoes. No TUN/radio/Speedtest.'}
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(summary))
    if any(not r['complete'] or not r['integrity_verified'] for r in rows if r['label'] == 'after'):
        raise RuntimeError('Candidate failure(s); all phases retained')


if __name__ == '__main__':
    main()
