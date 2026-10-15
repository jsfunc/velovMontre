package fr.velovmontre;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

/** Data Layer can wake the watch process while its activity is closed. */
public final class SelectionListenerService extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        var app = (VelovApplication) getApplication();
        for (DataEvent event : events) if (event.getType() == DataEvent.TYPE_CHANGED)
            app.selectionSync().receiveOnWatch(event.getDataItem());
    }
}
