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

/**
 * The rungs still inline on the agent, as the roster's terminal stage.
 *
 * <p>The extraction walks the ladder from the top, one stage per task with the parity goldens between
 * them, so between tasks the roster is a prefix of the real one. This stage is the rest: whatever the
 * agent has not handed over yet, ending — as the ladder always did — either in a selected action or
 * in {@code BadStateException}. It therefore satisfies INV-DP-06 for every interim roster, which is
 * what lets {@link DecisionPipeline#decide} be the live decision path of a real run from the first
 * extracted stage onwards rather than only after the last one. Each extraction task then reads as one
 * block moving out of this remainder and into a stage in front of it, with the goldens attributing
 * any change to that single move.
 *
 * <p>Task 2.7 lands {@code SataChainStage}, at which point the remainder is exactly the SATA chain
 * and this stage is replaced by it.
 */
final class InlineLadderStage implements DecisionStage {

    private final StageCollaborators collaborators;

    InlineLadderStage(StageCollaborators collaborators) {
        this.collaborators = collaborators;
    }

    @Override
    public String name() {
        return "InlineLadder";
    }

    /**
     * {@inheritDoc}
     *
     * <p>The remainder reads the agent's own fields, as it did when it was a block of
     * {@code selectNewActionNonnull}, so {@code ctx} carries nothing it needs; each extraction moves
     * one of those reads onto the context as the block that makes it becomes a stage.
     */
    @Override
    public StageResult decide(StepContext ctx) {
        return collaborators.decideInlineLadder();
    }
}
