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

import java.util.Objects;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * What one stage of the decision pipeline answered: it decided the step, it passed, or it performed
 * an effect and passed.
 *
 * <p><b>Why this type exists.</b> The ladder it replaces answered with {@code null}, and {@code null}
 * meant three unrelated things at three different rungs — "no trivial activity exists, try the next
 * block", "the LLM declined this step", and "this rung found nothing". A reader could not tell which
 * without following the call. Three variants with names is the whole point: a stage that passes says
 * {@link #continueChain()}, a stage that decided says {@link #select}, and the component trigger —
 * the one rung that acts without deciding — says {@link #sideEffect}, which the pipeline records and
 * walks past (INV-DP-05).
 *
 * <p><b>Why a tagged final class and not a hierarchy.</b> Java 11 has no sealed interfaces, and the
 * variant set here is closed research semantics: hard precedence with exactly these three outcomes
 * (owner decision Q1). An open {@code abstract} class would invite a fourth variant from anywhere; a
 * visitor would buy exhaustiveness with an interface, a type parameter and double dispatch for the
 * single consumer that exists, the {@link DecisionPipeline} loop. A final class with a private
 * constructor closes the set at compile time and costs one {@code switch} on {@link Kind} in one
 * place (design D1).
 *
 * <p><b>Why the accessors throw.</b> Reading {@link #action()} off a {@code CONTINUE} is a consumer
 * bug, not a state worth encoding. Returning {@code null} there would push the mistake downstream to
 * whatever dereferences it; throwing puts the failure at the misuse, where a unit test sees it.
 *
 * <p>The {@code decisionSource} label on a {@code SELECT} is the existing
 * {@link ModelAction.DecisionSource} name (or the derived non-model source) — no second vocabulary.
 * INV-DP-04 requires that, for a {@link ModelAction}, the label equal the provenance the stage
 * stamped on the action itself, which is what keeps {@code [APE-STEP]} and the stage-4 step record
 * from disagreeing about who decided. That equality is asserted per stage rather than enforced here
 * on purpose: this factory runs inside the decision path of a live run, and a mislabelled stage
 * should fail a test, not abort an experiment.
 */
public final class StageResult {

    /** Which of the three answers this is. */
    public enum Kind {
        /** The step is decided; the pipeline returns this action and evaluates no later stage. */
        SELECT,
        /** This stage has nothing to say; the pipeline advances to the next one. */
        CONTINUE,
        /** An effect was performed; the pipeline records it and still advances (INV-DP-05). */
        SIDE_EFFECT
    }

    /**
     * The one {@code CONTINUE} value. It carries no data, so every "I pass" answer in a run is this
     * object — a stage returning {@code CONTINUE} allocates nothing on the hot path.
     */
    private static final StageResult CONTINUE_INSTANCE =
            new StageResult(Kind.CONTINUE, null, null, null);

    private final Kind kind;
    private final Action action;
    private final String decisionSource;
    private final String description;

    private StageResult(Kind kind, Action action, String decisionSource, String description) {
        this.kind = kind;
        this.action = action;
        this.decisionSource = decisionSource;
        this.description = description;
    }

    /**
     * The step is decided by this stage.
     *
     * @param action the action the step will execute
     * @param decisionSource the decision-source label, equal to the provenance stamped on
     *        {@code action} when it is a {@link ModelAction} (INV-DP-04)
     * @return a {@code SELECT} result; the pipeline returns its action and evaluates no later stage
     * @throws NullPointerException if either argument is null — a {@code SELECT} without an action or
     *         without an attribution is not a decision anything downstream can record
     */
    public static StageResult select(Action action, String decisionSource) {
        return new StageResult(Kind.SELECT,
                Objects.requireNonNull(action, "a SELECT carries the action it selected"),
                Objects.requireNonNull(decisionSource, "a SELECT carries its decision source"),
                null);
    }

    /**
     * This stage passes; the next one decides.
     *
     * @return the shared {@code CONTINUE} value
     */
    public static StageResult continueChain() {
        return CONTINUE_INSTANCE;
    }

    /**
     * An effect was performed and the step is <em>not</em> decided.
     *
     * @param description what was performed, for the step's record
     * @return a {@code SIDE_EFFECT} result; the pipeline records it and advances (INV-DP-05)
     * @throws NullPointerException if {@code description} is null — an unrecorded side effect is
     *         indistinguishable from no side effect
     */
    public static StageResult sideEffect(String description) {
        return new StageResult(Kind.SIDE_EFFECT, null, null,
                Objects.requireNonNull(description, "a SIDE_EFFECT carries what it did"));
    }

    /** Which variant this is — the tag the pipeline loop switches on. */
    public Kind kind() {
        return kind;
    }

    /**
     * The selected action.
     *
     * @throws IllegalStateException unless this is a {@code SELECT}
     */
    public Action action() {
        require(Kind.SELECT, "action");
        return action;
    }

    /**
     * The selecting stage's decision-source label.
     *
     * @throws IllegalStateException unless this is a {@code SELECT}
     */
    public String decisionSource() {
        require(Kind.SELECT, "decisionSource");
        return decisionSource;
    }

    /**
     * What the side effect did.
     *
     * @throws IllegalStateException unless this is a {@code SIDE_EFFECT}
     */
    public String description() {
        require(Kind.SIDE_EFFECT, "description");
        return description;
    }

    private void require(Kind expected, String member) {
        if (kind != expected) {
            throw new IllegalStateException(
                    member + " is only carried by a " + expected + " result, and this is a " + kind);
        }
    }

    @Override
    public String toString() {
        switch (kind) {
            case SELECT:
                return "Select(" + decisionSource + ", " + action + ")";
            case SIDE_EFFECT:
                return "SideEffect(" + description + ")";
            default:
                return "Continue";
        }
    }
}
