# ObjectStore Java client

A small, synchronous S3-compatible client built with the JDK alone. It uses path-style URLs and AWS Signature Version 4. The code is covered by the repository's MIT license.

## Build and test

Requires JDK 21 or newer. No Maven, Gradle, or third-party Java libraries are needed.

```sh
bash client/scripts/test.sh
bash client/scripts/build.sh
```

Run these commands from the ObjectStore repository root. The second command writes `client/dist/objectstore-client.jar` and `client/dist/javadoc/`.

The [AWT image manager](examples/README.md) is a small drag-and-drop app for trying uploads, listing, previews, downloads, and deletes against an existing bucket.

## Use

```java
import cloud.lunarsky.objectstore.client.ObjectStorageClient;
import cloud.lunarsky.objectstore.client.ObjectStorageClientBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;

try (ObjectStorageClient storage = new ObjectStorageClientBuilder()
        .endpoint(URI.create("https://storage.example.com"))
        .region("us-east-1")
        .credentials(accessKey, secretKey)
        .build()) {
    storage.putObject("photos", "hello.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain");
    try (ObjectStorageClient.ObjectData data = storage.getObject("photos", "hello.txt")) {
        data.body().transferTo(System.out);
    }
}
```

`getObject` returns an open stream. Close it even if you do not read every byte. The client requires HTTPS unless you explicitly call `allowInsecureHttp()` for a trusted local endpoint.

## Scope

This version implements single-request object PUT, GET, HEAD, DELETE, ListObjectsV2 pages, standard S3 multipart initiation/part upload/list/complete/abort, unfinished-upload listing, bucket/object ACL GET and PUT, and service detection. ACLs are S3 ACL requests, not a calculation of effective permissions from IAM policies or bucket policies. An S3 service with ACLs disabled can reject them; ObjectStore implements a limited ACL subset.

`getCapabilities()` reads ObjectStore's signed `GET /_objectstore/capabilities` response when available. It reports implemented S3 operation names, service version, storage mode, and configured limits. A listed operation means the service implements it; it does not establish that the current key may perform it or that every AWS option is supported. Older ObjectStore builds fall back to `/health` and leave unverified operations `UNKNOWN`. The response is not cached.

For a generic S3 endpoint, successful operations on this client are recorded as `SUPPORTED`; all untested operations remain `UNKNOWN`. Call `probeReadOnlyCapabilities(bucket, existingObjectKey)` to opt into safe read probes. An S3 denial or missing object leaves that operation `UNKNOWN`, not `UNSUPPORTED`. The client never infers features from a hostname or vendor header. The older `/health` identity fallback is self-reported, not cryptographic attestation.

Multipart upload sessions expose their bucket, key, and upload ID so a caller can persist them and inspect already uploaded parts after a restart. `uploadFileParts` sends a file in sequential parts and leaves completion to the caller. If it fails, the upload remains open for retry or explicit abort; keep the returned upload ID and do not change the source file.

The client does not yet implement presigned URLs, credential providers, automatic retries, region redirects, or streaming uploads of unknown length. A single-request file upload hashes the file before sending it; do not modify the file while the upload runs. The client does not retry writes because a lost response can leave their outcome uncertain.

## Errors

- `ObjectStorageException`: an HTTP error, with status, S3 error code, request ID, and a retryability hint.
- `TransportException`: connection or I/O failure. A write may already have completed.
- `ProtocolException`: an unexpected or malformed successful response.

The client does not include access keys, secret keys, or response bodies in exception messages.
