package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The {@code isEnabled} gates (scoring-pipeline "Scoring Pass Roster and Gates", INV-ARCH-14): each
 * pass is mounted by its feature — carried by the value {@link ScoringParams} holds for it, or, for
 * {@code MopWidgetPass}, by the {@code MopContext} the group root {@code MOP} provides — and, for
 * the WTG family, by WTG data in the static model. A pass reads its collaborators from the
 * {@link ScoringContext}, not a field of its own.
 *
 * <p>The weights below are not the jar's defaults, on purpose: a gate that opens on a value which
 * exists nowhere but this file cannot be opening because of a static read someone forgot to
 * remove. What the jar's defaults actually are is {@code ScoringParamsDefaultsTest}'s question.
 */
public class ScoringPassGateTest {

    private static final int W_OPEN_MENU = 271;
    private static final int W_WTG = 419;
    private static final int W_FRONTIER = 331;
    private static final int W_MOP_FRONTIER = 233;
    private static final int W_COVERAGE = 101;

    /** Every member feature mounted — the shape of a fully-armed cell. */
    private static ScoringParams allOn() {
        return new ScoringParams(W_OPEN_MENU, W_WTG, W_FRONTIER,
                W_MOP_FRONTIER, W_COVERAGE, true);
    }

    /** Every member feature absent — what a plan carrying no scoring feature resolves to. */
    private static ScoringParams allOff() {
        return new ScoringParams(0, 0, 0, 0, 0, false);
    }

    /** A context with the given providers; either may be null (group not mounted). */
    private static StubScoringContext ctx(StaticGuiModel staticModel, MopData mopData) {
        StubScoringContext ctx = new StubScoringContext();
        ctx.staticModel = staticModel;
        ctx.mopData = mopData;
        return ctx;
    }

    private static MopData mop() {
        return MopData.forTest(null, null);
    }

    private static StaticGuiModel staticWithWtg() {
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        wtg.put("com.x.Main", Collections.singletonList(
                new StaticGuiModel.WtgTransition("btn", "com.x.Detail")));
        return new StaticGuiModel(null, null, wtg, null, null, null, null, null);
    }

    // ---- MopWidgetPass: mounted by the group root MOP ------------------------

    @Test
    public void mopWidgetPassEnabledIffMopIsMounted() {
        assertFalse(new MopWidgetPass(ctx(staticWithWtg(), null), new NoopSink()).isEnabled());
        assertTrue(new MopWidgetPass(ctx(StaticGuiModel.EMPTY, mop()), new NoopSink()).isEnabled());
    }

    // ---- MenuGatewayPass: MENU_GATEWAY, with MOP data ------------------------

    @Test
    public void menuGatewayPassEnabledIffItsFeatureIsMountedWithMop() {
        assertFalse("MENU_GATEWAY absent",
                new MenuGatewayPass(ctx(StaticGuiModel.EMPTY, mop()), allOff()).isEnabled());
        assertFalse("no MOP data", new MenuGatewayPass(ctx(null, null), allOn()).isEnabled());
        assertTrue(new MenuGatewayPass(ctx(StaticGuiModel.EMPTY, mop()), allOn()).isEnabled());
    }

    // ---- WtgPass: WTG, with MOP data and WTG data ----------------------------

    @Test
    public void wtgPassRequiresItsFeatureMopDataAndWtgData() {
        assertFalse("no MopData", new WtgPass(ctx(staticWithWtg(), null), allOn()).isEnabled());
        assertFalse("no WTG data",
                new WtgPass(ctx(StaticGuiModel.EMPTY, mop()), allOn()).isEnabled());
        assertFalse("WTG absent", new WtgPass(ctx(staticWithWtg(), mop()), allOff()).isEnabled());
        assertTrue(new WtgPass(ctx(staticWithWtg(), mop()), allOn()).isEnabled());
    }

    // ---- NavFrontierPass: NAV_FRONTIER and WTG data, no MOP data needed -----

    @Test
    public void navFrontierPassRequiresItsFeatureAndWtgDataOnly() {
        assertFalse("no static model", new NavFrontierPass(ctx(null, null), allOn()).isEnabled());
        assertFalse("no WTG data",
                new NavFrontierPass(ctx(StaticGuiModel.EMPTY, null), allOn()).isEnabled());
        assertFalse("NAV_FRONTIER absent",
                new NavFrontierPass(ctx(staticWithWtg(), null), allOff()).isEnabled());
        assertTrue("mounted with no MOP data at all (INV-WTG-08)",
                new NavFrontierPass(ctx(staticWithWtg(), null), allOn()).isEnabled());
    }

    // ---- MopFrontierPass: MOP_FRONTIER, with MOP data and WTG data ----------

    @Test
    public void mopFrontierPassRequiresItsFeatureMopDataAndWtgData() {
        assertFalse("MOP_FRONTIER absent (INV-MFP-03)",
                new MopFrontierPass(ctx(staticWithWtg(), mop()), allOff()).isEnabled());
        assertFalse("no MopData",
                new MopFrontierPass(ctx(staticWithWtg(), null), allOn()).isEnabled());
        assertFalse("no WTG data",
                new MopFrontierPass(ctx(StaticGuiModel.EMPTY, mop()), allOn()).isEnabled());
        assertTrue(new MopFrontierPass(ctx(staticWithWtg(), mop()), allOn()).isEnabled());
    }

    // ---- CoveragePass / FormCompletionPass: their feature alone --------------

    @Test
    public void coveragePassEnabledIffItsFeatureIsMounted() {
        assertTrue(new CoveragePass(new StubScoringContext(), allOn()).isEnabled());
        assertFalse(new CoveragePass(new StubScoringContext(), allOff()).isEnabled());
    }

    @Test
    public void formCompletionPassEnabledIffItsFeatureIsMounted() {
        assertTrue(new FormCompletionPass(new StubScoringContext(), allOn()).isEnabled());
        assertFalse(new FormCompletionPass(new StubScoringContext(), allOff()).isEnabled());
    }

    // ---- the gate decision is read from the context, not a pass field --------

    @Test
    public void passReadsItsProviderFromContextNotItsOwnState() {
        // Same pass type, two contexts -> two different enabled states, proving the pass holds no
        // provider of its own and reads it from the ScoringContext at construction.
        assertTrue(new NavFrontierPass(ctx(staticWithWtg(), null), allOn()).isEnabled());
        assertFalse(new NavFrontierPass(ctx(null, null), allOn()).isEnabled());
    }
}
