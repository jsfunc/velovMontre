package fr.velovmontre.data;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Bounded HTTPS downloads, including HTTPS redirects published by the data providers. */
public final class HttpTransport implements FeedClient.Transport {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    private final int timeoutMillis;

    public HttpTransport() { this(url -> (HttpURLConnection) url.openConnection()); }
    HttpTransport(Connections connections) { this(connections, 10_000); }
    HttpTransport(Connections connections, int timeoutMillis) {
        this.connections = connections;
        this.timeoutMillis = timeoutMillis;
    }

    @Override public FeedClient.Request request(String address) {
        return new FeedClient.Request() {
            private volatile HttpURLConnection active;
            private volatile boolean cancelled;
            public String get() throws IOException {
                try {
                    return new HttpTransport(url -> {
                        if (cancelled) throw new IOException("Request cancelled");
                        HttpURLConnection connection = connections.open(url);
                        active = connection;
                        if (cancelled) { connection.disconnect(); throw new IOException("Request cancelled"); }
                        return connection;
                    }, Math.min(timeoutMillis, FeedClient.DEADLINE_MS)).get(address);
                } finally { active = null; }
            }
            public void cancel() {
                cancelled = true;
                HttpURLConnection connection = active;
                if (connection != null) connection.disconnect();
            }
        };
    }

    @Override public String get(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection connection = connections.open(url);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "VelovMontre/1.1.0");
            connection.setInstanceFollowRedirects(false);
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("HTTP " + code + ": no Location");
                    url = new URL(url, location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                try (InputStream stream = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                        ? new GZIPInputStream(connection.getInputStream()) : connection.getInputStream()) {
                    byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) throw new IOException("Feed exceeds size limit");
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many HTTPS redirects");
    }
}
