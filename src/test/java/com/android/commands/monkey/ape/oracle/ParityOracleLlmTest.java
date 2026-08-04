package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * rearch-01 task 6.3 — the {@code llm} preset's baseline golden: a scripted router, no MOP data.
 *
 * <h2>All three hooks, all three verdicts, and the fourth label</h2>
 * The four steps are one of each, in the order the ladder consults them:
 * <ol>
 *   <li><b>new-state accept</b> — the hook routes because the step declares a first visit
 *       ({@code shouldRouteNewState} honors {@code _isNewState}, it does not merely obey the
 *       script), and the accepted action comes back labelled {@code LLM} / {@code llm} by
 *       the accepting stage. The selected action is a member of the offered list, never a
 *       synthesized tap: resolving a {@code MODEL_LLM_TAP} would leave the JVM.</li>
 *   <li><b>stagnation decline</b> — the hook routes, the verdict is null, the ladder falls through
 *       to the SATA chain. The record still carries {@code llm:"declined"}: the consultation
 *       happened and the golden says so.</li>
 *   <li><b>random timeout</b> — the same observable as a decline (null, fall-through), a different
 *       provenance. That distinction exists only in the golden, which is exactly why the golden
 *       records it (design D3).</li>
 *   <li><b>no entry</b> — {@code not_routed}: a router was present and the step passed it by. That
 *       is a different claim from the field being <i>absent</i>, which is what an {@code aperv}
 *       golden says, and only a preset that has a router can make it.</li>
 * </ol>
 *
 * <p>The screens stay unsaturated throughout, so every fall-through lands on EARLY_STAGE. That is
 * deliberate: the chain's descent is the {@code aperv} golden's subject, and mixing the two axes
 * into one scenario would make a diff harder to read, not richer.
 */
public class ParityOracleLlmTest {

    private static final long SEED = 6060L;

    private static final String PRESET = "llm";
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
                                true, false, false, ScriptedLlmRouter.FIRST_UNVISITED_TARGETED)),
                        ScenarioScript.step(true, 5, ScenarioScript.decline(false, true, false)),
                        ScenarioScript.step(false, 6, ScenarioScript.timeout(false, false, true)),
                        ScenarioScript.step(false, 7))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertSharedDefaults();
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.LLM, script, new ScriptedLlmRouter(script));

        List<DecisionRecord> records = OracleDriver.run(agent, script);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());
        assertEquals("the new-state hook accepted", "accepted", records.get(0).getLlm());
        assertEquals("an accepted action is labelled by the accepting LLM stage",
                "LLM", records.get(0).getDecisionSource());
        assertEquals("through the llm pick channel", "llm", records.get(0).getPickChannel());
        assertEquals("the stagnation hook declined", "declined", records.get(1).getLlm());
        assertEquals("the random hook timed out", "timeout", records.get(2).getLlm());
        assertEquals("a step the router was consulted about and passed by",
                "not_routed", records.get(3).getLlm());
        for (int step = 1; step < records.size(); step++) {
            assertEquals("a null verdict falls through to the SATA chain",
                    "roulette_early", records.get(step).getPickChannel());
        }

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, null, GoldenFile.capturedAt()), records);
    }
}
