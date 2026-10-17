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
package com.android.commands.monkey.ape.telemetry;

import java.util.List;
import java.util.Map;

/**
 * The observation surface of a run: everything the jar records about its own behavior goes through
 * here, and nothing comes back.
 *
 * <p><b>The shape is the neutrality argument.</b> Every method returns {@code void} and takes
 * primitives and strings the caller already holds, so no decision path can read anything the sink
 * computed, and no sink call can advance the seeded RNG or hand back a value that steers a pick.
 * That is INV-SNK-07, and it is what replaced INV-ARCH-01 when telemetry became always-on for
 * every arm: the control arm is no longer protected by being blind, it is protected by the sink
 * being unable to speak. The property is proven rather than asserted — the parity harness runs the
 * same seed with {@link NdjsonSink} and with {@link NoopSink} and the action sequences must match.
 *
 * <p><b>No method throws.</b> A telemetry defect must not alter or kill an experimental run
 * (INV-SNK-12), so implementations swallow their own failures and latch off. Callers never guard a
 * sink call, and a sink call is never the reason a step behaves differently.
 *
 * <p><b>One record per step, closed at N+1.</b> {@link #beginStep} opens the pending record at
 * selection start — early enough that LLM attempts made before the action is known land in it —
 * {@link #decision} fills it when the action is finalized, and {@link #outcome} closes and writes
 * it during the next step's graph update, which is when the outcome of this step first exists. A
 * step whose outcome never resolves closes without an {@code out} member, which is a different and
 * deliberate encoding from the teardown flush (INV-SNK-08).
 */
public interface EventSink {

    /**
     * The value that means "this field has no value for this step", for the tri-state integers
     * whose absence is itself information ({@code dec.patched}, {@code dec.mopd}) and for the MOP
     * exposure pair. Zero cannot serve: {@code patched=0} and a boosted count of {@code 0} are
     * both real observations, and the whole point of the tri-state is that "no resolved target" and
     * "a target that was not patched" are different facts.
     */
    int ABSENT = -1;

    /**
     * {@code dec.mops} for a MOP lookup answered by the node's handler stamp (source
     * {@code STAMP}, design D17, D23). The wire letter is the sink's vocabulary, so it is named
     * here rather than at the call site.
     */
    String MOP_SOURCE_STAMP = "s";

    /** {@code dec.mops} for a MOP lookup answered by the resource-id key (source {@code ID}). */
    String MOP_SOURCE_ID = "i";

    // --- step lifecycle -------------------------------------------------------------------------

    /**
     * Opens the record for a step, closing a still-pending predecessor without an {@code out}
     * member. Re-entering the same step (the {@code BadStateException} selection retry re-runs
     * selection without advancing the agent timestamp) keeps the open record rather than starting a
     * second one — one step is one record, whatever selection did internally (INV-SNK-03).
     *
     * @param step the agent exploration timestamp, unique and increasing within the run
     * @param tRelMs milliseconds since {@code RUN_START}, whose record carries the epoch base
     * @param activity the current activity; interned into an {@code ACT} dictionary id
     * @param activityHasMop the static per-activity MOP fact, recorded once on the {@code ACT}
     *        entry rather than repeated on every step
     * @param stateKey the current abstract state key; interned into a {@code STATE} dictionary id
     */
    void beginStep(int step, long tRelMs, String activity, boolean activityHasMop, String stateKey);

    /**
     * Records the finalized model action and how it was picked.
     *
     * @param action the full action string, escaped by the serializer rather than flattened at the
     *        origin — which is the defect class this whole stage removes
     * @param decisionSource the mechanism credited with the pick, from the fixed enum
     * @param pickChannel the pick channel label, from the fixed enum
     * @param priority the selected action's priority
     * @param mop the MOP boost; like every boost below, a zero is omitted from the record
     *        (INV-SNK-05) and materialized again by the reader
     * @param mopFrontier the MOP-frontier boost
     * @param wtg the WTG-reach boost ({@code WtgPass}, MOP)
     * @param navFrontier {@code dec.navf}: the navigation-frontier boost ({@code NAV_FRONTIER},
     *        STATIC), in its own field since the two producers stopped sharing {@code wtg}
     * @param coverage the coverage boost
     * @param menu the menu boost
     * @param form the form-completion boost
     * @param patched {@code 0}/{@code 1}, or {@link #ABSENT} when the action has no resolved target
     * @param mopDistance {@code dec.mopd} (design D23): under {@code ape.mopScoring=distance}, the
     *        distance {@code d} of the nearest unretired target behind the action's MOP weight, or
     *        {@link #ABSENT} when the action has no such weight (and always under {@code flag}).
     *        Exempt from the defaults-omitted rule: {@code 0} is a value — the handler is itself a
     *        target — and is written
     * @param mopSource {@code dec.mops} (design D23): {@link #MOP_SOURCE_STAMP} or
     *        {@link #MOP_SOURCE_ID} for the source of the lookup behind the action's MOP weight, in
     *        both modes and whether or not the action carries a weight; {@code null} when the
     *        source is {@code NONE} or the action has no resolved target. It travels with the
     *        decision rather than through a call of its own because, unlike {@code dec.ret}, it is
     *        known when the decision is made
     */
    void decision(String action, String decisionSource, String pickChannel, int priority,
            int mop, int mopFrontier, int wtg, int navFrontier, int coverage, int menu, int form,
            int patched, int mopDistance, String mopSource);

    /**
     * Records a finalized non-model action: the event-level actions that carry no priority and no
     * boosts, and produce no transition under their own identity.
     */
    void decisionNonModel(String action, String decisionSource, String pickChannel);

    /**
     * Records how many of the screen's actions were eligible for MOP steering and how many were
     * boosted — the denominator without which "the MOP scorer fired on 0.4 % of decisions" cannot
     * be split into "the mechanism had no opportunity" and "it had one and lost the roulette".
     *
     * <p>It arrives from the scoring pass rather than riding {@link #decision} because that is
     * where the pair is computed, and it cannot ride the {@code STATE} dictionary entry because it
     * is not constant per abstract state — measured across 25 campaign traces, 14 show a state
     * with more than one realised pair.
     */
    void mopExposure(int boosted, int total);

    /**
     * Records the targets the step's action retired, for the step's {@code dec.ret} (design D23,
     * mop-guidance "Target Retirement"): the indices into the artifact's target list whose
     * retirement counter reached {@code ape.mopRetireAfter} on the first interaction of the
     * selected action's widget in its activity, ascending, each once.
     *
     * <p>It is a separate call because of when the indices exist: retirement is counted at
     * selection, in {@code StatefulAgent.moveForward}, after {@link #decision} has already run inside
     * {@code resolveNewAction}. Like {@link #mopExposure} and {@link #componentLaunch}, it adds a
     * member to the open step's {@code dec} after the decision call; the record writes {@code dec}
     * when it closes, so the indices survive a record closed without an {@code out} member. Called
     * only with a non-empty array, and only under {@code ape.mopScoring=distance}.
     *
     * @param indices the retired target indices, ascending, non-empty
     */
    void mopRetired(int[] indices);

    /**
     * Records the platform's answer to a component launch, on component-trigger steps.
     *
     * <p>It is a separate call because of when the answer exists: the retired {@code [APE-STEP]}
     * line was emitted before dispatch and therefore could never carry it, while this record is not
     * written until step N+1, comfortably after. A refused intent and an accepted one are otherwise
     * the same trace evidence, which is what makes the campaign's ~1,075 direct launches against 0
     * in the control unfalsifiable.
     *
     * @param result the platform's {@code START_*} result code
     * @param error the failure enum, or {@code null} when the launch was accepted
     */
    void componentLaunch(int result, String error);

    // --- the routing and selection members of isolate-guidance-groups ---------------------------
    //
    // Each adds one member to the open step's `dec` (design § Telemetry contract, event-sink
    // "StepRecord Schema"). Like mopExposure and mopRetired, they may arrive before or after the
    // decision call, since the record writes `dec` when it closes. Every one is pure (INV-SNK-16):
    // a caller hands over a value it already computed and reads nothing back.

    /**
     * Contributes to {@code dec.own}, the step's routing record, written on every step. The
     * pipeline and the LLM and MOP stages each fill the members they know, so a call merges: a
     * null string or a {@code NaN} draw leaves that member as it was.
     *
     * @param intended {@code i}: the first claimer of the step in pipeline order ({@code budget},
     *        {@code restart}, {@code llm}, {@code launcher}, {@code mop}, {@code sata})
     * @param trigger {@code trg}: {@code new_state}, {@code stagnation} or {@code coin}
     * @param uL the LLM coin's draw, when {@code LLM} is mounted
     * @param uM the MOP coin's draw, when {@code MOP} is mounted
     * @param gate {@code g}: the LLM gate that barred the call, {@code min_actions} or
     *        {@code breaker}
     * @param fallback {@code fb}: why the executed owner differs from {@code i} ({@code gate},
     *        {@code error}, {@code decline}, {@code no_match}, {@code ineligible})
     */
    void owner(String intended, String trigger, double uL, double uM, String gate,
            String fallback);

    /**
     * Adds one key to {@code dec.cfc}: whether the pick, recomputed with {@code scorer}'s field
     * zeroed on every candidate and the same recorded draw, selects another action.
     *
     * @param scorer {@code mop}, {@code mopf}, {@code wtg}, {@code navf}, {@code cov},
     *        {@code menu} or {@code form}
     */
    void cfc(String scorer, boolean changed);

    /** {@code dec.draw}: the integer a roulette drew and the bound it was drawn below. */
    void draw(int index, int total);

    /**
     * {@code dec.nav}: the SATA extension point that decided a tie among two or more candidates.
     *
     * @param point {@code X1}, {@code X3} or {@code X4}
     * @param variant {@code ape} or {@code mop}, the mounted variant
     * @param alt whether the MOP variant decided by its own rule (design D-B14.8)
     * @param drew whether the decision consumed a draw
     */
    void nav(String point, String variant, boolean alt, boolean drew);

    /**
     * {@code dec.inp}: the origin of the text the step's input refiner set — {@code typed},
     * {@code heuristic}, {@code random}, {@code form} or {@code llm}.
     */
    void input(String origin);

    /**
     * {@code dec.restart}: the kind of restart a RESTART step dispatched — {@code graph},
     * {@code state}, {@code activity} or {@code periodic}.
     */
    void restart(String kind);

    /** {@code dec.bmc}: candidates the BACK/MENU cap removed from the pick; 0 is omitted. */
    void backMenuCapped(int removed);

    /** {@code dec.strat}: the path inside SATA that selected the action. */
    void strategy(String path);

    /**
     * {@code dec.rot}: the display rotation at step open, {@code 0..3}, or {@code -1} when it could
     * not be read. Written on every step record, {@code 0} included.
     */
    void rotation(int rotation);

    /**
     * {@code dec.buf}: the owner that took the step while the SATA buffer was non-empty and
     * abandoned it without weakening the planned edge.
     */
    void bufferAbandoned(String owner);

    /**
     * {@code dec.trig}: the component the trigger fired on this step, a side effect that names no
     * owner.
     *
     * @param kind {@code broadcast}, {@code service} or {@code provider}
     * @param className the component's class name
     */
    void componentTrigger(String kind, String className);

    /** Adds one to a {@code RUN_END.counters} member of isolate-guidance-groups. */
    void count(RunCounter counter);

    /**
     * Stages the prompt and response dumps for the next LLM sub-event of this step.
     *
     * <p>They are staged rather than passed to {@link #llmCall} because of when they exist: the
     * prompt is written before the response is parsed and the mapping decided, exactly as the
     * retired {@code [APE-LLM-PROMPT]}/{@code [APE-LLM-RESPONSE]} lines preceded their
     * {@code [APE-LLM-TEL]} line. An attempt abandoned before it maps therefore keeps its prompt,
     * which the retired rendering also managed and no field ordering here should lose. Anything
     * still staged when the step closes is discarded.
     */
    void llmDump(String system, String user, String response, String toolCalls);

    /**
     * Appends one completed LLM routing attempt to the step's {@code llm} array, in occurrence
     * order. Attribution to the step is by construction: the entry is a member of the step's own
     * record, so no {@code step=} key exists or is needed.
     *
     * @param result {@code matched}, {@code llm_tap} or {@code no_match}
     * @param reason the {@code no_match} discriminator ({@code dead_pair}, {@code degenerate},
     *        {@code boundary}), or {@code null}
     * @param repair the repair-form label when the parse needed one, or {@code null} on a clean
     *        parse — orthogonal to {@code result}, as it has always been
     */
    void llmCall(int call, String mode, String tool, int qwenX, int qwenY, int pixelX, int pixelY,
            String result, String reason, String repair, String matchedClass, String nearestClass,
            double nearestDistance, int widgets, int tokensIn, int tokensOut, long ms, String text);

    /**
     * Appends an attempt abandoned before it could map, with exactly one named cause. Replaces the
     * {@code [APE-LLM-ERROR]} line, whose {@code step=} key becomes unnecessary.
     */
    void llmError(String cause, String detail);

    /**
     * Appends the once-per-episode breaker decline. Emitted at the first routing-predicate decline
     * of each open window, never once per declined call.
     */
    void llmBreakerOpen(int trips);

    /**
     * Closes the pending record with its outcome and writes it. Called during step N+1's graph
     * update, under the caller's single-shot, reference-equality buffered-decision guard — the
     * same discipline that governed the retired {@code [APE-OUTCOME]} line.
     *
     * <p>The target's activity travels with the state key because the target state may be seen
     * here for the first time, and its {@code STATE} dictionary entry has to name the activity it
     * belongs to: the outcome-side MOP flag is derived by the reader as
     * {@code out.target → STATE.act → ACT.mop}, and a dictionary entry with no activity would
     * break that derivation for exactly the new states the run exists to find.
     */
    void outcome(boolean newState, String targetStateKey, String targetActivity,
            boolean targetActivityHasMop, boolean activityChanged);

    /**
     * Writes a still-pending record with {@code out:{"resolved":false}} — the teardown step that
     * bounds loss on a cut run at one record, and the encoding that distinguishes "the run stopped
     * mid-step" from the legitimate absence of an outcome.
     */
    void flushPendingStep();

    // --- run-level records ----------------------------------------------------------------------

    /**
     * Records the static-analysis load census.
     *
     * <p>{@code wtgEdges} is the summed size of the click-only {@code wtgTransitions} view — the
     * number the three frontier passes actually gate on — and deliberately not the flat
     * {@code transitions} list the retired line reported: 14 of the decisive campaign's 40
     * applications report 9–29 transitions with the whole frontier family disabled, so restoring
     * the old field would restore the misreading. No {@code has_wtg_data} boolean is emitted; it is
     * {@code wtgEdges > 0} by construction.
     *
     * <p>{@code formatVersion} and {@code sourceDigest} are the provenance pair: they name which
     * wire contract was read and which static-analysis document it was derived from, so a trace
     * identifies its own input instead of leaving the reader to infer it from the run's date. Only
     * a load that reached an artifact has them, so {@code MopData.reject} passes {@code 0} and
     * {@code null} — a neutral absence, not a claim. Every counter below it is neutral on that path
     * for the same reason: a rejected load counted nothing, so a zero states that rather than
     * asserting an empty app.
     *
     * <p>{@code targets}, {@code handlers} and {@code droppedPairs} come with wire format 2
     * (design D15, D23): the length of the target list every distance pair indexes (the bound of
     * every index in {@code dec.ret}), the number of classes the artifact's {@code handlers} table
     * lists, and the number of malformed distance pairs the loader dropped. A conforming
     * generator emits no malformed pair, so a non-zero {@code droppedPairs} is a generator↔jar
     * skew signal.
     */
    void mopData(String status, String reason, int formatVersion, String sourceDigest, String pkg,
            int windows, int widgets, int flagged, int droppedNoId, int wtgEdges,
            int handlersUnmatched, int syntheticLambda, int recovered, int mopActivities,
            int mopActsAugmented, int components, int targets, int handlers, int droppedPairs);

    /**
     * Records pipeline assembly provenance: the stages, the passes that were constructed, and every
     * candidate pass with whether it was.
     *
     * <p>The candidate census is what makes the pass list readable as a data-dependent outcome
     * instead of a configuration echo — the WTG/frontier family is never constructed in 25 of the
     * campaign's 40 applications, and nothing in the trace said so except three names missing from
     * a list nobody was reading as an outcome. No {@code reason} field: the gate conjuncts are all
     * recoverable from {@code MOP_DATA.status}, {@code MOP_DATA.wtgEdges} and
     * {@code RUN_START.params}, and the passes do not evaluate them in a uniform order, so a
     * "first failing conjunct" would encode source order rather than cause.
     *
     * @param stages the static list of candidate stage names — never a constructed disabled-stage
     *        object, which the decision-pipeline capability forbids (INV-DP-03)
     * @param candidates every candidate pass in declaration order, mapped to whether it was
     *        constructed
     */
    void pipeline(List<String> stages, List<String> passes, Map<String, Boolean> candidates);

    /** Records the model the server actually served, at most once per run. */
    void llmAck(String serverModel);

    /**
     * Writes {@code RUN_END}, the last sink record of a normal termination (INV-SNK-09).
     *
     * <p>Called as the last teardown step, after every other one has had its chance to run. The
     * record carries how the run ended, how many step records it wrote, the {@code t} of its first
     * and last step, and the counters. It is <b>write-only</b>: nothing on the Python side reads or
     * validates it, no task status depends on it, and its absence means only that the process was
     * killed before teardown (owner decision D5).
     *
     * <p>The counters the sink itself owns — the step-record count and the two dictionary sizes —
     * are not parameters: the sink is the only thing that knows them, and asking teardown to fetch
     * them so it could hand them back would be indirection with one subscriber.
     *
     * @param reason how the run ended: {@code timeout} (the time budget or a
     *        {@code StopTestingException}), {@code crash} (a {@code Throwable} escaped the
     *        exploration loop), or {@code unknown} when nothing said
     * @param detail the crash's exception class name, or {@code null} — the sketch in the design's
     *        API section carried no such parameter, and it is here because the same section
     *        specifies the class name as part of what {@code crash} means, which a bare reason
     *        enum cannot say
     * @param stampNodes {@code counters.stampNodes} (design D23): the nodes, over every GUI tree the
     *        run captured, that carried at least one handler stamp, each counted once per capture it
     *        appears in. A parameter because only the agent sees the trees; written on every run,
     *        zero included, because a zero is the measurement that the APK was not stamped
     * @param retired {@code counters.retired} (design D23): the targets retired by the end of the
     *        run, from the run's retirement counters rather than from the {@code dec.ret} the sink
     *        saw, so a retirement whose step record was lost is still counted; 0 under
     *        {@code flag} and without MOP data. {@code counters.stampHits}, the third MOP counter,
     *        is not a parameter: it counts the written records whose {@code dec.mops} is
     *        {@link #MOP_SOURCE_STAMP}, which the sink alone knows, and is at most one per step by
     *        construction
     * <p>The routing counters of {@link RunCounter} are written in full on every run, zero
     * included, beside the three MOP counters: the clean-control gate reads their zeros.
     *
     * @param counters the LLM totals, or {@code null} on a plan with no LLM — in which case the
     *        record carries no LLM counter block at all, since a zeroed one would read as an LLM
     *        that was asked nothing rather than an arm that has none
     */
    void runEnd(String reason, String detail, int stampNodes, int retired, RunCounters counters);
}
