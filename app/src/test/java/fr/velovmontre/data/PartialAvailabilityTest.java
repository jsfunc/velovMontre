package fr.velovmontre.data;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static fr.velovmontre.data.Availability.State.*;

public class PartialAvailabilityTest {
    private static final long NOW = Instant.parse("2026-10-13T18:00:00Z").toEpochMilli();

    private String feed(String... ids) {
        String rows = java.util.Arrays.stream(ids).map(id -> """
                {"station_id":"%s","last_reported":"2026-10-13T18:00:00Z",
                "num_docks_available":8,"is_installed":true,"is_returning":true,"is_renting":true,
                "vehicle_types_available":[{"vehicle_type_id":"electrical","count":3},{"vehicle_type_id":"mechanical","count":7}]}
                """.formatted(id)).collect(java.util.stream.Collectors.joining(","));
        return "{\"version\":\"3.0\",\"last_updated\":\"2026-10-13T18:00:00Z\",\"data\":{\"stations\":[" + rows + "]}}";
    }
    private String geo(String... ids) {
        String rows = java.util.Arrays.stream(ids).map(id -> """
                {"properties":{"number":%s,"last_update":"2026-10-13T18:00:00Z",
                "available_bike_stands":8,"status":"OPEN",
                "total_stands":{"availabilities":{"electricalBikes":3,"mechanicalBikes":7}}}}
                """.formatted(id)).collect(java.util.stream.Collectors.joining(","));
        return "{\"features\":[" + rows + "]}";
    }
    private Availability.Snapshot snapshot(String id, long observed, long feedTime, boolean returning) {
        return new Availability.Snapshot(NOW, feedTime, id,
                Map.of(id, new Availability.Station(id, 8, true, returning, observed)));
    }

    @Test public void partialPrimaryIsFilledFromAnotherSource() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new FeedClient(url -> switch (calls.incrementAndGet()) {
            case 1 -> geo("7046", "7012");
            case 2 -> feed(Availability.FAVORITES.subList(2, Availability.FAVORITES.size()).toArray(new String[0]));
            default -> throw new AssertionError("Both pages already complete");
        }, () -> NOW);
        var data = client.fetch();
        assertEquals(2, calls.get());
        for (String id : Availability.FAVORITES) assertEquals(AVAILABLE, data.state(id, NOW));
        assertEquals("Grand Lyon GeoJSON", data.sourceFor("7046"));
        assertEquals("Métropole de Lyon / JCDecaux", data.sourceFor("7013"));
    }

    @Test public void completePrimaryAvoidsExtraDownloads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var data = new FeedClient(url -> { calls.incrementAndGet(); return geo(Availability.FAVORITES.toArray(new String[0])); }, () -> NOW).fetch();
        assertEquals(1, calls.get());
        assertEquals(16, data.stations().size());
    }

    @Test public void partialDataSurvivesOtherSourcesFailing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var data = new FeedClient(url -> {
            if (calls.incrementAndGet() == 1) return geo("7046");
            throw new IOException("offline");
        }, () -> NOW).fetch();
        assertEquals(3, calls.get());
        assertEquals(AVAILABLE, data.state("7046", NOW));
        assertEquals(UNKNOWN, data.state("7013", NOW));
    }

    @Test public void mergingKeepsEachFeedClockAndOriginalCacheDownloadTime() {
        var cached = new Availability.Snapshot(NOW - 120_000, NOW - 180_000, "cached",
                Map.of("7046", new Availability.Station("7046", 8, true, true, NOW - 60_000)));
        var merged = cached.merge(snapshot("7013", NOW, NOW, true), NOW);
        assertEquals(NOW - 120_000, merged.fetchTime("7046"));
        assertEquals(NOW - 180_000, merged.feedTime("7046"));
        assertEquals(NOW - 60_000, merged.stations().get("7046").reportedAt());
        assertEquals(STALE, merged.state("7046", NOW + 120_000));
        assertEquals(AVAILABLE, merged.state("7013", NOW + 120_000));
        assertFalse(merged.showCount("7046", NOW + 3_420_000));
        assertTrue(merged.showCount("7013", NOW + 3_420_000));
        var gson = new com.google.gson.Gson();
        assertEquals(merged, gson.fromJson(gson.toJson(merged), Availability.Snapshot.class));
    }

    @Test public void newerClosureWinsAndOldReadingCannotReplaceNewerOne() {
        var old = snapshot("7046", NOW - 120_000, NOW, true);
        var closed = snapshot("7046", NOW, NOW, false);
        assertEquals(CLOSED, old.merge(closed, NOW).state("7046", NOW));
        assertEquals(CLOSED, closed.merge(old, NOW).state("7046", NOW));
        assertFalse(old.merge(closed, NOW).showCount("7046", NOW + 600_000));
    }

    @Test public void incompleteNewResponseDoesNotEraseRetainedCount() {
        var previous = snapshot("7046", NOW - 600_000, NOW, true);
        var invalid = new Availability.Snapshot(NOW, NOW, "invalid", Map.of("7046",
                new Availability.Station("7046", null, true, true, NOW)));
        assertTrue(previous.merge(invalid, NOW).showCount("7046", NOW));
    }

    @Test public void oldButUnderOneHourFeedCanBeDisplayedWhenNoFresherSourceWorks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var data = new FeedClient(url -> {
            if (calls.incrementAndGet() == 1) return geo("7046").replace("18:00:00", "17:50:00");
            throw new IOException("offline");
        }, () -> NOW).fetch();
        assertEquals(STALE, data.state("7046", NOW));
        assertTrue(data.showCount("7046", NOW));
        assertFalse(data.showCount("7046", NOW + 3_000_000));
    }

    @Test public void diagnosticExplainsOldAndMissingRowsEvenAfterSuccessfulFetch() {
        var text = AvailabilityDiagnostics.describe(snapshot("7046", NOW - 600_000, NOW, true), NOW);
        assertTrue(text.contains("7046 : 8 places (ancien, en rouge)"));
        assertTrue(text.contains("Observation : il y a 600 s"));
        assertTrue(text.contains("7013 : aucun relevé reçu"));
    }
}
