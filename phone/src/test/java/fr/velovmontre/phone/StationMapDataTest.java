package fr.velovmontre.phone;

import com.google.gson.JsonParser;
import fr.velovmontre.data.Availability;
import fr.velovmontre.selection.StationSelection;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class StationMapDataTest {
    @Test public void coordinatesPreserveLatitudeLongitudeAndSkipBadRecords() {
        var points = StationCoordinates.parse("""
            {"features":[null,{"properties":{"number":7046,"lat":45.7271,"lng":4.8322}},
            {"properties":{"number":7006,"lat":91,"lng":4.8}},
            {"properties":{"number":7007,"lat":0,"lng":0}},
            {"properties":{"number":7012,"lat":"NaN","lng":4.8}},
            {"properties":{"number":"invalid","lat":45,"lng":4}},
            {"properties":{"number":7013,"name":"Names-only cached station"}}]}
            """);
        assertEquals(Map.of("7046", new StationCoordinates.Point(45.7271, 4.8322)), points);
        assertThrows(UnsupportedOperationException.class, () -> points.clear());
    }
    @Test public void oldCatalogueWithoutCoordinatesRemainsUsable() {
        assertTrue(StationCoordinates.parse("""
            {"features":[{"properties":{"number":7046,"name":"Place de l’École"}}]}
            """).isEmpty());
    }
    @Test public void mapUsesOnlyCurrentGroupAndNeverInventsPositions() {
        var selection = StationSelection.defaults();
        var points = Map.of("7046", new StationCoordinates.Point(45.7271, 4.8322),
                "5030", new StationCoordinates.Point(45.75, 4.8));
        var data = JsonParser.parseString(StationMapData.json(selection, 0, points, Availability.Snapshot.empty(), 123)).getAsJsonObject();
        assertEquals(7, data.get("missing").getAsInt());
        assertEquals(8, data.get("total").getAsInt());
        assertEquals(1, data.getAsJsonArray("stations").size());
        var station = data.getAsJsonArray("stations").get(0).getAsJsonObject();
        assertEquals("7046", station.get("id").getAsString());
        assertEquals("_-_-_", station.get("value").getAsString());
        assertEquals(45.7271, station.get("lat").getAsDouble(), .000001);
        assertFalse(station.get("description").getAsString().contains("actualiser"));
    }
    @Test public void countsAndFreshnessExpireOnTheMapWithoutAnotherDownload() {
        long now = 1_000_000;
        var snapshot = new Availability.Snapshot(now, now, "test",
                Map.of("7046", new Availability.Station("7046", 8, true, true, now)));
        var points = Map.of("7046", new StationCoordinates.Point(45.7271, 4.8322));
        String fresh = StationMapData.json(StationSelection.defaults(), 0, points, snapshot, now);
        assertTrue(fresh.contains("_-_-8"));
        assertTrue(fresh.contains("#64e4c0"));
        String stale = StationMapData.json(StationSelection.defaults(), 0, points, snapshot, now + 300_000);
        assertTrue(stale.contains("#ff929a"));
        String expired = StationMapData.json(StationSelection.defaults(), 0, points, snapshot, now + 3_600_000);
        assertTrue(expired.contains("_-_-_"));
        assertFalse(expired.contains("8 places libres"));
    }
    @Test public void namesRemainDataEvenWhenTheyContainHtmlAndQuotes() {
        String name = "Quai </script> \" & <img src=x onerror=alert(1)>";
        var selection = new StationSelection(1, 1, List.of(List.of(new StationSelection.Choice("7046", name)), List.of()));
        String json = StationMapData.json(selection, 0, Map.of("7046", new StationCoordinates.Point(45, 4)), Availability.Snapshot.empty(), 0);
        assertEquals(name, JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("stations")
                .get(0).getAsJsonObject().get("name").getAsString());
    }
}
