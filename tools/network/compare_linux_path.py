"""Same-JVM control: loopback TCP relay versus direct TCP across the private netem veth."""
import argparse
import json
from pathlib import Path
import subprocess
import time


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath",required=True)
    parser.add_argument("--output",required=True,type=Path)
    parser.add_argument("--key",default="/tmp/vtunnel-ssh-network/client_key")
    parser.add_argument("--repetitions",type=int,default=3,choices=range(1,7))
    parser.add_argument("--seconds",type=int,default=8,choices=range(1,31))
    args = parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    raw = args.output / "linux-path.jsonl"
    if raw.exists():
        raise SystemExit("Use a fresh output directory; do not append another experiment")
    with (args.output / "phases.log").open("w") as log:
        for repetition in range(args.repetitions):
            seed = 1060 + repetition
            order = ["linux_proxy","linux_direct"]
            if repetition % 2: order.reverse()
            for label in order:
                host,port = ("127.0.0.1",19080) if label == "linux_proxy" else ("10.205.33.2",22022)
                print(f"seed={seed} path={label}",flush=True)
                log.write(f"seed={seed} path={label}\n");log.flush()
                subprocess.run(["java","-Dvtunnel.probe.offsets=false",
                    "-Dvtunnel.probe.controlHost=127.0.0.1","-cp",args.classpath,
                    "com.jcraft.jsch.TunnelNetworkProbe","queue",label,"mobile-jitter",str(seed),
                    "upload",str(args.seconds),host,str(port),args.key,"4","0","0",str(raw)],
                    stdout=log,stderr=subprocess.STDOUT,check=True)
                time.sleep(2)
    rows = [json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
    if len(rows) != 2 * args.repetitions:
        raise SystemExit("Incomplete Linux path comparison")
    if len({(row["label"],row["seed"]) for row in rows}) != len(rows):
        raise SystemExit("Duplicate Linux path rows")
