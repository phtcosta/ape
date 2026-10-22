package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.StaticGuiModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The launcher's three counters, which are three different things people conflate.
 *
 * <p>The cadence counter advances at every step open and is reset at the firing point whatever the
 * scan finds; the launch budget is
 * spent only on an actual launch; the round-robin cursor advances at every firing and persists across
 * them. A stage that reset all three together, or none, would still fire at plausible-looking
 * intervals — which is why each has its own assertion here rather than one end-to-end one.
 *
 * <p>The pure seams the stage walks ({@code shouldFire}, {@code selectTriggerCandidate}) keep their
 * own tests in {@code ActivityFrontierTest}; what is under test here is the state around them.
 * The stage no longer builds the deep-link URI — it reads {@code ComponentInfo.deepLinkUri} from
 * the wire, and the assembly rule's tests live in the generator repository (rv-android
 * {@code test_derive_mop_artifact.py}, the {@code test_deep_link_*} family), not in
 * {@code ActivityFrontierTest}.
 */
public class MopLauncherStageTest {

    private static final String PACKAGE = "com.example.app";
    private static final String MAIN = "com.example.app.MainActivity";

    private static ComponentInfo.ActivityInfo activity(String className) {
        return new ComponentInfo.ActivityInfo(className, false,
                java.util.Collections.<ComponentInfo.IntentFilter>emptyList(), null);
    }

    /**
     * The census the launcher reads, split as the loader splits it: the activities, package and
     * main activity in the static model, the MOP activity set (and, below, the distances) in the
     * MOP data.
     */
    private static final class Census {
        final MopData mop;
        final StaticGuiModel statics;

        Census(MopData mop, StaticGuiModel statics) {
            this.mop = mop;
            this.statics = statics;
        }

        List<ComponentInfo.ActivityInfo> getActivities() {
            return statics.activities();
        }

        String getMainActivity() {
            return statics.mainActivity();
        }

        Set<String> getMopActivities() {
            return mop.getMopActivities();
        }
    }

    private static StaticGuiModel staticsOf(List<ComponentInfo.ActivityInfo> activities) {
        return new StaticGuiModel(PACKAGE, MAIN, null, null, null, null, activities, null);
    }

    private static Census censusOf(String... classNames) {
        List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
        for (String className : classNames) {
            activities.add(activity(className));
        }
        return new Census(MopData.forTest(null, new HashSet<>(Arrays.asList(classNames))),
                staticsOf(activities));
    }

    private static FakeStepContext stepOver(Census data) {
        FakeStepContext ctx = new FakeStepContext();
        ctx.mopData = data.mop;
        ctx.staticModel = data.statics;
        ctx.graph = new Graph(); // no activity node has been visited
        return ctx;
    }

    /**
     * The stage under test with the pipeline's step open around it: each {@link #decide} opens a
     * step on the routing, broadcasts {@code onStepOpen}, asks the stage and keeps its selection,
     * as the agent does when no post-selection restart replaces it — the path every step that
     * reaches the launcher takes. {@link #decideReplaced} is the same step with the selection
     * replaced; {@link #skip} is a step an earlier stage took.
     */
    private static final class Launcher {

        final StepRouting routing = new StepRouting(false, true, false);
        final List<String> abandoned = new ArrayList<>();
        final MopLauncherStage stage;

        Launcher(int cadence, int maxPerRun, MopScoring scoring, int dmax) {
            this.stage = new MopLauncherStage(cadence, maxPerRun, scoring, dmax, routing,
                    abandoned::add);
        }

        String name() {
            return stage.name();
        }

        StageResult decide(FakeStepContext ctx) {
            StageResult result = decideReplaced(ctx);
            if (result.kind() == StageResult.Kind.SELECT) {
                stage.onSelectionKept(ctx, result.action());
            }
            return result;
        }

        StageResult decideReplaced(FakeStepContext ctx) {
            routing.open(Double.NaN, 0.5, Double.NaN);
            stage.onStepOpen(ctx);
            return stage.decide(ctx);
        }

        void skip(FakeStepContext ctx) {
            routing.open(Double.NaN, 0.5, Double.NaN);
            stage.onStepOpen(ctx);
        }
    }

    private static Launcher launcher(int cadence, int maxPerRun, MopScoring scoring, int dmax) {
        return new Launcher(cadence, maxPerRun, scoring, dmax);
    }

    private static String launchedClass(StageResult result) {
        return ((ActivityTriggerAction) result.action()).getClassName();
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.MOP_LAUNCHER.stageName(),
                launcher(50, 0, MopScoring.FLAG, 6).name());
    }

    @Test
    public void testItFiresExactlyAtTheCadenceAndThenEveryCadencePasses() throws Exception {
        Launcher stage = launcher(3, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf("com.example.app.A", "com.example.app.B"));

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        StageResult third = stage.decide(ctx);

        assertEquals(StageResult.Kind.SELECT, third.kind());
        assertEquals("a launch is owned by the launcher (design D-B7)",
                "launcher", third.decisionSource());
        assertEquals(1, ctx.recorder.count(RunCounter.LAUNCHES));

        // The counter restarted at the firing point, so the next firing is a full cadence away.
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(StageResult.Kind.SELECT, stage.decide(ctx).kind());
    }

    @Test
    public void testTheCadenceResetsEvenWhenTheScanFindsNothing() throws Exception {
        Launcher stage = launcher(2, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf()); // an empty census: nothing to launch

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals("the firing point came up empty", StageResult.Kind.CONTINUE,
                stage.decide(ctx).kind());

        // A firing point that failed to reset would leave the counter at or past the cadence and
        // the launcher would fire on every step. Stocking the census proves the next firing point
        // arrives a full cadence later.
        Census stocked = censusOf("com.example.app.A");
        ctx.mopData = stocked.mop;
        ctx.staticModel = stocked.statics;
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals("the counter restarted at the empty firing point",
                StageResult.Kind.SELECT, stage.decide(ctx).kind());
    }

    @Test
    public void testStepsAnotherStageTookAdvanceTheCadence() throws Exception {
        // Task 7.9.4, INV-DP-08: the counter advances at every step open, so LLM-taken steps bring
        // the next launch closer, and a due step the LLM took defers the firing to the next step
        // that reaches the launcher.
        Launcher stage = launcher(3, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf("com.example.app.A"));

        stage.skip(ctx);
        stage.skip(ctx);
        stage.skip(ctx); // due here, but the LLM took the step
        stage.skip(ctx); // and here
        StageResult fired = stage.decide(ctx);

        assertEquals("the first step that reaches the launcher past its cadence fires",
                StageResult.Kind.SELECT, fired.kind());
        assertEquals("the counter restarted at the firing point", StageResult.Kind.CONTINUE,
                stage.decide(ctx).kind());
    }

    @Test
    public void testADueLauncherClaimsTheStepAndAnEmptyScanFallsBackAsIneligible()
            throws Exception {
        Launcher stage = launcher(1, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf());

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        stage.routing.record("sata", ctx.sink());

        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("a due launcher is the first claimer (D-B14.6)", "launcher", own.i);
        assertEquals("ineligible", own.fb);
        assertEquals(0, ctx.recorder.count(RunCounter.LAUNCHES));
    }

    @Test
    public void testALaunchOverANonEmptyBufferAbandonsIt() throws Exception {
        Launcher stage = launcher(1, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf("com.example.app.A"));
        ctx.actionBufferSize = 2;

        assertEquals(StageResult.Kind.SELECT, stage.decide(ctx).kind());
        assertEquals(Collections.singletonList("launcher"), stage.abandoned);
    }

    @Test
    public void testThePackageAndActivitiesComeFromTheStaticModel() throws Exception {
        Launcher stage = launcher(1, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf("com.example.app.A"));
        // The MOP census still names A; the static model is the other provider, and its package
        // is the one the launch must carry.
        ctx.staticModel = new StaticGuiModel("com.example.other", MAIN, null, null, null, null,
                ctx.staticModel.activities(), null);

        StageResult result = stage.decide(ctx);

        assertEquals("com.example.other",
                ((ActivityTriggerAction) result.action()).getPackageName());
    }

    @Test
    public void testTheBudgetIsSpentOnlyByAnActualLaunch() throws Exception {
        // Cap of 1. The first firing finds nothing (empty census) and must not consume the budget;
        // a stage that decremented at the firing point would never launch anything at all.
        Launcher stage = launcher(1, 1, MopScoring.FLAG, 6);
        FakeStepContext empty = stepOver(censusOf());
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(empty).kind());

        FakeStepContext stocked = stepOver(censusOf("com.example.app.A"));
        StageResult launched = stage.decide(stocked);
        assertEquals("the budget survived a firing that found nothing (INV-CT-12)",
                StageResult.Kind.SELECT, launched.kind());

        // And now it is spent.
        FakeStepContext more = stepOver(censusOf("com.example.app.B"));
        assertEquals("the cap bounds launches, not firings",
                StageResult.Kind.CONTINUE, stage.decide(more).kind());
    }

    /** A launch the agent does not keep commits nothing: the activity, budget and buffer stay. */
    @Test
    public void testAReplacedLaunchCommitsNothing() throws Exception {
        Launcher stage = launcher(1, 1, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(censusOf("com.example.app.A"));
        ctx.actionBufferSize = 2;

        assertEquals("com.example.app.A", launchedClass(stage.decideReplaced(ctx)));
        assertEquals(0, ctx.recorder.count(RunCounter.LAUNCHES));
        assertTrue("the buffer is left to the restart", stage.abandoned.isEmpty());

        assertEquals("the activity is still eligible and the budget unspent",
                "com.example.app.A", launchedClass(stage.decide(ctx)));
        assertEquals(1, ctx.recorder.count(RunCounter.LAUNCHES));
        assertEquals(Collections.singletonList("launcher"), stage.abandoned);
        FakeStepContext more = stepOver(censusOf("com.example.app.B"));
        assertEquals("the kept launch spent the budget",
                StageResult.Kind.CONTINUE, stage.decide(more).kind());
    }

    @Test
    public void testACapOfZeroIsUnlimited() throws Exception {
        Launcher stage = launcher(1, 0, MopScoring.FLAG, 6);

        for (int i = 0; i < 5; i++) {
            FakeStepContext ctx = stepOver(censusOf("com.example.app.A" + i));
            assertEquals(StageResult.Kind.SELECT, stage.decide(ctx).kind());
        }
    }

    @Test
    public void testTheRoundRobinCursorPersistsAcrossFirings() throws Exception {
        Launcher stage = launcher(1, 0, MopScoring.FLAG, 6);
        Census census = censusOf("com.example.app.A", "com.example.app.B", "com.example.app.C");
        FakeStepContext ctx = stepOver(census);

        String first = launchedClass(stage.decide(ctx));
        String second = launchedClass(stage.decide(ctx));

        // A cursor reset per firing would launch the same activity forever, which is exactly the
        // failure a census the run keeps failing to reach would hide (INV-CT-06).
        assertNotEquals("the walk resumes where it stopped", first, second);
        assertTrue(Arrays.asList("com.example.app.A", "com.example.app.B", "com.example.app.C")
                .contains(second));
    }

    // -------------------------------------------------------------------------
    // llm-coordinate-single-base Part B, design D20 (task 14.1): the distance order and its
    // rotation. Under `distance` the eligible activities are ordered by the smallest unretired
    // activityDist distance <= ape.mopLauncherDmax, then manifest order, those without one last;
    // the cursor holds the sort position of the last selection and the next candidate is the
    // first eligible activity after it, wrapping, whatever the launch result. Under `flag` nothing
    // changes.
    // -------------------------------------------------------------------------

    private static final String P = "com.example.app.P";
    private static final String Q = "com.example.app.Q";
    private static final String R = "com.example.app.R";

    /** The census of {@code classNames} with the given {@code activityDist} entries. */
    private static Census withDistances(Map<String, int[][]> activityDist, String... classNames)
            throws Exception {
        Census data = censusOf(classNames);
        FakeStepContext.setField(data.mop, "activityDist", activityDist);
        return data;
    }

    private static Map<String, int[][]> dist(Object... activityThenPairs) {
        Map<String, int[][]> m = new HashMap<>();
        for (int i = 0; i < activityThenPairs.length; i += 2) {
            m.put((String) activityThenPairs[i], (int[][]) activityThenPairs[i + 1]);
        }
        return m;
    }

    private static int[][] pairs(int... flat) {
        int[][] out = new int[flat.length / 2][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new int[] {flat[2 * i], flat[2 * i + 1]};
        }
        return out;
    }

    private static int[] counters(MopRetirement r) throws Exception {
        java.lang.reflect.Field f = MopRetirement.class.getDeclaredField("counters");
        f.setAccessible(true);
        return (int[]) f.get(r);
    }

    /** Retires {@code targets} of {@code r} (a retirement built to retire at one count). */
    private static void retire(MopRetirement r, int... targets) throws Exception {
        for (int i : targets) {
            counters(r)[i] = 1;
        }
    }

    /** Ten targets retiring at one count, {@code retired} already retired. */
    private static MopRetirement retirement(int... retired) throws Exception {
        MopRetirement r = new MopRetirement(10, 1);
        retire(r, retired);
        return r;
    }

    private static MopContext distanceContext(Census data, MopRetirement retirement, int dmax) {
        return new MopContext(data.mop, data.statics, MopScoring.DISTANCE, 500, 300, 500, 400, 300, 1, dmax,
                retirement);
    }

    /** The class the pure distance walk selects after {@code last}, or null. */
    private static String byDistance(Census data, Set<String> visited, MopRetirement retirement,
            int dmax, long last) {
        long position = MopLauncherStage.selectByDistance(data.getActivities(), visited,
                data.getMainActivity(), data.getMopActivities(), data.mop, retirement, dmax, last);
        return position < 0 ? null
                : data.getActivities().get(MopLauncherStage.manifestIndex(position)).className;
    }

    /** Scenario "distance mode launches the nearest eligible activity first". */
    @Test
    public void testDistanceOrdersByNearestThenManifestOrderAndVisitedLeave() throws Exception {
        Census data = withDistances(dist(P, pairs(4, 5), Q, pairs(0, 2, 7, 3), R, pairs(2, 2)),
                P, Q, R);
        Set<String> visited = new HashSet<>();
        long none = MopLauncherStage.NO_SELECTION;

        assertEquals(Q, byDistance(data, visited, null, 6, none));
        visited.add(Q);
        assertEquals("same distance 2, later in manifest order",
                R, byDistance(data, visited, null, 6, none));
        visited.add(R);
        assertEquals(P, byDistance(data, visited, null, 6, none));
        visited.add(P);
        assertNull(byDistance(data, visited, null, 6, none));
    }

    /** Scenario "distances beyond the bound and retired targets do not count". */
    @Test
    public void testDistancesBeyondDmaxAndRetiredTargetsDoNotCount() throws Exception {
        Census data = withDistances(dist(P, pairs(1, 7), Q, pairs(3, 1, 5, 4)), P, Q, R);
        MopRetirement retirement = retirement(3);
        Set<String> visited = new HashSet<>();
        long none = MopLauncherStage.NO_SELECTION;

        assertEquals("Q keyed 4 by its unretired target 5", Q,
                byDistance(data, visited, retirement, 6, none));
        visited.add(Q);
        assertEquals("P (only d = 7 > Dmax) and R (no entry) follow in manifest order", P,
                byDistance(data, visited, retirement, 6, none));
        visited.add(P);
        assertEquals(R, byDistance(data, visited, retirement, 6, none));

        // With Dmax 3 Q's unretired d = 4 no longer counts either: all three are keyless and
        // manifest order decides.
        assertEquals(P, byDistance(data, new HashSet<String>(), retirement, 3, none));
        // Without the retirement Q's key is 1 and it leads under the same bound.
        assertEquals(Q, byDistance(data, new HashSet<String>(), null, 3, none));
    }

    /** Ties by manifest order; activities without a distance after every one with one. */
    @Test
    public void testTiesGoToManifestOrderAndKeylessActivitiesComeLast() throws Exception {
        Census data = withDistances(dist(Q, pairs(1, 3), R, pairs(2, 3)), P, Q, R);
        Set<String> visited = new HashSet<>();
        assertEquals("Q and R tie at 3, Q first in the manifest; keyless P is last", Q,
                byDistance(data, visited, null, 6, MopLauncherStage.NO_SELECTION));
        visited.add(Q);
        assertEquals(R, byDistance(data, visited, null, 6, MopLauncherStage.NO_SELECTION));
        visited.add(R);
        assertEquals(P, byDistance(data, visited, null, 6, MopLauncherStage.NO_SELECTION));
    }

    /** Eligibility is unchanged under distance: a distance never makes an activity eligible. */
    @Test
    public void testDistanceNeverWidensEligibility() throws Exception {
        String about = "com.example.app.About";
        String framework = "androidx.activity.ComponentActivity";
        Census data = withDistances(dist(MAIN, pairs(0, 0), about, pairs(1, 0),
                framework, pairs(2, 0), Q, pairs(3, 5)), MAIN, framework, Q);
        List<ComponentInfo.ActivityInfo> activities = new ArrayList<>(data.getActivities());
        activities.add(activity(about)); // declared, outside the census
        data = new Census(data.mop, staticsOf(activities));

        assertEquals(Q, byDistance(data, new HashSet<String>(), null, 6,
                MopLauncherStage.NO_SELECTION));
        assertNull(byDistance(data, new HashSet<>(Collections.singleton(Q)), null, 6,
                MopLauncherStage.NO_SELECTION));
    }

    /**
     * Scenario "a refused launch is not retired under distance": the launch of Q is refused (Q stays
     * unvisited, so the graph never gains its node), the next firing selects the activity after Q
     * in the order, and Q is not selected again in the run (design D-B15).
     */
    @Test
    public void testARefusedLaunchIsPassedByTheRotationAndNotRepeated() throws Exception {
        Census data = withDistances(dist(P, pairs(4, 5), Q, pairs(0, 2, 7, 3), R, pairs(2, 2)),
                P, Q, R);
        MopRetirement retirement = retirement();
        Launcher stage = launcher(1, 0, MopScoring.DISTANCE, 6);
        FakeStepContext ctx = stepOver(data);
        ctx.mopContext = distanceContext(data, retirement, 6);

        assertEquals(Q, launchedClass(stage.decide(ctx)));
        assertEquals("the rotation moves past the refused Q", R, launchedClass(stage.decide(ctx)));
        assertEquals(P, launchedClass(stage.decide(ctx)));
        assertEquals("every activity launched once: nothing left to launch",
                StageResult.Kind.CONTINUE, stage.decide(ctx).kind());

        int[] counts = counters(retirement);
        for (int i = 0; i < counts.length; i++) {
            assertEquals("no target counted by a launch (INV-CT-14)", 0, counts[i]);
        }
    }

    /** The order is recomputed at each firing: a target retired since then no longer counts. */
    @Test
    public void testTheOrderIsRecomputedAtEachFiring() throws Exception {
        Census data = withDistances(dist(P, pairs(1, 1), Q, pairs(2, 2), R, pairs(3, 3)), P, Q, R);
        MopRetirement retirement = retirement();
        Launcher stage = launcher(1, 0, MopScoring.DISTANCE, 6);
        FakeStepContext ctx = stepOver(data);
        ctx.mopContext = distanceContext(data, retirement, 6);

        assertEquals(P, launchedClass(stage.decide(ctx))); // position (1, 0)
        retire(retirement, 2); // Q's only target: Q is keyless now and sorts after R
        assertEquals(R, launchedClass(stage.decide(ctx))); // position (3, 2)
        assertEquals("keyless Q comes after R", Q, launchedClass(stage.decide(ctx)));
    }

    /** Scenario "flag mode keeps the round-robin": activityDist and retirement are not read. */
    @Test
    public void testFlagModeKeepsTheRoundRobin() throws Exception {
        Census data = withDistances(dist(P, pairs(4, 5), Q, pairs(0, 2, 7, 3), R, pairs(2, 2)),
                P, Q, R);
        Launcher stage = launcher(1, 0, MopScoring.FLAG, 6);
        FakeStepContext ctx = stepOver(data);

        Set<String> launched = new HashSet<>();
        for (int rr = 0; rr < 3; rr++) {
            ComponentInfo expected = MopLauncherStage.selectTriggerCandidate(data.getActivities(),
                    launched, MAIN, rr, data.getMopActivities());
            assertEquals("the pre-change round-robin from the same cursor, over the activities "
                    + "not launched yet", expected.className, launchedClass(stage.decide(ctx)));
            launched.add(expected.className);
        }
        Set<String> none = Collections.emptySet();
        assertEquals("the round-robin starts at manifest index 0", P,
                MopLauncherStage.selectTriggerCandidate(data.getActivities(), none, MAIN, 0,
                        data.getMopActivities()).className);
    }

    // -------------------------------------------------------------------------
    // isolate-guidance-groups, design D-B15 (task 13.4): each activity is launched at most once per
    // run. A launch whose activity never opens (the graph never gains its node) leaves it eligible
    // by the visited test alone; the stage's launched set removes it, in both modes, without
    // reading the launch result (INV-CT-14).
    // -------------------------------------------------------------------------

    private static final String RECORD = "com.example.app.RecordAudioActivity";
    private static final String SEARCH = "com.example.app.SearchActivity";

    /**
     * Scenario "an activity whose launch did not open it is not launched again": RecordAudio is
     * launched first and never becomes visited; every later firing selects Search while it is
     * eligible, and then finds nothing.
     */
    private static void assertLaunchedOnce(MopScoring scoring) throws Exception {
        Census data = withDistances(dist(RECORD, pairs(0, 1), SEARCH, pairs(1, 2)), RECORD, SEARCH);
        Launcher stage = launcher(1, 0, scoring, 6);
        FakeStepContext ctx = stepOver(data);
        ctx.mopContext = distanceContext(data, retirement(), 6);

        assertEquals(RECORD, launchedClass(stage.decide(ctx)));
        // The app crashed: the graph still has no node for RecordAudio.
        assertEquals("the launched activity is not selected again", SEARCH,
                launchedClass(stage.decide(ctx)));
        assertEquals(2, ctx.recorder.count(RunCounter.LAUNCHES));
    }

    @Test
    public void testALaunchedUnvisitedActivityIsNotSelectedAgainUnderFlag() throws Exception {
        assertLaunchedOnce(MopScoring.FLAG);
    }

    @Test
    public void testALaunchedUnvisitedActivityIsNotSelectedAgainUnderDistance() throws Exception {
        assertLaunchedOnce(MopScoring.DISTANCE);
    }

    /**
     * With nothing else eligible, the firing after the launch finds no candidate: it falls back as
     * {@code ineligible} and spends no budget (INV-CT-12), under both modes.
     */
    @Test
    public void testWithNoOtherEligibleActivityTheFiringIsIneligibleAndSpendsNoBudget()
            throws Exception {
        for (MopScoring scoring : MopScoring.values()) {
            Census data = withDistances(dist(RECORD, pairs(0, 1)), RECORD);
            Launcher stage = launcher(1, 2, scoring, 6);
            FakeStepContext ctx = stepOver(data);
            ctx.mopContext = distanceContext(data, retirement(), 6);

            assertEquals(RECORD, launchedClass(stage.decide(ctx)));
            stage.routing.record("launcher", ctx.sink());
            assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
            stage.routing.record("sata", ctx.sink());

            RecordingSink.Own own = ctx.recorder.lastOwner();
            assertEquals(scoring.name(), "launcher", own.i);
            assertEquals(scoring.name(), "ineligible", own.fb);
            assertEquals(scoring.name(), 1, ctx.recorder.count(RunCounter.LAUNCHES));

            // The budget of 2 still has one launch: an activity that appears is launched.
            Census more = withDistances(dist(RECORD, pairs(0, 1)), RECORD, SEARCH);
            ctx.mopData = more.mop;
            ctx.staticModel = more.statics;
            ctx.mopContext = distanceContext(more, retirement(), 6);
            assertEquals(scoring.name(), SEARCH, launchedClass(stage.decide(ctx)));
        }
    }

    /** A graph whose only visited activity is {@code activity}. */
    private static Graph graphWithActivity(String activity) {
        return new Graph() {
            @Override
            public ActivityNode[] getActivityNodes() {
                return new ActivityNode[] {new ActivityNode(activity)};
            }
        };
    }

    /**
     * The launched set comes from the stage's selection, never from the launch result: whether the
     * launched activity then opens (the graph gains its node) or not, the next firing selects the
     * same activity (INV-CT-14).
     */
    @Test
    public void testTheLaunchResultIsNeverRead() throws Exception {
        for (MopScoring scoring : MopScoring.values()) {
            String[] next = new String[2];
            for (int opened = 0; opened < 2; opened++) {
                Census data = withDistances(dist(RECORD, pairs(0, 1), SEARCH, pairs(1, 2)),
                        RECORD, SEARCH, R);
                Launcher stage = launcher(1, 0, scoring, 6);
                FakeStepContext ctx = stepOver(data);
                ctx.mopContext = distanceContext(data, retirement(), 6);

                assertEquals(RECORD, launchedClass(stage.decide(ctx)));
                if (opened == 1) {
                    ctx.graph = graphWithActivity(RECORD);
                }
                next[opened] = launchedClass(stage.decide(ctx));
            }
            assertEquals(scoring.name(), next[0], next[1]);
        }
    }
}
