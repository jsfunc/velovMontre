package fr.velovmontre.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpTransportTest {
    private static final String ADDRESS = "https://example.org/feed.json";
    private static class Connection extends HttpURLConnection {
        int code = 200;
        String location;
        String encoding;
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        boolean disconnected;
        Connection(URL url) { super(url); }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public String getContentEncoding() { return encoding; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

    @Test public void followsRelativeHttpsRedirectAndSetsWatchTimeouts() throws Exception {
        List<Connection> calls = new ArrayList<>();
        var transport = new HttpTransport(url -> {
            var connection = new Connection(url);
            if (calls.isEmpty()) { connection.code = 302; connection.location = "/new.json"; }
            calls.add(connection);
            return connection;
        });
        assertEquals("{}", transport.get(ADDRESS));
        assertEquals("/new.json", calls.get(1).getURL().getPath());
        for (var connection : calls) {
            assertEquals(10_000, connection.getConnectTimeout());
            assertEquals(10_000, connection.getReadTimeout());
            assertFalse(connection.getUseCaches());
            assertTrue(connection.disconnected);
        }
    }

    @Test public void rejectsDowngradeAndLimitsRedirectLoops() {
        List<Connection> calls = new ArrayList<>();
        var insecure = new HttpTransport(url -> {
            var connection = new Connection(url);
            connection.code = 307; connection.location = "http://example.org/feed.json";
            calls.add(connection);
            return connection;
        });
        assertTrue(assertThrows(IOException.class, () -> insecure.get(ADDRESS)).getMessage().contains("HTTPS"));
        assertEquals(1, calls.size());
        calls.clear();
        var loop = new HttpTransport(url -> {
            var connection = new Connection(url);
            connection.code = 308; connection.location = ADDRESS;
            calls.add(connection);
            return connection;
        });
        assertTrue(assertThrows(IOException.class, () -> loop.get(ADDRESS)).getMessage().contains("redirects"));
        assertEquals(4, calls.size());
        assertTrue(calls.stream().allMatch(c -> c.disconnected));
    }

    @Test public void decodesGzipAndEnforcesDecodedSizeLimit() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write("{\"data\":[]}".getBytes(StandardCharsets.UTF_8)); }
        var connection = new Connection(new URL(ADDRESS));
        connection.encoding = "gzip"; connection.body = bytes.toByteArray();
        assertEquals("{\"data\":[]}", new HttpTransport(url -> connection).get(ADDRESS));
        connection.encoding = null; connection.body = new byte[HttpTransport.MAX_BYTES + 1];
        assertThrows(IOException.class, () -> new HttpTransport(url -> connection).get(ADDRESS));
        assertTrue(connection.disconnected);
    }

    @Test public void reportsHttpErrorsAndDisconnects() throws Exception {
        var connection = new Connection(new URL(ADDRESS));
        connection.code = 403;
        assertEquals("HTTP 403", assertThrows(IOException.class,
                () -> new HttpTransport(url -> connection).get(ADDRESS)).getMessage());
        assertTrue(connection.disconnected);
    }

    @Test public void cancellationStopsAnActiveRequestAndPreventsOpeningANewOne() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var disconnectSignal = new java.util.concurrent.CountDownLatch(1);
        var connection = new Connection(new URL(ADDRESS)) {
            @Override public InputStream getInputStream() {
                entered.countDown();
                try { disconnectSignal.await(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ByteArrayInputStream(new byte[0]);
            }
            @Override public void disconnect() { super.disconnect(); disconnectSignal.countDown(); }
        };
        var request = new HttpTransport(url -> connection).request(ADDRESS);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(request::get);
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            request.cancel();
            future.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(connection.disconnected);
            assertThrows(IOException.class, request::get);
        } finally { request.cancel(); worker.shutdownNow(); }
    }
}
