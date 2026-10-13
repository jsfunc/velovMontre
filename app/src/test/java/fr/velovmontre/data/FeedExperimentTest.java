package fr.velovmontre.data;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

public class FeedExperimentTest {
    private static class Connection extends HttpURLConnection {
        byte[] body;
        volatile boolean disconnected;
        final CountDownLatch released = new CountDownLatch(1);
        boolean block;
        int code = 200;
        CountDownLatch concurrent;
        Connection(URL url, String body) { super(url); this.body = body.getBytes(StandardCharsets.UTF_8); }
        @Override public int getResponseCode() throws IOException {
            if (concurrent != null) {
                concurrent.countDown();
                try { if (!concurrent.await(1, TimeUnit.SECONDS)) throw new IOException("not concurrent"); }
                catch (InterruptedException e) { throw new IOException(e); }
            }
            return code;
        }
        @Override public InputStream getInputStream() throws IOException {
            if (block) {
                try { released.await(); } catch (InterruptedException e) { throw new IOException(e); }
                throw new IOException("disconnected by deadline");
            }
            return new ByteArrayInputStream(body);
        }
        @Override public void disconnect() { disconnected = true; released.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

    private String gbfs() {
        String now = Instant.now().toString();
        return "{\"version\":\"3.0\",\"last_updated\":\"" + now + "\",\"data\":{\"stations\":["
                + "{\"station_id\":\"7046\",\"num_docks_available\":8,\"is_installed\":true,\"is_returning\":true,\"last_reported\":\"" + now + "\"}]}}";
    }
    private String geojson() {
        return "{\"features\":[{\"properties\":{\"number\":7046,\"status\":\"OPEN\",\"available_bike_stands\":8,\"last_update\":\""
                + Instant.now() + "\"}}]}";
    }

    @Test public void probesAllSourcesConcurrentlyAndPreservesReturnedClocks() throws Exception {
        CountDownLatch concurrent = new CountDownLatch(3);
        List<Connection> calls = new CopyOnWriteArrayList<>();
        var experiment = new FeedExperiment(url -> {
            var connection = new Connection(url, url.getHost().equals("data.grandlyon.com") ? geojson() : gbfs());
            connection.concurrent = concurrent;
            calls.add(connection);
            return connection;
        }, 2000);
        var samples = experiment.round();
        assertEquals(3, samples.size());
        for (var sample : samples) {
            assertTrue(sample.withinBudget());
            assertNotNull(sample.snapshot());
            assertEquals(Integer.valueOf(8), sample.snapshot().stations().get("7046").freeDocks());
            assertEquals(sample.receivedAt(), sample.snapshot().fetchedAt());
        }
        for (var call : calls) {
            assertTrue(call.disconnected);
            assertEquals("close", call.getRequestProperty("Connection"));
            assertEquals(2000, call.getConnectTimeout());
        }
    }

    @Test public void totalDeadlineAbortsBlockedReadsAndNeverCountsThemAsSuccess() throws Exception {
        List<Connection> calls = new CopyOnWriteArrayList<>();
        var experiment = new FeedExperiment(url -> {
            var connection = new Connection(url, "{}"); connection.block = true;
            calls.add(connection); return connection;
        }, 80);
        long started = System.nanoTime();
        var samples = experiment.round();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
        assertEquals(3, samples.size());
        assertEquals(3, calls.size());
        for (var sample : samples) {
            assertFalse(sample.withinBudget());
            assertNull(sample.snapshot());
            assertTrue(sample.error().contains("Délai total"));
        }
        assertTrue(calls.stream().allMatch(c -> c.disconnected));
    }

    @Test public void fastMalformedJsonIsNotUsefulDataAndHttpErrorsAreNotSuccesses() throws Exception {
        var samples = new FeedExperiment(url -> {
            var connection = new Connection(url, "<html>not json</html>");
            if (url.getHost().equals("api.cyclocity.fr")) connection.code = 503;
            return connection;
        }, 1000).round();
        assertFalse(samples.get(0).withinBudget());
        assertTrue(samples.get(1).withinBudget());
        assertNull(samples.get(1).snapshot());
        assertTrue(FeedExperiment.report(samples).contains("JSON utilisables : 0"));
    }

    @Test public void reportKeepsOriginalFourStationCohortAndExcludesTimeoutsFromMedian() {
        long now = System.currentTimeMillis();
        var snapshot = new Availability.Snapshot(now, now, "test", Map.of(
                "7046", new Availability.Station("7046", 8, true, true, now),
                "7012", new Availability.Station("7012", 8, true, true, now),
                "7006", new Availability.Station("7006", 8, true, true, now),
                "7013", new Availability.Station("7013", 8, true, true, now)));
        var source = FeedClient.SOURCES.get(0);
        String report = FeedExperiment.report(List.of(
                new FeedExperiment.Sample(source.name(), source.url(), now, now, 250L, true, "", snapshot),
                new FeedExperiment.Sample(source.name(), source.url(), now, now, 5000L, false, "timeout", null)));
        assertTrue(report.contains("Reçues ≤5 s : 1/2"));
        assertTrue(report.contains("4 relevés <5 min : 1"));
        assertTrue(report.contains("Médiane réseau : 0,3 s"));
        assertFalse(report.contains("7049 :"));
    }
}
