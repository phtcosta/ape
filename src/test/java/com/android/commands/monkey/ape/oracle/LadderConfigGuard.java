package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.utils.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * rearch-01 task group 6 — the per-preset Config guard the spec requires ("Config default drift is
 * caught by the guard, not the golden").
 *
 * <p>A preset is realized by its injection profile over <b>jar-default</b> Config and a
 * jar-default {@link com.android.commands.monkey.ape.runtime.RunSpec} (design D2), so every golden
 * silently depends on those defaults. Without a guard, changing one would surface as
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
 * <p><b>Two keys the harness deliberately moves off their jar defaults are absent from this table,
 * and both belong here so the two files agree about what the LLM presets actually run under</b>
 * ({@code OracleScaffold.newAgent} records the same pair as INV-ORA-07 injection-profile
 * adaptations). {@code ape.llmPercentage=1.0}: an LLM preset states the key in its own plan rather
 * than inheriting the jar's, and the scaffold substitutes the stream the coin is drawn from (design
 * D3), so no LLM preset's golden depends on that default at all.
 * {@code ape.graphStableRestartThreshold=10}: the stagnation stage evaluates the real midpoint
 * predicate, which at the jar default of 100 would sit at 50 while every scenario's counter is 0, 5,
 * 6 or 7. Lowering it cannot trigger a forced restart, because {@code onGraphStable} is reached only
 * from {@code checkStable()} at the end of {@code updateStateInternal} and this oracle drives
 * {@code ladder()} directly — so no golden depends on that default either.
 */
final class LadderConfigGuard {

    private LadderConfigGuard() {
    }

    /** The defaults every preset's ladder reads, whatever its injection profile. */
    static void assertSharedDefaults() {
        // The first block: enabled, so the ladder dereferences the tracker unconditionally, and the
        // budget arithmetic the scenario's declared exhaustion has to reach (design D2).
        assertTrue("ape.activityBudgetEnabled", Config.activityBudgetEnabled);
        assertEquals("ape.activityBaseBudget", 50, Config.activityBaseBudget);
        assertEquals("ape.activityBudgetPerWidget", 5, Config.activityBudgetPerWidget);
        // The trivial-activity rung: at this threshold a scenario's handful of activity nodes
        // yields an empty set, which is what keeps the rung a fall-through (finding 6.1-b).
        assertEquals("ape.trivialActivityRankThreshold", 3, Config.trivialActivityRankThreshold);
        assertFalse("ape.doBackToTrivialActivity", Config.doBackToTrivialActivity);
        // The component-trigger block's gate. False here is why the block cannot fire and why it
        // consumes no RNG draw (finding 2.1-c); a non-zero default would reopen that exclusion.
        assertEquals("ape.componentPercentage", 0.0, Config.componentPercentage, 0.0);
        // The SATA chain's own reads: the action set a state carries, how EARLY_STAGE decides what
        // is still worth visiting, and the epsilon-greedy rung's caps and coin.
        assertTrue("ape.modelMenuEnabled", Config.modelMenuEnabled);
        assertTrue("ape.useActionDiffer", Config.useActionDiffer);
        assertTrue("ape.leastVisitedPriorityTiebreak", Config.leastVisitedPriorityTiebreak);
        assertEquals("ape.backMenuPickCap", 3, Config.backMenuPickCap);
        assertTrue("ape.dynamicEpsilon", Config.dynamicEpsilon);
        assertEquals("ape.defaultEpsilon", 0.05, Config.defaultEpsilon, 0.0);
        assertEquals("ape.minEpsilon", 0.02, Config.minEpsilon, 0.0);
        assertEquals("ape.maxEpsilon", 0.15, Config.maxEpsilon, 0.0);
    }

    /**
     * The launcher and MOP-pick defaults, read only where {@code _mopData} is present — the cadence
     * the scenario's declared {@code _stepsSinceLauncherFiring} is seeded against, and the cap on
     * the deterministic MOP short-circuit.
     */
    static void assertMopDefaults() {
        // The launcher gate is the one ladder value that lives in the run plan rather than in
        // Config, so it is asserted against the plan the scaffold installed. The assertion is
        // unchanged in substance: a MOP arm at its jar defaults has the launcher enabled, which is
        // the condition every MOP golden was captured under.
        assertTrue("ape.activityTriggerEnabled",
                RunContext.current().spec().mop().activityTriggerEnabled());
        assertEquals("ape.activityTriggerStagnationStep", 50, Config.activityTriggerStagnationStep);
        assertEquals("ape.activityTriggerMaxPerRun", 0, Config.activityTriggerMaxPerRun);
        assertEquals("ape.mopTargetPickCap", 3, Config.mopTargetPickCap);
    }
}
