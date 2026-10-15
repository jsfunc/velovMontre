package fr.velovmontre.data;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SelectedStationsTest {
    private static final long NOW = 1_800_000_000_000L;
    private String geo() { return """
            {"features":[{"properties":{"number":1001,"status":"OPEN","last_update":1800000000,
            "last_update_fme":1800000000,"available_bike_stands":5,
            "total_stands":{"availabilities":{"electricalBikes":2,"mechanicalBikes":3}}}}]}
            """; }
    @Test public void previouslyUnlistedStationWorksFromFetchThroughCacheAndMerge() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var snapshot = new FeedClient(url -> { calls.incrementAndGet(); assertTrue(url.contains("IN%20(1001)")); return geo(); },
                () -> NOW, List.of("1001")).fetch();
        assertEquals(1, calls.get()); assertTrue(snapshot.allGreen(NOW, List.of("1001")));
        assertEquals(Integer.valueOf(5), snapshot.displayedCount("1001", Availability.Metric.DOCKS, NOW));
        var restored = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(snapshot), Availability.Snapshot.class);
        assertEquals(snapshot, restored); assertEquals(1, restored.stations().size());
    }
    @Test public void gbfsFallbackUsesSelectedIdsAndStopsWhenThoseAreComplete() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var snapshot = new FeedClient(url -> {
            if (calls.incrementAndGet() == 1) throw new java.io.IOException("offline");
            return """
                {"version":"2.3","last_updated":1800000000,"data":{"stations":[
                {"station_id":"1001","last_reported":1800000000,"is_installed":true,"is_returning":true,
                "is_renting":true,"num_docks_available":5,"vehicle_types_available":[
                {"vehicle_type_id":"electrical","count":2},{"vehicle_type_id":"mechanical","count":3}]}]}}
                """;
        }, () -> NOW, List.of("1001")).fetch();
        assertEquals(2, calls.get()); assertTrue(snapshot.allGreen(NOW, List.of("1001")));
    }
    @Test public void emptySelectionNeverRequestsFullFeed() throws Exception {
        var empty = new FeedClient(url -> { throw new AssertionError("No network with no stations"); }, () -> NOW, List.of()).fetch();
        assertTrue(empty.stations().isEmpty());
    }
    @Test public void changingSelectionPrunesRemovedStationsFromSuccessfulSnapshot() throws Exception {
        var old = new Availability.Snapshot(NOW, NOW, "test", Map.of("7046", new Availability.Station("7046", 5, true, true, NOW)));
        var snapshot = new FeedClient(url -> geo(), () -> NOW, List.of("1001")).fetch(old, s -> { });
        assertEquals(java.util.Set.of("1001"), snapshot.stations().keySet());
    }
}
