package fr.velovmontre;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.graphics.Typeface;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import fr.velovmontre.data.Availability;
import fr.velovmontre.data.AvailabilityRepository;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private AvailabilityRepository repository;
    private ViewPager pager;
    private boolean refreshing;
    private final Map<String, TextView> rows = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1_000);
        }
    };
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh(false);
            handler.postDelayed(this, 60_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repository = ((VelovApplication) getApplication()).repository();
        pager = new ViewPager(this);
        pager.setBackgroundColor(0xFF000000);
        pager.setOffscreenPageLimit(Availability.PAGES.size() - 1);
        pager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return Availability.PAGES.size(); }
            @Override public boolean isViewFromObject(View view, Object object) { return view == object; }
            @Override public Object instantiateItem(ViewGroup container, int position) {
                View page = stationPage(position);
                container.addView(page);
                return page;
            }
            @Override public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
                for (String id : Availability.PAGES.get(position)) rows.remove(id);
            }
        });
        setContentView(pager);
        if (state != null) pager.setCurrentItem(Math.max(0, Math.min(Availability.PAGES.size() - 1,
                state.getInt("stationPage", 0))), false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("stationPage", pager.getCurrentItem());
        super.onSaveInstanceState(state);
    }

    private View stationPage(int page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF000000);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setBackgroundColor(0xFF000000);
        content.setPadding(dp(28), dp(16), dp(28), dp(16));
        content.setOnClickListener(this::manualRefresh);
        content.setOnLongClickListener(view -> {
            new AlertDialog.Builder(this).setMessage(repository.diagnostics())
                    .setPositiveButton("OK", null).show();
            return true;
        });
        for (String id : Availability.PAGES.get(page)) {
            TextView row = new TextView(this);
            row.setGravity(Gravity.CENTER);
            row.setSingleLine(true);
            row.setIncludeFontPadding(false);
            row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.setOnClickListener(this::manualRefresh);
            row.setOnLongClickListener(view -> content.performLongClick());
            row.setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, TypedValue.COMPLEX_UNIT_SP);
            content.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));
            rows.put(id, row);
        }
        scroll.addView(content);
        render();
        return scroll;
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(ticker);
        handler.post(refresher);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void manualRefresh(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        refresh(true);
    }

    private void refresh(boolean force) {
        if (refreshing) return;
        refreshing = true;
        Futures.addCallback(repository.refresh(force), new FutureCallback<>() {
            @Override public void onSuccess(AvailabilityRepository.Result result) { finishRefresh(); }
            @Override public void onFailure(Throwable error) { finishRefresh(); }
        }, getMainExecutor());
    }

    private void finishRefresh() {
        refreshing = false;
        if (isDestroyed()) return;
        render();
        VelovTileService.requestUpdates(this);
    }

    private void render() {
        long now = System.currentTimeMillis();
        Availability.Snapshot snapshot = repository.current().snapshot();
        for (Map.Entry<String, TextView> entry : rows.entrySet()) {
            String id = entry.getKey();
            TextView row = entry.getValue();
            row.setText(StationPresentation.row(snapshot, id, now));
            row.setTextColor(StationPresentation.color(snapshot, id, now));
            row.setContentDescription(StationPresentation.description(snapshot, id, now));
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
