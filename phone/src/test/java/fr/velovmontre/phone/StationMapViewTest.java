package fr.velovmontre.phone;

import android.net.Uri;
import android.webkit.WebSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = PhoneActivityTest.OfflineApplication.class)
public class StationMapViewTest {
    @Test public void mapLoadsBundledCodeAndOnlyAllowsHttpsMapTiles() throws Exception {
        var view = new StationMapView(RuntimeEnvironment.getApplication());
        assertEquals(StationMapView.PAGE, Shadows.shadowOf(view).getLastLoadedUrl());
        assertFalse(view.getSettings().getAllowFileAccess());
        assertFalse(view.getSettings().getAllowContentAccess());
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.getSettings().getMixedContentMode());
        assertTrue(view.getSettings().getUserAgentString().contains("VelovMontrePhone/"));
        try (var stream = view.resource(Uri.parse(StationMapView.PAGE)).getData()) {
            assertTrue(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("Content-Security-Policy"));
        }
        assertNull(view.resource(Uri.parse("https://tile.openstreetmap.org/14/8410/5845.png")));
        for (String url : new String[]{"file:///etc/passwd", "http://tile.openstreetmap.org/14/1/1.png",
                "https://evil.example/code.js", StationMapView.ORIGIN + "/map/../../private.json",
                "https://tile.openstreetmap.org/script.js", "https://tile.openstreetmap.org.evil.example/1/1/1.png"})
            assertEquals(403, view.resource(Uri.parse(url)).getStatusCode());
        view.destroy();
    }
    @Test public void latestDataIsSentAfterPageIsReadyAndUnchangedUpdatesAreSkipped() {
        var view = new StationMapView(RuntimeEnvironment.getApplication());
        var shadow = Shadows.shadowOf(view);
        view.update("{\"group\":0}");
        view.update("{\"group\":1}");
        view.getWebViewClient().onPageFinished(view, StationMapView.PAGE);
        assertTrue(shadow.getLastEvaluatedJavascript().contains("group\\\":1"));
        view.recenter();
        view.update("{\"group\":1}");
        assertEquals("window.fitStations();", shadow.getLastEvaluatedJavascript());
        view.destroy();
    }
}
