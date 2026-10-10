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
| `StoreTest` | Signature V4 test vector and tampering, local writes and reads, quotas, restart persistence, multipart recovery, legacy reads, locking, and corruption rejection. |
| `ConcurrencyTest` | Atomic local overwrites and consistent reads, listings, and deletes during concurrent access. |
| `HttpTest` | Signed HTTP requests, object operations, ranges, listing, multipart uploads, and multipart listings in single-node mode. |
| `ClusterNodeTest` | Node identity and locking, authenticated segment transfers, checksum rejection, repair authorization, and restart cleanup. |
| `CliTest` | Version, status, verification, and a nonzero result for corrupt data. |

The script exits nonzero on failure. The test programs use temporary local directories and loopback HTTP ports; they do not use an existing ObjectStore volume.

## Disposable Docker cluster tests

Requires Docker with Compose, Python 3, `curl`, and a free local port 9001. Make a test-only environment file from `.env.cluster.example` and fill in all five blank credentials with test-only values. Keep that file private and out of Git.

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
COMPOSE_PROJECT_NAME=objectstore-tests docker compose --env-file /tmp/objectstore-cluster-tests.env -f compose.cluster.yaml --profile expansion down -v
```

If port 9001 is occupied, set `CLUSTER_HOST_PORT` to the same free port in both the environment file and the shell before running the script. The script reads that port from the shell; Compose reads it from the file.

The Docker suite checks signed S3 operations, multi-segment objects, concurrent overwrites, multipart staging and listings, completion after a gateway restart and node loss, reads and writes with a node stopped, refusal to write without a storage quorum, restart recovery, corrupt-replica repair including staged parts, metadata unavailability, and placement on a newly joined node. It also checks that containers labeled as one physical host cannot satisfy the normal host quorum. Its local-only override permits the remaining phases to use containers as separate test domains.

`ClusterMigrationTest` is a separate legacy-format fixture and is **not** run by either test script. Do not run its `create` phase against a populated metadata database. The migration procedure is in the [README](README.md#migrating-a-local-cluster).

## What these tests do not prove

- Container stops are not physical power cuts or disk failures. The automated suite does not reboot a host or test every possible crash point.
- The Compose nodes share one machine. Passing the local-only quorum override does not demonstrate durability across independent hosts, racks, or sites.
- The suite does not test metadata failover, an off-site backup restore, prolonged load, or full AWS S3 compatibility.

See [Limits and safety](README.md#limits-and-safety) before evaluating any multi-server deployment.
