"""Classify destination observations without mixing Android and server monotonic clocks."""
import argparse
from collections import Counter
import json
from pathlib import Path
from summarize import summarize, distribution


def classify_echo(sequence, before, after):
    result = dict(stage="OBSERVER_UNAVAILABLE", receive_after_marker_ms=None,
                  service_ms=None, sent_age_ms=None)
    if not after.get("available", False):
        return result
    if after.get("received_sequence") != sequence:
        result["stage"] = "NOT_RECEIVED_AT_DESTINATION"
        return result
    result["stage"] = "RECEIVED_NOT_SENT"
    if before.get("server_now_ns") is not None:
        result["receive_after_marker_ms"] = (after["received_ns"] - before["server_now_ns"]) / 1e6
    if after.get("sent_sequence") == sequence:
        result["stage"] = "SENT_AT_DESTINATION"
        result["service_ms"] = (after["sent_ns"] - after["received_ns"]) / 1e6
        result["sent_age_ms"] = (after["server_now_ns"] - after["sent_ns"]) / 1e6
    if any(value is not None and value < 0 for key, value in result.items() if key != "stage"):
        raise ValueError("Server timestamps regressed")
    return result


def queue_summary(rows):
    summaries = summarize(rows)
    for summary in summaries:
        group = [row for row in rows if all(row[key] == summary[key]
                 for key in ("profile", "label", "direction"))]
        timeout_destinations = Counter()
        receives, services, tails = [], [], []
        for row in group:
            events = row["echo_remote_events"]
            if len(events) != len(row["echo_events"]):
                raise ValueError("Mismatched echo observations")
            for local, remote in zip(row["echo_events"], events):
                result = classify_echo(remote["sequence"], remote["before"], remote["after"])
                if local["timeout"]:
                    timeout_destinations[result["stage"]] += 1
                receives.append(result["receive_after_marker_ms"])
                services.append(result["service_ms"])
            if row["tail_observation"]:
                last = events[-1]
                result = classify_echo(last["sequence"], last["before"], row["tail_observation"]["remote"])
                tails.append(dict(seed=row["seed"], **result,
                                  observed_after_stop_ms=row["tail_observation"]["after_load_stop_ms"]))
        summary["timeout_destination_stages"] = dict(timeout_destinations)
        summary["destination_receive_after_marker_ms"] = distribution(receives)
        summary["destination_service_ms"] = distribution(services)
        summary["tail_observations"] = tails
    return summaries


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.source.read_text().splitlines() if line.strip()]
    args.output.write_text(json.dumps(queue_summary(rows), indent=2, allow_nan=False) + "\n")
