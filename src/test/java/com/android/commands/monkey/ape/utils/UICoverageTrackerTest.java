package com.android.commands.monkey.ape.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        assertEquals("//Button[@text=\"OK\"]", id);
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
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]"));
        assertEquals(0, tracker.getInteractionCount(state, "//EditText[@resource-id=\"email\"]"));
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
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"A\"]"));
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"B\"]"));
        assertEquals(0, tracker.getInteractionCount(state, "//Button[@text=\"C\"]"));
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

        assertEquals(1, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]"));
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

        assertEquals(3, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]"));
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

        assertEquals(5, tracker.getInteractionCount(state, "//Button[@text=\"OK\"]"));
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

        assertEquals(1, tracker.getInteractionCount(state, "//Button[@text=\"Z\"]"));
        // Only 1 widget registered implicitly
        assertEquals(0.0f, tracker.getCoverageGap(state), 0.001f);
    }
}
