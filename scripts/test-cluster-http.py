#!/usr/bin/env python3
import datetime
import base64
import hashlib
import hmac
import http.client
import pathlib
import re
import sys
import urllib.parse
import zlib


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
        connection.request(method, path, body=body if method in ("PUT", "POST") else None, headers=headers)
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

copy_source = f"/{bucket}/cluster-test/copy-source.txt"
copy_target = f"/{bucket}/cluster-test/copied.txt"
body = b"cluster copy and checksum test"
crc32 = base64.b64encode(zlib.crc32(body).to_bytes(4, "big")).decode()
md5 = base64.b64encode(hashlib.md5(body).digest()).decode()
status, _, headers = request("PUT", copy_source, body,
                             {"content-type": "text/plain", "content-md5": md5,
                              "x-amz-checksum-crc32": crc32,
                              "x-amz-sdk-checksum-algorithm": "CRC32"})
assert status == 200 and headers["x-amz-checksum-crc32"] == crc32, status
status, content, _ = request("PUT", copy_source, body,
                             {"content-md5": base64.b64encode(bytes(16)).decode()})
assert status == 400 and b"BadDigest" in content, (status, content)
status, content, _ = request("GET", copy_source)
assert status == 200 and content == body, (status, content)
status, content, _ = request("PUT", copy_target, extra={"x-amz-copy-source": copy_source})
assert status == 200 and b"<CopyObjectResult>" in content, (status, content)
status, content, headers = request("GET", copy_target)
assert status == 200 and content == body and headers["content-type"] == "text/plain", (status, content)
status, _, _ = request("DELETE", copy_source)
assert status == 204, status
status, _, _ = request("DELETE", copy_target)
assert status == 204, status

multipart_key = f"/{bucket}/cluster-test/http-multipart.txt"
status, content, _ = request("POST", multipart_key + "?uploads")
assert status == 200, (status, content)
match = re.search(rb"<UploadId>([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})</UploadId>",
                  content[:8192])
assert match, content
upload_id = match.group(1).decode("ascii")
part_etags = []
for number, part in enumerate((b"hello ", b"world"), start=1):
    checksum = base64.b64encode(hashlib.sha1(part).digest()).decode()
    status, _, headers = request("PUT", multipart_key + f"?partNumber={number}&uploadId={upload_id}",
                                 part, {"x-amz-checksum-sha1": checksum})
    assert status == 200, status
    assert headers["x-amz-checksum-sha1"] == checksum, headers
    part_etags.append(headers["etag"])
status, content, _ = request("GET", multipart_key + f"?uploadId={upload_id}&max-parts=1")
assert status == 200 and b"<IsTruncated>true</IsTruncated>" in content, (status, content)
status, content, _ = request("GET", f"/{bucket}?uploads&prefix=cluster-test%2Fhttp-multipart")
assert status == 200 and upload_id.encode() in content, (status, content)
completion = "<CompleteMultipartUpload>" + "".join(
    f"<Part><PartNumber>{number}</PartNumber><ETag>{etag}</ETag></Part>"
    for number, etag in enumerate(part_etags, start=1)) + "</CompleteMultipartUpload>"
status, content, _ = request("POST", multipart_key + f"?uploadId={upload_id}", completion.encode(),
                             {"content-type": "application/xml"})
assert status == 200 and b"<CompleteMultipartUploadResult>" in content, (status, content)
status, content, _ = request("GET", multipart_key)
assert status == 200 and content == b"hello world", (status, content)
status, content, _ = request("GET", f"/{bucket}?uploads&prefix=cluster-test%2Fhttp-multipart")
assert status == 200 and upload_id.encode() not in content, (status, content)
print("Cluster HTTP tests passed: signed objects, copies, checksums, and multipart operations")
