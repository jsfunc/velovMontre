package fr.velovmontre.selection;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class StationSelectionTest {
    private final StationSelection.Choice a = new StationSelection.Choice("1001", "Terreaux");
    private final StationSelection.Choice b = new StationSelection.Choice("1002", "Opéra");
    private final StationSelection.Choice c = new StationSelection.Choice("1003", "Quai");
    private StationSelection sample() { return new StationSelection(1, 7, List.of(List.of(a, b, c), List.of())); }
    @Test public void editsStayImmutableAndRoundTripExactly() {
        var original = sample();
        var moved = original.move("1001", 0, 3);
        assertEquals(List.of(b, c, a), moved.groups().get(0));
        assertEquals(List.of(a, b, c), original.groups().get(0));
        assertTrue(moved.revision() > original.revision());
        assertEquals(moved, StationSelection.parse(moved.json()));
        assertThrows(UnsupportedOperationException.class, () -> moved.groups().get(0).clear());
    }
    @Test public void supportsCrossGroupMovesRemovalAndEmptyGroups() {
        var moved = sample().move("1002", 1, 0);
        assertEquals(List.of(a, c), moved.groups().get(0));
        assertEquals(List.of(b), moved.groups().get(1));
        var empty = moved.remove("1001").remove("1002").remove("1003");
        assertTrue(empty.ids().isEmpty());
        assertEquals(2, empty.groups().size());
        assertEquals(List.of(a), empty.add(1, a).groups().get(1));
    }
    @Test public void rejectsDuplicatesOverfullGroupsAndInjectedIds() {
        assertThrows(IllegalArgumentException.class, () -> sample().add(1, a));
        assertThrows(IllegalArgumentException.class, () -> StationSelection.defaults().add(0, a));
        assertThrows(IllegalArgumentException.class, () -> StationSelection.defaults().move("7046", 1, 0));
        for (String id : List.of("", "1) OR 1=1", "-1", "0", "1000000000"))
            assertThrows(IllegalArgumentException.class, () -> new StationSelection.Choice(id, "Nom"));
    }
    @Test public void malformedOrUnsupportedSyncCannotBecomeAConfiguration() {
        for (String json : List.of("null", "{}", "[]", sample().json().replace("\"schema\":1", "\"schema\":2"),
                sample().json().replace("Terreaux", ""), "x".repeat(16_385)))
            assertThrows(RuntimeException.class, () -> StationSelection.parse(json));
    }
    @Test public void catalogueSupportsNumberSearchAndPreservesCompleteNames() {
        var catalog = StationCatalog.parse("""
                {"features":[null,{"properties":{"number":1001,"name":"1001 - TERREAUX / TERME"}},
                {"properties":{"number":10010,"name":"10010 - FULL NAME"}}]}
                """);
        assertEquals("TERREAUX / TERME", catalog.get(0).name());
        assertEquals(List.of("1001", "10010"), StationCatalog.search(catalog, "1001").stream().map(StationSelection.Choice::id).toList());
        assertTrue(StationCatalog.search(catalog, "Terreaux").isEmpty());
        assertTrue(StationCatalog.search(catalog, "").isEmpty());
    }
}
