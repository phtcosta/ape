package com.android.commands.monkey.ape.agent.pipeline;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

/**
 * The budget gate's three outcomes, and the one thing about it that is easy to get wrong.
 *
 * <p>The gate reads as advisory and only half of it is: with a trivial activity reachable it
 * <em>decides</em> the step, preempting every later stage. Both halves are asserted here, and so is
 * the order between them — the trivial-activity search walks the graph and logs, so a relocation that
 * consulted it before checking exhaustion would change what a non-exhausted step prints even though
 * it decided the same action.
 */
public class BudgetStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /**
     * A context that answers only what the stage under test is entitled to read; everything else
     * fails the test rather than returning a plausible default, which is how the stage's read surface
     * stays asserted rather than assumed.
     */
    private static class BudgetContext implements StepContext {

        private final State state;
        private final ActivityBudgetTracker tracker;

        BudgetContext(State state, ActivityBudgetTracker tracker) {
            this.state = state;
            this.tracker = tracker;
        }

        @Override
        public State newState() {
            return state;
        }

        @Override
        public ActivityBudgetTracker budgetTracker() {
            return tracker;
        }

        @Override
        public GUITree newGUITree() {
            throw new UnsupportedOperationException("the budget gate does not read the tree");
        }

        @Override
        public int actionBufferSize() {
            throw new UnsupportedOperationException("the budget gate does not read the buffer");
        }

        @Override
        public int graphStableCounter() {
            throw new UnsupportedOperationException("the budget gate does not read stagnation");
        }

        @Override
        public int timestamp() {
            throw new UnsupportedOperationException("the budget gate does not read the clock");
        }

        @Override
        public Random random() {
            throw new UnsupportedOperationException("the budget gate draws nothing (INV-DP-10)");
        }

        @Override
        public MopData mopData() {
            throw new UnsupportedOperationException("the budget gate does not read MOP data");
        }

        @Override
        public Graph graph() {
            throw new UnsupportedOperationException("the budget gate does not read the graph");
        }

        @Override
        public List<ApePromptBuilder.ActionHistoryEntry> actionHistory() {
            throw new UnsupportedOperationException("the budget gate does not read the history");
        }

        @Override
        public void resetGraphStableCounter() {
            throw new UnsupportedOperationException("only the stagnation stage writes that counter");
        }
    }

    /**
     * A {@code State} on {@code activity}. Allocated rather than constructed because
     * {@code StateKey}'s constructor needs an {@code android.content.ComponentName}, which the
     * surefire classpath does not carry — the same device the model tests use.
     */
    private static State stateOn(String activity) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        StateKey stateKey = (StateKey) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, StateKey.class);
        setField(stateKey, "activity", activity);
        State state = (State) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, State.class);
        setField(state, "stateKey", stateKey);
        return state;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A tracker on which {@code ACTIVITY} has spent everything it was allocated. */
    private static ActivityBudgetTracker exhausted() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(0, 0);
        tracker.registerActivity(ACTIVITY, 0);
        return tracker;
    }

    /** A tracker that has never heard of {@code ACTIVITY}, which is how a fresh run reports. */
    private static ActivityBudgetTracker fresh() {
        return new ActivityBudgetTracker(50, 5);
    }

    private static ModelAction navigation() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        BudgetStage stage = new BudgetStage(BudgetStageTest::navigation);
        assertEquals("the echo and the census must name the same stage",
                DecisionPipeline.Candidate.BUDGET.stageName(), stage.name());
    }

    @Test
    public void testExhaustedWithTrivialActivitySelectsIt() throws Exception {
        ModelAction trivial = navigation();
        BudgetStage stage = new BudgetStage(() -> trivial);

        StageResult result = stage.decide(new BudgetContext(stateOn(ACTIVITY), exhausted()));

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(trivial, result.action());
        assertEquals("Budget", result.decisionSource());
        assertEquals("the label is the provenance stamped on the action itself (INV-DP-04)",
                trivial.getDecisionSource().name(), result.decisionSource());
        assertEquals(ModelAction.DecisionSource.Budget, trivial.getDecisionSource());
        assertEquals(ModelAction.PickChannel.SATA_OTHER, trivial.getPickChannel());
    }

    @Test
    public void testExhaustedWithoutTrivialActivityPasses() throws Exception {
        BudgetStage stage = new BudgetStage(() -> null);

        StageResult result = stage.decide(new BudgetContext(stateOn(ACTIVITY), exhausted()));

        assertEquals("nowhere to navigate is where the gate is advisory: no BACK, no restart",
                StageResult.Kind.CONTINUE, result.kind());
    }

    @Test
    public void testUnexhaustedBudgetPassesWithoutSearching() throws Exception {
        boolean[] searched = {false};
        BudgetStage stage = new BudgetStage(() -> {
            searched[0] = true;
            return navigation();
        });

        StageResult result = stage.decide(new BudgetContext(stateOn(ACTIVITY), fresh()));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertFalse("the trivial-activity search runs only on an exhausted activity: it walks the"
                + " graph and logs what it finds, so consulting it early changes the trace",
                searched[0]);
    }
}
