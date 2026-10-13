package fr.velovmontre;

import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ListenableFuture;
import fr.velovmontre.data.AvailabilityRepository;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VelovTileService extends TileService {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();

    protected int stationPage() { return 0; }

    public static void requestUpdates(android.content.Context context) {
        var updater = TileService.getUpdater(context);
        updater.requestUpdate(VelovTileService.class);
        updater.requestUpdate(WestTileService.class);
    }

    private AvailabilityRepository repository() {
        return ((VelovApplication) getApplication()).repository();
    }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(
            androidx.wear.tiles.RequestBuilders.TileRequest request) {
        SettableFuture<TileBuilders.Tile> response = SettableFuture.create();
        String clicked = request.getCurrentState().getLastClickableId();
        boolean force = "refresh".equals(clicked) || (clicked != null && clicked.startsWith("refresh:"));
        if (force) {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                        new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build());
            }
        }
        // Render cached data within 1.5 s, even when a radio or the feed is unavailable.
        var deadline = TIMER.schedule(() -> response.set(tile(repository().current(), request)),
                1500, TimeUnit.MILLISECONDS);
        Futures.addCallback(repository().refresh(force), new FutureCallback<>() {
            @Override public void onSuccess(AvailabilityRepository.Result result) {
                deadline.cancel(false);
                if (!response.set(tile(result, request)))
                    TileService.getUpdater(VelovTileService.this).requestUpdate(VelovTileService.this.getClass());
            }
            @Override public void onFailure(Throwable error) {
                deadline.cancel(false);
                response.set(tile(repository().current(), request));
            }
        }, MoreExecutors.directExecutor());
        return response;
    }

    @Override protected void onTileEnterEvent(EventBuilders.TileEnterEvent event) {
        AvailabilityRepository.Result before = repository().current();
        Futures.addCallback(repository().refresh(false), new FutureCallback<>() {
            @Override public void onSuccess(AvailabilityRepository.Result result) {
                if (!result.equals(before)) TileService.getUpdater(VelovTileService.this)
                        .requestUpdate(VelovTileService.this.getClass());
            }
            @Override public void onFailure(Throwable error) { /* Cached layout remains visible. */ }
        }, MoreExecutors.directExecutor());
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            androidx.wear.tiles.RequestBuilders.ResourcesRequest request) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion("1").build());
    }

    private TileBuilders.Tile tile(AvailabilityRepository.Result result,
            androidx.wear.tiles.RequestBuilders.TileRequest request) {
        return new TileLayout().build(result,
                request.getDeviceConfiguration().getScreenWidthDp(),
                request.getDeviceConfiguration().getScreenHeightDp(), System.currentTimeMillis(), stationPage());
    }
}
