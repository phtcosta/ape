package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The {@code as_llm} cell's baseline golden (isolate-guidance-groups 11.3): ADDONS, STATIC and a
 * scripted LLM, no MOP.
 *
 * <h2>The three LLM stages, the three verdicts, and the fourth label</h2>
 * <ol>
 *   <li><b>new-state accept</b> — the stage's trigger holds because the step declares a first
 *       visit, and the accepted action comes back owned by {@code LLM} on the {@code llm} channel.
 *       The selected action is a member of the offered list, never a synthesized tap: resolving a
 *       {@code MODEL_LLM_TAP} would leave the JVM.</li>
 *   <li><b>stagnation decline</b> — the new-state trigger holds again but the script refuses it at
 *       the gate, so no call is made; the stagnation stage calls, the verdict is null, and the step
 *       falls through to the SATA chain with {@code llm:"declined"}.</li>
 *   <li><b>coin timeout</b> — the same observable as a decline, a different provenance, which only
 *       the golden records (design D3).</li>
 *   <li><b>no entry</b> — {@code not_routed}: an LLM was present and the step passed it by, which
 *       is a different claim from the field being absent. The component trigger fires on this step
 *       ({@code u_C < 0.03} under the scenario's seed), beside SATA's pick.</li>
 * </ol>
 *
 * <p>The screens stay unsaturated throughout, so every fall-through lands on EARLY_STAGE: the
 * chain's descent is the {@code as} golden's subject.
 */
public class ParityOracleAsLlmTest {

    private static final long SEED = 6075L;

    private static final String PRESET = "as_llm";
    private static final String SCENARIO = "baseline";

    private static final String MAIN = "com.example.MainActivity";
    private static final String SECOND = "com.example.SecondActivity";

    private static final int WIDGET_PRIORITY = 20;
    private static final int NAV_PRIORITY = 5;

    private static ScenarioScript baseline() {
        return ScenarioScript.named(SCENARIO, SEED)
                .screens(
                        ScenarioScript.screen("home", MAIN, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='h0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h2']", WIDGET_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("second", SECOND, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='s0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='s1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='s2']", WIDGET_PRIORITY, false, 0.0F)))
                .transition("home", "//*[@resource-id='h0']", "second")
                .transition("home", "//*[@resource-id='h1']", "second")
                .transition("home", "//*[@resource-id='h2']", "second")
                .steps(
                        ScenarioScript.step(true, 0, ScenarioScript.accept(
                                true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)),
                        ScenarioScript.step(true, 5, ScenarioScript.decline(false, true, false)),
                        ScenarioScript.step(false, 6, ScenarioScript.timeout(false, false, true)),
                        ScenarioScript.step(false, 7))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertCellValues(OracleScaffold.Cell.AS_LLM);
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        ScriptedLlm llm = new ScriptedLlm(script);
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Cell.AS_LLM, script, llm);

        List<DecisionRecord> records = OracleDriver.run(agent, script, llm);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());
        assertEquals("the new-state hook accepted", "accepted", records.get(0).getLlm());
        assertEquals("an accepted action is labelled by the accepting LLM stage",
                "LLM", records.get(0).getDecisionSource());
        assertEquals("through the llm pick channel", "llm", records.get(0).getPickChannel());
        assertEquals("the stagnation hook declined", "declined", records.get(1).getLlm());
        assertEquals("the random hook timed out", "timeout", records.get(2).getLlm());
        assertEquals("a step the script was consulted about and passed by",
                "not_routed", records.get(3).getLlm());
        for (int step = 1; step < records.size(); step++) {
            assertEquals("a null verdict falls through to the SATA chain",
                    "roulette_early", records.get(step).getPickChannel());
        }

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, OracleScaffold.MOP_FIXTURE, GoldenFile.capturedAt()),
                records);
    }
}
