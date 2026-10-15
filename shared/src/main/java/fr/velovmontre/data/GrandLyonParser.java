package fr.velovmontre.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.function.Consumer;

/** Grand Lyon's official WFS GeoJSON; counts are explicit available_bike_stands. */
public final class GrandLyonParser {
    private final java.util.Set<String> selected;
    public GrandLyonParser() { this(Availability.FAVORITES); }
    public GrandLyonParser(java.util.Collection<String> ids) { selected = java.util.Set.copyOf(ids); }
    public Availability.Snapshot parse(String json, String source, long fetchedAt) {
        return parse(json, source, fetchedAt, warning -> { });
    }

    public Availability.Snapshot parse(String json, String source, long fetchedAt, Consumer<String> warnings) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Availability.Station> stations = new LinkedHashMap<>();
        var duplicates = new HashSet<String>();
        int malformed = 0;
        long updatedAt = 0;
        for (JsonElement element : root.getAsJsonArray("features")) {
            if (!element.isJsonObject()) { malformed++; continue; }
            JsonObject row = object(element.getAsJsonObject(), "properties");
            if (!row.has("number") || !row.get("number").isJsonPrimitive()) {
                malformed++;
                continue;
            }
            String id = row.get("number").getAsString();
            if (!selected.contains(id)) continue;
            // Ambiguous stations stay unknown; unrelated good stations remain usable.
            if (duplicates.contains(id)) continue;
            if (stations.remove(id) != null) { duplicates.add(id); continue; }
            long reportedAt = GbfsParser.timestamp(row.get("last_update"));
            // Older payloads may omit publication time. Explicitly invalid dates stay invalid.
            long publishedAt = row.has("last_update_fme")
                    ? GbfsParser.timestamp(row.get("last_update_fme")) : reportedAt;
            updatedAt = Math.max(updatedAt, publishedAt);
            String status = row.has("status") && row.get("status").isJsonPrimitive()
                    ? row.get("status").getAsString() : "";
            Boolean returning = "OPEN".equals(status) ? Boolean.TRUE
                    : "CLOSED".equals(status) ? Boolean.FALSE : null;
            JsonObject availability = object(object(row, "total_stands"), "availabilities");
            stations.put(id, new Availability.Station(id,
                    GbfsParser.integer(row.get("available_bike_stands")),
                    returning == null ? null : true, returning, reportedAt, fetchedAt, publishedAt, source,
                    GbfsParser.integer(availability.get("electricalBikes")),
                    GbfsParser.integer(availability.get("mechanicalBikes")), returning));
        }
        if (malformed > 0) warnings.accept(source + " : " + malformed + " entrée(s) mal formée(s) ignorée(s)");
        if (!duplicates.isEmpty()) warnings.accept(source + " : stations en doublon ignorées " + duplicates);
        if (stations.isEmpty()) throw new IllegalArgumentException("Favourite stations absent from feed");
        return new Availability.Snapshot(fetchedAt, updatedAt, source, Map.copyOf(stations));
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
}
