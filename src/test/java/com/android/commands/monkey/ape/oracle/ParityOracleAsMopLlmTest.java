package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The {@code as_mop_llm} cell's baseline golden (isolate-guidance-groups 11.3): all four groups.
 *
 * <h2>What the four steps interleave</h2>
 * <ol>
 *   <li><b>accept</b> on the fresh screen — the LLM decides before the launcher is reached, and the
 *       cadence counter advances anyway (design D-B11).</li>
 *   <li><b>decline</b> — the stagnation stage calls and gets null; the counter is past the cadence,
 *       so the launcher fires. One record carrying both a consulted LLM and the launcher.</li>
 *   <li><b>not_routed</b> — the counter restarted at the fire, so the launcher is quiet and the MOP
 *       coin takes the declared weight.</li>
 *   <li><b>timeout</b> — the coin call times out and the MOP coin takes the weight again, the
 *       driver writing no ledger; the component trigger fires on this step under the seed.</li>
 * </ol>
 *
 * <p>The seeded cadence (two below the fire) is what makes that sequence fit in four steps instead
 * of fifty — a declared scenario input, per design D2, not a lowered default.
 */
public class ParityOracleAsMopLlmTest {

    private static final long SEED = 7070L;

    private static final String PRESET = "as_mop_llm";
    private static final String SCENARIO = "baseline";

    private static final String HOST_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";
    /** The census activity nearest to an unretired target (distance mode, design D20). */
    private static final String EXPECTED_TRIGGER =
            "br.unb.cic.cryptoapp.generated.CryptographyActivity";

    private static final int SEEDED_CADENCE = 48;

    private static final int BOOSTED_PRIORITY = 20;
    private static final int PLAIN_PRIORITY = 12;
    private static final int NAV_PRIORITY = 5;
    private static final int DECLARED_WEIGHT = 7;

    private static ScenarioScript baseline() {
        return ScenarioScript.named(SCENARIO, SEED)
                .screens(
                        ScenarioScript.screen("home", HOST_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='h0']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h1']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h2']", BOOSTED_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("mopish", HOST_ACTIVITY, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='m_boost']",
                                        BOOSTED_PRIORITY, false, 1.0F, DECLARED_WEIGHT),
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
        LadderConfigGuard.assertCellValues(OracleScaffold.Cell.AS_MOP_LLM);
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Cell.AS_MOP_LLM, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());
        assertEquals("the new-state hook accepted", "accepted", records.get(0).getLlm());
        assertEquals("an accepted action is labelled by the accepting LLM stage",
                "LLM", records.get(0).getDecisionSource());

        DecisionRecord declined = records.get(1);
        assertEquals("the stagnation hook was consulted and declined",
                "declined", declined.getLlm());
        assertEquals("and the cadence, advanced on the accepted step too, fires the launcher",
                "EVENT_TRIGGER_ACTIVITY", declined.getActionType());
        assertEquals("with the nearest census candidate as its target",
                EXPECTED_TRIGGER, declined.getTarget());
        assertNull("a launcher return carries no decision source", declined.getDecisionSource());

        DecisionRecord coin = records.get(2);
        assertEquals("an unrouted step reaches the MOP coin", "not_routed", coin.getLlm());
        assertEquals("which takes the declared weight", "MOP", coin.getDecisionSource());
        assertEquals("on its own channel", "mop_coin", coin.getPickChannel());

        assertEquals("the random hook timed out", "timeout", records.get(3).getLlm());

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, OracleScaffold.MOP_FIXTURE, GoldenFile.capturedAt()),
                records);
    }
}
