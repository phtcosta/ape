package com.android.commands.monkey.ape.naming;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * INV-NAME-13 (task 1.4): {@link Naming#containsNamelet(List, Namelet)} — the guard extracted from
 * {@link Naming}'s namelet selection — must treat every absent namelet as absent, not only the
 * {@code insertionPoint == 0} case.
 *
 * <p>{@code Collections.binarySearch} returns {@code -(insertionPoint) - 1} for a missing element,
 * which is {@code <= -2} when the element sorts after the first. The old {@code == -1} guard
 * misclassified those as present, so an ancestor namelet outside the selected set was wrongly
 * treated as included. The static {@code comparator} orders by depth then exprStr, so this needs
 * no {@code Naming} instance and no Android runtime.
 */
public class NamingSelectTest {

    private static Namelet namelet(String exprStr) {
        // Namer is irrelevant to the depth/exprStr comparator; use the JVM-safe singleton.
        return new Namelet(Namelet.Type.REFINE, exprStr, EmptyNamer.emptyNamer);
    }

    private static List<Namelet> sorted(String... exprs) {
        List<Namelet> list = new ArrayList<>();
        for (String e : exprs) {
            list.add(namelet(e));
        }
        return list; // already ascending by exprStr at depth 0
    }

    @Test
    public void absentNameletAfterAllIsNotContained() {
        // "g" sorts after {a,c,e} → binarySearch = -4; old `== -1` called it present.
        assertFalse(Naming.containsNamelet(sorted("a", "c", "e"), namelet("g")));
    }

    @Test
    public void absentNameletInMiddleIsNotContained() {
        // "d" between c and e → binarySearch = -3.
        assertFalse(Naming.containsNamelet(sorted("a", "c", "e"), namelet("d")));
    }

    @Test
    public void absentNameletBeforeFirstIsNotContained() {
        // insertion point 0 → binarySearch = -1 (the case the old guard already handled).
        assertFalse(Naming.containsNamelet(sorted("c", "e"), namelet("a")));
    }

    @Test
    public void presentNameletIsContained() {
        List<Namelet> list = sorted("a", "c", "e");
        assertTrue(Naming.containsNamelet(list, list.get(1)));
    }
}
