package com.android.commands.monkey.ape.agent.pipeline;

import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.agent.StepRng;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.MopContext;

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
 *
 * <p>The rosters of the six cell presets are asserted in {@link PipelineParityTest}.
 */
public class DecisionPipelineFromSpecTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** The agent, reduced to what assembly binds, with each answer scripted. */
    private static class FakeCollaborators implements StageCollaborators {

        private final ModelAction trivial;
        private final ModelAction fromTheChain;

        /**
         * How many times the chain ran. Counted on the buffer rung because it is the chain's first
         * rung and the only one no other stage shares — the trivial-activity search is rung four
         * and also the budget gate's producer, so counting there could not tell the two apart.
         */
        private int chainCalls;

        FakeCollaborators(ModelAction trivial, ModelAction fromTheChain) {
            this.trivial = trivial;
            this.fromTheChain = fromTheChain;
        }

        @Override
        public ModelAction selectNewActionForTrivialActivity() {
            return trivial;
        }

        @Override
        public ModelAction selectNewActionFromBuffer() {
            chainCalls++;
            return null;
        }

        @Override
        public ModelAction selectNewActionBackToActivity() {
            return null;
        }

        @Override
        public ModelAction selectNewActionEarlyStageForward() {
            return null;
        }

        @Override
        public ModelAction selectNewActionEarlyStageBackward() {
            return null;
        }

        @Override
        public ModelAction selectNewActionEpsilonGreedyRandomly() {
            return null;
        }

        /** The chain's last rung, which is where these plans let it answer. */
        @Override
        public ModelAction handleNullAction() {
            return fromTheChain;
        }

        @Override
        public void logActionSelected(Action action, SataAgent.SataEventType type) {
        }

        /**
         * Null, and only ever read by assembly. These tests assert the roster and the wiring of the
         * non-LLM stages; where an LLM stage is assembled, no step reaches its engine, which is the
         * same reason the two below refuse rather than answer.
         */
        @Override
        public LlmEngine llmEngine() {
            return null;
        }

        @Override
        public boolean llmBreakerAllows() {
            throw new UnsupportedOperationException("no test here reaches an LLM stage's gate");
        }

        @Override
        public void resolveSynthesizedTap(ModelAction tap) {
            throw new UnsupportedOperationException("no test here decides through an LLM stage");
        }

        @Override
        public int componentTargetCount() {
            throw new UnsupportedOperationException("no MOP stage is assembled in these plans");
        }

        @Override
        public void triggerComponent(int target) {
            throw new UnsupportedOperationException("no MOP stage is assembled in these plans");
        }

        /** Whether a restart is due; read once per step by the restart guard. */
        private boolean restartDue;

        @Override
        public boolean restartDue() {
            return restartDue;
        }

        @Override
        public Action restartAction() {
            return RESTART_ACTION;
        }
    }

    /** The start action the fake agent's restart yields. */
    private static final ModelAction RESTART_ACTION = new ModelAction(null, ActionType.MODEL_BACK);

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
        @Override public boolean isNewState() { return false; }
        @Override public int actionBufferSize() { return 0; }
        @Override public int graphStableCounter() { return 0; }
        @Override public int timestamp() { return 1; }
        @Override public Random random() { return null; }
        @Override public MopContext mopContext() { return null; }
        @Override public StaticGuiModel staticModel() { return null; }
        @Override public StepRng stepRng() { return new StepRng(0L); }
        @Override public Graph graph() { return null; }
        @Override public List<ApePromptBuilder.ActionHistoryEntry> actionHistory() { return null; }
        @Override public void resetGraphStableCounter() { }
        @Override public com.android.commands.monkey.ape.telemetry.EventSink sink() {
            return new RecordingSink().sink;
        }
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
        // The budget is an ADDONS member, so the plan states the group.
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec("ape.addons", "true"),
                new FakeCollaborators(null, action()));

        assertEquals(Arrays.asList("Budget", "Restart", "SataChain"), pipeline.stageNames());
    }

    @Test
    public void aPlanWithTheBudgetOffAssemblesNoBudgetStage() {
        DecisionPipeline pipeline = assemble(
                TestRunSpecs.spec("ape.addons", "true", "ape.activityBudgetEnabled", "false"),
                new FakeCollaborators(null, action()));

        // Structural absence, not a stage that answers "disabled" (INV-DP-03).
        assertEquals(Arrays.asList("Restart", "SataChain"), pipeline.stageNames());
    }

    @Test
    public void assemblyEchoesTheRosterItBuilt() {
        List<DecisionPipeline> built = new java.util.ArrayList<>();
        String echoed = echoOf(TestRunSpecs.spec("ape.addons", "true"),
                new FakeCollaborators(null, action()), built);

        assertTrue("got: " + echoed,
                echoed.contains("[APE-ARCH] stages=[Budget, Restart, SataChain]"));
    }

    @Test
    public void theRestartStageIsWiredToTheAgentsRestart() throws Exception {
        FakeCollaborators agent = new FakeCollaborators(null, action());
        agent.restartDue = true;
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec(), agent);

        Action decided = pipeline.decide(new FakeContext(stateOn(ACTIVITY), exhausted()));

        assertSame("a due restart is selected before every later stage (INV-DP-14)",
                RESTART_ACTION, decided);
        assertEquals(0, agent.chainCalls);
    }

    @Test
    public void theTerminalStageIsAlwaysAssembledAndDecidesTheStep() throws Exception {
        ModelAction fromTheChain = action();
        FakeCollaborators agent =
                new FakeCollaborators(null, fromTheChain);
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec(), agent);

        Action decided = pipeline.decide(new FakeContext(stateOn(ACTIVITY), exhausted()));

        assertSame(fromTheChain, decided);
        assertEquals(1, agent.chainCalls);
    }

    @Test
    public void theBudgetStageIsWiredToTheTrivialActivityProducer() throws Exception {
        ModelAction trivial = action();
        FakeCollaborators agent =
                new FakeCollaborators(trivial, action());
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec("ape.addons", "true"), agent);

        Action decided = pipeline.decide(new FakeContext(stateOn(ACTIVITY), exhausted()));

        assertSame("assembly bound the budget gate to the wrong producer", trivial, decided);
        assertEquals("the budget gate preempts the rest of the ladder (INV-DP-02)",
                0, agent.chainCalls);
    }
}
