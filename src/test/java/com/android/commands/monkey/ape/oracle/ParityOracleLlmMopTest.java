package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * rearch-01 task 6.4 — the {@code llm_mop} preset's baseline golden: both axes at once, which is
 * the arm the decisive comparison was run on.
 *
 * <h2>What the four steps interleave</h2>
 * <ol>
 *   <li><b>accept</b> on the fresh screen — the LLM preempts everything below it, including the
 *       launcher block. The cadence counter is therefore <i>not</i> advanced on this step: the
 *       increment is the first statement of a block the ladder returned above (finding 3.3-1). The
 *       golden records the accepted action; the counter's behavior is asserted directly by the
 *       preemption golden (task 7.2, INV-ORA-05), not here.</li>
 *   <li><b>decline</b> — the stagnation hook routes, answers null, and the step falls past the
 *       launcher (the counter reaches 49, one short of the cadence) into the SATA chain, where the
 *       declared MOP boost converts into a {@code MOP} / {@code short_circuit_unvisited} pick. One
 *       record showing both axes: a consulted LLM and a MOP-decided action.</li>
 *   <li><b>not_routed</b> — no consultation, the counter reaches the cadence, and the launcher
 *       fires. This is the step that pins the launcher's precedence over the SATA chain.</li>
 *   <li><b>timeout</b> — the counter restarts from the fire's reset, so no launcher; the boosted
 *       widget is visited by now, so no short-circuit; the chain settles on the epsilon-greedy
 *       rung.</li>
 * </ol>
 *
 * <p>The seeded cadence (two below the fire) is what makes that sequence fit in four steps instead
 * of fifty — a declared scenario input, per design D2, not a lowered Config default.
 */
public class ParityOracleLlmMopTest {

    private static final long SEED = 7070L;

    private static final String PRESET = "llm_mop";
    private static final String SCENARIO = "baseline";

    private static final String HOST_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";
    private static final String EXPECTED_TRIGGER =
            "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity";

    private static final int SEEDED_CADENCE = 48;

    private static final int BOOSTED_PRIORITY = 20;
    private static final int PLAIN_PRIORITY = 12;
    private static final int NAV_PRIORITY = 5;
    private static final int DECLARED_BOOST = 7;

    private static ScenarioScript baseline() {
        return ScenarioScript.named(SCENARIO, SEED)
                .screens(
                        ScenarioScript.screen("home", HOST_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='h0']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h1']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h2']", BOOSTED_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("mopish", HOST_ACTIVITY, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='m_boost']",
                                        BOOSTED_PRIORITY, false, 1.0F, DECLARED_BOOST),
                                ScenarioScript.widget("//*[@resource-id='m1']", PLAIN_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='m2']", PLAIN_PRIORITY, true, 1.0F)))
                .transition("home", "//*[@resource-id='h0']", "mopish")
                .transition("home", "//*[@resource-id='h1']", "mopish")
                .transition("home", "//*[@resource-id='h2']", "mopish")
                .stepsSinceLauncherFiring(SEEDED_CADENCE)
                .steps(
                        ScenarioScript.step(true, 0, ScenarioScript.accept(
                                true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)),
                        ScenarioScript.step(false, 5, ScenarioScript.decline(false, true, false)),
                        ScenarioScript.step(false, 6),
                        ScenarioScript.step(false, 7, ScenarioScript.timeout(false, false, true)))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertSharedDefaults();
        LadderConfigGuard.assertMopDefaults();
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM_MOP, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());
        assertEquals("the new-state hook accepted", "accepted", records.get(0).getLlm());
        assertEquals("an accepted action is labelled by the accepting LLM stage",
                "LLM", records.get(0).getDecisionSource());

        DecisionRecord declined = records.get(1);
        assertEquals("the stagnation hook was consulted and declined",
                "declined", declined.getLlm());
        assertEquals("and the step fell through to the declared MOP boost",
                "MOP", declined.getDecisionSource());
        assertEquals("through the unvisited-MOP short-circuit",
                "short_circuit_unvisited", declined.getPickChannel());

        DecisionRecord launcher = records.get(2);
        assertEquals("an unrouted step reaches the launcher block",
                "not_routed", launcher.getLlm());
        assertEquals("and the seeded cadence fires it there",
                "EVENT_TRIGGER_ACTIVITY", launcher.getActionType());
        assertEquals("with the census candidate as its target",
                EXPECTED_TRIGGER, launcher.getTarget());
        assertNull("a launcher return carries no decision source", launcher.getDecisionSource());

        assertEquals("the random hook timed out", "timeout", records.get(3).getLlm());

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, OracleScaffold.MOP_FIXTURE, GoldenFile.capturedAt()),
                records);
    }
}
