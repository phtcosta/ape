package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The launcher's three counters, which are three different things people conflate.
 *
 * <p>The cadence counter is reset at the firing point whatever the scan finds; the launch budget is
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
                java.util.Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                java.util.Collections.<String>emptyList(), null);
    }

    /**
     * MopData carrying just the census the launcher reads. Allocated rather than parsed: the loader
     * wants a JSON artifact, and every field below is one the stage names directly.
     */
    private static MopData censusOf(String... classNames) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        MopData data = (MopData) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), MopData.class);
        List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
        for (String className : classNames) {
            activities.add(activity(className));
        }
        FakeStepContext.setField(data, "activities", activities);
        FakeStepContext.setField(data, "packageName", PACKAGE);
        FakeStepContext.setField(data, "mainActivity", MAIN);
        FakeStepContext.setField(data, "mopActivities", new HashSet<>(Arrays.asList(classNames)));
        return data;
    }

    private static FakeStepContext stepOver(MopData data) {
        FakeStepContext ctx = new FakeStepContext();
        ctx.mopData = data;
        ctx.graph = new Graph(); // no activity node has been visited
        return ctx;
    }

    private static String launchedClass(StageResult result) {
        return ((ActivityTriggerAction) result.action()).getClassName();
    }

    @Test
    public void testStageNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.MOP_LAUNCHER.stageName(),
                new MopLauncherStage(50, 0).name());
    }

    @Test
    public void testItFiresExactlyAtTheCadenceAndThenEveryCadencePasses() throws Exception {
        MopLauncherStage stage = new MopLauncherStage(3, 0);
        FakeStepContext ctx = stepOver(censusOf("com.example.app.A", "com.example.app.B"));

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        StageResult third = stage.decide(ctx);

        assertEquals(StageResult.Kind.SELECT, third.kind());
        assertEquals("a launch is a non-model decision attributed to the trigger",
                "Component", third.decisionSource());

        // The counter restarted at the firing point, so the next firing is a full cadence away.
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals(StageResult.Kind.SELECT, stage.decide(ctx).kind());
    }

    @Test
    public void testTheCadenceResetsEvenWhenTheScanFindsNothing() throws Exception {
        MopLauncherStage stage = new MopLauncherStage(2, 0);
        FakeStepContext ctx = stepOver(censusOf()); // an empty census: nothing to launch

        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals("the firing point came up empty", StageResult.Kind.CONTINUE,
                stage.decide(ctx).kind());

        // The gate is equality against the cadence, so a firing point that failed to reset would
        // leave the counter climbing past it and the launcher would never fire again for the rest of
        // the run. Stocking the census proves the next firing point still arrives on schedule.
        ctx.mopData = censusOf("com.example.app.A");
        assertEquals(StageResult.Kind.CONTINUE, stage.decide(ctx).kind());
        assertEquals("the counter restarted at the empty firing point",
                StageResult.Kind.SELECT, stage.decide(ctx).kind());
    }

    @Test
    public void testTheBudgetIsSpentOnlyByAnActualLaunch() throws Exception {
        // Cap of 1. The first firing finds nothing (empty census) and must not consume the budget;
        // a stage that decremented at the firing point would never launch anything at all.
        MopLauncherStage stage = new MopLauncherStage(1, 1);
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

    @Test
    public void testACapOfZeroIsUnlimited() throws Exception {
        MopLauncherStage stage = new MopLauncherStage(1, 0);

        for (int i = 0; i < 5; i++) {
            FakeStepContext ctx = stepOver(censusOf("com.example.app.A" + i));
            assertEquals(StageResult.Kind.SELECT, stage.decide(ctx).kind());
        }
    }

    @Test
    public void testTheRoundRobinCursorPersistsAcrossFirings() throws Exception {
        MopLauncherStage stage = new MopLauncherStage(1, 0);
        MopData census = censusOf("com.example.app.A", "com.example.app.B", "com.example.app.C");
        FakeStepContext ctx = stepOver(census);

        String first = launchedClass(stage.decide(ctx));
        String second = launchedClass(stage.decide(ctx));

        // A cursor reset per firing would launch the same activity forever, which is exactly the
        // failure a census the run keeps failing to reach would hide (INV-CT-06).
        assertNotEquals("the walk resumes where it stopped", first, second);
        assertTrue(Arrays.asList("com.example.app.A", "com.example.app.B", "com.example.app.C")
                .contains(second));
    }
}
