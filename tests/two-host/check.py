#!/usr/bin/env python3
import argparse
import datetime
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import time
import urllib.parse


def load_env(path):
    return dict(line.split("=", 1) for line in Path(path).read_text().splitlines()
                if line and not line.startswith("#"))


def sign(key, value):
    return hmac.new(key, value.encode(), hashlib.sha256).digest()


def request(env, method, key, body=b""):
    port = int(env.get("CLUSTER_HOST_PORT", "9003"))
    host = f"127.0.0.1:{port}"
    path = "/durability/" + urllib.parse.quote(key, safe="/-_.~")
    date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    stamp = date[:8]
    digest = hashlib.sha256(body).hexdigest()
    headers = {"host": host, "x-amz-content-sha256": digest, "x-amz-date": date}
    signed = ";".join(sorted(headers))
    canonical_headers = "".join(f"{name}:{headers[name]}\n" for name in sorted(headers))
    canonical = f"{method}\n{path}\n\n{canonical_headers}\n{signed}\n{digest}"
    scope = f"{stamp}/us-east-1/s3/aws4_request"
    to_sign = f"AWS4-HMAC-SHA256\n{date}\n{scope}\n{hashlib.sha256(canonical.encode()).hexdigest()}"
    key_bytes = ("AWS4" + env["S3_SECRET_KEY"]).encode()
    for component in (stamp, "us-east-1", "s3", "aws4_request"):
        key_bytes = sign(key_bytes, component)
    signature = hmac.new(key_bytes, to_sign.encode(), hashlib.sha256).hexdigest()
    headers["authorization"] = (f"AWS4-HMAC-SHA256 Credential={env['S3_ACCESS_KEY']}/{scope},"
                                f"SignedHeaders={signed},Signature={signature}")
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=40)
    try:
        connection.request(method, path, body=body if method == "PUT" else None, headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def payload(key, length):
    return hashlib.shake_256(key.encode()).digest(length)


def record(journal, key, body):
    entry = {"key": key, "length": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    with open(journal, "a", encoding="utf-8") as output:
        output.write(json.dumps(entry, separators=(",", ":")) + "\n")
        output.flush()
        os.fsync(output.fileno())


def put_and_record(env, journal, key, length):
    body = payload(key, length)
    try:
        status, response = request(env, "PUT", key, body)
    except (OSError, TimeoutError) as error:
        return "uncertain", str(error)
    if status == 200:
        record(journal, key, body)
        return "acknowledged", ""
    return "rejected", f"HTTP {status}: {response[:120]!r}"


def seed(env, journal):
    for key, length in (("durability/seed-small", 4096),
                        ("durability/seed-multisegment", 9 * 1024 * 1024 + 17)):
        outcome, detail = put_and_record(env, journal, key, length)
        if outcome != "acknowledged":
            raise RuntimeError(f"Seed {key}: {outcome} {detail}")
        print(f"Acknowledged {key}: {length} bytes", flush=True)


def stress(env, journal, seconds):
    end = time.monotonic() + seconds
    counts = {"acknowledged": 0, "rejected": 0, "uncertain": 0}
    sequence = 0
    nonce = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S")
    while time.monotonic() < end and sequence < 200:
        key = f"durability/stress/{nonce}-{sequence:04d}"
        outcome, detail = put_and_record(env, journal, key, 256 * 1024)
        counts[outcome] += 1
        if outcome != "acknowledged" and counts[outcome] == 1:
            print(f"{key}: {outcome} {detail}", flush=True)
        sequence += 1
        time.sleep(0.1)
    print(json.dumps(counts, sort_keys=True), flush=True)


def verify(env, journal):
    entries = [json.loads(line) for line in Path(journal).read_text().splitlines() if line]
    if not entries:
        raise RuntimeError("Journal contains no acknowledged writes")
    failures = []
    for entry in entries:
        try:
            status, body = request(env, "GET", entry["key"])
            actual = hashlib.sha256(body).hexdigest()
            if status != 200 or len(body) != entry["length"] or actual != entry["sha256"]:
                failures.append(f"{entry['key']}: HTTP {status}, {len(body)} bytes, SHA-256 {actual}")
        except (OSError, TimeoutError) as error:
            failures.append(f"{entry['key']}: {error}")
    for failure in failures:
        print(failure)
    print(f"Verified {len(entries) - len(failures)}/{len(entries)} acknowledged writes", flush=True)
    if failures:
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file")
    parser.add_argument("journal")
    parser.add_argument("action", choices=("seed", "stress", "verify"))
    parser.add_argument("--seconds", type=int, default=90)
    args = parser.parse_args()
    env = load_env(args.env_file)
    if args.action == "seed":
        seed(env, args.journal)
    elif args.action == "stress":
        stress(env, args.journal, args.seconds)
    else:
        verify(env, args.journal)


if __name__ == "__main__":
    main()
