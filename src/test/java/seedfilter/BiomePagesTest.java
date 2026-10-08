package seedfilter;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BiomePagesTest {
    static final List<String> ALL = IntStream.range(0, 30).mapToObj(i -> String.format("b%02d", i)).toList();
    static final Function<String, String> LABEL = Function.identity();

    static List<String> everyPage(Set<String> selected, String query) {
        int pages = FilterScreen.pageCount(selected, ALL, LABEL, query, 8);
        return IntStream.range(0, pages)
                .boxed().flatMap(p -> FilterScreen.page(selected, ALL, LABEL, query, p, 8).stream()).toList();
    }

    @Test
    void ninthBiomeCanBeAddedWithEightSelected() {
        Set<String> sel = new LinkedHashSet<>(ALL.subList(0, 8));
        assertTrue(everyPage(sel, "b2").contains("b20"), "search results must stay reachable");
    }

    @Test
    void handEditedTwelveSelectedAreAllVisible() {
        Set<String> sel = new LinkedHashSet<>(ALL.subList(10, 22));
        assertTrue(everyPage(sel, "").containsAll(sel));
    }

    @Test
    void selectedFirstThenMatchesSortedAndNoDuplicates() {
        Set<String> sel = new LinkedHashSet<>(List.of("b05", "b01"));
        List<String> all = everyPage(sel, "");
        assertEquals(List.of("b05", "b01", "b00", "b02"), all.subList(0, 4));
        assertEquals(all.size(), new LinkedHashSet<>(all).size());
        assertEquals(30, all.size());
    }

    @Test
    void pageOutOfRangeIsEmptyAndAtLeastOnePage() {
        assertEquals(List.of(), FilterScreen.page(Set.of(), ALL, LABEL, "zzz", 3, 8));
        assertEquals(1, FilterScreen.pageCount(Set.of(), ALL, LABEL, "zzz", 8));
    }
}
