package fr.velovmontre.selection;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import java.util.function.Consumer;

/** Only the phone publishes selections. The watch publishes a separate receipt after disk persistence. */
public final class SelectionSync {
    public static final String PATH = "/velov/stations";
    public static final String RECEIPT_PATH = "/velov/stations-applied";
    private final Context context;
    private final SelectionStore store;
    public SelectionSync(Context context, SelectionStore store) {
        this.context = context.getApplicationContext(); this.store = store;
    }
    private boolean publishing;
    private Consumer<String> publishStatus;
    public synchronized void publish(Consumer<String> status) {
        publishStatus = status;
        if (publishing) return;
        publishing = true;
        StationSelection sent = store.current();
        var request = PutDataMapRequest.create(PATH);
        request.getDataMap().putString("selection", sent.json());
        try {
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
                    .addOnCompleteListener(task -> finishPublish(sent, task.isSuccessful()
                            ? "Enregistré · en attente de la montre"
                            : "Enregistré · connexion à la montre à réessayer"));
        } catch (RuntimeException e) { finishPublish(sent, "Enregistré · synchronisation indisponible"); }
    }
    private synchronized void finishPublish(StationSelection sent, String status) {
        publishing = false;
        publishStatus.accept(status);
        // Serialize writes so a slow older put cannot replace the last offline edit in Data Layer.
        if (!sent.equals(store.current())) publish(publishStatus);
    }
    public void readOnWatch() {
        try {
            Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*" + PATH), DataClient.FILTER_LITERAL)
                    .addOnSuccessListener(items -> {
                        try { for (DataItem item : items) receiveOnWatch(item); }
                        finally { items.release(); }
                    }).addOnFailureListener(error -> Log.w("Velov", "Selection sync unavailable", error));
        } catch (RuntimeException e) { Log.w("Velov", "Selection sync unavailable", e); }
    }
    public void receiveOnWatch(DataItem item) {
        if (!PATH.equals(item.getUri().getPath())) return;
        try {
            StationSelection incoming = StationSelection.parse(DataMapItem.fromDataItem(item).getDataMap().getString("selection"));
            store.accept(incoming);
            // Duplicate delivery is safe; acknowledge only the exact persisted selection.
            if (incoming.equals(store.current())) {
                var receipt = PutDataMapRequest.create(RECEIPT_PATH);
                receipt.getDataMap().putString("selection", incoming.json());
                Wearable.getDataClient(context).putDataItem(receipt.asPutDataRequest().setUrgent())
                        .addOnFailureListener(error -> Log.w("Velov", "Selection receipt failed", error));
            }
        } catch (RuntimeException e) { Log.w("Velov", "Invalid or unsaved phone selection", e); }
    }
    public void receiptStatus(Consumer<String> status) {
        try {
            Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*" + RECEIPT_PATH), DataClient.FILTER_LITERAL)
                    .addOnSuccessListener(items -> {
                        try {
                            for (DataItem item : items) {
                                try {
                                    var saved = StationSelection.parse(DataMapItem.fromDataItem(item).getDataMap().getString("selection"));
                                    if (saved.equals(store.current())) { status.accept("Stations synchronisées avec la montre"); return; }
                                } catch (RuntimeException ignored) { }
                            }
                        } finally { items.release(); }
                    }).addOnFailureListener(error -> { });
        } catch (RuntimeException ignored) { }
    }
}
