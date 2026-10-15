package fr.velovmontre.phone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.velovmontre.StationPresentation;
import fr.velovmontre.data.Availability;
import fr.velovmontre.selection.StationSelection;
import java.util.Locale;
import java.util.Map;

final class StationMapData {
    static String json(StationSelection selection, int group, Map<String, StationCoordinates.Point> points,
                       Availability.Snapshot snapshot, long now) {
        var data = new JsonObject();
        data.addProperty("group", group);
        var stations = new JsonArray();
        int missing = 0;
        for (var station : selection.groups().get(group)) {
            var point = points.get(station.id());
            if (point == null) { missing++; continue; }
            var row = new JsonObject();
            row.addProperty("id", station.id());
            row.addProperty("name", station.name());
            row.addProperty("lat", point.latitude());
            row.addProperty("lng", point.longitude());
            row.addProperty("value", StationPresentation.value(snapshot, station.id(), now));
            row.addProperty("color", String.format(Locale.ROOT, "#%06x", StationPresentation.color(snapshot, station.id(), now) & 0xffffff));
            row.addProperty("freshness", switch (snapshot.displayLevel(station.id(), now)) {
                case 3 -> "Relevé récent · moins de 3 min";
                case 2 -> "Relevé de 3 à 5 min";
                default -> "Relevé ancien ou disponibilité inconnue";
            });
            row.addProperty("description", StationPresentation.description(snapshot, station.id(), now, false, station.name())
                    .replace(". Toucher pour actualiser.", "."));
            stations.add(row);
        }
        data.add("stations", stations);
        data.addProperty("missing", missing);
        data.addProperty("total", selection.groups().get(group).size());
        return data.toString();
    }
}
