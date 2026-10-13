package fr.velovmontre.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** GeoJSON first, then staggered fallbacks; publish useful results as each arrives. */
public final class FeedClient {
    public record Source(String name, String url) {}
    // Keep the benchmark's existing source order stable. Production uses REQUEST_ORDER.
    public static final List<Source> SOURCES = List.of(
            new Source("JCDecaux / Cyclocity", "https://api.cyclocity.fr/contracts/lyon/gbfs/v3/station_status.json"),
            new Source("Métropole de Lyon / JCDecaux", "https://download.data.grandlyon.com/files/rdata/jcd_jcdecaux.jcdvelov/station_status.json"),
            new Source("Grand Lyon GeoJSON", "https://data.grandlyon.com/geoserver/metropole-de-lyon/ows"
                    + "?SERVICE=WFS&SRSNAME=EPSG:4326&VERSION=2.0.0&outputFormat=application/json"
                    + "&request=GetFeature&typename=metropole-de-lyon:jcd_jcdecaux.jcdvelov"));
    public static final List<Source> REQUEST_ORDER = List.of(SOURCES.get(2), SOURCES.get(1), SOURCES.get(0));
    public static final int HEAD_START_MS = 3_000;
    public static final int DEADLINE_MS = 5_000;

    public interface Request { String get() throws IOException; void cancel(); }
    public interface Transport {
        String get(String url) throws IOException;
        default Request request(String url) {
            return new Request() {
                public String get() throws IOException { return Transport.this.get(url); }
                public void cancel() { }
            };
        }
    }
    private static final ThreadFactory DAEMON = task -> {
        Thread thread = new Thread(task, "velov-feed"); thread.setDaemon(true); return thread;
    };
    private final Transport transport;
    private final LongSupplier clock;
    private final int headStartMs, deadlineMs;
    private volatile String timings = "";
    public String timings() { return timings; }

    public FeedClient(Transport transport, LongSupplier clock) { this(transport, clock, HEAD_START_MS, DEADLINE_MS); }
    FeedClient(Transport transport, LongSupplier clock, int headStartMs, int deadlineMs) {
        this.transport = transport; this.clock = clock; this.headStartMs = headStartMs; this.deadlineMs = deadlineMs;
    }
    private record Completed(int index, Availability.Snapshot snapshot, IOException error, long finished) {}
    private record Attempt(int index, long started, Request request, Future<Completed> future) {}

    public Availability.Snapshot fetch() throws IOException {
        return fetch(Availability.Snapshot.empty(), snapshot -> { });
    }

    /** The callback runs on this coordinator thread, never on a late/cancelled network worker. */
    public Availability.Snapshot fetch(Availability.Snapshot cached, Consumer<Availability.Snapshot> progress) throws IOException {
        ExecutorService workers = Executors.newFixedThreadPool(3, DAEMON);
        ExecutorService cleanup = Executors.newFixedThreadPool(3, DAEMON);
        CompletionService<Completed> completed = new ExecutorCompletionService<>(workers);
        Map<Integer, Attempt> active = new LinkedHashMap<>();
        List<Attempt> attempts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        IOException failure = new IOException("No usable availability feed");
        Availability.Snapshot combined = cached;
        boolean receivedUsable = false;
        long started = System.nanoTime(), nextStart = started;
        int next = 0;
        timings = "";
        try {
            while (next < REQUEST_ORDER.size() || !active.isEmpty()) {
                long now = System.nanoTime();
                if (next < REQUEST_ORDER.size() && (active.isEmpty() || now >= nextStart)) {
                    final int index = next++;
                    Source source = REQUEST_ORDER.get(index);
                    Request request = transport.request(source.url);
                    long attemptStart = System.nanoTime();
                    Future<Completed> future = completed.submit(() -> {
                        try {
                            String body = request.get();
                            long fetched = clock.getAsLong();
                            Availability.Snapshot snapshot = index == 0
                                    ? new GrandLyonParser().parse(body, source.name, fetched)
                                    : new GbfsParser().parse(body, source.name, fetched);
                            return new Completed(index, snapshot, null, System.nanoTime());
                        } catch (IOException | RuntimeException e) {
                            return new Completed(index, null, new IOException(source.name + ": "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e), System.nanoTime());
                        }
                    });
                    Attempt attempt = new Attempt(index, attemptStart, request, future);
                    active.put(index, attempt); attempts.add(attempt);
                    nextStart = attemptStart + TimeUnit.MILLISECONDS.toNanos(headStartMs);
                }
                now = System.nanoTime();
                long wake = next < REQUEST_ORDER.size() ? nextStart : Long.MAX_VALUE;
                for (Attempt attempt : active.values())
                    wake = Math.min(wake, attempt.started + TimeUnit.MILLISECONDS.toNanos(deadlineMs));
                Future<Completed> ready = completed.poll(Math.max(0, wake - now), TimeUnit.NANOSECONDS);
                if (ready != null) {
                    Completed result;
                    try { result = ready.get(); }
                    catch (CancellationException ignored) { continue; }
                    catch (ExecutionException e) { throw new IOException("Feed worker failed", e.getCause()); }
                    Attempt attempt = active.remove(result.index);
                    if (attempt == null) continue; // A timed-out worker cannot publish a late result.
                    boolean inTime = result.finished - attempt.started <= TimeUnit.MILLISECONDS.toNanos(deadlineMs);
                    if (!inTime || result.error != null) {
                        IOException error = result.error != null ? result.error : timeout(attempt);
                        failure.addSuppressed(error);
                        notes.add(error.getMessage());
                    } else {
                        notes.add(REQUEST_ORDER.get(result.index).name + " : réponse en " + seconds(result.finished - attempt.started));
                        long wallNow = clock.getAsLong();
                        if (hasKnown(result.snapshot, wallNow)) {
                            receivedUsable = true;
                            combined = combined.merge(result.snapshot, wallNow);
                            timings = describe(started, notes);
                            progress.accept(combined);
                            if (combined.allGreen(wallNow)) return combined;
                        } else {
                            failure.addSuppressed(new IOException(REQUEST_ORDER.get(result.index).name + ": aucun relevé utilisable"));
                        }
                    }
                    // Only the latest launched provider controls the next provider's head start.
                    if (result.index == next - 1) nextStart = System.nanoTime();
                }
                now = System.nanoTime();
                for (Attempt attempt : new ArrayList<>(active.values())) {
                    // Judge queued completions by their finish time, not time spent publishing another result.
                    if (attempt.future.isDone()) continue;
                    if (now - attempt.started < TimeUnit.MILLISECONDS.toNanos(deadlineMs)) continue;
                    active.remove(attempt.index);
                    IOException error = timeout(attempt); failure.addSuppressed(error); notes.add(error.getMessage());
                    attempt.future.cancel(true); cleanup.execute(attempt.request::cancel);
                    if (attempt.index == next - 1) nextStart = now;
                }
                timings = describe(started, notes);
            }
            if (receivedUsable) return combined;
            throw failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Refresh interrupted", e);
        } finally {
            for (Attempt attempt : attempts) {
                attempt.future.cancel(true);
                cleanup.execute(attempt.request::cancel);
            }
            workers.shutdownNow(); cleanup.shutdown();
            timings = describe(started, notes);
        }
    }

    private boolean hasKnown(Availability.Snapshot snapshot, long now) {
        for (String id : Availability.FAVORITES) for (Availability.Metric metric : Availability.Metric.values()) {
            Availability.Reading reading = snapshot.reading(id, metric);
            if (reading != null && reading.known(now)) return true;
        }
        return false;
    }
    private IOException timeout(Attempt attempt) {
        return new IOException(REQUEST_ORDER.get(attempt.index).name + " : délai total dépassé (" + deadlineMs + " ms)");
    }
    private static String seconds(long nanos) { return String.format(Locale.FRANCE, "%.1f s", nanos / 1_000_000_000.0); }
    private static String describe(long started, List<String> notes) {
        return "Durée totale : " + seconds(System.nanoTime() - started) + "\n" + String.join("\n", notes);
    }
}
