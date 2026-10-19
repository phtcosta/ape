package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The per-cell guard the spec requires ("Config default drift is caught by the guard, not the
 * golden").
 *
 * <p>A cell is realized by its injection profile over <b>jar-default</b> Config and the cell's
 * resolved {@link RunSpec} (design D2), so every golden silently depends on those values. Without a guard, changing one would surface as
 * an unexplained golden divergence — the reader would go hunting through the ladder for a decision
 * that changed, when what changed is an input. Each assertion here names the key, so the failure
 * says which default moved.
 *
 * <p><b>The boundary of what belongs here</b> is exactly "the values the selection ladder reads",
 * and it is easy to over-read. The scoring weights ({@code mopWeight*}, {@code frontierBoostWeight},
 * {@code coverageBoostWeight}, {@code formCompletionEnabled}) are <b>not</b> among them: scoring
 * happens in {@code adjustActionsByGUITree()}, above this oracle's entry point, so no golden record
 * can depend on one. Their guard belongs to {@code rearch-03} INV-ARCH-12.
 *
 * <p><b>Two keys the harness deliberately moves off the cell's values</b>
 * ({@code OracleScaffold.planOf} records the pair as INV-ORA-07 injection-profile adaptations).
 * {@code ape.llmPercentage=1.0}: at the cell's 0.9 a scripted coin consultation would be lost on
 * one step in ten, which the script cannot foresee, so the guard asserts the harness's value.
 * {@code ape.graphStableRestartThreshold=10}: the stagnation stage evaluates the real midpoint
 * predicate, which at the jar default of 100 would sit at 50 while every scenario's counter is 0, 5,
 * 6 or 7. Lowering it cannot trigger a forced restart, because {@code onGraphStable} is reached only
 * from {@code checkStable()} at the end of {@code updateStateInternal} and this oracle drives
 * {@code ladder()} directly — so no golden depends on that default either.
 */
final class LadderConfigGuard {

    private LadderConfigGuard() {
    }

    /**
     * The values {@code cell}'s ladder reads: the jar-default {@code Config} values outside the
     * plan, which every cell shares, and the cell plan's values (parity-oracle "Per-Preset Golden
     * Decision-Sequence Capture"), asserted against the plan the scaffold builds the cell over.
     */
    static void assertCellValues(OracleScaffold.Cell cell) {
        RunSpec spec = OracleScaffold.planOf(cell, 0L); // no ladder value depends on the seed
        boolean addons = spec.has(Feature.ADDONS);
        // The BUDGET stage: assembled iff the ADDONS member is mounted, with the budget arithmetic a
        // scenario's declared exhaustion has to reach (design D2).
        assertEquals(cell + " ape.activityBudgetEnabled", addons, spec.has(Feature.ACTIVITY_BUDGET));
        if (spec.has(Feature.ACTIVITY_BUDGET)) {
            assertEquals("ape.activityBaseBudget", 50,
                    spec.exploration().integer("ape.activityBaseBudget"));
            assertEquals("ape.activityBudgetPerWidget", 5,
                    spec.exploration().integer("ape.activityBudgetPerWidget"));
        }
        // The trivial-activity rung: at this threshold a scenario's handful of activity nodes
        // yields an empty set, which is what keeps the rung a fall-through (finding 6.1-b).
        assertEquals("ape.trivialActivityRankThreshold", 3,
                spec.exploration().trivialActivityRankThreshold());
        assertFalse("ape.doBackToTrivialActivity", spec.exploration().doBackToTrivialActivity());
        // The SATA chain's own reads: the action set a state carries, how EARLY_STAGE decides what
        // is still worth visiting, and the epsilon-greedy rung's cap and coin.
        assertEquals(cell + " ape.modelMenuEnabled", addons, spec.has(Feature.MODEL_MENU));
        assertTrue("ape.useActionDiffer", spec.exploration().useActionDiffer());
        assertEquals(cell + " ape.backMenuPickCap", addons ? 3 : 0,
                spec.exploration().backMenuPickCap());
        assertEquals(cell + " ape.dynamicEpsilon", addons, spec.exploration().dynamicEpsilon());
        assertEquals("ape.defaultEpsilon", 0.05, spec.exploration().defaultEpsilon(), 0.0);
        if (spec.exploration().dynamicEpsilon()) {
            assertEquals("ape.minEpsilon", 0.02, spec.exploration().minEpsilon(), 0.0);
            assertEquals("ape.maxEpsilon", 0.15, spec.exploration().maxEpsilon(), 0.0);
        }
        // The component trigger: the STATIC block mounts it at 0.03; its coin is u_C, drawn from
        // its own stream, so it moves no other draw.
        assertEquals(cell + " ape.componentPercentage", spec.has(Feature.STATIC),
                spec.has(Feature.COMPONENT_TRIGGER));
        if (spec.has(Feature.COMPONENT_TRIGGER)) {
            assertEquals("ape.componentPercentage", 0.03,
                    spec.staticData().componentPercentage(), 0.0);
        }
        if (spec.has(Feature.MOP)) {
            // The launcher's cadence the scenario's declared counter is seeded against, and the
            // MOP coin's dose.
            assertTrue("ape.activityTriggerEnabled", spec.has(Feature.MOP_LAUNCHER));
            assertEquals("ape.activityTriggerStagnationStep", 50,
                    spec.mop().activityTriggerStagnationStep());
            assertEquals("ape.activityTriggerMaxPerRun", 0, spec.mop().activityTriggerMaxPerRun());
            assertEquals("ape.mopCoinPercentage", 1.0, spec.mop().coinPercentage(), 0.0);
        }
        if (spec.has(Feature.LLM)) {
            assertEquals("ape.llmPercentage (harness)", 1.0, spec.llm().dbl("ape.llmPercentage"),
                    0.0);
            assertEquals("ape.llmGateMinActions", 3, spec.llm().gateMinActions());
        }
    }
}
