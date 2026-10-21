"""Conservative offset classification. TCP ACK does not mean OpenSSH forwarded the data."""
import argparse
from collections import Counter
import json
from pathlib import Path
from summarize import summarize


def classify(sample, sequence, generation):
    remote = sample.get("remote", {})
    transport = remote.get("relay_transport", {})
    if not transport.get("available"):
        return "RELAY_OBSERVER_UNAVAILABLE"
    if transport.get("generation") != generation:
        return "CONNECTION_CHANGED"
    if remote.get("received_sequence") == sequence:
        return "DESTINATION_RECEIVED"
    packet = sample.get("range")
    if packet is None or packet.get("start") is None:
        return "NO_TRANSPORT_PACKET_YET"
    if not sample.get("local", {}).get("valid") or packet.get("failed"):
        return "INVALID_CLIENT_TRACE"
    if packet["end"] <= packet["start"]:
        return "INVALID_CLIENT_TRACE"
    if packet["writes"] != packet["completed_writes"]:
        return "CLIENT_WRITE_IN_PROGRESS"
    if transport.get("failed") or transport.get("write_eof"):
        return "RELAY_STREAM_ENDED"
    end = packet["end"]
    if transport["received_upper"] < end:
        return "BEFORE_RELAY_COPY"
    if transport["accepted_upper"] < end:
        return "RELAY_USERSPACE"
    if transport["accepted_lower"] < end:
        return "BOUNDARY_UNCERTAIN"
    if transport.get("sent_upper") is not None and transport["sent_upper"] < end:
        return "RELAY_TCP_UNSENT"
    if (transport.get("sent_lower") is not None and transport["sent_lower"] >= end
            and transport.get("acked_upper") is not None and transport["acked_upper"] < end):
        return "TCP_SENT_NOT_FULLY_ACKED"
    if transport.get("acked_lower") is not None and transport["acked_lower"] >= end:
        return "TCP_ACKED_DESTINATION_PENDING"
    return "BOUNDARY_UNCERTAIN"


def summarize_streams(rows):
    result = summarize(rows)
    for summary in result:
        group = [row for row in rows if all(row[key] == summary[key] for key in ("profile","label","direction"))]
        stages = Counter()
        timeouts = Counter()
        for row in group:
            events = {event["sequence"]: event for event in row["echo_remote_events"]}
            for sample in row["stream_samples"]:
                event = events[sample["sequence"]]
                generation = event["before"]["relay_transport"]["generation"]
                stage = classify(sample,sample["sequence"],generation)
                stages[stage] += 1
                if sample["kind"] == "timeout": timeouts[stage] += 1
        summary["stream_observation_stages"] = dict(stages)
        summary["stream_timeout_stages"] = dict(timeouts)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source",type=Path)
    parser.add_argument("output",type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.source.read_text().splitlines() if line.strip()]
    args.output.write_text(json.dumps(summarize_streams(rows),indent=2,allow_nan=False)+"\n")
