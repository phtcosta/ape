package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * rearch-03 task 5.1a (INV-ARCH-12) — the only thing in the whole rearch set that fails when a
 * scoring default changes silently.
 *
 * <p><b>Why it has to exist.</b> Four mechanisms look like they would catch a changed weight and
 * none of them does. The parity goldens never execute scoring: it runs in
 * {@code adjustActionsByGUITree()}, which {@code resolveNewAction()} calls before the oracle's
 * entry point {@code selectNewActionNonnull()} (`StatefulAgent.java:1537-1538`), so no golden
 * record can depend on a weight. The pass unit tests cannot see a wrong value because INV-ARCH-11
 * has them supply their own. The {@code RUN_START} echo prints the resolved weights but nothing
 * reads it back (owner decision D1, level 0), so it is evidence after the fact rather than a gate.
 * And {@code rearch-01}'s {@code LadderConfigGuard} scopes itself to "the values the selection
 * ladder reads", which excludes every scoring weight — it says so, and names this test as where
 * the responsibility went.
 *
 * <p>{@code KeyOwnershipTotalityTest.declaredDefaultsMatchTheJarsOwnDefaults} is not this guard
 * either. It compares the ownership table against {@code Config}'s fields by reflection, so it
 * pins that the two <em>agree</em>; changing the weight in both leaves it green. That is why the
 * expected values below are spelled out as literals rather than read from the constants they
 * guard — an assertion that fetches its expectation from the thing under test compares a value to
 * itself and passes under any value.
 */
public class ScoringParamsDefaultsTest {

    /**
     * A MOP arm is what a scoring default has to be read through: the six MOP-family weights only
     * belong to a plan that carries the MOP feature, and the feature is activated by the presence
     * of the data path. Nothing is installed into {@link com.android.commands.monkey.ape.runtime.RunContext}
     * — the plan is a value here, and the mapping under test is a pure function of it.
     */
    @Test
    public void aDefaultMopPlanCarriesTheJarsScoringWeights() {
        RunSpec spec = TestRunSpecs.spec("ape.mopDataPath", TestRunSpecs.MOP_PATH);

        ScoringParams params = ScoringParams.fromSpec(spec);

        assertEquals("ape.mopWeightDirect", 500, params.mopWeightDirect());
        assertEquals("ape.mopWeightTransitive", 300, params.mopWeightTransitive());
        assertEquals("ape.mopWeightOpenMenu", 250, params.mopWeightOpenMenu());
        assertEquals("ape.mopWeightWtg", 200, params.mopWeightWtg());
        assertEquals("ape.frontierBoostWeight", 200, params.frontierBoostWeight());
        assertEquals("ape.mopFrontierWeight", 0, params.mopFrontierWeight());
        assertEquals("ape.coverageBoostWeight", 100, params.coverageBoostWeight());
        assertTrue("ape.formCompletionEnabled", params.formCompletionEnabled());
    }

    /**
     * The other half of the mapping, and the half a wrong default cannot be blamed for: with no
     * MOP feature there is no {@code MopParams} to read, and all six MOP-family weights have to
     * come back as zero rather than as an exception. This is the plan the {@code aperv} preset
     * resolves to, so it is the shape most runs of the corpus actually score under.
     */
    @Test
    public void aPlanWithoutMopZeroesEveryMopWeightAndKeepsTheRest() {
        ScoringParams params = ScoringParams.fromSpec(TestRunSpecs.spec());

        assertEquals("ape.mopWeightDirect without MOP", 0, params.mopWeightDirect());
        assertEquals("ape.mopWeightTransitive without MOP", 0, params.mopWeightTransitive());
        assertEquals("ape.mopWeightOpenMenu without MOP", 0, params.mopWeightOpenMenu());
        assertEquals("ape.mopWeightWtg without MOP", 0, params.mopWeightWtg());
        assertEquals("ape.frontierBoostWeight without MOP", 0, params.frontierBoostWeight());
        assertEquals("ape.mopFrontierWeight without MOP", 0, params.mopFrontierWeight());
        // The two non-MOP weights are untouched by the MOP feature's absence: they are what an
        // APE-baseline arm still scores with.
        assertEquals("ape.coverageBoostWeight", 100, params.coverageBoostWeight());
        assertTrue("ape.formCompletionEnabled", params.formCompletionEnabled());
    }

    /**
     * A stated off value has to read the same as an absent feature, because that is the equivalence
     * every pass gate is written against. Both routes reach zero here through different paths in
     * the resolver — {@code frontierBoostWeight=0} makes the FRONTIER feature inactive, so the key
     * is dropped from the plan and read through the absent branch, while a pass sees only the zero.
     */
    @Test
    public void statedOffValuesReadAsTheAbsentFeatureDoes() {
        ScoringParams params = ScoringParams.fromSpec(TestRunSpecs.spec(
                "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                "ape.frontierBoostWeight", "0",
                "ape.mopWeightWtg", "0",
                "ape.coverageBoostWeight", "0",
                "ape.formCompletionEnabled", "false"));

        assertEquals("ape.frontierBoostWeight stated off", 0, params.frontierBoostWeight());
        assertEquals("ape.mopWeightWtg stated off", 0, params.mopWeightWtg());
        assertEquals("ape.coverageBoostWeight stated off", 0, params.coverageBoostWeight());
        assertFalse("ape.formCompletionEnabled stated off", params.formCompletionEnabled());
        // The weights the plan did not mention keep the jar's values: stating one gate off is not
        // a kill-switch for the others (the switch that used to do that is retired).
        assertEquals("ape.mopWeightDirect", 500, params.mopWeightDirect());
        assertEquals("ape.mopWeightOpenMenu", 250, params.mopWeightOpenMenu());
    }
}
