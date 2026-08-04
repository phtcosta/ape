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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.agent.SataAgent.SataEventType;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * The SATA strategy chain: seven ways of choosing an action, tried in a fixed order until one
 * answers.
 *
 * <p>This is the policy every plan carries and the reason no plan can decide nothing. It is the
 * terminal stage (INV-DP-06): it never passes, because the last rung either returns an action or
 * throws — the state offered nothing executable, which is a broken state rather than a decision.
 *
 * <p><b>The order is the research semantics.</b> Replay a buffered sequence before starting a new
 * one; navigate back to an activity that still owes work before exploring where you are; prefer the
 * unvisited before the merely under-visited; and only then take the epsilon-greedy gamble. Each rung
 * is bound at assembly to the agent method that implements it, so what lives here is the order and
 * nothing else — the strategies themselves are untouched by this pipeline.
 *
 * <p><b>Why a table and not seven copies of the same four lines.</b> The block this replaces spelled
 * {@code resolved = …; if (resolved != null) { log(…); return …; }} out seven times, which made the
 * order hard to read and easy to disturb: an edit to one copy that missed its siblings changed the
 * policy silently. As a table the order is a single list, and the walk that consumes it is written
 * once (design D12). The de-duplication is exactly that — no rung's predicate, log line, RNG draw or
 * provenance stamp differs from what it did inline.
 *
 * <p><b>The stamp is read after it is written.</b> {@code logActionSelected} attributes an action to
 * {@code SATA} unless the rung is one of the two that consume priority and stamp their own source at
 * the pick site, so the label this stage mirrors into its {@link StageResult} must be read after that
 * call, never before (INV-DP-04's equality clause).
 */
public final class SataChainStage implements DecisionStage {

    /** One rung: the strategy to try, and the label its log line and counters carry. */
    private static final class Rung {

        private final Supplier<ModelAction> strategy;
        private final SataEventType event;

        Rung(Supplier<ModelAction> strategy, SataEventType event) {
            this.strategy = strategy;
            this.event = event;
        }
    }

    private final List<Rung> rungs;
    private final BiConsumer<Action, SataEventType> logActionSelected;

    /**
     * Binds the seven rungs in their fixed order. The parameters are named for the strategies rather
     * than passed as a list so that assembly reads as a wiring and the order stays here, where the
     * stage's own test can assert the one that ships.
     *
     * @param logActionSelected the agent's per-rung log and attribution write
     * @param fromBuffer replay of an already-planned sequence
     * @param backToActivity navigation back to an activity with unfinished work
     * @param earlyStageForward the unvisited-action roulette
     * @param forTrivialActivity navigation towards an activity that still owes work
     * @param earlyStageBackward the backward walk over the graph
     * @param epsilonGreedy the epsilon-greedy gamble
     * @param nullHandler the last resort, which selects or declares the state broken
     */
    public SataChainStage(BiConsumer<Action, SataEventType> logActionSelected,
            Supplier<ModelAction> fromBuffer,
            Supplier<ModelAction> backToActivity,
            Supplier<ModelAction> earlyStageForward,
            Supplier<ModelAction> forTrivialActivity,
            Supplier<ModelAction> earlyStageBackward,
            Supplier<ModelAction> epsilonGreedy,
            Supplier<ModelAction> nullHandler) {
        this.logActionSelected = logActionSelected;
        this.rungs = Collections.unmodifiableList(Arrays.asList(
                new Rung(fromBuffer, SataEventType.USE_BUFFER),
                new Rung(backToActivity, SataEventType.TRIVIAL_ACTIVITY),
                new Rung(earlyStageForward, SataEventType.EARLY_STAGE),
                new Rung(forTrivialActivity, SataEventType.TRIVIAL_ACTIVITY),
                new Rung(earlyStageBackward, SataEventType.EARLY_STAGE),
                new Rung(epsilonGreedy, SataEventType.EPSILON_GREEDY),
                new Rung(nullHandler, SataEventType.NULL)));
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.SATA_CHAIN.stageName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ctx} is unread: each rung is bound to an agent method that reads the agent's own
     * fields, exactly as it did when this was a block of {@code selectNewActionNonnull} — which is the
     * parity claim this stage rests on, not an account of how the file got here. Those fields are the
     * agent's injected exploration parameters, so no rung reaches a static {@code Config}
     * (INV-DP-12).
     *
     * @throws BadStateException when every rung declines — the terminal stage's one way out other
     *         than a decision (INV-DP-06)
     */
    @Override
    public StageResult decide(StepContext ctx) {
        for (Rung rung : rungs) {
            ModelAction resolved = rung.strategy.get();
            if (resolved != null) {
                logActionSelected.accept(resolved, rung.event);
                return StageResult.select(resolved, resolved.getDecisionSource().name());
            }
        }
        throw new BadStateException("No available action on the current state");
    }
}
