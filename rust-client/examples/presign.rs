use lunarsky_objectstore_client::ObjectStoreClient;
use std::time::Duration;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 6 {
        return Err(
            "usage: presign ENDPOINT BUCKET KEY ACCESS_KEY get|put (set S3_SECRET_KEY)".into(),
        );
    }
    let client = ObjectStoreClient::connect(
        &args[1],
        "us-east-1",
        &args[4],
        &std::env::var("S3_SECRET_KEY")?,
    )
    .await?;
    let expiry = Duration::from_secs(60);
    let url = match args[5].as_str() {
        "get" => client.presigned_get_url(&args[2], &args[3], expiry).await?,
        "put" => client.presigned_put_url(&args[2], &args[3], expiry).await?,
        _ => return Err("method must be get or put".into()),
    };
    println!("{url}");
    Ok(())
}
