package fr.velovmontre.phone;

import android.app.Application;
import android.util.AtomicFile;
import fr.velovmontre.data.AvailabilityRepository;
import fr.velovmontre.data.HttpTransport;
import fr.velovmontre.selection.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class PhoneApplication extends Application {
    private SelectionStore selection;
    private SelectionSync sync;
    private AvailabilityRepository repository;
    private volatile List<StationSelection.Choice> catalog = List.of();
    private volatile java.util.Map<String, StationCoordinates.Point> coordinates = java.util.Map.of();
    private volatile String catalogStatus = "Chargement du catalogue…";
    private volatile String syncStatus = "Enregistré sur le téléphone";
    private volatile boolean catalogLoading;
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();
    @Override public void onCreate() {
        super.onCreate();
        selection = new SelectionStore(this);
        sync = new SelectionSync(this, selection);
        repository = new AvailabilityRepository(this, selection, () -> { });
        var initial = new ArrayList<StationSelection.Choice>();
        selection.current().groups().forEach(initial::addAll);
        catalog = List.copyOf(initial);
        loadCatalog();
    }
    public SelectionStore selection() { return selection; }
    public AvailabilityRepository repository() { return repository; }
    public List<StationSelection.Choice> catalog() { return catalog; }
    java.util.Map<String, StationCoordinates.Point> coordinates() { return coordinates; }
    public String catalogStatus() { return catalogStatus; }
    public String syncStatus() { return syncStatus; }
    public void save(StationSelection next) {
        if (selection.accept(next)) {
            repository.selectionChanged();
            publish();
        }
    }
    public void publish() { sync.publish(value -> { syncStatus = value; sync.receiptStatus(s -> syncStatus = s); }); }
    public void checkReceipt() { sync.receiptStatus(s -> syncStatus = s); }
    public synchronized void loadCatalog() {
        if (catalogLoading) return;
        catalogLoading = true;
        catalogStatus = "Chargement du catalogue…";
        io.execute(() -> {
            AtomicFile cache = new AtomicFile(new File(getFilesDir(), "station-catalog.json"));
            try (var stream = cache.openRead()) {
                byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length <= 2 * 1024 * 1024) acceptCatalog(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            try {
                String json = new HttpTransport().get(StationCatalog.URL);
                acceptCatalog(json);
                FileOutputStream stream = null;
                try {
                    stream = cache.startWrite();
                    stream.write(json.getBytes(StandardCharsets.UTF_8));
                    cache.finishWrite(stream);
                } catch (Exception e) { if (stream != null) cache.failWrite(stream); }
                catalogStatus = "";
                getMainExecutor().execute(() -> {
                    var current = selection.current();
                    var groups = new ArrayList<List<StationSelection.Choice>>();
                    for (var group : current.groups()) {
                        var updated = new ArrayList<StationSelection.Choice>();
                        for (var station : group) updated.add(catalog.stream()
                                .filter(candidate -> candidate.id().equals(station.id())).findFirst().orElse(station));
                        groups.add(updated);
                    }
                    if (!groups.equals(current.groups())) save(new StationSelection(1,
                            Math.max(current.revision() + 1, System.currentTimeMillis()), groups));
                });
            } catch (Exception e) {
                catalogStatus = "Catalogue hors ligne · recherche dans les stations mémorisées";
            } finally { catalogLoading = false; }
        });
    }
    private void acceptCatalog(String json) {
        var names = StationCatalog.parse(json);
        var points = StationCoordinates.parse(json);
        catalog = names;
        coordinates = points;
    }
}
