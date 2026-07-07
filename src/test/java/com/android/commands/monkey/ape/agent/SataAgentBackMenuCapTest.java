package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * back-menu-pick-cap §4 — pure static seams of the discretionary BACK/MENU cap.
 *
 * SataAgent is not instantiable on the JVM (its selection chain touches Android), so the cap
 * is exercised through the pure statics it reuses from mop-target-revisit-cap
 * ({@link SataAgent#eligibleForMopPick}/{@link SataAgent#recordMopPick}) plus the new key
 * derivation {@link SataAgent#backMenuPickKey} and the capped {@link ActionFilter} wrapper.
 */
public class SataAgentBackMenuCapTest {

    // ---- backMenuPickKey contract (INV-SEL-NAV-01) ---------------------------

    @Test
    public void testKeyForBackAndMenu() {
        assertEquals("A|MODEL_BACK", SataAgent.backMenuPickKey(ActionType.MODEL_BACK, "A"));
        assertEquals("A|MODEL_MENU", SataAgent.backMenuPickKey(ActionType.MODEL_MENU, "A"));
    }

    @Test
    public void testKeyNullForOtherTypes() {
        assertNull(SataAgent.backMenuPickKey(ActionType.MODEL_CLICK, "A"));
        assertNull(SataAgent.backMenuPickKey(ActionType.MODEL_LONG_CLICK, "A"));
        assertNull(SataAgent.backMenuPickKey(ActionType.MODEL_SCROLL_TOP_DOWN, "A"));
    }

    @Test
    public void testKeyNullForNullActivity() {
        assertNull(SataAgent.backMenuPickKey(ActionType.MODEL_BACK, null));
        assertNull(SataAgent.backMenuPickKey(ActionType.MODEL_MENU, null));
    }

    @Test
    public void testDistinctActivitiesAndTypesAreDistinctKeys() {
        String backA = SataAgent.backMenuPickKey(ActionType.MODEL_BACK, "A");
        String menuA = SataAgent.backMenuPickKey(ActionType.MODEL_BACK, "B");
        String backA2 = SataAgent.backMenuPickKey(ActionType.MODEL_MENU, "A");
        assertNotEquals(backA, menuA);
        assertNotEquals(backA, backA2);
    }

    // ---- eligibility / record round-trip at the cap boundary (INV-SEL-NAV-01/-04) ----

    @Test
    public void testEligibleUntilCapThenCappedOnce() {
        Map<String, Integer> picks = new HashMap<>();
        String key = SataAgent.backMenuPickKey(ActionType.MODEL_BACK, "A");
        int cap = 3;
        assertTrue(SataAgent.eligibleForMopPick(picks, key, cap));
        assertFalse("1st pick", SataAgent.recordMopPick(picks, key, cap));
        assertTrue(SataAgent.eligibleForMopPick(picks, key, cap));
        assertFalse("2nd pick", SataAgent.recordMopPick(picks, key, cap));
        assertTrue(SataAgent.eligibleForMopPick(picks, key, cap));
        assertTrue("3rd pick reaches cap (once)", SataAgent.recordMopPick(picks, key, cap));
        assertFalse("capped after 3", SataAgent.eligibleForMopPick(picks, key, cap));
        assertFalse("no re-signal after cap", SataAgent.recordMopPick(picks, key, cap));
    }

    // ---- cap disabled (INV-SEL-NAV-02) ---------------------------------------

    @Test
    public void testCapDisabledAlwaysEligibleNoCounting() {
        Map<String, Integer> picks = new HashMap<>();
        String key = SataAgent.backMenuPickKey(ActionType.MODEL_MENU, "A");
        picks.put(key, 999);
        assertTrue(SataAgent.eligibleForMopPick(picks, key, 0));
        assertTrue(SataAgent.eligibleForMopPick(picks, key, -1));
        assertFalse(SataAgent.recordMopPick(picks, key, 0));
        assertEquals(999, (int) picks.get(key)); // unchanged
    }

    // ---- capped ActionFilter wrapper (task 4.3, INV-SEL-NAV-04) --------------

    private static ModelAction typed(ActionType type) {
        ModelAction a = new ModelAction(null, type);
        a.setPriority(5);
        return a;
    }

    @Test
    public void testCappedFilterExcludesCappedTypesStably() {
        Map<String, Integer> picks = new HashMap<>();
        int cap = 3;
        String activity = "A";
        // BACK is capped, MENU is not.
        for (int i = 0; i < cap; i++) {
            SataAgent.recordMopPick(picks, SataAgent.backMenuPickKey(ActionType.MODEL_BACK, activity), cap);
        }
        ActionFilter capped = SataAgent.cappedBackMenuFilter(ActionFilter.ALL, picks, activity, cap);

        ModelAction back = typed(ActionType.MODEL_BACK);
        ModelAction menu = typed(ActionType.MODEL_MENU);
        ModelAction click = typed(ActionType.MODEL_CLICK);

        // Two passes over the same actions must give identical include decisions.
        for (int pass = 0; pass < 2; pass++) {
            assertFalse("capped BACK excluded", capped.include(back));
            assertTrue("uncapped MENU included", capped.include(menu));
            assertTrue("non-back/menu included", capped.include(click));
        }
    }

    @Test
    public void testCappedFilterHonoursBaseFilter() {
        // With an empty picks map (nothing capped), the wrapper is exactly the base filter.
        Map<String, Integer> picks = new HashMap<>();
        ActionFilter capped = SataAgent.cappedBackMenuFilter(ActionFilter.WITH_TARGET, picks, "A", 3);
        ModelAction back = typed(ActionType.MODEL_BACK); // no target
        // base WITH_TARGET already excludes target-less BACK, wrapper must not re-admit it
        assertEquals(ActionFilter.WITH_TARGET.include(back), capped.include(back));
    }

    @Test
    public void testCappedFilterDisabledCapIsBaseFilter() {
        Map<String, Integer> picks = new HashMap<>();
        picks.put(SataAgent.backMenuPickKey(ActionType.MODEL_BACK, "A"), 999);
        ActionFilter capped = SataAgent.cappedBackMenuFilter(ActionFilter.ALL, picks, "A", 0);
        // cap disabled → BACK still included despite the count
        assertTrue(capped.include(typed(ActionType.MODEL_BACK)));
    }

    // ---- menu-boost gate contract (task 4.4, INV-SEL-NAV) --------------------
    // SataAgent.menuPickEligible(activity) is exactly
    //   eligibleForMopPick(backMenuPicks, backMenuPickKey(MODEL_MENU, activity), backMenuPickCap)
    // and the boost pass uses `menuPickEligible(a) ? scoreOpenMenu : 0`, so a MENU key at the cap
    // forces menuBoost to 0 (no setPriority/setMenuBoost). The instance method is not JVM-invokable
    // (StatefulAgent/SataAgent are not instantiable off-device); we lock its underlying expression.

    @Test
    public void testMenuGateOpenBelowCapClosedAtCap() {
        Map<String, Integer> picks = new HashMap<>();
        String menuKey = SataAgent.backMenuPickKey(ActionType.MODEL_MENU, "A");
        int cap = 3;
        assertTrue("gate open below cap", SataAgent.eligibleForMopPick(picks, menuKey, cap));
        for (int i = 0; i < cap; i++) {
            SataAgent.recordMopPick(picks, menuKey, cap);
        }
        assertFalse("gate closed at cap → menuBoost forced to 0", SataAgent.eligibleForMopPick(picks, menuKey, cap));
        // The gate is MENU-scoped: a capped MENU key must not close the gate on other activities.
        String otherMenuKey = SataAgent.backMenuPickKey(ActionType.MODEL_MENU, "B");
        assertTrue("other activity's menu gate stays open", SataAgent.eligibleForMopPick(picks, otherMenuKey, cap));
    }
}
