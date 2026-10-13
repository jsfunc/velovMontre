package fr.velovmontre;

import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;
import static androidx.wear.protolayout.DimensionBuilders.sp;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.StateBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.TileBuilders;
import fr.velovmontre.data.Availability;
import fr.velovmontre.data.AvailabilityRepository;
import java.util.ArrayList;
import java.util.TreeSet;

/** Layout and expiry timelines, independently testable without an Android service. */
public final class TileLayout {

    public TileBuilders.Tile build(AvailabilityRepository.Result result, float width, float height, long now) {
        return build(result, width, height, now, 0);
    }

    public TileBuilders.Tile build(AvailabilityRepository.Result result, float width, float height, long now, int page) {
        result = new AvailabilityRepository.Result(result.snapshot().displayableAt(now), result.failed());
        float scale = Math.min(1.2f, Math.max(0.85f, Math.min(width, height) / 192f));
        TreeSet<Long> boundaries = new TreeSet<>();
        boundaries.add(now);
        for (String id : Availability.PAGES.get(page)) {
            for (Availability.Metric metric : Availability.Metric.values()) {
                Availability.Reading reading = result.snapshot().reading(id, metric);
                if (reading == null || !reading.visible(now)) continue;
                for (long boundary : new long[] {reading.dataTime() + Availability.GREEN_AGE_MS,
                        reading.dataTime() + Availability.MAX_AGE_MS, reading.discardAt()}) {
                    if (boundary > now && boundary <= reading.discardAt()) boundaries.add(boundary);
                }
            }
        }
        ArrayList<Long> times = new ArrayList<>(boundaries);
        TimelineBuilders.Timeline.Builder timeline = new TimelineBuilders.Timeline.Builder();
        // Outside the known timeline, discard all counts even if background updates stop.
        timeline.addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                .setLayout(layout(result, now, now, scale, true, page)).build());
        for (int i = 0; i < times.size() - 1; i++) {
            timeline.addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                    .setValidity(new TimelineBuilders.TimeInterval.Builder()
                            .setStartMillis(times.get(i)).setEndMillis(times.get(i + 1)).build())
                    .setLayout(layout(result, now, times.get(i), scale, false, page)).build());
        }
        // With no future expiry, no count is displayable.
        return new TileBuilders.Tile.Builder().setResourcesVersion("1")
                // Consume click state so an automatic update does not repeat touch feedback.
                .setState(new StateBuilders.State.Builder().build())
                .setFreshnessIntervalMillis(60_000).setTileTimeline(timeline.build()).build();
    }

    private LayoutElementBuilders.Layout layout(AvailabilityRepository.Result result, long referenceTime,
                                                 long now, float scale, boolean discard, int page) {
        LayoutElementBuilders.Column.Builder column = new LayoutElementBuilders.Column.Builder();
        for (String id : Availability.PAGES.get(page)) {
            boolean known = !discard && result.snapshot().showAnyCount(id, referenceTime);
            column.addContent(new LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(148 * scale)).setHeight(dp(18 * scale))
                    .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                            .setClickable(new ModifiersBuilders.Clickable.Builder().setId("refresh:" + id)
                                    .setOnClick(new ActionBuilders.LoadAction.Builder().build()).build())
                            .setSemantics(new ModifiersBuilders.Semantics.Builder()
                                    .setContentDescription(known
                                            ? StationPresentation.description(result.snapshot(), id, now)
                                            : StationPresentation.shortName(id) + ", disponibilité inconnue ou expirée. Toucher pour actualiser.")
                                    .build()).build())
                    .addContent(text(known ? StationPresentation.row(result.snapshot(), id, now)
                                    : StationPresentation.shortName(id) + ": _-_-_", 12 * scale,
                            known ? StationPresentation.color(result.snapshot(), id, now)
                                    : StationPresentation.RED)).build());
        }
        return new LayoutElementBuilders.Layout.Builder().setRoot(new LayoutElementBuilders.Box.Builder()
                .setWidth(expand()).setHeight(expand())
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(0xFF000000)).build())
                        .build())
                .addContent(column.build()).build()).build();
    }

    private static LayoutElementBuilders.Text text(String value, float size, int color) {
        return new LayoutElementBuilders.Text.Builder().setText(value).setMaxLines(1)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder().setSize(sp(size))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .setColor(ColorBuilders.argb(color)).build()).build();
    }
}
