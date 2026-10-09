#!/usr/bin/env python3
import datetime
import hashlib
import hmac
import http.client
import pathlib
import sys
import urllib.parse


values = dict(line.strip().split("=", 1) for line in pathlib.Path(sys.argv[1]).read_text().splitlines()
         if line.strip() and not line.startswith("#"))
access = values["S3_ACCESS_KEY"]
secret = values["S3_SECRET_KEY"]
bucket = values.get("S3_BUCKET", "objects")
port = int(values.get("CLUSTER_HOST_PORT", "9001"))
if not 1 <= port <= 65535:
    raise ValueError("CLUSTER_HOST_PORT must be between 1 and 65535")
host = f"127.0.0.1:{port}"


def sign(key, message):
    return hmac.new(key, message.encode(), hashlib.sha256).digest()


def request(method, path, body=b"", extra=None):
    extra = extra or {}
    date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    stamp = date[:8]
    digest = hashlib.sha256(body).hexdigest()
    uri, _, query = path.partition("?")
    canonical = "&".join(f"{urllib.parse.quote(k, safe='~-._')}={urllib.parse.quote(v, safe='~-._')}"
                         for k, v in sorted(urllib.parse.parse_qsl(query, keep_blank_values=True)))
    headers = {"host": host, "x-amz-content-sha256": digest, "x-amz-date": date, **extra}
    signed_names = ";".join(sorted(headers))
    canonical_headers = "".join(f"{name}:{headers[name]}\n" for name in sorted(headers))
    canonical_request = f"{method}\n{uri}\n{canonical}\n{canonical_headers}\n{signed_names}\n{digest}"
    scope = f"{stamp}/us-east-1/s3/aws4_request"
    to_sign = f"AWS4-HMAC-SHA256\n{date}\n{scope}\n{hashlib.sha256(canonical_request.encode()).hexdigest()}"
    key = sign(sign(sign(sign(("AWS4" + secret).encode(), stamp), "us-east-1"), "s3"), "aws4_request")
    signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
    headers["authorization"] = (f"AWS4-HMAC-SHA256 Credential={access}/{scope},"
                                f"SignedHeaders={signed_names},Signature={signature}")
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    try:
        connection.request(method, path, body=body if method == "PUT" else None, headers=headers)
        response = connection.getresponse()
        return response.status, response.read(), response.headers
    finally:
        connection.close()


if len(sys.argv) > 2 and sys.argv[2] == "survivor":
    status, content, _ = request("GET", f"/{bucket}/cluster-test/survivor")
    assert status == 200 and content == b"acknowledged object survives node loss", (status, content)
    print("Cluster surviving-replica HTTP read passed")
    sys.exit(0)

if len(sys.argv) > 4 and sys.argv[2] == "status":
    key = urllib.parse.quote(sys.argv[3], safe="/")
    expected = int(sys.argv[4])
    status, _, _ = request("GET", f"/{bucket}/{key}")
    assert status == expected, (status, expected)
    print(f"Cluster GET status passed: {status}")
    sys.exit(0)


key = f"/{bucket}/cluster-test/http.txt"
body = b"HTTP gateway integration test"
status, _, _ = request("PUT", key, body)
assert status == 200, status
status, content, _ = request("GET", key)
assert status == 200 and content == body, (status, content)
status, content, _ = request("GET", key, extra={"range": "bytes=5-11"})
assert status == 206 and content == body[5:12], (status, content)
status, content, _ = request("HEAD", key)
assert status == 200 and not content, status
status, content, _ = request("GET", f"/{bucket}?list-type=2&prefix=cluster-test%2F")
assert status == 200 and b"cluster-test/http.txt" in content, (status, content)
status, _, _ = request("DELETE", key)
assert status == 204, status
status, _, _ = request("GET", key)
assert status == 404, status
print("Cluster HTTP tests passed: signed PUT, GET, range, HEAD, LIST, DELETE")
