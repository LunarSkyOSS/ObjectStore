# Two-machine durability drill

Run this disposable test on two machines. Machine A runs the gateway, PostgreSQL, and one storage node; machine B runs a second storage node. Keep the node connection on a private network: this drill uses bearer tokens over HTTP and does not enable the optional node TLS setup.

Both machines need Docker. Machine A also needs Docker Compose and Python 3. The example uses loopback port 9003 on machine A and private-network port 9103 on machine B; change them if needed. Use separate test volumes, and do not point this drill at an existing ObjectStore cluster.

## Start the cluster

On machine A, generate test-only credentials outside the repository and build the current image:

```sh
python3 tests/two-host/prepare.py /path/to/private-test-dir http://MACHINE_B_PRIVATE_IP:9103
docker build -t objectstore-durability:local .
docker save objectstore-durability:local | gzip > /path/to/private-test-dir/objectstore-durability.tar.gz
```

Transfer the image archive and `remote-node.env` to a private directory on machine B. Load the image there, then start its node with a dedicated volume and a port bound only to its private-network address:

```sh
gzip -dc objectstore-durability.tar.gz | sudo docker load
sudo docker run -d --name objectstore-durability-remote --restart unless-stopped \
  --publish MACHINE_B_PRIVATE_IP:9103:9100 \
  --volume objectstore-durability-remote-data:/data \
  --env-file remote-node.env --entrypoint java objectstore-durability:local \
  --add-modules jdk.httpserver,java.net.http -cp /app:/app/postgresql.jar \
  cloud.lunarsky.store.ClusterNode
```

Start the services on machine A from the repository root:

```sh
docker compose --env-file /path/to/private-test-dir/cluster.env \
  -f tests/two-host/compose.yaml up -d --wait gateway
python3 tests/two-host/check.py /path/to/private-test-dir/cluster.env \
  /path/to/private-test-dir/acknowledged.jsonl seed
python3 tests/two-host/check.py /path/to/private-test-dir/cluster.env \
  /path/to/private-test-dir/acknowledged.jsonl verify
```

Before disrupting either machine, check `cluster_segments.replica_ids` against `cluster_nodes.host_id` in the test PostgreSQL database. Every seeded segment must have replicas on two distinct host IDs. The IDs are operator labels; confirm that the nodes run on separate machines.

## Failure checks

Run `check.py ... stress --seconds 120` while both nodes are healthy, then interrupt machine B. The journal records a write only after an HTTP 200 response and calls `fsync` for each entry. New writes should return 503 while only one machine remains. Reads of acknowledged objects should still succeed from the other replica. Restore machine B, wait for its node to answer `/health`, and run `check.py ... verify` against the entire journal.

Stop the node container on machine A and repeat the rejection and read checks using the replica on machine B. Restart the node, then abruptly kill only the disposable gateway, metadata, and node containers on machine A during another stress run. Start them again and verify the journal. A connection closed during a write has an **uncertain** outcome; do not count it as acknowledged or assume it was rejected.

For a manual metadata recovery drill, take a custom-format `pg_dump` after the last acknowledged write and verify it with `pg_restore --list`. Copy the dump to machine B and restore it into a fresh PostgreSQL volume there. Stop the original gateway and metadata container on machine A. Start a separate gateway against the restored database, then verify the journal. Finally stop the storage node on machine A and verify again: this forces reads to use both the restored metadata and the replica on machine B. Keep the backup and its credentials private.

Passing this drill does not prove every crash point, disk-controller write behavior, long-term bit-rot resistance, automatic metadata failover, or production readiness. The cluster still requires a manual metadata restore.
