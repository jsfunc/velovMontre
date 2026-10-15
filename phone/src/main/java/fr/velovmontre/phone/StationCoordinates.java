package fr.velovmontre.phone;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinates stay on the phone; the watch's selection payload does not change. */
final class StationCoordinates {
    record Point(double latitude, double longitude) {
        Point {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180
                    || (latitude == 0 && longitude == 0)) throw new IllegalArgumentException("Invalid coordinate");
        }
    }
    static Map<String, Point> parse(String json) {
        var points = new LinkedHashMap<String, Point>();
        for (var feature : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("features")) {
            try {
                var p = feature.getAsJsonObject().getAsJsonObject("properties");
                String id = p.get("number").getAsString();
                if (id.matches("[1-9][0-9]{0,8}"))
                    points.put(id, new Point(p.get("lat").getAsDouble(), p.get("lng").getAsDouble()));
            } catch (RuntimeException ignored) { /* Names-only caches and malformed locations remain usable in the list. */ }
        }
        return Map.copyOf(points);
    }
}
