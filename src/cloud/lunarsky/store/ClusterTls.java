package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class ClusterTls {
    private ClusterTls() { }

    static HttpServer nodeServer(InetSocketAddress address, Map<String, String> env) throws IOException {
        String keyStore = env.get("NODE_TLS_KEYSTORE");
        String passwordFile = env.get("NODE_TLS_PASSWORD_FILE");
        if (missing(keyStore) && missing(passwordFile)) return HttpServer.create(address, 64);
        if (missing(keyStore) || missing(passwordFile))
            throw new IOException("Node TLS requires both NODE_TLS_KEYSTORE and NODE_TLS_PASSWORD_FILE");
        SSLContext context = serverContext(Path.of(keyStore), Path.of(passwordFile));
        HttpsServer server = HttpsServer.create(address, 64);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        return server;
    }

    static HttpClient client(Map<String, String> env, Duration timeout) throws IOException {
        String trustStore = env.get("CLUSTER_TLS_TRUSTSTORE");
        String passwordFile = env.get("CLUSTER_TLS_PASSWORD_FILE");
        if (missing(trustStore) != missing(passwordFile))
            throw new IOException("Cluster TLS requires both CLUSTER_TLS_TRUSTSTORE and CLUSTER_TLS_PASSWORD_FILE");
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
        if (!missing(trustStore))
            builder.sslContext(clientContext(Path.of(trustStore), Path.of(passwordFile)));
        return builder.build();
    }

    private static SSLContext serverContext(Path keyStore, Path passwordFile) throws IOException {
        char[] password = password(passwordFile);
        try {
            KeyStore keys = load(keyStore, password);
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not configure node TLS", error);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static SSLContext clientContext(Path trustStore, Path passwordFile) throws IOException {
        char[] password = password(passwordFile);
        try {
            KeyStore trust = load(trustStore, password);
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not configure cluster TLS trust", error);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static KeyStore load(Path path, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private static char[] password(Path file) throws IOException {
        String value = Files.readString(file, StandardCharsets.UTF_8);
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IOException("Cluster TLS password file is empty");
        return value.toCharArray();
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
