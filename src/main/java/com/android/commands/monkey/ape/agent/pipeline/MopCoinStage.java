/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.agent.pipeline;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.android.commands.monkey.ape.agent.FormCompletion;
import com.android.commands.monkey.ape.agent.MopInteractionLedger;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

/**
 * The MOP group's decider: take an interaction with a MOP-targeted action whose key is still within
 * the coin's budget k, at the plan's dose {@code p_M} (mop-guidance "MOP Coin Decider", design
 * D-A10, D-A15, D-B10, D-B17).
 *
 * <p>Placed after the LLM stages, the launcher and the component trigger and before the SATA chain,
 * so with the LLM mounted it decides only the steps the LLM leaves. The step is <b>eligible</b> when
 * the state offers a valid, enabled, target-requiring action that
 * <ol>
 *   <li>has a positive undivided {@link ModelAction#getMopWeight()} (under {@code distance}, an
 *       unretired target);
 *   <li>has fewer than k executions in {@link MopInteractionLedger} for its (activity,
 *       {@code xpath|ActionType}) key, whatever their owners ({@code belowBudget}; k = 1 by
 *       default, so an untried key) — visit count is not a condition;
 *   <li>is not the submit candidate form completion excludes, when {@code FORM_COMPLETION} is
 *       mounted (INV-FORM-06).
 * </ol>
 * The stage selects when the step is eligible and {@code u_M < p_M}, {@code u_M} drawn at step
 * open from the MOP coin's own stream ({@link StepRouting#uM()}, INV-MOP-39).
 *
 * <p><b>Ranking</b> (INV-MOP-38), each key consulted only to break a tie on the keys before it: the
 * larger {@code mopWeight}; under {@code distance}, the larger count of distinct unretired targets
 * within {@code d + 1} of the weight's lookup; then the first in {@code State.getActions()} order.
 * Priority is not a key, so no ADDONS or STATIC scorer can steer the coin (R1).
 *
 * <p>A step the coin takes is owned by {@code mop} on the {@code mop_coin} channel, counts
 * {@code coinMop}, and abandons a non-empty buffer without weakening ({@code dec.buf:"mop"}). Its
 * {@code dec.mopd}/{@code dec.mops}/{@code dec.hnd} ride the decision record from the action's
 * lookup fields.
 */
public final class MopCoinStage implements DecisionStage {

    /** The coin's owner value, {@code dec.src}. */
    public static final String OWNER = "mop";

    private final double percentage;
    private final boolean formCompletion;
    private final StepRouting routing;
    private final Supplier<MopInteractionLedger> ledger;
    private final Consumer<String> abandonBuffer;

    /**
     * @param percentage {@code p_M}, the plan's clamped dose; positive, since a zero dose assembles
     *        no stage
     * @param formCompletion whether {@code FORM_COMPLETION} is mounted, which excludes the submit
     *        candidate of an unfilled form
     * @param routing the pipeline's per-step routing record, which holds {@code u_M}
     * @param ledger the run's execution ledger, built with k, {@code StageCollaborators.mopLedger}
     * @param abandonBuffer the agent's buffer abandonment without weakening
     */
    public MopCoinStage(double percentage, boolean formCompletion, StepRouting routing,
            Supplier<MopInteractionLedger> ledger, Consumer<String> abandonBuffer) {
        this.percentage = percentage;
        this.formCompletion = formCompletion;
        this.routing = routing;
        this.ledger = ledger;
        this.abandonBuffer = abandonBuffer;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.MOP_COIN.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (!(routing.uM() < percentage)) {
            return StageResult.continueChain();
        }
        State state = ctx.newState();
        ModelAction excluded = null;
        if (formCompletion && FormCompletion.hasUnfilledEditText(state, ctx.timestamp())) {
            excluded = FormCompletion.selectSubmitCandidate(state, ctx.timestamp());
        }
        ModelAction pick = pick(state, excluded, ledger.get(), ctx.mopContext());
        if (pick == null) {
            return StageResult.continueChain();
        }
        routing.claim(OWNER);
        pick.setDecisionSource(ModelAction.DecisionSource.MOP);
        pick.setPickChannel(ModelAction.PickChannel.MOP_COIN);
        ctx.sink().count(RunCounter.COIN_MOP);
        if (ctx.actionBufferSize() > 0) {
            abandonBuffer.accept(OWNER);
        }
        return StageResult.select(pick, OWNER);
    }

    /**
     * The coin's choice among the eligible actions of {@code state}, or {@code null} when the step
     * is not eligible. Pure: the ledger and the context's retirement are read, never written.
     *
     * @param state the step's state; its {@code getActions()} order is the last key
     * @param excluded the submit candidate form completion excludes, or {@code null}
     * @param ledger the run's execution ledger, built with k
     * @param mop the run's MOP context: the scoring mode and the retirement
     * @return the eligible action that ranks first, or {@code null}
     */
    static ModelAction pick(State state, ModelAction excluded, MopInteractionLedger ledger,
            MopContext mop) {
        String activity = state.getActivity();
        boolean byTargets = mop != null && mop.scoring() == MopScoring.DISTANCE;
        ModelAction best = null;
        int bestTargets = -1; // computed on the first tie that needs it
        for (ModelAction action : state.getActions()) {
            if (!eligible(action, excluded, ledger, activity)) {
                continue;
            }
            if (best == null) {
                best = action;
                bestTargets = -1;
                continue;
            }
            int cmp = Integer.compare(action.getMopWeight(), best.getMopWeight());
            int targets = -1;
            if (cmp == 0 && byTargets) {
                if (bestTargets < 0) {
                    bestTargets = targetCount(mop, activity, best);
                }
                targets = targetCount(mop, activity, action);
                cmp = Integer.compare(targets, bestTargets);
            }
            if (cmp > 0) {
                best = action;
                bestTargets = targets;
            }
        }
        return best;
    }

    /**
     * The three conditions of design D-B10, over a valid, enabled, target-requiring action, the
     * ledger's read being the budget of D-B17.
     */
    private static boolean eligible(ModelAction action, ModelAction excluded,
            MopInteractionLedger ledger, String activity) {
        return action != excluded
                && action.isValid() && action.isEnabled() && action.requireTarget()
                && action.getMopWeight() > 0
                && ledger.belowBudget(activity, UICoverageTracker.widgetId(action));
    }

    /**
     * The second key under {@code distance}: the number of distinct unretired targets within
     * {@code d + 1} over the pairs of the action's weight's lookup, {@code d} being the distance
     * behind its weight — both from the one {@link MopScorer#weigh}; 0 when no lookup has an
     * unretired pair.
     */
    private static int targetCount(MopContext mop, String activity, ModelAction action) {
        MopScorer.Scored scored = MopScorer.weigh(mop, activity, action);
        if (scored.distance == Integer.MAX_VALUE) {
            return 0;
        }
        return MopRetirement.countWithin(mop.retirement(), scored.lookup.dist,
                scored.distance + 1);
    }
}
