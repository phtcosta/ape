package com.android.commands.monkey.ape.agent.pipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopData;

/**
 * A whole run's worth of collaborators, stated as values, for the tests that assert a property of an
 * assembled pipeline rather than of one stage.
 *
 * <p>{@link FakeStepContext} does this for a step; this does it for the run around the step. The two
 * tests that need it — {@link LlmStructuralFallbackTest} and {@link HardPreemptionTest} — assert
 * opposite halves of the same mechanism (what happens when an LLM stage declines, and what happens
 * when it does not), so they need the same plan, the same census and the same scripted router, and
 * differ only in the verdict and in what they count.
 *
 * <p>Everything here is scripted rather than mocked: a plan is resolved from a preset the way a
 * device receives it, and the census is the smallest MopData the launcher and the component trigger
 * will both accept.
 */
final class PipelineFixture {

    /** The activity the step's state runs on; never registered with a budget, so the gate passes. */
    static final String ACTIVITY = "com.example.MainActivity";

    /** The package the census declares, and the one a launched trigger must carry (INV-CT-04). */
    static final String PACKAGE = "com.example.app";

    /** The census activity the launcher's round-robin returns first. */
    static final String TRIGGER_TARGET = PACKAGE + ".SettingsActivity";

    /**
     * A deployment URL, stated so an LLM plan resolves at all. Only its presence matters — it is
     * what activates the LLM feature — and nothing here opens a connection.
     */
    static final String LLM_URL = "http://10.0.2.2:30000/v1";

    private PipelineFixture() {
    }

    /**
     * The router reduced to a verdict: whether the triggers route, and what the engine answers.
     *
     * <p>A null answer stands for every engine failure the spec enumerates — decline, timeout,
     * no-match, dead-pair ban, boundary reject, transport error — because that is what all of them
     * return. A false {@code routes} stands for every trigger denial, breaker included: the breaker
     * is the last conjunct of each real predicate, so a stage cannot tell those apart either.
     */
    static class StubRouter extends LlmRouter {

        private final boolean routes;
        private ModelAction answer;
        int selectCalls;

        StubRouter(boolean routes, ModelAction answer) {
            super(new Random(42L));
            this.routes = routes;
            this.answer = answer;
        }

        /** Changes what the engine answers from the next step on. */
        void answersFrom(ModelAction next) {
            this.answer = next;
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

    /**
     * The agent, reduced to what assembly binds, counting the calls a preemption assertion needs.
     *
     * <p>The chain answers from its last rung and the count is taken on its first, which is the only
     * rung no other stage shares — the trivial-activity search is both rung four and the budget
     * gate's producer, so counting there could not tell the two apart.
     */
    static class FakeAgent implements StageCollaborators {

        private final LlmRouter router;
        private final ModelAction fromTheChain;
        private final int componentTargets;

        int chainCalls;
        int triggerCalls;
        int lastTriggeredTarget = -1;

        FakeAgent(LlmRouter router, ModelAction fromTheChain) {
            this(router, fromTheChain, 1);
        }

        FakeAgent(LlmRouter router, ModelAction fromTheChain, int componentTargets) {
            this.router = router;
            this.fromTheChain = fromTheChain;
            this.componentTargets = componentTargets;
        }

        @Override public LlmRouter llmRouter() { return router; }
        @Override public ModelAction selectNewActionForTrivialActivity() { return null; }
        @Override public ModelAction selectNewActionBackToActivity() { return null; }
        @Override public ModelAction selectNewActionEarlyStageForward() { return null; }
        @Override public ModelAction selectNewActionEarlyStageBackward() { return null; }
        @Override public ModelAction selectNewActionEpsilonGreedyRandomly() { return null; }
        @Override public void logActionSelected(Action action, SataAgent.SataEventType type) { }
        @Override public int mopComponentTargetCount() { return componentTargets; }

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
        public void triggerMopComponent(int target) {
            triggerCalls++;
            lastTriggeredTarget = target;
        }

        @Override
        public void resolveSynthesizedTap(ModelAction tap) {
            throw new UnsupportedOperationException("no answer here is a synthesized tap");
        }
    }

    /** The plan a preset states, plus the deployment keys it deliberately omits. */
    static RunSpec preset(String name, String... deploymentKeys) {
        String[] entries = new String[deploymentKeys.length + 2];
        entries[0] = "ape.preset";
        entries[1] = name;
        System.arraycopy(deploymentKeys, 0, entries, 2, deploymentKeys.length);
        return TestRunSpecs.spec(entries);
    }

    /** The shipped {@code llm} arm: the three LLM stages, then the chain. */
    static RunSpec llmPlan() {
        return preset(Presets.LLM, "ape.llmUrl", LLM_URL);
    }

    /**
     * The {@code llm} arm with the MOP substrate and the activity trigger on, which puts the launcher
     * between the LLM stages and the chain.
     *
     * @param cadence the launcher's firing period, so a test can put the firing point where it needs
     *        it rather than counting to the jar default of fifty
     */
    static RunSpec llmWithLauncherPlan(int cadence) {
        return preset(Presets.LLM,
                "ape.llmUrl", LLM_URL,
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.activityTriggerEnabled", "true",
                "ape.activityTriggerStagnationStep", Integer.toString(cadence));
    }

    /** A step every LLM stage is entitled to consult: empty buffer, three actions, new state. */
    static FakeStepContext routableStep() throws Exception {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = FakeStepContext.stateWith(ACTIVITY, 3);
        ctx.isNewState = true;
        ctx.actionBufferSize = 0;
        ctx.timestamp = 7;
        // Registered nowhere, so the activity has budget left and the gate passes.
        ctx.budgetTracker = new ActivityBudgetTracker(10, 1);
        return ctx;
    }

    /** An untouched chain action: {@code ModelAction}'s default source is {@code SATA}. */
    static ModelAction chainAction() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    /**
     * MopData carrying just the census the MOP stages read: one launchable activity, and the three
     * empty component lists {@code hasComponents()} walks before it reaches the activities.
     *
     * <p>Allocated rather than parsed, because the loader wants a JSON artifact and every field below
     * is one a stage names directly.
     */
    static MopData census() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        MopData data = (MopData) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), MopData.class);
        List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
        activities.add(new ComponentInfo.ActivityInfo(TRIGGER_TARGET, false, true,
                Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                Collections.<String>emptyList(), null));
        FakeStepContext.setField(data, "activities", activities);
        FakeStepContext.setField(data, "receivers",
                Collections.<ComponentInfo.ReceiverInfo>emptyList());
        FakeStepContext.setField(data, "services",
                Collections.<ComponentInfo.ServiceInfo>emptyList());
        FakeStepContext.setField(data, "providers",
                Collections.<ComponentInfo.ProviderInfo>emptyList());
        FakeStepContext.setField(data, "packageName", PACKAGE);
        FakeStepContext.setField(data, "mainActivity", PACKAGE + ".MainActivity");
        FakeStepContext.setField(data, "mopActivities",
                new HashSet<>(Arrays.asList(TRIGGER_TARGET)));
        return data;
    }

    /** A graph with no visited activity node, so the census candidate is eligible to launch. */
    static Graph emptyGraph() {
        return new Graph();
    }
}
