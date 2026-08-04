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

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Fire one MOP-reachable receiver, service or provider, at a configured rate.
 *
 * <p>A monitored operation can sit behind a component the GUI never opens — a boot receiver, an
 * exported service, a content provider. Clicking cannot reach those, so this rung dispatches one
 * directly, walking the census round-robin so a pool the run keeps failing on is not retried from
 * its first entry forever.
 *
 * <p><b>It is the one rung that acts without deciding.</b> The block it replaces called the trigger
 * and then fell through to the SATA chain with no return; here that is a
 * {@link StageResult#sideEffect}, which the pipeline records and passes over (INV-DP-05). A stage
 * that returned a {@code Select} here would silently cost the step its GUI action.
 *
 * <p><b>The coin is drawn after the data guard, and that ordering is the parity crux.</b> The
 * original conjunction short-circuited on an empty census before reaching
 * {@code getRandom().nextDouble()}, so a run with no components consumed no draw. The seeded stream
 * is shared with every later decision, so drawing one coin earlier would move every subsequent
 * choice in the run (INV-DP-10) — which is why the conjunction below is the original one, with only
 * the two plan-dependent conjuncts ({@code componentPercentage > 0}, MOP data present) lifted into
 * assembly, where they decide whether this stage exists at all (INV-DP-03).
 */
public final class ComponentTriggerStage implements DecisionStage {

    private final double rate;
    private final IntSupplier targetCount;
    private final IntConsumer trigger;

    /** Where the next census walk starts; persisted across firings (INV-DP-07). */
    private int roundRobinIndex;

    /**
     * @param rate the probability that a selection pass fires a trigger
     * @param targetCount how many targets the arm's census yields, asked at each firing
     * @param trigger fires the target at the given index
     */
    public ComponentTriggerStage(double rate, IntSupplier targetCount, IntConsumer trigger) {
        this.rate = rate;
        this.targetCount = targetCount;
        this.trigger = trigger;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.COMPONENT_TRIGGER.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (ctx.mopData().hasComponents() && ctx.random().nextDouble() < rate) {
            int targets = targetCount.getAsInt();
            if (targets == 0) {
                // The census declares components but none is triggerable — every candidate was
                // filtered out as unreachable. The rung still acted (it spent its coin and asked),
                // so the step records what it found; what does not move is the cursor, which
                // indexes targets and had none to index.
                return StageResult.sideEffect("no triggerable component target");
            }
            int target = roundRobinIndex % targets;
            roundRobinIndex++;
            trigger.accept(target);
            return StageResult.sideEffect(
                    "triggered component target " + target + " of " + targets);
        }
        return StageResult.continueChain();
    }
}
