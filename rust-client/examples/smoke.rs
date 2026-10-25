use lunarsky_objectstore_client::ObjectStoreClient;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 7 {
        return Err(
            "usage: smoke ENDPOINT BUCKET KEY INPUT OUTPUT ACCESS_KEY (set S3_SECRET_KEY)".into(),
        );
    }
    let client = ObjectStoreClient::connect(
        &args[1],
        "us-east-1",
        &args[6],
        &std::env::var("S3_SECRET_KEY")?,
    )
    .await?;
    client.put_file(&args[2], &args[3], &args[4]).await?;
    let mut file = tokio::fs::File::create(&args[5]).await?;
    client.get_to_writer(&args[2], &args[3], &mut file).await?;
    Ok(())
}
