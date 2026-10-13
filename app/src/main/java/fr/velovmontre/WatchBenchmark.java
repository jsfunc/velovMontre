package fr.velovmontre;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.AtomicFile;
import com.google.gson.Gson;
import fr.velovmontre.data.AvailabilityRepository;
import fr.velovmontre.data.FeedExperiment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Temporary, user-started foreground experiment. Never changes the app's data or request order. */
public final class WatchBenchmark {
    private static final int ROUNDS = 12;
    private static final long INTERVAL_MS = 20_000;
    public record Round(int number, String networkBefore, String networkAfter, List<FeedExperiment.Sample> samples) {}
    public record Results(String device, int androidApi, long startedAt, long savedAt,
                          int deadlineMs, long intervalMs, int plannedRounds, String outcome, List<Round> rounds) {}
    private final Context context;
    private final AvailabilityRepository repository;
    private volatile boolean running;
    private volatile String label = "Test feeds";
    private volatile String report = "";
    private Thread worker;

    public WatchBenchmark(Context context, AvailabilityRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        try {
            File previous = new File(context.getFilesDir(), "feed-benchmark.txt");
            // Files.readString is unavailable on the supported Wear OS versions.
            if (previous.isFile() && previous.length() <= 64_000) {
                try (FileInputStream stream = new FileInputStream(previous)) {
                    byte[] bytes = stream.readNBytes(64_001);
                    if (bytes.length <= 64_000) report = new String(bytes, StandardCharsets.UTF_8);
                }
            }
            if (!report.isEmpty()) label = "Résultats test";
        } catch (IOException ignored) { }
    }

    public boolean running() { return running; }
    public String label() { return label; }
    public String report() { return report; }

    public synchronized void start() {
        if (running) return;
        running = true;
        label = "Attente… · stop";
        var previousRefresh = repository.pauseForBenchmark();
        worker = new Thread(() -> {
            List<Round> rounds = new ArrayList<>();
            long startedAt = System.currentTimeMillis();
            String outcome = "Terminé";
            try {
                previousRefresh.get(90, TimeUnit.SECONDS);
                FeedExperiment experiment = new FeedExperiment();
                for (int number = 1; number <= ROUNDS; number++) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    label = "Test " + number + "/" + ROUNDS + " · stop";
                    long roundStart = System.nanoTime();
                    String before = network();
                    rounds.add(new Round(number, before, network(), List.of()));
                    List<FeedExperiment.Sample> samples = experiment.round();
                    rounds.set(rounds.size() - 1, new Round(number, before, network(), samples));
                    save(startedAt, "En cours", rounds);
                    if (number < ROUNDS) {
                        long wait = INTERVAL_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStart);
                        if (wait > 0) Thread.sleep(wait);
                    }
                }
            } catch (InterruptedException e) {
                outcome = "Arrêté (résultats partiels)";
            } catch (Exception e) {
                outcome = "Erreur : " + e.getClass().getSimpleName() + " : " + e.getMessage();
            } finally {
                // Clear interruption so a partial report can be written on leaving the app.
                Thread.interrupted();
                try { save(startedAt, outcome, rounds); }
                catch (IOException e) { report += "\nSauvegarde impossible : " + e.getMessage(); }
                repository.resumeAfterBenchmark();
                label = "Résultats test";
                running = false;
            }
        }, "velov-benchmark-controller");
        worker.start();
    }

    public synchronized void cancel() { if (running && worker != null) worker.interrupt(); }

    private void save(long startedAt, String outcome, List<Round> rounds) throws IOException {
        List<FeedExperiment.Sample> samples = new ArrayList<>();
        for (Round round : rounds) samples.addAll(round.samples());
        report = outcome + "\n" + rounds.stream().filter(round -> !round.samples().isEmpty()).count()
                + "/" + ROUNDS + " tours\n" + FeedExperiment.report(samples);
        Results results = new Results(Build.MODEL, Build.VERSION.SDK_INT, startedAt, System.currentTimeMillis(),
                FeedExperiment.BUDGET_MS, INTERVAL_MS, ROUNDS, outcome, List.copyOf(rounds));
        byte[] json = new Gson().toJson(results).getBytes(StandardCharsets.UTF_8);
        File history = new File(context.getFilesDir(), "feed-experiments");
        if (!history.isDirectory() && !history.mkdirs()) throw new IOException("Cannot create experiment directory");
        write(new File(history, startedAt + ".json"), json);
        write(new File(context.getFilesDir(), "feed-benchmark.json"), json);
        write(new File(context.getFilesDir(), "feed-benchmark.txt"), report.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(File target, byte[] data) throws IOException {
        AtomicFile file = new AtomicFile(target);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(data);
            file.finishWrite(stream);
        } catch (IOException e) {
            if (stream != null) file.failWrite(stream);
            throw e;
        }
    }

    private String network() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        if (capabilities == null) return "Aucun réseau actif";
        List<String> transports = new ArrayList<>();
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("Wi-Fi");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("Mobile");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("Bluetooth");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN");
        return transports.isEmpty() ? "Transport inconnu" : String.join(" + ", transports);
    }
}
