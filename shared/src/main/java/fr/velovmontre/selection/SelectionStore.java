package fr.velovmontre.selection;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArrayList;

/** Persist before acknowledging a change. One instance per application process. */
public final class SelectionStore {
    interface Storage { String read(); boolean write(String json); }
    private final Storage storage;
    private volatile StationSelection current;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    public SelectionStore(Context context) { this(preferences(context)); }
    SelectionStore(Storage storage) {
        this.storage = storage;
        try { current = StationSelection.parse(storage.read()); }
        catch (RuntimeException e) { current = StationSelection.defaults(); }
    }
    private static Storage preferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("station-selection-v1", Context.MODE_PRIVATE);
        return new Storage() {
            public String read() { return prefs.getString("selection", null); }
            public boolean write(String json) { return prefs.edit().putString("selection", json).commit(); }
        };
    }
    public StationSelection current() { return current; }
    public synchronized boolean accept(StationSelection next) {
        if (next.revision() <= current.revision()) return false;
        if (!storage.write(next.json()))
            throw new IllegalStateException("Impossible d’enregistrer les stations");
        current = next;
        for (Runnable listener : listeners) listener.run();
        return true;
    }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
}
