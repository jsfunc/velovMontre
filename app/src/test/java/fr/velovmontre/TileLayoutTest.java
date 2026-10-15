package fr.velovmontre;

import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import fr.velovmontre.data.Availability;
import fr.velovmontre.data.AvailabilityRepository;
import org.junit.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class TileLayoutTest {
    private static final long NOW = Instant.parse("2026-10-13T18:00:00Z").toEpochMilli();

    @Test public void synchronizedGroupsReplaceDefaultsAndEmptyGroupHasAnExplanation() {
        var selection = new fr.velovmontre.selection.StationSelection(1, 1, List.of(
                List.of(new fr.velovmontre.selection.StationSelection.Choice("1001", "Terreaux / Terme")), List.of()));
        var snapshot = new Availability.Snapshot(NOW, NOW, "test", Map.of(
                "1001", new Availability.Station("1001", 5, true, true, NOW, 0, 0, null, 2, 3, true)));
        var result = new AvailabilityRepository.Result(snapshot, false);
        var entries = new TileLayout().build(result, 192, 192, NOW, 0, selection).getTileTimeline().getTimelineEntries();
        assertEquals("Terreaux: 2-3-5", rowText(entries.get(1), 0).getText().getValue());
        var root = (LayoutElementBuilders.Box) entries.get(1).getLayout().getRoot();
        assertEquals(1, ((LayoutElementBuilders.Column) root.getContents().get(0)).getContents().size());
        var empty = new TileLayout().build(result, 192, 192, NOW, 1, selection).getTileTimeline().getTimelineEntries();
        assertEquals(1, empty.size());
        var emptyRoot = (LayoutElementBuilders.Box) empty.get(0).getLayout().getRoot();
        var column = (LayoutElementBuilders.Column) emptyRoot.getContents().get(0);
        assertEquals("Choisir sur le téléphone", ((LayoutElementBuilders.Text) column.getContents().get(0)).getText().getValue());
    }

    @Test public void rowsChangeColourAndExpireIndependentlyWithoutNetwork() {
        var snapshot = new Availability.Snapshot(NOW, NOW, "test", Map.of(
                "7046", new Availability.Station("7046", 8, true, true, NOW),
                "7012", new Availability.Station("7012", 5, true, true, NOW - 120_000)));
        var entries = new TileLayout().build(new AvailabilityRepository.Result(snapshot, false),
                192, 192, NOW).getTileTimeline().getTimelineEntries();
        assertEquals(6, entries.size());
        assertNull(entries.get(0).getValidity());
        assertEquals("Place de: _-_-_", rowText(entries.get(0), 0).getText().getValue());
        assertEquals(StationPresentation.RED, colour(entries.get(0), 0));
        var starts = entries.subList(1, entries.size()).stream().map(e -> e.getValidity().getStartMillis()).toList();
        assertEquals(List.of(NOW, NOW + 60_000, NOW + 180_000, NOW + 300_000, NOW + 3_480_000), starts);
        assertEquals(NOW + 3_600_000, entries.get(5).getValidity().getEndMillis());
        assertEquals(StationPresentation.GREEN, colour(entries.get(1), 0));
        assertEquals(StationPresentation.GREEN, colour(entries.get(1), 1));
        assertEquals(StationPresentation.GREEN, colour(entries.get(2), 0));
        assertEquals(StationPresentation.ORANGE, colour(entries.get(2), 1));
        assertEquals(StationPresentation.ORANGE, colour(entries.get(3), 0));
        assertEquals(StationPresentation.RED, colour(entries.get(3), 1));
        assertEquals("Mérieux: _-_-5", rowText(entries.get(4), 1).getText().getValue());
        assertEquals("Mérieux: _-_-_", rowText(entries.get(5), 1).getText().getValue());
        assertEquals("Place de: _-_-8", rowText(entries.get(5), 0).getText().getValue());
    }

    @Test public void initialTileHasExactlyEightIndividuallyRefreshableRows() {
        var entries = new TileLayout().build(new AvailabilityRepository.Result(Availability.Snapshot.empty(), false),
                192, 192, NOW).getTileTimeline().getTimelineEntries();
        assertEquals(1, entries.size());
        var labels = new ArrayList<String>();
        var root = (LayoutElementBuilders.Box) entries.get(0).getLayout().getRoot();
        var column = (LayoutElementBuilders.Column) root.getContents().get(0);
        assertEquals(8, column.getContents().size());
        for (int i = 0; i < 8; i++) {
            var row = (LayoutElementBuilders.Box) column.getContents().get(i);
            assertEquals("refresh:" + Availability.FAVORITES.get(i), row.getModifiers().getClickable().getId());
            assertTrue(row.getModifiers().getClickable().getOnClick() instanceof androidx.wear.protolayout.ActionBuilders.LoadAction);
            labels.add(rowText(entries.get(0), i).getText().getValue());
        }
        assertEquals(List.of("Place de: _-_-_", "Mérieux: _-_-_", "Gerland: _-_-_", "Halle To: _-_-_",
                "Cité sco: _-_-_", "Debourg: _-_-_", "Farge /: _-_-_", "Musée de: _-_-_"), labels);
    }

    @Test public void secondTileUsesOnlyTheSecondPageAndItsExpiryTimes() {
        var snapshot = new Availability.Snapshot(NOW, NOW, "test", Map.of(
                "7046", new Availability.Station("7046", 5, true, true, NOW - 100_000),
                "5030", new Availability.Station("5030", 11, true, true, NOW,
                        0, 0, null, 3, 7, true)));
        var entries = new TileLayout().build(new AvailabilityRepository.Result(snapshot, false),
                192, 192, NOW, 1).getTileTimeline().getTimelineEntries();
        assertEquals(4, entries.size()); // The other page's earlier boundaries are excluded.
        assertEquals("Charcot: 3-7-11", rowText(entries.get(1), 0).getText().getValue());
        assertEquals(NOW + 3_600_000, entries.get(3).getValidity().getEndMillis());
        var root = (LayoutElementBuilders.Box) entries.get(0).getLayout().getRoot();
        var column = (LayoutElementBuilders.Column) root.getContents().get(0);
        assertEquals(8, column.getContents().size());
        var names = List.of("Charcot", "Albéric", "Sainte-I", "Point du", "Place de", "Place Va", "Saint-Ju", "Fulchiro");
        for (int i = 0; i < 8; i++) {
            assertEquals(names.get(i) + ": _-_-_", rowText(entries.get(0), i).getText().getValue());
            var row = (LayoutElementBuilders.Box) column.getContents().get(i);
            assertEquals("refresh:" + Availability.PAGES.get(1).get(i), row.getModifiers().getClickable().getId());
        }
    }

    @Test public void invalidFutureReadingNeverAppearsInLaterTimelineEntry() {
        var snapshot = new Availability.Snapshot(NOW, NOW, "test", Map.of(
                "7046", new Availability.Station("7046", 8, true, true, NOW),
                "7012", new Availability.Station("7012", 5, true, true, NOW + 120_000)));
        var entries = new TileLayout().build(new AvailabilityRepository.Result(snapshot, false),
                192, 192, NOW).getTileTimeline().getTimelineEntries();
        for (var entry : entries) assertEquals("Mérieux: _-_-_", rowText(entry, 1).getText().getValue());
    }

    @Test public void offlineTimelineKeepsAbsoluteDescriptionsAndLargeRefreshTarget() {
        var snapshot = new Availability.Snapshot(NOW, NOW, "test", Map.of(
                "7046", new Availability.Station("7046", 8, true, true, NOW)));
        var entries = new TileLayout().build(new AvailabilityRepository.Result(snapshot, false),
                192, 192, NOW).getTileTimeline().getTimelineEntries();
        String initial = null;
        for (var entry : entries) {
            var root = (LayoutElementBuilders.Box) entry.getLayout().getRoot();
            assertEquals("refresh", root.getModifiers().getClickable().getId());
            assertTrue(root.getModifiers().getClickable().getOnClick()
                    instanceof androidx.wear.protolayout.ActionBuilders.LoadAction);
            assertTrue(root.getWidth() instanceof androidx.wear.protolayout.DimensionBuilders.ExpandedDimensionProp);
            assertTrue(root.getHeight() instanceof androidx.wear.protolayout.DimensionBuilders.ExpandedDimensionProp);
            var column = (LayoutElementBuilders.Column) root.getContents().get(0);
            var row = (LayoutElementBuilders.Box) column.getContents().get(0);
            String description = row.getModifiers().getSemantics().getContentDescription().getValue();
            assertTrue(description.contains("Place de l’École"));
            assertFalse(description.contains("minutes"));
            if (entry.getValidity() != null) {
                assertTrue(description.contains("13/10/2026"));
                if (initial == null) initial = description;
                assertEquals(initial, description);
            } else {
                assertFalse(description.contains("8 places libres"));
            }
        }
    }

    @Test public void bikesRemainVisibleAndExpireEvenWhenDocksAreUnknown() {
        var snapshot = new Availability.Snapshot(NOW, NOW, "test", Map.of(
                "7046", new Availability.Station("7046", null, true, true, NOW,
                        0, 0, null, 3, 7, true)));
        var entries = new TileLayout().build(new AvailabilityRepository.Result(snapshot, false),
                192, 192, NOW).getTileTimeline().getTimelineEntries();
        assertEquals(4, entries.size());
        assertEquals("Place de: _-_-_", rowText(entries.get(0), 0).getText().getValue());
        assertEquals("Place de: 3-7-_", rowText(entries.get(1), 0).getText().getValue());
        assertEquals(StationPresentation.GREEN, colour(entries.get(1), 0));
        assertEquals(StationPresentation.ORANGE, colour(entries.get(2), 0));
        assertEquals(StationPresentation.RED, colour(entries.get(3), 0));
        assertEquals(NOW + 3_600_000, entries.get(3).getValidity().getEndMillis());
    }

    @Test public void mixedSourceCountsExpireSeparatelyAndFutureFieldNeverAppears() {
        var docks = new Availability.Reading(11, true, NOW, NOW, NOW, "Grand Lyon GeoJSON", false);
        var oldElectric = new Availability.Reading(3, true, NOW - 10_000_000,
                NOW - 120_000, NOW - 120_000, "Métropole de Lyon / JCDecaux", true);
        var futureNormal = new Availability.Reading(7, true, NOW + 120_000, NOW, NOW, "test", false);
        var snapshot = new Availability.Snapshot(NOW, NOW, "mixed",
                Map.of("7046", new Availability.Station("7046", 11, true, true, NOW)),
                Map.of("7046", List.of(oldElectric, futureNormal, docks)));
        var entries = new TileLayout().build(new AvailabilityRepository.Result(snapshot, false),
                192, 192, NOW).getTileTimeline().getTimelineEntries();
        assertEquals("Place de: 3-_-11", rowText(entries.get(1), 0).getText().getValue());
        assertEquals(StationPresentation.RED, colour(entries.get(1), 0));
        for (var entry : entries) assertFalse(rowText(entry, 0).getText().getValue().contains("-7-"));
        var last = entries.get(entries.size() - 1);
        assertEquals(NOW + 3_480_000, last.getValidity().getStartMillis());
        assertEquals("Place de: _-_-11", rowText(last, 0).getText().getValue());
        assertEquals(NOW + 3_600_000, last.getValidity().getEndMillis());
    }

    private LayoutElementBuilders.Text rowText(TimelineBuilders.TimelineEntry entry, int index) {
        var root = (LayoutElementBuilders.Box) entry.getLayout().getRoot();
        var column = (LayoutElementBuilders.Column) root.getContents().get(0);
        var row = (LayoutElementBuilders.Box) column.getContents().get(index);
        return (LayoutElementBuilders.Text) row.getContents().get(0);
    }

    private int colour(TimelineBuilders.TimelineEntry entry, int row) {
        return rowText(entry, row).getFontStyle().getColor().getArgb();
    }
}
