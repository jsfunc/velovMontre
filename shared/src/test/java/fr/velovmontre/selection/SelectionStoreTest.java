package fr.velovmontre.selection;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionStoreTest {
    private static class Disk implements SelectionStore.Storage {
        String json; boolean fail;
        public String read() { return json; }
        public boolean write(String value) { if (fail) return false; json = value; return true; }
    }
    @Test public void offlineChangesPersistAcrossRestartAndLateDeliveryCannotRevertThem() {
        var disk = new Disk(); var phone = new SelectionStore(disk);
        var first = phone.current().remove("7046"); phone.accept(first);
        var second = phone.current().remove("7012"); phone.accept(second);
        var restarted = new SelectionStore(disk);
        assertEquals(second, restarted.current());
        var watchDisk = new Disk(); var watch = new SelectionStore(watchDisk);
        AtomicInteger updates = new AtomicInteger(); watch.addListener(updates::incrementAndGet);
        assertTrue(watch.accept(StationSelection.parse(second.json())));
        assertFalse(watch.accept(first));
        assertFalse(watch.accept(second));
        assertEquals(1, updates.get());
        assertEquals(second, new SelectionStore(watchDisk).current());
    }
    @Test public void failedWriteMustNotChangeMemoryOrNotifyWatchReceipt() {
        var disk = new Disk(); var store = new SelectionStore(disk);
        var original = store.current(); disk.fail = true;
        AtomicInteger updates = new AtomicInteger(); store.addListener(updates::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> store.accept(original.remove("7046")));
        assertEquals(original, store.current()); assertEquals(0, updates.get());
    }
    @Test public void emptyConfigurationPersistsAndCorruptFileFallsBackToDefaults() {
        var disk = new Disk(); var store = new SelectionStore(disk);
        var empty = new StationSelection(1, 4, List.of(List.of(), List.of()));
        store.accept(empty); assertEquals(empty, new SelectionStore(disk).current());
        disk.json = "broken"; assertEquals(StationSelection.defaults(), new SelectionStore(disk).current());
    }
}
