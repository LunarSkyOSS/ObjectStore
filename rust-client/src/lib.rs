use aws_config::BehaviorVersion;
use aws_credential_types::Credentials;
use aws_sdk_s3::{operation, presigning::PresigningConfig, primitives::ByteStream, Client};
use aws_types::region::Region;
use std::{error::Error, path::Path, time::Duration};
use tokio::io::{AsyncWrite, AsyncWriteExt};
use url::Url;

pub type ClientResult<T> = Result<T, Box<dyn Error + Send + Sync>>;

#[derive(Clone)]
pub struct ObjectStoreClient {
    client: Client,
}

impl ObjectStoreClient {
    pub async fn connect(
        endpoint: &str,
        region: &str,
        access_key: &str,
        secret_key: &str,
    ) -> ClientResult<Self> {
        let parsed = Url::parse(endpoint)?;
        let host = parsed.host_str().ok_or("endpoint needs a host")?;
        let local = matches!(host, "localhost" | "127.0.0.1" | "::1");
        if parsed.scheme() != "https" && !(parsed.scheme() == "http" && local) {
            return Err("use HTTPS for a nonlocal ObjectStore endpoint".into());
        }
        if parsed.path() != "/"
            || parsed.query().is_some()
            || parsed.fragment().is_some()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return Err("endpoint must be an origin without path, query, or credentials".into());
        }
        let credentials = Credentials::new(access_key, secret_key, None, None, "objectstore");
        let shared = aws_config::defaults(BehaviorVersion::latest())
            .credentials_provider(credentials)
            .region(Region::new(region.to_owned()))
            .load()
            .await;
        let config = aws_sdk_s3::config::Builder::from(&shared)
            .endpoint_url(endpoint)
            .force_path_style(true)
            .build();
        Ok(Self {
            client: Client::from_conf(config),
        })
    }

    pub fn sdk(&self) -> &Client {
        &self.client
    }

    pub async fn put_file(
        &self,
        bucket: &str,
        key: &str,
        path: impl AsRef<Path>,
    ) -> ClientResult<operation::put_object::PutObjectOutput> {
        let body = ByteStream::from_path(path.as_ref().to_path_buf()).await?;
        Ok(self
            .client
            .put_object()
            .bucket(bucket)
            .key(key)
            .body(body)
            .send()
            .await?)
    }

    pub async fn put_bytes(
        &self,
        bucket: &str,
        key: &str,
        bytes: Vec<u8>,
    ) -> ClientResult<operation::put_object::PutObjectOutput> {
        Ok(self
            .client
            .put_object()
            .bucket(bucket)
            .key(key)
            .body(ByteStream::from(bytes))
            .send()
            .await?)
    }

    pub async fn get_to_writer<W: AsyncWrite + Unpin>(
        &self,
        bucket: &str,
        key: &str,
        writer: &mut W,
    ) -> ClientResult<u64> {
        let response = self
            .client
            .get_object()
            .bucket(bucket)
            .key(key)
            .send()
            .await?;
        let mut reader = response.body.into_async_read();
        let bytes = tokio::io::copy(&mut reader, writer).await?;
        writer.flush().await?;
        Ok(bytes)
    }

    pub async fn delete(&self, bucket: &str, key: &str) -> ClientResult<()> {
        self.client
            .delete_object()
            .bucket(bucket)
            .key(key)
            .send()
            .await?;
        Ok(())
    }

    pub async fn presigned_get_url(
        &self,
        bucket: &str,
        key: &str,
        expires_in: Duration,
    ) -> ClientResult<String> {
        let request = self
            .client
            .get_object()
            .bucket(bucket)
            .key(key)
            .presigned(PresigningConfig::expires_in(expires_in)?)
            .await?;
        Ok(request.uri().to_string())
    }

    pub async fn presigned_put_url(
        &self,
        bucket: &str,
        key: &str,
        expires_in: Duration,
    ) -> ClientResult<String> {
        let request = self
            .client
            .put_object()
            .bucket(bucket)
            .key(key)
            .presigned(PresigningConfig::expires_in(expires_in)?)
            .await?;
        Ok(request.uri().to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::ObjectStoreClient;

    #[tokio::test]
    async fn rejects_insecure_remote_endpoint() {
        assert!(
            ObjectStoreClient::connect("http://storage.example", "us-east-1", "key", "secret")
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn rejects_endpoint_with_path() {
        assert!(ObjectStoreClient::connect(
            "https://storage.example/bucket",
            "us-east-1",
            "key",
            "secret"
        )
        .await
        .is_err());
    }
}
