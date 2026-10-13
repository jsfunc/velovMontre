package fr.velovmontre.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/** Grand Lyon's official WFS GeoJSON; counts are explicit available_bike_stands. */
public final class GrandLyonParser {
    public Availability.Snapshot parse(String json, String source, long fetchedAt) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Availability.Station> stations = new LinkedHashMap<>();
        long updatedAt = 0;
        for (JsonElement element : root.getAsJsonArray("features")) {
            JsonObject row = element.getAsJsonObject().getAsJsonObject("properties");
            if (row == null || !row.has("number")) continue;
            String id = row.get("number").getAsString();
            if (!Availability.FAVORITES.contains(id)) continue;
            if (stations.containsKey(id)) throw new IllegalArgumentException("Duplicate station " + id);
            long reportedAt = GbfsParser.timestamp(row.get("last_update"));
            long publishedAt = GbfsParser.timestamp(row.get("last_update_fme"));
            updatedAt = Math.max(updatedAt, publishedAt > 0 ? publishedAt : reportedAt);
            String status = row.has("status") && row.get("status").isJsonPrimitive()
                    ? row.get("status").getAsString() : "";
            Boolean returning = "OPEN".equals(status) ? Boolean.TRUE
                    : "CLOSED".equals(status) ? Boolean.FALSE : null;
            JsonObject availability = object(object(row, "total_stands"), "availabilities");
            stations.put(id, new Availability.Station(id,
                    GbfsParser.integer(row.get("available_bike_stands")),
                    returning == null ? null : true, returning, reportedAt, 0, 0, null,
                    GbfsParser.integer(availability.get("electricalBikes")),
                    GbfsParser.integer(availability.get("mechanicalBikes")), returning));
        }
        if (stations.isEmpty()) throw new IllegalArgumentException("Favourite stations absent from feed");
        return new Availability.Snapshot(fetchedAt, updatedAt, source, Map.copyOf(stations));
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
}
