package com.android.commands.monkey.ape.model;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.naming.Name;

/**
 * Unit tests for {@link State#greedyPickLeastVisited(ActionFilter, boolean)}.
 *
 * <p>Verifies that priority is used as a tiebreaker when multiple actions
 * share the same minimum visitedCount (INV-SEL-01..03).
 *
 * <p>Uses sun.misc.Unsafe to allocate State/StateKey instances without
 * calling constructors that depend on Android APIs.
 */
public class StateTest {

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return (T) allocate.invoke(unsafe, clazz);
    }

    /**
     * Creates a State with the given actions array, bypassing constructor.
     */
    private static State createState(ModelAction[] actions) throws Exception {
        StateKey sk = allocateInstance(StateKey.class);
        Field activityField = StateKey.class.getDeclaredField("activity");
        activityField.setAccessible(true);
        activityField.set(sk, "test.Activity");
        Field widgetsField = StateKey.class.getDeclaredField("widgets");
        widgetsField.setAccessible(true);
        widgetsField.set(sk, new Name[0]);

        State state = allocateInstance(State.class);
        Field stateKeyField = State.class.getDeclaredField("stateKey");
        stateKeyField.setAccessible(true);
        stateKeyField.set(state, sk);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, actions);

        return state;
    }

    /**
     * Creates a ModelAction with given priority and visitedCount via reflection.
     */
    private static ModelAction createAction(State state, int priority, int visitedCount) throws Exception {
        ModelAction action = new ModelAction(state, ActionType.MODEL_CLICK);
        action.setPriority(priority);

        // visitedCount is a protected field in GraphElement
        Field vcField = GraphElement.class.getDeclaredField("visitedCount");
        vcField.setAccessible(true);
        vcField.setInt(action, visitedCount);

        return action;
    }

    private static final ActionFilter ALL = ActionFilter.ALL;

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Scenario: Single least-visited action (unchanged behavior).
     * WHEN actions have visitedCounts [0, 3, 5]
     * THEN the action with visitedCount=0 SHALL be selected.
     */
    @Test
    public void testSingleLeastVisited() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction a0 = createAction(state, 32, 0);
        ModelAction a1 = createAction(state, 32, 3);
        ModelAction a2 = createAction(state, 32, 5);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1, a2});

        assertSame(a0, state.greedyPickLeastVisited(ALL, true));
    }

    /**
     * Scenario: Tie broken by priority.
     * WHEN actions have visitedCounts [2, 2, 5] and priorities [32, 532, 52]
     * THEN the action with visitedCount=2 and priority=532 SHALL be selected.
     */
    @Test
    public void testTieBrokenByPriority() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction a0 = createAction(state, 32, 2);
        ModelAction a1 = createAction(state, 532, 2);
        ModelAction a2 = createAction(state, 52, 5);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1, a2});

        assertSame(a1, state.greedyPickLeastVisited(ALL, true));
    }

    /**
     * Scenario: All actions same visitedCount.
     * WHEN all actions have visitedCount=0 and priorities [32, 32, 232, 32, 532, 32]
     * THEN the action with priority=532 SHALL be selected.
     */
    @Test
    public void testAllSameVisitedCount_highestPriorityWins() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction a0 = createAction(state, 32, 0);
        ModelAction a1 = createAction(state, 32, 0);
        ModelAction a2 = createAction(state, 232, 0);
        ModelAction a3 = createAction(state, 32, 0);
        ModelAction a4 = createAction(state, 532, 0);
        ModelAction a5 = createAction(state, 32, 0);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1, a2, a3, a4, a5});

        assertSame(a4, state.greedyPickLeastVisited(ALL, true));
    }

    /**
     * Scenario: Tie with equal priorities.
     * WHEN actions have visitedCounts [1, 1, 3] and priorities [52, 52, 32]
     * THEN either of the two tied actions MAY be selected (first encountered wins).
     */
    @Test
    public void testTieWithEqualPriorities_firstEncounteredWins() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction a0 = createAction(state, 52, 1);
        ModelAction a1 = createAction(state, 52, 1);
        ModelAction a2 = createAction(state, 32, 3);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1, a2});

        ModelAction result = state.greedyPickLeastVisited(ALL, true);
        // First encountered with min visitedCount and max priority wins
        assertSame(a0, result);
    }

    /**
     * INV-SEL-01: Lower visitedCount always wins regardless of priority.
     * Priority NEVER overrides visitedCount.
     */
    @Test
    public void testINV_SEL_01_visitedCountAlwaysPrimary() throws Exception {
        State state = createState(new ModelAction[0]);
        // a0 has low visitedCount but low priority
        ModelAction a0 = createAction(state, 1, 0);
        // a1 has high visitedCount but very high priority
        ModelAction a1 = createAction(state, 99999, 1);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1});

        assertSame(a0, state.greedyPickLeastVisited(ALL, true));
    }

    /**
     * INV-SEL-02: Distinct visitedCounts = same behavior as before.
     * When all actions have unique visitedCounts, priority is irrelevant.
     */
    @Test
    public void testINV_SEL_02_distinctVisitedCounts() throws Exception {
        State state = createState(new ModelAction[0]);
        // Lowest visitedCount has lowest priority — still wins
        ModelAction a0 = createAction(state, 10, 1);
        ModelAction a1 = createAction(state, 500, 2);
        ModelAction a2 = createAction(state, 1000, 3);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1, a2});

        assertSame(a0, state.greedyPickLeastVisited(ALL, true));
    }

    /**
     * Verify the method returns null when no actions pass the filter.
     */
    @Test
    public void testNoMatchingActions_returnsNull() throws Exception {
        State state = createState(new ModelAction[0]);

        assertNull(state.greedyPickLeastVisited(ALL, true));
    }

    /**
     * Verify filter is respected: filtered-out actions are ignored even if
     * they have lower visitedCount.
     */
    @Test
    public void testFilterExcludesActions() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction a0 = createAction(state, 100, 0);  // would win, but filtered out
        ModelAction a1 = createAction(state, 50, 5);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{a0, a1});

        // Filter that excludes the first action
        ActionFilter excludeFirst = action -> action != a0;

        assertSame(a1, state.greedyPickLeastVisited(excludeFirst, true));
    }

    /**
     * form-completion #1 D-7.2 guard (extended INV-FORM-06): the excluded submit candidate is
     * skipped even when it is the least-visited action, so the next-least-visited non-excluded
     * action wins; a null exclusion leaves behavior unchanged.
     */
    @Test
    public void testExcludedActionSkippedEvenIfLeastVisited() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction submit = createAction(state, 100, 0);   // least visited — would win unguarded
        ModelAction field = createAction(state, 32, 3);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{submit, field});

        assertSame("excluded submit skipped despite lowest visitedCount",
                field, state.greedyPickLeastVisited(ALL, submit, true));
        assertSame("null exclusion → unchanged least-visited behavior",
                submit, state.greedyPickLeastVisited(ALL, null, true));
    }

    /**
     * The tiebreak argument decides the pick — the only thing that can catch it being wired wrong.
     *
     * <p>One fixed action set, one fixed set of visit counts, differing priorities, passed to
     * {@code greedyPickLeastVisited} twice: once with {@code true}, once with {@code false}. The
     * two calls SHALL pick different actions. This is the seam where every priority boost — MOP,
     * WTG, coverage — becomes a chosen action, and the greedy path takes 85-98% of decisions, so an
     * argument wired wrong here degrades MOP guidance while every stage still reports the same
     * structure: same roster, same rungs, same lines, no golden moves. Nothing else in the suite
     * would go red.
     *
     * <p>The priorities are stated here and appear nowhere in production, so the result can only
     * carry them by having travelled through the argument under test.
     */
    @Test
    public void testTiebreakArgumentDecidesThePick() throws Exception {
        State state = createState(new ModelAction[0]);
        // Tied on the lowest visit count; boosted is the one a priority boost would steer to.
        ModelAction first = createAction(state, 37, 2);
        ModelAction boosted = createAction(state, 537, 2);
        ModelAction visited = createAction(state, 57, 5);

        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, new ModelAction[]{first, boosted, visited});

        ModelAction withTiebreak = state.greedyPickLeastVisited(ALL, true);
        ModelAction withoutTiebreak = state.greedyPickLeastVisited(ALL, false);

        assertSame("tiebreak on: the boost reaches the greedy path", boosted, withTiebreak);
        assertSame("tiebreak off: the tie falls back to array order (upstream APE)",
                first, withoutTiebreak);
        assertNotSame("the argument is load-bearing, not decorative",
                withTiebreak, withoutTiebreak);
    }

    /**
     * The same contrast through {@code MopCounterfactual}, which mirrors the tie rule. A
     * counterfactual that disagreed with the branch it stands in for would misreport {@code
     * cf_changed} on every {@code ape_pure} run, and no factual assertion would notice.
     */
    @Test
    public void testCounterfactualHonoursTheSameTiebreakArgument() throws Exception {
        State state = createState(new ModelAction[0]);
        ModelAction first = createAction(state, 37, 2);
        ModelAction boosted = createAction(state, 537, 2);
        java.util.List<ModelAction> candidates = java.util.Arrays.asList(first, boosted);

        assertSame("tiebreak on: higher priority wins the tie", boosted,
                MopCounterfactual.leastVisitedWithoutMopWeights(candidates, true));
        assertSame("tiebreak off: candidate order wins the tie", first,
                MopCounterfactual.leastVisitedWithoutMopWeights(candidates, false));
    }

    // -----------------------------------------------------------------------
    // The leastVisitedPriorityTiebreak seam. The flag is a parameter of
    // greedyPickLeastVisited, so both branches are reachable through the real
    // entry point; testTiebreakArgumentDecidesThePick below is what proves the
    // argument is load-bearing. The tests here pin the pure seam it delegates to.
    // -----------------------------------------------------------------------

    @Test
    public void testBeatsLeastVisited_fewerVisitsAlwaysWins() {
        // vc below the incumbent minValue wins regardless of tiebreak flag or priority.
        assertTrue(State.beatsLeastVisited(2, 1, 5, 999, true));
        assertTrue(State.beatsLeastVisited(2, 1, 5, 999, false));
    }

    @Test
    public void testBeatsLeastVisited_tieBrokenByPriorityOnlyWhenTiebreakOn() {
        // Equal vc, strictly higher priority: RV tiebreak takes it, upstream keeps the incumbent.
        assertTrue("tiebreak on: higher priority wins the tie",
                State.beatsLeastVisited(3, 500, 3, 100, true));
        assertFalse("tiebreak off: incumbent keeps the slot (upstream array-order)",
                State.beatsLeastVisited(3, 500, 3, 100, false));
    }

    @Test
    public void testBeatsLeastVisited_higherVisitsNeverWins() {
        assertFalse(State.beatsLeastVisited(6, 999, 5, 0, true));
        assertFalse(State.beatsLeastVisited(6, 999, 5, 0, false));
    }

    @Test
    public void testBeatsLeastVisited_equalPriorityTieKeepsIncumbent() {
        // Equal vc and equal priority: incumbent keeps the slot under both settings (strict >).
        assertFalse(State.beatsLeastVisited(3, 100, 3, 100, true));
        assertFalse(State.beatsLeastVisited(3, 100, 3, 100, false));
    }
}
