package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.DecisionStage;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The preemption golden (parity-oracle "Preemption-Order Golden"), captured in the
 * {@code as_mop_llm} cell: the artifact that pins the <b>order</b> in which the owners get to
 * decide, rather than what one of them decides.
 *
 * <p>The pipeline is BUDGET, RESTART, LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN, MOP_LAUNCHER,
 * COMPONENT_TRIGGER, MOP_COIN, SATA_CHAIN. An accepted LLM answer preempts the launcher, the
 * launcher preempts the MOP coin, a won MOP coin preempts the SATA chain, and at most one LLM stage
 * calls the model on a step (design D-B8). RESTART is assembled but never selects here: its start
 * action leaves the JVM, so the scaffold injects a threshold that never comes due.
 *
 * <h2>The scenario, and what each of its four steps is for</h2>
 * <ol>
 *   <li><b>{@code entry}, LLM accept.</b> The cadence counter is seeded at {@link #DUE_CADENCE} =
 *       49, so the step's open advances it to the cadence and the launcher is <i>due</i>. The
 *       new-state stage accepts first, so the launcher is not reached; the counter has advanced
 *       anyway (global cadence, design D-B11, INV-ORA-05).</li>
 *   <li><b>{@code primed}, the new-state call declines.</b> The new-state stage makes the step's one
 *       call and gets null; the stagnation and coin stages return {@code Continue} without
 *       consulting anything (INV-RTR-26); the step reaches the launcher, now past its cadence, which
 *       fires. One record carries both {@code llm:"declined"} and {@code EVENT_TRIGGER_ACTIVITY},
 *       although the screen offers a MOP-weighted widget: the launcher precedes the MOP coin.</li>
 *   <li><b>{@code primed} again, a stagnation decline.</b> The counter is at the midpoint, so the
 *       stagnation stage calls, burns the episode's shot and gets null. The cadence restarted at the
 *       fire, so the launcher stays quiet and the MOP coin takes the weighted widget.</li>
 *   <li><b>{@code plain}, the shot is spent.</b> The script names the stagnation stage again, but
 *       its flag is burned, so only the coin stage reaches its gate, which the script refuses; the
 *       screen carries no MOP weight, so the SATA chain is the fallback.</li>
 * </ol>
 *
 * <h2>The budget gate, in both its states</h2>
 * {@code entry} runs on an activity the scenario never declares exhausted, so the block is not
 * entered; {@code primed} runs on one it does, so on steps 1–2 the block is entered, gets no
 * trivial action and falls through, ahead of every other stage. Both states are asserted on the
 * tracker, because the two produce the same decision record. Its trivial-action return is outside
 * the capture boundary (finding 6.1-b).
 *
 * <h2>Why some field assertions are one step long</h2>
 * The launcher's cadence counter and the stagnation stage's shot are never written by
 * {@link OracleDriver}, so both survive a multi-step run and are read at its end.
 * {@code graphStableCounter} is not: the driver injects the step's scripted value at the start of
 * every step, so its two assertions — reset to 0 by a stagnation accept, left alone by a decline —
 * are made on one-step scenarios, where the value read after the run is what the ladder left.
 */
public class PreemptionGoldenTest {

    private static final long SEED = 8080L;

    private static final String PRESET = "as_mop_llm";
    private static final String SCENARIO = "preemption";

    /** The entry screen's activity: never declared exhausted, so the budget gate stays closed. */
    private static final String ENTRY_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    /**
     * The activity the scenario declares exhausted. It is deliberately <b>not</b> one of the MOP
     * fixture's census activities: the launcher skips the activities already in the graph, so a
     * screen borrowing a census name would starve the launcher of its own candidate.
     */
    private static final String PRIMED_ACTIVITY = "br.unb.cic.cryptoapp.SideActivity";

    /** The last screen's activity, also outside the census. */
    private static final String PLAIN_ACTIVITY = "br.unb.cic.cryptoapp.PlainActivity";

    /** The census activity nearest to an unretired target (distance mode, design D20). */
    private static final String EXPECTED_TRIGGER =
            "br.unb.cic.cryptoapp.generated.CryptographyActivity";

    /**
     * One below {@code ape.activityTriggerStagnationStep} (50): the step's open advances the counter
     * to the cadence, so the launcher is due on the first step of the run.
     */
    private static final int DUE_CADENCE = 49;

    /** The stagnation midpoint at the harness's threshold of 10, and a non-zero counter. */
    private static final int STABLE_COUNTER = 5;

    /**
     * Widgets outrank BACK/MENU so the EARLY_STAGE roulette keeps the walk on the targeted actions:
     * a targetless pick has no transition, and a scenario that silently stops moving looks exactly
     * like one that works.
     */
    private static final int WIDGET_PRIORITY = 20;
    private static final int NAV_PRIORITY = 5;
    private static final int DECLARED_WEIGHT = 7;

    private static final String E0 = "//*[@resource-id='e0']";
    private static final String E1 = "//*[@resource-id='e1']";
    private static final String E2 = "//*[@resource-id='e2']";
    private static final String P_WEIGHTED = "//*[@resource-id='p_mop']";

    // The three LLM stages, in roster order.
    private static final String NEW_STATE = "new-state";
    private static final String STAGNATION = "stagnation";
    private static final String RANDOM = "random";

    private static ScenarioScript preemption() {
        return ScenarioScript.named(SCENARIO, SEED)
                .screens(
                        ScenarioScript.screen("entry", ENTRY_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget(E0, WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget(E1, WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget(E2, WIDGET_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("primed", PRIMED_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget(P_WEIGHTED, WIDGET_PRIORITY, false, 0.0F,
                                        DECLARED_WEIGHT),
                                ScenarioScript.widget("//*[@resource-id='p1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='p2']", WIDGET_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("plain", PLAIN_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='q0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='q1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='q2']", WIDGET_PRIORITY, false, 0.0F)))
                // Whichever entry widget the accept picks, the run arrives on `primed`; the launcher
                // leaves it there, and the MOP coin's pick moves it on to `plain`.
                .transition("entry", E0, "primed")
                .transition("entry", E1, "primed")
                .transition("entry", E2, "primed")
                .transition("primed", P_WEIGHTED, "plain")
                .stepsSinceLauncherFiring(DUE_CADENCE)
                .budgetExhausted(PRIMED_ACTIVITY)
                .steps(
                        // 0 — accept, on a first visit, while the launcher is due.
                        ScenarioScript.step(true, 0, ScenarioScript.accept(
                                true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)),
                        // 1 — first arrival on `primed`: the new-state call declines, and it is the
                        // step's only call.
                        ScenarioScript.step(true, 0, ScenarioScript.decline(true, true, true)),
                        // 2 — the counter at the midpoint: the stagnation call declines.
                        ScenarioScript.step(false, STABLE_COUNTER,
                                ScenarioScript.decline(false, true, false)),
                        // 3 — the stagnation stage is scripted again; the shot is already spent.
                        ScenarioScript.step(false, STABLE_COUNTER,
                                ScenarioScript.decline(false, true, false)))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertCellValues(OracleScaffold.Cell.AS_MOP_LLM);
    }

    @Test
    public void theSeededCadenceIsOneBelowTheConfiguredFiringPoint() {
        // The whole scenario rests on this arithmetic: seeded at 49, the first step's open reaches
        // the cadence.
        RunSpec spec = OracleScaffold.planOf(OracleScaffold.Cell.AS_MOP_LLM, SEED);
        assertEquals("ape.activityTriggerStagnationStep", DUE_CADENCE + 1,
                spec.mop().activityTriggerStagnationStep());
    }

    @Test
    public void preemptionGoldenIsReproduced() throws Exception {
        ScenarioScript script = preemption();
        HookOrderRecorder recorder = new HookOrderRecorder(script);
        OracleSataAgent agent =
                OracleScaffold.newAgent(OracleScaffold.Cell.AS_MOP_LLM, script, recorder);

        List<DecisionRecord> records = OracleDriver.run(agent, script, recorder);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());

        // --- step 0: the accept preempts a launcher that was due to fire
        DecisionRecord accepted = records.get(0);
        assertEquals("the new-state stage accepted", "accepted", accepted.getLlm());
        assertEquals("the accepting LLM stage owns the action", "LLM", accepted.getDecisionSource());
        assertEquals("through the LLM channel", "llm", accepted.getPickChannel());
        assertEquals("only the new-state stage was consulted — it decided the step",
                Arrays.asList(NEW_STATE), recorder.consultedAt(0));

        // --- step 1: one call, declined, and the launcher fires over the MOP-weighted screen
        DecisionRecord launcher = records.get(1);
        assertEquals("the new-state call declined", "declined", launcher.getLlm());
        assertEquals("the stages after it did not call (INV-RTR-26)",
                Arrays.asList(NEW_STATE), recorder.consultedAt(1));
        assertEquals("and the step fell past the LLM stages into the launcher",
                "EVENT_TRIGGER_ACTIVITY", launcher.getActionType());
        assertEquals("with the nearest census candidate as its target", EXPECTED_TRIGGER,
                launcher.getTarget());
        assertNull("a launcher return is not a ModelAction, so it carries no decision source",
                launcher.getDecisionSource());
        assertNull("nor a pick channel", launcher.getPickChannel());

        // --- step 2: the stagnation call declines, the MOP coin takes the weighted widget
        DecisionRecord coin = records.get(2);
        assertEquals("the stagnation call declined", "declined", coin.getLlm());
        assertEquals(Arrays.asList(STAGNATION), recorder.consultedAt(2));
        assertEquals("the launcher's cadence restarted at the fire, so the MOP coin decides",
                "MOP", coin.getDecisionSource());
        assertEquals("on its own channel", "mop_coin", coin.getPickChannel());
        assertEquals("the weighted widget", P_WEIGHTED, coin.getTarget());

        // --- step 3: the shot is spent, and the SATA chain is the fallback
        DecisionRecord fallback = records.get(3);
        assertEquals("the stagnation stage does not reach its gate once the shot is spent",
                Arrays.asList(RANDOM), recorder.consultedAt(3));
        assertTrue("and the coin stage's gate refuses: the script names only stagnation",
                recorder.routedAt(3).isEmpty());
        assertEquals("not_routed", fallback.getLlm());
        assertEquals("a screen without MOP weight falls to the SATA chain", "SATA",
                fallback.getDecisionSource());
        assertTrue("and the episode's flag stays burned", stagnationShotBurned(agent));

        // --- the cadence counter, after the run: reset at the fire, then one advance per step
        assertEquals("the counter restarted from the firing point and advanced once per later step",
                2, cadenceCounter(agent));

        // --- the budget gate, in both its states
        ActivityBudgetTracker budget =
                (ActivityBudgetTracker) OracleScaffold.getField(agent, "_budgetTracker");
        assertTrue("the declared activity is exhausted, so the block is entered on steps 1-2",
                budget.isBudgetExhausted(PRIMED_ACTIVITY));
        assertFalse("the entry activity is unregistered, so step 0 never enters the block",
                budget.isBudgetExhausted(ENTRY_ACTIVITY));
        for (DecisionRecord record : records) {
            assertFalse("the budget block's trivial return is outside the boundary (6.1-b)",
                    "Budget".equals(record.getDecisionSource()));
        }

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, OracleScaffold.MOP_FIXTURE, GoldenFile.capturedAt()),
                records);
    }

    // ---- the field assertions the golden cannot make --------------------------------------------

    /**
     * INV-ORA-05, stated as a contrast: two one-step runs from the <i>same</i> seeded cadence,
     * differing only in whether the LLM accepted. The accepted one has still advanced the counter to
     * the cadence; the unrouted one reaches the launcher, which fires and resets it.
     */
    @Test
    public void anAcceptedStepAdvancesTheLauncherCadence() throws Exception {
        ScenarioScript preempted = oneStep("cadence-preempted", DUE_CADENCE,
                ScenarioScript.step(true, 0, ScenarioScript.accept(
                        true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)));
        ScriptedLlm preemptedLlm = new ScriptedLlm(preempted);
        OracleSataAgent accepting = OracleScaffold.newAgent(
                OracleScaffold.Cell.AS_MOP_LLM, preempted, preemptedLlm);

        List<DecisionRecord> acceptedRecords =
                OracleDriver.run(accepting, preempted, preemptedLlm);

        assertEquals("the step was taken by the LLM", "accepted", acceptedRecords.get(0).getLlm());
        assertEquals("and the counter advanced anyway, reaching the cadence (D-B11)",
                DUE_CADENCE + 1, cadenceCounter(accepting));

        ScenarioScript unpreempted =
                oneStep("cadence-unpreempted", DUE_CADENCE, ScenarioScript.step(true, 0));
        ScriptedLlm unpreemptedLlm = new ScriptedLlm(unpreempted);
        OracleSataAgent unrouted = OracleScaffold.newAgent(
                OracleScaffold.Cell.AS_MOP_LLM, unpreempted, unpreemptedLlm);

        List<DecisionRecord> unroutedRecords =
                OracleDriver.run(unrouted, unpreempted, unpreemptedLlm);

        assertEquals("the same seeded cadence, unpreempted, fires the launcher",
                "EVENT_TRIGGER_ACTIVITY", unroutedRecords.get(0).getActionType());
        assertEquals("which resets the counter", 0, cadenceCounter(unrouted));
    }

    /**
     * The episode's single shot is spent once the stagnation stage calls, whatever the verdict, and
     * a decline leaves {@code graphStableCounter} where it was — the reset is on the accept path
     * only.
     */
    @Test
    public void aStagnationDeclineBurnsTheShotAndLeavesTheCounterUnchanged() throws Exception {
        ScenarioScript script = oneStep("stagnation-decline", 0,
                ScenarioScript.step(false, STABLE_COUNTER,
                        ScenarioScript.decline(false, true, false)));
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Cell.AS_MOP_LLM, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("the stagnation stage called and the answer declined",
                "declined", records.get(0).getLlm());
        assertTrue("the shot is burned by the call, whatever the answer",
                stagnationShotBurned(agent));
        assertEquals("a decline does not reset the counter",
                STABLE_COUNTER, intField(agent, "graphStableCounter"));
        assertEquals("and the step still produced a decision, below the LLM stages",
                "SATA", records.get(0).getDecisionSource());
    }

    /** The other half of the same rule: {@code graphStableCounter} is reset by an accept. */
    @Test
    public void aStagnationAcceptResetsTheCounter() throws Exception {
        ScenarioScript script = oneStep("stagnation-accept", 0,
                ScenarioScript.step(false, STABLE_COUNTER, ScenarioScript.accept(
                        false, true, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)));
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Cell.AS_MOP_LLM, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("the stagnation stage accepted", "accepted", records.get(0).getLlm());
        assertEquals("an accept resets the counter", 0, intField(agent, "graphStableCounter"));
        assertTrue("and burns the shot on the same path",
                stagnationShotBurned(agent));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A one-screen, one-step scenario: the shortest run in which a field the ladder writes can be
     * read without the driver's next injection having overwritten it. Its activity is never
     * declared exhausted, so the budget block stays out of the way of what is being observed.
     */
    private static ScenarioScript oneStep(String name, int seededCadence, ScenarioScript.Step step) {
        return ScenarioScript.named(name, SEED)
                .screens(ScenarioScript.screen("only", ENTRY_ACTIVITY, NAV_PRIORITY, false,
                        ScenarioScript.widget(E0, WIDGET_PRIORITY, false, 0.0F),
                        ScenarioScript.widget(E1, WIDGET_PRIORITY, false, 0.0F),
                        ScenarioScript.widget(E2, WIDGET_PRIORITY, false, 0.0F)))
                .stepsSinceLauncherFiring(seededCadence)
                .steps(step)
                .build();
    }

    private static int intField(Object target, String name) throws Exception {
        return (Integer) OracleScaffold.getField(target, name);
    }

    private static boolean boolField(Object target, String name) throws Exception {
        return (Boolean) OracleScaffold.getField(target, name);
    }

    /**
     * Whether the stagnation episode has spent its single shot.
     *
     * <p>The flag lives in the stage that owns it rather than on the agent (INV-DP-07), so reading it
     * means going through the assembled roster. That is the point of the invariant: nothing outside
     * the stage can reach the flag by ordinary means, and a test that wants to observe it has to say
     * out loud whose state it is.
     */
    private static int cadenceCounter(OracleSataAgent agent) throws Exception {
        return (Integer) stageField(agent, DecisionPipeline.Candidate.MOP_LAUNCHER, "stepsSinceFiring");
    }

    private static boolean stagnationShotBurned(OracleSataAgent agent) throws Exception {
        return (Boolean) stageField(agent, DecisionPipeline.Candidate.LLM_STAGNATION,
                "firedThisEpisode");
    }

    /** A field of the stage that owns it, found through the roster (INV-DP-07). */
    private static Object stageField(OracleSataAgent agent, DecisionPipeline.Candidate candidate,
            String field) throws Exception {
        DecisionPipeline pipeline =
                (DecisionPipeline) OracleScaffold.getField(agent, "decisionPipeline");
        for (DecisionStage stage : pipeline.stages()) {
            if (stage.name().equals(candidate.stageName())) {
                return OracleScaffold.getField(stage, field);
            }
        }
        throw new IllegalStateException("this preset assembles no " + candidate + " stage");
    }

    /**
     * A {@link ScriptedLlm} that additionally records, per step, which hooks reached their gate and
     * in which order — the only way to observe the spec's hook-order requirement, since a step
     * where two hooks decline is indistinguishable in the golden from one where only the second was
     * reached.
     *
     * <p>It lives here rather than in {@code ScriptedLlm} on purpose: the cell baselines are captured
     * with that class as it stands, and observation added to a shared class is one edit away from
     * being behavior added to it. A local subclass cannot touch them.
     *
     * <p><b>What "consulted" means.</b> The scripted verdict stands in for the breaker, the last gate
     * of the chain, so a stage records only when no LLM stage of the step has called yet, its own
     * trigger held and the plan's minimum-actions gate admitted.
     */
    static final class HookOrderRecorder extends ScriptedLlm {

        private final List<List<String>> consulted = new ArrayList<>();
        private final List<List<String>> routed = new ArrayList<>();

        HookOrderRecorder(ScenarioScript script) {
            super(script);
        }

        @Override
        void beginStep(int index) {
            super.beginStep(index);
            consulted.add(new ArrayList<String>());
            routed.add(new ArrayList<String>());
        }

        @Override
        protected boolean consult(String hook) {
            boolean routes = super.consult(hook);
            consulted.get(consulted.size() - 1).add(hook);
            if (routes) {
                routed.get(routed.size() - 1).add(hook);
            }
            return routes;
        }

        /** The hooks whose gate the pipeline reached on {@code step}, in invocation order. */
        List<String> consultedAt(int step) {
            return consulted.get(step);
        }

        /** The subset of those that answered true, so the engine was reached. */
        List<String> routedAt(int step) {
            return routed.get(step);
        }
    }
}
