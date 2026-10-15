package fr.velovmontre;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import fr.velovmontre.selection.StationSelection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.List;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = MainActivityTest.OfflineApplication.class)
public class MainActivityTest {
    public static class OfflineApplication extends VelovApplication {
        @Override public void onCreate() { super.onCreate(); repository().pauseForBenchmark(); }
        @Override protected void requestTileUpdates() { }
        @Override protected void readPhoneSelection() { }
    }
    @Test public void syncRebuildsBothPagesWhileVisibleAndHandlesEmptyGroup() {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        var app = (VelovApplication) RuntimeEnvironment.getApplication();
        app.selection().accept(new StationSelection(1, 1, List.of(
                List.of(new StationSelection.Choice("1001", "Terreaux / Terme")), List.of())));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(contains(controller.get().getWindow().getDecorView(), "Terreaux: _-_-_"));
        assertFalse(contains(controller.get().getWindow().getDecorView(), "Place de: _-_-_"));
        assertTrue(contains(controller.get().getWindow().getDecorView(), "Choisissez des stations\nsur le téléphone"));
        controller.pause().stop().destroy();
    }
    private boolean contains(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return true;
        if (view instanceof ViewGroup group) for (int i=0; i<group.getChildCount(); i++)
            if (contains(group.getChildAt(i), text)) return true;
        return false;
    }
}
