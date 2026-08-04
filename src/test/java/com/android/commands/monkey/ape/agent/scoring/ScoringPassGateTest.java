package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * rv-scoring-pipeline tasks 3.1–3.6 (isEnabled gates) + 2.2 (a pass reads its collaborators from the
 * {@link ScoringContext}, not a field of its own). Gates are asserted at DEFAULT config
 * (coverageBoostWeight=100, mopWeightWtg=200, frontierBoostWeight=200, formCompletionEnabled=true) —
 * the {@code static final} weight/boolean gates cannot be varied in this JVM, so the weight-off cases
 * (coverageBoostWeight=0, mopWeightWtg=0, formCompletionEnabled=false) are covered by the kill-switch
 * registry test and on device. The MopData dimension IS varied here through the stub context, which is
 * the parity-critical part: WtgPass/FrontierPass must re-guard on MopData+WTG-data (the split from the
 * inline interleaved loop is parity-safe only with that guard).
 */
public class ScoringPassGateTest {

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


    private static StubScoringContext ctxWithMop(MopData mopData) {
        StubScoringContext ctx = new StubScoringContext();
        ctx.mopData = mopData;
        return ctx;
    }

    private static MopData mopNoWtg() {
        return MopData.forTest(null, null, null); // no transitions -> hasWtgData() == false
    }

    private static MopData mopWithWtg() {
        Map<String, List<MopData.WtgTransition>> wtg = new HashMap<>();
        wtg.put("com.x.Main", Collections.singletonList(
                new MopData.WtgTransition("btn", "android.widget.Button", "com.x.Detail")));
        return MopData.forTest(null, null, wtg);
    }

    // ---- MopWidgetPass / MenuGatewayPass: gated on mopData != null ----------

    @Test
    public void mopWidgetPassEnabledIffMopDataPresent() {
        assertFalse(new MopWidgetPass(ctxWithMop(null)).isEnabled());
        assertTrue(new MopWidgetPass(ctxWithMop(mopNoWtg())).isEnabled());
    }

    @Test
    public void menuGatewayPassEnabledIffMopDataPresent() {
        assertFalse(new MenuGatewayPass(ctxWithMop(null)).isEnabled());
        assertTrue(new MenuGatewayPass(ctxWithMop(mopNoWtg())).isEnabled());
    }

    // ---- WtgPass / FrontierPass: gated on mopData != null && hasWtgData() ---
    // (parity-critical: the split from the inline interleaved loop re-guards on WTG data)

    @Test
    public void wtgPassRequiresMopDataAndWtgData() {
        assertFalse("no MopData", new WtgPass(ctxWithMop(null)).isEnabled());
        assertFalse("MopData but no WTG data", new WtgPass(ctxWithMop(mopNoWtg())).isEnabled());
        assertTrue("MopData with WTG data", new WtgPass(ctxWithMop(mopWithWtg())).isEnabled());
    }

    @Test
    public void frontierPassRequiresMopDataAndWtgData() {
        assertFalse("no MopData", new FrontierPass(ctxWithMop(null)).isEnabled());
        assertFalse("MopData but no WTG data", new FrontierPass(ctxWithMop(mopNoWtg())).isEnabled());
        assertTrue("MopData with WTG data", new FrontierPass(ctxWithMop(mopWithWtg())).isEnabled());
    }

    // ---- MopFrontierPass: gated on the plan's frontier weight > 0 && mopData+WTG (Lever B) ----
    // The weight is a plan value, so both the weight-off (default 0 → disabled, INV-MFP-03) and
    // weight-on branches are reachable by installing two plans.

    @Test
    public void mopFrontierPassRequiresWeightAndMopDataAndWtgData() {
        // Default weight 0 → disabled even with full MopData+WTG (byte-identical to absent).
        TestRunSpecs.installMop();
        assertFalse("default weight 0",
                new MopFrontierPass(ctxWithMop(mopWithWtg())).isEnabled());

        TestRunSpecs.installMop("ape.mopFrontierWeight", "200");
        assertFalse("weight>0 but no MopData",
                new MopFrontierPass(ctxWithMop(null)).isEnabled());
        assertFalse("weight>0 but no WTG data",
                new MopFrontierPass(ctxWithMop(mopNoWtg())).isEnabled());
        assertTrue("weight>0 with MopData+WTG",
                new MopFrontierPass(ctxWithMop(mopWithWtg())).isEnabled());
    }

    // ---- CoveragePass / FormCompletionPass: on at default config -------------

    @Test
    public void coveragePassEnabledAtDefaultWeight() {
        assertTrue(new CoveragePass(new StubScoringContext()).isEnabled());
    }

    @Test
    public void formCompletionPassEnabledAtDefaultFlag() {
        assertTrue(new FormCompletionPass(new StubScoringContext()).isEnabled());
    }

    // ---- 2.2: the gate decision is read from the context, not a pass field --

    @Test
    public void passReadsMopDataFromContextNotItsOwnState() {
        // Same pass type, two contexts -> two different enabled states, proving the pass holds no
        // MopData of its own and reads it from the ScoringContext at construction.
        assertTrue(new MopWidgetPass(ctxWithMop(mopNoWtg())).isEnabled());
        assertFalse(new MopWidgetPass(ctxWithMop(null)).isEnabled());
    }
}
