package com.android.commands.monkey.ape.agent.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.android.commands.monkey.ape.agent.MopInteractionLedger;
import com.android.commands.monkey.ape.llm.PromptFixtures;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.StaticArtifactLoader;
import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The MOP coin (task 7.9.3; mop-guidance "MOP Coin Decider", design D-B10, D-B17): eligibility
 * and its budget k, the ranking, the dose {@code p_M} and the submit exclusion.
 */
public class MopCoinStageTest {

    private static final String ACTIVITY = "com.example.MainActivity";

    /** A valid, enabled click on its own widget, carrying {@code weight}. */
    private static ModelAction target(String xpath, int weight) {
        ModelAction action = new ModelAction(null, new PromptFixtures.TestName(xpath),
                ActionType.MODEL_CLICK);
        action.setValid(true);
        action.setMopWeight(weight);
        return action;
    }

    /** A state on {@link #ACTIVITY} offering {@code actions}, in that order. */
    private static State stateOf(ModelAction... actions) throws Exception {
        State state = FakeStepContext.stateWith(ACTIVITY, 0);
        FakeStepContext.setField(state, "actions", actions);
        return state;
    }

    private static final class Coin {
        final StepRouting routing = new StepRouting(false, true, false);
        final MopInteractionLedger ledger = new MopInteractionLedger(1);
        final List<String> abandoned = new ArrayList<>();
        final MopCoinStage stage;

        Coin(double percentage, boolean formCompletion) {
            stage = new MopCoinStage(percentage, formCompletion, routing, () -> ledger,
                    abandoned::add);
        }

        /** A step the coin decides, whose pick the agent keeps, as it does with no restart due. */
        StageResult decide(FakeStepContext ctx, double uM) {
            StageResult result = decideReplaced(ctx, uM);
            if (result.kind() == StageResult.Kind.SELECT) {
                stage.onSelectionKept(ctx, result.action());
            }
            return result;
        }

        /** The same step, with the pick replaced by a post-selection restart. */
        StageResult decideReplaced(FakeStepContext ctx, double uM) {
            routing.open(Double.NaN, uM, Double.NaN);
            stage.onStepOpen(ctx);
            return stage.decide(ctx);
        }
    }

    private static FakeStepContext stepOn(State state) {
        FakeStepContext ctx = new FakeStepContext();
        ctx.newState = state;
        return ctx;
    }

    @Test
    public void theNameIsItsCandidateName() {
        assertEquals(DecisionPipeline.Candidate.MOP_COIN.stageName(),
                new Coin(1.0, false).stage.name());
    }

    @Test
    public void anEligibleStepIsTakenAtTheDefaultDose() throws Exception {
        ModelAction x = target("/x", 500);
        ModelAction y = target("/y", 0);
        Coin coin = new Coin(1.0, false);
        FakeStepContext ctx = stepOn(stateOf(y, x));

        StageResult result = coin.decide(ctx, 0.99);

        assertEquals(StageResult.Kind.SELECT, result.kind());
        assertSame(x, result.action());
        assertEquals("mop", result.decisionSource());
        assertEquals(ModelAction.DecisionSource.MOP, x.getDecisionSource());
        assertEquals(ModelAction.PickChannel.MOP_COIN, x.getPickChannel());
        assertEquals(1, ctx.recorder.count(RunCounter.COIN_MOP));
    }

    @Test
    public void theLargestWeightWinsAndPriorityIsIgnored() throws Exception {
        ModelAction low = target("/low", 400);
        low.setPriority(900);
        ModelAction high = target("/high", 500);
        high.setPriority(532);

        assertSame(high, MopCoinStage.pick(stateOf(low, high), null, new MopInteractionLedger(1),
                null));
    }

    @Test
    public void equalOnEveryKeyTheFirstInActionOrderWins() throws Exception {
        ModelAction x = target("/x", 500);
        ModelAction y = target("/y", 500);

        assertSame(x, MopCoinStage.pick(stateOf(x, y), null, new MopInteractionLedger(1), null));
    }

    @Test
    public void anActionTriedInTheActivityIsNotEligible() throws Exception {
        ModelAction w = target("/w", 500);
        MopInteractionLedger ledger = new MopInteractionLedger(1);
        ledger.record(ACTIVITY, UICoverageTracker.widgetId(w));

        assertNull(MopCoinStage.pick(stateOf(w), null, ledger, null));
    }

    /**
     * Design D-B17: with k = 3 the coin stays eligible for a key until the ledger counts three
     * executions of it, whatever their owners (the stage reads only the count).
     */
    @Test
    public void withBudgetThreeTheCoinTakesAKeyThreeTimes() throws Exception {
        ModelAction w = target("/w", 500);
        String key = UICoverageTracker.widgetId(w);
        MopInteractionLedger ledger = new MopInteractionLedger(3);

        for (int executions = 0; executions < 3; executions++) {
            assertSame("eligible after " + executions + " executions", w,
                    MopCoinStage.pick(stateOf(w), null, ledger, null));
            ledger.record(ACTIVITY, key);
        }
        assertNull("the third execution exhausts k",
                MopCoinStage.pick(stateOf(w), null, ledger, null));
    }

    @Test
    public void visitCountIsNoConditionOnlyTheLedgerIs() throws Exception {
        ModelAction tried = target("/x", 300);
        ModelAction visited = target("/y", 300);
        visited.visitedAt(1);
        visited.visitedAt(2);
        MopInteractionLedger ledger = new MopInteractionLedger(1);
        ledger.record(ACTIVITY, UICoverageTracker.widgetId(tried));

        assertSame(visited, MopCoinStage.pick(stateOf(tried, visited), null, ledger, null));
    }

    @Test
    public void invalidDisabledOrTargetlessActionsAreNotEligible() throws Exception {
        ModelAction invalid = target("/a", 500);
        invalid.setValid(false);
        ModelAction disabled = target("/b", 500);
        disabled.setEnabled(false);
        ModelAction back = new ModelAction(null, ActionType.MODEL_BACK);
        back.setValid(true);
        back.setMopWeight(500);

        assertNull(MopCoinStage.pick(stateOf(invalid, disabled, back), null,
                new MopInteractionLedger(1), null));
    }

    @Test
    public void theExcludedSubmitIsSkipped() throws Exception {
        ModelAction submit = target("/submit", 500);

        assertNull("the form-completion exclusion leaves no eligible action",
                MopCoinStage.pick(stateOf(submit), submit, new MopInteractionLedger(1), null));
        assertSame("without the exclusion the submit is eligible", submit,
                MopCoinStage.pick(stateOf(submit), null, new MopInteractionLedger(1), null));
    }

    @Test
    public void theCoinLoses() throws Exception {
        Coin coin = new Coin(0.4, false);
        FakeStepContext ctx = stepOn(stateOf(target("/x", 500)));

        StageResult result = coin.decide(ctx, 0.7);
        coin.routing.record("sata", ctx.sink());

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("a lost coin claims nothing", "sata", own.i);
        assertEquals(0.7, own.uM, 0.0);
        assertEquals(0, ctx.recorder.count(RunCounter.COIN_MOP));
    }

    @Test
    public void anIneligibleStepContinuesWithNoClaim() throws Exception {
        Coin coin = new Coin(1.0, false);
        FakeStepContext ctx = stepOn(stateOf(target("/x", 0)));

        assertEquals(StageResult.Kind.CONTINUE, coin.decide(ctx, 0.0).kind());
        coin.routing.record("sata", ctx.sink());
        assertEquals("sata", ctx.recorder.lastOwner().i);
    }

    @Test
    public void aTakenStepAbandonsANonEmptyBuffer() throws Exception {
        Coin coin = new Coin(1.0, false);
        FakeStepContext ctx = stepOn(stateOf(target("/x", 500)));
        ctx.actionBufferSize = 2;

        coin.decide(ctx, 0.0);

        assertEquals(Collections.singletonList("mop"), coin.abandoned);
    }

    /** A pick the agent does not keep counts no {@code coinMop} and leaves the buffer. */
    @Test
    public void aReplacedPickCountsNothingAndLeavesTheBuffer() throws Exception {
        Coin coin = new Coin(1.0, false);
        FakeStepContext ctx = stepOn(stateOf(target("/x", 500)));
        ctx.actionBufferSize = 2;

        assertEquals(StageResult.Kind.SELECT, coin.decideReplaced(ctx, 0.0).kind());

        assertEquals(0, ctx.recorder.count(RunCounter.COIN_MOP));
        assertTrue(coin.abandoned.isEmpty());
    }

    // -------------------------------------------------------------------------
    // The second key under distance: unretired targets within d + 1 (gh-tbd-mop-weights-list)
    // -------------------------------------------------------------------------

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void resetThePlan() {
        RunContext.resetForTest();
    }

    /** A format-2 artifact whose widgets of {@link #ACTIVITY} carry the given click lists. */
    private String artifact(int targets, String... idAndList) throws Exception {
        StringBuilder widgets = new StringBuilder();
        for (int i = 0; i < idAndList.length; i += 2) {
            widgets.append(i == 0 ? "" : ",").append('"').append(idAndList[i])
                    .append("\":{\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":")
                    .append(idAndList[i + 1]).append("}}");
        }
        File f = folder.newFile();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"formatVersion\":2,\"package\":\"com.example\",\"mainActivity\":\""
                    + ACTIVITY + "\",\"targets\":" + targets + ",\"handlers\":{},"
                    + "\"mopActivities\":[\"" + ACTIVITY + "\"],"
                    + "\"widgets\":{\"" + ACTIVITY + "\":{" + widgets + "}}}");
        }
        return f.getAbsolutePath();
    }

    /** Loads under a distance plan with {@code ladder}, through the public entry, as a run does. */
    private static MopData loadUnder(String path, String ladder) {
        TestRunSpecs.installMop("ape.mopScoring", "distance", "ape.mopWeights", ladder);
        StaticArtifactLoader.Loaded loaded = StaticArtifactLoader.load(path, null, null, new NoopSink());
        assertNotNull(loaded);
        return loaded.mopData;
    }

    private static MopContext distance(MopData data, int[] ladder, MopRetirement retirement) {
        return new MopContext(data, StaticGuiModel.EMPTY, MopScoring.DISTANCE, 500, 300, ladder, 3, 6,
                retirement);
    }

    private static GUITreeNode node(String shortId) {
        GUITreeNode n = new GUITreeNode(null);
        n.setResourceID("com.example:id/" + shortId);
        return n;
    }

    /**
     * A click on widget {@code shortId} whose {@code mopWeight} is the scorer's undivided weight
     * under {@code mop}, as {@code MopWidgetPass} sets it.
     */
    private static ModelAction scored(MopContext mop, String shortId) throws Exception {
        ModelAction action = target("//" + shortId, 0);
        GUITreeNode resolved = node(shortId);
        action.resolveAt(1, 0, null, resolved, new GUITreeNode[] {resolved});
        action.setMopWeight(MopScorer.weigh(mop, ACTIVITY, action).weight);
        return action;
    }

    /** Scenario "Window boundary": the window is d + 1, and a tie on both keys goes to order. */
    @Test
    public void theSecondKeyCountsTheTargetsWithinDPlusOne() throws Exception {
        int[] ladder = {500, 400, 300, 300, 300};
        MopData data = loadUnder(artifact(10, "x", "[[0,1],[1,2],[2,3]]", "y", "[[5,1],[6,1],[7,3]]"),
                "500,400,300,300,300");
        MopContext mop = distance(data, ladder, new MopRetirement(10, 3));
        ModelAction x = scored(mop, "x");
        ModelAction y = scored(mop, "y");
        assertEquals(500, x.getMopWeight());
        assertEquals(500, y.getMopWeight());
        assertEquals("targets 0 and 1, not 2", 2, MopCoinStage.targetCount(mop, ACTIVITY, x));
        assertEquals(2, MopCoinStage.targetCount(mop, ACTIVITY, y));

        MopInteractionLedger ledger = new MopInteractionLedger(1);
        assertSame(x, MopCoinStage.pick(stateOf(x, y), null, ledger, mop));
        assertSame(y, MopCoinStage.pick(stateOf(y, x), null, ledger, mop));
    }

    /** Scenario "Retired target excluded". */
    @Test
    public void aRetiredTargetInTheWindowIsNotCounted() throws Exception {
        MopData data = loadUnder(artifact(4, "x", "[[0,1],[1,2]]", "r", "[[1,2]]", "z", "[[1,1]]"),
                "500,400,300");
        MopRetirement retirement = new MopRetirement(4, 1);
        retirement.onFirstInteraction(data.lookup(ACTIVITY, node("r"), "click"));
        assertTrue(retirement.retired(1));
        MopContext mop = distance(data, new int[] {500, 400, 300}, retirement);

        ModelAction x = scored(mop, "x");
        assertEquals(500, x.getMopWeight());
        assertEquals("target 0 only", 1, MopCoinStage.targetCount(mop, ACTIVITY, x));

        ModelAction z = scored(mop, "z");
        assertEquals("every target of the lookup is retired", 0, z.getMopWeight());
        assertEquals(0, MopCoinStage.targetCount(mop, ACTIVITY, z));
        assertNull("weighing 0, the action is not eligible",
                MopCoinStage.pick(stateOf(z), null, new MopInteractionLedger(1), mop));
    }

    /** Scenario "Window never passes the cut": the pairs beyond c = 3 were dropped at load. */
    @Test
    public void theWindowNeverPassesTheCut() throws Exception {
        String path = artifact(4, "w", "[[0,3],[1,3],[2,4],[3,4]]");
        TestRunSpecs.installMop("ape.mopScoring", "distance", "ape.mopWeights", "500,400,300");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MopData data = StaticArtifactLoader.load(path, null, null,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8"))).mopData;
        JSONObject record = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim());
        assertEquals(2, record.getInt("cutPairs"));

        MopContext mop = distance(data, new int[] {500, 400, 300}, new MopRetirement(4, 3));
        ModelAction w = scored(mop, "w");
        assertEquals("scored at d = 3", 300, w.getMopWeight());
        assertEquals("targets 0 and 1; the d = 4 pairs were cut", 2,
                MopCoinStage.targetCount(mop, ACTIVITY, w));
    }
}
