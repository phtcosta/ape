package com.android.commands.monkey.ape.agent.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Assembly: which stages a plan produces, and what each of them was wired to.
 *
 * <p>{@link DecisionPipelineTest} pins the loop with stubs; this pins the step before it. The two
 * failures it exists to catch are different in kind and neither is visible from a golden. A plan
 * whose feature is off but whose stage is built anyway still decides every step identically until the
 * step where it does not — INV-DP-03 is about the absence being structural, so it has to be asserted
 * on the roster. And a stage bound to the wrong producer is a silent swap: the pipeline is the right
 * shape and the action comes from the wrong place, which is why the wiring is exercised through
 * {@code decide} rather than inspected.
 *
 * <p>A fake stands in for the agent, which is the reason {@code fromSpec} names
 * {@link StageCollaborators} instead of the agent class: these properties belong to the assembly and
 * would otherwise only be assertable on a device.
 */
public class DecisionPipelineFromSpecTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** The agent, reduced to what assembly binds, with each answer scripted. */
    private static class FakeCollaborators implements StageCollaborators {

        private final ModelAction trivial;
        private final StageResult remainder;
        private int inlineLadderCalls;

        FakeCollaborators(ModelAction trivial, StageResult remainder) {
            this.trivial = trivial;
            this.remainder = remainder;
        }

        @Override
        public ModelAction selectNewActionForTrivialActivity() {
            return trivial;
        }

        @Override
        public StageResult decideInlineLadder() {
            inlineLadderCalls++;
            return remainder;
        }
    }

    /**
     * A context carrying the state and the tracker the budget gate reads. The discipline of what a
     * stage may read is asserted in {@link BudgetStageTest}; here the context is scenery.
     */
    private static class FakeContext implements StepContext {

        private final State state;
        private final ActivityBudgetTracker tracker;

        FakeContext(State state, ActivityBudgetTracker tracker) {
            this.state = state;
            this.tracker = tracker;
        }

        @Override public State newState() { return state; }
        @Override public ActivityBudgetTracker budgetTracker() { return tracker; }
        @Override public GUITree newGUITree() { return null; }
        @Override public int actionBufferSize() { return 0; }
        @Override public int graphStableCounter() { return 0; }
        @Override public int timestamp() { return 1; }
        @Override public Random random() { return null; }
        @Override public MopData mopData() { return null; }
        @Override public Graph graph() { return null; }
        @Override public List<ApePromptBuilder.ActionHistoryEntry> actionHistory() { return null; }
        @Override public void resetGraphStableCounter() { }
    }

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

    private static ActivityBudgetTracker exhausted() {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(0, 0);
        tracker.registerActivity(ACTIVITY, 0);
        return tracker;
    }

    private static ModelAction action() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    /** Assembles while capturing the echo, which is emitted by construction. */
    private static String echoOf(RunSpec spec, StageCollaborators collaborators,
            List<DecisionPipeline> out) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true));
            out.add(DecisionPipeline.fromSpec(spec, collaborators));
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    private static DecisionPipeline assemble(RunSpec spec, StageCollaborators collaborators) {
        return DecisionPipeline.fromSpec(spec, collaborators);
    }

    @Test
    public void aBareplanAssemblesTheBudgetGateAndTheTerminalStage() {
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec(),
                new FakeCollaborators(null, StageResult.select(action(), "SATA")));

        assertEquals(Arrays.asList("Budget", "InlineLadder"), pipeline.stageNames());
    }

    @Test
    public void aPlanWithTheBudgetOffAssemblesNoBudgetStage() {
        DecisionPipeline pipeline = assemble(
                TestRunSpecs.spec("ape.activityBudgetEnabled", "false"),
                new FakeCollaborators(null, StageResult.select(action(), "SATA")));

        // Structural absence, not a stage that answers "disabled" (INV-DP-03).
        assertEquals(Arrays.asList("InlineLadder"), pipeline.stageNames());
    }

    @Test
    public void assemblyEchoesTheRosterItBuilt() {
        List<DecisionPipeline> built = new java.util.ArrayList<>();
        String echoed = echoOf(TestRunSpecs.spec(),
                new FakeCollaborators(null, StageResult.select(action(), "SATA")), built);

        assertTrue("got: " + echoed, echoed.contains("[APE-ARCH] stages=[Budget, InlineLadder]"));
    }

    @Test
    public void theTerminalStageIsAlwaysAssembledAndDecidesTheStep() throws Exception {
        ModelAction fromTheChain = action();
        FakeCollaborators agent =
                new FakeCollaborators(null, StageResult.select(fromTheChain, "SATA"));
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec(), agent);

        Action decided = pipeline.decide(new FakeContext(stateOn(ACTIVITY), exhausted()));

        assertSame(fromTheChain, decided);
        assertEquals(1, agent.inlineLadderCalls);
    }

    @Test
    public void theBudgetStageIsWiredToTheTrivialActivityProducer() throws Exception {
        ModelAction trivial = action();
        FakeCollaborators agent =
                new FakeCollaborators(trivial, StageResult.select(action(), "SATA"));
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec(), agent);

        Action decided = pipeline.decide(new FakeContext(stateOn(ACTIVITY), exhausted()));

        assertSame("assembly bound the budget gate to the wrong producer", trivial, decided);
        assertEquals("the budget gate preempts the rest of the ladder (INV-DP-02)",
                0, agent.inlineLadderCalls);
    }
}
