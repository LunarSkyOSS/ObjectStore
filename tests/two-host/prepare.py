#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
import secrets
import urllib.parse
import uuid


def write_private(path, lines):
    with path.open("x", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("remote_node_url")
    parser.add_argument("--gateway-port", type=int, default=9003)
    args = parser.parse_args()
    url = urllib.parse.urlparse(args.remote_node_url)
    if url.scheme != "http" or not url.hostname or not url.port or url.path or url.query or url.fragment:
        parser.error("remote_node_url must be an http://host:port address without a path")
    if not 1 <= args.gateway_port <= 65535:
        parser.error("gateway port must be between 1 and 65535")
    args.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    args.directory.chmod(0o700)
    access = "DurabilityTest" + secrets.token_hex(8)
    secret = secrets.token_hex(32)
    token = secrets.token_hex(32)
    repair = secrets.token_hex(32)
    remote_id = uuid.uuid4()
    old_umask = os.umask(0o077)
    try:
        write_private(args.directory / "cluster.env", [
            "COMPOSE_PROJECT_NAME=objectstore-durability",
            f"S3_ACCESS_KEY={access}", f"S3_SECRET_KEY={secret}",
            f"CLUSTER_TOKEN={token}", f"CLUSTER_REPAIR_TOKEN={repair}",
            f"POSTGRES_PASSWORD={secrets.token_hex(24)}",
            f"LOCAL_HOST_ID={uuid.uuid4()}", f"REMOTE_HOST_ID={remote_id}",
            f"REMOTE_NODE_URL={args.remote_node_url}",
            f"CLUSTER_HOST_PORT={args.gateway_port}", "S3_BUCKET=durability",
        ])
        write_private(args.directory / "remote-node.env", [
            f"CLUSTER_TOKEN={token}", f"CLUSTER_REPAIR_TOKEN={repair}",
            f"CLUSTER_HOST_ID={remote_id}", "NODE_BIND=0.0.0.0",
            "NODE_PORT=9100", "DATA_DIR=/data",
        ])
    finally:
        os.umask(old_umask)
    print(f"Test configuration written to {args.directory}")


if __name__ == "__main__":
    main()
