package com.android.commands.monkey.ape.naming;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

/**
 * INV-NAME-13: {@link Naming#containsNamelet(List, Namelet)}, the membership test of
 * {@link Naming}'s namelet selection, under plans with and without {@code APE_FIXES}.
 *
 * <p>{@code Collections.binarySearch} returns {@code -(insertionPoint) - 1} for a missing element,
 * which is {@code <= -2} when the element sorts after the first. With the member (an ADDONS plan)
 * every negative result reads as absent; without it (the jar defaults, no ADDONS) only {@code -1}
 * does, as in APE. The static {@code comparator} orders by depth then exprStr, so this needs no
 * {@code Naming} instance and no Android runtime.
 */
public class NamingSelectTest {

    @After
    public void resetPlan() {
        RunContext.resetForTest();
    }

    private static void withMember() {
        TestRunSpecs.install("ape.addons", "true");
    }

    private static void withoutMember() {
        TestRunSpecs.install();
    }

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
    public void absentNameletAfterAllIsNotContainedWithMember() {
        withMember();
        // "g" sorts after {a,c,e} → binarySearch = -4.
        assertFalse(Naming.containsNamelet(sorted("a", "c", "e"), namelet("g")));
    }

    @Test
    public void absentNameletInMiddleIsNotContainedWithMember() {
        withMember();
        // "d" between c and e → binarySearch = -3.
        assertFalse(Naming.containsNamelet(sorted("a", "c", "e"), namelet("d")));
    }

    @Test
    public void absentNameletInMiddleReadsAsPresentWithoutMember() {
        withoutMember();
        // insertion point 2 → binarySearch = -3, which APE's `== -1` test reads as present.
        assertTrue(Naming.containsNamelet(sorted("a", "c", "e"), namelet("d")));
    }

    @Test
    public void absentNameletAfterAllReadsAsPresentWithoutMember() {
        withoutMember();
        assertTrue(Naming.containsNamelet(sorted("a", "c", "e"), namelet("g")));
    }

    @Test
    public void absentNameletBeforeFirstIsNotContainedInBothPlans() {
        // insertion point 0 → binarySearch = -1.
        withMember();
        assertFalse(Naming.containsNamelet(sorted("c", "e"), namelet("a")));
        withoutMember();
        assertFalse(Naming.containsNamelet(sorted("c", "e"), namelet("a")));
    }

    @Test
    public void presentNameletIsContainedInBothPlans() {
        List<Namelet> list = sorted("a", "c", "e");
        withMember();
        assertTrue(Naming.containsNamelet(list, list.get(1)));
        withoutMember();
        assertTrue(Naming.containsNamelet(list, list.get(1)));
    }
}
