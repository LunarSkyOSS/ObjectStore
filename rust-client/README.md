# ObjectStore Rust client

This small adapter configures the official AWS SDK for Rust for ObjectStore's path-style S3 endpoint. It reuses the SDK's signing, connection pooling, and streaming body support. `put_file` streams a file and `get_to_writer` streams a response into any Tokio async writer. `put_bytes` accepts an existing buffer; `presigned_get_url` and `presigned_put_url` make short-lived links for trusted server code. The underlying SDK client is available through `sdk()` for listing, multipart uploads, and other supported S3 operations.

```toml
[dependencies]
lunarsky-objectstore-client = { path = "../rust-client" }
tokio = { version = "1", features = ["macros", "rt-multi-thread", "fs"] }
```

```rust
use lunarsky_objectstore_client::ObjectStoreClient;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let client = ObjectStoreClient::connect(
        "http://127.0.0.1:9000", "us-east-1",
        &std::env::var("S3_ACCESS_KEY")?, &std::env::var("S3_SECRET_KEY")?,
    ).await?;
    client.put_file("objects", "example.bin", "example.bin").await?;
    let mut output = tokio::fs::File::create("downloaded.bin").await?;
    client.get_to_writer("objects", "example.bin", &mut output).await?;
    Ok(())
}
```

The constructor requires HTTPS except for loopback tests. Keep access keys on the server, never in browser code. The wrapper is untested against every AWS S3 option; inspect ObjectStore's supported-operation list before using SDK methods directly. This crate is not published yet.
