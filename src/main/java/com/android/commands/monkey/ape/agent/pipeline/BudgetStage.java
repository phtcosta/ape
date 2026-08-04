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

import java.util.function.Supplier;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * The gh9 activity budget: leave an activity whose per-activity budget is spent, if there is
 * somewhere useful to go.
 *
 * <p><b>The budget decides the step when it can.</b> An exhausted activity plus a reachable trivial
 * activity ends the step here with {@code DecisionSource.Budget}; every later stage, including the
 * LLM hooks, is preempted. That is worth stating because the block reads as advisory and is not: the
 * "advisory" half is only what happens when there is nowhere to go.
 *
 * <p><b>And it is advisory when it cannot.</b> Exhausted with no trivial activity reachable, the
 * stage passes and the rest of the pipeline explores the exhausted activity as usual — no BACK, no
 * restart. The two mechanisms that ended the step instead (a forced {@code EVENT_RESTART}, a
 * {@code MODEL_BACK}) each produced a loop, which is why the budget spends its influence on
 * navigation or not at all.
 *
 * <p><b>Where the enabled check went.</b> The block tested {@code Config.activityBudgetEnabled}
 * before touching the tracker, because a disabled run has no tracker to touch. That conjunct is now
 * the assembly condition: the stage exists exactly on plans carrying the activity-budget feature
 * (INV-DP-03), and on those the tracker is the one the agent built. The remaining predicate is
 * unchanged, in the same order — exhaustion first, and only then the trivial-activity search, which
 * scans the graph and logs.
 */
public final class BudgetStage implements DecisionStage {

    private final Supplier<ModelAction> trivialActivityAction;

    /**
     * @param trivialActivityAction the agent's trivial-activity navigation search, consulted only
     *        when the budget is exhausted — it walks the graph and logs what it finds, so calling it
     *        on a step that would ignore the answer is not free
     */
    public BudgetStage(Supplier<ModelAction> trivialActivityAction) {
        this.trivialActivityAction = trivialActivityAction;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.BUDGET.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (!ctx.budgetTracker().isBudgetExhausted(ctx.newState().getActivity())) {
            return StageResult.continueChain();
        }
        ModelAction trivial = trivialActivityAction.get();
        if (trivial == null) {
            return StageResult.continueChain();
        }
        Logger.iformat("[APE-RV] Budget exhausted for %s, navigating to trivial activity",
                ctx.newState().getActivity());
        trivial.setDecisionSource(ModelAction.DecisionSource.Budget);
        trivial.setPickChannel(ModelAction.PickChannel.SATA_OTHER);
        return StageResult.select(trivial, ModelAction.DecisionSource.Budget.name());
    }
}
