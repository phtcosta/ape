package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * rv-scoring-pipeline tasks 3.1–3.6 (isEnabled gates) + 2.2 (a pass reads its collaborators from the
 * {@link ScoringContext}, not a field of its own), re-based on injected parameters (INV-ARCH-11).
 *
 * <p>Both dimensions of every gate are exercised here. The MopData dimension comes through the stub
 * context, and it is the parity-critical one: {@code WtgPass}/{@code FrontierPass} must re-guard on
 * MopData plus WTG data, because splitting them out of the inline interleaved loop is parity-safe
 * only with that guard. The weight dimension comes through {@link ScoringParams}, and used to be
 * untestable — the gates read {@code static final} fields no JVM test could re-evaluate, so the
 * weight-off cases were deferred to a registry test and to the device. A weight is an argument
 * now, so "off" is just another value to pass.
 *
 * <p>The weights below are not the jar's defaults, on purpose: a gate that opens on a value which
 * exists nowhere but this file cannot be opening because of a static read someone forgot to
 * remove. What the jar's defaults actually are is {@code ScoringParamsDefaultsTest}'s question.
 */
public class ScoringPassGateTest {

    private static final int W_DIRECT = 917;
    private static final int W_TRANSITIVE = 613;
    private static final int W_OPEN_MENU = 271;
    private static final int W_WTG = 419;
    private static final int W_FRONTIER = 331;
    private static final int W_MOP_FRONTIER = 233;
    private static final int W_COVERAGE = 101;

    /** Every weight positive and every flag on — the shape of a fully-armed MOP arm. */
    private static ScoringParams allOn() {
        return new ScoringParams(W_DIRECT, W_TRANSITIVE, W_OPEN_MENU, W_WTG, W_FRONTIER,
                W_MOP_FRONTIER, W_COVERAGE, true);
    }

    /** Every weight and flag off — what a plan carrying no scoring feature resolves to. */
    private static ScoringParams allOff() {
        return new ScoringParams(0, 0, 0, 0, 0, 0, 0, false);
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
        assertFalse(new MopWidgetPass(ctxWithMop(null), allOn()).isEnabled());
        assertTrue(new MopWidgetPass(ctxWithMop(mopNoWtg()), allOn()).isEnabled());
    }

    @Test
    public void menuGatewayPassEnabledIffMopDataPresent() {
        assertFalse(new MenuGatewayPass(ctxWithMop(null), allOn()).isEnabled());
        assertTrue(new MenuGatewayPass(ctxWithMop(mopNoWtg()), allOn()).isEnabled());
    }

    // ---- WtgPass / FrontierPass: gated on mopData != null && hasWtgData() ---
    // (parity-critical: the split from the inline interleaved loop re-guards on WTG data)

    @Test
    public void wtgPassRequiresMopDataAndWtgData() {
        assertFalse("no MopData", new WtgPass(ctxWithMop(null), allOn()).isEnabled());
        assertFalse("MopData but no WTG data",
                new WtgPass(ctxWithMop(mopNoWtg()), allOn()).isEnabled());
        assertTrue("MopData with WTG data",
                new WtgPass(ctxWithMop(mopWithWtg()), allOn()).isEnabled());
    }

    @Test
    public void frontierPassRequiresMopDataAndWtgData() {
        assertFalse("no MopData", new FrontierPass(ctxWithMop(null), allOn()).isEnabled());
        assertFalse("MopData but no WTG data",
                new FrontierPass(ctxWithMop(mopNoWtg()), allOn()).isEnabled());
        assertTrue("MopData with WTG data",
                new FrontierPass(ctxWithMop(mopWithWtg()), allOn()).isEnabled());
    }

    // ---- the weight half of the WTG-family gates, unreachable before injection ----

    @Test
    public void wtgAndFrontierPassesGateOnTheirWeightToo() {
        assertFalse("mopWeightWtg == 0 shuts WtgPass with the substrate fully present",
                new WtgPass(ctxWithMop(mopWithWtg()), allOff()).isEnabled());
        assertFalse("frontierBoostWeight == 0 shuts FrontierPass likewise",
                new FrontierPass(ctxWithMop(mopWithWtg()), allOff()).isEnabled());
    }

    // ---- MopFrontierPass: gated on the injected weight > 0 && mopData+WTG (Lever B) ----

    @Test
    public void mopFrontierPassRequiresWeightAndMopDataAndWtgData() {
        assertFalse("weight 0 even with full MopData+WTG (byte-identical to absent, INV-MFP-03)",
                new MopFrontierPass(ctxWithMop(mopWithWtg()), allOff()).isEnabled());
        assertFalse("weight>0 but no MopData",
                new MopFrontierPass(ctxWithMop(null), allOn()).isEnabled());
        assertFalse("weight>0 but no WTG data",
                new MopFrontierPass(ctxWithMop(mopNoWtg()), allOn()).isEnabled());
        assertTrue("weight>0 with MopData+WTG",
                new MopFrontierPass(ctxWithMop(mopWithWtg()), allOn()).isEnabled());
    }

    // ---- CoveragePass / FormCompletionPass: gated on their own value alone ---

    @Test
    public void coveragePassEnabledIffItsWeightIsNonZero() {
        assertTrue(new CoveragePass(new StubScoringContext(), allOn()).isEnabled());
        assertFalse(new CoveragePass(new StubScoringContext(), allOff()).isEnabled());
    }

    @Test
    public void formCompletionPassEnabledIffItsFlagIsSet() {
        assertTrue(new FormCompletionPass(new StubScoringContext(), allOn()).isEnabled());
        assertFalse(new FormCompletionPass(new StubScoringContext(), allOff()).isEnabled());
    }

    /**
     * The two MOP-data-gated passes stay on when their substrate is there, whatever the weights
     * say: their gate is the data, not a number. Asserting it keeps the two dimensions from being
     * conflated — a reader could otherwise take {@code allOff()} to mean "no pass is constructed".
     */
    @Test
    public void theMopDataGatedPassesIgnoreTheWeights() {
        assertTrue(new MopWidgetPass(ctxWithMop(mopNoWtg()), allOff()).isEnabled());
        assertTrue(new MenuGatewayPass(ctxWithMop(mopNoWtg()), allOff()).isEnabled());
    }

    // ---- 2.2: the gate decision is read from the context, not a pass field --

    @Test
    public void passReadsMopDataFromContextNotItsOwnState() {
        // Same pass type, two contexts -> two different enabled states, proving the pass holds no
        // MopData of its own and reads it from the ScoringContext at construction.
        assertTrue(new MopWidgetPass(ctxWithMop(mopNoWtg()), allOn()).isEnabled());
        assertFalse(new MopWidgetPass(ctxWithMop(null), allOn()).isEnabled());
    }
}
