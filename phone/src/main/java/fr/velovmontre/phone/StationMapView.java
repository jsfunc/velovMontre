package fr.velovmontre.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.*;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/** Bundled map code, an HTTPS asset origin, and no JavaScript-to-Java interface. */
final class StationMapView extends WebView {
    static final String ORIGIN = "https://appassets.androidplatform.net";
    static final String PAGE = ORIGIN + "/map/index.html";
    private static final Map<String, String> ASSETS = Map.of(
            "/map/index.html", "text/html", "/map/map.js", "application/javascript",
            "/map/map.css", "text/css", "/map/vendor/leaflet.js", "application/javascript",
            "/map/vendor/leaflet.css", "text/css");
    private boolean ready;
    private String pending = "";
    @SuppressLint("SetJavaScriptEnabled") // Only packaged code executes; remote resources are PNG tiles only.
    StationMapView(Context context) {
        super(context);
        setBackgroundColor(0xffe4e8e5);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setGeolocationEnabled(false);
        getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        getSettings().setUserAgentString(getSettings().getUserAgentString() + " VelovMontrePhone/1.2 (fr.velovmontre)");
        // Default WebView HTTP caching honors tile Cache-Control / ETag headers.
        getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return resource(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (request.isForMainFrame() && "https".equals(uri.getScheme())
                        && "www.openstreetmap.org".equals(uri.getHost()) && "/copyright".equals(uri.getPath())) {
                    try { context.startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (android.content.ActivityNotFoundException ignored) { }
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (PAGE.equals(url)) { ready = true; send(); }
            }
        });
        loadUrl(PAGE);
    }
    WebResourceResponse resource(Uri uri) {
        if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                && uri.getPort() == -1 && ASSETS.containsKey(uri.getPath())) {
            try {
                return new WebResourceResponse(ASSETS.get(uri.getPath()), "UTF-8",
                        getContext().getAssets().open(uri.getPath().substring(1)));
            } catch (IOException ignored) { }
        }
        if ("https".equals(uri.getScheme()) && "tile.openstreetmap.org".equals(uri.getHost())
                && uri.getPort() == -1 && uri.getPath() != null && uri.getPath().matches("/[0-9]+/[0-9]+/[0-9]+\\.png")) return null;
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Map.of(), new ByteArrayInputStream(new byte[0]));
    }
    void update(String json) {
        if (pending.equals(json)) return;
        pending = json;
        send();
    }
    private void send() {
        if (ready && !pending.isEmpty()) evaluateJavascript("window.showStations(JSON.parse(" + new Gson().toJson(pending) + "));", null);
    }
    void recenter() { if (ready) evaluateJavascript("window.fitStations();", null); }
}
