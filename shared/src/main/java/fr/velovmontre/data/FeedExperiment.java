package fr.velovmontre.data;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.*;

/** Explicit experiment only: concurrent cold requests, five-second total network budget. */
public final class FeedExperiment {
    public static final int BUDGET_MS = 5_000;
    // Keep the cohort fixed for comparison with the desktop experiment already run.
    public static final List<String> STATIONS = List.of("7046", "7012", "7006", "7013");
    public record Sample(String source, String url, long startedAt, long receivedAt,
                         Long elapsedMs, boolean withinBudget, String error, Availability.Snapshot snapshot) {}
    private final HttpTransport.Connections connections;
    private final int budgetMs;

    public FeedExperiment() { this(url -> (HttpURLConnection) url.openConnection(), BUDGET_MS); }
    FeedExperiment(HttpTransport.Connections connections, int budgetMs) {
        this.connections = connections;
        this.budgetMs = budgetMs;
    }

    public List<Sample> round() throws InterruptedException {
        ThreadFactory daemon = task -> {
            Thread thread = new Thread(task, "velov-experiment");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService workers = Executors.newFixedThreadPool(3, daemon);
        ScheduledExecutorService deadlines = Executors.newScheduledThreadPool(3, daemon);
        List<Attempt> attempts = new ArrayList<>();
        List<Future<Sample>> pending = new ArrayList<>();
        long roundStart = System.nanoTime();
        try {
            for (FeedClient.Source source : FeedClient.SOURCES) {
                Attempt attempt = new Attempt(source, deadlines);
                attempts.add(attempt);
                pending.add(workers.submit(attempt));
            }
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                // The network is cancelled at five seconds; allow a small parsing/cancellation grace.
                long remaining = Math.max(1, budgetMs + 2_000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStart));
                try {
                    samples.add(pending.get(i).get(remaining, TimeUnit.MILLISECONDS));
                } catch (TimeoutException | ExecutionException e) {
                    attempts.get(i).abort();
                    pending.get(i).cancel(true);
                    samples.add(attempts.get(i).failure("Délai total dépassé / " + e.getClass().getSimpleName()));
                }
            }
            return List.copyOf(samples);
        } finally {
            attempts.forEach(Attempt::abort);
            pending.forEach(future -> future.cancel(true));
            workers.shutdownNow();
            deadlines.shutdownNow();
        }
    }

    private final class Attempt implements Callable<Sample> {
        private final FeedClient.Source source;
        private final ScheduledExecutorService deadlines;
        private volatile HttpURLConnection active;
        private volatile boolean aborted;
        private volatile long startedAt;

        Attempt(FeedClient.Source source, ScheduledExecutorService deadlines) {
            this.source = source;
            this.deadlines = deadlines;
        }

        void abort() {
            aborted = true;
            HttpURLConnection connection = active;
            if (connection != null) connection.disconnect();
        }

        Sample failure(String error) {
            return new Sample(source.name(), source.url(), startedAt, System.currentTimeMillis(), null, false, error, null);
        }

        @Override public Sample call() {
            startedAt = System.currentTimeMillis();
            long started = System.nanoTime();
            var deadline = deadlines.schedule(this::abort, budgetMs, TimeUnit.MILLISECONDS);
            Long elapsed = null;
            boolean received = false;
            long receivedAt = startedAt;
            try {
                HttpTransport transport = new HttpTransport(url -> {
                    if (aborted) throw new IOException("Délai total dépassé");
                    HttpURLConnection connection = connections.open(url);
                    active = connection;
                    if (aborted) { connection.disconnect(); throw new IOException("Délai total dépassé"); }
                    connection.setRequestProperty("Connection", "close");
                    return connection;
                }, budgetMs);
                String body = transport.get(source.url());
                elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                receivedAt = System.currentTimeMillis();
                received = !aborted && elapsed <= budgetMs;
                deadline.cancel(false);
                if (!received) return new Sample(source.name(), source.url(), startedAt, receivedAt,
                        elapsed, false, "Réponse hors délai", null);
                Availability.Snapshot snapshot = source.name().equals("Grand Lyon GeoJSON")
                        ? new GrandLyonParser().parse(body, source.name(), receivedAt)
                        : new GbfsParser().parse(body, source.name(), receivedAt);
                return new Sample(source.name(), source.url(), startedAt, receivedAt, elapsed, true, "", snapshot);
            } catch (IOException | RuntimeException e) {
                return new Sample(source.name(), source.url(), startedAt, System.currentTimeMillis(),
                        elapsed != null ? elapsed : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        received, (aborted ? "Délai total dépassé : " : "") + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
            } finally {
                deadline.cancel(false);
                active = null;
            }
        }
    }

    public static String report(List<Sample> samples) {
        StringBuilder text = new StringBuilder("Limite totale : 5 s\nConnexion neuve ; 3 requêtes simultanées par tour.\n");
        for (FeedClient.Source source : FeedClient.SOURCES) {
            List<Sample> rows = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            int parsed = 0, complete = 0;
            for (Sample sample : samples) {
                if (!sample.source.equals(source.name())) continue;
                rows.add(sample);
                if (sample.withinBudget && sample.elapsedMs != null) times.add(sample.elapsedMs);
                if (sample.snapshot == null) continue;
                parsed++;
                if (STATIONS.stream().allMatch(id -> sample.snapshot.usable(id, sample.receivedAt))) complete++;
            }
            text.append("\n").append(source.name()).append("\nReçues ≤5 s : ")
                    .append(times.size()).append('/').append(rows.size())
                    .append("\nJSON utilisables : ").append(parsed)
                    .append("\n4 relevés <5 min : ").append(complete)
                    .append("\nMédiane réseau : ").append(times.isEmpty() ? "—" : seconds(median(times)));
            for (String id : STATIONS) {
                List<Long> ages = new ArrayList<>();
                Map<Long, Set<Integer>> countsAtTime = new HashMap<>();
                int fresh = 0;
                for (Sample sample : rows) {
                    if (sample.snapshot == null) continue;
                    if (sample.snapshot.usable(id, sample.receivedAt)) fresh++;
                    Availability.Station station = sample.snapshot.stations().get(id);
                    if (station == null || sample.snapshot.state(id, sample.receivedAt) == Availability.State.UNKNOWN) continue;
                    boolean fieldsKnown = Boolean.FALSE.equals(station.installed()) || Boolean.FALSE.equals(station.returning())
                            || (Boolean.TRUE.equals(station.installed()) && Boolean.TRUE.equals(station.returning())
                                && station.freeDocks() != null && station.freeDocks() >= 0);
                    if (fieldsKnown) {
                        ages.add(Math.max(0, sample.receivedAt - sample.snapshot.dataTime(id)));
                        if (station.freeDocks() != null) countsAtTime.computeIfAbsent(station.reportedAt(), key -> new HashSet<>())
                                .add(station.freeDocks());
                    }
                }
                text.append("\n").append(id).append(" : frais ").append(fresh).append('/').append(rows.size())
                        .append(", âge déclaré médian ").append(ages.isEmpty() ? "—" : seconds(median(ages)));
                if (countsAtTime.values().stream().anyMatch(counts -> counts.size() > 1))
                    text.append("\n⚠ Nombre changé sans nouvel horodatage.");
            }
            rows.stream().filter(row -> !row.error.isEmpty()).findFirst()
                    .ifPresent(row -> text.append("\nExemple d’erreur : ").append(row.error));
            text.append('\n');
        }
        text.append("\nLes délais dépassés ne figurent pas dans les médianes. Un court test ne prouve pas la fiabilité à long terme.");
        return text.toString();
    }

    private static long median(List<Long> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
    private static String seconds(long ms) { return String.format(Locale.FRANCE, "%.1f s", ms / 1000.0); }
}
