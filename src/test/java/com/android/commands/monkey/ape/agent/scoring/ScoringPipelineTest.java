package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * rv-scoring-pipeline tasks 2.3/2.4 — {@link ScoringPipeline} assembly and run mechanics
 * (INV-ARCH-02/03/04). The pipeline is exercised both with stub passes (filtering, ordering,
 * boost-reset, empty no-op) and through {@link ScoringPipeline#fromParams}, which is the
 * weights-and-substrate assembly matrix.
 *
 * <p>Every roster below is produced by handing {@code fromParams} a {@link ScoringParams} and a
 * context, with nothing installed process-wide. An empty roster used to be the one case this file
 * could not reach through the real entry point — it needed the retired {@code apePureMode} switch
 * to force {@code static final} gates a JVM test cannot re-evaluate — so it was asserted through
 * the package-private constructor instead. A plan with no scoring feature is now just a
 * {@code ScoringParams} of zeroes, so the case is reachable where it belongs.
 */
public class ScoringPipelineTest {

    /** A fully-armed MOP arm, at values that are not the jar's, so a roster cannot be a default. */
    private static ScoringParams allOn() {
        return new ScoringParams(917, 613, 271, 419, 331, 233, 101, true);
    }

    /** The MOP arm as a plan states it by default: every weight but the MOP-frontier one. */
    private static ScoringParams withoutMopFrontier() {
        return new ScoringParams(917, 613, 271, 419, 331, 0, 101, true);
    }


    /** A stub pass that records that it ran, in order, and optionally mutates the actions. */
    static final class RecordingPass implements ScoringPass {
        final String name;
        final boolean enabled;
        final List<String> runLog;
        Runnable onApply;

        RecordingPass(String name, boolean enabled, List<String> runLog) {
            this.name = name;
            this.enabled = enabled;
            this.runLog = runLog;
        }

        @Override public String name() { return name; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
            runLog.add(name);
            if (onApply != null) onApply.run();
        }
    }

    private static ModelAction click() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    // ---- assembly: filtering + order (INV-ARCH-04) --------------------------

    @Test
    public void keepsOnlyEnabledPassesPreservingOrder() {
        List<String> log = new ArrayList<>();
        ScoringPipeline p = new ScoringPipeline(Arrays.asList(
                new RecordingPass("A", true, log),
                new RecordingPass("B", false, log),
                new RecordingPass("C", true, log)));
        assertEquals(Arrays.asList("A", "C"), p.passNames());
        assertEquals(2, p.size());
    }

    // ---- run: order + boost reset -------------------------------------------

    @Test
    public void applyRunsEnabledPassesInPipelineOrder() {
        List<String> log = new ArrayList<>();
        ScoringPipeline p = new ScoringPipeline(Arrays.asList(
                new RecordingPass("A", true, log),
                new RecordingPass("B", true, log)));
        p.apply(null, new ModelAction[]{click()}, new StubScoringContext());
        assertEquals(Arrays.asList("A", "B"), log);
    }

    @Test
    public void applyResetsBoostsBeforePassesWhenNonEmpty() {
        ModelAction a = click();
        a.setMopBoost(500); // stale provenance from a previous step
        ScoringPipeline p = new ScoringPipeline(Collections.<ScoringPass>singletonList(
                new RecordingPass("A", true, new ArrayList<String>())));
        p.apply(null, new ModelAction[]{a}, new StubScoringContext());
        assertEquals("non-empty pipeline clears provenance before passes run", 0, a.getMopBoost());
    }

    // ---- empty pipeline is a strict no-op (INV-ARCH-02) ---------------------

    @Test
    public void emptyPipelineIsStrictNoOp() {
        ModelAction a = click();
        a.setPriority(42);
        a.setMopBoost(7); // must survive: an empty pipeline touches nothing, not even resetBoosts
        ScoringPipeline p = new ScoringPipeline(Arrays.asList(
                new RecordingPass("A", false, new ArrayList<String>())));
        assertEquals(0, p.size());
        p.apply(null, new ModelAction[]{a}, new StubScoringContext());
        assertEquals("priority untouched", 42, a.getPriority());
        assertEquals("provenance untouched (no reset on empty pipeline)", 7, a.getMopBoost());
    }

    // ---- fromParams assembly matrix: weights x substrate (2.4) --------------

    private static MopData mopWithWtg() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        wtg.put("com.x.Main", Collections.singletonList(
                new MopData.WtgTransition("btn", "com.x.Detail")));
        return MopData.forTest(null, null, wtg);
    }

    @Test
    public void fullMopArmAssemblesSixInOrderWhenTheMopFrontierWeightIsZero() {
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopWithWtg();
        ScoringPipeline p = ScoringPipeline.fromParams(withoutMopFrontier(), ctx, new NoopSink());
        assertEquals(Arrays.asList(
                "MopWidgetPass", "MenuGatewayPass", "WtgPass",
                "FrontierPass", "CoveragePass", "FormCompletionPass"), p.passNames());
    }

    @Test
    public void mopFrontierSitsAfterFrontierAndBeforeCoverageWhenWeighted() {
        // Task 4.3 / INV-MFP registration position: MopFrontierPass joins the roster only when the
        // weight is positive, immediately after the generic FrontierPass and before CoveragePass —
        // the frontier family stays contiguous (INV-ARCH-03 relative order preserved).
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopWithWtg();
        ScoringPipeline p = ScoringPipeline.fromParams(allOn(), ctx, new NoopSink());
        assertEquals(Arrays.asList(
                "MopWidgetPass", "MenuGatewayPass", "WtgPass", "FrontierPass",
                "MopFrontierPass", "CoveragePass", "FormCompletionPass"), p.passNames());
    }

    // ---- the injection is real: one context, two params, two rosters --------

    @Test
    public void twoParamsOverOneContextAssembleDifferentPipelines() {
        // The contrast that a decorative parameter could not produce. One ScoringContext object,
        // used twice; two ScoringParams differing in exactly one field; two different rosters. No
        // Config is mutated and no plan is installed, because there is nothing global left for the
        // assembly to consult (INV-ARCH-11).
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopWithWtg();

        ScoringPipeline without = ScoringPipeline.fromParams(withoutMopFrontier(), ctx, new NoopSink());
        ScoringPipeline with = ScoringPipeline.fromParams(allOn(), ctx, new NoopSink());

        assertFalse("weight 0 keeps MopFrontierPass out",
                without.passNames().contains("MopFrontierPass"));
        assertTrue("a positive weight puts it in",
                with.passNames().contains("MopFrontierPass"));
        assertEquals("and nothing else moved", without.size() + 1, with.size());
    }

    /**
     * The empty pipeline, reached through the real entry point. This is the case that used to need
     * the retired {@code apePureMode} switch and could not be produced in this JVM at all, so
     * INV-ARCH-02's strict no-op was asserted through the package-private constructor instead.
     *
     * <p>It takes both halves, and that is the point worth pinning: zeroing every weight is not
     * enough, because {@code MopWidgetPass} and {@code MenuGatewayPass} gate on the substrate
     * alone. An arm that wants no scoring states no MOP data path either — which is what "a plan
     * carrying no scoring feature" means, since the data path is the MOP feature's activation key.
     */
    @Test
    public void aPlanWithNoScoringFeatureAssemblesNothing() {
        StubScoringContext ctx = new StubScoringContext(); // no substrate
        ScoringPipeline p = ScoringPipeline.fromParams(
                new ScoringParams(0, 0, 0, 0, 0, 0, 0, false), ctx, new NoopSink());

        assertEquals(Collections.<String>emptyList(), p.passNames());
        assertEquals(0, p.size());
        assertEquals("the census still records all seven as candidates", 7, p.candidates().size());
    }

    @Test
    public void zeroedWeightsAloneDoNotEmptyThePipeline() {
        // The complement of the case above, asserted so the two halves cannot be confused: with
        // the substrate present, the two MopData-gated passes survive every weight being zero.
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopWithWtg();

        ScoringPipeline p = ScoringPipeline.fromParams(
                new ScoringParams(0, 0, 0, 0, 0, 0, 0, false), ctx, new NoopSink());

        assertEquals(Arrays.asList("MopWidgetPass", "MenuGatewayPass"), p.passNames());
    }

    // ---- the candidate census: what the pass list does NOT say (5.2a) --------

    @Test
    public void theCensusCarriesEveryCandidateAndItsVerdict() {
        // No MopData: the four MOP-family passes are candidates that were not constructed, which
        // is exactly the fact the [APE-ARCH] line cannot express — it shows their absence only as
        // names that are not there.
        StubScoringContext ctx = new StubScoringContext();
        ScoringPipeline p = ScoringPipeline.fromParams(allOn(), ctx, new NoopSink());

        Map<String, Boolean> census = p.candidates();

        assertEquals("every candidate is named, constructed or not",
                Arrays.asList("MopWidgetPass", "MenuGatewayPass", "WtgPass", "FrontierPass",
                        "MopFrontierPass", "CoveragePass", "FormCompletionPass"),
                new ArrayList<>(census.keySet()));
        assertEquals(Boolean.FALSE, census.get("WtgPass"));
        assertEquals(Boolean.FALSE, census.get("MopFrontierPass"));
        assertEquals(Boolean.TRUE, census.get("CoveragePass"));
        assertEquals(Boolean.TRUE, census.get("FormCompletionPass"));
    }

    @Test
    public void theCensusIsASiblingOfThePassListNotAWideningOfIt() {
        // INV-ARCH-04: a consumer reading passes still sees exactly the constructed ones, so the
        // census cannot leak a disabled pass into the roster or the [APE-ARCH] line.
        StubScoringContext ctx = new StubScoringContext();
        ScoringPipeline p = ScoringPipeline.fromParams(allOn(), ctx, new NoopSink());

        assertEquals(Arrays.asList("CoveragePass", "FormCompletionPass"), p.passNames());
        assertEquals(2, p.size());
        assertEquals("the census still names all seven", 7, p.candidates().size());
    }

    @Test
    public void theCensusIsUnmodifiable() {
        ScoringPipeline p = ScoringPipeline.fromParams(allOn(), new StubScoringContext(), new NoopSink());
        try {
            p.candidates().put("Injected", Boolean.TRUE);
            fail("the census is the run's record, not a place to write to");
        } catch (UnsupportedOperationException expected) {
            // the record a sink reads cannot be edited by the code that reads it
        }
    }

    @Test
    public void coverageOnlyArmWhenNoMopData() {
        StubScoringContext ctx = new StubScoringContext(); // mopData == null
        ScoringPipeline p = ScoringPipeline.fromParams(allOn(), ctx, new NoopSink());
        assertEquals(Arrays.asList("CoveragePass", "FormCompletionPass"), p.passNames());
    }
}
