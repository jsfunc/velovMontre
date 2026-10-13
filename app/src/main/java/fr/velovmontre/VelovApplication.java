package fr.velovmontre;

import android.app.Application;
import fr.velovmontre.data.AvailabilityRepository;

public final class VelovApplication extends Application {
    private AvailabilityRepository repository;

    @Override public void onCreate() {
        super.onCreate();
        repository = new AvailabilityRepository(this);
    }

    public AvailabilityRepository repository() { return repository; }
}
