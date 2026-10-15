package fr.velovmontre.selection;

import com.google.gson.Gson;
import fr.velovmontre.StationPresentation;
import fr.velovmontre.data.Availability;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Phone-owned, immutable configuration. Revision orders offline edits without relying on delivery order. */
public record StationSelection(int schema, long revision, List<List<Choice>> groups) {
    public record Choice(String id, String name) {
        public Choice {
            if (id == null || !id.matches("[1-9][0-9]{0,8}") || name == null
                    || name.isBlank() || name.length() > 160 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Station invalide");
            name = name.strip();
        }
    }
    public StationSelection {
        if (schema != 1 || revision < 0 || groups == null || groups.size() != 2)
            throw new IllegalArgumentException("Configuration incompatible");
        var copy = new ArrayList<List<Choice>>();
        var seen = new HashSet<String>();
        for (var group : groups) {
            if (group == null || group.size() > 8) throw new IllegalArgumentException("Huit stations maximum par groupe");
            for (var station : group)
                if (station == null || !seen.add(station.id())) throw new IllegalArgumentException("Station déjà sélectionnée");
            copy.add(List.copyOf(group));
        }
        groups = List.copyOf(copy);
    }
    public static StationSelection defaults() {
        var groups = new ArrayList<List<Choice>>();
        for (var page : Availability.PAGES) {
            var stations = new ArrayList<Choice>();
            for (String id : page) stations.add(new Choice(id, StationPresentation.fullName(id)));
            groups.add(stations);
        }
        return new StationSelection(1, 0, groups);
    }
    public List<String> ids() {
        var ids = new ArrayList<String>();
        for (var group : groups) for (var station : group) ids.add(station.id());
        return List.copyOf(ids);
    }
    public String name(String id) {
        for (var group : groups) for (var station : group) if (station.id().equals(id)) return station.name();
        return StationPresentation.fullName(id);
    }
    public StationSelection add(int group, Choice station) {
        var copy = mutableGroups();
        copy.get(group).add(station);
        return edited(copy);
    }
    public StationSelection remove(String id) {
        var copy = mutableGroups();
        copy.forEach(group -> group.removeIf(s -> s.id().equals(id)));
        return edited(copy);
    }
    /** Insert before the destination index in the original list; size means append. */
    public StationSelection move(String id, int destination, int index) {
        var copy = mutableGroups();
        if (destination < 0 || destination >= 2 || index < 0 || index > copy.get(destination).size())
            throw new IllegalArgumentException("Position invalide");
        Choice moved = null;
        for (int g = 0; g < 2; g++) for (int i = 0; i < copy.get(g).size(); i++) {
            if (copy.get(g).get(i).id().equals(id)) {
                moved = copy.get(g).remove(i);
                if (g == destination && i < index) index--;
                break;
            }
        }
        if (moved == null) throw new IllegalArgumentException("Station absente");
        copy.get(destination).add(index, moved);
        return edited(copy);
    }
    private ArrayList<List<Choice>> mutableGroups() {
        var copy = new ArrayList<List<Choice>>();
        groups.forEach(g -> copy.add(new ArrayList<>(g)));
        return copy;
    }
    private StationSelection edited(List<List<Choice>> groups) {
        return new StationSelection(1, Math.max(Math.addExact(revision, 1), System.currentTimeMillis()), groups);
    }
    public String json() { return new Gson().toJson(this); }
    public static StationSelection parse(String json) {
        if (json == null || json.length() > 16_384) throw new IllegalArgumentException("Configuration trop grande");
        var selection = new Gson().fromJson(json, StationSelection.class);
        if (selection == null) throw new IllegalArgumentException("Configuration absente");
        return selection;
    }
}
