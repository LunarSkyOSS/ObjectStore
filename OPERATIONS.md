# Operations

These notes describe the development branch and may be ahead of the latest tagged release. For v0.0.8, use the [versioned operations docs](https://docs.lunarsky.cloud/objectstore/v0.0.8/operations).

## Encrypted storage

At-rest encryption is **opt-in** through a host-mounted dm-crypt/LUKS filesystem. ObjectStore does not encrypt data itself or manage the key. Unlock and mount the filesystem outside Docker, then set `ENCRYPTED_STORAGE_ROOT` to its mount point and `ENCRYPTED_VOLUME_ID` to a stable 32-character lowercase hex identifier in a private environment file. Generate the ID with `openssl rand -hex 16`; it guards the mount and is **not** a key. Store the LUKS recovery key separately from data and backups.

On the mounted filesystem, run `sh scripts/prepare-encrypted-storage.sh single /path/to/objectstore.env` or `sh scripts/prepare-encrypted-storage.sh cluster /path/to/cluster.env`. It reads only the encryption settings, verifies the dm-crypt mount, and refuses nonempty directories. Give it permission to create directories and set container ownership. Start the matching Compose overlay:

```sh
docker compose --env-file /path/to/objectstore.env -f compose.yaml -f compose.encrypted.yaml up -d --build
docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml -f compose.cluster.encrypted.yaml up -d --build
```

Use the first command for single-node mode or the second for the **local development cluster**. The overlays mount encrypted directories for object data, nodes, PostgreSQL, and staging. A marker check prevents startup on a missing or wrong mount; it does not unlock LUKS. Arrange host unlock and mount before Compose starts.

**Existing volumes are not migrated automatically.** Stop the stack, verify a backup, and copy the stopped volumes into prepared encrypted directories. For PostgreSQL, copy its data into `cluster/metadata/pgdata` because the overlay changes `PGDATA`. Verify the restore before retiring old volumes. Each host in a future multi-host deployment needs its own encrypted mount and key recovery plan.

This does not cover Docker logs, swap, other temporary files, external backups, or a compromised running host. Protect those separately, use TLS, and test backup recovery. Encryption alone does not establish GDPR compliance.

## Access keys and ACLs

`S3_ACCESS_KEY` and `S3_SECRET_KEY` define the owner. For additional identities, put one `ACCESSKEY:secret` pair per line in a private file and mount it read-only at `/run/secrets/s3-credentials` on `objectstore` (or `gateway` in cluster mode). Set `S3_CREDENTIALS_FILE` to that path. Keys need 16–128 alphanumeric characters; secrets need at least 32 characters. Changes load on restart. Additional identities have no access until granted it.

The owner can set `x-amz-acl: public-read` or signed `x-amz-grant-*` headers on bucket creation, uploads, copies, and multipart initiation. Use `GET ?acl` to read bucket or object grants; `PUT ?acl` accepts signed grant headers or an XML `AccessControlPolicy`. A grantee ID is its access key. Permissions are `READ`, `WRITE`, `READ_ACP`, `WRITE_ACP`, and `FULL_CONTROL`; `AllUsers` can only read, and `AuthenticatedUsers` is recognized. Anonymous reads require an `AllUsers` grant. Overwriting an object resets its ACL to private unless new grants are supplied; retained versions keep their ACLs.

The configured owner owns every bucket and object. A `WRITE_ACP` grantee can change an ACL only on a retained, non-null version; current unversioned and null versions require the owner key. Version lists, multipart inspection, tags, versioning controls, bucket creation/deletion, and capabilities remain owner-only. IAM policies, email grantees, Object Ownership, Block Public Access, and temporary credentials are unsupported. Public bucket `READ` exposes names; public object `READ` exposes bytes. Review grants before exposing a gateway.

## CLI and tests

From the server shell, run the CLI inside the running container from the directory containing `compose.yaml`:

```sh
docker compose exec objectstore objectstore status
docker compose exec objectstore objectstore verify
docker compose exec objectstore objectstore version
```

The startup log shows the LunarSky banner, version, and a small storage summary (`docker compose logs --tail=20 objectstore`). `status` reports object and multipart usage. `verify` also checks stored payload hashes and exits nonzero on an error. Both commands can run while the service is live; they are not a snapshot or a backup.

## Capability discovery

Signed `GET /_objectstore/capabilities` returns schema version 1, software version, storage mode, operation names, and configured limits. Use header-based Signature V4. It does not disclose credentials or topology, and responses are not cached.

`operations` does not imply every AWS option or permission for the caller. `maxObjectBytes` limits completed objects and individual parts; `maxTotalBytes` is logical capacity; `maxParts` is 10,000. Clients should ignore new fields and unknown operations. This endpoint is an ObjectStore extension, so its absence elsewhere does not imply missing S3 features.

Run `sh scripts/test.sh` with JDK 21 to test from source. See [TESTS.md](TESTS.md) for coverage, the disposable Docker cluster suite, and the limits of those tests.

The server includes [hash4j](https://github.com/dynatrace-oss/hash4j) for streaming XXHash checksums. Its Apache-2.0 license is included at [lib/LICENSE.hash4j](lib/LICENSE.hash4j).

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

Public-client limits are **disabled by default**. The localhost Compose setup is unchanged. For an endpoint that accepts external clients, set one or both of `PUBLIC_REQUESTS_PER_SECOND` and `PUBLIC_BYTES_PER_SECOND` to a positive number in `.env`. The first limits requests per client IP with a token bucket; the second paces upload and download bytes through one shared per-IP budget. `PUBLIC_REQUEST_BURST` and `PUBLIC_BYTE_BURST` default to one second of their respective rates. `PUBLIC_MAX_IN_FLIGHT_PER_IP` defaults to 8 when either rate is enabled. Requests above the rate or concurrency limit receive S3 `503 SlowDown` and `Retry-After: 1`; an admitted transfer is paced rather than cut off. These are per-gateway limits, not cluster-wide quotas.

`MAX_IN_FLIGHT_REQUESTS` controls the per-gateway concurrency cap (default 16, range 1–1024); an additional request receives `503 SlowDown`. `HTTP_BACKLOG` controls the listening socket backlog (default 64, range 1–4096). Raise either only after a mixed upload/download load test, because each active request can hold memory, disk space, and database connections. The configured object and total storage limits still apply. `MAX_OBJECT_BYTES` currently cannot exceed 1 GiB.

Set `S3_VIRTUAL_HOST_SUFFIX` to a DNS suffix such as `s3.example.com` to accept `bucket.s3.example.com/key` alongside path-style `/bucket/key`. Configure DNS and TLS for the bucket hostnames, and preserve the client's original `Host` header through the proxy; Signature V4 signs it. The suffix is empty by default. This changes request parsing, not bucket naming rules or DNS configuration.

For example, to start with 100 requests per second and 16 MiB/s combined upload and download per IP, set `PUBLIC_REQUESTS_PER_SECOND=100` and `PUBLIC_BYTES_PER_SECOND=16777216`. Both start with a one-second burst. Adjust these numbers after measuring the actual workload; do not copy them as a universal production policy.

ObjectStore uses the socket peer as the client IP and ignores forwarded-IP headers by default. If a reverse proxy sits between external clients and ObjectStore, set `PUBLIC_TRUSTED_PROXY_IPS` to the exact IP address that ObjectStore sees for that proxy and configure the proxy to **replace** `X-Real-IP` with its actual client IP. A request from that trusted peer without exactly one valid numeric `X-Real-IP` is rejected. Do not trust an address reachable by arbitrary clients, and preserve the original `Host` header for Signature V4. In Docker, the proxy's address as seen by the container may be a bridge address rather than `127.0.0.1`. If proxy trust is not configured, all clients behind that proxy share its budget; this is safe from header spoofing but may throttle them together.

Enable these limits only on a deliberately public endpoint. They also apply to direct localhost storage calls to that same endpoint; leave them disabled for the local-only setup or run a separate local-only instance if local storage calls must be exempt. A direct loopback `/health` probe without a forwarded-IP header remains exempt. The byte limit is aggregate ingress plus egress for each IP, and several users behind one NAT share it. If a proxy buffers complete uploads before forwarding them, the upload byte limit controls proxy-to-ObjectStore traffic, not the client's initial upload speed; disable request buffering when end-to-end upload pacing is required. It is a fairness control, not a defense against connection floods before the Java handler runs. Put an internet-facing proxy or firewall in front of the gateway for TLS, connection limits, request timeouts, and buffering controls. Do not expose storage nodes or PostgreSQL publicly.

The default local cluster still uses plaintext node and PostgreSQL connections. The optional TLS overlay secures node traffic, but PostgreSQL TLS, automatic metadata failover, scoped credentials, and physical host verification remain absent. Garbage collection can remove data required by an older metadata backup, so its retention guard is essential. Host UUIDs are operator labels, not proof that machines have separate power, disks, or network paths. Keep `CLUSTER_LOCAL_DEV=true` limited to local tests.

The standalone cluster node binds to localhost by default. Set `NODE_BIND` only for a private test network; the Compose file binds inside its private Docker network. PostgreSQL JDBC 42.7.14 is bundled in the image with its license inside the JAR.
