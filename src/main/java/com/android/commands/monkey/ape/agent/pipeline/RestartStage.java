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

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * The restart guard: the run's one restart decision, taken before any LLM, MOP or SATA stage
 * (decision-pipeline "Restart Guard Stage", design D-B7, INV-DP-14).
 *
 * <p>Mounted iff {@code ape.checkRestart} and {@code APE_FIXES}, second after {@code Budget}. When
 * {@code StageCollaborators.restartDue()} holds — the stability flag of step 2, or the periodic
 * threshold passed with the previous step's selection not disabling it — the stage selects the
 * start action {@code restartAction()} yields, owned by {@code restart} on the {@code restart}
 * channel. With the stage mounted no restart replaces an action after selection, and
 * {@code dec.a} names the start action that ran. Without it the agent restarts after selection,
 * as APE does ({@code ApeAgent.checkRestart}).
 *
 * <p>Everything about the restart itself is the agent's: reading and clearing the
 * {@code disableRestart} latch, the kind ({@code dec.restart}), the threshold redraw and the kind
 * toss on the helper stream, the {@code restarts} counter, and the new episode that clears the
 * buffer without weakening and without {@code dec.buf} (D-B14.13, D-B14.16). The stage reaches it
 * only through those two collaborator methods.
 */
public final class RestartStage implements DecisionStage {

    /** The guard's owner value, {@code dec.src} and {@code dec.ch}. */
    public static final String OWNER = "restart";

    private final BooleanSupplier restartDue;
    private final Supplier<Action> restartAction;

    /**
     * @param restartDue whether a restart is due on this step, {@code StageCollaborators.restartDue}
     * @param restartAction the start action of a due restart,
     *        {@code StageCollaborators.restartAction}
     */
    public RestartStage(BooleanSupplier restartDue, Supplier<Action> restartAction) {
        this.restartDue = restartDue;
        this.restartAction = restartAction;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.RESTART.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (!restartDue.getAsBoolean()) {
            return StageResult.continueChain();
        }
        Action start = restartAction.get();
        if (start instanceof ModelAction) {
            ModelAction model = (ModelAction) start;
            model.setDecisionSource(ModelAction.DecisionSource.RESTART);
            model.setPickChannel(ModelAction.PickChannel.RESTART);
        }
        return StageResult.select(start, OWNER);
    }
}
