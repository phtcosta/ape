package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The RNG neutrality gate (isolate-guidance-groups 11.5, decision-pipeline INV-DP-13): mounting or
 * unmounting a gate, or setting a coin's dose to 0, moves no draw of the streams SATA decides with,
 * so SATA's choice sequence on a fixed script is the same.
 *
 * <p>Each coin is drawn at step open from its own {@code StepRng} stream whether or not its stage is
 * assembled or reached, and a gate is a pure predicate that draws nothing. The script gives the LLM
 * nothing to accept, so every step is SATA's in both replays and the records compare whole.
 */
public class RngNeutralityTest {

    private static final long SEED = 1357L;

    private static final String MAIN = "com.example.MainActivity";
    private static final String NARROW = "com.example.NarrowActivity";

    private static final int WIDGET_PRIORITY = 20;
    private static final int NAV_PRIORITY = 5;

    /**
     * A screen with three widgets and BACK/MENU, and one with BACK/MENU alone, so the minimum-actions
     * gate (3) admits on the first and bars on the second — the gate has something to decide in one
     * replay and is absent in the other. The run reaches the second screen and stays there, its
     * actions carrying no target to move on by.
     */
    private static ScenarioScript script() {
        return ScenarioScript.named("rng-neutrality", SEED)
                .screens(
                        ScenarioScript.screen("wide", MAIN, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='a0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='a1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='a2']", WIDGET_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("narrow", NARROW, NAV_PRIORITY, true))
                .transition("wide", "//*[@resource-id='a0']", "narrow")
                .transition("wide", "//*[@resource-id='a1']", "narrow")
                .transition("wide", "//*[@resource-id='a2']", "narrow")
                // Every step names the coin stage and the script refuses it: no call is ever made.
                .steps(
                        ScenarioScript.step(true, 0, ScenarioScript.decline(false, false, false)),
                        ScenarioScript.step(true, 0, ScenarioScript.decline(false, false, false)),
                        ScenarioScript.step(false, 0, ScenarioScript.decline(false, false, false)),
                        ScenarioScript.step(false, 0, ScenarioScript.decline(false, false, false)),
                        ScenarioScript.step(false, 0, ScenarioScript.decline(false, false, false)),
                        ScenarioScript.step(false, 0, ScenarioScript.decline(false, false, false)))
                .build();
    }

    private static List<DecisionRecord> replay(OracleScaffold.Cell cell, String... overrides)
            throws Exception {
        ScenarioScript script = script();
        ScriptedLlm llm = cell.hasLlm() ? new ScriptedLlm(script) : null;
        OracleSataAgent agent = OracleScaffold.newAgent(cell, script, llm, null, overrides);
        return llm == null ? OracleDriver.run(agent, script) : OracleDriver.run(agent, script, llm);
    }

    private static void assertSameSataSequence(List<DecisionRecord> mounted,
            List<DecisionRecord> unmounted) {
        assertEquals(mounted.size(), unmounted.size());
        for (int i = 0; i < mounted.size(); i++) {
            assertEquals("step " + i + " is SATA's", "SATA", mounted.get(i).getDecisionSource());
            assertEquals("step " + i + " moved", mounted.get(i).getTarget() + "|"
                            + mounted.get(i).getActionType() + "|" + mounted.get(i).getPickChannel(),
                    unmounted.get(i).getTarget() + "|" + unmounted.get(i).getActionType() + "|"
                            + unmounted.get(i).getPickChannel());
        }
    }

    @Test
    public void unmountingTheMinimumActionsGateMovesNoSataChoice() throws Exception {
        List<DecisionRecord> gated = replay(OracleScaffold.Cell.AS_LLM);
        List<DecisionRecord> ungated = replay(OracleScaffold.Cell.AS_LLM,
                "ape.llmGateMinActions", "0");
        assertSameSataSequence(gated, ungated);
    }

    @Test
    public void theMopCoinAtZeroMovesNoSataChoice() throws Exception {
        // No screen carries a MOP weight, so the coin never selects at 1.0 either: the two
        // replays differ only in whether the stage is assembled, while u_M is drawn in both.
        List<DecisionRecord> coin = replay(OracleScaffold.Cell.AS_MOP);
        List<DecisionRecord> noCoin = replay(OracleScaffold.Cell.AS_MOP, "ape.mopCoinPercentage", "0");
        assertSameSataSequence(coin, noCoin);
        assertTrue("the replay walks more than one rung", coin.size() > 1);
    }
}
