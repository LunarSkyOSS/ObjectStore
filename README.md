![LunarSky ObjectStore banner](assets/objectstore-banner.svg)

# ObjectStore

Small S3-compatible object storage for LunarSky.

Source: [GitHub](https://github.com/LunarSkyOSS/ObjectStore) · [Gitea mirror](https://git.lunarsky.cloud/admin/ObjectStore)

**Development software. Do not use it for production data.** It is provided as is, without warranty, under the [MIT License](LICENSE).

[![Stage: development](assets/badge-stage.svg)](#limits-and-safety) [![License: MIT](assets/badge-license.svg)](LICENSE) [![Runtime: Java 21](assets/badge-java.svg)](Dockerfile) [![S3 API: partial support](assets/badge-api.svg)](#s3-api-support-checklist) [![CodeFactor](https://www.codefactor.io/repository/github/lunarskyoss/objectstore/badge)](https://www.codefactor.io/repository/github/lunarskyoss/objectstore)

## Contents

- [Capability checklist](#capability-checklist)
- [S3 API support checklist](#s3-api-support-checklist)
- [Tests](TESTS.md)
- [Single-node setup](#single-node-setup)
- [Access keys and ACLs](#access-keys-and-acls)
- [CLI and tests](#cli-and-tests)
- [Capability discovery](#capability-discovery)
- [Java client](#java-client)
- [Local cluster prototype](#local-cluster-prototype)
- [Node transport TLS](#node-transport-tls)
- [Metadata primary routing](#metadata-primary-routing)
- [Migrating a local cluster](#migrating-a-local-cluster)
- [Adding a cluster node](#adding-a-cluster-node)
- [Cluster maintenance and recovery](#cluster-maintenance-and-recovery)
- [Limits and safety](#limits-and-safety)
- [Disclaimer](#disclaimer)
- [AI contributions](#ai-contributions)

## Capability checklist

ObjectStore creates the configured default bucket at startup. Additional buckets share the configured capacity limit.

- ✅ Persistent single-node storage with checksum verification
- ✅ Configurable per-object and total logical size limits
- ✅ CLI status, version, and full payload verification
- ✅ Local cluster prototype with stable node IDs and host-aware placement code
- ✅ Optional HTTPS between cluster processes and storage nodes
- ✅ Opt-in automatic repair, rebalance, and guarded garbage collection in the local cluster
- ✅ Metadata backup and tested restore to a separate local PostgreSQL instance
- ✅ Manual [two-machine durability and metadata-restore drill](tests/two-host/README.md)
- ⬜ Production multi-server deployment and metadata failover

New objects retain their content type and key. Objects written by the earlier single-node format remain readable, but cannot appear in listings until overwritten because their original keys were not stored.

## S3 API support checklist

- ✅ Header-based and presigned-query AWS Signature Version 4 authentication
- ✅ `PutObject`, `GetObject`, `HeadObject`, and `DeleteObject` in both modes
- ✅ Single-range GET and `ListObjectsV2` in both modes
- ✅ SHA-256 payload verification and `x-amz-checksum-sha256` in both modes
- ✅ `Content-MD5` and CRC32, CRC32C, CRC64NVME, XXHash64, XXHash3, XXHash128, SHA-1, SHA-256, SHA-512, and MD5 checksum headers on `PutObject` and `UploadPart`
- ✅ `CreateMultipartUpload`, `UploadPart`, `CompleteMultipartUpload`, and `AbortMultipartUpload` in both modes
- ✅ `ListParts` and `ListMultipartUploads` in both modes
- ✅ Presigned URLs and signed streaming Signature V4 uploads, including signed checksum trailers
- ✅ `CreateBucket`, `HeadBucket`, `DeleteBucket`, and `ListBuckets` in both modes
- ✅ `CopyObject` across owned buckets, with `COPY` and `REPLACE` content-type and user-metadata behavior
- ✅ User metadata on object and multipart uploads; object tags on upload, copy, and the tagging subresource
- ✅ CRC64NVME and XXHash checksums, checksum trailers, and persisted object checksum metadata
- ✅ Bucket versioning with retained versions, delete markers, and version-specific reads, copies, deletes, and tags in both modes
- ✅ Basic bucket and object ACL grants, public reads, and multiple access-key identities
- ⬜ Full AWS ACL ownership controls, email grantees, and bucket policies

This is an S3 API subset, not full AWS S3 compatibility. Unsupported S3 operations and Amazon-specific headers are rejected.
Buckets use the same three-to-63-character lowercase names as the configured default bucket. The default bucket cannot be deleted through the API. Bucket creation currently accepts the empty-body request used for the configured region. Tags do not control access.
Versioning supports enabled and suspended states, historical object versions, null versions, and delete markers. Retained versions count toward the capacity limit. Deleting a specific version is permanent. Lifecycle expiration, MFA Delete, and `ListObjectVersions` delimiter grouping are not supported yet.
User metadata values currently accept printable ASCII only; non-ASCII metadata header encoding is not yet supported.
Checksums supplied with `PutObject` are validated before publication and retained across restarts, copies, and object versions. When no checksum is supplied, ObjectStore calculates and stores CRC64NVME, including for completed multipart uploads. Request `x-amz-checksum-mode: ENABLED` on `GetObject` or `HeadObject` to receive the stored checksum. `UploadPart` checksums are validated but are not yet returned by `ListParts` or combined into a multipart checksum; the completed object's CRC64NVME covers its full content. Presigned URLs use query Signature V4 with a maximum seven-day expiry. Streaming uploads support signed `aws-chunked` payloads and one signed checksum trailer. Temporary credentials and other streaming payload modes remain unsupported. Copies use the existing object size limit.

## Single-node setup

Requires Docker Compose. Copy `.env.example` to `.env`, then set `S3_ACCESS_KEY` and `S3_SECRET_KEY` to unique values. The access key must be at least 16 alphanumeric characters; the secret must be at least 32 characters.

```sh
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:9000/health
```

Port 9000 binds to localhost. Data stays in the `object-data` Docker volume. `docker compose down -v` deletes that volume.

The standalone defaults are 128 MiB per object and 2 GiB total. Set `MAX_OBJECT_BYTES` and `MAX_TOTAL_BYTES` in `.env` to change them. Incomplete multipart uploads consume space until aborted.

## Access keys and ACLs

`S3_ACCESS_KEY` is the owner identity. Its secret is `S3_SECRET_KEY`. Additional keys are optional: place one `ACCESSKEY:secret` pair per line in a file mounted read-only inside the container, and set `S3_CREDENTIALS_FILE=/run/secrets/s3-credentials` in the environment file. Use a Compose override to mount an absolute host path at `/run/secrets/s3-credentials` for the `objectstore` service (or `gateway` in cluster mode). Each access key needs 16–128 alphanumeric characters and each secret at least 32 characters. A restart loads changes to that file. Keep it outside Git and protect it as a secret. Additional identities have no access until the owner grants it.

The owner can send `x-amz-acl: public-read` or signed `x-amz-grant-*` headers on bucket creation, object uploads, copies, and multipart initiation. `GET` and `PUT ?acl` support bucket and object ACLs; a PUT accepts either signed grant headers with an empty body or an XML `AccessControlPolicy`. A grantee ID is its configured access key. Supported permissions are `READ`, `WRITE`, `READ_ACP`, `WRITE_ACP`, and `FULL_CONTROL`. The `AllUsers` group is limited to `READ`; `AuthenticatedUsers` is also recognized. Anonymous reads work only where `AllUsers` has a read grant. Replacing an object starts with a private ACL unless the new upload supplies grants; older version ACLs remain attached to their versions.

This is a deliberately limited ACL subset. The configured owner owns every bucket and object. A `WRITE_ACP` grantee can change an object ACL only on a retained, non-null version; updates to current unversioned and null versions require the owner key. List-versions, multipart inspection, tagging, versioning controls, bucket creation/deletion, and the capability endpoint remain owner-only. There are no IAM policies, email grantees, Object Ownership modes, Block Public Access settings, or temporary credentials. Granting public bucket `READ` exposes object names through `ListObjectsV2`; granting public object `READ` exposes that object's bytes. Review those grants before exposing a gateway to the internet.

## CLI and tests

From the server shell, run the CLI inside the running container from the directory containing `compose.yaml`:

```sh
docker compose exec objectstore objectstore status
docker compose exec objectstore objectstore verify
docker compose exec objectstore objectstore version
```

The startup log shows the LunarSky banner, version, and a small storage summary (`docker compose logs --tail=20 objectstore`). `status` reports object and multipart usage. `verify` also checks stored payload hashes and exits nonzero on an error. Both commands can run while the service is live; they are not a snapshot or a backup.

## Capability discovery

ObjectStore provides a signed `GET /_objectstore/capabilities` endpoint. Use the same header-based Signature V4 authentication as the S3 API. It returns JSON with `schemaVersion: 1`, the service and software version, storage mode, supported operation names, and configured limits. The endpoint does not disclose credentials or cluster topology. The response has `Cache-Control: no-store` because limits can change after a restart.

`operations` lists implemented API operations, not every AWS option for each operation or the caller's authorization to use them. `limits.maxObjectBytes` applies to a completed object and to each uploaded part; `limits.maxTotalBytes` is the logical storage limit; `limits.maxParts` is 10,000. Future schema version 1 responses may add fields. Clients should ignore unknown fields and treat unknown operation names as unsupported by their own implementation. This endpoint is an ObjectStore extension; a missing endpoint on another S3-compatible service does not prove that a feature is unavailable.

Run `sh scripts/test.sh` with JDK 21 to test from source. See [TESTS.md](TESTS.md) for coverage, the disposable Docker cluster suite, and the limits of those tests.

The server includes [hash4j](https://github.com/dynatrace-oss/hash4j) for streaming XXHash checksums. Its Apache-2.0 license is included at [lib/LICENSE.hash4j](lib/LICENSE.hash4j).

## Java client

The [JDK-only Java client](client/README.md) works with ObjectStore and other S3-compatible endpoints. It supports object transfers, listing, multipart uploads, and read-only capability discovery. An [AWT image manager](client/examples/README.md) provides a small desktop example. Run `bash client/scripts/build.sh` to produce its JAR and Javadoc locally.

## Local cluster prototype

The local cluster prototype starts three segment containers and one PostgreSQL container on the same Docker host. Copy `.env.cluster.example` to a private environment file, replace all five credential values, and run:

```sh
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml up -d --build
sh scripts/test-cluster.sh /path/to/cluster.env
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml run --rm repair
```

The cluster S3 endpoint binds to `127.0.0.1:9001`; storage nodes and PostgreSQL have no published ports. The separate repair container holds the repair credential and restores missing or corrupt replicas.

Multipart parts are stored on cluster nodes and indexed in PostgreSQL. Incomplete uploads count toward the logical capacity limit; abort them to release that capacity. Repair includes staged parts. The gateway upgrades the metadata schema when it starts, so back up the database before upgrading an existing cluster.

Node UUIDs persist on their volumes, and replica manifests use those UUIDs so reordering configured URLs cannot move an existing replica. Each node also has an operator-assigned physical host UUID. New writes require acknowledgements from two different host UUIDs. The optional `CLUSTER_TEST_NODE_DOMAINS=true` override counts containers instead, solely for local process tests; all containers in this Compose file share one physical host.

## Node transport TLS

The default local Compose cluster uses HTTP inside its private Docker network. For an HTTPS test, give each node a PKCS#12 keystore containing its private key and a certificate whose DNS subject alternative name matches its `CLUSTER_NODES` hostname. Give the gateway, repair, garbage collection, and maintenance processes a PKCS#12 truststore containing the issuing CA or each node certificate. Mount the files read-only and keep the keystores and password files outside Git.

Set `NODE_TLS_KEYSTORE` and `NODE_TLS_PASSWORD_FILE` on each node. Set `CLUSTER_TLS_TRUSTSTORE` and `CLUSTER_TLS_PASSWORD_FILE` on every process that contacts nodes, and change each node URL to `https://`. With a truststore configured, HTTP node URLs are rejected. The client verifies the certificate chain and hostname; a failed handshake does not fall back to HTTP. Both settings in each pair are required. Restart affected processes after rotating certificates or truststores.

The optional [Compose TLS overlay](compose.cluster.tls.yaml) expects `node-a.p12` through `node-d.p12`, matching `.pass` files, and `trust.p12` with `trust.pass` in `CLUSTER_TLS_DIR`. Set that variable to a private certificate directory and include both Compose files:

```sh
CLUSTER_TLS_DIR=/private/objectstore-certs docker compose --env-file /path/to/cluster.env \
  -f compose.cluster.yaml -f compose.cluster.tls.yaml up -d --build
```

The files must be readable by container UID 10001 without making private keys or passwords world-readable. Add HTTPS URLs for additional nodes when expanding the cluster.

This secures node traffic only. The local cluster still lacks automatic PostgreSQL failover, database TLS configuration, encryption at rest, and production multi-server validation. Its HTTP S3 gateway remains bound to localhost; use a separate trusted proxy for external TLS. Do not treat the TLS overlay as a production deployment.

## Metadata primary routing

`POSTGRES_JDBC_URL` can override the database URL for the gateway, repair, garbage collection, and maintenance processes. Its local Compose default now uses `targetServerType=primary`, and `/ready` returns unavailable when the database is read-only or in recovery. The base Compose file still starts and waits for its own single PostgreSQL container; it is not an HA deployment. In an independently managed deployment, list the PostgreSQL hosts in the JDBC URL and keep `targetServerType=primary`:

```text
jdbc:postgresql://db-a:5432,db-b:5432/objectstore?targetServerType=primary&connectTimeout=3&socketTimeout=10
```

ObjectStore opens a new database connection for each operation, so the JDBC driver can select a promoted primary after the old one is stopped. This does **not** promote a standby, fence the old primary, configure synchronous replication, or guarantee that a recently acknowledged write reached the standby. Those jobs belong to a separately operated PostgreSQL HA system. Never allow two writable metadata databases: they can diverge while serving different ObjectStore requests. A request interrupted during failover has an uncertain outcome; verify it before retrying a non-idempotent operation. For remote database connections, configure PostgreSQL TLS and use JDBC `sslmode=verify-full` with a mounted CA certificate. The provided local Compose database does not enable TLS.

## Migrating a local cluster

For an existing **local** three-node cluster that stores replicas by URL position:

1. Stop the old gateway. Back up PostgreSQL and every node volume, and record the original ordered `CLUSTER_NODES`.
2. Start the nodes with the new image and a separate `CLUSTER_REPAIR_TOKEN`. Do not start the new gateway yet.
3. Run `objectstore cluster-migrate --check` in a one-off gateway container. Compare `legacy_0` through `legacy_2` and their URLs with the pre-upgrade inventory.
4. Run `objectstore cluster-migrate --apply id0,id1,id2` with those node IDs in the original order. The command verifies every listed live replica before conversion.
5. Start the new gateway only after the command reports `cluster_format=2`. Do not restart an old gateway against the converted database.

The old format did not record the original URL mapping, so the inventory check is essential. The isolated migration fixture tests conversion; building this code does not automatically upgrade the running local prototype.

## Adding a cluster node

`objectstore cluster-join http://new-node:9100 expected-host-uuid` registers an additional local node. Add its URL to `CLUSTER_NODES` and restart the gateway. A repair pass then copies existing segments to their preferred nodes and removes obsolete replicas from the manifest only after verifying the replacements. `scripts/test-cluster.sh` exercises a fourth container joining, receiving new segments, and rebalancing existing ones.

## Cluster maintenance and recovery

From the Compose directory, use `docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml` as the command prefix:

```sh
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml --profile automatic up -d maintenance
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml run --rm gc
sh scripts/backup-cluster-metadata.sh /path/to/cluster.env /path/to/metadata.dump
```

The maintenance service is opt-in. It repairs missing or corrupt replicas and rebalances them every 60 seconds by default. Set `CLUSTER_MAINTENANCE_INTERVAL_SECONDS` to change the interval. The `gc` command is a dry run; use `gc --apply` only after checking its candidate count and keeping independent backups. Cleanup records each orphan on one pass and waits at least `CLUSTER_GC_MIN_AGE_SECONDS` before deleting it on a later pass. The default age is 14 days. To permit deletion, set `CLUSTER_BACKUP_RETENTION_SECONDS` to your actual backup retention in seconds; it must be at least one day and shorter than the cleanup age. Scheduled cleanup also requires `CLUSTER_GC_ENABLED=true` on the maintenance service. The backup command writes a verified PostgreSQL archive with private file permissions. Restore it to a separate database and point a gateway at that database only after validating the restore. A backup restore is manual recovery, not automatic failover.

## Limits and safety

Public-client limits are **disabled by default**. The localhost Compose setup is unchanged. For an endpoint that accepts external clients, set one or both of `PUBLIC_REQUESTS_PER_SECOND` and `PUBLIC_BYTES_PER_SECOND` to a positive number in `.env`. The first limits requests per client IP with a token bucket; the second paces upload and download bytes through one shared per-IP budget. `PUBLIC_REQUEST_BURST` and `PUBLIC_BYTE_BURST` default to one second of their respective rates. `PUBLIC_MAX_IN_FLIGHT_PER_IP` defaults to 8 when either rate is enabled. Requests above the rate or concurrency limit receive S3 `503 SlowDown` and `Retry-After: 1`; an admitted transfer is paced rather than cut off. These are per-gateway limits, not cluster-wide quotas. The existing 16-request gateway cap and storage limits still apply.

For example, to start with 100 requests per second and 16 MiB/s combined upload and download per IP, set `PUBLIC_REQUESTS_PER_SECOND=100` and `PUBLIC_BYTES_PER_SECOND=16777216`. Both start with a one-second burst. Adjust these numbers after measuring the actual workload; do not copy them as a universal production policy.

ObjectStore uses the socket peer as the client IP and ignores forwarded-IP headers by default. If a reverse proxy sits between external clients and ObjectStore, set `PUBLIC_TRUSTED_PROXY_IPS` to the exact IP address that ObjectStore sees for that proxy and configure the proxy to **replace** `X-Real-IP` with its actual client IP. A request from that trusted peer without exactly one valid numeric `X-Real-IP` is rejected. Do not trust an address reachable by arbitrary clients, and preserve the original `Host` header for Signature V4. In Docker, the proxy's address as seen by the container may be a bridge address rather than `127.0.0.1`. If proxy trust is not configured, all clients behind that proxy share its budget; this is safe from header spoofing but may throttle them together.

Enable these limits only on a deliberately public endpoint. They also apply to direct localhost storage calls to that same endpoint; leave them disabled for the local-only setup or run a separate local-only instance if local storage calls must be exempt. A direct loopback `/health` probe without a forwarded-IP header remains exempt. The byte limit is aggregate ingress plus egress for each IP, and several users behind one NAT share it. If a proxy buffers complete uploads before forwarding them, the upload byte limit controls proxy-to-ObjectStore traffic, not the client's initial upload speed; disable request buffering when end-to-end upload pacing is required. It is a fairness control, not a defense against connection floods before the Java handler runs. Put an internet-facing proxy or firewall in front of the gateway for TLS, connection limits, request timeouts, and buffering controls. Do not expose storage nodes or PostgreSQL publicly.

The default local cluster still uses plaintext node and PostgreSQL connections. The optional TLS overlay secures node traffic, but PostgreSQL TLS, automatic metadata failover, scoped credentials, and physical host verification remain absent. Garbage collection can remove data required by an older metadata backup, so its retention guard is essential. Host UUIDs are operator labels, not proof that machines have separate power, disks, or network paths. Keep `CLUSTER_LOCAL_DEV=true` limited to local tests.

The standalone cluster node binds to localhost by default. Set `NODE_BIND` only for a private test network; the Compose file binds inside its private Docker network. PostgreSQL JDBC 42.7.14 is bundled in the image with its license inside the JAR.

## Disclaimer

ObjectStore is independent software. It is not affiliated with, sponsored by, or endorsed by Amazon or Amazon Web Services (AWS). “S3-compatible” describes only the API subset listed above.

Keep independent backups; data loss is possible. The software is provided “as is” under the [MIT License](LICENSE). To the extent permitted by applicable law, the authors and maintainers are not responsible for data loss or other damages arising from its use.

## AI contributions

AI tools assisted with parts of this project. Maintainers review releases and remain responsible for what ships.
