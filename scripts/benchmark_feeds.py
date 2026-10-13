#!/usr/bin/env python3
"""Compare the app's feeds; Python 3 + curl, no third-party packages.

Run outside the restricted Codex terminal:
  python3 scripts/benchmark_feeds.py --rounds 12 --interval 20

Each round probes all three feeds concurrently, with a hard five-second total
network deadline. This measures this computer/network, not the watch. Freshness
uses the same fields and conservative timestamp rules as the app. HTTP latency
excludes local JSON parsing. Raw responses and observations are saved for audit.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import shutil
import statistics
import subprocess
import time

FAVORITES = ("7046", "7012", "7006", "7013")
SOURCES = (
    ("cyclocity", "https://api.cyclocity.fr/contracts/lyon/gbfs/v3/station_status.json"),
    ("lyon_gbfs", "https://download.data.grandlyon.com/files/rdata/jcd_jcdecaux.jcdvelov/station_status.json"),
    ("lyon_geojson", "https://data.grandlyon.com/geoserver/metropole-de-lyon/ows"
     "?SERVICE=WFS&SRSNAME=EPSG:4326&VERSION=2.0.0&outputFormat=application/json"
     "&request=GetFeature&typename=metropole-de-lyon:jcd_jcdecaux.jcdvelov"),
)
MAX_BYTES = 2 * 1024 * 1024


def timestamp(value):
    try:
        if type(value) in (int, float) and math.isfinite(value) and float(value).is_integer():
            return float(value)
        if isinstance(value, str):
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            return parsed.timestamp() if parsed.tzinfo else None
    except (ValueError, OverflowError):
        pass
    return None


def flag(value):
    if type(value) is bool:
        return value
    if type(value) is int and value in (0, 1):
        return bool(value)
    return None


def observations(body, source, received_at):
    root = json.loads(body)
    selected = {}
    if source == "lyon_geojson":
        for feature in root["features"]:
            row = feature.get("properties") or {}
            key = str(row.get("number"))
            if key in FAVORITES:
                if key in selected:
                    raise ValueError("duplicate favourite " + key)
                selected[key] = row
        published = [timestamp(row.get("last_update_fme")) or timestamp(row.get("last_update")) or 0
                     for row in selected.values()]
        feed_time = max(published, default=0)
    else:
        if not str(root.get("version", "")).startswith(("2.", "3.")):
            raise ValueError("unsupported GBFS version")
        feed_time = timestamp(root.get("last_updated"))
        if not feed_time or feed_time <= 0:
            raise ValueError("missing feed timestamp")
        for row in root["data"]["stations"]:
            if not isinstance(row, dict):
                continue
            key = str(row.get("station_id"))
            if key in FAVORITES:
                if key in selected:
                    raise ValueError("duplicate favourite " + key)
                selected[key] = row
    if not selected:
        raise ValueError("no favourite stations present")
    result = {}
    for key in FAVORITES:
        row = selected.get(key)
        if row is None:
            result[key] = {"state": "missing", "fresh": False}
            continue
        if source == "lyon_geojson":
            reported = timestamp(row.get("last_update"))
            count = row.get("available_bike_stands")
            status = row.get("status")
            installed = True if status in ("OPEN", "CLOSED") else None
            returning = status == "OPEN" if installed is not None else None
        else:
            reported = timestamp(row.get("last_reported"))
            count = row.get("num_docks_available")
            installed, returning = flag(row.get("is_installed")), flag(row.get("is_returning"))
        valid_time = all(t is not None and 0 < t <= received_at + 60 for t in (reported, feed_time))
        closed = installed is False or returning is False
        valid_count = type(count) in (int, float) and math.isfinite(count) and float(count).is_integer() and 0 <= count <= 2147483647
        known = valid_time and (closed or (installed is True and returning is True and valid_count))
        effective_age = max(0, received_at - min(reported, feed_time, received_at)) if valid_time else None
        fresh = bool(known and effective_age < 300)
        result[key] = {
            "count": count if valid_count else None,
            "reported_at": reported,
            "feed_updated_at": feed_time,
            "observation_age_s": received_at - reported if reported else None,
            "effective_age_s": effective_age,
            "known": bool(known), "fresh": fresh,
            "state": "invalid" if not known else "stale" if not fresh else "closed" if closed else "open",
        }
    return result


def probe(source, round_number, directory):
    key, url = source
    stem = directory / f"{round_number:02d}-{key}"
    body_path = stem.with_suffix(".body")
    header_path = stem.with_suffix(".headers")
    record = {"round": round_number, "source": key, "url": url, "started_at": time.time()}
    command = ["curl", "-q", "--silent", "--show-error", "--compressed", "--http1.1",
               "--header", "Accept: application/json", "--header", "Accept-Encoding: gzip",
               "--user-agent", "VelovMontre/0.1.3",
               "--connect-timeout", "5", "--max-time", "5", "--location", "--max-redirs", "3",
               "--proto", "=https", "--proto-redir", "=https", "--max-filesize", str(MAX_BYTES),
               "--dump-header", str(header_path), "--output", str(body_path),
               "--write-out", "%{json}", url]
    started = time.monotonic()
    try:
        process = subprocess.run(command, capture_output=True, text=True, timeout=7)
        record.update(exit_code=process.returncode, error=process.stderr.strip())
        try:
            metrics = json.loads(process.stdout)
        except json.JSONDecodeError:
            metrics = {}
        # Do not save curl's full metadata: it may include local network addresses.
        for name in ("http_code", "time_namelookup", "time_connect", "time_appconnect",
                     "time_starttransfer", "time_total", "size_download", "num_redirects", "url_effective"):
            record[name] = metrics.get(name)
    except subprocess.TimeoutExpired:
        record.update(exit_code=124, error="local watchdog exceeded 7 seconds")
    record["wall_s"] = time.monotonic() - started
    record["received_at"] = time.time()
    record["within_5s"] = (record["exit_code"] == 0 and record.get("http_code") == 200
                            and record.get("time_total") is not None and record["time_total"] <= 5)
    record["parsed"] = False
    record["stations"] = {}
    if record["within_5s"]:
        try:
            if body_path.stat().st_size > MAX_BYTES:
                raise ValueError("decoded response exceeds app's two-megabyte limit")
            record["stations"] = observations(body_path.read_bytes(), key, record["received_at"])
            record["parsed"] = True
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            record["parse_error"] = str(error)
    return record


def summary(records, planned):
    lines = ["Vélo’v endpoint experiment", "=" * 27,
             f"Completed {len(set(r['round'] for r in records))}/{planned} rounds.",
             "Five seconds includes DNS, TCP, TLS, redirects and the full response body.",
             "Three concurrent requests per round; fresh connection per request, HTTP/1.1 + gzip.",
             "Results apply to this computer/network; concurrency and curl differ from the watch.",
             "Fresh = valid known state with observation AND feed timestamps less than 300 s old.",
             "", "Endpoint        N  HTTP200<=5s  Parsed  All4fresh  Median(s)  Max(s)"]
    for key, _ in SOURCES:
        rows = [r for r in records if r["source"] == key]
        successful = [r for r in rows if r["within_5s"]]
        times = [r["time_total"] for r in successful]
        all_fresh = sum(r["parsed"] and all(s.get("fresh") for s in r["stations"].values()) for r in rows)
        timing = f"{statistics.median(times):9.3f}  {max(times):6.3f}" if times else "      n/a     n/a"
        lines.append(f"{key:15} {len(rows):2}  {len(successful):3}/{len(rows):<3}      "
                     f"{sum(r['parsed'] for r in rows):3}     {all_fresh:3}       {timing}")
        failures = {}
        for row in rows:
            reason = row.get("parse_error") or row.get("error")
            if reason:
                failures[reason] = failures.get(reason, 0) + 1
        for reason, count in failures.items():
            lines.append(f"  {count}x {reason}")
        for station in FAVORITES:
            data = [r["stations"].get(station, {}) for r in rows]
            ages = [s["effective_age_s"] for s in data if s.get("known") and s.get("effective_age_s") is not None]
            median = f"{statistics.median(ages):.1f}s" if ages else "n/a"
            lines.append(f"  {station}: known {sum(bool(s.get('known')) for s in data)}/{len(rows)}, "
                         f"fresh {sum(bool(s.get('fresh')) for s in data)}/{len(rows)}, median data age {median}")
    lines += ["", "Freshness comparisons within the same round (observation timestamp; ties allowed):"]
    for station in FAVORITES:
        wins = {key: 0 for key, _ in SOURCES}
        compared = 0
        for number in sorted({r["round"] for r in records}):
            candidates = [(r["source"], r["stations"].get(station, {})) for r in records if r["round"] == number]
            candidates = [(key, s) for key, s in candidates if s.get("known")]
            if len(candidates) < 2:
                continue
            compared += 1
            newest = max(s["reported_at"] for _, s in candidates)
            for key, station_data in candidates:
                wins[key] += station_data["reported_at"] == newest
        lines.append(f"  {station}: {compared} comparable rounds; newest/tied {wins}")
    lines += ["", "Timestamp consistency (counts changing under one reported_at value):"]
    anomaly = False
    for source, _ in SOURCES:
        for station in FAVORITES:
            counts_at_time = {}
            for row in records:
                if row["source"] != source:
                    continue
                data = row["stations"].get(station, {})
                if data.get("known") and data.get("count") is not None:
                    counts_at_time.setdefault(data["reported_at"], set()).add(data["count"])
            for reported, counts in counts_at_time.items():
                if len(counts) > 1:
                    anomaly = True
                    lines.append(f"  {source} / {station}: counts {sorted(counts)}, unchanged timestamp {reported}")
    if anomaly:
        lines.append("WARNING: old reported timestamps do not prove the counts themselves are old.")
    else:
        lines.append("  No such change observed in this short sample.")
    lines += ["", "Latency statistics exclude errors and timeouts; timed-out requests are censored at 5 s.",
              "A short experiment cannot establish long-term reliability or watch latency."]
    if not any(r["parsed"] for r in records):
        lines.append("NO VALID DATA: no endpoint ranking or freshness conclusion is justified.")
    else:
        lines.append("Review success rates, four-station freshness and latency together before choosing an order.")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rounds", type=int, default=12)
    parser.add_argument("--interval", type=float, default=20, help="minimum seconds between round starts")
    parser.add_argument("--output", type=Path, help="new output directory (must not already exist)")
    args = parser.parse_args()
    if not 1 <= args.rounds <= 60 or not math.isfinite(args.interval) or args.interval < 10:
        parser.error("use 1–60 rounds and an interval of at least 10 seconds")
    if not shutil.which("curl"):
        parser.error("curl is required")
    directory = args.output or Path("experiments") / datetime.now(timezone.utc).strftime("feeds-%Y%m%dT%H%M%SZ")
    directory.mkdir(parents=True, exist_ok=False)
    records = []
    print(f"Writing results to {directory.resolve()}", flush=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        for number in range(1, args.rounds + 1):
            started = time.monotonic()
            batch = list(pool.map(lambda source: probe(source, number, directory), SOURCES))
            records.extend(batch)
            with (directory / "samples.jsonl").open("a") as output:
                for record in batch:
                    output.write(json.dumps(record, ensure_ascii=False) + "\n")
            (directory / "summary.txt").write_text(summary(records, args.rounds))
            print(f"Round {number}/{args.rounds}: " + "; ".join(
                f"{r['source']}={r.get('time_total', r['wall_s'])}s, "
                f"{'valid' if r['parsed'] else 'unusable'}" for r in batch), flush=True)
            if all(r["exit_code"] == 6 for r in batch):
                print("All three DNS lookups failed. Stopping: this network cannot test the feeds.", flush=True)
                break
            if number < args.rounds:
                time.sleep(max(0, args.interval - (time.monotonic() - started)))
    print(summary(records, args.rounds))


if __name__ == "__main__":
    main()
