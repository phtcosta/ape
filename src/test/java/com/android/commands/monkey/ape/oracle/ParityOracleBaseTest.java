package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The {@code base} cell's baseline golden (isolate-guidance-groups 11.3, 11.6): no group, the
 * oracle's reference for upstream APE (parity-oracle INV-ORA-09).
 *
 * <p>The scenario is the {@code as} cell's: a fresh screen, a settled one and a third, every widget
 * of a screen leading to the next. With no group mounted there is no budget, no BACK/MENU cap, no
 * dynamic epsilon and no component trigger, so the chain is SATA's alone: EARLY_STAGE on the fresh
 * screen, then both legs of the epsilon-greedy rung.
 *
 * <p><b>X1 at the oracle level.</b> With zero scorers mounted, a least-visited tie is decided as
 * upstream's {@code model/State.java:185-190} decides it: the first least-visited action of the
 * array. On step 2 the third screen's widgets and navigation are all visited once, so the pick is
 * {@code b0}. X3 and X4 need graph paths, which the driver does not record (finding 6.1-b); their
 * base-cell parity is pinned with synthetic ties in {@code SataExtensionPointTest}
 * ({@code x3ApeKeepsTheIncumbentAndDrawsNothing}, {@code x4ApeConsumesExactlyOneHelperDrawWith
 * MoreThanOnePath}).
 */
public class ParityOracleBaseTest {

    private static final long SEED = 4343L;

    private static final String PRESET = "base";
    private static final String SCENARIO = "baseline";

    private static final String MAIN = "com.example.MainActivity";
    private static final String SETTLED = "com.example.SettledActivity";
    private static final String BUDGETED = "com.example.BudgetedActivity";

    /**
     * Widgets outrank BACK/MENU on the settled screens so the least-visited pick, which breaks its
     * visit-count tie by priority, lands on a widget and the scenario keeps moving. The declared
     * priority is a scenario input like any other (finding 1.2-b) — nothing below the oracle's
     * entry point would have assigned one.
     */
    private static final int WIDGET_PRIORITY = 20;
    private static final int NAV_PRIORITY = 5;

    private static ScenarioScript baseline() {
        return ScenarioScript.named(SCENARIO, SEED)
                .screens(
                        // A genuinely fresh screen: nothing visited, nothing saturated. BACK and
                        // MENU are unsaturated too — isSaturated() is visit-based for targetless
                        // actions — so the EARLY_STAGE priority roulette offers them alongside the
                        // widgets; the low navigation priority is what keeps the walk on the
                        // widgets, and it is a declared input like every other.
                        ScenarioScript.screen("entry", MAIN, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='e0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='e1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='e2']", WIDGET_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("settled", SETTLED, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='s0']", WIDGET_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='s1']", WIDGET_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='s2']", WIDGET_PRIORITY, true, 1.0F)),
                        ScenarioScript.screen("budgeted", BUDGETED, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='b0']", WIDGET_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='b1']", WIDGET_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='b2']", WIDGET_PRIORITY, true, 1.0F)))
                // Every widget of a screen leads to the next one, so the walk does not depend on
                // which widget the rung picked — the scenario declares the topology, the ladder
                // declares the choice.
                .transition("entry", "//*[@resource-id='e0']", "settled")
                .transition("entry", "//*[@resource-id='e1']", "settled")
                .transition("entry", "//*[@resource-id='e2']", "settled")
                .transition("settled", "//*[@resource-id='s0']", "budgeted")
                .transition("settled", "//*[@resource-id='s1']", "budgeted")
                .transition("settled", "//*[@resource-id='s2']", "budgeted")
                // Only the entry screen is a first visit. `settled` and `budgeted` carry
                // pre-visited actions, so `_isNewState` is false there: the flag is the script's
                // stand-in for what `updateStateInternal` computed before the visit mark erased it,
                // and claiming a first visit on a screen whose actions are already visited would be
                // a claim the state itself contradicts.
                .steps(
                        ScenarioScript.step(true, 0),
                        ScenarioScript.step(false, 0),
                        ScenarioScript.step(false, 0),
                        ScenarioScript.step(false, 1),
                        ScenarioScript.step(false, 2))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertCellValues(OracleScaffold.Cell.BASE);
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Cell.BASE, script, null);

        List<DecisionRecord> records = OracleDriver.run(agent, script);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());
        assertEquals("a fresh state selects through EARLY_STAGE",
                "roulette_early", records.get(0).getPickChannel());
        boolean leastVisited = false;
        boolean roulette = false;
        for (int step = 1; step < records.size(); step++) {
            String channel = records.get(step).getPickChannel();
            leastVisited = leastVisited || "sata_other".equals(channel);
            roulette = roulette || "roulette_greedy".equals(channel);
            assertTrue("step " + step + " descends to the epsilon-greedy rung, not above it",
                    "sata_other".equals(channel) || "roulette_greedy".equals(channel));
        }
        // Both legs of the epsilon-greedy rung, in one golden. They are the two sides of the
        // `egreedy()` coin — the least-visited pick and the priority roulette — and both sit behind
        // the one production line this change edits (INV-ORA-01), which is why a golden that
        // exercised only one of them would leave half of stage 3's highest-leverage surface unpinned.
        assertTrue("the golden pins the least-visited leg", leastVisited);
        assertTrue("the golden pins the priority-roulette leg", roulette);
        for (DecisionRecord record : records) {
            assertNull("a cell without an LLM has no llm axis at all", record.getLlm());
            assertFalse("the budget block's trivial return is outside the boundary (6.1-b)",
                    "Budget".equals(record.getDecisionSource()));
        }

        assertEquals("X1 with zero scorers: the first least-visited action of the array",
                "//*[@resource-id='b0']", records.get(2).getTarget());
        assertEquals("through the least-visited pick", "sata_other", records.get(2).getPickChannel());
        for (DecisionRecord record : records) {
            assertNull("BASE mounts no component trigger", record.getTrig());
        }
        assertNull("BASE mounts no activity budget, so no BUDGET stage reads a tracker",
                OracleScaffold.getField(agent, "_budgetTracker"));

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, null, GoldenFile.capturedAt()), records);
    }
}
