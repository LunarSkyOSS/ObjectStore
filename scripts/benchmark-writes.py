#!/usr/bin/env python3
import argparse
import concurrent.futures
import hashlib
import hmac
import http.client
import json
import os
import statistics
import threading
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import quote, urlsplit


def sign(method, path, body, host, access, secret, region):
    now = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    date = now[:8]
    digest = hashlib.sha256(body).hexdigest()
    names = "host;x-amz-content-sha256;x-amz-date"
    headers = {"Host": host, "x-amz-content-sha256": digest, "x-amz-date": now}
    canonical = (f"{method}\n{path}\n\nhost:{host}\nx-amz-content-sha256:{digest}\n"
                 f"x-amz-date:{now}\n\n{names}\n{digest}")
    scope = f"{date}/{region}/s3/aws4_request"
    message = f"AWS4-HMAC-SHA256\n{now}\n{scope}\n{hashlib.sha256(canonical.encode()).hexdigest()}"
    key = ("AWS4" + secret).encode()
    for part in (date, region, "s3", "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, message.encode(), hashlib.sha256).hexdigest()
    headers["Authorization"] = (f"AWS4-HMAC-SHA256 Credential={access}/{scope},"
                                f"SignedHeaders={names},Signature={signature}")
    return headers


def main():
    parser = argparse.ArgumentParser(description="Bounded ObjectStore PUT benchmark")
    parser.add_argument("--endpoint", default="http://127.0.0.1:9000")
    parser.add_argument("--bucket", default="objects")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--size", type=int, default=4096)
    args = parser.parse_args()
    if not 1 <= args.requests <= 5000 or not 1 <= args.concurrency <= 256 or not 1 <= args.size <= 1048576:
        parser.error("requests 1–5000, concurrency 1–256, size 1–1048576 are required")
    access, secret = os.environ["S3_ACCESS_KEY"], os.environ["S3_SECRET_KEY"]
    endpoint = urlsplit(args.endpoint)
    if endpoint.scheme not in ("http", "https") or not endpoint.hostname or endpoint.path not in ("", "/"):
        parser.error("endpoint must be an HTTP(S) origin")
    if endpoint.scheme == "http" and endpoint.hostname not in ("localhost", "127.0.0.1", "::1"):
        parser.error("use HTTPS outside localhost")
    host = endpoint.netloc
    payload = os.urandom(args.size)
    prefix = f"benchmark/{uuid.uuid4().hex}/"
    local = threading.local()
    completed = []
    lock = threading.Lock()

    def connection():
        if not hasattr(local, "connection"):
            cls = http.client.HTTPSConnection if endpoint.scheme == "https" else http.client.HTTPConnection
            local.connection = cls(endpoint.hostname, endpoint.port, timeout=30)
        return local.connection

    def request(method, key, body=b""):
        path = "/" + quote(args.bucket, safe="") + "/" + quote(key, safe="/~")
        conn = connection()
        conn.request(method, path, body, sign(method, path, body, host, access, secret, args.region))
        response = conn.getresponse()
        data = response.read()
        return response.status, data

    def put(index):
        key = prefix + str(index)
        start = time.perf_counter()
        status, body = request("PUT", key, payload)
        elapsed = time.perf_counter() - start
        if status != 200:
            raise RuntimeError(f"PUT {index} returned {status}: {body[:200]!r}")
        with lock:
            completed.append(key)
        return elapsed

    started = time.perf_counter()
    failures = []
    latencies = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futures = [pool.submit(put, index) for index in range(args.requests)]
            for future in concurrent.futures.as_completed(futures):
                try:
                    latencies.append(future.result())
                except Exception as error:
                    failures.append(str(error))
        duration = time.perf_counter() - started
        ordered = sorted(latencies)
        result = {
            "completed": len(ordered), "failed": len(failures), "seconds": round(duration, 3),
            "writes_per_second": round(len(ordered) / duration, 1),
            "median_ms": round(statistics.median(ordered) * 1000, 1) if ordered else None,
            "p95_ms": round(ordered[min(len(ordered) - 1, int(len(ordered) * .95))] * 1000, 1)
            if ordered else None, "size_bytes": args.size, "concurrency": args.concurrency,
            "prefix": prefix,
        }
        print(json.dumps(result, indent=2))
        for error in failures[:5]:
            print(error)
    finally:
        cleanup_failed = 0
        for key in completed:
            try:
                status, _ = request("DELETE", key)
                if status != 204:
                    cleanup_failed += 1
            except Exception:
                cleanup_failed += 1
        if cleanup_failed:
            print(f"Cleanup failed for {cleanup_failed} objects under {prefix}")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
