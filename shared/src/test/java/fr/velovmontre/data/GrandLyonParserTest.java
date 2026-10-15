package fr.velovmontre.data;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;
import static fr.velovmontre.data.Availability.State.*;

public class GrandLyonParserTest {
    private static final long NOW = Instant.parse("2026-10-13T18:00:00Z").toEpochMilli();
    // Reduced fixture using the documented official WFS properties and timestamp representation.
    private String feed(String status, String count, String observed) {
        return """
                {"type":"FeatureCollection","features":[{"type":"Feature","properties":{
                "number":7046,"name":"7046 - PLACE DE L'ECOLE","status":%s,
                "bike_stands":20,"available_bikes":5,"available_bike_stands":%s,
                "last_update":%s,"last_update_fme":"2026-10-13T18:00:00.000+00:00"}}]}
                """.formatted(status, count, observed);
    }
    private String valid() { return feed("\"OPEN\"", "8", "\"2026-10-13T17:59:00.000+00:00\""); }

    @Test public void readsExplicitSpacesAndPreservesObservationTime() {
        var parsed = new GrandLyonParser().parse(valid(), "test", NOW);
        assertEquals(Integer.valueOf(8), parsed.stations().get("7046").freeDocks());
        assertEquals(NOW - 60_000, parsed.stations().get("7046").reportedAt());
        assertEquals(AVAILABLE, parsed.state("7046", NOW));
        assertEquals(STALE, parsed.state("7046", NOW + 240_000));
        assertEquals(UNKNOWN, parsed.state("7013", NOW));
    }

    @Test public void missingOrClosedDataCannotAdvertiseSpaces() {
        var parser = new GrandLyonParser();
        assertEquals(CLOSED, parser.parse(valid().replace("OPEN", "CLOSED"), "test", NOW).state("7046", NOW));
        assertEquals(UNKNOWN, parser.parse(feed("null", "8", "null"), "test", NOW).state("7046", NOW));
        assertEquals(UNKNOWN, parser.parse(feed("\"OPEN\"", "null", "\"2026-10-13T18:00:00Z\""), "test", NOW).state("7046", NOW));
    }

    @Test public void geoJsonCountsSurviveBothGbfsSourcesFailing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new FeedClient(url -> {
            if (calls.incrementAndGet() == 1) return valid();
            throw new IOException("HTTP 503");
        }, () -> NOW);
        var snapshot = client.fetch();
        assertEquals(3, calls.get());
        assertEquals("Grand Lyon GeoJSON", snapshot.source());
        assertEquals(AVAILABLE, snapshot.state("7046", NOW));
    }

    @Test public void failurePreservesSourceAndReasonForDiagnostics() {
        var client = new FeedClient(url -> { throw new IOException("HTTP 503"); }, () -> NOW);
        IOException error = assertThrows(IOException.class, client::fetch);
        assertEquals(3, error.getSuppressed().length);
        assertTrue(error.getSuppressed()[0].getMessage().contains("Grand Lyon GeoJSON"));
        assertTrue(error.getSuppressed()[2].getMessage().contains("HTTP 503"));
    }

    @Test public void publicationDatesStayIndependentThroughMergeAndCache() {
        var root = JsonParser.parseString(valid()).getAsJsonObject();
        var features = root.getAsJsonArray("features");
        var fresh = features.get(0).deepCopy();
        fresh.getAsJsonObject().getAsJsonObject("properties").addProperty("number", 7012);
        features.get(0).getAsJsonObject().getAsJsonObject("properties")
                .addProperty("last_update_fme", "2026-10-13T16:00:00Z");
        features.add(fresh);
        var parsed = new GrandLyonParser().parse(root.toString(), "Grand Lyon GeoJSON", NOW);
        var merged = Availability.Snapshot.empty().merge(parsed, NOW);
        var restored = new Gson().fromJson(new Gson().toJson(merged), Availability.Snapshot.class);
        for (var data : List.of(parsed, merged, restored)) {
            assertEquals(NOW - 7_200_000, data.feedTime("7046"));
            assertFalse(data.showCount("7046", NOW));
            assertEquals(NOW, data.feedTime("7012"));
            assertEquals(Integer.valueOf(8), data.displayedCount("7012", Availability.Metric.DOCKS, NOW));
            assertEquals(3, data.displayLevel("7012", NOW));
        }
    }

    @Test public void futurePublicationDoesNotInvalidateOtherStations() {
        var root = JsonParser.parseString(valid()).getAsJsonObject();
        var future = root.getAsJsonArray("features").get(0).deepCopy();
        var row = future.getAsJsonObject().getAsJsonObject("properties");
        row.addProperty("number", 7012);
        row.addProperty("last_update_fme", "2026-10-13T18:02:00Z");
        root.getAsJsonArray("features").add(future);
        var parsed = new GrandLyonParser().parse(root.toString(), "Grand Lyon GeoJSON", NOW);
        assertTrue(parsed.showCount("7046", NOW));
        assertFalse(parsed.showCount("7012", NOW));
    }

    @Test public void missingPublicationUsesOnlyItsOwnObservationButInvalidPublicationStaysUnknown() {
        var root = JsonParser.parseString(valid()).getAsJsonObject();
        var row = root.getAsJsonArray("features").get(0).getAsJsonObject().getAsJsonObject("properties");
        row.remove("last_update_fme");
        var parser = new GrandLyonParser();
        var data = parser.parse(root.toString(), "Grand Lyon GeoJSON", NOW);
        assertEquals(NOW - 60_000, data.feedTime("7046"));
        assertTrue(data.showCount("7046", NOW + 3_539_999));
        assertFalse(data.showCount("7046", NOW + 3_540_000));
        for (String invalid : List.of("null", "{}", "\"not a date\"")) {
            row.add("last_update_fme", JsonParser.parseString(invalid));
            assertFalse(parser.parse(root.toString(), "Grand Lyon GeoJSON", NOW).showCount("7046", NOW));
        }
    }

    @Test public void malformedFeaturesDoNotDiscardValidStationsAndAreDiagnosed() throws Exception {
        var root = JsonParser.parseString(valid()).getAsJsonObject();
        for (String invalid : List.of("null", "42", "[]", "{}", "{\"properties\":null}",
                "{\"properties\":[]}", "{\"properties\":{\"number\":null}}", "{\"properties\":{\"number\":{}}}"))
            root.getAsJsonArray("features").add(JsonParser.parseString(invalid));
        var warnings = new ArrayList<String>();
        var parsed = new GrandLyonParser().parse(root.toString(), "Grand Lyon GeoJSON", NOW, warnings::add);
        assertTrue(parsed.showCount("7046", NOW));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("8 entrée(s)"));
        var client = new FeedClient(url -> {
            if (url.equals(FeedClient.REQUEST_ORDER.get(0).url())) return root.toString();
            throw new IOException("offline");
        }, () -> NOW);
        assertTrue(client.fetch().showCount("7046", NOW));
        assertTrue(client.timings().contains("8 entrée(s)"));
    }

    @Test public void duplicatesInvalidateOnlyTheAmbiguousStation() {
        var root = JsonParser.parseString(valid()).getAsJsonObject();
        var features = root.getAsJsonArray("features");
        features.add(features.get(0).deepCopy());
        features.add(features.get(0).deepCopy());
        var other = features.get(0).deepCopy();
        other.getAsJsonObject().getAsJsonObject("properties").addProperty("number", 7012);
        features.add(other);
        var warnings = new ArrayList<String>();
        var parsed = new GrandLyonParser().parse(root.toString(), "Grand Lyon GeoJSON", NOW, warnings::add);
        assertFalse(parsed.showCount("7046", NOW));
        assertTrue(parsed.showCount("7012", NOW));
        assertTrue(warnings.get(0).contains("7046"));
    }
}
