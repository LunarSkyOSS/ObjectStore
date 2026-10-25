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

New buckets default to `BucketOwnerEnforced` and all four Block Public Access flags enabled. Existing buckets retain their prior ACL behavior on upgrade. The owner can inspect or change settings with `GET`/`PUT ?ownershipControls` and `GET`/`PUT ?publicAccessBlock`; changing a legacy bucket to owner-enforced immediately stops its ACL grants from authorizing requests. Public ACL writes are blocked when `BlockPublicAcls` is enabled, and existing public ACL grants are ignored when `IgnorePublicAcls` is enabled. `GET`/`PUT`/`DELETE ?policy` manages a bucket policy. `BlockPublicPolicy` rejects new policies that allow a wildcard principal; `RestrictPublicBuckets` prevents anonymous access through such a policy.

On a bucket with ACLs enabled, the owner can set `x-amz-acl: public-read` or signed `x-amz-grant-*` headers on bucket creation, uploads, copies, and multipart initiation. Use `GET ?acl` to read bucket or object grants; `PUT ?acl` accepts signed grant headers or an XML `AccessControlPolicy`. A grantee ID is its access key. Permissions are `READ`, `WRITE`, `READ_ACP`, `WRITE_ACP`, and `FULL_CONTROL`; `AllUsers` can only read, and `AuthenticatedUsers` is recognized. Anonymous reads require an `AllUsers` grant or an allowed bucket policy. Overwriting an object resets its ACL to private unless new grants are supplied; retained versions keep their ACLs. Email grantees return `405 MethodNotAllowed`, matching AWS's removal of that grant type.

For a narrower additional key, mount a second private file and set `S3_KEY_SCOPES_FILE` to its container path. Each non-comment line has four **tab-separated** fields: access key, bucket, key prefix, and comma-separated actions (`read`, `write`, `list`). For example, `UPLOADKEY12345678<TAB>media<TAB>users/42/<TAB>read,write,list`. The file may name a permanent or session identity; the owner key is always unrestricted. Once the scope file is enabled, an additional key with no scope is denied. A list request must include a prefix inside the granted prefix. Copy requires read permission on the source and write permission on the destination. Restricted keys cannot change ACLs or manage buckets. A scope is a ceiling: the bucket ACL, bucket policy, or identity policy must also grant access. Restart the service after editing the files.

To use identity policies, mount a private directory and set `S3_IDENTITY_POLICIES_DIR` to its container path. Put one `<ACCESSKEY>.json` file per identity. The supported JSON subset uses `Version: "2012-10-17"` and `Statement` entries with `Effect`, `Action`, and `Resource`; it accepts S3 action wildcards and bucket/object ARNs. The bucket-policy form also requires `Principal`, using `*`, an access key, or `{ "AWS": "ACCESSKEY" }`. Policies govern object reads, writes, deletes, copies, ACLs, bucket listing, and multipart writes or aborts. Explicit denies win. `Condition`, `NotAction`, `NotResource`, `NotPrincipal`, and IAM roles are rejected rather than silently ignored. Files load on restart; protect the directory like credentials.

For a restricted key to upload **and read** files in a new owner-enforced bucket, give it both a `read,write` key scope for its prefix and an identity policy allowing `s3:PutObject` and `s3:GetObject` on that prefix. For example, a scope of `UPLOADKEY12345678<TAB>media<TAB>users/42/<TAB>read,write` pairs with `UPLOADKEY12345678.json` containing `{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:PutObject","s3:GetObject"],"Resource":"arn:aws:s3:::media/users/42/*"}]}`. A scope alone is only a ceiling, and ObjectStore does not infer ownership from the uploader's key. Assign a distinct prefix per key when keys must not read each other's files.

For externally issued sessions, mount a private file and set `S3_SESSION_CREDENTIALS_FILE` to its container path. Each line is four **tab-separated** fields: session access key, secret, security token, and Unix expiry time in seconds. Session keys use the same 16–128 alphanumeric format as permanent keys; secrets and tokens need at least 32 characters. Signed requests must sign and send `x-amz-security-token`; presigned URLs must include signed `X-Amz-Security-Token`. Expired or mismatched tokens are denied on every request. ObjectStore does not issue or refresh sessions: provision and rotate the file outside the service, then restart. A session has no access until an ACL, bucket policy, or identity policy grants it; a configured key scope can narrow it further.

The configured owner owns every bucket and object. `BucketOwnerPreferred` and `ObjectWriter` are accepted as ACL-enabled modes, but there is one ObjectStore account, so cross-account object ownership is not modeled. A `WRITE_ACP` grantee can change an ACL only on a retained, non-null version; current unversioned and null versions require the owner key. Version lists, multipart inspection, tags, versioning controls, bucket creation/deletion, and capabilities remain owner-only. These policy features cover common object and bucket access, not full AWS IAM or STS. Public bucket `READ` exposes names; public object `READ` exposes bytes. Review grants before exposing a gateway.

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

## Browser access, transfer limits, and metrics

`CORS_ALLOWED_ORIGINS` is disabled by default. Set a comma-separated list of exact `http://` or `https://` origins to permit browser preflight for GET, HEAD, PUT, POST, and DELETE. Browsers still need a signed request or presigned URL; CORS does not grant access. Do not put secret access keys in browser code. For direct browser uploads, issue short-lived presigned URLs from a trusted server and allow that site's origin. Browser preflight requests count against the same admission limits as other requests.

The Docker image no longer has a fixed 30-second request or 60-second response deadline. `OBJECTSTORE_REQUEST_TIMEOUT_SECONDS` and `OBJECTSTORE_RESPONSE_TIMEOUT_SECONDS` default to `0` (no total transfer deadline); positive values are capped at one day. A reverse proxy may have its own upload size, buffering, idle timeout, and response timeout. Configure those for expected video sizes and slow clients. `MAX_OBJECT_BYTES` defaults to 128 MiB and can be raised up to 1 GiB; `MAX_TOTAL_BYTES` defaults to 2 GiB. For a 250 MB video workload, a starting configuration is `MAX_OBJECT_BYTES=536870912` (512 MiB) and a `MAX_TOTAL_BYTES` based on the actual free disk and retention plan. The in-flight cap remains 16 by default. Measure the target machine before raising it; 256 concurrent uploads can exhaust memory or disk bandwidth.

Set `METRICS_BEARER_TOKEN` to a random value of at least 32 characters to enable `GET /metrics` in Prometheus text format. Scrape with `Authorization: Bearer <token>` over a private network or HTTPS. The endpoint reports counts by response class, total request time, in-flight requests, and S3 request and body-byte totals without bucket or user labels. Keep the token private. The Prometheus endpoint is disabled when the variable is empty.

### Local admin console

The admin console is a read-only status, bucket, and performance view at `http://127.0.0.1:9000/_objectstore/admin/`. It lists buckets, navigates object prefixes, and shows object metadata and bucket access settings. The object-size chart and counts cover only the current page of a listing. The performance view samples every three seconds while visible and graphs S3 request rate, inbound and outbound body bytes, process CPU, and memory. It also shows GC collections. Admin requests and health checks are excluded from those S3 rates, and chart history lives only in the browser tab. RSS is read from Linux when available; otherwise the memory card shows JVM heap plus non-heap usage. This view does not require the separate Prometheus token. It is disabled by default and uses a separate password, not an S3 access key. Its Biscuit widgets are bundled with the image under the MIT license in `admin-ui/LICENSE.Biscuit`. To enable it for the local Compose stack:

```sh
mkdir -p secrets
chmod 700 secrets
umask 077
openssl rand -base64 32 > secrets/admin-password
chmod 644 secrets/admin-password
docker compose -f compose.yaml -f compose.admin.yaml up -d --build
```

Read the generated password from `secrets/admin-password` on the host to sign in. The directory is private to your host account, while the file is readable by the container's unprivileged user through a read-only mount. The file is ignored by Git. The password must be at least 16 bytes. Sessions expire after 30 minutes, and five failed logins from one peer pause further attempts for five minutes. A restart clears all sessions. Do not reuse the S3 secret.

The console accepts only loopback Host values by default, including if the S3 port is accidentally published on a wider interface. For access through a reverse proxy, set `ADMIN_UI_ORIGIN` to the exact external HTTPS origin, preserve its Host header, and terminate TLS at the proxy. HTTP access through a remote origin is not supported. The password is still required behind the loopback binding or proxy; neither is an authentication mechanism. The console does not grant access to S3 operations.

For a bounded local write baseline, run `python3 scripts/benchmark-writes.py --requests 100 --concurrency 8 --size 4096` with `S3_ACCESS_KEY` and `S3_SECRET_KEY` in the environment. It writes under a unique `benchmark/` prefix and deletes successful objects afterward. Results depend on CPU, disks, network, connection reuse, and durability settings; a laptop number does not predict another server. Run a mixed upload/download workload before changing concurrency or public limits.

## Cold snapshots

The local Compose stacks support a cold snapshot of **all** their named data volumes. Stop the stack first, then run one of the following with absolute paths:

```sh
docker compose --env-file /path/to/objectstore.env -f compose.yaml stop
sh scripts/snapshot-local.sh single backup /path/to/objectstore.env /path/to/objectstore-snapshot.tar

docker compose --env-file /path/to/cluster.env -f compose.cluster.yaml stop
sh scripts/snapshot-local.sh cluster backup /path/to/cluster.env /path/to/cluster-snapshot.tar
```

The script refuses to run while any service in that Compose project is running. It includes PostgreSQL and node volumes for the local cluster, checks each component archive, and writes a checksum manifest. Store the snapshot and a separate copy of the private environment, key-scope, credential, and encryption recovery material off the machine. To restore, build or pull the images, then use the same command with `restore` instead of `backup` on a **fresh empty** set of stopped volumes; the script refuses to overwrite nonempty volumes. Start the stack and run `objectstore verify` or the cluster repair check, then read test objects. The script refuses encrypted bind-mount overlays, which need a separate host-filesystem snapshot. A snapshot is not a substitute for an off-site restore drill. The local Compose cluster is still one physical-host prototype; this script is not a coordinated backup of a deployed multi-host cluster.

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

This secures node traffic only. The local cluster still lacks automatic PostgreSQL failover and production multi-server validation. PostgreSQL TLS must be configured separately, and at-rest encryption uses the host overlay described above. Its HTTP S3 gateway remains bound to localhost; use a separate trusted proxy for external TLS. Do not treat the TLS overlay as a production deployment.

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

Set `S3_PUBLIC_ENDPOINT` to the external HTTP(S) origin, such as `https://s3.example.com`, when a TLS reverse proxy fronts ObjectStore. Multipart completion uses it to return a path-style `Location` URL. Without it, the response uses the request's signed `Host` and direct connection scheme, which may be `http` behind a TLS proxy. The value must be an origin without a path, query, user info, or fragment.

For example, to start with 100 requests per second and 16 MiB/s combined upload and download per IP, set `PUBLIC_REQUESTS_PER_SECOND=100` and `PUBLIC_BYTES_PER_SECOND=16777216`. Both start with a one-second burst. Adjust these numbers after measuring the actual workload; do not copy them as a universal production policy.

ObjectStore uses the socket peer as the client IP and ignores forwarded-IP headers by default. If a reverse proxy sits between external clients and ObjectStore, set `PUBLIC_TRUSTED_PROXY_IPS` to the exact IP address that ObjectStore sees for that proxy and configure the proxy to **replace** `X-Real-IP` with its actual client IP. A request from that trusted peer without exactly one valid numeric `X-Real-IP` is rejected. Do not trust an address reachable by arbitrary clients, and preserve the original `Host` header for Signature V4. In Docker, the proxy's address as seen by the container may be a bridge address rather than `127.0.0.1`. If proxy trust is not configured, all clients behind that proxy share its budget; this is safe from header spoofing but may throttle them together.

Enable these limits only on a deliberately public endpoint. They also apply to direct localhost storage calls to that same endpoint; leave them disabled for the local-only setup or run a separate local-only instance if local storage calls must be exempt. A direct loopback `/health` probe without a forwarded-IP header remains exempt. The byte limit is aggregate ingress plus egress for each IP, and several users behind one NAT share it. If a proxy buffers complete uploads before forwarding them, the upload byte limit controls proxy-to-ObjectStore traffic, not the client's initial upload speed; disable request buffering when end-to-end upload pacing is required. It is a fairness control, not a defense against connection floods before the Java handler runs. Put an internet-facing proxy or firewall in front of the gateway for TLS, connection limits, request timeouts, and buffering controls. Do not expose storage nodes or PostgreSQL publicly.

The default local cluster still uses plaintext node and PostgreSQL connections. The optional TLS overlay secures node traffic, but PostgreSQL TLS, automatic metadata failover, full IAM, and physical host verification remain absent. The key-scope file and policy subset limit additional signed identities; they are not an IAM replacement. Garbage collection can remove data required by an older metadata backup, so its retention guard is essential. Host UUIDs are operator labels, not proof that machines have separate power, disks, or network paths. Keep `CLUSTER_LOCAL_DEV=true` limited to local tests.

The standalone cluster node binds to localhost by default. Set `NODE_BIND` only for a private test network; the Compose file binds inside its private Docker network. PostgreSQL JDBC 42.7.14 is bundled in the image with its license inside the JAR.
