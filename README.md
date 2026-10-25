![LunarSky ObjectStore banner](assets/objectstore-banner.svg)

# ObjectStore

Small S3-compatible object storage for LunarSky.

Source: [GitHub](https://github.com/LunarSkyOSS/ObjectStore) · [Gitea mirror](https://git.lunarsky.cloud/admin/ObjectStore) · [Docs v0.0.8](https://docs.lunarsky.cloud/objectstore/v0.0.8/)

**Development software. Do not use it for production data.** It is provided as is, without warranty, under the [MIT License](LICENSE).

[![Stage: development](assets/badge-stage.svg)](OPERATIONS.md#limits-and-safety) [![License: MIT](assets/badge-license.svg)](LICENSE) [![Runtime: Java 21](assets/badge-java.svg)](Dockerfile) [![S3 API: partial support](assets/badge-api.svg)](#s3-api-support-checklist) [![CodeFactor](https://www.codefactor.io/repository/github/lunarskyoss/objectstore/badge)](https://www.codefactor.io/repository/github/lunarskyoss/objectstore)

The [v0.0.8 docs](https://docs.lunarsky.cloud/objectstore/v0.0.8/) cover released behavior, the [ObjectStore Java client](https://docs.lunarsky.cloud/objectstore/v0.0.8/client/), and operations. This README describes the development branch; [TESTS.md](TESTS.md) records test coverage and limits.

## Capability checklist

ObjectStore creates the default bucket at startup. All buckets share one capacity limit.

- ✅ Persistent single-node storage with checksum verification
- ✅ Optional dm-crypt-backed data mounts with startup guards
- ✅ Configurable size limits and CLI verification
- ✅ Local cluster prototype with host-aware placement and optional node TLS
- ✅ Opt-in repair, rebalance, guarded garbage collection, and metadata backup
- ✅ Cold snapshot and restore tooling for the local Compose stacks
- ✅ Manual [two-machine durability and restore drill](tests/two-host/README.md)
- ⬜ Production multi-server deployment and metadata failover

Legacy single-node objects remain readable but stay out of listings until overwritten because their keys were not stored.

## S3 API support checklist

- ✅ Header-based and presigned-query AWS Signature Version 4 authentication
- ✅ Path-style and configurable virtual-hosted-style bucket addresses
- ✅ `PutObject`, `GetObject`, `HeadObject`, and `DeleteObject` in both modes
- ✅ Single-range GET and `ListObjectsV2` in both modes
- ✅ SHA-256 payload verification and `x-amz-checksum-sha256` in both modes
- ✅ `Content-MD5` and CRC32, CRC32C, CRC64NVME, XXHash64, XXHash3, XXHash128, SHA-1, SHA-256, SHA-512, and MD5 checksum headers on `PutObject` and `UploadPart`
- ✅ `CreateMultipartUpload`, `UploadPart`, `ListParts`, `CompleteMultipartUpload`, `AbortMultipartUpload`, and `ListMultipartUploads` in both modes
- ✅ Presigned URLs and signed streaming Signature V4 uploads, including signed checksum trailers
- ✅ `CreateBucket`, `HeadBucket`, `DeleteBucket`, and `ListBuckets` in both modes
- ✅ `CopyObject` across owned buckets, with `COPY` and `REPLACE` content-type and user-metadata behavior
- ✅ User metadata on object and multipart uploads; object tags on upload, copy, and the tagging subresource
- ✅ CRC64NVME and XXHash checksums, checksum trailers, and persisted object checksum metadata
- ✅ Bucket versioning with retained versions, delete markers, and version-specific reads, copies, deletes, and tags in both modes
- ✅ Basic bucket and object ACL grants, public reads, and multiple access-key identities
- ⬜ Full AWS ACL ownership controls, email grantees, and bucket policies

This is an S3 subset. Unsupported operations and Amazon-specific headers are rejected. Bucket names use three to 63 lowercase letters, digits, or hyphens; the default bucket cannot be deleted. Bucket creation accepts an empty body for the configured region. Tags do not control access.

Versioning supports enabled and suspended states. Old versions and delete markers count toward capacity; deleting a specific version is permanent. Lifecycle expiration, MFA Delete, and `ListObjectVersions` delimiter grouping are unsupported. User metadata accepts printable ASCII only.

`PutObject` checksums are validated and retained; without one, ObjectStore stores CRC64NVME, including for completed multipart uploads. Request `x-amz-checksum-mode: ENABLED` on GET or HEAD to receive it. `UploadPart` checksums are validated but are not returned by `ListParts` or combined into a multipart checksum. Presigned URLs expire within seven days. Signed `aws-chunked` uploads support one checksum trailer; temporary credentials and other streaming modes are unsupported. Copies obey the object size limit.

## Single-node setup

Requires Docker Compose. Copy `.env.example` to `.env`, then set `S3_ACCESS_KEY` and `S3_SECRET_KEY` to unique values. The access key must be at least 16 alphanumeric characters; the secret must be at least 32 characters.

```sh
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:9000/health
```

Port 9000 binds to localhost. Data stays in the `object-data` Docker volume. `docker compose down -v` deletes that volume.

The standalone defaults are 128 MiB per object and 2 GiB total. Set `MAX_OBJECT_BYTES` and `MAX_TOTAL_BYTES` in `.env` to change them. Incomplete multipart uploads consume space until aborted.

## Operations

The [operations guide](OPERATIONS.md) covers encrypted mounts, access keys and ACLs, the CLI, cluster setup and maintenance, migration, metadata routing, and public-client limits. Read it before exposing an endpoint or upgrading a cluster.

## Java client

The [JDK-only Java client](client/README.md) works with ObjectStore and other S3-compatible endpoints. Version 0.0.8 is available from `https://repo.lunarsky.cloud/` without an account. Add that URL as a Maven repository, then use `cloud.lunarsky:objectstore-client:0.0.8`.

Gradle (Kotlin DSL):

```kotlin
repositories {
    maven { url = uri("https://repo.lunarsky.cloud/") }
}
dependencies {
    implementation("cloud.lunarsky:objectstore-client:0.0.8")
}
```

Maven (`pom.xml`):

```xml
<repositories>
  <repository>
    <id>lunarsky</id>
    <url>https://repo.lunarsky.cloud/</url>
  </repository>
</repositories>
<dependencies>
  <dependency>
    <groupId>cloud.lunarsky</groupId>
    <artifactId>objectstore-client</artifactId>
    <version>0.0.8</version>
  </dependency>
</dependencies>
```

See [client installation and examples](https://docs.lunarsky.cloud/objectstore/v0.0.8/client/install). The [AWT image manager](client/examples/README.md) is a small desktop example.

## Rust client

The [Rust adapter](rust-client/README.md) configures the official AWS SDK for path-style ObjectStore endpoints and streams file transfers. It is local source code, not a published crate.

## Disclaimer

ObjectStore is independent software. It is not affiliated with, sponsored by, or endorsed by Amazon or Amazon Web Services (AWS). “S3-compatible” describes only the API subset listed above.

Keep independent backups; data loss is possible. The software is provided “as is” under the [MIT License](LICENSE). To the extent permitted by applicable law, the authors and maintainers are not responsible for data loss or other damages arising from its use.

## AI contributions

AI tools assisted with parts of this project. Maintainers review releases and remain responsible for what ships.
