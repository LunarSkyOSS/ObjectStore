# Testing ObjectStore

ObjectStore is development software. Do not use it for production data. It is provided **as is, without warranty** under the [MIT License](LICENSE). Keep independent backups. Passing these tests does not guarantee that data cannot be lost.

Run the commands below from the repository root.

## Source tests

Requires JDK 21. No Docker service is needed.

```sh
sh scripts/test.sh
```

The script compiles the source and test programs into `out/classes`, then runs:

| Test | Checks |
| --- | --- |
| `StoreTest` | Signature V4 and signed-stream vectors, CRC64NVME and XXHash reference vectors, local writes and reads, quotas, restart persistence for objects, checksums, ACLs, attributes, buckets, and versions, multipart recovery, legacy reads, locking, and corruption rejection. |
| `ConcurrencyTest` | Atomic local overwrites, consistent reads, listings and deletes, plus concurrent quota enforcement and restart recovery. |
| `HttpTest` | Signed capability discovery, presigned URLs, streaming uploads and trailers, object and bucket operations, ACL grants with a second access key and public reads, copies, checksum persistence and rejection, ranges, listing, metadata, tags, multipart uploads, versioning, and signed virtual-hosted requests in single-node mode. |
| `ClientLimitsTest` | Disabled defaults, trusted-proxy address validation, ignored untrusted headers, per-IP request refusal, and paced response bytes. |
| `EncryptedVolumeTest` | Marker acceptance and refusal when the configured encrypted directory is missing, mismatched, or a symlink. |
| `ClusterNodeTest` | Node identity and locking, authenticated segment transfers, checksum rejection, repair authorization, inventory and guarded deletion, and restart cleanup. |
| `ClusterTlsTest` | HTTPS node identity and segment roundtrip with a trusted certificate, plus rejection of untrusted and wrong-host certificates. |
| `CliTest` | Version, status, verification, and a nonzero result for corrupt data. |
| `ClientTest` and `MultipartClientTest` | Java client request signing, capability discovery, error handling, and multipart operations. |

The script exits nonzero on failure. The test programs use temporary local directories and loopback HTTP or HTTPS ports; they do not use an existing ObjectStore volume. `ClusterTlsTest` uses the JDK's `keytool` to create disposable test certificates.

The encrypted Compose overlays can be checked with `docker compose config` without starting the stack. This verifies their bind mounts and startup settings, not that a host filesystem is actually encrypted. `scripts/prepare-encrypted-storage.sh` must also verify an active dm-crypt-backed mount. A complete deployment drill must unlock it, prepare directories, start with the overlay, write data, restart, restore from backup, and confirm that the stack refuses to start while the mount is unavailable. Do not point that drill at existing data.

## Disposable metadata routing test

Run `sh scripts/test-metadata.sh` with Docker Compose. It creates a separate, temporary PostgreSQL container and two in-process storage nodes. The test connects through a two-host JDBC URL whose first host is unavailable, checks writable readiness, then makes the test database read-only and verifies that readiness drops. The script removes its test containers and temporary database afterward. It does not promote a standby or test automatic failover.

## Disposable Docker cluster tests

Requires Docker with Compose, Python 3.9 or newer, `curl`, and a free local port 9001. Make a test-only environment file from `.env.cluster.example` and replace all five credential values with test-only values. Keep that file private and out of Git.

```sh
cp .env.cluster.example /tmp/objectstore-cluster-tests.env
chmod 600 /tmp/objectstore-cluster-tests.env
```

After filling in the file, run:

```sh
COMPOSE_PROJECT_NAME=objectstore-tests sh scripts/test-cluster.sh /tmp/objectstore-cluster-tests.env
```

Use a fresh, disposable Compose project. The script writes test objects, stops and restarts storage nodes and PostgreSQL, corrupts a replica to exercise repair, and joins a fourth node. It leaves the test stack running. To remove **only that test project's** containers and volumes after review:

```sh
COMPOSE_PROJECT_NAME=objectstore-tests docker compose --env-file /tmp/objectstore-cluster-tests.env -f compose.cluster.yaml --profile expansion --profile automatic --profile recovery down -v
```

If port 9001 is occupied, set `CLUSTER_HOST_PORT` to the same free port in both the environment file and the shell before running the script. The script reads that port from the shell; Compose reads it from the file.

The Docker suite checks signed capability discovery and S3 operations, bucket creation and deletion, metadata and tags, public-read ACLs and anonymous access, copies, upload checksums, multi-segment objects, concurrent overwrites, multipart staging and listings, versioned reads and delete markers, versioned multipart completion, completion after a gateway restart and node loss, reads and writes with a node stopped, refusal to write without a storage quorum, restart recovery, corrupt-replica repair including staged parts, metadata unavailability, read-only metadata readiness rejection, and placement on a newly joined node. It then checks rebalance to the fourth node, automatic repair, garbage collection dry run and delayed deletion, and a metadata backup restored to a separate PostgreSQL instance while the original database is stopped. A two-host JDBC URL selects that restored writable database. Historical regular and multipart versions are checked after repair and cleanup. It also checks that containers labeled as one physical host cannot satisfy the normal host quorum. Its local-only override permits the remaining phases to use containers as separate test domains.

`ClusterMigrationTest` is a separate legacy-format fixture and is **not** run by either test script. Do not run its `create` phase against a populated metadata database. The migration procedure is in the [README](README.md#migrating-a-local-cluster).

## Two-machine durability drill

The [manual two-machine drill](tests/two-host/README.md) uses machine A and machine B with disposable volumes. It records acknowledged writes during a machine interruption, checks reads and write rejection with either storage machine unavailable, restarts test containers during writes, and restores a metadata backup on machine B. This drill is not part of `scripts/test.sh` because it requires two machines and a coordinated interruption.

## What these tests do not prove

- Container stops are not physical power cuts or disk failures. The automated suite does not reboot a host or test every possible crash point.
- The Compose nodes share one machine. Passing the local-only quorum override does not demonstrate durability across independent hosts, racks, or sites.
- The suite does not test automatic metadata failover, an off-site backup restore, prolonged load, or full AWS S3 compatibility. `scripts/test.sh` checks scoped keys, CORS, and metrics. A separate local cold-snapshot drill restored one object into fresh single-node and cluster Compose projects and confirmed its bytes; the single-node restore also refused a nonempty destination. This does not test a multi-host restore. The Rust client passed live local upload/download and presigned GET/PUT smoke tests, but is not covered by the Java test script.

See [Limits and safety](README.md#limits-and-safety) before evaluating any multi-server deployment.
