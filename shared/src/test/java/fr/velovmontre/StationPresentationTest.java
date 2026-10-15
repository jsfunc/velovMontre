package fr.velovmontre;

import fr.velovmontre.data.Availability;
import org.junit.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.Assert.*;

public class StationPresentationTest {
    private static final long NOW = Instant.parse("2026-10-13T18:00:00Z").toEpochMilli();
    private Availability.Snapshot snapshot(long reported) {
        return new Availability.Snapshot(NOW, NOW, "test",
                Map.of("7046", new Availability.Station("7046", 8, true, true, reported)));
    }

    @Test public void keepsOldCountInRedUntilExactlyOneHour() {
        var data = snapshot(NOW);
        assertEquals("Place de: _-_-8", StationPresentation.row(data, "7046", NOW + 300_000));
        assertEquals(StationPresentation.RED, StationPresentation.color(data, "7046", NOW + 300_000));
        assertEquals("_-_-8", StationPresentation.value(data, "7046", NOW + 3_599_999));
        assertEquals("Place de: _-_-_", StationPresentation.row(data, "7046", NOW + 3_600_000));
    }

    @Test public void coloursAgeRatherThanSpaceCountAtExactBoundaries() {
        var data = snapshot(NOW);
        assertEquals(StationPresentation.GREEN, StationPresentation.color(data, "7046", NOW + 179_999));
        assertEquals(StationPresentation.ORANGE, StationPresentation.color(data, "7046", NOW + 180_000));
        assertEquals(StationPresentation.ORANGE, StationPresentation.color(data, "7046", NOW + 299_999));
        assertEquals(StationPresentation.RED, StationPresentation.color(data, "7046", NOW + 300_000));
        var full = new Availability.Snapshot(NOW, NOW, "test",
                Map.of("7046", new Availability.Station("7046", 0, true, true, NOW)));
        assertEquals(StationPresentation.GREEN, StationPresentation.color(full, "7046", NOW));
        assertEquals("_-_-0", StationPresentation.value(full, "7046", NOW));
    }

    @Test public void accessibilityKeepsFullNameAndObservationAge() {
        assertTrue(StationPresentation.description(snapshot(NOW), "7046", NOW).contains("8 places libres"));
        assertTrue(StationPresentation.description(snapshot(NOW), "7046", NOW + 300_000).contains("5 minutes"));
    }

    @Test public void tileAccessibilityUsesStableAbsoluteTimeAndFullNames() {
        var data = snapshot(NOW);
        String spoken = StationPresentation.tileDescription(data, "7046", NOW + 300_000);
        assertTrue(spoken.contains("Place de l’École"));
        assertTrue(spoken.contains("8 places libres"));
        assertTrue(spoken.contains("13/10/2026"));
        assertFalse(spoken.contains("minutes"));
        assertEquals(spoken, StationPresentation.tileDescription(data, "7046", NOW + 3_599_999));
        assertFalse(StationPresentation.tileDescription(data, "7046", NOW + 3_600_000).contains("8 places libres"));
        assertTrue(StationPresentation.tileDescription(Availability.Snapshot.empty(), "7046", NOW)
                .contains("Place de l’École"));
    }

    @Test public void tileAccessibilityDoesNotInventMissingObservationDate() {
        var data = new Availability.Snapshot(NOW, NOW, "Métropole de Lyon / JCDecaux",
                Map.of("7046", new Availability.Station("7046", 8, true, true, 0)));
        assertTrue(StationPresentation.tileDescription(data, "7046", NOW).contains("âge inconnu"));
    }

    @Test public void closedMissingInvalidAndFutureNeverBecomeCounts() {
        for (var station : new Availability.Station[] {
                new Availability.Station("7046", 8, true, false, NOW),
                new Availability.Station("7046", 8, null, true, NOW),
                new Availability.Station("7046", null, true, true, NOW)}) {
            var data = new Availability.Snapshot(NOW, NOW, "test", Map.of("7046", station));
            assertEquals("_-_-_", StationPresentation.value(data, "7046", NOW));
            assertEquals("_-_-_", StationPresentation.value(data, "7046", NOW + 600_000));
        }
        assertEquals("_-_-_", StationPresentation.value(snapshot(NOW + 120_000), "7046", NOW));
        assertEquals("Halle To: _-_-_", StationPresentation.row(snapshot(NOW), "7013", NOW));
    }
}
