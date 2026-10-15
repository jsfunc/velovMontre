package fr.velovmontre.data;

import org.junit.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static fr.velovmontre.data.Availability.State.*;

public class AvailabilityTest {
    private static final long NOW = Instant.parse("2026-10-13T18:00:00Z").toEpochMilli();

    private Availability.Snapshot snapshot(Integer docks, Boolean installed, Boolean returning, long reported) {
        return new Availability.Snapshot(NOW, NOW, "test",
                Map.of("7046", new Availability.Station("7046", docks, installed, returning, reported)));
    }

    @Test public void favoritesPreserveRequestedOrder() {
        assertEquals(List.of("7046", "7012", "7006", "7013", "7049", "7005", "7007", "2020"), Availability.PAGES.get(0));
        assertEquals(List.of("5030", "5047", "5008", "5016", "5007", "5001", "5053", "5015"), Availability.PAGES.get(1));
        assertEquals(16, Availability.FAVORITES.size());
        assertEquals(16, new java.util.HashSet<>(Availability.FAVORITES).size());
        assertEquals(Availability.PAGES.get(1), Availability.FAVORITES.subList(8, 16));
    }

    @Test public void countsDistinguishAvailableLowAndFull() {
        assertEquals(AVAILABLE, snapshot(8, true, true, NOW).state("7046", NOW));
        assertEquals(LOW, snapshot(1, true, true, NOW).state("7046", NOW));
        assertEquals(FULL, snapshot(0, true, true, NOW).state("7046", NOW));
    }

    @Test public void closedStationNeverAdvertisesItsEmptyDocks() {
        assertEquals(CLOSED, snapshot(18, true, false, NOW).state("7046", NOW));
        assertEquals(CLOSED, snapshot(18, false, true, NOW).state("7046", NOW));
    }

    @Test public void missingFieldsNeverBecomeZeroOrAvailable() {
        assertEquals(UNKNOWN, snapshot(null, true, true, NOW).state("7046", NOW));
        assertEquals(UNKNOWN, snapshot(8, null, true, NOW).state("7046", NOW));
        assertEquals(UNKNOWN, snapshot(8, true, null, NOW).state("7046", NOW));
        assertEquals(UNKNOWN, snapshot(8, true, true, NOW).state("7006", NOW));
    }

    @Test public void expiresAtFiveMinutesWithoutAnotherFetch() {
        var data = snapshot(8, true, true, NOW);
        assertEquals(AVAILABLE, data.state("7046", NOW + Availability.MAX_AGE_MS - 1));
        assertEquals(STALE, data.state("7046", NOW + Availability.MAX_AGE_MS));
    }

    @Test public void fetchingAnOldObservationDoesNotFreshenIt() {
        assertEquals(STALE, snapshot(8, true, true, NOW - 600_000).state("7046", NOW));
        var data = new Availability.Snapshot(NOW, NOW - 600_000, "test",
                snapshot(8, true, true, NOW).stations());
        assertEquals(STALE, data.state("7046", NOW));
    }

    @Test public void futureAndMissingTimestampsAreUnknown() {
        assertEquals(UNKNOWN, snapshot(8, true, true, NOW + 120_000).state("7046", NOW));
        assertEquals(UNKNOWN, snapshot(8, true, true, 0).state("7046", NOW));
    }

    private String feed(String version, String timestamp, String docks, String installed, String returning) {
        return "{\"version\":\"" + version + "\",\"last_updated\":" + timestamp
                + ",\"data\":{\"stations\":[{\"station_id\":\"7046\",\"last_reported\":" + timestamp
                + ",\"num_docks_available\":" + docks + ",\"is_installed\":" + installed
                + ",\"is_returning\":" + returning + "}]}}";
    }

    private String validFeed() { return feed("3.0", "\"2026-10-13T18:00:00Z\"", "8", "true", "true"); }

    @Test public void parsesV3IsoTimestampAndExplicitDockCount() {
        var parsed = new GbfsParser().parse(validFeed(), "test", NOW);
        assertEquals(NOW, parsed.stations().get("7046").reportedAt());
        assertEquals(Integer.valueOf(8), parsed.stations().get("7046").freeDocks());
        assertEquals(AVAILABLE, parsed.state("7046", NOW));
    }

    @Test public void parsesV2EpochSecondsAndNumericFlags() {
        var parsed = new GbfsParser().parse(feed("2.3", String.valueOf(NOW / 1000), "2", "1", "1"), "test", NOW);
        assertEquals(NOW, parsed.feedUpdatedAt());
        assertEquals(LOW, parsed.state("7046", NOW));
    }

    @Test public void malformedCountsAndFlagsRemainUnknown() {
        for (String count : List.of("null", "-1", "1.5", "\"8\"", "true", "999999999999")) {
            assertEquals(UNKNOWN, new GbfsParser().parse(feed("2.3", String.valueOf(NOW / 1000), count,
                    "true", "true"), "test", NOW).state("7046", NOW));
        }
        assertEquals(UNKNOWN, new GbfsParser().parse(feed("2.3", String.valueOf(NOW / 1000), "8",
                "\"false\"", "true"), "test", NOW).state("7046", NOW));
    }

    @Test public void fallsBackWhenPrimaryRequestFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new FeedClient(url -> {
            if (calls.incrementAndGet() == 1) throw new IOException("offline");
            return validFeed();
        }, () -> NOW);
        assertEquals("Métropole de Lyon / JCDecaux", client.fetch().source());
        assertEquals(3, calls.get());
    }

    @Test public void fallsBackWhenPrimaryIsStaleOrMalformed() throws Exception {
        for (String primary : List.of("<html>error</html>",
                feed("2.3", String.valueOf((NOW - 600_000) / 1000), "8", "true", "true"))) {
            AtomicInteger calls = new AtomicInteger();
            var client = new FeedClient(url -> calls.incrementAndGet() == 1 ? primary : validFeed(), () -> NOW);
            assertEquals(AVAILABLE, client.fetch().state("7046", NOW));
            assertEquals(3, calls.get());
        }
    }

    @Test public void completeFailureIsReportedRatherThanInventingAvailability() {
        var client = new FeedClient(url -> { throw new IOException("offline"); }, () -> NOW);
        assertThrows(IOException.class, client::fetch);
    }

    @Test public void cacheRoundTripPreservesObservationTimes() {
        var gson = new com.google.gson.Gson();
        var original = snapshot(8, true, true, NOW - 600_000);
        var restored = gson.fromJson(gson.toJson(original), Availability.Snapshot.class);
        assertEquals(original, restored);
        assertEquals(STALE, restored.state("7046", NOW));
    }
}
