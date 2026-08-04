package com.android.commands.monkey.ape.agent.pipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

/**
 * LLM fallback is the rest of the pipeline, and nothing else (INV-DP-11).
 *
 * <p>The stage tests already pin that each LLM stage answers {@code Continue} when the model
 * declines or its trigger says no. What they cannot show, one stage at a time, is the property the
 * word "fallback" actually names: that the step is still decided, by whatever the plan put after
 * the LLM stages, carrying that stage's own attribution. That is asserted here over an assembled
 * pipeline, because "the remainder" is a fact about the roster rather than about any one stage.
 *
 * <p><b>Why the remainder is asserted twice, on two plans.</b> Under the {@code llm} arm the
 * remainder is the SATA chain, so the fallback is attributed {@code SATA}. Add the MOP substrate and
 * turn the activity trigger on and the launcher sits between the LLM stages and the chain, so the
 * same declined step is decided by the launcher and attributed {@code Component}. A test that only
 * ever saw {@code SATA} would pass just as well against a hardcoded chain — the second plan is what
 * makes "the configured remainder" mean something.
 *
 * <p><b>Decline and timeout are one case here, deliberately.</b> At the stage seam a timeout is a
 * null answer, exactly like a decline, a no-match or a banned pair; the difference between them is
 * provenance, recorded a layer up and pinned by the {@code llm} golden, whose steps 1 and 2 are a
 * decline and a timeout falling through to {@code SATA}. Writing two identical stage-level tests
 * would assert the same code path twice under two names.
 *
 * <p><b>Breaker denial arrives as a false trigger.</b> {@code breakerAllows()} is the last conjunct
 * of each routing predicate, so a stage cannot tell a breaker-open step from any other declined
 * trigger — which is the whole point of INV-DP-11 having one path. The breaker's own contract, that
 * its OPEN line is logged once per open episode, is asserted where the latch lives, in
 * {@code LlmRouterTest}.
 */
public class LlmStructuralFallbackTest {

    private static final String ACTIVITY = "com.example.MainActivity";
    private static final String PACKAGE = "com.example.app";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    /**
     * The router reduced to a verdict: whether the triggers route, and what the engine answers.
     *
     * <p>A null answer stands for every engine failure the spec enumerates — decline, timeout,
     * no-match, dead-pair ban, boundary reject, transport error — because that is what all of them
     * return.
     */
    private static class StubRouter extends LlmRouter {

        private final boolean routes;
        private final ModelAction answer;
        int selectCalls;

        StubRouter(boolean routes, ModelAction answer) {
            super(new java.util.Random(42L));
            this.routes = routes;
            this.answer = answer;
        }

        @Override
        public boolean shouldRouteNewState(boolean isNewState) {
            return routes;
        }

        @Override
        public boolean shouldRouteStagnation(int graphStableCounter, boolean firedThisEpisode) {
            return routes && !firedThisEpisode;
        }

        @Override
        public boolean shouldRouteRandom() {
            return routes;
        }

        @Override
        public ModelAction selectAction(GUITree tree, State state, List<ModelAction> actions,
                MopData mopData, List<ApePromptBuilder.ActionHistoryEntry> history, String mode,
                int timestamp) {
            selectCalls++;
            return answer;
        }
    }

    /** The agent, reduced to the router and the chain's last rung. */
    private static class FallbackCollaborators implements StageCollaborators {

        private final LlmRouter router;
        private final ModelAction fromTheChain;
        int chainCalls;

        FallbackCollaborators(LlmRouter router, ModelAction fromTheChain) {
            this.router = router;
            this.fromTheChain = fromTheChain;
        }

        @Override public LlmRouter llmRouter() { return router; }
        @Override public ModelAction selectNewActionForTrivialActivity() { return null; }
        @Override public ModelAction selectNewActionBackToActivity() { return null; }
        @Override public ModelAction selectNewActionEarlyStageForward() { return null; }
        @Override public ModelAction selectNewActionEarlyStageBackward() { return null; }
        @Override public ModelAction selectNewActionEpsilonGreedyRandomly() { return null; }
        @Override public void logActionSelected(Action action, SataAgent.SataEventType type) { }

        @Override
        public ModelAction selectNewActionFromBuffer() {
            chainCalls++;
            return null;
        }

        /** The rung these plans let the chain answer from. */
        @Override
        public ModelAction handleNullAction() {
            return fromTheChain;
        }

        @Override
        public void resolveSynthesizedTap(ModelAction tap) {
            throw new UnsupportedOperationException("no answer here is a synthesized tap");
        }

        @Override
        public int mopComponentTargetCount() {
            throw new UnsupportedOperationException("no plan here assembles the component trigger");
        }

        @Override
        public void triggerMopComponent(int target) {
            throw new UnsupportedOperationException("no plan here assembles the component trigger");
        }
    }

    /** The plan a preset states, plus the deployment keys it deliberately omits. */
    private static RunSpec preset(String name, String... deploymentKeys) {
        String[] entries = new String[deploymentKeys.length + 2];
        entries[0] = "ape.preset";
        entries[1] = name;
        System.arraycopy(deploymentKeys, 0, entries, 2, deploymentKeys.length);
        return TestRunSpecs.spec(entries);
    }

    /** The shipped {@code llm} arm: the LLM stages, then the chain. */
    private static RunSpec llmPlan() {
        return preset(Presets.LLM, "ape.llmUrl", LLM_URL);
    }

    /**
     * The {@code llm} arm with the MOP substrate and the activity trigger on, which is what puts the
     * launcher between the LLM stages and the chain. The cadence of 1 makes the launcher's first
     * evaluated step its firing point, so the fall-through reaches it immediately.
     */
    private static RunSpec llmWithLauncherPlan() {
        return preset(Presets.LLM,
                "ape.llmUrl", LLM_URL,
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.activityTriggerEnabled", "true",
                "ape.activityTriggerStagnationStep", "1");
    }

    /** A step every LLM stage is entitled to consult: empty buffer, three actions, new state. */
    private static FakeStepContext routableStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.isNewState = true;
        ctx.actionBufferSize = 0;
        ctx.timestamp = 7;
        // Registered nowhere, so the activity has budget left and the gate passes.
        ctx.budgetTracker = new ActivityBudgetTracker(10, 1);
        return ctx;
    }

    private static ModelAction chainAction() {
        // ModelAction's default source is SATA, which is what an untouched chain rung carries.
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    /** MopData carrying just the census the launcher reads, and one activity it can launch. */
    private static MopData census() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        MopData data = (MopData) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), MopData.class);
        String target = PACKAGE + ".SettingsActivity";
        List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
        activities.add(new ComponentInfo.ActivityInfo(target, false, true,
                java.util.Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                java.util.Collections.<String>emptyList(), null));
        FakeStepContext.setField(data, "activities", activities);
        FakeStepContext.setField(data, "packageName", PACKAGE);
        FakeStepContext.setField(data, "mainActivity", PACKAGE + ".MainActivity");
        FakeStepContext.setField(data, "mopActivities", new HashSet<>(Arrays.asList(target)));
        return data;
    }

    @Test
    public void anEngineThatAnswersNothingLeavesTheStepToTheChain() throws Exception {
        StubRouter router = new StubRouter(true, null);
        ModelAction fromTheChain = chainAction();
        FallbackCollaborators agent = new FallbackCollaborators(router, fromTheChain);
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(llmPlan(), agent);

        Action decided = pipeline.decide(routableStep());

        assertSame("the remainder decides the step, and nothing retries", fromTheChain, decided);
        assertEquals("all three routing modes asked and all three were answered with nothing",
                3, router.selectCalls);
        assertEquals(1, agent.chainCalls);
        assertEquals(ModelAction.DecisionSource.SATA, fromTheChain.getDecisionSource());
        assertNotEquals("a step the LLM did not decide must not be attributed to it",
                ModelAction.DecisionSource.LLM, fromTheChain.getDecisionSource());
    }

    @Test
    public void aDeniedTriggerLeavesTheStepToTheChainWithoutConsultingTheModel() throws Exception {
        // What a breaker-open step looks like from a stage: the predicate says no. So does a mode
        // that is off, or a coin that came up short — one path, by construction.
        StubRouter router = new StubRouter(false, chainAction());
        ModelAction fromTheChain = chainAction();
        FallbackCollaborators agent = new FallbackCollaborators(router, fromTheChain);
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(llmPlan(), agent);

        Action decided = pipeline.decide(routableStep());

        assertSame(fromTheChain, decided);
        assertEquals("a denied trigger must not cost a round trip", 0, router.selectCalls);
        assertEquals(ModelAction.DecisionSource.SATA, fromTheChain.getDecisionSource());
    }

    @Test
    public void theRemainderIsWhateverThePlanPutAfterTheLlmStages() throws Exception {
        StubRouter router = new StubRouter(true, null);
        FallbackCollaborators agent = new FallbackCollaborators(router, chainAction());
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(llmWithLauncherPlan(), agent);
        FakeStepContext ctx = routableStep();
        ctx.mopData = census();
        ctx.graph = new Graph(); // no activity node visited, so the census candidate is eligible

        Action decided = pipeline.decide(ctx);

        assertEquals(Arrays.asList("Budget", "LlmNewState", "LlmStagnation", "LlmRandom",
                "MopLauncher", "SataChain"), pipeline.stageNames());
        assertEquals("the declined step falls through to the launcher, not past it to the chain",
                ActivityTriggerAction.class, decided.getClass());
        assertEquals(PACKAGE + ".SettingsActivity",
                ((ActivityTriggerAction) decided).getClassName());
        assertEquals("the chain was never reached", 0, agent.chainCalls);
    }
}
