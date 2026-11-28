import csv
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    rank = max(0, math.ceil(p / 100 * len(ordered)) - 1)
    return ordered[rank]


def fmt_ms(value):
    return "-" if value is None else f"{value:,.0f}"


def load_requests(path):
    requests = []
    with path.open() as f:
        for row in csv.DictReader(f):
            if row["metric_name"] != "http_req_duration":
                continue
            endpoint = ""
            for part in (row.get("extra_tags") or "").split("&"):
                if part.startswith("endpoint="):
                    endpoint = part.split("=", 1)[1]
            if not endpoint:
                continue
            end_ms = int(row["timestamp"])
            duration = float(row["metric_value"])
            requests.append({
                "endpoint": endpoint,
                "status": row["status"],
                "duration": duration,
                "start_ms": end_ms - duration,
            })
    return requests


def load_threads(path):
    samples = []
    if not path.exists():
        return samples
    with path.open() as f:
        for row in csv.DictReader(f):
            if row["busy_threads"]:
                samples.append((int(row["epoch_ms"]), float(row["busy_threads"])))
    return samples


def summarize(requests, endpoint):
    rows = [r for r in requests if r["endpoint"] == endpoint]
    durations = [r["duration"] for r in rows]
    statuses = Counter(r["status"] for r in rows)
    ok = statuses.get("200", 0)
    return {
        "count": len(rows),
        "statuses": dict(sorted(statuses.items())),
        "non_200_rate": None if not rows else round(1 - ok / len(rows), 4),
        "p50": percentile(durations, 50),
        "p95": percentile(durations, 95),
        "p99": percentile(durations, 99),
        "max": max(durations) if durations else None,
    }


def main():
    out_dir = Path(sys.argv[1])
    requests = load_requests(out_dir / "k6.csv")
    threads = load_threads(out_dir / "threads.csv")
    if not requests:
        raise SystemExit("no http_req_duration samples found")

    t0 = min(r["start_ms"] for r in requests)
    buckets = defaultdict(lambda: {"price": [], "price_errors": 0, "paddle_sent": 0})
    for r in requests:
        second = int((r["start_ms"] - t0) // 1000)
        bucket = buckets[second]
        if r["endpoint"] == "price_latest":
            bucket["price"].append(r["duration"])
            if r["status"] != "200":
                bucket["price_errors"] += 1
        elif r["endpoint"] == "paddle_transaction":
            bucket["paddle_sent"] += 1

    busy_by_second = defaultdict(list)
    for epoch_ms, busy in threads:
        second = int((epoch_ms - t0) // 1000)
        busy_by_second[second].append(busy)

    last_second = max(max(buckets), max(busy_by_second) if busy_by_second else 0)
    series = []
    for second in range(0, last_second + 1):
        bucket = buckets.get(second, {"price": [], "price_errors": 0, "paddle_sent": 0})
        busy = busy_by_second.get(second)
        series.append({
            "second": second,
            "price_sent": len(bucket["price"]),
            "price_p95_ms": percentile(bucket["price"], 95),
            "price_max_ms": max(bucket["price"]) if bucket["price"] else None,
            "price_non_200": bucket["price_errors"],
            "paddle_sent": bucket["paddle_sent"],
            "busy_threads": max(busy) if busy else None,
        })

    with (out_dir / "timeseries.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(series[0].keys()))
        writer.writeheader()
        for row in series:
            writer.writerow({k: ("" if v is None else round(v, 1) if isinstance(v, float) else v)
                             for k, v in row.items()})

    summary = {
        "price_latest": summarize(requests, "price_latest"),
        "paddle_transaction": summarize(requests, "paddle_transaction"),
        "max_busy_threads": max((b for _, b in threads), default=None),
        "seconds_with_busy_at_max": sum(1 for row in series if row["busy_threads"] == 20.0),
    }
    (out_dir / "analysis.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))

    lines = ["| 구간(초) | 시세 요청 수 | 시세 p95(ms) | 시세 최대(ms) | 시세 실패 | 결제 요청 수 | 바쁜 스레드 최대 |",
             "|---|---|---|---|---|---|---|"]
    for start in range(0, len(series), 5):
        chunk = series[start:start + 5]
        price_values = [r for r in requests if r["endpoint"] == "price_latest"
                        and start <= (r["start_ms"] - t0) // 1000 < start + 5]
        durations = [r["duration"] for r in price_values]
        busy_values = [row["busy_threads"] for row in chunk if row["busy_threads"] is not None]
        lines.append("| {}~{} | {} | {} | {} | {} | {} | {} |".format(
            start, start + len(chunk),
            len(durations),
            fmt_ms(percentile(durations, 95)),
            fmt_ms(max(durations) if durations else None),
            sum(row["price_non_200"] for row in chunk),
            sum(row["paddle_sent"] for row in chunk),
            "-" if not busy_values else f"{max(busy_values):.0f}",
        ))
    (out_dir / "timeseries-5s.md").write_text("\n".join(lines) + "\n")

    print(json.dumps(summary, indent=2, ensure_ascii=False))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
