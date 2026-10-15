package fr.velovmontre.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads GBFS 2.3 (epoch seconds) and 3.0 (ISO timestamps). */
public final class GbfsParser {
    private final java.util.Set<String> selected;
    public GbfsParser() { this(Availability.FAVORITES); }
    public GbfsParser(java.util.Collection<String> ids) { selected = java.util.Set.copyOf(ids); }
    public Availability.Snapshot parse(String json, String source, long fetchedAt) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String version = root.get("version").getAsString();
        if (!version.startsWith("2.") && !version.startsWith("3."))
            throw new IllegalArgumentException("Unsupported GBFS version");
        long feedTime = timestamp(root.get("last_updated"));
        if (feedTime <= 0) throw new IllegalArgumentException("Missing feed timestamp");
        Map<String, Availability.Station> stations = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonObject("data").getAsJsonArray("stations")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (!row.has("station_id") || !row.get("station_id").isJsonPrimitive()) continue;
            String id = row.get("station_id").getAsString();
            if (!selected.contains(id)) continue;
            if (stations.containsKey(id)) throw new IllegalArgumentException("Duplicate station " + id);
            stations.put(id, new Availability.Station(id, integer(row.get("num_docks_available")),
                    bool(row.get("is_installed")), bool(row.get("is_returning")),
                    timestamp(row.get("last_reported")), 0, 0, null,
                    bikeCount(row, "electrical"), bikeCount(row, "mechanical"), bool(row.get("is_renting"))));
        }
        if (stations.isEmpty()) throw new IllegalArgumentException("Favourite stations absent from feed");
        return new Availability.Snapshot(fetchedAt, feedTime, source, Map.copyOf(stations));
    }

    // These are the type IDs published by both Lyon GBFS feeds, not array positions.
    private static Integer bikeCount(JsonObject station, String type) {
        JsonElement types = station.get("vehicle_types_available");
        if (types == null || !types.isJsonArray()) return null;
        Integer count = null;
        boolean found = false;
        for (JsonElement entry : types.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject row = entry.getAsJsonObject();
            JsonElement id = row.get("vehicle_type_id");
            if (id == null || !id.isJsonPrimitive() || !type.equals(id.getAsString())) continue;
            if (found) return null; // Conflicting/duplicate entries must not become a guessed count.
            found = true;
            count = integer(row.get("count"));
        }
        return count;
    }

    static long timestamp(JsonElement value) {
        try {
            if (value == null || !value.isJsonPrimitive()) return 0;
            if (value.getAsJsonPrimitive().isNumber())
                return Math.multiplyExact(value.getAsBigDecimal().longValueExact(), 1000);
            return Instant.parse(value.getAsString()).toEpochMilli();
        } catch (RuntimeException e) { return 0; }
    }

    static Integer integer(JsonElement value) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                return null;
            int number = value.getAsBigDecimal().intValueExact();
            return number < 0 ? null : number;
        } catch (RuntimeException e) { return null; }
    }

    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        // GBFS 2 feeds may publish the legacy numeric representation.
        if (value.getAsJsonPrimitive().isNumber()) {
            if ("1".equals(value.getAsString())) return true;
            if ("0".equals(value.getAsString())) return false;
        }
        return null;
    }
}
