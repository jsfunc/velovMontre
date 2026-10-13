package fr.velovmontre.data;

import com.google.gson.*;
import fr.velovmontre.StationPresentation;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshProtocolTest {
    private static final long NOW = 1_800_000_000_000L;
    private static String geo(long age, boolean missingBike) {
        JsonArray rows = new JsonArray();
        for (String id : Availability.FAVORITES) {
            JsonObject row = new JsonObject(); row.addProperty("number", id); row.addProperty("status", "OPEN");
            row.addProperty("last_update", (NOW - age) / 1000); row.addProperty("last_update_fme", NOW / 1000);
            row.addProperty("available_bike_stands", 11);
            JsonObject counts = new JsonObject();
            if (!missingBike) counts.addProperty("electricalBikes", 3);
            counts.addProperty("mechanicalBikes", 7);
            JsonObject total = new JsonObject(); total.add("availabilities", counts); row.add("total_stands", total);
            JsonObject feature = new JsonObject(); feature.add("properties", row); rows.add(feature);
        }
        JsonObject root = new JsonObject(); root.add("features", rows); return root.toString();
    }
    private static String gbfs(long age, int electric, int docks) {
        JsonArray rows = new JsonArray();
        for (String id : Availability.FAVORITES) {
            JsonObject row = new JsonObject(); row.addProperty("station_id", id);
            row.addProperty("last_reported", (NOW - age) / 1000); row.addProperty("num_docks_available", docks);
            row.addProperty("is_installed", true); row.addProperty("is_renting", true); row.addProperty("is_returning", true);
            JsonArray types = new JsonArray();
            for (String type : List.of("electrical", "mechanical")) {
                JsonObject t = new JsonObject(); t.addProperty("vehicle_type_id", type);
                t.addProperty("count", type.equals("electrical") ? electric : 9); types.add(t);
            }
            row.add("vehicle_types_available", types); rows.add(row);
        }
        JsonObject data = new JsonObject(); data.add("stations", rows);
        JsonObject root = new JsonObject(); root.addProperty("version", "2.3");
        root.addProperty("last_updated", NOW / 1000); root.add("data", data); return root.toString();
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IOException("test latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    @Test public void freshCompleteGeoStopsWithoutCallingFallbacks() throws Exception {
        var calls = new ArrayList<String>(); var updates = new ArrayList<Availability.Snapshot>();
        var client = new FeedClient(url -> { calls.add(url); return geo(0, false); }, () -> NOW);
        var result = client.fetch(Availability.Snapshot.empty(), updates::add);
        assertEquals(List.of(FeedClient.SOURCES.get(2).url()), calls);
        assertEquals(1, updates.size()); assertTrue(result.allGreen(NOW));
    }

    @Test public void orangeDataIsPublishedThenTriggersImmediateGbfs() throws Exception {
        AtomicInteger calls = new AtomicInteger(); var updates = new ArrayList<Availability.Snapshot>();
        var client = new FeedClient(url -> calls.incrementAndGet() == 1 ? geo(180_000, false) : gbfs(0, 4, 12), () -> NOW);
        var result = client.fetch(Availability.Snapshot.empty(), updates::add);
        assertEquals(2, calls.get()); assertEquals(2, updates.size());
        assertEquals(2, updates.get(0).displayLevel("7046", NOW));
        assertEquals("3-7-11", StationPresentation.value(updates.get(0), "7046", NOW));
        assertTrue(result.allGreen(NOW)); assertEquals("4-9-12", StationPresentation.value(result, "7046", NOW));
    }

    @Test public void oldGbfsFillsOnlyMissingCountAndStillTriggersCyclocity() throws Exception {
        var calls = new ArrayList<String>(); var updates = new ArrayList<Availability.Snapshot>();
        var client = new FeedClient(url -> {
            calls.add(url);
            if (calls.size() == 1) return geo(0, true);
            return gbfs(4 * 3_600_000L, calls.size() == 2 ? 4 : 5, 99);
        }, () -> NOW);
        var result = client.fetch(Availability.Snapshot.empty(), updates::add);
        assertEquals(FeedClient.REQUEST_ORDER.stream().map(FeedClient.Source::url).toList(), calls);
        assertEquals("4-7-11", StationPresentation.value(result, "7046", NOW));
        assertEquals(1, result.displayLevel("7046", NOW)); // One uncertain field makes the row red.
        assertEquals(3, result.reading("7046", Availability.Metric.DOCKS).level(NOW));
        assertEquals(FeedClient.SOURCES.get(1).name(), result.reading("7046", Availability.Metric.ELECTRIC).source());
        assertEquals(3, updates.size());
        var restored = new Gson().fromJson(new Gson().toJson(result), Availability.Snapshot.class);
        assertEquals(result, restored);
        assertEquals("_-_-_", StationPresentation.value(restored, "7046", NOW + 3_600_000));
    }

    @Test public void cyclocityCanImproveRemainingRedCountsWithoutErasingGeo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var result = new FeedClient(url -> switch (calls.incrementAndGet()) {
            case 1 -> geo(0, true);
            case 2 -> gbfs(4 * 3_600_000L, 4, 99);
            default -> gbfs(0, 5, 99);
        }, () -> NOW).fetch();
        assertTrue(result.allGreen(NOW));
        assertEquals("5-7-11", StationPresentation.value(result, "7046", NOW));
    }

    @Test public void fallbackStartsAfterHeadStartWhileSlowGeoContinues() throws Exception {
        CountDownLatch fallbackStarted = new CountDownLatch(1);
        AtomicLong geoStart = new AtomicLong(), gbfsStart = new AtomicLong();
        AtomicBoolean geoFinished = new AtomicBoolean();
        var client = new FeedClient(url -> {
            if (url.equals(FeedClient.SOURCES.get(2).url())) {
                geoStart.set(System.nanoTime()); await(fallbackStarted); geoFinished.set(true); return geo(0, false);
            }
            if (url.equals(FeedClient.SOURCES.get(1).url())) {
                assertFalse(geoFinished.get()); gbfsStart.set(System.nanoTime()); fallbackStarted.countDown();
                return gbfs(4 * 3_600_000L, 4, 99);
            }
            throw new IOException("offline");
        }, () -> NOW, 80, 600);
        var result = client.fetch();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(gbfsStart.get() - geoStart.get()) >= 60);
        assertTrue(geoFinished.get()); assertTrue(result.allGreen(NOW));
        assertEquals("3-7-11", StationPresentation.value(result, "7046", NOW));
    }

    @Test public void allHungRequestsHaveBoundedDeadlinesAndAreCancelled() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(3); var seen = ConcurrentHashMap.<String>newKeySet();
        FeedClient.Transport transport = new FeedClient.Transport() {
            public String get(String url) { throw new AssertionError("Use cancellable request"); }
            public FeedClient.Request request(String url) {
                CountDownLatch stop = new CountDownLatch(1);
                return new FeedClient.Request() {
                    public String get() throws IOException { await(stop); throw new IOException("cancelled"); }
                    public void cancel() { stop.countDown(); if (seen.add(url)) cancelled.countDown(); }
                };
            }
        };
        long started = System.nanoTime();
        var error = assertThrows(IOException.class, () -> new FeedClient(transport, () -> NOW, 40, 150).fetch());
        assertEquals(3, error.getSuppressed().length);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
    }

    @Test public void lateCancelledResponseCannotOverwritePublishedResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1), exited = new CountDownLatch(1);
        var updates = new ArrayList<Availability.Snapshot>();
        var client = new FeedClient(url -> {
            if (url.equals(FeedClient.SOURCES.get(2).url())) {
                // Simulate a network stack that does not immediately honour interruption.
                boolean done = false;
                while (!done) try { done = release.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                exited.countDown(); return geo(0, false);
            }
            return gbfs(0, 4, 12);
        }, () -> NOW, 40, 300);
        try {
            var result = client.fetch(Availability.Snapshot.empty(), updates::add);
            assertEquals("4-9-12", StationPresentation.value(result, "7046", NOW));
            release.countDown(); assertTrue(exited.await(1, TimeUnit.SECONDS));
            assertEquals(1, updates.size());
        } finally { release.countDown(); }
    }

    @Test public void gbfsRequiresRecentPublicationAndNeverBecomesGreenFromDownloadAlone() {
        var parser = new GbfsParser();
        var old = parser.parse(gbfs(4 * 3_600_000L, 4, 12), FeedClient.SOURCES.get(1).name(), NOW);
        assertEquals("4-9-12", StationPresentation.value(old, "7046", NOW));
        assertEquals(1, old.displayLevel("7046", NOW));
        assertFalse(old.showAnyCount("7046", NOW + 3_600_000));
        String stalePublication = gbfs(4 * 3_600_000L, 4, 12).replace("\"last_updated\":1800000000", "\"last_updated\":1799996400");
        assertFalse(parser.parse(stalePublication, FeedClient.SOURCES.get(1).name(), NOW).showAnyCount("7046", NOW));
        var newer = parser.parse(gbfs(4 * 3_600_000L, 5, 13), FeedClient.SOURCES.get(1).name(), NOW + 60_000);
        assertEquals("5-9-13", StationPresentation.value(old.merge(newer, NOW + 60_000), "7046", NOW + 60_000));
    }
}
