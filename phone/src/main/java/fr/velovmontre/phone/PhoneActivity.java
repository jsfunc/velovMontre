package fr.velovmontre.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.*;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureCallback;
import fr.velovmontre.StationPresentation;
import fr.velovmontre.data.Availability;
import fr.velovmontre.data.AvailabilityRepository;
import fr.velovmontre.selection.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** A quiet map of the selected group, with the list retained for reading and editing. */
public final class PhoneActivity extends Activity {
    private PhoneApplication app;
    private LinearLayout root, list;
    private TextView footer;
    private int group;
    private boolean editing;
    private boolean mapMode = true;
    private StationMapView mapView;
    private final Map<String, TextView> counts = new LinkedHashMap<>();
    private final Map<String, View> stationRows = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int seconds;
    private AlertDialog searchDialog;
    private EditText search;
    private LinearLayout matches;
    private String searchState = "";
    private StationSelection renderedSelection;
    private final Runnable tick = new Runnable() {
        public void run() {
            renderCounts();
            if (searchDialog != null && searchDialog.isShowing()) renderSearch();
            if (++seconds % 10 == 0) app.checkReceipt();
            if (seconds % 60 == 0) refresh(false);
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (PhoneApplication) getApplication();
        mapMode = getPreferences(MODE_PRIVATE).getBoolean("mapMode", true);
        if (state != null) { group = Math.max(0, Math.min(1, state.getInt("group"))); editing = state.getBoolean("editing"); }
        build();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("group", group); state.putBoolean("editing", editing);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();
        app.publish();
        if (mapView != null && mapMode && !editing) mapView.onResume();
        refresh(false);
        handler.post(tick);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        if (mapView != null) mapView.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (searchDialog != null) searchDialog.dismiss();
        if (mapView != null) {
            if (mapView.getParent() instanceof ViewGroup parent) parent.removeView(mapView);
            mapView.stopLoading(); mapView.destroy(); mapView = null;
        }
        super.onDestroy();
    }
    private void build() {
        if (mapView != null) {
            if (mapView.getParent() instanceof ViewGroup parent) parent.removeView(mapView);
            mapView.onPause();
        }
        renderedSelection = app.selection().current();
        counts.clear(); stationRows.clear();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xff000000);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left + dp(16), bars.top + dp(12), bars.right + dp(16), bars.bottom + dp(8));
            return insets;
        });
        LinearLayout toolbar = horizontal();
        TextView title = label("Vélo’v", 24); title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Actualiser", () -> refresh(true)));
        toolbar.addView(button(editing ? "Terminer" : "Modifier", () -> { editing = !editing; build(); }));
        root.addView(toolbar);
        LinearLayout tabs = horizontal();
        for (int g = 0; g < 2; g++) {
            final int target = g;
            Button tab = button("Groupe " + (g + 1), () -> { group = target; build(); });
            tab.setTextColor(g == group ? StationPresentation.GREEN : StationPresentation.MUTED);
            tab.setSelected(g == group);
            tab.setOnDragListener((v, event) -> drop(event, target, app.selection().current().groups().get(target).size()));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        root.addView(tabs);
        if (!editing) {
            LinearLayout views = horizontal();
            for (boolean showMap : new boolean[]{true, false}) {
                Button choice = button(showMap ? "Carte" : "Liste", () -> {
                    mapMode = showMap; getPreferences(MODE_PRIVATE).edit().putBoolean("mapMode", mapMode).apply(); build();
                });
                choice.setSelected(mapMode == showMap);
                choice.setTextColor(mapMode == showMap ? StationPresentation.GREEN : StationPresentation.MUTED);
                views.addView(choice, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            if (mapMode) views.addView(button("Recentrer", () -> mapView.recenter()));
            root.addView(views);
        }
        TextView legend = label(editing ? "Maintenir une station pour la déplacer" : "Électriques · Classiques · Places libres", 13);
        legend.setTextColor(StationPresentation.MUTED); legend.setPadding(0, dp(12), 0, dp(12)); root.addView(legend);
        if (mapMode && !editing) {
            if (mapView == null) mapView = new StationMapView(this);
            root.addView(mapView, new LinearLayout.LayoutParams(-1, 0, 1));
            mapView.onResume();
        } else {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
            list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
            var selected = app.selection().current().groups().get(group);
            for (int i = 0; i < selected.size(); i++) addRow(selected.get(i), i);
            if (selected.isEmpty()) {
                TextView empty = label("Aucune station dans ce groupe", 16); empty.setPadding(0, dp(24), 0, dp(24)); list.addView(empty);
            }
            if (editing) {
                Button add = button(selected.size() == 8 ? "Groupe complet · 8 stations" : "Ajouter une station", this::searchStations);
                add.setEnabled(selected.size() < 8);
                list.addView(add, new LinearLayout.LayoutParams(-1, dp(56)));
                TextView end = label("Déposer ici pour placer en fin de groupe", 13);
                end.setGravity(Gravity.CENTER); end.setMinHeight(dp(64)); end.setTextColor(StationPresentation.MUTED);
                end.setOnDragListener((v, event) -> drop(event, group, app.selection().current().groups().get(group).size()));
                list.addView(end);
            }
            scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        footer = label("", 12); footer.setTextColor(StationPresentation.MUTED); footer.setPadding(0, dp(8), 0, dp(8)); root.addView(footer);
        setContentView(root); root.requestApplyInsets(); renderCounts();
    }
    private void addRow(StationSelection.Choice station, int index) {
        LinearLayout row = horizontal(); row.setMinimumHeight(dp(76)); row.setPadding(0, dp(12), 0, dp(12));
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(station.name(), 17); name.setTypeface(null, Typeface.BOLD); names.addView(name);
        TextView number = label(station.id(), 12); number.setTextColor(StationPresentation.MUTED); names.addView(number);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = label(editing ? "☰" : "_-_-_", editing ? 24 : 20);
        value.setGravity(Gravity.END); value.setPadding(dp(12), 0, 0, 0);
        value.setTypeface(null, Typeface.BOLD); row.addView(value);
        if (!editing) counts.put(station.id(), value);
        stationRows.put(station.id(), row);
        row.setOnClickListener(v -> { if (editing) stationActions(station, index); else refresh(true); });
        row.setOnLongClickListener(v -> {
            if (editing) return v.startDragAndDrop(ClipData.newPlainText("station", station.id()), new View.DragShadowBuilder(v), station.id(), 0);
            new AlertDialog.Builder(this).setMessage(app.repository().diagnostics()).setPositiveButton("OK", null).show();
            return true;
        });
        row.setOnDragListener((v, event) -> drop(event, group, index));
        list.addView(row);
        View separator = new View(this); separator.setBackgroundColor(0xff202020); list.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private boolean drop(DragEvent event, int destination, int index) {
        if (!editing || !(event.getLocalState() instanceof String id)) return false;
        if (event.getAction() == DragEvent.ACTION_DROP) save(() -> app.selection().current().move(id, destination, index));
        return true;
    }
    private void stationActions(StationSelection.Choice station, int index) {
        new AlertDialog.Builder(this).setTitle(station.name()).setItems(
                new String[]{"Monter", "Descendre", "Déplacer vers le groupe " + (2 - group), "Retirer"}, (dialog, which) -> {
                    var selection = app.selection().current();
                    if (which == 0 && index > 0) save(() -> selection.move(station.id(), group, index - 1));
                    if (which == 1 && index + 1 < selection.groups().get(group).size()) save(() -> selection.move(station.id(), group, index + 2));
                    if (which == 2) save(() -> selection.move(station.id(), 1 - group, selection.groups().get(1 - group).size()));
                    if (which == 3) new AlertDialog.Builder(this).setMessage("Retirer " + station.name() + " ?")
                            .setNegativeButton("Annuler", null).setPositiveButton("Retirer", (d, w) -> save(() -> app.selection().current().remove(station.id()))).show();
                }).setNegativeButton("Annuler", null).show();
    }
    private void save(java.util.function.Supplier<StationSelection> edit) {
        try { app.save(edit.get()); build(); }
        catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void searchStations() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), 0, dp(20), 0);
        search = new EditText(this); search.setHint("Numéro de station"); search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); box.addView(search);
        ScrollView scroll = new ScrollView(this); matches = new LinearLayout(this); matches.setOrientation(LinearLayout.VERTICAL); scroll.addView(matches);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, dp(320)));
        searchDialog = new AlertDialog.Builder(this).setTitle("Ajouter au groupe " + (group + 1)).setView(box)
                .setNegativeButton("Annuler", null).setNeutralButton("Recharger le catalogue", null).create();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchState = ""; renderSearch(); }
            public void afterTextChanged(Editable s) { }
        });
        searchState = ""; searchDialog.show();
        searchDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { app.loadCatalog(); searchState = ""; renderSearch(); });
        renderSearch(); search.requestFocus();
        searchDialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    private void renderSearch() {
        String number = search.getText().toString();
        String state = number + ":" + app.catalog().hashCode() + ":" + app.catalogStatus();
        if (state.equals(searchState)) return;
        searchState = state; matches.removeAllViews();
        if (!app.catalogStatus().isEmpty()) matches.addView(label(app.catalogStatus(), 13));
        var results = StationCatalog.search(app.catalog(), number);
        if (results.isEmpty()) matches.addView(label(number.isBlank() ? "Saisissez un numéro" : "Aucune station trouvée", 15));
        for (var station : results) {
            boolean present = app.selection().current().ids().contains(station.id());
            Button result = button(station.id() + " · " + station.name() + (present ? " · déjà choisie" : ""), () -> {
                try {
                    app.save(app.selection().current().add(group, station));
                    searchDialog.dismiss(); build();
                } catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            });
            result.setEnabled(!present); result.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            matches.addView(result, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private void renderCounts() {
        if (app == null || footer == null) return;
        if (!app.selection().current().equals(renderedSelection)) { build(); return; }
        long now = System.currentTimeMillis();
        Availability.Snapshot snapshot = app.repository().current().snapshot();
        if (mapMode && !editing && mapView != null) mapView.update(StationMapData.json(
                app.selection().current(), group, app.coordinates(), snapshot, now));
        for (var station : app.selection().current().groups().get(group)) {
            TextView value = counts.get(station.id());
            if (value != null) {
                String text = StationPresentation.value(snapshot, station.id(), now);
                if (!text.contentEquals(value.getText())) value.setText(text);
                int color = StationPresentation.color(snapshot, station.id(), now);
                if (value.getCurrentTextColor() != color) value.setTextColor(color);
            }
            View row = stationRows.get(station.id());
            if (row != null) {
                String description = editing ? station.name() + ", station " + station.id() + ". Toucher pour modifier ou maintenir pour déplacer."
                        : StationPresentation.description(snapshot, station.id(), now, false, station.name());
                if (!android.text.TextUtils.equals(description, row.getContentDescription())) row.setContentDescription(description);
            }
        }
        String status = editing ? app.syncStatus() : app.repository().current().failed()
                ? "Hors ligne · dernières données disponibles" : mapMode
                ? "Toucher les chiffres pour afficher la station" : "Toucher une station pour actualiser";
        if (mapMode && !editing && !app.catalogStatus().isEmpty()) status += "\n" + app.catalogStatus();
        if (!status.contentEquals(footer.getText())) footer.setText(status);
    }
    private void refresh(boolean force) {
        if (force && mapMode && !editing) app.loadCatalog();
        Futures.addCallback(app.repository().refresh(force), new FutureCallback<>() {
            public void onSuccess(AvailabilityRepository.Result result) { if (!isDestroyed()) renderCounts(); }
            public void onFailure(Throwable error) { if (!isDestroyed()) renderCounts(); }
        }, getMainExecutor());
    }
    private LinearLayout horizontal() { var layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(StationPresentation.WHITE); return view; }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(13); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setTextColor(StationPresentation.WHITE);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
