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
- [CLI and tests](#cli-and-tests)
- [Local cluster prototype](#local-cluster-prototype)
- [Migrating a local cluster](#migrating-a-local-cluster)
- [Adding a cluster node](#adding-a-cluster-node)
- [Limits and safety](#limits-and-safety)
- [Disclaimer](#disclaimer)
- [AI contributions](#ai-contributions)

## Capability checklist

ObjectStore serves one configured bucket.

- ✅ Persistent single-node storage with checksum verification
- ✅ Configurable per-object and total logical size limits
- ✅ CLI status, version, and full payload verification
- ✅ Local cluster prototype with stable node IDs and host-aware placement code
- ⬜ Automatic repair, rebalance, and garbage collection
- ⬜ Production multi-server deployment and metadata failover

New objects retain their content type and key. Objects written by the earlier single-node format remain readable, but cannot appear in listings until overwritten because their original keys were not stored.

## S3 API support checklist

- ✅ Header-based AWS Signature Version 4 authentication
- ✅ `PutObject`, `GetObject`, `HeadObject`, and `DeleteObject` in both modes
- ✅ Single-range GET and `ListObjectsV2` in both modes
- ✅ SHA-256 payload verification and `x-amz-checksum-sha256` in both modes
- ✅ `CreateMultipartUpload`, `UploadPart`, `CompleteMultipartUpload`, and `AbortMultipartUpload` in single-node mode
- ⬜ Multipart uploads in cluster mode
- ⬜ Presigned URLs and streaming Signature V4 uploads
- ⬜ `CopyObject`, `ListParts`, and `ListMultipartUploads`
- ⬜ `Content-MD5` and checksum algorithms other than SHA-256
- ⬜ Bucket creation and listing, object versioning, ACLs, tags, and user metadata

This is an S3 API subset, not full AWS S3 compatibility. Unsupported S3 operations and Amazon-specific headers are rejected.

## Single-node setup

Requires Docker Compose. Copy `.env.example` to `.env`, then set `S3_ACCESS_KEY` and `S3_SECRET_KEY` to unique values. The access key must be at least 16 alphanumeric characters; the secret must be at least 32 characters.

```sh
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:9000/health
```

Port 9000 binds to localhost. Data stays in the `object-data` Docker volume. `docker compose down -v` deletes that volume.

The standalone defaults are 128 MiB per object and 2 GiB total. Set `MAX_OBJECT_BYTES` and `MAX_TOTAL_BYTES` in `.env` to change them. Incomplete multipart uploads consume space until aborted.

## CLI and tests

From the server shell, run the CLI inside the running container from the directory containing `compose.yaml`:

```sh
docker compose exec objectstore objectstore status
docker compose exec objectstore objectstore verify
docker compose exec objectstore objectstore version
```

The startup log shows the LunarSky banner, version, and a small storage summary (`docker compose logs --tail=20 objectstore`). `status` reports object and multipart usage. `verify` also checks stored payload hashes and exits nonzero on an error. Both commands can run while the service is live; they are not a snapshot or a backup.

Run `sh scripts/test.sh` with JDK 21 to test from source. See [TESTS.md](TESTS.md) for coverage, the disposable Docker cluster suite, and the limits of those tests.

## Local cluster prototype

The local cluster prototype starts three segment containers and one PostgreSQL container on the same Docker host. Copy `.env.cluster.example` to a private environment file, replace all four credentials, and run:

```sh
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml up -d --build
sh scripts/test-cluster.sh /path/to/cluster.env
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml run --rm repair
```

The cluster S3 endpoint binds to `127.0.0.1:9001`; storage nodes and PostgreSQL have no published ports. The separate repair container holds the repair credential and restores missing or corrupt replicas.

Node UUIDs persist on their volumes, and replica manifests use those UUIDs so reordering configured URLs cannot move an existing replica. Each node also has an operator-assigned physical host UUID. New writes require acknowledgements from two different host UUIDs. The optional `CLUSTER_TEST_NODE_DOMAINS=true` override counts containers instead, solely for local process tests; all containers in this Compose file share one physical host.

## Migrating a local cluster

For an existing **local** three-node cluster that stores replicas by URL position:

1. Stop the old gateway. Back up PostgreSQL and every node volume, and record the original ordered `CLUSTER_NODES`.
2. Start the nodes with the new image and a separate `CLUSTER_REPAIR_TOKEN`. Do not start the new gateway yet.
3. Run `objectstore cluster-migrate --check` in a one-off gateway container. Compare `legacy_0` through `legacy_2` and their URLs with the pre-upgrade inventory.
4. Run `objectstore cluster-migrate --apply id0,id1,id2` with those node IDs in the original order. The command verifies every listed live replica before conversion.
5. Start the new gateway only after the command reports `cluster_format=2`. Do not restart an old gateway against the converted database.

The old format did not record the original URL mapping, so the inventory check is essential. The isolated migration fixture tests conversion; building this code does not automatically upgrade the running local prototype.

## Adding a cluster node

`objectstore cluster-join http://new-node:9100 expected-host-uuid` registers an additional local node. Add its URL to `CLUSTER_NODES` and restart the gateway to use it for new writes. Existing segments stay where their manifests say; this is capacity expansion for new writes, not a rebalance. `scripts/test-cluster.sh` exercises a fourth container joining and receiving new segments.

## Limits and safety

The cluster retains old and failed-write segments. It has no garbage collection, metadata standby, automated rebalance, private-network TLS, scoped credentials, or physical host verification yet. Host UUIDs are operator labels, not proof that machines have separate power, disks, or network paths. Keep `CLUSTER_LOCAL_DEV=true` limited to local tests.

The standalone cluster node binds to localhost by default. Set `NODE_BIND` only for a private test network; the Compose file binds inside its private Docker network. PostgreSQL JDBC 42.7.14 is bundled in the image with its license inside the JAR.

## Disclaimer

ObjectStore is independent software. It is not affiliated with, sponsored by, or endorsed by Amazon or Amazon Web Services (AWS). “S3-compatible” describes only the API subset listed above.

Keep independent backups; data loss is possible. The software is provided “as is” under the [MIT License](LICENSE). To the extent permitted by applicable law, the authors and maintainers are not responsible for data loss or other damages arising from its use.

## AI contributions

AI tools assisted with parts of this project. Maintainers review releases and remain responsible for what ships.
