package com.android.commands.monkey.ape.agent.scoring;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.utils.StaticArtifactLoader;
import com.android.commands.monkey.ape.utils.TestMopContexts;

import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.button;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.editText;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.fieldsOf;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.graphVisiting;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.nonTarget;
import static com.android.commands.monkey.ape.agent.scoring.PassFixtures.state;
import static org.junit.Assert.*;

/**
 * Rule R1 (scoring-pipeline "Scorer Independence", INV-ARCH-13): no pass reads the priority or
 * another pass's field, so the order of the chain changes no field and no final priority, mounting
 * one scorer never changes another's term, and two actions that differ only in base priority get
 * the same terms.
 *
 * <p>The screens are the {@code as_mop} cell's seven passes over a MOP-bearing screen, and the
 * form passes over a form screen with no MOP-weighted action. The form pass's submit candidate is
 * chosen by {@code FormCompletion.selectSubmitCandidate}, which still prefers a MOP-boosted action
 * until the form-completion change of this plan lands (design D-B14.10); a form screen that also
 * carries a MOP-weighted action is therefore left out here rather than asserted either way.
 */
public class ScoringPassIndependenceTest {

    private static final String ACT = "com.x.Main";
    private static final String FORM = "com.x.Form";

    /** The {@code as_mop} weights: menu 250, WTG 200, frontier 200, MOP frontier 200, coverage 100. */
    private static final ScoringParams AS_MOP = new ScoringParams(250, 200, 200, 200, 100, true);

    private StaticArtifactLoader.Loaded loaded;

    @Before
    public void loadTheArtifact() throws Exception {
        TestRunSpecs.install();
        File f = File.createTempFile("independence", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"formatVersion\":2,\"targets\":3,\"handlers\":{},"
                    + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                    + "\"widgets\":{\"" + ACT + "\":{\"go\":{\"mop\":{\"click\":\"transitive\"},"
                    + "\"dist\":{\"click\":[[0,1]]}}}},"
                    + "\"mopActivities\":[\"" + ACT + "\",\"com.x.Detail\"],"
                    + "\"wtg\":{\"" + ACT + "\":[{\"widget\":\"go\",\"target\":\"com.x.Detail\"},"
                    + "{\"widget\":\"plain\",\"target\":\"com.x.Plain\"}]},"
                    + "\"optionsMenus\":[{\"activity\":\"" + ACT + "\",\"hasFlaggedWidget\":true}]}");
        }
        loaded = StaticArtifactLoader.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(loaded);
    }

    @After
    public void clearPlan() {
        RunContext.resetForTest();
    }

    private StubScoringContext context() {
        StubScoringContext ctx = new StubScoringContext();
        ctx.staticModel = loaded.staticModel;
        ctx.mopContext = TestMopContexts.defaults(loaded);
        ctx.graph = graphVisiting(ACT);
        return ctx;
    }

    /** The mounted passes of {@code params} over {@code ctx}, in pipeline order. */
    private static List<ScoringPass> roster(ScoringParams params, StubScoringContext ctx) {
        List<ScoringPass> all = Arrays.asList(new MopWidgetPass(ctx, new NoopSink()),
                new MenuGatewayPass(ctx, params), new WtgPass(ctx, params),
                new NavFrontierPass(ctx, params), new MopFrontierPass(ctx, params),
                new CoveragePass(ctx, params), new FormCompletionPass(ctx, params));
        List<ScoringPass> mounted = new ArrayList<>();
        for (ScoringPass p : all) {
            if (p.isEnabled()) {
                mounted.add(p);
            }
        }
        return mounted;
    }

    /** Runs {@code passes} in the given order, as the pipeline does, from base priority 10. */
    private static void run(List<ScoringPass> passes, State state, ModelAction[] actions,
            ScoringContext ctx) {
        for (ModelAction a : actions) {
            a.setPriority(10);
        }
        new ScoringPipeline(passes).apply(state, actions, ctx);
    }

    private ModelAction[] mopScreen() throws Exception {
        return new ModelAction[] {button("go"), button("plain"), button("other"),
                nonTarget(ActionType.MODEL_MENU)};
    }

    private ModelAction[] formScreen() throws Exception {
        return new ModelAction[] {editText("field"), button("send")};
    }

    private void assertOrderFree(String activity, ModelAction[] forward, ModelAction[] reverse)
            throws Exception {
        StubScoringContext ctx = context();
        List<ScoringPass> passes = roster(AS_MOP, ctx);
        assertEquals("the as_mop cell mounts all seven", 7, passes.size());
        List<ScoringPass> reversed = new ArrayList<>(passes);
        Collections.reverse(reversed);

        run(passes, state(activity, forward), forward, ctx);
        run(reversed, state(activity, reverse), reverse, ctx);

        for (int i = 0; i < forward.length; i++) {
            assertArrayEquals("action " + i + ": priority and the scorer fields",
                    fieldsOf(forward[i]), fieldsOf(reverse[i]));
        }
    }

    /** Scenario "the order of the chain does not matter", on the MOP-bearing screen. */
    @Test
    public void theOrderOfTheChainChangesNoFieldOnTheMopScreen() throws Exception {
        ModelAction[] forward = mopScreen();
        assertOrderFree(ACT, forward, mopScreen());
        ModelAction go = forward[0];
        assertTrue("the screen exercises the MOP widget term", go.getMopBoost() > 0);
        assertEquals("WTG-MOP", 200, go.getWtgBoost());
        assertEquals("navigation frontier", 200, go.getNavFrontierBoost());
        assertEquals("MOP frontier", 200, go.getMopFrontierBoost());
        assertEquals("the plain edge earns the navigation frontier only",
                200, forward[1].getNavFrontierBoost());
        assertEquals(0, forward[1].getWtgBoost());
        assertEquals("the menu gateway", 250, forward[3].getMenuBoost());
    }

    /** The same, on a form screen with no MOP-weighted action. */
    @Test
    public void theOrderOfTheChainChangesNoFieldOnTheFormScreen() throws Exception {
        ModelAction[] forward = formScreen();
        assertOrderFree(FORM, forward, formScreen());
        assertTrue("the screen exercises the form term", forward[0].getFormBoost() > 0);
        assertTrue("the lone button is the submit candidate", forward[1].getFormBoost() > 0);
    }

    /** Scenario "mounting a scorer does not change another's term". */
    @Test
    public void mountingCoverageChangesNoOtherTerm() throws Exception {
        StubScoringContext ctx = context();
        ModelAction[] with = mopScreen();
        ModelAction[] without = mopScreen();
        run(roster(AS_MOP, ctx), state(ACT, with), with, ctx);
        run(roster(new ScoringParams(250, 200, 200, 200, 0, true), ctx), state(ACT, without),
                without, ctx);
        for (int i = 0; i < with.length; i++) {
            assertEquals(with[i].getMopBoost(), without[i].getMopBoost());
            assertEquals(with[i].getMopWeight(), without[i].getMopWeight());
            assertEquals(with[i].getWtgBoost(), without[i].getWtgBoost());
            assertEquals(with[i].getNavFrontierBoost(), without[i].getNavFrontierBoost());
            assertEquals(with[i].getMopFrontierBoost(), without[i].getMopFrontierBoost());
            assertEquals(with[i].getMenuBoost(), without[i].getMenuBoost());
            assertEquals(with[i].getPriority() - with[i].getCoverageBoost(),
                    without[i].getPriority());
        }
    }

    /** Scenario "a scorer does not read priority": base 32 and 52, the same terms. */
    @Test
    public void noPassReadsThePriority() throws Exception {
        StubScoringContext ctx = context();
        ModelAction[] low = mopScreen();
        ModelAction[] high = mopScreen();
        State lowState = state(ACT, low);
        State highState = state(ACT, high);
        for (ModelAction a : low) {
            a.setPriority(32);
        }
        for (ModelAction a : high) {
            a.setPriority(52);
        }
        ScoringPipeline pipeline = new ScoringPipeline(roster(AS_MOP, ctx));
        pipeline.apply(lowState, low, ctx);
        pipeline.apply(highState, high, ctx);
        for (int i = 0; i < low.length; i++) {
            int[] l = fieldsOf(low[i]);
            int[] h = fieldsOf(high[i]);
            assertEquals("the priority keeps its base difference", l[0] + 20, h[0]);
            l[0] = 0;
            h[0] = 0;
            assertArrayEquals("action " + i + ": the same terms", l, h);
        }
    }
}
