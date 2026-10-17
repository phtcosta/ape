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

import java.util.EnumSet;
import java.util.Set;

import com.android.commands.monkey.ape.agent.StepRng;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.RunCounter;

/**
 * The step's routing record: the coins drawn at step open, the first claimer, and the LLM's one
 * call (decision-pipeline, design D-B7–D-B9, D-B14.6).
 *
 * <p>One instance per pipeline, shared by the stages that read a coin or claim a step, and reset by
 * {@link #open} at every step open. The pipeline owns it because two of its facts belong to no
 * single stage: the claimer is the first stage in pipeline order that intended to take the step,
 * whichever stage then took it, and the LLM call budget is spent by whichever LLM stage called
 * first (INV-RTR-26).
 *
 * <p><b>The coins are drawn here, every step, before any stage runs</b> (INV-DP-13): {@code u_L}
 * when {@code LLM} is mounted, {@code u_M} when {@code MOP} is, {@code u_C} when
 * {@code COMPONENT_TRIGGER} is. Each comes from its own {@link StepRng} stream, so whether a stage
 * is reached, a gate bars or a coin is read never moves another draw. A coin of an unmounted group
 * reads {@code NaN} and is never compared.
 */
public final class StepRouting {

    /** {@code dec.own.trg} of the new-state stage. */
    public static final String TRIGGER_NEW_STATE = "new_state";
    /** {@code dec.own.trg} of the stagnation stage. */
    public static final String TRIGGER_STAGNATION = "stagnation";
    /** {@code dec.own.trg} of the coin stage. */
    public static final String TRIGGER_COIN = "coin";

    /** {@code dec.own.fb} when a gate barred the LLM call. */
    public static final String FALLBACK_GATE = "gate";
    /** {@code dec.own.fb} when a due launcher found no candidate. */
    public static final String FALLBACK_INELIGIBLE = "ineligible";

    private final boolean drawsLlm;
    private final boolean drawsMop;
    private final boolean drawsComponent;

    private double uL = Double.NaN;
    private double uM = Double.NaN;
    private double uC = Double.NaN;

    private String claimer;
    private String fallback;
    private String trigger;
    private String gate;
    private boolean llmCalled;
    private final Set<RunCounter> gatesCounted = EnumSet.noneOf(RunCounter.class);

    /**
     * @param drawsLlm whether {@code LLM} is mounted, so {@code u_L} is drawn every step
     * @param drawsMop whether {@code MOP} is mounted, so {@code u_M} is drawn every step
     * @param drawsComponent whether {@code COMPONENT_TRIGGER} is mounted, so {@code u_C} is drawn
     *        every step
     */
    public StepRouting(boolean drawsLlm, boolean drawsMop, boolean drawsComponent) {
        this.drawsLlm = drawsLlm;
        this.drawsMop = drawsMop;
        this.drawsComponent = drawsComponent;
    }

    /**
     * Opens a step: forgets the previous step's routing and draws this step's coins from their own
     * streams, in a fixed order, whatever the step then does.
     *
     * @param rng the run's per-step streams
     */
    void open(StepRng rng) {
        double llm = drawsLlm ? rng.llmCoin() : Double.NaN;
        double mop = drawsMop ? rng.mopCoin() : Double.NaN;
        double component = drawsComponent ? rng.componentCoin() : Double.NaN;
        open(llm, mop, component);
    }

    /**
     * Opens a step on coins already drawn: the step's routing is forgotten and the three values
     * become this step's. {@link #open(StepRng)} is the production path; a stage test states the
     * coins it needs here.
     *
     * @param llm {@code u_L}, or {@code NaN}
     * @param mop {@code u_M}, or {@code NaN}
     * @param component {@code u_C}, or {@code NaN}
     */
    void open(double llm, double mop, double component) {
        claimer = null;
        fallback = null;
        trigger = null;
        gate = null;
        llmCalled = false;
        gatesCounted.clear();
        uL = llm;
        uM = mop;
        uC = component;
    }

    /** This step's {@code u_L}, or {@code NaN} when {@code LLM} is not mounted. */
    public double uL() {
        return uL;
    }

    /** This step's {@code u_M}, or {@code NaN} when {@code MOP} is not mounted. */
    public double uM() {
        return uM;
    }

    /** This step's component coin, or {@code NaN} when {@code COMPONENT_TRIGGER} is not mounted. */
    public double uC() {
        return uC;
    }

    /**
     * Records that {@code owner} intends to take the step. Only the first claim of a step counts:
     * it is {@code dec.own.i}, whichever stage then takes the step.
     *
     * @param owner the claiming stage's owner
     * @return whether this was the step's first claim
     */
    public boolean claim(String owner) {
        if (claimer != null) {
            return false;
        }
        claimer = owner;
        return true;
    }

    /**
     * Records why the first claimer did not take the step ({@code dec.own.fb}). The claimer's own
     * reason only: a stage that is not the first claimer has no say in it.
     *
     * @param owner the stage's owner
     * @param reason the fallback reason
     */
    public void fallback(String owner, String reason) {
        if (owner.equals(claimer)) {
            fallback = reason;
        }
    }

    /** Whether an LLM stage has called the engine on this step (INV-RTR-26). */
    public boolean llmCalled() {
        return llmCalled;
    }

    /**
     * An LLM trigger held: the LLM claims the step, and the trigger is the step's {@code trg}
     * until a stage actually calls.
     *
     * @param llmTrigger {@link #TRIGGER_NEW_STATE}, {@link #TRIGGER_STAGNATION} or
     *        {@link #TRIGGER_COIN}
     */
    void llmTriggered(String llmTrigger) {
        claim(LlmCall.OWNER);
        if (trigger == null) {
            trigger = llmTrigger;
        }
    }

    /**
     * A gate barred the LLM call. The first barring gate is {@code dec.own.g}; each gate counts at
     * most once per step, however many LLM stages it barred.
     *
     * @param gateName the gate's wire name
     * @param counter the gate's {@code RUN_END} counter
     * @param sink where the counter is added
     */
    void llmBarred(String gateName, RunCounter counter, EventSink sink) {
        if (gate == null) {
            gate = gateName;
        }
        fallback(LlmCall.OWNER, FALLBACK_GATE);
        if (gatesCounted.add(counter)) {
            sink.count(counter);
        }
    }

    /**
     * The step's one LLM call is being made: no later LLM stage calls (INV-RTR-26), {@code trg} is
     * the calling stage's, and no gate barred it.
     *
     * @param llmTrigger the calling stage's trigger
     */
    void llmCalling(String llmTrigger) {
        llmCalled = true;
        trigger = llmTrigger;
        gate = null;
        fallback = null;
    }

    /**
     * Writes {@code dec.own} for the step the pipeline just decided (design D-B14.6): {@code i} is
     * the first claimer, or the executed owner when nothing claimed the step; {@code trg} and
     * {@code g} are the LLM's; {@code fb} is written only when the executed owner is not {@code i}.
     *
     * @param executed the owner of the stage that selected the step's action
     * @param sink the run's sink
     */
    void record(String executed, EventSink sink) {
        String intended = claimer != null ? claimer : executed;
        String reason = intended.equals(executed) ? null : fallback;
        sink.owner(intended, trigger, uL, uM, gate, reason);
    }
}
