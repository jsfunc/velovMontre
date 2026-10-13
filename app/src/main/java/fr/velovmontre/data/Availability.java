package fr.velovmontre.data;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Pure domain model: fetching a response never makes an old observation fresh. */
public final class Availability {
    private Availability() {}

    public static final List<List<String>> PAGES = List.of(
            List.of("7046", "7012", "7006", "7013", "7049", "7005", "7007", "2020"),
            List.of("5030", "5047", "5008", "5016", "5007", "5001", "5053", "5015"));
    public static final List<String> FAVORITES;
    static {
        var stations = new java.util.ArrayList<String>();
        for (List<String> page : PAGES) stations.addAll(page);
        FAVORITES = List.copyOf(stations);
    }
    public static final long GREEN_AGE_MS = 3 * 60_000L;
    public static final long MAX_AGE_MS = 5 * 60_000L;
    public static final long DISCARD_AGE_MS = 60 * 60_000L;
    private static final long CLOCK_TOLERANCE_MS = 60_000L;

    public enum State { AVAILABLE, LOW, FULL, CLOSED, STALE, UNKNOWN }
    public enum Metric { ELECTRIC, NORMAL, DOCKS }

    /** One count and its provenance, so a fallback can fill a gap without ageing other counts. */
    public record Reading(Integer count, Boolean available, long reportedAt, long fetchedAt,
                          long feedUpdatedAt, String source, boolean gbfs) {
        public long dataTime() { return Math.min(reportedAt, Math.min(fetchedAt, feedUpdatedAt)); }
        public long discardAt() {
            return (gbfs ? Math.min(fetchedAt, feedUpdatedAt) : dataTime()) + DISCARD_AGE_MS;
        }
        public boolean clockValid(long now) {
            return fetchedAt > 0 && feedUpdatedAt > 0 && (gbfs || reportedAt > 0)
                    && fetchedAt <= now + CLOCK_TOLERANCE_MS && feedUpdatedAt <= now + CLOCK_TOLERANCE_MS
                    && reportedAt <= now + CLOCK_TOLERANCE_MS && now < discardAt();
        }
        public boolean known(long now) {
            return clockValid(now) && (Boolean.FALSE.equals(available)
                    || (Boolean.TRUE.equals(available) && Snapshot.validCount(count)));
        }
        public boolean visible(long now) { return known(now) && Boolean.TRUE.equals(available); }
        /** 3 = green, 2 = orange, 1 = red, 0 = unknown/expired. */
        public int level(long now) {
            if (!known(now)) return 0;
            long age = now - dataTime();
            return reportedAt <= 0 ? 1 : age < GREEN_AGE_MS ? 3 : age < MAX_AGE_MS ? 2 : 1;
        }
    }

    public record Station(String id, Integer freeDocks, Boolean installed, Boolean returning,
                          long reportedAt, long fetchedAt, long feedUpdatedAt, String source,
                          Integer electricBikes, Integer normalBikes, Boolean renting) {
        public Station(String id, Integer freeDocks, Boolean installed, Boolean returning,
                       long reportedAt, long fetchedAt, long feedUpdatedAt, String source) {
            this(id, freeDocks, installed, returning, reportedAt, fetchedAt, feedUpdatedAt, source,
                    null, null, null);
        }
        public Station(String id, Integer freeDocks, Boolean installed, Boolean returning, long reportedAt) {
            this(id, freeDocks, installed, returning, reportedAt, 0, 0, null);
        }
    }

    public record Snapshot(long fetchedAt, long feedUpdatedAt, String source,
                           Map<String, Station> stations, Map<String, List<Reading>> readings) {
        public Snapshot(long fetchedAt, long feedUpdatedAt, String source, Map<String, Station> stations) {
            this(fetchedAt, feedUpdatedAt, source, stations, Map.of());
        }
        public static Snapshot empty() { return new Snapshot(0, 0, "", Map.of()); }

        public Reading reading(String id, Metric metric) {
            List<Reading> values = readings == null ? null : readings.get(id);
            if (values != null && values.size() == Metric.values().length && values.get(metric.ordinal()) != null)
                return values.get(metric.ordinal());
            Station station = stations.get(id);
            if (station == null) return null;
            Boolean enabled = metric == Metric.DOCKS ? station.returning : station.renting;
            Boolean available = Boolean.FALSE.equals(station.installed) || Boolean.FALSE.equals(enabled) ? Boolean.FALSE
                    : Boolean.TRUE.equals(station.installed) && Boolean.TRUE.equals(enabled) ? Boolean.TRUE : null;
            Integer count = switch (metric) {
                case ELECTRIC -> station.electricBikes;
                case NORMAL -> station.normalBikes;
                case DOCKS -> station.freeDocks;
            };
            String origin = sourceFor(id);
            return new Reading(count, available, station.reportedAt, fetchTime(id), feedTime(id), origin,
                    "JCDecaux / Cyclocity".equals(origin) || "Métropole de Lyon / JCDecaux".equals(origin));
        }

        public Integer displayedCount(String id, Metric metric, long now) {
            Reading value = reading(id, metric);
            return value != null && value.visible(now) ? value.count : null;
        }

        public boolean allGreen(long now) {
            for (String id : FAVORITES) for (Metric metric : Metric.values()) {
                Reading value = reading(id, metric);
                if (value == null || !value.visible(now) || value.level(now) != 3) return false;
            }
            return true;
        }

        public int displayLevel(String id, long now) {
            int level = 3;
            boolean visible = false;
            for (Metric metric : Metric.values()) {
                Reading value = reading(id, metric);
                if (value != null && value.visible(now)) {
                    visible = true;
                    level = Math.min(level, value.level(now));
                }
            }
            return visible ? level : 0;
        }

        /** Freeze validity when building a Tile: future-invalid fields must not appear later. */
        public Snapshot displayableAt(long now) {
            Map<String, List<Reading>> safe = new LinkedHashMap<>();
            for (String id : stations.keySet()) {
                var values = new java.util.ArrayList<Reading>();
                for (Metric metric : Metric.values()) {
                    Reading value = reading(id, metric);
                    values.add(value.visible(now) ? value : new Reading(null, null, value.reportedAt,
                            value.fetchedAt, value.feedUpdatedAt, value.source, value.gbfs));
                }
                safe.put(id, List.copyOf(values));
            }
            return new Snapshot(fetchedAt, feedUpdatedAt, source, stations, Map.copyOf(safe));
        }

        public State state(String id, long now) {
            Reading value = reading(id, Metric.DOCKS);
            if (value == null || value.reportedAt <= 0 || value.feedUpdatedAt <= 0 || value.fetchedAt <= 0)
                return State.UNKNOWN;
            if (value.reportedAt > now + CLOCK_TOLERANCE_MS
                    || value.feedUpdatedAt > now + CLOCK_TOLERANCE_MS
                    || value.fetchedAt > now + CLOCK_TOLERANCE_MS) return State.UNKNOWN;
            if (now >= expiresAt(id)) return State.STALE;
            if (Boolean.FALSE.equals(value.available)) return State.CLOSED;
            if (value.available == null || !validCount(value.count)) return State.UNKNOWN;
            if (value.count == 0) return State.FULL;
            return value.count <= 2 ? State.LOW : State.AVAILABLE;
        }

        public long expiresAt(String id) {
            Reading value = reading(id, Metric.DOCKS);
            return value == null ? 0 : value.dataTime() + MAX_AGE_MS;
        }

        public long fetchTime(String id) {
            Reading value = storedDocks(id);
            if (value != null) return value.fetchedAt;
            Station station = stations.get(id);
            return station != null && station.source != null ? station.fetchedAt : fetchedAt;
        }

        public long feedTime(String id) {
            Reading value = storedDocks(id);
            if (value != null) return value.feedUpdatedAt;
            Station station = stations.get(id);
            return station != null && station.source != null ? station.feedUpdatedAt : feedUpdatedAt;
        }

        public String sourceFor(String id) {
            Reading value = storedDocks(id);
            if (value != null) return value.source;
            Station station = stations.get(id);
            return station != null && station.source != null ? station.source : source;
        }

        public boolean usable(String id, long now) {
            State state = state(id, now);
            return state != State.UNKNOWN && state != State.STALE;
        }

        public long dataTime(String id) {
            Reading value = reading(id, Metric.DOCKS);
            return value == null ? 0 : value.dataTime();
        }

        private Reading storedDocks(String id) {
            List<Reading> values = readings == null ? null : readings.get(id);
            return values != null && values.size() == Metric.values().length ? values.get(Metric.DOCKS.ordinal()) : null;
        }

        public boolean showCount(String id, long now) {
            return displayedCount(id, Metric.DOCKS, now) != null;
        }

        public boolean showAnyCount(String id, long now) {
            return displayLevel(id, now) > 0;
        }

        public static boolean validCount(Integer count) {
            return count != null && count >= 0;
        }

        private int quality(String id, long now) {
            if (usable(id, now) || (showAnyCount(id, now) && now < expiresAt(id))) return 2;
            Station station = stations.get(id);
            boolean closed = station != null && state(id, now) != State.UNKNOWN
                    && now < dataTime(id) + DISCARD_AGE_MS
                    && (Boolean.FALSE.equals(station.installed) || Boolean.FALSE.equals(station.returning));
            return showAnyCount(id, now) || closed ? 1 : 0;
        }

        private boolean prefer(Snapshot other, String id, long now) {
            int currentQuality = quality(id, now), newQuality = other.quality(id, now);
            if (currentQuality != newQuality) return newQuality > currentQuality;
            Station a = stations.get(id), b = other.stations.get(id);
            if (a.reportedAt != b.reportedAt) return b.reportedAt > a.reportedAt;
            boolean aClosed = Boolean.FALSE.equals(a.installed) || Boolean.FALSE.equals(a.returning);
            boolean bClosed = Boolean.FALSE.equals(b.installed) || Boolean.FALSE.equals(b.returning);
            if (aClosed != bClosed) return bClosed;
            // An equally dated complete reading can enrich a cache from the docks-only version.
            int aFields = bikeFields(a), bFields = bikeFields(b);
            if (aFields != bFields) return bFields > aFields;
            return other.expiresAt(id) > expiresAt(id);
        }

        private static int bikeFields(Station station) {
            return (validCount(station.electricBikes) ? 1 : 0)
                    + (validCount(station.normalBikes) ? 1 : 0) + (station.renting != null ? 1 : 0);
        }

        /** Select independently, preserving each observation's original feed/download clocks. */
        public Snapshot merge(Snapshot other, long now) {
            Map<String, Station> merged = new LinkedHashMap<>();
            Map<String, List<Reading>> mergedReadings = new LinkedHashMap<>();
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            for (String id : FAVORITES) {
                Station a = stations.get(id), b = other.stations.get(id);
                Snapshot chosen = this;
                if (a == null || (b != null && prefer(other, id, now))) chosen = other;
                Station selected = chosen.stations.get(id);
                if (selected == null) continue;
                var values = new java.util.ArrayList<Reading>();
                for (Metric metric : Metric.values()) {
                    Reading value = choose(reading(id, metric), other.reading(id, metric), now);
                    values.add(value);
                    if (value != null && value.known(now)) sources.add(value.source);
                }
                mergedReadings.put(id, List.copyOf(values));
                String origin = chosen.sourceFor(id);
                merged.put(id, new Station(id, selected.freeDocks, selected.installed, selected.returning,
                        selected.reportedAt, chosen.fetchTime(id), chosen.feedTime(id), origin,
                        selected.electricBikes, selected.normalBikes, selected.renting));
            }
            return new Snapshot(Math.max(fetchedAt, other.fetchedAt), Math.max(feedUpdatedAt, other.feedUpdatedAt),
                    String.join(" + ", sources), Map.copyOf(merged), Map.copyOf(mergedReadings));
        }

        private static Reading choose(Reading a, Reading b, long now) {
            if (a == null) return b;
            if (b == null) return a;
            if (a.level(now) != b.level(now)) return b.level(now) > a.level(now) ? b : a;
            if (a.reportedAt != b.reportedAt) return b.reportedAt > a.reportedAt ? b : a;
            if (Boolean.FALSE.equals(a.available) != Boolean.FALSE.equals(b.available))
                return Boolean.FALSE.equals(b.available) ? b : a;
            int aPriority = priority(a.source), bPriority = priority(b.source);
            if (aPriority != bPriority) return bPriority < aPriority ? b : a;
            if (a.feedUpdatedAt != b.feedUpdatedAt) return b.feedUpdatedAt > a.feedUpdatedAt ? b : a;
            return b.fetchedAt > a.fetchedAt ? b : a;
        }

        private static int priority(String source) {
            return "Grand Lyon GeoJSON".equals(source) ? 0
                    : "Métropole de Lyon / JCDecaux".equals(source) ? 1
                    : "JCDecaux / Cyclocity".equals(source) ? 2 : 3;
        }
    }
}
