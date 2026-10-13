package fr.velovmontre.data;

import com.google.gson.Gson;
import fr.velovmontre.StationPresentation;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class BikeAvailabilityTest {
    private static final long NOW = 1_800_000_000_000L;

    private String gbfs(String version, String time, String types, String renting, String returning) {
        return """
                {"version":"%s","last_updated":%s,"data":{"stations":[{
                "station_id":"7046","num_docks_available":11,"is_installed":true,
                "is_renting":%s,"is_returning":%s,"last_reported":%s,
                "vehicle_types_available":%s}]}}
                """.formatted(version, time, renting, returning, time, types);
    }

    private static final String TYPES = """
            [{"vehicle_type_id":"mechanical","count":7},
             {"vehicle_type_id":"electrical","count":3}]
            """;

    @Test public void bothGbfsVersionsUseTypeIdsAndKeepDisplayOrder() {
        for (String version : new String[] {"2.3", "3.0"}) {
            String time = version.startsWith("2") ? "1800000000" : "\"2027-01-15T08:00:00Z\"";
            var snapshot = new GbfsParser().parse(gbfs(version, time, TYPES, "true", "true"), "test", NOW);
            assertEquals("Place de: 3-7-11", StationPresentation.row(snapshot, "7046", NOW));
            assertEquals("3-7-11", StationPresentation.value(snapshot, "7046", NOW + 3_599_999));
            assertEquals("_-_-_", StationPresentation.value(snapshot, "7046", NOW + 3_600_000));
            String description = StationPresentation.description(snapshot, "7046", NOW);
            assertTrue(description.contains("3 vélos électriques, 7 vélos classiques, 11 places libres"));
        }
    }

    @Test public void invalidAbsentAndDuplicateBikeTypesStayUnknownAndZeroIsReal() {
        for (String types : new String[] {"null", "[]", "{}",
                "[{\"vehicle_type_id\":\"electrical\",\"count\":-1}]",
                "[{\"vehicle_type_id\":\"electrical\",\"count\":1.5}]",
                "[{\"vehicle_type_id\":\"electrical\",\"count\":3},{\"vehicle_type_id\":\"electrical\",\"count\":4}]"}) {
            var snapshot = new GbfsParser().parse(gbfs("2.3", "1800000000", types, "true", "true"), "test", NOW);
            assertEquals("_-_-11", StationPresentation.value(snapshot, "7046", NOW));
        }
        var zero = new GbfsParser().parse(gbfs("2.3", "1800000000",
                "[{\"vehicle_type_id\":\"electrical\",\"count\":0}]", "true", "true"), "test", NOW);
        assertEquals("0-_-11", StationPresentation.value(zero, "7046", NOW));
    }

    @Test public void rentingAndReturningAreIndependent() {
        for (String renting : new String[] {"false", "null"}) {
            var snapshot = new GbfsParser().parse(gbfs("2.3", "1800000000", TYPES, renting, "true"), "test", NOW);
            assertEquals("_-_-11", StationPresentation.value(snapshot, "7046", NOW));
        }
        var noReturns = new GbfsParser().parse(gbfs("2.3", "1800000000", TYPES, "true", "false"), "test", NOW);
        assertEquals("3-7-_", StationPresentation.value(noReturns, "7046", NOW));
        assertTrue(noReturns.showAnyCount("7046", NOW));
        var future = new GbfsParser().parse(gbfs("2.3", "1800000120", TYPES, "true", "true"), "test", NOW);
        assertEquals("_-_-_", StationPresentation.value(future, "7046", NOW));
    }

    @Test public void geoJsonUsesTotalCountsWithoutDoubleCountingBatterySubtypes() {
        String feed = """
                {"features":[{"properties":{"number":7046,"status":"OPEN",
                "available_bike_stands":11,"last_update":1800000000,
                "total_stands":{"availabilities":{"electricalBikes":3,"mechanicalBikes":7,
                "electricalInternalBatteryBikes":3,"electricalRemovableBatteryBikes":0}},
                "main_stands":{"availabilities":{"electricalBikes":1,"mechanicalBikes":2}}}}]}
                """;
        var parser = new GrandLyonParser();
        assertEquals("3-7-11", StationPresentation.value(parser.parse(feed, "test", NOW), "7046", NOW));
        assertEquals("_-_-11", StationPresentation.value(parser.parse(feed.replace("total_stands", "absent"), "test", NOW), "7046", NOW));
        assertEquals("_-_-_", StationPresentation.value(parser.parse(feed.replace("OPEN", "CLOSED"), "test", NOW), "7046", NOW));
    }

    @Test public void newCountsSurviveMergeAndCacheWhileOldCacheHasUnknownBikes() {
        var old = new Availability.Snapshot(NOW, NOW, "old",
                Map.of("7046", new Availability.Station("7046", 11, true, true, NOW)));
        Gson gson = new Gson();
        String legacy = gson.toJson(old);
        var restored = gson.fromJson(legacy, Availability.Snapshot.class);
        assertEquals("_-_-11", StationPresentation.value(restored, "7046", NOW));
        var fresh = new GbfsParser().parse(gbfs("2.3", "1800000000", TYPES, "true", "true"), "new", NOW);
        var merged = restored.merge(fresh, NOW);
        var cached = gson.fromJson(gson.toJson(merged), Availability.Snapshot.class);
        assertEquals("3-7-11", StationPresentation.value(cached, "7046", NOW));
        assertEquals(NOW, cached.dataTime("7046"));
        assertEquals("old", cached.sourceFor("7046")); // Equally dated docks remain unchanged.
        assertEquals("new", cached.reading("7046", Availability.Metric.ELECTRIC).source());
        var newer = new GbfsParser().parse(gbfs("2.3", "1800000060", "[]", "true", "true"), "newer", NOW + 60_000);
        // Missing fields in a new reading no longer erase still-valid cached bike counts.
        assertEquals("3-7-11", StationPresentation.value(cached.merge(newer, NOW + 60_000), "7046", NOW + 60_000));
    }
}
