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
 * Fire one manifest receiver, service or provider, at a configured rate (component-triggering,
 * design D-B13).
 *
 * <p>Behaviour can sit behind a component the GUI never opens — a boot receiver, an exported
 * service, a content provider. Clicking cannot reach those, so this rung dispatches one directly,
 * walking the pool round-robin so a pool the run keeps failing on is not retried from its first
 * entry forever. It is a STATIC member: the pool is the static model's manifest components
 * ({@link StepContext#staticModel()}), and no MOP data is read, so the stage fires in the cells
 * without MOP too.
 *
 * <p><b>It is the one rung that acts without deciding.</b> The block it replaces called the trigger
 * and then fell through to the SATA chain with no return; here that is a
 * {@link StageResult#sideEffect}, which the pipeline records and passes over (INV-DP-05). A stage
 * that returned a {@code Select} here would silently cost the step its GUI action.
 *
 * <p><b>The coin has its own stream.</b> {@code u_C} is drawn at every step open from
 * {@code StepRng.componentCoin} ({@link StepRouting#uC()}, INV-DP-13), whether or not the step
 * reaches this stage, so mounting the trigger never shifts SATA's draws. The plan-dependent
 * conjunct, {@code componentPercentage > 0}, is lifted into assembly, where it decides whether this
 * stage exists at all (INV-DP-03). The dispatch records {@code dec.trig} on the agent's side.
 */
public final class ComponentTriggerStage implements DecisionStage {

    private final double rate;
    private final StepRouting routing;
    private final IntSupplier targetCount;
    private final IntConsumer trigger;

    /** Where the next pool walk starts; persisted across firings (INV-DP-07). */
    private int roundRobinIndex;

    /**
     * @param rate {@code ape.componentPercentage}, the probability that a step fires a trigger
     * @param routing the pipeline's per-step routing record, which holds {@code u_C}
     * @param targetCount how many targets the pool yields, asked at each firing
     * @param trigger fires the target at the given index
     */
    public ComponentTriggerStage(double rate, StepRouting routing, IntSupplier targetCount,
            IntConsumer trigger) {
        this.rate = rate;
        this.routing = routing;
        this.targetCount = targetCount;
        this.trigger = trigger;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.COMPONENT_TRIGGER.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (routing.uC() < rate && ctx.staticModel().hasComponents()) {
            int targets = targetCount.getAsInt();
            if (targets == 0) {
                // The manifest declares components but none is triggerable — every candidate was
                // filtered out. The rung still acted (it won its coin and asked),
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
