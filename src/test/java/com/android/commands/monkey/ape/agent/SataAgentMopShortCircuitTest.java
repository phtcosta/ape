package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * mop-discriminative-boost (#2) — ranking logic of the MOP-target greedy short-circuit
 * ({@link SataAgent#pickBestMopTarget}). The unvisited/enabled/valid gating is the
 * {@code ENABLED_VALID_UNVISITED} filter applied by the caller and is device-gated along
 * with the rest of the selection chain; here we test the pure ranking the spec scenarios
 * pin (highest mopBoost wins, ties → highest priority, mopBoost ≤ 0 ignored, empty → null).
 *
 * <p>The {@link ModelAction} constructor does not dereference the State (see ModelActionTest),
 * so null-state actions with set boosts/priorities are sufficient.
 */
public class SataAgentMopShortCircuitTest {

    private static ModelAction action(int mopBoost, int priority) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(mopBoost);
        a.setPriority(priority);
        return a;
    }

    @Test
    public void testUnvisitedMopTargetSelectedAheadOfNonMop() {
        ModelAction mop = action(500, 10);
        List<ModelAction> candidates = Arrays.asList(action(0, 99), mop, action(0, 50));
        assertSame(mop, SataAgent.pickBestMopTarget(candidates));
    }

    @Test
    public void testHighestMopBoostWins() {
        ModelAction strong = action(500, 1);
        ModelAction weak = action(300, 99);
        assertSame(strong, SataAgent.pickBestMopTarget(Arrays.asList(weak, strong)));
    }

    @Test
    public void testTieBrokenByHighestPriority() {
        ModelAction lowPriority = action(300, 5);
        ModelAction highPriority = action(300, 40);
        assertSame(highPriority,
                SataAgent.pickBestMopTarget(Arrays.asList(lowPriority, highPriority)));
    }

    @Test
    public void testNoMopTargetIsNoOp() {
        List<ModelAction> candidates = Arrays.asList(action(0, 10), action(0, 99));
        assertNull(SataAgent.pickBestMopTarget(candidates));
    }

    @Test
    public void testEmptyCandidatesReturnsNull() {
        assertNull(SataAgent.pickBestMopTarget(new ArrayList<ModelAction>()));
        assertNull(SataAgent.pickBestMopTarget(Collections.<ModelAction>emptyList()));
    }

    // -------------------------------------------------------------------------
    // form-completion (#1) INV-FORM-06: the submit candidate is excluded from the
    // short-circuit while the form is incomplete; once it is not excluded, it wins.
    // -------------------------------------------------------------------------

    @Test
    public void testExcludedSubmitNotPickedWhileFormIncomplete() {
        // The submit handler is the mopBoost=500 action; while a field is unfilled it is excluded,
        // so the short-circuit picks no MOP target here (the only other action is non-MOP).
        ModelAction submit = action(500, 10);
        ModelAction field = action(0, 5);
        List<ModelAction> candidates = Arrays.asList(field, submit);
        assertNull("submit excluded while form incomplete",
                SataAgent.pickBestMopTarget(candidates, submit));
    }

    @Test
    public void testExcludedSubmitStillPicksOtherMopTarget() {
        // A second, non-submit MOP target remains eligible even while the submit is excluded.
        ModelAction submit = action(500, 10);
        ModelAction otherMop = action(300, 5);
        List<ModelAction> candidates = Arrays.asList(submit, otherMop);
        assertSame(otherMop, SataAgent.pickBestMopTarget(candidates, submit));
    }

    @Test
    public void testSubmitPickedOnceNotExcluded() {
        // Once the fields are filled the submit is no longer excluded (excluded=null) and wins.
        ModelAction submit = action(500, 10);
        ModelAction field = action(0, 5);
        List<ModelAction> candidates = Arrays.asList(field, submit);
        assertSame(submit, SataAgent.pickBestMopTarget(candidates, null));
    }

    // -------------------------------------------------------------------------
    // mop-target-revisit-cap (#3): per-(widget,type,activity) deterministic cap.
    // No JVM test can instantiate SataAgent, so the cap is exercised through its
    // pure static seam: mopPickKey / eligibleForMopPick / recordMopPick.
    // -------------------------------------------------------------------------

    private static Name fakeName(final String xpath) {
        return new Name() {
            @Override public Namer getNamer() { return null; }
            @Override public Name getLocalName() { return this; }
            @Override public boolean refinesTo(Name other) { return false; }
            @Override public String toXPath() { return xpath; }
            @Override public void appendXPathLocalProperties(StringBuilder sb) { sb.append(xpath); }
            @Override public void toXPath(StringBuilder sb) { sb.append(xpath); }
            @Override public int compareTo(Name o) { return xpath.compareTo(o.toXPath()); }
            @Override public int hashCode() { return xpath.hashCode(); }
            @Override public boolean equals(Object o) {
                return o instanceof Name && xpath.equals(((Name) o).toXPath());
            }
        };
    }

    private static ModelAction targeted(String xpath, ActionType type, int mopBoost) {
        ModelAction a = new ModelAction(null, fakeName(xpath), type);
        a.setMopBoost(mopBoost);
        return a;
    }

    @Test
    public void testMopPickKeyFormat() {
        ModelAction a = targeted("//X", ActionType.MODEL_CLICK, 300);
        assertEquals("//X|MODEL_CLICK|com.example.Act",
                SataAgent.mopPickKey(a, "com.example.Act"));
    }

    @Test
    public void testMopPickKeyNullTargetReturnsNull() {
        ModelAction nonTargeted = action(300, 10); // built with the (state,type) ctor → null target
        assertNull(SataAgent.mopPickKey(nonTargeted, "com.example.Act"));
        assertNull(SataAgent.mopPickKey(null, "com.example.Act"));
    }

    /** 3.1 (INV-SEL-MOP-04): the action type is part of the key — capping clicks leaves long-click free. */
    @Test
    public void testKeyIncludesActionType() {
        ModelAction click = targeted("//X", ActionType.MODEL_CLICK, 300);
        ModelAction longClick = targeted("//X", ActionType.MODEL_LONG_CLICK, 300);
        String clickKey = SataAgent.mopPickKey(click, "A");
        String longKey = SataAgent.mopPickKey(longClick, "A");
        assertNotEquals(clickKey, longKey);

        Map<String, Integer> picks = new HashMap<>();
        int cap = 3;
        // Three deterministic clicks on X exhaust the click allowance.
        for (int i = 0; i < 3; i++) {
            assertTrue(SataAgent.eligibleForMopPick(picks, clickKey, cap));
            SataAgent.recordMopPick(picks, clickKey, cap);
        }
        assertFalse("click key capped after 3 picks", SataAgent.eligibleForMopPick(picks, clickKey, cap));
        // The long-click on the same widget is a distinct key → still eligible.
        assertTrue("long-click on same widget stays eligible",
                SataAgent.eligibleForMopPick(picks, longKey, cap));
    }

    /** 3.2: same (widget,type) in different activities are independent keys. */
    @Test
    public void testDistinctActivitiesCountedIndependently() {
        ModelAction a = targeted("//X", ActionType.MODEL_CLICK, 300);
        String keyA = SataAgent.mopPickKey(a, "com.example.ActA");
        String keyB = SataAgent.mopPickKey(a, "com.example.ActB");
        assertNotEquals(keyA, keyB);

        Map<String, Integer> picks = new HashMap<>();
        int cap = 3;
        for (int i = 0; i < 3; i++) {
            SataAgent.recordMopPick(picks, keyA, cap);
        }
        assertFalse(SataAgent.eligibleForMopPick(picks, keyA, cap));
        assertTrue("other activity's key is independent",
                SataAgent.eligibleForMopPick(picks, keyB, cap));
    }

    /** 3.3 (INV-SEL-MOP-05): cap <= 0 disables the cap entirely — always eligible, no counting. */
    @Test
    public void testCapDisabledAlwaysEligible() {
        Map<String, Integer> picks = new HashMap<>();
        picks.put("//X|MODEL_CLICK|A", 999);
        assertTrue(SataAgent.eligibleForMopPick(picks, "//X|MODEL_CLICK|A", 0));
        assertTrue(SataAgent.eligibleForMopPick(picks, "//X|MODEL_CLICK|A", -1));
        // recordMopPick is a no-op when the cap is disabled (no counter update).
        assertFalse(SataAgent.recordMopPick(picks, "//Y|MODEL_CLICK|A", 0));
        assertFalse(picks.containsKey("//Y|MODEL_CLICK|A"));
    }

    /** 3.5: a null key (uncountable action) is always eligible and never counted. */
    @Test
    public void testNullKeyAlwaysEligible() {
        Map<String, Integer> picks = new HashMap<>();
        assertTrue(SataAgent.eligibleForMopPick(picks, null, 3));
        assertFalse(SataAgent.recordMopPick(picks, null, 3));
        assertTrue(picks.isEmpty());
    }

    /** 3.4: recordMopPick signals "reached the cap" exactly once — on the cap-th pick. */
    @Test
    public void testRecordMopPickReachesCapOnce() {
        Map<String, Integer> picks = new HashMap<>();
        String key = "//X|MODEL_CLICK|A";
        int cap = 3;
        assertFalse("1st pick", SataAgent.recordMopPick(picks, key, cap));
        assertFalse("2nd pick", SataAgent.recordMopPick(picks, key, cap));
        assertTrue("3rd pick reaches cap", SataAgent.recordMopPick(picks, key, cap));
        // A hypothetical later pick (filtered in practice) must not re-signal.
        assertFalse("4th pick does not re-signal", SataAgent.recordMopPick(picks, key, cap));
    }

    /** 3.6: the cap never mutates the action's mopBoost — it stays roulette-visible. */
    @Test
    public void testCappedActionKeepsMopBoost() {
        ModelAction click = targeted("//X", ActionType.MODEL_CLICK, 300);
        String key = SataAgent.mopPickKey(click, "A");
        Map<String, Integer> picks = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            SataAgent.recordMopPick(picks, key, 3);
        }
        assertFalse(SataAgent.eligibleForMopPick(picks, key, 3));
        assertEquals("mopBoost unchanged after capping", 300, click.getMopBoost());
    }
}
