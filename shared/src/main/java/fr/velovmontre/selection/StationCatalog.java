package fr.velovmontre.selection;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Station names are downloaded separately from frequently refreshed availability. */
public final class StationCatalog {
    public static final String URL = fr.velovmontre.data.FeedClient.SOURCES.get(2).url() + "&propertyName=number,name,lat,lng";
    public static List<StationSelection.Choice> parse(String json) {
        var rows = new LinkedHashMap<String, StationSelection.Choice>();
        for (var feature : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("features")) {
            try {
                var properties = feature.getAsJsonObject().getAsJsonObject("properties");
                String id = properties.get("number").getAsString();
                if (!id.matches("[1-9][0-9]{0,8}")) continue;
                String name = properties.get("name").getAsString().replaceFirst("^0*" + id + "\\s*[-–]\\s*", "").strip();
                rows.put(id, new StationSelection.Choice(id, name));
            } catch (RuntimeException ignored) { }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Catalogue de stations vide");
        var result = new ArrayList<>(rows.values());
        result.sort(Comparator.comparingInt(choice -> Integer.parseInt(choice.id())));
        return List.copyOf(result);
    }
    public static List<StationSelection.Choice> search(List<StationSelection.Choice> catalog, String number) {
        String query = number.strip();
        if (query.isEmpty() || !query.matches("[0-9]+")) return List.of();
        var result = new ArrayList<StationSelection.Choice>();
        for (var station : catalog) if (station.id().startsWith(query)) result.add(station);
        result.sort(Comparator.<StationSelection.Choice, Boolean>comparing(s -> !s.id().equals(query))
                .thenComparingInt(s -> Integer.parseInt(s.id())));
        return List.copyOf(result);
    }
}
