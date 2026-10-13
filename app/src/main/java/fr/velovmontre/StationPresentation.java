package fr.velovmontre;

import fr.velovmontre.data.Availability;
import java.util.Map;

/** Shared compact display and full accessibility descriptions. */
public final class StationPresentation {
    public static final int WHITE = 0xFFF4F6F8;
    public static final int MUTED = 0xFFA9B2BC;
    public static final int GREEN = 0xFF64E4C0;
    public static final int ORANGE = 0xFFFFB74D;
    public static final int RED = 0xFFFF929A;
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("7046", "Place de l’École"), Map.entry("7012", "Mérieux / Pavillons"),
            Map.entry("7006", "Gerland"), Map.entry("7013", "Halle Tony Garnier"),
            Map.entry("7049", "Cité scolaire internationale"), Map.entry("7005", "Debourg"),
            Map.entry("7007", "Farge / Ayasse"), Map.entry("2020", "Musée des Confluences"),
            Map.entry("5030", "Charcot / Résidence universitaire"), Map.entry("5047", "Albéric Pont"),
            Map.entry("5008", "Sainte-Irénée"), Map.entry("5016", "Point du Jour / Granges"),
            Map.entry("5007", "Place de Trion"), Map.entry("5001", "Place Varillon (Funiculaire St Just)"),
            Map.entry("5053", "Saint-Just"), Map.entry("5015", "Fulchiron"));

    private StationPresentation() {}

    public static String shortName(String id) {
        String name = NAMES.getOrDefault(id, id);
        return name.substring(0, Math.min(8, name.length())).stripTrailing();
    }

    public static String row(Availability.Snapshot snapshot, String id, long now) {
        return shortName(id) + ": " + value(snapshot, id, now);
    }

    public static String value(Availability.Snapshot snapshot, String id, long now) {
        return count(snapshot.displayedCount(id, Availability.Metric.ELECTRIC, now)) + "-"
                + count(snapshot.displayedCount(id, Availability.Metric.NORMAL, now)) + "-"
                + count(snapshot.displayedCount(id, Availability.Metric.DOCKS, now));
    }

    private static String count(Integer value) {
        return Availability.Snapshot.validCount(value) ? String.valueOf(value) : "_";
    }

    public static int color(Availability.Snapshot snapshot, String id, long now) {
        return switch (snapshot.displayLevel(id, now)) {
            case 3 -> GREEN;
            case 2 -> ORANGE;
            default -> RED;
        };
    }

    public static String description(Availability.Snapshot snapshot, String id, long now) {
        String detail;
        if (snapshot.showAnyCount(id, now)) {
            long oldest = now;
            boolean unknownAge = false;
            for (Availability.Metric metric : Availability.Metric.values()) {
                Availability.Reading reading = snapshot.reading(id, metric);
                if (reading != null && reading.visible(now)) {
                    oldest = Math.min(oldest, reading.dataTime());
                    unknownAge |= reading.reportedAt() <= 0;
                }
            }
            detail = spoken(snapshot.displayedCount(id, Availability.Metric.ELECTRIC, now)) + " vélos électriques, "
                    + spoken(snapshot.displayedCount(id, Availability.Metric.NORMAL, now)) + " vélos classiques, "
                    + spoken(snapshot.displayedCount(id, Availability.Metric.DOCKS, now)) + " places libres, "
                    + (unknownAge ? "âge inconnu" : "relevé le plus ancien de "
                    + Math.max(0, (now - oldest) / 60_000) + " minutes");
        } else {
            detail = snapshot.state(id, now) == Availability.State.CLOSED
                    ? "retours indisponibles" : "disponibilité inconnue ou relevé expiré";
        }
        return NAMES.getOrDefault(id, id) + ", " + detail + ". Toucher pour actualiser.";
    }

    private static String spoken(Integer value) {
        return Availability.Snapshot.validCount(value) ? String.valueOf(value) : "nombre inconnu de";
    }
}
