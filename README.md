# ObjectStore

Small S3-compatible object storage for LunarSky. Early development.

Requires Docker Compose. Copy `.env.example` to `.env`, then set `S3_ACCESS_KEY` and `S3_SECRET_KEY` to unique values. The access key must be at least 16 alphanumeric characters; the secret must be at least 32 characters.

```sh
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:9000/health
```

Port 9000 binds to localhost. Data stays in the `object-data` Docker volume. `docker compose down -v` deletes that volume.

Run `sh scripts/test.sh` with JDK 21 to test from source. Lunaris uses ObjectStore through its S3 storage adapter.

Do not use this development version as the only copy of important data.
