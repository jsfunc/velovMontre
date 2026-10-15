package fr.velovmontre;

import android.app.Application;
import fr.velovmontre.data.AvailabilityRepository;

public class VelovApplication extends Application {
    private AvailabilityRepository repository;
    private fr.velovmontre.selection.SelectionStore selection;
    private fr.velovmontre.selection.SelectionSync selectionSync;

    @Override public void onCreate() {
        super.onCreate();
        selection = new fr.velovmontre.selection.SelectionStore(this);
        repository = new AvailabilityRepository(this, selection, this::requestTileUpdates);
        selectionSync = new fr.velovmontre.selection.SelectionSync(this, selection);
        selection.addListener(() -> {
            requestTileUpdates();
            repository.selectionChanged();
        });
        readPhoneSelection();
    }

    protected void requestTileUpdates() { VelovTileService.requestUpdates(this); }
    protected void readPhoneSelection() { selectionSync.readOnWatch(); }

    public fr.velovmontre.selection.SelectionStore selection() { return selection; }
    public fr.velovmontre.selection.SelectionSync selectionSync() { return selectionSync; }

    public AvailabilityRepository repository() { return repository; }
}
