package fr.velovmontre.data;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AvailabilityRepository {
    public record Result(Availability.Snapshot snapshot, boolean failed) {}
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicFile cache;
    private final Gson gson = new Gson();
    private final Context context;
    private volatile FeedClient client = new FeedClient(new HttpTransport(), System::currentTimeMillis);
    private final fr.velovmontre.selection.SelectionStore selection;
    private final Runnable onUpdate;
    private volatile Result current = new Result(Availability.Snapshot.empty(), false);
    private volatile String diagnostic = "Pas encore de tentative.";
    private volatile boolean fetching;
    private SettableFuture<Result> inFlight;
    private long lastAttempt;
    private java.util.Set<String> activeIds = java.util.Set.of();
    private boolean loaded;
    private boolean benchmarkPaused;

    public synchronized ListenableFuture<Result> pauseForBenchmark() {
        benchmarkPaused = true;
        return inFlight != null ? inFlight : com.google.common.util.concurrent.Futures.immediateFuture(current);
    }

    public synchronized void resumeAfterBenchmark() { benchmarkPaused = false; }

    public AvailabilityRepository(Context context, fr.velovmontre.selection.SelectionStore selection, Runnable onUpdate) {
        this.selection = selection;
        this.onUpdate = onUpdate;
        this.context = context.getApplicationContext();
        // v1 GeoJSON readings have lost their per-station publication dates and cannot be repaired.
        cache = new AtomicFile(new File(context.getFilesDir(), "availability-v2.json"));
    }

    public Result current() { return current; }

    public String diagnostics() {
        long now = System.currentTimeMillis();
        return "v1.1.0 · " + java.time.Instant.ofEpochMilli(now) + "\n\n"
                + (fetching ? "Actualisation en cours…\n\n" : "") + diagnostic
                + "\n\n" + client.timings()
                + AvailabilityDiagnostics.describe(current.snapshot(), now, selection.current().ids());
    }

    private static String failureDetails(Throwable error) {
        StringBuilder details = new StringBuilder(error.getClass().getSimpleName())
                .append(": ").append(error.getMessage());
        for (Throwable cause : error.getSuppressed()) details.append("\n\n").append(cause.getMessage());
        return details.toString();
    }

    /** All callers share one request. Manual refresh is rate-limited to ten seconds. */
    public synchronized ListenableFuture<Result> refresh(boolean force) {
        if (benchmarkPaused) return com.google.common.util.concurrent.Futures.immediateFuture(current);
        if (inFlight != null) return inFlight;
        SettableFuture<Result> promise = SettableFuture.create();
        inFlight = promise;
        io.execute(() -> {
            try {
                loadOnce();
                long now = android.os.SystemClock.elapsedRealtime();
                long interval = force ? 10_000 : 60_000;
                if (lastAttempt == 0 || now - lastAttempt >= interval) {
                    lastAttempt = now;
                    var ids = selection.current().ids();
                    activeIds = java.util.Set.copyOf(ids);
                    client = new FeedClient(new HttpTransport(), System::currentTimeMillis, ids);
                    fetching = true;
                    try {
                        client.fetch(current.snapshot(), snapshot -> {
                            if (!java.util.Set.copyOf(ids).equals(java.util.Set.copyOf(selection.current().ids()))) return;
                            current = new Result(snapshot, false);
                            diagnostic = "Données reçues : " + snapshot.source() + "\n"
                                    + java.time.Instant.ofEpochMilli(snapshot.fetchedAt());
                            save(snapshot);
                            onUpdate.run();
                        });
                    } catch (IOException e) {
                        diagnostic = failureDetails(e);
                        current = new Result(current.snapshot(), true);
                        Log.w("Velov", "Availability refresh failed", e);
                    } finally {
                        fetching = false;
                    }
                }
                promise.set(current);
            } catch (RuntimeException | LinkageError e) {
                diagnostic = failureDetails(e);
                current = new Result(current.snapshot(), true);
                promise.set(current);
                Log.e("Velov", "Availability refresh failed", e);
            } finally {
                synchronized (this) { inFlight = null; }
            }
        });
        return promise;
    }

    /** Queue behind a previous fetch; then bypass its cooldown for the new station set. */
    public void selectionChanged() {
        io.execute(() -> {
            if (activeIds.equals(java.util.Set.copyOf(selection.current().ids()))) {
                onUpdate.run(); // Reordering/renaming needs new layouts, not another feed download.
                return;
            }
            lastAttempt = 0;
            loadOnce();
            current = new Result(current.snapshot().retain(selection.current().ids()), false);
            save(current.snapshot());
            onUpdate.run();
            refresh(true);
        });
    }

    private void loadOnce() {
        if (loaded) return;
        loaded = true;
        try (InputStream stream = cache.openRead()) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return;
            Availability.Snapshot saved = gson.fromJson(new String(bytes, StandardCharsets.UTF_8),
                    Availability.Snapshot.class);
            if (saved != null && saved.stations() != null && saved.source() != null
                    && saved.stations().values().stream().noneMatch(java.util.Objects::isNull))
                current = new Result(saved, false);
        } catch (IOException | RuntimeException ignored) {
            // Missing or damaged cache is equivalent to a first launch.
        }
    }

    private void save(Availability.Snapshot snapshot) {
        FileOutputStream stream = null;
        try {
            stream = cache.startWrite();
            stream.write(gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
            cache.finishWrite(stream);
        } catch (IOException e) {
            if (stream != null) cache.failWrite(stream);
            Log.w("Velov", "Could not persist availability", e);
        }
    }

}
