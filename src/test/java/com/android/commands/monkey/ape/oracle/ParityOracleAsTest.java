package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The {@code as} cell's baseline golden (isolate-guidance-groups 11.3): ADDONS and STATIC, no MOP
 * and no LLM — the full tool without specifications, the control of the C2 contrast. Its ADDONS
 * block is the former {@code aperv} preset, and the scenario is the one that preset's golden walked.
 *
 * <h2>What the scenario is built to walk</h2>
 * Three screens, chosen so the chain descends rather than repeating its first rung:
 * <ol>
 *   <li><b>{@code entry}</b> — a fresh screen: unvisited, unsaturated widgets, selected through
 *       EARLY_STAGE ({@code roulette_early}).</li>
 *   <li><b>{@code settled}</b> — every widget visited <i>and</i> saturated, BACK and MENU visited,
 *       so the chain falls to the epsilon-greedy rung.</li>
 *   <li><b>{@code budgeted}</b> — the same shape, on an activity whose budget the scenario declares
 *       exhausted (design D2).</li>
 * </ol>
 *
 * <h2>The budget block, pinned at its gate</h2>
 * The first steps run with the block's gate <b>closed</b> — their activities are never registered,
 * and {@code ActivityBudgetTracker.isBudgetExhausted} answers false for an unregistered activity.
 * The last two run with it <b>open</b>: the block is entered, gets no trivial action, and falls
 * through to the same SATA chain. Both states are asserted on the tracker, because the two produce
 * the <i>same</i> decision record. The block's trivial-action return is outside the capture
 * boundary (finding 6.1-b).
 *
 * <h2>The component trigger</h2>
 * The STATIC block mounts it at {@code ape.componentPercentage=0.03}. Its coin {@code u_C} comes
 * from {@code StepRng}'s component stream, and the scenario's seed puts {@code u_C < 0.03} on step 4,
 * so that step carries the fixture's only target, a provider, as {@code trig}: a side effect beside
 * the action SATA still selected (design D-B14.5).
 */
public class ParityOracleAsTest {

    private static final long SEED = 4243L;

    private static final String PRESET = "as";
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
                .budgetExhausted(BUDGETED)
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
        LadderConfigGuard.assertCellValues(OracleScaffold.Cell.AS);
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Cell.AS, script, null);

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

        assertEquals("u_C < 0.03 on step 4 fires the component trigger, which decides nothing",
                "provider:androidx.startup.InitializationProvider", records.get(4).getTrig());
        assertEquals("the step is still SATA's", "SATA", records.get(4).getDecisionSource());

        ActivityBudgetTracker budget =
                (ActivityBudgetTracker) OracleScaffold.getField(agent, "_budgetTracker");
        assertTrue("the declared activity's budget is exhausted, so the block is entered",
                budget.isBudgetExhausted(BUDGETED));
        assertFalse("an unregistered activity reports no exhaustion, so the block is not entered",
                budget.isBudgetExhausted(MAIN));

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, OracleScaffold.MOP_FIXTURE, GoldenFile.capturedAt()),
                records);
    }
}
