package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.agent.MopInteractionLedger;
import com.android.commands.monkey.ape.llm.PromptFixtures;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * The MOP coin (task 7.9.3; mop-guidance "MOP Coin Decider", design D-B10): eligibility, the
 * ranking, the dose {@code p_M} and the submit exclusion.
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
        final MopInteractionLedger ledger = new MopInteractionLedger();
        final List<String> abandoned = new ArrayList<>();
        final MopCoinStage stage;

        Coin(double percentage, boolean formCompletion) {
            stage = new MopCoinStage(percentage, formCompletion, routing, () -> ledger,
                    abandoned::add);
        }

        StageResult decide(FakeStepContext ctx, double uM) {
            routing.open(Double.NaN, uM, Double.NaN);
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

        assertSame(high, MopCoinStage.pick(stateOf(low, high), null, new MopInteractionLedger(),
                null));
    }

    @Test
    public void equalOnEveryKeyTheFirstInActionOrderWins() throws Exception {
        ModelAction x = target("/x", 500);
        ModelAction y = target("/y", 500);

        assertSame(x, MopCoinStage.pick(stateOf(x, y), null, new MopInteractionLedger(), null));
    }

    @Test
    public void anActionTriedInTheActivityIsNotEligible() throws Exception {
        ModelAction w = target("/w", 500);
        MopInteractionLedger ledger = new MopInteractionLedger();
        ledger.record(ACTIVITY, UICoverageTracker.widgetId(w));

        assertNull(MopCoinStage.pick(stateOf(w), null, ledger, null));
    }

    @Test
    public void visitCountIsNoConditionOnlyTheLedgerIs() throws Exception {
        ModelAction tried = target("/x", 300);
        ModelAction visited = target("/y", 300);
        visited.visitedAt(1);
        visited.visitedAt(2);
        MopInteractionLedger ledger = new MopInteractionLedger();
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
                new MopInteractionLedger(), null));
    }

    @Test
    public void theExcludedSubmitIsSkipped() throws Exception {
        ModelAction submit = target("/submit", 500);

        assertNull("the form-completion exclusion leaves no eligible action",
                MopCoinStage.pick(stateOf(submit), submit, new MopInteractionLedger(), null));
        assertSame("without the exclusion the submit is eligible", submit,
                MopCoinStage.pick(stateOf(submit), null, new MopInteractionLedger(), null));
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
}
