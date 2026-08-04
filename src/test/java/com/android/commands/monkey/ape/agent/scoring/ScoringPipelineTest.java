package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.After;
import org.junit.Before;
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
 * boost-reset, empty no-op) and via {@link ScoringPipeline#fromConfig} at default config (the
 * flags→passes assembly matrix for the coverage-only and full-MOP arms). The pure/empty arm through
 * {@code fromConfig} needs {@code apePureMode}, which forces {@code static final} gates that cannot be
 * re-evaluated in this JVM, so the empty-pipeline contract is asserted here via the direct
 * constructor and on device (task 7.6).
 */
public class ScoringPipelineTest {

    /**
     * The pass roster is plan-controlled, so every case needs a plan in effect. A MOP arm at its
     * jar defaults is the neutral starting point; a case that needs another weight installs its own.
     */
    @Before
    public void installDefaultMopPlan() {
        TestRunSpecs.installMop();
    }

    @After
    public void clearPlan() {
        RunContext.resetForTest();
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

    // ---- fromConfig assembly matrix at default flags (2.4) ------------------

    private static MopData mopWithWtg() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        wtg.put("com.x.Main", Collections.singletonList(
                new MopData.WtgTransition("btn", "android.widget.Button", "com.x.Detail")));
        return MopData.forTest(null, null, wtg);
    }

    @Test
    public void fromConfigFullMopArmAssemblesAllSixInOrder() {
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopWithWtg();
        ScoringPipeline p = ScoringPipeline.fromConfig(null, ctx);
        assertEquals(Arrays.asList(
                "MopWidgetPass", "MenuGatewayPass", "WtgPass",
                "FrontierPass", "CoveragePass", "FormCompletionPass"), p.passNames());
    }

    @Test
    public void fromConfigInsertsMopFrontierAfterFrontierBeforeCoverageWhenWeighted() {
        // Task 4.3 / INV-MFP registration position: MopFrontierPass joins the roster only when
        // the plan states a positive frontier weight, immediately after the generic FrontierPass
        // and before CoveragePass —
        // the frontier family stays contiguous (INV-ARCH-03 relative order preserved).
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopWithWtg();
        TestRunSpecs.installMop("ape.mopFrontierWeight", "200");
        ScoringPipeline p = ScoringPipeline.fromConfig(null, ctx);
        assertEquals(Arrays.asList(
                "MopWidgetPass", "MenuGatewayPass", "WtgPass", "FrontierPass",
                "MopFrontierPass", "CoveragePass", "FormCompletionPass"), p.passNames());
    }

    @Test
    public void fromConfigCoverageOnlyArmWhenNoMopData() {
        StubScoringContext ctx = new StubScoringContext(); // mopData == null
        ScoringPipeline p = ScoringPipeline.fromConfig(null, ctx);
        assertEquals(Arrays.asList("CoveragePass", "FormCompletionPass"), p.passNames());
    }
}
