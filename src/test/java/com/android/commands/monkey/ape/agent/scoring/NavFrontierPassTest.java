package com.android.commands.monkey.ape.agent.scoring;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.utils.StaticGuiModel;

import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.button;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.graphVisiting;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.state;
import static org.junit.Assert.*;

/**
 * The STATIC navigation frontier (wtg-navigation "WTG Frontier Boost for Unvisited Activities"):
 * {@link NavFrontierPass} reads the WTG from {@link StaticGuiModel} alone — every context below
 * carries no MOP data — and writes its term to {@code navFrontierBoost} and the priority, never to
 * {@code wtgBoost} (INV-WTG-06/07/08).
 */
public class NavFrontierPassTest {

    private static final String MAIN = "com.x.Main";
    private static final String DETAIL = "com.x.Detail";
    private static final String SEEN = "com.x.Seen";
    private static final int WEIGHT = 200;

    private static ScoringParams frontierOnly(int weight) {
        return new ScoringParams(0, 0, weight, 0, 0, false);
    }

    /** A context with the static model and no MOP data at all: the {@code as} cell's shape. */
    private static StubScoringContext staticOnly(String... visited) {
        Map<String, List<StaticGuiModel.WtgTransition>> wtg = new HashMap<>();
        wtg.put(MAIN, Arrays.asList(
                new StaticGuiModel.WtgTransition("toDetail", DETAIL),
                new StaticGuiModel.WtgTransition("toSeen", SEEN)));
        StubScoringContext ctx = new StubScoringContext();
        ctx.staticModel = new StaticGuiModel(null, null, wtg, null, null, null, null, null);
        ctx.graph = graphVisiting(visited);
        assertNull("no MOP data is reachable from this context", ctx.getMopData());
        return ctx;
    }

    private static void apply(StubScoringContext ctx, ModelAction... actions) throws Exception {
        NavFrontierPass pass = new NavFrontierPass(ctx, frontierOnly(WEIGHT));
        assertTrue(pass.isEnabled());
        for (ModelAction a : actions) {
            a.resetBoosts();
            a.setPriority(10);
        }
        pass.apply(state(MAIN, actions), actions, ctx);
    }

    /** Scenario "widget leading to an unvisited activity is boosted". */
    @Test
    public void boostsAWidgetLeadingToAnUnvisitedActivity() throws Exception {
        ModelAction toDetail = button("toDetail");
        apply(staticOnly(SEEN), toDetail);
        assertEquals(10 + WEIGHT, toDetail.getPriority());
        assertEquals(WEIGHT, toDetail.getNavFrontierBoost());
        assertEquals("one producer per field: wtgBoost is WtgPass's", 0, toDetail.getWtgBoost());
    }

    /** Scenario "boost disappears after the target is visited". */
    @Test
    public void aVisitedTargetEarnsNothing() throws Exception {
        ModelAction toSeen = button("toSeen");
        ModelAction unmatched = button("other");
        apply(staticOnly(SEEN), toSeen, unmatched);
        assertEquals(10, toSeen.getPriority());
        assertEquals(0, toSeen.getNavFrontierBoost());
        assertEquals(10, unmatched.getPriority());
        assertEquals(0, unmatched.getNavFrontierBoost());
    }

    /** Scenario "disabled": no NAV_FRONTIER, or no WTG data, means no pass. */
    @Test
    public void notMountedWithoutItsFeatureOrWtgData() {
        assertFalse(new NavFrontierPass(staticOnly(), frontierOnly(0)).isEnabled());
        StubScoringContext empty = new StubScoringContext();
        empty.staticModel = StaticGuiModel.EMPTY;
        assertFalse(new NavFrontierPass(empty, frontierOnly(WEIGHT)).isEnabled());
    }

    /**
     * INV-WTG-08: the term is the same with and without MOP — here, with a MOP context mounted
     * beside the same static model, the field and the priority do not move.
     */
    @Test
    public void theTermIsTheSameWithAndWithoutMop() throws Exception {
        ModelAction withoutMop = button("toDetail");
        apply(staticOnly(), withoutMop);

        StubScoringContext withMop = staticOnly();
        withMop.mopData = com.android.commands.monkey.ape.utils.MopData.forTest(null, null);
        ModelAction alongsideMop = button("toDetail");
        apply(withMop, alongsideMop);

        assertEquals(withoutMop.getNavFrontierBoost(), alongsideMop.getNavFrontierBoost());
        assertEquals(withoutMop.getPriority(), alongsideMop.getPriority());
    }
}
