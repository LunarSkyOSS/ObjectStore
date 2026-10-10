package cloud.lunarsky.objectstore.client;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Configures a path-style, Signature V4 S3 client without third-party dependencies. */
public final class ObjectStorageClientBuilder {
    private URI endpoint;
    private String region = "us-east-1";
    private String accessKey;
    private String secretKey;
    private Duration timeout = Duration.ofSeconds(30);
    private boolean allowInsecureHttp;

    /** Sets the service root, for example {@code https://storage.example.com}. */
    public ObjectStorageClientBuilder endpoint(URI value) {
        endpoint = Objects.requireNonNull(value, "endpoint");
        return this;
    }

    /** Sets the Signature V4 region. The default is {@code us-east-1}. */
    public ObjectStorageClientBuilder region(String value) {
        region = requireText(value, "region");
        return this;
    }

    /** Sets credentials used only in request signatures. Keep them out of logs. */
    public ObjectStorageClientBuilder credentials(String access, String secret) {
        accessKey = requireText(access, "access key");
        secretKey = requireText(secret, "secret key");
        return this;
    }

    /** Sets the per-request timeout. */
    public ObjectStorageClientBuilder timeout(Duration value) {
        if (Objects.requireNonNull(value, "timeout").isNegative() || value.isZero())
            throw new IllegalArgumentException("timeout must be positive");
        timeout = value;
        return this;
    }

    /** Allows plain HTTP for a trusted local test endpoint. HTTPS is required by default. */
    public ObjectStorageClientBuilder allowInsecureHttp() {
        allowInsecureHttp = true;
        return this;
    }

    /** Builds an independent client. */
    public ObjectStorageClient build() {
        if (endpoint == null) throw new IllegalStateException("endpoint is required");
        if (accessKey == null || secretKey == null) throw new IllegalStateException("credentials are required");
        if (!region.matches("[a-z0-9-]+")) throw new IllegalArgumentException("invalid region");
        if (!accessKey.matches("[A-Za-z0-9_+=./@-]+")) throw new IllegalArgumentException("invalid access key");
        String scheme = endpoint.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !(allowInsecureHttp && "http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("HTTPS endpoint required unless insecure HTTP is explicitly allowed");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null ||
            endpoint.getRawFragment() != null || !(endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty() ||
            "/".equals(endpoint.getRawPath())))
            throw new IllegalArgumentException("endpoint must be an origin without path, query, fragment, or user info");
        return new ObjectStorageClient(endpoint, region, accessKey, secretKey, timeout, Clock.systemUTC());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
