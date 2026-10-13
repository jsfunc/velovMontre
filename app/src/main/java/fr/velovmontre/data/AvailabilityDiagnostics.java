package fr.velovmontre.data;

/** On-demand explanations for the compact station rows. */
public final class AvailabilityDiagnostics {
    private AvailabilityDiagnostics() {}

    public static String describe(Availability.Snapshot snapshot, long now) {
        StringBuilder text = new StringBuilder();
        for (String id : Availability.FAVORITES) {
            text.append("\n\n").append(id).append(" : ");
            Availability.Station station = snapshot.stations().get(id);
            if (station == null) {
                text.append("aucun relevé reçu");
                continue;
            }
            Availability.Reading docks = snapshot.reading(id, Availability.Metric.DOCKS);
            text.append(switch (snapshot.state(id, now)) {
                case AVAILABLE, LOW, FULL -> docks.count() + " places";
                case CLOSED -> "retours indisponibles";
                case STALE -> snapshot.showCount(id, now)
                        ? docks.count() + " places (ancien, en rouge)"
                        : "relevé expiré (1 h) ou retours indisponibles";
                case UNKNOWN -> "relevé incomplet ou horloge invalide";
            });
            text.append("\nÉlectriques / classiques / places : ")
                    .append(fr.velovmontre.StationPresentation.value(snapshot, id, now));
            text.append("\nObservation : ").append(age(docks.reportedAt(), now));
            text.append("\nFlux : ").append(age(snapshot.feedTime(id), now));
            text.append("\nTéléchargement : ").append(age(snapshot.fetchTime(id), now));
            text.append("\n").append(snapshot.sourceFor(id));
            for (Availability.Metric metric : Availability.Metric.values()) {
                Availability.Reading reading = snapshot.reading(id, metric);
                if (reading == null) continue;
                text.append("\n").append(switch (metric) {
                    case ELECTRIC -> "Électriques";
                    case NORMAL -> "Classiques";
                    case DOCKS -> "Places";
                }).append(" : ").append(reading.visible(now) ? reading.count() : "_")
                        .append(" · ").append(reading.source())
                        .append(" · observation ").append(age(reading.reportedAt(), now))
                        .append(" · reçu ").append(age(reading.fetchedAt(), now));
            }
        }
        return text.toString();
    }

    private static String age(long timestamp, long now) {
        if (timestamp <= 0) return "date absente";
        if (timestamp > now + 60_000) return "date future";
        return "il y a " + Math.max(0, (now - timestamp) / 1000) + " s";
    }
}
