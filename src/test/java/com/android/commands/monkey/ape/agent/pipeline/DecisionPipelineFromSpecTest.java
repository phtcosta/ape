package com.android.commands.monkey.ape.agent.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.Presets;
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
 *
 * <p>The last group asserts the four <em>shipped</em> presets rather than plans invented for the
 * assertion, and that is the point of it: {@link DecisionPipelineTest} already covers what each
 * feature gates, so what is left to get wrong is which features a campaign arm actually states. Two
 * of those rosters are not what a reading of the stage table predicts. No shipped arm assembles the
 * launcher or the component trigger, MOP substrate or not; and both LLM arms assemble the random
 * stage neither of them mentions, because its rate is a jar default rather than a preset value.
 * Both facts are recoverable only by resolving the arms, which is what these four tests do.
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

        @Override
        public LlmRouter llmRouter() {
            return null;
        }

        @Override
        public void resolveSynthesizedTap(ModelAction tap) {
            throw new UnsupportedOperationException("no test here decides through an LLM stage");
        }

        @Override
        public int mopComponentTargetCount() {
            throw new UnsupportedOperationException("no MOP stage is assembled in these plans");
        }

        @Override
        public void triggerMopComponent(int target) {
            throw new UnsupportedOperationException("no MOP stage is assembled in these plans");
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
        @Override public boolean isNewState() { return false; }
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
                new FakeCollaborators(null, action()));

        assertEquals(Arrays.asList("Budget", "SataChain"), pipeline.stageNames());
    }

    @Test
    public void aPlanWithTheBudgetOffAssemblesNoBudgetStage() {
        DecisionPipeline pipeline = assemble(
                TestRunSpecs.spec("ape.activityBudgetEnabled", "false"),
                new FakeCollaborators(null, action()));

        // Structural absence, not a stage that answers "disabled" (INV-DP-03).
        assertEquals(Arrays.asList("SataChain"), pipeline.stageNames());
    }

    @Test
    public void assemblyEchoesTheRosterItBuilt() {
        List<DecisionPipeline> built = new java.util.ArrayList<>();
        String echoed = echoOf(TestRunSpecs.spec(),
                new FakeCollaborators(null, action()), built);

        assertTrue("got: " + echoed, echoed.contains("[APE-ARCH] stages=[Budget, SataChain]"));
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
        DecisionPipeline pipeline = assemble(TestRunSpecs.spec(), agent);

        Action decided = pipeline.decide(new FakeContext(stateOn(ACTIVITY), exhausted()));

        assertSame("assembly bound the budget gate to the wrong producer", trivial, decided);
        assertEquals("the budget gate preempts the rest of the ladder (INV-DP-02)",
                0, agent.chainCalls);
    }

    // --- The four shipped presets: feature absent = stage absent (INV-DP-03) -------------------

    /**
     * A deployment URL, stated so an LLM preset resolves at all. Only its <em>presence</em> matters
     * — it is what activates {@link Feature#LLM} — and nothing here opens a connection, the same
     * way {@link TestRunSpecs#MOP_PATH} names a file the resolver never reads.
     */
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    /**
     * The plan a preset states, plus the deployment-specific keys it deliberately omits.
     *
     * <p>A preset carries no {@code ape.mopDataPath} and no {@code ape.llmUrl} because a path and a
     * server belong to the machine rather than to the arm, and the MOP and LLM feature families
     * depend on them: {@code ape.preset=llm} without a URL aborts instead of quietly resolving to
     * "LLM off". So a preset can only be assembled the way a device receives it — name plus
     * deployment keys.
     */
    private static RunSpec preset(String name, String... deploymentKeys) {
        String[] entries = new String[deploymentKeys.length + 2];
        entries[0] = "ape.preset";
        entries[1] = name;
        System.arraycopy(deploymentKeys, 0, entries, 2, deploymentKeys.length);
        return TestRunSpecs.spec(entries);
    }

    /** Assembles the plan and asserts both the roster it built and the line it echoed. */
    private static void assertAssembles(RunSpec spec, List<String> roster) {
        List<DecisionPipeline> built = new ArrayList<>();
        String echoed = echoOf(spec, new FakeCollaborators(null, action()), built);

        assertEquals(roster, built.get(0).stageNames());
        assertTrue("echoed: " + echoed,
                echoed.contains("[APE-ARCH] stages=[" + String.join(", ", roster) + "]"));
    }

    @Test
    public void theApervPresetAssemblesTheBudgetGateAndTheTerminalStage() {
        // The baseline arm: the budget flag is the only decision-stage gate it turns on, and it
        // turns ape.activityTriggerEnabled explicitly off.
        assertAssembles(preset(Presets.APERV), Arrays.asList("Budget", "SataChain"));
    }

    @Test
    public void theMopPresetAssemblesNoMopStage() {
        // The distinction the roster makes and a feature list does not: `mop` is `aperv` plus four
        // *scoring* weights (mopWeightDirect/Transitive/OpenMenu/Wtg), which the scoring pipeline
        // reads and no decision stage gates on. The MOP substrate is present — the arm's whole
        // point — and still assembles neither MopLauncher (ape.activityTriggerEnabled stays false,
        // inherited from aperv) nor ComponentTrigger (no preset states ape.componentPercentage, so
        // it keeps its neutral 0). Guidance in this arm travels through candidate scores, never
        // through a stage of its own.
        RunSpec spec = preset(Presets.MOP, "ape.mopDataPath", TestRunSpecs.MOP_PATH);

        assertTrue("the mop arm's substrate must be present", spec.has(Feature.MOP));
        assertAssembles(spec, Arrays.asList("Budget", "SataChain"));
    }

    @Test
    public void theLlmPresetAssemblesAllThreeRoutingStages() {
        // llmBlock() states onNewState and onStagnation and says nothing about ape.llmPercentage —
        // and the third stage is assembled anyway, because that key's jar default is 0.02
        // (KeyOwnership) and a positive rate is what LLM_RANDOM gates on. The "0" beside
        // LLM_RANDOM in Feature's table is the *neutral* value a plan may state for an inactive
        // feature, not the value an unstated key resolves to. So the llm arm really does route
        // about one step in fifty at random, and the roster is where that becomes visible.
        RunSpec spec = preset(Presets.LLM, "ape.llmUrl", LLM_URL);

        assertTrue("the random-routing rate is what assembles the third stage",
                spec.has(Feature.LLM_RANDOM));
        assertAssembles(spec, Arrays.asList(
                "Budget", "LlmNewState", "LlmStagnation", "LlmRandom", "SataChain"));
    }

    @Test
    public void theLlmMopPresetAssemblesTheSameRosterAsLlm() {
        // llm_mop is mop + llmBlock(), and mop's contribution is scoring weights, so the two LLM
        // arms are structurally identical decision paths that differ only in how the SataChain's
        // candidates are scored.
        RunSpec spec = preset(Presets.LLM_MOP,
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.llmUrl", LLM_URL);

        assertTrue("the llm_mop arm's substrate must be present", spec.has(Feature.MOP));
        assertAssembles(spec, Arrays.asList(
                "Budget", "LlmNewState", "LlmStagnation", "LlmRandom", "SataChain"));
    }
}
