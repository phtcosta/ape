package com.android.commands.monkey.ape.model;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.agent.sata.LeastVisitedTie;
import com.android.commands.monkey.ape.agent.sata.ScorerSumLeastVisitedTie;
import com.android.commands.monkey.ape.naming.Name;

/**
 * Unit tests for {@link State#greedyPickLeastVisited(ActionFilter, LeastVisitedTie)}.
 *
 * <p>Fewer visits always win; a visit-count tie goes to X1, rule R2: the larger scorer sum, else
 * array order. Priority is never read (action-selection INV-SEL-01/02, design D-B4).
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
        return createAction(state, priority, visitedCount, 0);
    }

    /** As above, with a coverage scorer term: the scorer sum R2 compares. */
    private static ModelAction createAction(State state, int priority, int visitedCount,
            int coverageBoost) throws Exception {
        ModelAction action = new ModelAction(state, ActionType.MODEL_CLICK);
        action.setPriority(priority);
        action.setCoverageBoost(coverageBoost);

        // visitedCount is a protected field in GraphElement
        Field vcField = GraphElement.class.getDeclaredField("visitedCount");
        vcField.setAccessible(true);
        vcField.setInt(action, visitedCount);

        return action;
    }

    private static final ActionFilter ALL = ActionFilter.ALL;

    private static final LeastVisitedTie R2 = new ScorerSumLeastVisitedTie();

    private static State stateWith(ModelAction... actions) throws Exception {
        State state = createState(new ModelAction[0]);
        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, actions);
        return state;
    }

    /** A tie strategy that fails the test if it is consulted. */
    private static final LeastVisitedTie NEVER = tied -> {
        fail("X1 consulted without a tie: " + tied);
        return null;
    };

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /** Scenario: single least-visited action — X1 is not consulted (INV-SEL-02). */
    @Test
    public void testSingleLeastVisited() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 32, 0);
        ModelAction a1 = createAction(holder, 32, 3, 500);
        ModelAction a2 = createAction(holder, 32, 5);
        State state = stateWith(a0, a1, a2);

        assertSame(a0, state.greedyPickLeastVisited(ALL, NEVER));
    }

    /** Scenario: a tie broken by the scorer sum, not by the priority. */
    @Test
    public void testTieBrokenByScorerSum() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 32, 2);
        ModelAction a1 = createAction(holder, 532, 2, 500);
        ModelAction a2 = createAction(holder, 52, 5);
        State state = stateWith(a0, a1, a2);

        assertSame(a1, state.greedyPickLeastVisited(ALL, R2));
    }

    /** Scenario: all actions on the same visit count — the largest scorer sum wins. */
    @Test
    public void testAllSameVisitedCount_largestScorerSumWins() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction[] actions = new ModelAction[10];
        int[] sums = {0, 0, 200, 0, 500, 0, 0, 0, 0, 0};
        for (int i = 0; i < actions.length; i++) {
            actions[i] = createAction(holder, 32 + sums[i], 0, sums[i]);
        }
        State state = stateWith(actions);

        assertSame(actions[4], state.greedyPickLeastVisited(ALL, R2));
    }

    /** Scenario: tie with equal scorer sums — the first of the array wins. */
    @Test
    public void testTieWithEqualSums_firstWins() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 252, 1, 200);
        ModelAction a1 = createAction(holder, 252, 1, 200);
        ModelAction a2 = createAction(holder, 32, 3);
        State state = stateWith(a0, a1, a2);

        assertSame(a0, state.greedyPickLeastVisited(ALL, R2));
    }

    /**
     * Scenario: the base cell (no scorer) — array order, NOT the priority-532 action. This is the
     * case the retired addon G got wrong, because it compared the whole priority.
     */
    @Test
    public void testNoScorer_arrayOrderNotPriority() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 32, 2);
        ModelAction a1 = createAction(holder, 532, 2);
        ModelAction a2 = createAction(holder, 52, 5);
        State state = stateWith(a0, a1, a2);

        assertSame(a0, state.greedyPickLeastVisited(ALL, R2));
    }

    /**
     * Scenario: the same action set picks differently with and without a scorer term — the
     * permanent paired test of the conversion point (action-selection "Priority Tiebreaker").
     */
    @Test
    public void testScorerTermDecidesTheTie() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction first = createAction(holder, 32, 2);
        ModelAction scored = createAction(holder, 132, 2, 100);
        ModelAction visited = createAction(holder, 52, 5);
        State state = stateWith(first, scored, visited);

        ModelAction withScorer = state.greedyPickLeastVisited(ALL, R2);
        scored.setCoverageBoost(0);
        ModelAction withoutScorer = state.greedyPickLeastVisited(ALL, R2);

        assertSame("scorer sum 100 wins the tie", scored, withScorer);
        assertSame("every sum 0: the first of the array (upstream APE)", first, withoutScorer);
        assertNotSame(withScorer, withoutScorer);
    }

    /** INV-SEL-01: a lower visit count always wins, whatever the scorer sum or priority. */
    @Test
    public void testINV_SEL_01_visitedCountAlwaysPrimary() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 1, 0);
        ModelAction a1 = createAction(holder, 99999, 1, 99999);
        State state = stateWith(a0, a1);

        assertSame(a0, state.greedyPickLeastVisited(ALL, R2));
    }

    /** INV-SEL-02: distinct visit counts never consult X1. */
    @Test
    public void testINV_SEL_02_distinctVisitedCounts() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 10, 1);
        ModelAction a1 = createAction(holder, 500, 2, 500);
        ModelAction a2 = createAction(holder, 1000, 3);
        State state = stateWith(a0, a1, a2);

        assertSame(a0, state.greedyPickLeastVisited(ALL, NEVER));
    }

    /** The tied candidates come back in array order, the X1 strategy's input. */
    @Test
    public void testLeastVisitedListsTheTieInArrayOrder() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 32, 3);
        ModelAction a1 = createAction(holder, 32, 1);
        ModelAction a2 = createAction(holder, 32, 2);
        ModelAction a3 = createAction(holder, 32, 1);
        State state = stateWith(a0, a1, a2, a3);

        assertEquals(java.util.Arrays.asList(a1, a3), state.leastVisited(ALL, null));
    }

    @Test
    public void testNoMatchingActions_returnsNull() throws Exception {
        State state = createState(new ModelAction[0]);

        assertNull(state.greedyPickLeastVisited(ALL, NEVER));
    }

    /** The filter is respected: a filtered-out action is ignored even with fewer visits. */
    @Test
    public void testFilterExcludesActions() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction a0 = createAction(holder, 100, 0);
        ModelAction a1 = createAction(holder, 50, 5);
        State state = stateWith(a0, a1);

        ActionFilter excludeFirst = action -> action != a0;

        assertSame(a1, state.greedyPickLeastVisited(excludeFirst, R2));
    }

    /**
     * Form-submit guard (INV-FORM-06): the excluded submit candidate is skipped even when it is the
     * least-visited action; a null exclusion leaves the pick unchanged.
     */
    @Test
    public void testExcludedActionSkippedEvenIfLeastVisited() throws Exception {
        State holder = createState(new ModelAction[0]);
        ModelAction submit = createAction(holder, 100, 0);
        ModelAction field = createAction(holder, 32, 3);
        State state = stateWith(submit, field);

        assertSame("excluded submit skipped despite lowest visitedCount",
                field, state.greedyPickLeastVisited(ALL, submit, R2));
        assertSame("null exclusion → unchanged least-visited behavior",
                submit, state.greedyPickLeastVisited(ALL, null, R2));
    }
}
