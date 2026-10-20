"""Summarize per-execution metrics; never pool RTT samples from different executions."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def distribution(values):
    values = [value for value in values if value is not None]
    if not values:
        return None
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        metric = "remote_upload_mbps" if row["direction"] == "upload" else "mbps"
        if row[metric] < 0 or row.get("upload_after", 0) < row.get("upload_before", 0):
            raise ValueError("Invalid remote-counter sample; rerun the paired measurement")
        groups[row["profile"], row["label"], row["direction"]].append(row)
    result = []
    for (profile, label, direction), runs in sorted(groups.items()):
        delivered = "remote_upload_mbps" if direction == "upload" else "mbps"
        normalized_cpu = []
        for row in runs:
            mib = row[delivered] * 1e6 * row["seconds"] / 8 / 1048576
            if mib > 0 and row["cpu_ms"] >= 0:
                normalized_cpu.append(row["cpu_ms"] / mib)
        summary = {
            "profile": profile, "label": label, "direction": direction,
            "executions": len(runs), "seeds": [row["seed"] for row in runs],
            "timeouts_total": sum(row["timeouts"] for row in runs),
            "load_errors_total": sum(row["load_errors"] for row in runs),
            "goodput_mbps": distribution(row[delivered] for row in runs),
            "cpu_ms_per_mib": distribution(normalized_cpu),
        }
        for metric in ("samples", "seconds", "cpu_ms", "rtt_p50_ms", "rtt_p95_ms", "adjacent_rtt_jitter_ms"):
            summary[metric] = distribution(row[metric] for row in runs)
        result.append(summary)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.input.read_text().splitlines() if line.strip()]
    args.output.write_text(json.dumps(summarize(rows), indent=2, allow_nan=False) + "\n")
