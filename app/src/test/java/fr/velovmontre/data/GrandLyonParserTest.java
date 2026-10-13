package fr.velovmontre.data;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
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
}
