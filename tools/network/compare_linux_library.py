"""Paired library validation on end-to-end TCP over the private netem veth, no TCP relay."""
import argparse
import json
from pathlib import Path
import subprocess
import time


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline",required=True)
    parser.add_argument("--candidate",required=True)
    parser.add_argument("--probe",required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--profile",choices=["mobile-jitter","unshaped"],default="mobile-jitter")
    parser.add_argument("--key",default="/tmp/vtunnel-ssh-network/client_key")
    parser.add_argument("--repetitions",type=int,default=3,choices=range(1,7))
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    raw=args.output/"library.jsonl"
    if raw.exists(): raise SystemExit("Use a fresh output directory")
    with (args.output/"phases.log").open("w") as log:
        for repetition in range(args.repetitions):
            seed=1100+repetition
            order=["before","after"]
            if repetition%2: order.reverse()
            for label in order:
                library=args.baseline if label=="before" else args.candidate
                print(f"seed={seed} library={label} profile={args.profile}",flush=True)
                log.write(f"seed={seed} library={label} profile={args.profile}\n");log.flush()
                subprocess.run(["java","-Dvtunnel.probe.offsets=false",
                    "-Dvtunnel.probe.controlHost=127.0.0.1","-cp",library+":"+args.probe,
                    "com.jcraft.jsch.TunnelNetworkProbe","queue",label,args.profile,str(seed),
                    "upload","8","10.205.33.2","22022",args.key,"4","0","0",str(raw)],
                    stdout=log,stderr=subprocess.STDOUT,check=True)
                time.sleep(2)
    rows=[json.loads(line) for line in raw.read_text().splitlines() if line.strip()]
    if len(rows)!=2*args.repetitions or len({(r["label"],r["seed"]) for r in rows})!=len(rows):
        raise SystemExit("Incomplete or duplicate library comparison")
