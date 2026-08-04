package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.DecisionStage;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.Config;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * rearch-01 tasks 7.1/7.2 — the preemption golden: the only artifact of this change that pins the
 * <b>order</b> in which mechanisms get to decide, rather than what one of them decides.
 *
 * <p>The four preset baselines pin behavior; this one pins precedence. Today that precedence is
 * nothing but textual position inside {@code selectNewActionNonnull()} ({@code SataAgent.java:449-589}):
 * budget gate, then the three LLM hooks, then the cadence launcher, then the SATA chain.
 * {@code rearch-03-decision-pipeline} replaces textual position with a pipeline, and this golden is
 * what will notice if the pipeline reorders them.
 *
 * <h2>The scenario, and what each of its four steps is for</h2>
 * <ol>
 *   <li><b>{@code entry}, LLM accept.</b> The cadence counter is seeded at
 *       {@link #DUE_CADENCE} = 49, one below {@code Config.activityTriggerStagnationStep}, and an
 *       unvisited census candidate is available — so the launcher is <i>due</i>: had this step not
 *       been preempted, the launcher stage's cadence increment would have reached 50 and
 *       fired. The new-state hook accepts and the ladder returns at {@code :485}, above the
 *       increment. <b>The golden itself carries the proof</b>: the launcher fires on step 1, not on
 *       step 0, which is only possible if step 0 left the counter at 49 (finding 3.3-1,
 *       INV-ORA-05).</li>
 *   <li><b>{@code primed}, every scripted verdict declines.</b> All three hooks route, in order, and
 *       all three answer null; the step falls past them into the launcher block, whose counter now
 *       reaches the cadence and fires. One record carrying both {@code llm:"declined"} and the
 *       launcher's {@code EVENT_TRIGGER_ACTIVITY} — see the note below on why that is one step and
 *       not two. This is also the step where the episode's stagnation shot is burned
 *       ({@code :499}, burned on the consultation and not on the verdict).</li>
 *   <li><b>SATA fallback.</b> No scripted entry: all three predicates are consulted and none routes,
 *       the counter is 1 (reset at the fire), and the chain decides. The launcher preempts the SATA
 *       chain only when it fires; this is what the chain does when it does not.</li>
 *   <li><b>The single shot is spent.</b> The step scripts the stagnation hook again; the stub honors
 *       the {@code firedThisEpisode} argument, so the predicate is consulted and answers false, and
 *       the step falls through to the SATA chain unrouted.</li>
 * </ol>
 *
 * <h2>Why the decline and the launcher fire are the same step (§ the spec's two scenarios)</h2>
 * The spec asks for a record carrying {@code llm:"declined"} <i>and</i> the launcher's
 * {@code EVENT_TRIGGER_ACTIVITY}. That combination is only reachable on a single step: the LLM
 * blocks must be consulted and decline, and only then does control reach {@code :522}, so the
 * cadence has to be met on a step that also routes. It cannot be split across two steps — a
 * launcher fire resets the counter to 0 and {@code shouldFireLauncher} is an exact equality
 * ({@code :781}), so one scenario can fire the launcher exactly once. <b>One scenario file
 * therefore carries both requirements</b>, and no second preemption golden is needed: step 1 is
 * simultaneously the "decline falls through to the launcher" scenario and the "cadence resumes from
 * the pre-preemption value" half of the accept scenario. The spec's "the next step without LLM
 * routing" is met in substance rather than literally — what matters to finding 3.3-1 is that the
 * step is not <i>preempted</i>, and a step that routes and declines is not.
 *
 * <h2>The budget gate, in both its states</h2>
 * {@code entry} runs on an activity the scenario never declares exhausted, so the block is not
 * entered at all; {@code primed} runs on one it does, so on steps 1–3 the block is entered, calls
 * {@code selectNewActionForTrivialActivity()}, gets null, and falls through — ahead of the LLM
 * hooks, the launcher and the chain, all of which still decide. Both states are asserted on the
 * tracker, because the two produce the same decision record: the block's precedence is real but
 * unobservable in a record, since the block never returns. Its trivial-action return is outside the
 * capture boundary (finding 6.1-b) — it needs a path over graph edges the driver records none of.
 *
 * <h2>Where the field assertions of task 7.2 live, and why some of them are one step long</h2>
 * The launcher's cadence counter and the stagnation stage's shot are never written by
 * {@link OracleDriver}, so both survive a multi-step run and are read at the end of the scenario.
 * {@code graphStableCounter} is not: the driver injects the step's scripted value at the <i>start</i>
 * of every step ({@code OracleDriver.java:136}), so after a four-step run the field holds step 3's
 * injection and a test reading it would be asserting the script rather than the ladder. The two
 * assertions about it — reset to 0 by a stagnation accept ({@code :503}), left alone by a stagnation
 * decline — are therefore made on <b>one-step scenarios</b>, where the value read after the run is
 * unambiguously what the ladder left. That costs no fidelity and needs no driver change: extending
 * the driver to snapshot fields per step would add a per-step observation the golden format has no
 * place for, in the stage where the driver is supposed to be finished.
 */
public class PreemptionGoldenTest {

    private static final long SEED = 8080L;

    private static final String PRESET = "llm_mop";
    private static final String SCENARIO = "preemption";

    /** The entry screen's activity: never declared exhausted, so the budget gate stays closed. */
    private static final String ENTRY_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    /**
     * The activity the scenario declares exhausted. It is deliberately <b>not</b> one of the MOP
     * fixture's census activities: the launcher's visited set is built from the graph's activity
     * nodes, so a screen borrowing a census name would starve the launcher of its own candidate
     * (group-6 finding, carried forward).
     */
    private static final String PRIMED_ACTIVITY = "br.unb.cic.cryptoapp.SideActivity";

    /** The census candidate {@code selectTriggerCandidate} returns first, at round-robin index 0. */
    private static final String EXPECTED_TRIGGER =
            "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity";

    /**
     * One below {@code Config.activityTriggerStagnationStep} (50): the launcher is due on the very
     * next non-preempted pass. Asserted against the Config default in
     * {@link #theSeededCadenceIsOneBelowTheConfiguredFiringPoint()} rather than left as a number
     * that happens to work.
     */
    private static final int DUE_CADENCE = 49;

    /** A non-zero {@code graphStableCounter}, so "unchanged by a decline" has something to say. */
    private static final int STABLE_COUNTER = 5;

    /**
     * Widgets outrank BACK/MENU so the EARLY_STAGE roulette keeps the walk on the targeted actions:
     * a targetless pick has no transition, and a scenario that silently stops moving looks exactly
     * like one that works (group-6 learning).
     */
    private static final int WIDGET_PRIORITY = 20;
    private static final int NAV_PRIORITY = 5;

    private static final String E0 = "//*[@resource-id='e0']";
    private static final String E1 = "//*[@resource-id='e1']";
    private static final String E2 = "//*[@resource-id='e2']";

    // The three hooks, in the textual order the ladder consults them (:480, :493, :508).
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
                                ScenarioScript.widget("//*[@resource-id='p0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='p1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='p2']", WIDGET_PRIORITY, false, 0.0F)))
                // Whichever entry widget the accept picks, the run arrives on `primed`.
                .transition("entry", E0, "primed")
                .transition("entry", E1, "primed")
                .transition("entry", E2, "primed")
                .stepsSinceLauncherFiring(DUE_CADENCE)
                .budgetExhausted(PRIMED_ACTIVITY)
                .steps(
                        // 0 — accept, on a first visit, while the launcher is due.
                        ScenarioScript.step(true, 0, ScenarioScript.accept(
                                true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)),
                        // 1 — first arrival on `primed`, so the new-state hook routes honestly;
                        // every verdict declines and all three hooks are consulted in order.
                        ScenarioScript.step(true, STABLE_COUNTER,
                                ScenarioScript.decline(true, true, true)),
                        // 2 — nothing scripted: the three predicates are consulted, none routes.
                        ScenarioScript.step(false, STABLE_COUNTER + 1),
                        // 3 — the stagnation hook is scripted again; the shot is already spent.
                        ScenarioScript.step(false, STABLE_COUNTER + 2,
                                ScenarioScript.decline(false, true, false)))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertSharedDefaults();
        LadderConfigGuard.assertMopDefaults();
    }

    @Test
    public void theSeededCadenceIsOneBelowTheConfiguredFiringPoint() {
        // The whole scenario rests on this arithmetic: seeded at 49, one increment reaches the
        // firing point exactly, and the equality at SataAgent.java:781 is what "due" means.
        assertEquals("ape.activityTriggerStagnationStep", DUE_CADENCE + 1,
                Config.activityTriggerStagnationStep);
    }

    @Test
    public void preemptionGoldenIsReproduced() throws Exception {
        ScenarioScript script = preemption();
        HookOrderRecorder recorder = new HookOrderRecorder(script);
        OracleSataAgent agent =
                OracleScaffold.newAgent(OracleScaffold.Preset.LLM_MOP, script, recorder);

        List<DecisionRecord> records = OracleDriver.run(agent, script, recorder);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());

        // --- step 0: the accept preempts a launcher that was due to fire
        DecisionRecord accepted = records.get(0);
        assertEquals("the new-state hook accepted", "accepted", accepted.getLlm());
        assertEquals("the accepting LLM stage labels the action", "LLM", accepted.getDecisionSource());
        assertEquals("through the LLM channel", "llm", accepted.getPickChannel());
        assertEquals("only the new-state hook was consulted — the ladder returned above the rest",
                Arrays.asList(NEW_STATE), recorder.consultedAt(0));

        // --- step 1: the decline falls through to the launcher, on the same step
        DecisionRecord launcher = records.get(1);
        assertEquals("every scripted verdict declined", "declined", launcher.getLlm());
        assertEquals("and the step fell past the LLM blocks into the launcher",
                "EVENT_TRIGGER_ACTIVITY", launcher.getActionType());
        assertEquals("with the census candidate as its target", EXPECTED_TRIGGER,
                launcher.getTarget());
        assertNull("a launcher return is not a ModelAction, so it carries no decision source",
                launcher.getDecisionSource());
        assertNull("nor a pick channel", launcher.getPickChannel());
        // Finding 3.3-1, read straight off the golden: the launcher was due at step 0 too. It fired
        // here instead, so step 0's accepted return left the counter at its pre-preemption value.
        assertEquals("hook order: new-state before stagnation before random",
                Arrays.asList(NEW_STATE, STAGNATION, RANDOM), recorder.consultedAt(1));
        assertEquals("and all three routed on this step", Arrays.asList(NEW_STATE, STAGNATION, RANDOM),
                recorder.routedAt(1));

        // --- steps 2 and 3: the SATA chain is the fallback
        for (int step = 2; step < records.size(); step++) {
            DecisionRecord fallback = records.get(step);
            assertEquals("step " + step + " routed nothing", "not_routed", fallback.getLlm());
            assertEquals("step " + step + " was decided by the SATA chain", "SATA",
                    fallback.getDecisionSource());
        }
        // Step 2 revisits a known screen with the episode's shot already spent, so the first two
        // hooks stop at their own conditions and never reach a gate — isNewState is false and the
        // stagnation flag is burned. Only the coin's hook gets that far, and the script gives it
        // nothing to route.
        assertEquals("an unscripted step consults the hooks whose own condition still holds",
                Arrays.asList(RANDOM), recorder.consultedAt(2));

        // --- the single shot, spent at step 1 and unavailable at step 3
        assertEquals("the stagnation hook does not even reach its gate once the shot is spent",
                Arrays.asList(RANDOM), recorder.consultedAt(3));
        assertTrue("and nothing routes: the script declares only the stagnation hook, which the"
                        + " stage's own flag has already closed",
                recorder.routedAt(3).isEmpty());
        assertTrue("and the episode's flag stays burned",
                stagnationShotBurned(agent));

        // --- the cadence counter, after the run: reset at the fire, then two ordinary passes
        assertEquals("the counter restarted from the firing point and advanced once per later step",
                2, cadenceCounter(agent));

        // --- the budget gate, in both its states
        ActivityBudgetTracker budget =
                (ActivityBudgetTracker) OracleScaffold.getField(agent, "_budgetTracker");
        assertTrue("the declared activity is exhausted, so the block is entered on steps 1-3",
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

    // ---- task 7.2: the field assertions the golden cannot make ----------------------------------

    /**
     * Finding 3.3-1 / INV-ORA-05, stated as a contrast rather than as a single number: two one-step
     * runs from the <i>same</i> seeded cadence, differing only in whether the LLM accepted. The
     * accepted one leaves the counter untouched; the unrouted one increments it into the firing
     * point, launches, and resets. Same input, two outcomes — which is what makes the first one an
     * observation about the ladder rather than about the scenario.
     */
    @Test
    public void anAcceptedStepDoesNotAdvanceTheLauncherCadence() throws Exception {
        ScenarioScript preempted = oneStep("cadence-preempted", DUE_CADENCE,
                ScenarioScript.step(true, 0, ScenarioScript.accept(
                        true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)));
        ScriptedLlm preemptedLlm = new ScriptedLlm(preempted);
        OracleSataAgent accepting = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM_MOP, preempted, preemptedLlm);

        List<DecisionRecord> acceptedRecords =
                OracleDriver.run(accepting, preempted, preemptedLlm);

        assertEquals("the step was preempted by the LLM", "accepted", acceptedRecords.get(0).getLlm());
        assertEquals("so the increment at SataAgent.java:522 was never reached",
                DUE_CADENCE, cadenceCounter(accepting));

        ScenarioScript unpreempted =
                oneStep("cadence-unpreempted", DUE_CADENCE, ScenarioScript.step(true, 0));
        ScriptedLlm unpreemptedLlm = new ScriptedLlm(unpreempted);
        OracleSataAgent unrouted = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM_MOP, unpreempted, unpreemptedLlm);

        List<DecisionRecord> unroutedRecords =
                OracleDriver.run(unrouted, unpreempted, unpreemptedLlm);

        assertEquals("the same seeded cadence, unpreempted, reaches the firing point",
                "EVENT_TRIGGER_ACTIVITY", unroutedRecords.get(0).getActionType());
        assertEquals("and the block resets the counter at the firing point (:529)",
                0, cadenceCounter(unrouted));
    }

    /**
     * The episode's single shot is spent on the <i>consultation</i>, not on the verdict
     * ({@code SataAgent.java:499} runs before {@code selectAction}), and a decline leaves
     * {@code graphStableCounter} where it was — the reset at {@code :503} is on the accept path
     * only. See the class javadoc for why this is a one-step scenario.
     */
    @Test
    public void aStagnationDeclineBurnsTheShotAndLeavesTheCounterUnchanged() throws Exception {
        ScenarioScript script = oneStep("stagnation-decline", 0,
                ScenarioScript.step(false, STABLE_COUNTER,
                        ScenarioScript.decline(false, true, false)));
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM_MOP, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("the stagnation hook was consulted and declined",
                "declined", records.get(0).getLlm());
        assertTrue("the shot is burned by the consultation, whatever the answer",
                stagnationShotBurned(agent));
        assertEquals("a decline does not reset the counter",
                STABLE_COUNTER, intField(agent, "graphStableCounter"));
        assertEquals("and the step still produced a decision, below the LLM blocks",
                "SATA", records.get(0).getDecisionSource());
    }

    /** The other half of the same rule: {@code graphStableCounter} is reset by an accept, at {@code :503}. */
    @Test
    public void aStagnationAcceptResetsTheCounter() throws Exception {
        ScenarioScript script = oneStep("stagnation-accept", 0,
                ScenarioScript.step(false, STABLE_COUNTER, ScenarioScript.accept(
                        false, true, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)));
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM_MOP, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("the stagnation hook accepted", "accepted", records.get(0).getLlm());
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
     * <p>It lives here rather than in {@code ScriptedLlm} on purpose: the four committed baselines
     * were captured with that class as it stands, and observation added to a shared class is one
     * edit away from being behavior added to it. A local subclass cannot touch them.
     *
     * <p><b>What "consulted" means here is now sharper than it was.</b> The gate is each stage's
     * last conjunct, so a hook records only when the shared precondition passed <em>and</em> the
     * stage's own condition held. Before the trigger predicates moved into the stages, the stub's
     * predicates were the first thing each hook called and every hook recorded on every step that
     * cleared the precondition. The order this observes is unchanged; the population is smaller,
     * and the assertions below say which hooks are absent and why.
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
