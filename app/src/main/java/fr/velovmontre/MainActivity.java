package fr.velovmontre;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.text.TextUtils;
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
    private boolean paging;
    private fr.velovmontre.selection.StationSelection selection;
    private final Map<String, TextView> rows = new LinkedHashMap<>();
    private final Map<Integer, ScrollView> pages = new LinkedHashMap<>();
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
        selection = ((VelovApplication) getApplication()).selection().current();
        pager = new ViewPager(this);
        pager.setBackgroundColor(0xFF000000);
        pager.setOffscreenPageLimit(selection.groups().size() - 1);
        pager.setAdapter(new PagerAdapter() {
            @Override public int getItemPosition(Object object) { return POSITION_NONE; }
            @Override public int getCount() { return selection.groups().size(); }
            @Override public boolean isViewFromObject(View view, Object object) { return view == object; }
            @Override public Object instantiateItem(ViewGroup container, int position) {
                View page = stationPage(position);
                container.addView(page);
                return page;
            }
            @Override public void destroyItem(ViewGroup container, int position, Object object) {
                container.removeView((View) object);
                pages.remove(position);
                // Rows are replaced when a new configuration rebuilds the pages.
            }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) { render(); }
            @Override public void onPageScrollStateChanged(int state) {
                paging = state != ViewPager.SCROLL_STATE_IDLE;
                render();
            }
        });
        setContentView(pager);
        if (state != null) pager.setCurrentItem(Math.max(0, Math.min(selection.groups().size() - 1,
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
        for (var station : selection.groups().get(page)) {
            String id = station.id();
            TextView row = new TextView(this);
            row.setGravity(Gravity.CENTER);
            row.setSingleLine(true);
            row.setIncludeFontPadding(false);
            row.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            row.setOnClickListener(this::manualRefresh);
            row.setOnLongClickListener(view -> content.performLongClick());
            row.setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, TypedValue.COMPLEX_UNIT_SP);
            content.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));
            rows.put(id, row);
        }
        if (selection.groups().get(page).isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Choisissez des stations\nsur le téléphone");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(StationPresentation.MUTED);
            content.addView(empty);
        }
        scroll.addView(content);
        pages.put(page, scroll);
        renderPage(page, repository.current().snapshot(), System.currentTimeMillis());
        return scroll;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            ScrollView scroll = pages.get(pager.getCurrentItem());
            if (scroll != null) {
                float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                        * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
                scroll.scrollBy(0, Math.round(delta));
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
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
    }

    private void render() {
        var latest = ((VelovApplication) getApplication()).selection().current();
        if (!latest.equals(selection)) {
            selection = latest;
            rows.clear();
            pager.getAdapter().notifyDataSetChanged();
        }
        long now = System.currentTimeMillis();
        Availability.Snapshot snapshot = repository.current().snapshot();
        if (paging) {
            for (int page : pages.keySet()) renderPage(page, snapshot, now);
        } else {
            renderPage(pager.getCurrentItem(), snapshot, now);
        }
    }

    private void renderPage(int page, Availability.Snapshot snapshot, long now) {
        for (var station : selection.groups().get(page)) {
            String id = station.id();
            TextView row = rows.get(id);
            if (row == null) continue;
            String text = StationPresentation.abbreviated(station.name()) + ": " + StationPresentation.value(snapshot, id, now);
            int color = StationPresentation.color(snapshot, id, now);
            String description = StationPresentation.description(snapshot, id, now, false, station.name());
            if (!TextUtils.equals(row.getText(), text)) row.setText(text);
            if (row.getCurrentTextColor() != color) row.setTextColor(color);
            if (!TextUtils.equals(row.getContentDescription(), description)) row.setContentDescription(description);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
