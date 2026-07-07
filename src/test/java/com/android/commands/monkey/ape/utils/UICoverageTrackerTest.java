package com.android.commands.monkey.ape.utils;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.Before;
import org.junit.Test;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;

/**
 * Unit tests for {@link UICoverageTracker}.
 *
 * <p>Verifies INV-COV-01..04 and all scenarios from the ui-coverage spec.
 *
 * <p>Since State/StateKey/ModelAction depend on Android APIs (ComponentName) in their
 * normal constructors, this test uses reflection to create minimal instances that
 * satisfy the tracker's map-key requirements (equals/hashCode via StateKey).
 */
public class UICoverageTrackerTest {

    private UICoverageTracker tracker;

    @Before
    public void setUp() {
        tracker = new UICoverageTracker();
    }

    // -----------------------------------------------------------------------
    // Helper: create a State with a unique identity (via reflection)
    // -----------------------------------------------------------------------

    /**
     * Creates a State object with a distinct StateKey via reflection,
     * bypassing the normal constructor that requires Android's ComponentName.
     */
    private static State createState(String activity, Name... widgets) throws Exception {
        // Create StateKey via Unsafe/reflection to set fields directly
        // (bypasses constructor that requires Android's ComponentName).
        StateKey sk = createStateKeyReflective(activity, widgets);

        // Create State bypassing constructor
        State state = allocateInstance(State.class);
        Field stateKeyField = State.class.getDeclaredField("stateKey");
        stateKeyField.setAccessible(true);
        stateKeyField.set(state, sk);

        // Set actions array (empty — we pass actions separately to registerScreenElements)
        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[0]);

        return state;
    }

    private static StateKey createStateKeyReflective(String activity, Name[] widgets) throws Exception {
        StateKey sk = allocateInstance(StateKey.class);

        Field activityField = StateKey.class.getDeclaredField("activity");
        activityField.setAccessible(true);
        activityField.set(sk, activity);

        Field widgetsField = StateKey.class.getDeclaredField("widgets");
        widgetsField.setAccessible(true);
        widgetsField.set(sk, widgets != null ? widgets : new Name[0]);

        // naming left null — that's fine for equals/hashCode (both handle null)

        return sk;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> clazz) throws Exception {
        // Use sun.misc.Unsafe to allocate without calling constructor
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return (T) allocate.invoke(unsafe, clazz);
    }

    // -----------------------------------------------------------------------
    // Helper: create ModelAction objects
    // -----------------------------------------------------------------------

    /**
     * Creates a targeted ModelAction (e.g., MODEL_CLICK on a Name).
     */
    private static ModelAction createTargetedAction(State state, Name target, ActionType type) {
        return new ModelAction(state, target, type);
    }

    /**
     * Creates a non-targeted ModelAction (e.g., MODEL_BACK).
     */
    private static ModelAction createNonTargetedAction(State state, ActionType type) {
        return new ModelAction(state, type);
    }

    // -----------------------------------------------------------------------
    // Helper: simple Name implementation for tests (no Android deps)
    // -----------------------------------------------------------------------

    /**
     * Minimal Name implementation that returns a fixed XPath string.
     */
    private static Name fakeName(final String xpath) {
        return new Name() {
            @Override
            public Namer getNamer() { return null; }
            @Override
            public Name getLocalName() { return this; }
            @Override
            public boolean refinesTo(Name other) { return false; }
            @Override
            public String toXPath() { return xpath; }
            @Override
            public void appendXPathLocalProperties(StringBuilder sb) {
                sb.append(xpath);
            }
            @Override
            public void toXPath(StringBuilder sb) { sb.append(xpath); }
            @Override
            public int compareTo(Name o) { return xpath.compareTo(o.toXPath()); }
            @Override
            public int hashCode() { return xpath.hashCode(); }
            @Override
            public boolean equals(Object o) {
                if (o instanceof Name) {
                    return xpath.equals(((Name) o).toXPath());
                }
                return false;
            }
        };
    }

    // =======================================================================
    // INV-COV-01: getCoverageGap returns value in [0.0, 1.0]
    // =======================================================================

    @Test
    public void testInvCov01_gapInRange_noInteractions() throws Exception {
        State state = createState("com.example.Activity1");
        List<ModelAction> actions = Arrays.asList(
                createTargetedAction(state, fakeName("//Button[@text=\"OK\"]"), ActionType.MODEL_CLICK),
                createTargetedAction(state, fakeName("//EditText[@resource-id=\"email\"]"), ActionType.MODEL_CLICK),
                createNonTargetedAction(state, ActionType.MODEL_BACK)
        );
        tracker.registerScreenElements(state, actions);

        float gap = tracker.getCoverageGap(state);
        assertTrue("Gap must be >= 0.0", gap >= 0.0f);
        assertTrue("Gap must be <= 1.0", gap <= 1.0f);
        assertEquals("No interactions yet => gap should be 1.0", 1.0f, gap, 0.001f);
    }

    @Test
    public void testInvCov01_gapInRange_fullCoverage() throws Exception {
        State state = createState("com.example.Activity2");
        Name okName = fakeName("//Button[@text=\"OK\"]");
        ModelAction clickOk = createTargetedAction(state, okName, ActionType.MODEL_CLICK);
        ModelAction back = createNonTargetedAction(state, ActionType.MODEL_BACK);

        tracker.registerScreenElements(state, Arrays.asList(clickOk, back));

        tracker.recordInteraction(state, clickOk);
        tracker.recordInteraction(state, back);

        float gap = tracker.getCoverageGap(state);
        assertTrue("Gap must be >= 0.0", gap >= 0.0f);
        assertTrue("Gap must be <= 1.0", gap <= 1.0f);
        assertEquals("Full coverage => gap should be 0.0", 0.0f, gap, 0.001f);
    }

    // =======================================================================
    // INV-COV-02: gap monotonically decreases with interactions
    // =======================================================================

    @Test
    public void testInvCov02_gapMonotonicallyDecreases() throws Exception {
        State state = createState("com.example.MonoActivity");
        Name w1 = fakeName("//Button[@text=\"A\"]");
        Name w2 = fakeName("//Button[@text=\"B\"]");
        Name w3 = fakeName("//Button[@text=\"C\"]");
        Name w4 = fakeName("//Button[@text=\"D\"]");

        ModelAction a1 = createTargetedAction(state, w1, ActionType.MODEL_CLICK);
        ModelAction a2 = createTargetedAction(state, w2, ActionType.MODEL_CLICK);
        ModelAction a3 = createTargetedAction(state, w3, ActionType.MODEL_CLICK);
        ModelAction a4 = createTargetedAction(state, w4, ActionType.MODEL_CLICK);

        tracker.registerScreenElements(state, Arrays.asList(a1, a2, a3, a4));

        float prevGap = tracker.getCoverageGap(state);
        assertEquals(1.0f, prevGap, 0.001f);

        // Interact with w1
        tracker.recordInteraction(state, a1);
        float gap1 = tracker.getCoverageGap(state);
        assertTrue("Gap must decrease or stay same after interaction", gap1 <= prevGap);
        assertEquals(0.75f, gap1, 0.001f);
        prevGap = gap1;

        // Interact with w1 again (repeated — gap should not change)
        tracker.recordInteraction(state, a1);
        float gap1r = tracker.getCoverageGap(state);
        assertTrue("Repeated interaction must not increase gap", gap1r <= prevGap);
        assertEquals(0.75f, gap1r, 0.001f);

        // Interact with w2
        tracker.recordInteraction(state, a2);
        float gap2 = tracker.getCoverageGap(state);
        assertTrue("Gap must decrease after new widget interaction", gap2 <= prevGap);
        assertEquals(0.5f, gap2, 0.001f);
        prevGap = gap2;

        // Interact with w3
        tracker.recordInteraction(state, a3);
        float gap3 = tracker.getCoverageGap(state);
        assertTrue("Gap must decrease", gap3 <= prevGap);
        assertEquals(0.25f, gap3, 0.001f);
        prevGap = gap3;

        // Interact with w4
        tracker.recordInteraction(state, a4);
        float gap4 = tracker.getCoverageGap(state);
        assertTrue("Gap must decrease to 0", gap4 <= prevGap);
        assertEquals(0.0f, gap4, 0.001f);
    }

    // =======================================================================
    // INV-COV-03: unknown state returns 1.0
    // =======================================================================

    @Test
    public void testInvCov03_unknownStateReturns1() throws Exception {
        State unknown = createState("com.example.UnknownActivity");
        assertEquals("Unknown state must return 1.0", 1.0f, tracker.getCoverageGap(unknown), 0.001f);
    }

    @Test
    public void testInvCov03_nullStateReturns1() {
        assertEquals("Null state must return 1.0", 1.0f, tracker.getCoverageGap(null), 0.001f);
    }

    // =======================================================================
    // INV-COV-04: widgetId always non-null non-empty
    // =======================================================================

    @Test
    public void testInvCov04_widgetIdForTargetedAction() throws Exception {
        State state = createState("com.example.Act");
        Name name = fakeName("//Button[@text=\"OK\"]");
        ModelAction action = createTargetedAction(state, name, ActionType.MODEL_CLICK);

        String id = UICoverageTracker.widgetId(action);
        assertNotNull("widgetId must not be null for targeted action", id);
        assertFalse("widgetId must not be empty for targeted action", id.isEmpty());
        // INV-COV-06: targeted-action key now incorporates the action type.
        assertEquals("//Button[@text=\"OK\"]|MODEL_CLICK", id);
    }

    @Test
    public void testInvCov04_widgetIdForNonTargetedAction() throws Exception {
        State state = createState("com.example.Act");
        ModelAction back = createNonTargetedAction(state, ActionType.MODEL_BACK);
        ModelAction menu = createNonTargetedAction(state, ActionType.MODEL_MENU);

        String backId = UICoverageTracker.widgetId(back);
        assertNotNull("widgetId must not be null for BACK", backId);
        assertFalse("widgetId must not be empty for BACK", backId.isEmpty());
        assertEquals("MODEL_BACK", backId);

        String menuId = UICoverageTracker.widgetId(menu);
        assertNotNull("widgetId must not be null for MENU", menuId);
        assertFalse("widgetId must not be empty for MENU", menuId.isEmpty());
        assertEquals("MODEL_MENU", menuId);
    }

    @Test
    public void testInvCov04_widgetIdForNullAction() {
        String id = UICoverageTracker.widgetId(null);
        assertNotNull("widgetId must not be null even for null action", id);
        // Returns empty string for null — this is the fallback
    }

    // =======================================================================
    // Scenario: Register widgets for a new state
    // =======================================================================

    @Test
    public void testRegisterNewState() throws Exception {
        State state = createState("com.example.RegisterActivity");
        Name okName = fakeName("//Button[@text=\"OK\"]");
        Name emailName = fakeName("//EditText[@resource-id=\"email\"]");

        ModelAction clickOk = createTargetedAction(state, okName, ActionType.MODEL_CLICK);
        ModelAction clickEmail = createTargetedAction(state, emailName, ActionType.MODEL_CLICK);
        ModelAction back = createNonTargetedAction(state, ActionType.MODEL_BACK);

        tracker.registerScreenElements(state, Arrays.asList(clickOk, clickEmail, back));

        // 3 registered elements, all with 0 interactions
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]|MODEL_CLICK"));
        assertEquals(0, tracker.getInteractionCount(state, "//EditText[@resource-id=\"email\"]|MODEL_CLICK"));
        assertEquals(0, tracker.getInteractionCount(state, "MODEL_BACK"));
        assertEquals(1.0f, tracker.getCoverageGap(state), 0.001f);
        assertEquals(3, tracker.getTotalElements());
    }

    // =======================================================================
    // Scenario: Re-register same state with different widgets
    // =======================================================================

    @Test
    public void testReRegisterSameState() throws Exception {
        State state = createState("com.example.ReRegActivity");
        Name w1 = fakeName("//Button[@text=\"A\"]");
        Name w2 = fakeName("//Button[@text=\"B\"]");
        Name w3 = fakeName("//Button[@text=\"C\"]");

        ModelAction a1 = createTargetedAction(state, w1, ActionType.MODEL_CLICK);
        ModelAction a2 = createTargetedAction(state, w2, ActionType.MODEL_CLICK);

        // First registration: 2 widgets
        tracker.registerScreenElements(state, Arrays.asList(a1, a2));
        assertEquals(2, tracker.getTotalElements());
        assertEquals(1.0f, tracker.getCoverageGap(state), 0.001f);

        // Interact with w1
        tracker.recordInteraction(state, a1);
        assertEquals(0.5f, tracker.getCoverageGap(state), 0.001f);

        // Re-register with different widgets: w2, w3
        ModelAction a3 = createTargetedAction(state, w3, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(state, Arrays.asList(a2, a3));

        // w1's interaction count should be gone (not in new set)
        // w2 was in old set with 0 count, should be preserved
        // w3 is new
        assertEquals(2, tracker.getTotalElements());
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"A\"]|MODEL_CLICK"));
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"B\"]|MODEL_CLICK"));
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"C\"]|MODEL_CLICK"));
    }

    // =======================================================================
    // Scenario: Record first interaction
    // =======================================================================

    @Test
    public void testRecordFirstInteraction() throws Exception {
        State state = createState("com.example.FirstInteract");
        Name okName = fakeName("//Button[@text=\"OK\"]");
        ModelAction clickOk = createTargetedAction(state, okName, ActionType.MODEL_CLICK);

        tracker.registerScreenElements(state, Arrays.asList(clickOk));
        tracker.recordInteraction(state, clickOk);

        assertEquals(1, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]|MODEL_CLICK"));
    }

    // =======================================================================
    // Scenario: Record repeated interaction
    // =======================================================================

    @Test
    public void testRecordRepeatedInteraction() throws Exception {
        State state = createState("com.example.RepeatInteract");
        Name okName = fakeName("//Button[@text=\"OK\"]");
        ModelAction clickOk = createTargetedAction(state, okName, ActionType.MODEL_CLICK);

        tracker.registerScreenElements(state, Arrays.asList(clickOk));

        tracker.recordInteraction(state, clickOk);
        tracker.recordInteraction(state, clickOk);
        tracker.recordInteraction(state, clickOk);

        assertEquals(3, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]|MODEL_CLICK"));
    }

    // =======================================================================
    // Scenario: Partial coverage (3 of 10 widgets interacted)
    // =======================================================================

    @Test
    public void testPartialCoverage() throws Exception {
        State state = createState("com.example.PartialAct");
        List<ModelAction> actions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Name name = fakeName("//Widget[@id=\"w" + i + "\"]");
            actions.add(createTargetedAction(state, name, ActionType.MODEL_CLICK));
        }

        tracker.registerScreenElements(state, actions);
        assertEquals(1.0f, tracker.getCoverageGap(state), 0.001f);

        // Interact with 3 distinct widgets
        tracker.recordInteraction(state, actions.get(0));
        tracker.recordInteraction(state, actions.get(3));
        tracker.recordInteraction(state, actions.get(7));

        assertEquals("3 of 10 interacted => gap = 0.7", 0.7f, tracker.getCoverageGap(state), 0.001f);
    }

    // =======================================================================
    // Scenario: Full coverage
    // =======================================================================

    @Test
    public void testFullCoverage() throws Exception {
        State state = createState("com.example.FullAct");
        List<ModelAction> actions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Name name = fakeName("//Widget[@id=\"w" + i + "\"]");
            actions.add(createTargetedAction(state, name, ActionType.MODEL_CLICK));
        }

        tracker.registerScreenElements(state, actions);

        for (ModelAction action : actions) {
            tracker.recordInteraction(state, action);
        }

        assertEquals("All 10 interacted => gap = 0.0", 0.0f, tracker.getCoverageGap(state), 0.001f);
    }

    // =======================================================================
    // Scenario: Query interaction count
    // =======================================================================

    @Test
    public void testInteractionCount_known() throws Exception {
        State state = createState("com.example.CountAct");
        Name okName = fakeName("//Button[@text=\"OK\"]");
        ModelAction clickOk = createTargetedAction(state, okName, ActionType.MODEL_CLICK);

        tracker.registerScreenElements(state, Arrays.asList(clickOk));

        for (int i = 0; i < 5; i++) {
            tracker.recordInteraction(state, clickOk);
        }

        assertEquals(5, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]|MODEL_CLICK"));
    }

    // =======================================================================
    // Scenario: Unknown widget returns 0
    // =======================================================================

    @Test
    public void testInteractionCount_unknownWidget() throws Exception {
        State state = createState("com.example.UnknownWidget");
        Name okName = fakeName("//Button[@text=\"OK\"]");
        ModelAction clickOk = createTargetedAction(state, okName, ActionType.MODEL_CLICK);

        tracker.registerScreenElements(state, Arrays.asList(clickOk));

        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"Cancel\"]"));
    }

    // =======================================================================
    // Scenario: Unknown state returns 0 for interaction count
    // =======================================================================

    @Test
    public void testInteractionCount_unknownState() throws Exception {
        State unknown = createState("com.example.NeverRegistered");
        assertEquals(0, tracker.getInteractionCount(unknown, "//anything"));
    }

    // =======================================================================
    // Null safety
    // =======================================================================

    @Test
    public void testRegister_nullState() {
        // Should not throw
        tracker.registerScreenElements(null, Collections.emptyList());
        assertEquals(0, tracker.getTotalElements());
    }

    @Test
    public void testRegister_nullActions() throws Exception {
        State state = createState("com.example.NullActions");
        tracker.registerScreenElements(state, null);
        assertEquals(1.0f, tracker.getCoverageGap(state), 0.001f);
    }

    @Test
    public void testRecordInteraction_nullState() throws Exception {
        State state = createState("com.example.NullRec");
        Name name = fakeName("//Button");
        ModelAction action = createTargetedAction(state, name, ActionType.MODEL_CLICK);
        // Should not throw
        tracker.recordInteraction(null, action);
    }

    @Test
    public void testRecordInteraction_nullAction() throws Exception {
        State state = createState("com.example.NullAct");
        tracker.registerScreenElements(state, Collections.emptyList());
        // Should not throw
        tracker.recordInteraction(state, null);
    }

    // =======================================================================
    // Telemetry
    // =======================================================================

    @Test
    public void testTelemetry_totalElementsAndInteractions() throws Exception {
        State s1 = createState("com.example.Telemetry1");
        State s2 = createState("com.example.Telemetry2");

        Name w1 = fakeName("//Widget[@id=\"a\"]");
        Name w2 = fakeName("//Widget[@id=\"b\"]");
        Name w3 = fakeName("//Widget[@id=\"c\"]");

        tracker.registerScreenElements(s1, Arrays.asList(
                createTargetedAction(s1, w1, ActionType.MODEL_CLICK),
                createTargetedAction(s1, w2, ActionType.MODEL_CLICK)
        ));
        tracker.registerScreenElements(s2, Arrays.asList(
                createTargetedAction(s2, w3, ActionType.MODEL_CLICK)
        ));

        assertEquals(3, tracker.getTotalElements());
        assertEquals(0, tracker.getTotalInteractions());

        tracker.recordInteraction(s1, createTargetedAction(s1, w1, ActionType.MODEL_CLICK));
        tracker.recordInteraction(s1, createTargetedAction(s1, w1, ActionType.MODEL_CLICK));
        tracker.recordInteraction(s2, createTargetedAction(s2, w3, ActionType.MODEL_CLICK));

        assertEquals(3, tracker.getTotalElements());
        assertEquals(3, tracker.getTotalInteractions());
    }

    // =======================================================================
    // Multiple states are independent
    // =======================================================================

    @Test
    public void testMultipleStatesIndependent() throws Exception {
        State s1 = createState("com.example.IndepA");
        State s2 = createState("com.example.IndepB");

        Name w1 = fakeName("//Button[@text=\"X\"]");
        Name w2 = fakeName("//Button[@text=\"Y\"]");

        tracker.registerScreenElements(s1, Arrays.asList(
                createTargetedAction(s1, w1, ActionType.MODEL_CLICK)
        ));
        tracker.registerScreenElements(s2, Arrays.asList(
                createTargetedAction(s2, w2, ActionType.MODEL_CLICK)
        ));

        tracker.recordInteraction(s1, createTargetedAction(s1, w1, ActionType.MODEL_CLICK));

        assertEquals("s1 fully covered", 0.0f, tracker.getCoverageGap(s1), 0.001f);
        assertEquals("s2 still unexplored", 1.0f, tracker.getCoverageGap(s2), 0.001f);
    }

    // =======================================================================
    // Record interaction for unregistered state creates entry
    // =======================================================================

    @Test
    public void testRecordInteractionForUnregisteredState() throws Exception {
        State state = createState("com.example.Unreg");
        Name name = fakeName("//Button[@text=\"Z\"]");
        ModelAction action = createTargetedAction(state, name, ActionType.MODEL_CLICK);

        tracker.recordInteraction(state, action);

        assertEquals(1, tracker.getInteractionCount(state, "//Button[@text=\"Z\"]|MODEL_CLICK"));
        // Only 1 widget registered implicitly
        assertEquals(0.0f, tracker.getCoverageGap(state), 0.001f);
    }

    // =======================================================================
    // gh15 A-4: action-type in the key (INV-COV-06), per-Activity aggregation,
    // and bounded stateData with rollup preservation (INV-COV-05).
    // =======================================================================

    /** INV-COV-06: same target, different action types → distinct coverage elements. */
    @Test
    public void testDistinctActionTypesOnSameTargetAreSeparate() throws Exception {
        State state = createState("com.example.ScrollAct");
        Name list = fakeName("//RecyclerView");
        ModelAction down = createTargetedAction(state, list, ActionType.MODEL_SCROLL_TOP_DOWN);
        ModelAction up = createTargetedAction(state, list, ActionType.MODEL_SCROLL_BOTTOM_UP);

        tracker.registerScreenElements(state, Arrays.asList(down, up));

        // Two distinct registered elements despite sharing the target.
        assertEquals(2, tracker.getTotalElements());
        assertEquals("//RecyclerView|MODEL_SCROLL_TOP_DOWN", UICoverageTracker.widgetId(down));
        assertEquals("//RecyclerView|MODEL_SCROLL_BOTTOM_UP", UICoverageTracker.widgetId(up));

        // Interacting with one leaves the other uncovered (gap 0.5, not 0.0).
        tracker.recordInteraction(state, down);
        assertEquals(0.5f, tracker.getCoverageGap(state), 0.001f);
    }

    /** Per-Activity reporting aggregates naming fragments of one Activity. */
    @Test
    public void testActivityCoverageAggregatesFragments() throws Exception {
        // Two distinct StateKeys (naming fragments) of the same Activity.
        Name w1 = fakeName("//Button[@text=\"A\"]");
        Name w2 = fakeName("//Button[@text=\"B\"]");
        State frag1 = createState("com.example.Frag", w1);
        State frag2 = createState("com.example.Frag", w2);

        ModelAction a1 = createTargetedAction(frag1, w1, ActionType.MODEL_CLICK);
        ModelAction a2 = createTargetedAction(frag2, w2, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(frag1, Arrays.asList(a1));
        tracker.registerScreenElements(frag2, Arrays.asList(a2));

        // One widget covered out of two across the Activity's fragments → gap 0.5.
        tracker.recordInteraction(frag1, a1);
        assertEquals(0.5f, tracker.getActivityCoverageGap("com.example.Frag"), 0.001f);
        assertEquals(1.0f, tracker.getActivityCoverageGap("com.example.Other"), 0.001f);
    }

    /**
     * INV-COV-05: when stateData exceeds its bound the least-recently-used state is
     * evicted, but its coverage is preserved in the per-Activity rollup so the reported
     * per-Activity gap does not regress, even though the per-state entry is gone.
     */
    @Test
    public void testBoundedStateDataPreservesRollupOnEviction() throws Exception {
        // The "Kept" state, fully covered, then accessed last so it is NOT most-recent
        // once the fillers arrive.
        Name kept = fakeName("//Button[@text=\"keep\"]");
        State keptState = createState("com.example.Kept", kept);
        ModelAction keptAction = createTargetedAction(keptState, kept, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(keptState, Arrays.asList(keptAction));
        tracker.recordInteraction(keptState, keptAction);
        assertEquals(0.0f, tracker.getCoverageGap(keptState), 0.001f);

        // Flood with > coverageMaxStates distinct states so the LRU evicts "Kept".
        int fillers = Config.coverageMaxStates + 1;
        for (int i = 0; i < fillers; i++) {
            Name fw = fakeName("//Filler[@i=\"" + i + "\"]");
            State fs = createState("com.example.Filler" + i, fw);
            tracker.registerScreenElements(fs, Arrays.asList(
                    createTargetedAction(fs, fw, ActionType.MODEL_CLICK)));
        }

        // The per-state entry for "Kept" was evicted (unknown → gap 1.0)...
        assertEquals("evicted state has no live per-state entry",
                1.0f, tracker.getCoverageGap(keptState), 0.001f);
        // ...but its coverage survives in the per-Activity rollup (gap stays 0.0).
        assertEquals("rollup preserves evicted coverage",
                0.0f, tracker.getActivityCoverageGap("com.example.Kept"), 0.001f);
    }

    // =======================================================================
    // Coverage dump (exploration-observability item #4): [APE-RV] UICOV lines
    // =======================================================================

    /** Builds a per-state widget map keyed by the widgetId convention "<xpath>|<TYPE>". */
    private static Map<String, Integer> data(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    public void testDump_partialCoverageLine() throws Exception {
        // 10 registered widgets, 3 interacted => gap 0.7 (spec scenario).
        State state = createState("com.example.MainActivity");
        Map<String, Integer> d = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            d.put("//Button[@i=\"" + i + "\"]|MODEL_CLICK", i < 3 ? 1 : 0);
        }
        String line = tracker.formatCoverageLine(state, d, s -> false);
        assertTrue(line, line.startsWith("[APE-RV] UICOV state="));
        assertTrue(line, line.contains("discovered=10 interacted=3 gap=0.7"));
        assertTrue(line, line.endsWith("mopReach=0"));
    }

    @Test
    public void testDump_fullyUnexploredGapOne() throws Exception {
        State state = createState("com.example.Empty");
        Map<String, Integer> d = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            d.put("//W[@i=\"" + i + "\"]|MODEL_CLICK", 0);
        }
        String line = tracker.formatCoverageLine(state, d, s -> false);
        assertTrue(line, line.contains("discovered=8 interacted=0 gap=1.0"));
    }

    @Test
    public void testDump_byTypeBreakdownInActionTypeOrder() throws Exception {
        // 3 MODEL_CLICK (2 interacted) + 1 MODEL_LONG_CLICK (0 interacted).
        State state = createState("com.example.ByType");
        Map<String, Integer> d = data(
                "//a|MODEL_CLICK", 1,
                "//b|MODEL_CLICK", 2,
                "//c|MODEL_CLICK", 0,
                "//d|MODEL_LONG_CLICK", 0);
        String line = tracker.formatCoverageLine(state, d, s -> false);
        // MODEL_CLICK precedes MODEL_LONG_CLICK in ActionType declaration order.
        assertTrue(line, line.contains("byType=MODEL_CLICK:2/3,MODEL_LONG_CLICK:0/1"));
    }

    @Test
    public void testDump_mopReachReflectsPredicate() throws Exception {
        State state = createState("com.example.MopActivity");
        Map<String, Integer> d = data("//x|MODEL_CLICK", 1);
        assertTrue(tracker.formatCoverageLine(state, d, s -> true).endsWith("mopReach=1"));
        assertTrue(tracker.formatCoverageLine(state, d, s -> false).endsWith("mopReach=0"));
        // Null predicate (e.g. _mopData == null at the call site) => 0.
        assertTrue(tracker.formatCoverageLine(state, d, null).endsWith("mopReach=0"));
    }

    @Test
    public void testDump_oneLinePerTrackedState() throws Exception {
        for (int i = 0; i < 5; i++) {
            Name w = fakeName("//B[@i=\"" + i + "\"]");
            State s = createState("com.example.S" + i, w);
            tracker.registerScreenElements(s, Arrays.asList(
                    createTargetedAction(s, w, ActionType.MODEL_CLICK)));
        }
        String out = captureDump(s -> false);
        int stateLines = 0;
        int activityLines = 0;
        for (String l : out.split("\n")) {
            // "[APE-RV] UICOV state=" is the per-state token; "[APE-RV] UICOV-ACT" is the new
            // per-activity token. A bare contains("[APE-RV] UICOV") would match both (prefix).
            if (l.contains("[APE-RV] UICOV state=")) {
                stateLines++;
            }
            if (l.contains("[APE-RV] UICOV-ACT")) {
                activityLines++;
            }
        }
        assertEquals("one UICOV line per tracked state", 5, stateLines);
        // 5 distinct activities (com.example.S0..S4), each with one live fragment.
        assertEquals("one UICOV-ACT line per activity", 5, activityLines);
    }

    @Test
    public void testDump_isReadOnly_INV_COV_07() throws Exception {
        Name w1 = fakeName("//A");
        Name w2 = fakeName("//B");
        State s = createState("com.example.ReadOnly", w1, w2);
        ModelAction a1 = createTargetedAction(s, w1, ActionType.MODEL_CLICK);
        ModelAction a2 = createTargetedAction(s, w2, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(s, Arrays.asList(a1, a2));
        tracker.recordInteraction(s, a1);

        int elementsBefore = tracker.getTotalElements();
        int interactionsBefore = tracker.getTotalInteractions();
        float gapBefore = tracker.getCoverageGap(s);

        captureDump(state -> true);

        assertEquals("dump must not change totalElements", elementsBefore, tracker.getTotalElements());
        assertEquals("dump must not change totalInteractions", interactionsBefore, tracker.getTotalInteractions());
        assertEquals("dump must not change coverage gap", gapBefore, tracker.getCoverageGap(s), 0.0f);
    }

    // =======================================================================
    // Per-Activity coverage dump (uicov-activity-rollup): [APE-RV] UICOV-ACT lines
    // =======================================================================

    /** Extracts the single UICOV-ACT line for the given activity from a dump, or fails. */
    private static String activityLine(String dump, String activity) {
        String needle = "[APE-RV] UICOV-ACT activity=" + activity + " ";
        String found = null;
        for (String l : dump.split("\n")) {
            if (l.contains(needle)) {
                assertNull("duplicate UICOV-ACT line for " + activity, found);
                found = l;
            }
        }
        assertNotNull("no UICOV-ACT line for " + activity + " in:\n" + dump, found);
        return found;
    }

    /** Seeds the private activityRollup so a rollup-sourced activity can be dumped (simulates eviction). */
    @SuppressWarnings("unchecked")
    private void seedRollup(String activity, Map<String, Integer> widgets) throws Exception {
        Field f = UICoverageTracker.class.getDeclaredField("activityRollup");
        f.setAccessible(true);
        Map<String, Map<String, Integer>> rollup = (Map<String, Map<String, Integer>>) f.get(tracker);
        rollup.put(activity, new LinkedHashMap<>(widgets));
    }

    /** INV-COV-08: 3 fragments with the same 10 widget keys collapse into one line, discovered=10. */
    @Test
    public void activityLineCollapsesFragments() throws Exception {
        Name[] w = new Name[10];
        for (int i = 0; i < 10; i++) {
            w[i] = fakeName("//Button[@i=\"" + i + "\"]");
        }
        State frag2Interacted = null;
        ModelAction interactAction = null;
        for (int f = 0; f < 3; f++) {
            State frag = createState("com.example.MainActivity", fakeName("frag" + f));
            List<ModelAction> actions = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                actions.add(createTargetedAction(frag, w[i], ActionType.MODEL_CLICK));
            }
            tracker.registerScreenElements(frag, actions);
            if (f == 1) {
                frag2Interacted = frag;
                interactAction = actions.get(0); // widget X = w[0]
            }
        }
        tracker.recordInteraction(frag2Interacted, interactAction);

        String line = activityLine(captureDump(s -> false), "com.example.MainActivity");
        assertTrue(line, line.contains("discovered=10"));   // union, not 30
        assertTrue(line, line.contains("interacted=1"));     // X counted once
        assertTrue(line, line.contains("gap=0.9"));
        assertTrue(line, line.contains("liveStates=3"));
    }

    /** Evicted (rollup) fragment aggregates with a live fragment; liveStates counts only the live one. */
    @Test
    public void rollupIncludedInActivityLine() throws Exception {
        // Rollup holds a covered widget A from an evicted fragment.
        Map<String, Integer> rolled = new LinkedHashMap<>();
        rolled.put("//A|MODEL_CLICK", 2);
        seedRollup("com.example.SettingsActivity", rolled);

        // Live fragment holds an uncovered widget B.
        Name b = fakeName("//B");
        State live = createState("com.example.SettingsActivity", b);
        tracker.registerScreenElements(live, Arrays.asList(
                createTargetedAction(live, b, ActionType.MODEL_CLICK)));

        String line = activityLine(captureDump(s -> false), "com.example.SettingsActivity");
        assertTrue(line, line.contains("discovered=2"));   // A (rollup) ∪ B (live)
        assertTrue(line, line.contains("interacted=1"));    // A covered, B not
        assertTrue(line, line.contains("liveStates=1"));    // only B is live
    }

    /** Activity present only in the rollup (all fragments evicted) reports liveStates=0. */
    @Test
    public void rollupOnlyActivityLine() throws Exception {
        Map<String, Integer> rolled = new LinkedHashMap<>();
        rolled.put("//X|MODEL_CLICK", 1);
        rolled.put("//Y|MODEL_CLICK", 0);
        seedRollup("com.example.LoginActivity", rolled);

        String line = activityLine(captureDump(s -> false), "com.example.LoginActivity");
        assertTrue(line, line.contains("discovered=2"));
        assertTrue(line, line.contains("interacted=1"));
        assertTrue(line, line.contains("liveStates=0"));
    }

    /** UICOV-ACT lines are emitted in lexicographic activity-name order. */
    @Test
    public void activityLinesDeterministicOrder() throws Exception {
        String[] activities = {"com.example.Zeta", "com.example.Alpha", "com.example.Mid"};
        for (String a : activities) {
            Name w = fakeName("//w-" + a);
            State s = createState(a, w);
            tracker.registerScreenElements(s, Arrays.asList(
                    createTargetedAction(s, w, ActionType.MODEL_CLICK)));
        }
        String dump = captureDump(s -> false);
        int iAlpha = dump.indexOf("UICOV-ACT activity=com.example.Alpha");
        int iMid = dump.indexOf("UICOV-ACT activity=com.example.Mid");
        int iZeta = dump.indexOf("UICOV-ACT activity=com.example.Zeta");
        assertTrue("Alpha before Mid", iAlpha >= 0 && iAlpha < iMid);
        assertTrue("Mid before Zeta", iMid < iZeta);
    }

    /** INV-COV-07 parity: the activity dump mutates neither stateData nor activityRollup. */
    @Test
    public void activityDumpIsReadOnly() throws Exception {
        Name b = fakeName("//B");
        State live = createState("com.example.RO", b);
        tracker.registerScreenElements(live, Arrays.asList(
                createTargetedAction(live, b, ActionType.MODEL_CLICK)));
        Map<String, Integer> rolled = new LinkedHashMap<>();
        rolled.put("//A|MODEL_CLICK", 1);
        seedRollup("com.example.RO", rolled);

        Field sdF = UICoverageTracker.class.getDeclaredField("stateData");
        sdF.setAccessible(true);
        Field arF = UICoverageTracker.class.getDeclaredField("activityRollup");
        arF.setAccessible(true);
        int stateBefore = ((Map<?, ?>) sdF.get(tracker)).size();
        int rollupBefore = ((Map<?, ?>) arF.get(tracker)).size();

        captureDump(s -> true);

        assertEquals("dump must not change stateData size", stateBefore, ((Map<?, ?>) sdF.get(tracker)).size());
        assertEquals("dump must not change activityRollup size", rollupBefore, ((Map<?, ?>) arF.get(tracker)).size());
    }

    /** formatActivityLine is pure and host-testable, mirroring formatCoverageLine. */
    @Test
    public void formatActivityLineIsPure() {
        Map<String, Integer> merged = data(
                "//a|MODEL_CLICK", 1,
                "//b|MODEL_CLICK", 0,
                "//c|MODEL_LONG_CLICK", 0);
        String line = tracker.formatActivityLine("com.example.Fmt", merged, 2);
        assertTrue(line, line.startsWith("[APE-RV] UICOV-ACT activity=com.example.Fmt "));
        assertTrue(line, line.contains("discovered=3 interacted=1 gap=0.7"));
        assertTrue(line, line.contains("byType=MODEL_CLICK:1/2,MODEL_LONG_CLICK:0/1"));
        assertTrue(line, line.endsWith("liveStates=2"));
    }

    // =======================================================================
    // Activity-scoped interaction membership (coverage-boost-activity-scope)
    // =======================================================================

    /** INV-COV-09: interacting X in fragment S1 marks X interacted for the whole Activity. */
    @Test
    public void activityInteractionCoversFragments() throws Exception {
        Name w = fakeName("//Widget[@id=\"x\"]");
        State s1 = createState("com.example.MainActivity", fakeName("s1"));
        State s2 = createState("com.example.MainActivity", fakeName("s2"));
        ModelAction a1 = createTargetedAction(s1, w, ActionType.MODEL_CLICK);
        ModelAction a2 = createTargetedAction(s2, w, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(s1, Arrays.asList(a1));
        tracker.registerScreenElements(s2, Arrays.asList(a2));

        tracker.recordInteraction(s1, a1);

        String widgetId = UICoverageTracker.widgetId(a1);
        assertTrue(tracker.hasActivityInteraction("com.example.MainActivity", widgetId));
        // ...but the sibling fragment's own per-state count is still 0.
        assertEquals(0, tracker.getInteractionCount(s2, widgetId));
    }

    /** Unknown widget → false. */
    @Test
    public void hasActivityInteractionFalseForUnknown() throws Exception {
        Name w = fakeName("//W");
        State s = createState("com.example.MainActivity", w);
        tracker.registerScreenElements(s, Arrays.asList(
                createTargetedAction(s, w, ActionType.MODEL_CLICK)));
        assertFalse(tracker.hasActivityInteraction("com.example.MainActivity", "//Never|MODEL_CLICK"));
        assertFalse(tracker.hasActivityInteraction(null, "//W|MODEL_CLICK"));
    }

    /** INV-COV-09: membership survives LRU eviction of the fragment it was recorded in. */
    @Test
    public void activityInteractionSurvivesEviction() throws Exception {
        Name w = fakeName("//Kept");
        State s1 = createState("com.example.EvictActivity", w);
        ModelAction a1 = createTargetedAction(s1, w, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(s1, Arrays.asList(a1));
        tracker.recordInteraction(s1, a1);
        String widgetId = UICoverageTracker.widgetId(a1);
        assertTrue(tracker.hasActivityInteraction("com.example.EvictActivity", widgetId));

        // Flood > coverageMaxStates distinct states so s1 is LRU-evicted (folded into rollup).
        int fillers = Config.coverageMaxStates + 1;
        for (int i = 0; i < fillers; i++) {
            Name fw = fakeName("//Filler[@i=\"" + i + "\"]");
            State fs = createState("com.example.Filler" + i, fw);
            tracker.registerScreenElements(fs, Arrays.asList(
                    createTargetedAction(fs, fw, ActionType.MODEL_CLICK)));
        }
        // s1's per-state entry is gone, but activity membership is cumulative and never evicts.
        assertEquals("s1 per-state entry evicted", 0, tracker.getInteractionCount(s1, widgetId));
        assertTrue("activity membership survives eviction",
                tracker.hasActivityInteraction("com.example.EvictActivity", widgetId));
    }

    /** The exact differential the boost call-site consumes: interacted in S1, locally 0 in S2. */
    @Test
    public void siblingFragmentInteractedButLocallyZero() throws Exception {
        Name w = fakeName("//Shared");
        State s1 = createState("com.example.Sib", fakeName("f1"));
        State s2 = createState("com.example.Sib", fakeName("f2"));
        ModelAction a1 = createTargetedAction(s1, w, ActionType.MODEL_CLICK);
        ModelAction a2 = createTargetedAction(s2, w, ActionType.MODEL_CLICK);
        tracker.registerScreenElements(s1, Arrays.asList(a1));
        tracker.registerScreenElements(s2, Arrays.asList(a2));
        tracker.recordInteraction(s1, a1);

        String widgetId = UICoverageTracker.widgetId(a2);
        assertTrue("interacted somewhere in the activity",
                tracker.hasActivityInteraction("com.example.Sib", widgetId));
        assertEquals("but locally untested in the sibling fragment",
                0, tracker.getInteractionCount(s2, widgetId));
    }

    /** First-touch on an unregistered state still records activity membership (both branches). */
    @Test
    public void activityInteractionCapturedOnUnregisteredState() throws Exception {
        Name w = fakeName("//FirstTouch");
        State s = createState("com.example.Unreg", w);
        ModelAction a = createTargetedAction(s, w, ActionType.MODEL_CLICK);
        // No registerScreenElements → recordInteraction hits the unregistered-state branch.
        tracker.recordInteraction(s, a);
        assertTrue(tracker.hasActivityInteraction("com.example.Unreg", UICoverageTracker.widgetId(a)));
    }

    /** Runs the dump while capturing stdout (the [APE] Logger writes to System.out). */
    private String captureDump(Predicate<State> mopReach) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer));
            tracker.dump(mopReach);
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }
}
