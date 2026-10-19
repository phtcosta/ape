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
package com.android.commands.monkey.ape.agent;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.AndroidDevice.Activity;
import com.android.commands.monkey.ape.AndroidDevice.Stack;
import com.android.commands.monkey.ape.AndroidDevice.Task;
import com.android.commands.monkey.ape.BaseActionFilter;
import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.SubsequenceFilter;
import com.android.commands.monkey.ape.agent.pipeline.BudgetStage;
import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.StageCollaborators;
import com.android.commands.monkey.ape.agent.sata.AbaTargetTie;
import com.android.commands.monkey.ape.agent.sata.ApeAbaTargetTie;
import com.android.commands.monkey.ape.agent.sata.ApeTrivialPathPick;
import com.android.commands.monkey.ape.agent.sata.MopAbaTargetTie;
import com.android.commands.monkey.ape.agent.sata.MopTrivialPathPick;
import com.android.commands.monkey.ape.agent.sata.NavVariant;
import com.android.commands.monkey.ape.agent.sata.ScorerSumLeastVisitedTie;
import com.android.commands.monkey.ape.agent.sata.TrivialPathPick;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.ScorerCounterfactual;
import com.android.commands.monkey.ape.model.ScorerCounterfactual.Scorer;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateActionDiffer;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import android.content.ComponentName;

public class SataAgent extends StatefulAgent implements StageCollaborators {


    SubsequenceFilter unsaturatedActionsFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            State last = path.getLastState();
            return last.firstAction(ActionFilter.ENABLED_VALID_UNSATURATED) != null;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong();
        }
    };

    SubsequenceFilter backtrackSubsequenceFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            State last = path.getLastState();
            if (isGreedyState(last)) {
                return true;
            }
            if (isEntryState(last)) {
                return true;
            }
            return false;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (!edge.action.isBack()) { // only back action
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong();
        }
    };

    SubsequenceFilter greedySubsequenceFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            State last = path.getLastState();
            State prev;
            if (path.size() > 1) {
                prev = path.getLastLastState();
            } else {
                prev = path.getStartState();
            }
            if (isGreedyState(prev, last)) {
                return true;
            }
            return false;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (edge.action.isBack()) {
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong();
        }
    };

    SubsequenceFilter weakActionSubsequenceFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            StateTransition last = path.getLastStateTransition();
            if (last.getStrength() == 0 && last.getVisitedCount() < 3) {
                return true;
            }
            return false;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong() || edge.getStrength() == 0;
        }
    };

    public static enum SataEventType {
        TRIVIAL_ACTIVITY,
        SATURATED_STATE, USE_BUFFER, EARLY_STAGE,
        EPSILON_GREEDY, RANDOM, NULL, BUFFER_LOSS, FILL_BUFFER, BAD_STATE;
    }

    /**
     * Control the exploration and exploitation. The idea is borrowed from reinforcement learning.
     */
    private double epsilon;

    private StateActionDiffer actionDiffer = new StateActionDiffer();

    private int[] actionCounters;
    private ActivityNode backToActivity;

    /**
     * The run's decision policy, assembled once from the plan (INV-DP-01).
     *
     * <p>The oracle harness allocates its agent without running a constructor, so this field is one
     * of the ones it injects; {@code OracleScaffold.newAgent} assembles it from the same
     * {@code fromSpec} against the preset's plan.
     */
    private final DecisionPipeline decisionPipeline;

    /**
     * X1, the least-visited tie (action-selection, design D-B12). It has one strategy in every
     * cell, rule R2, and holds no state, so it is not mounted per plan.
     */
    static final ScorerSumLeastVisitedTie LEAST_VISITED_TIE = new ScorerSumLeastVisitedTie();

    /*
     * X3 and X4 and the form-submit exclusion, mounted once from the plan by
     * mountExtensionPoints: the MOP variants iff MOP is in the plan, the exclusion iff
     * FORM_COMPLETION is (INV-SEL-12, form-completion INV-FORM-06/08). No use site branches on the
     * plan or on MOP availability. Not final, and reached through their accessors, because the
     * test harnesses allocate this class without running a constructor; an accessor finding them
     * unset mounts them from the same plan the constructor reads.
     */
    private AbaTargetTie abaTargetTie;
    private TrivialPathPick trivialPathPick;
    private boolean submitExclusion;

    /** The telemetry of the SATA pick under way; see {@link SataTrace}. */
    private SataTrace trace;

    public SataAgent(MonkeySourceApe ape, Graph graph) {
        this(ape, graph, RunContext.current().spec().exploration().defaultEpsilon());
    }

    public SataAgent(MonkeySourceApe ape, Graph graph, double epsilon) {
        super(ape, graph);
        this.epsilon = epsilon;

        RunSpec spec = RunContext.current().spec();
        mountExtensionPoints(spec);

        this.actionCounters = new int[SataEventType.values().length];
        // Assembly is here rather than in StatefulAgent because the stages bind this class's action
        // producers, and it is safe at this point for the same reason the scoring pipeline's is: the
        // bindings are method references, so nothing is invoked before the constructor finishes.
        //
        // It is also why the pipeline is the agent's and not the run context's (design D15): binding
        // those producers needs an agent, and the context is established before one exists — which is
        // what makes the spec read above possible at all. The context owns the LLM units instead,
        // which have no agent in them.
        this.decisionPipeline = DecisionPipeline.fromSpec(spec, this);
    }

    @Override
    public void startNewEpisode() {
        super.startNewEpisode();
    }

    protected boolean isEntryState(State state) {
        return this.getGraph().isEntryState(state);
    }

    /**
     * The composition root of X3, X4 and the form-submit exclusion (action-selection INV-SEL-12,
     * design D-B12): the MOP variants iff {@code MOP} is in the plan, the APE defaults otherwise;
     * the submit exclusion iff {@code FORM_COMPLETION} is. There is no key. The MOP variants read
     * {@link MopScorer#stateMopDensity} through this agent's context and clock, at the step their
     * tie happens in.
     */
    private void mountExtensionPoints(RunSpec spec) {
        if (spec.has(Feature.MOP)) {
            ToIntFunction<State> density =
                    state -> MopScorer.stateMopDensity(state, getMopContext(), getTimestamp());
            abaTargetTie = new MopAbaTargetTie(density);
            trivialPathPick = new MopTrivialPathPick(density);
        } else {
            abaTargetTie = new ApeAbaTargetTie();
            trivialPathPick = new ApeTrivialPathPick();
        }
        submitExclusion = spec.has(Feature.FORM_COMPLETION);
    }

    /** X3 as mounted; mounts the extension points first on an agent built without a constructor. */
    AbaTargetTie abaTargetTie() {
        if (abaTargetTie == null) {
            mountExtensionPoints(RunContext.current().spec());
        }
        return abaTargetTie;
    }

    /** X4 as mounted; mounts the extension points first on an agent built without a constructor. */
    TrivialPathPick trivialPathPick() {
        if (trivialPathPick == null) {
            mountExtensionPoints(RunContext.current().spec());
        }
        return trivialPathPick;
    }

    /**
     * The form's submit candidate that selection must skip on {@code state} (form-completion
     * INV-FORM-06): non-null only when {@code FORM_COMPLETION} is mounted and the state still has an
     * unfilled EditText, so the exclusion lifts once every field is filled (INV-FORM-07).
     */
    private ModelAction submitExcluded(State state) {
        abaTargetTie(); // mounts submitExclusion with the rest
        if (!submitExclusion || !FormCompletion.hasUnfilledEditText(state, timestamp)) {
            return null;
        }
        return FormCompletion.selectSubmitCandidate(state, timestamp);
    }

    /**
     * The telemetry of the SATA pick under way (event-sink {@code dec.strat}, {@code draw},
     * {@code cfc}, {@code nav}, {@code bmc}). The pick sites fill it as they decide; it reaches the
     * step's record only in {@link #logActionSelected}, which the SATA chain calls for the rung whose
     * action the step takes. A rung that declines leaves at most its tie behind, which the last tie
     * of the step overwrites (action-selection "SATA Navigation Extension Points"). Pure: it holds
     * values the decisions already computed (INV-SNK-16).
     */
    private static final class SataTrace {
        String strat;
        boolean hasDraw;
        int drawIndex;
        int drawTotal;
        final Map<String, Boolean> cfc = new LinkedHashMap<>();
        String navPoint;
        String navVariant;
        boolean navAlt;
        boolean navDrew;
        int backMenuCapped;
    }

    private SataTrace trace() {
        if (trace == null) {
            trace = new SataTrace();
        }
        return trace;
    }

    /**
     * Stamps a SATA pick: its channel (INV-SEL-05) and its path ({@code dec.strat}). The owner is
     * {@code sata} for every action this agent picks; {@link #logActionSelected} writes it.
     *
     * @return {@code action}, for the pick site's return
     */
    private ModelAction chose(ModelAction action, ModelAction.PickChannel channel, String strat) {
        if (action != null) {
            action.setPickChannel(channel);
            trace().strat = strat;
        }
        return action;
    }

    /** Records {@code dec.draw} for the roulette that chose the step's action. */
    private void recordDraw(int index, int total) {
        SataTrace t = trace();
        t.hasDraw = true;
        t.drawIndex = index;
        t.drawTotal = total;
    }

    /** Records {@code dec.nav}: an extension point decided between two or more candidates. */
    // Package-private so that BufferAbandonTest can stand in for the search's X4 record (D-B21).
    void recordNav(String point, String variant, boolean alt, boolean drew) {
        SataTrace t = trace();
        t.navPoint = point;
        t.navVariant = variant;
        t.navAlt = alt;
        t.navDrew = drew;
    }

    /**
     * Records {@code dec.cfc} for a pick over {@code candidates} (action-selection "Per-step
     * counterfactual attribution"): for each scorer whose field is non-zero on at least one
     * candidate — only a mounted scorer writes its field — whether {@code recompute}, the same pick
     * with that scorer zeroed, selects another action than {@code picked}. A recomputation that
     * fails or finds nothing records 0. Pure: the recomputations replay, they never draw.
     */
    private void recordCounterfactual(List<ModelAction> candidates, ModelAction picked,
            java.util.function.Function<Scorer, ModelAction> recompute) {
        for (Scorer scorer : Scorer.values()) {
            if (!anyNonZero(candidates, scorer)) {
                continue;
            }
            ModelAction alternative = counterfactual(() -> recompute.apply(scorer));
            trace().cfc.put(scorer.wire(), alternative != null && alternative != picked);
        }
    }

    private static boolean anyNonZero(List<ModelAction> candidates, Scorer scorer) {
        for (ModelAction candidate : candidates) {
            if (scorer.of(candidate) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Records {@code dec.cfc} for a roulette that drew {@code index} below {@code total}. */
    private void recordRouletteCounterfactual(List<ModelAction> candidates, ModelAction picked,
            int index, int total) {
        recordCounterfactual(candidates, picked,
                scorer -> ScorerCounterfactual.replayRoulette(candidates, index, total, scorer));
    }

    @Override
    public void logActionSelected(Action action, SataEventType type) {
        Logger.iformat("Select action %s by strategy %s", action, type);
        // Every action this agent picks is owned by SATA (dec.src, INV-SEL-14); the scorers'
        // terms stay in their own fields and their effect in dec.cfc. The EARLY_STAGE and
        // EPSILON_GREEDY rungs stamp their channel at the pick site; every other rung is the
        // buffer or sata_other (INV-SEL-05). The writes also clear what a previous step left on a
        // reused action object.
        if (action != null && action.isModelAction()) {
            ModelAction modelAction = (ModelAction) action;
            modelAction.setDecisionSource(ModelAction.DecisionSource.SATA);
            if (type == SataEventType.USE_BUFFER) {
                chose(modelAction, ModelAction.PickChannel.BUFFER, "buffer");
            } else if (type != SataEventType.EARLY_STAGE && type != SataEventType.EPSILON_GREEDY) {
                modelAction.setPickChannel(ModelAction.PickChannel.SATA_OTHER);
            }
            writeTrace();
        }
        logEvent(type);
    }

    /** Hands the chosen pick's telemetry to the step's open record. */
    private void writeTrace() {
        SataTrace t = trace();
        EventSink sink = RunContext.current().sink();
        if (t.strat != null) {
            sink.strategy(t.strat);
        }
        if (t.hasDraw) {
            sink.draw(t.drawIndex, t.drawTotal);
        }
        for (Map.Entry<String, Boolean> entry : t.cfc.entrySet()) {
            sink.cfc(entry.getKey(), entry.getValue());
        }
        if (t.navPoint != null) {
            sink.nav(t.navPoint, t.navVariant, t.navAlt, t.navDrew);
        }
        if (t.backMenuCapped > 0) {
            sink.backMenuCapped(t.backMenuCapped);
        }
    }

    /**
     * The candidates a pick channel would walk: the state's actions passing {@code filter}, in the
     * order the roulette and the least-visited scan iterate them, minus the form-submit exclusion
     * when the caller applies one. {@code dec.cfc} recomputes over exactly this set — the
     * counterfactual changes the weights, never the candidates.
     */
    private static List<ModelAction> candidatesOf(State state, ActionFilter filter,
                                                  ModelAction excluded) {
        List<ModelAction> candidates = new ArrayList<>();
        for (ModelAction action : state.getActions()) {
            if (action == excluded || !filter.include(action)) {
                continue;
            }
            candidates.add(action);
        }
        return candidates;
    }

    /**
     * {@code dec.bmc}: how many actions passing {@code base} (minus {@code excluded}) the BACK/MENU
     * cap's wrapped filter removed. 0 when the cap is off. Pure: both filters only read flags.
     */
    private static int removedByCap(State state, ActionFilter base, ActionFilter capped,
            ModelAction excluded) {
        int removed = 0;
        for (ModelAction action : state.getActions()) {
            if (action != excluded && base.include(action) && !capped.include(action)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Run a counterfactual recomputation with failure contained: the factual pick has already been
     * made, so a recomputation that throws costs the record that scorer's {@code 1} and nothing
     * else (action-selection "Per-step counterfactual attribution").
     */
    private static ModelAction counterfactual(Supplier<ModelAction> recomputation) {
        try {
            return recomputation.get();
        } catch (Exception e) {
            Logger.wformat("[APE-RV] counterfactual recomputation failed: %s", e.getMessage());
            return null;
        }
    }

    protected void logEvent(SataEventType type) {
        int ordinal = type.ordinal();
        actionCounters[ordinal] = actionCounters[ordinal] + 1;
    }

    public void tearDown() {
        super.tearDown();
        printCounters();
    }

    /**
     * Per-state UI-coverage dump (read-only), emitted strictly before {@code actionCounters} —
     * the first teardown step that produces output (INV-COV-10). {@code mopReach} is computed
     * here because the tracker holds no MopData reference (D4): a state's Activity gates a MOP
     * target iff {@code activityHasMop(activity)}. It is a MOP signal, so the predicate exists only
     * when {@code MOP} is mounted ({@link #getMopData()} is null otherwise, design D-B14.21); a null
     * predicate is the tracker's "no mopReach" case.
     */
    @Override
    protected void dumpCoverage() {
        MopData mopData = getMopData();
        getCoverageTracker().dump(mopData == null ? null
                : state -> mopData.activityHasMop(state.getActivity()));
    }

    protected void printCounters() {
        SataEventType[] types = SataEventType.values();
        for (SataEventType type : types) {
            Logger.format("%6d  %s", actionCounters[type.ordinal()], type);
        }
    }

    protected ModelAction checkBackTrack() {
        if (newState.isSaturated()) { // no forward unsaturated actions selected by EARLY_STAGE
            Logger.iprintln("State is saturated: try to back track.");
            boolean doBackTrack = false;
            LinkedList<State> queue = new LinkedList<State>();
            Set<State> visited = new HashSet<>();
            queue.add(newState);
            visited.add(newState);
            State state = null;
            outer: while (!queue.isEmpty()) {
                state = queue.removeFirst();
                ModelAction action = state.getBackAction();
                Collection<StateTransition> sts = getGraph().getOutStateTransitions(action);
                for (StateTransition st : sts) {
                    if (st.isCircle()) {
                        continue;
                    }
                    if (st.isStrong()) {
                        State target = st.getTarget();
                        if (!target.isSaturated()) {
                            doBackTrack = true;
                            break outer;
                        } else {
                            if (!visited.contains(state)) {
                                queue.addLast(target);
                                visited.add(state);
                            }
                        }
                    }
                }
            }
            if (doBackTrack && newState != state) {
                ModelAction action = moveToState(newState, state, true);
                if (action != null) {
                    Logger.iprintln("Backtrack to an unsaturated state: " + state);
                    chose(action, ModelAction.PickChannel.SATA_OTHER, "saturated");
                    logActionSelected(action, SataEventType.SATURATED_STATE);
                    return action;
                } else {
                    Logger.iformat("Cannot backtrack to %s", state);
                }
            }
        }
        return null;
    }

    protected boolean isDialogState(State state) {
        Collection<StateTransition> edges = getGraph().getInStateTransitions(state);
        int threshold = 5;
        if (edges.size() <= threshold) {
            return false;
        }
        return hasGreedyActionForward(state);
    }

    @Override
    public LlmEngine llmEngine() {
        return RunContext.current().llmEngine();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reached only from an LLM stage, which exists only on a plan that built the client, so the
     * dereference is safe by assembly rather than by a guard.
     */
    @Override
    public boolean llmBreakerAllows() {
        return RunContext.current().llmClient().allows();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the edge to the stages after the agent's own bookkeeping has run, which is the
     * order the episode state depends on: the stagnation stage re-arms on exactly the transitions
     * that reset the stability counter it reads, so re-arming before the reset would show it the
     * previous step's counter.
     */
    @Override
    public void onVisitStateTransition(StateTransition edge) {
        super.onVisitStateTransition(edge);
        decisionPipeline.onStateTransition(edge);
    }

    /**
     * Resolves a synthesized off-tree tap against {@code newState} so it carries a
     * {@code GUITreeAction} before dispatch (llm-coordinate-tap, D7).
     *
     * <p>Agent-side because the throttle it resolves with is computed from the state's history. The
     * targetless branch of {@link State#resolveAction} populates the action exactly as
     * {@code MODEL_MENU} / {@code MODEL_BACK} are populated; without it {@link #resolveNewAction()}
     * would read a null {@code GUITreeAction} and {@code assertNotNull} would throw.
     *
     * @param tap the synthesized tap, already screened by the LLM stages' resolution guard
     */
    @Override
    public void resolveSynthesizedTap(ModelAction tap) {
        newState.resolveAction(this, tap, getThrottleForNewAction(newState, tap));
    }

    protected Action selectNewActionNonnull() {
        {
            // Logging
            printStrategy();
            Logger.iprintln("Check global actions.");
            for (ModelAction action : newState.targetedActions()) {
                if (action.isVisited()) {
                    continue;
                }
                if (getGraph().isNameGlobalAction(action)) {
                    Logger.iformat("- %s", action);
                }
            }
        }
        trace = new SataTrace();
        return decisionPipeline.decide(this);
    }

    @Override
    public ModelAction selectNewActionEarlyStageBackward() {
        return selectNewActionEarlyStageBackwardGreedy();
    }

    public void onBufferLoss(State actual, State expected) {
        logEvent(SataEventType.BUFFER_LOSS);
    }

    @Override
    public ModelAction selectNewActionEpsilonGreedyRandomly() {
        // back-menu-pick-cap: bound discretionary BACK/MENU re-picks per (activity, type) across
        // NamingFactory-minted sibling states (INV-SEL-NAV-01). Only the discretionary channels
        // here are capped — the short-circuits, the least-visited pick, and the roulette. The
        // navigation-essential BACK sites (selectNewActionBackToActivity, backToTrivialActivity,
        // checkBackTrack, handleNullAction) never consult backMenuPicks (INV-SEL-NAV-03).
        String activity = newState.getActivity();
        int cap = exploration.backMenuPickCap();
        String backKey = backMenuPickKey(ActionType.MODEL_BACK, activity);
        String menuKey = backMenuPickKey(ActionType.MODEL_MENU, activity);
        ModelAction back = newState.getBackAction();
        if (back.isValid()) {
            if (back.isUnvisited() && eligibleForCappedPick(backMenuPicks, backKey, cap)) {
                Logger.iprintln("Select Back because Back action is unvisited.");
                recordBackMenuPick(ActionType.MODEL_BACK, activity);
                return chose(back, ModelAction.PickChannel.SATA_OTHER, "eps_back");
            }
        }
        ModelAction menu = newState.getMenuAction();
        if (menu.isValid()) {
            if (menu.isUnvisited() && eligibleForCappedPick(backMenuPicks, menuKey, cap)) {
                Logger.iprintln("Select Menu because Menu action is unvisited.");
                recordBackMenuPick(ActionType.MODEL_MENU, activity);
                return chose(menu, ModelAction.PickChannel.SATA_OTHER, "eps_menu");
            }
        }
        // back-menu-pick-cap: capped BACK/MENU must also fall out of the discretionary least-visited
        // and roulette channels — else a refinement-minted sibling (visitedCount == 0) would win
        // least-visited outright, merely relabeling the re-pick channel (Decision 3). One wrapped
        // filter, stable across the roulette's count/pick passes (INV-SEL-NAV-04).
        ActionFilter cappedFilter =
                cappedBackMenuFilter(ActionFilter.ENABLED_VALID, backMenuPicks, activity, cap);
        if (egreedy()) { // TODO: this is different from Sarsa.
            Logger.iformat("Try to select the least visited action.");
            // INV-FORM-06: the submit candidate is not picked by visit count while the form has
            // unfilled EditTexts; the roulette below weighs it by priority instead.
            ModelAction submitExcluded = submitExcluded(newState);
            List<ModelAction> tied = newState.leastVisited(cappedFilter, submitExcluded);
            ModelAction leastVisited = State.pickLeastVisited(tied, LEAST_VISITED_TIE);
            if (leastVisited != null) {
                trace().backMenuCapped = removedByCap(newState, ActionFilter.ENABLED_VALID,
                        cappedFilter, submitExcluded);
                if (tied.size() > 1) {
                    // X1 has one strategy in every cell: c is ape and alt 0; what the scorer sum
                    // did is read from dec.cfc (design D-B14.8).
                    recordNav("X1", "ape", false, false);
                    recordCounterfactual(tied, leastVisited,
                            scorer -> LEAST_VISITED_TIE.pickWithout(tied, scorer));
                }
                recordBackMenuPick(leastVisited.getType(), activity);
            }
            return chose(leastVisited, ModelAction.PickChannel.SATA_OTHER, "least_visited");
        }
        Logger.iformat("Try to randomly select a visited action.");
        // Priority roulette. The recorded form reports the draw, so dec.draw can replay it and
        // dec.cfc can recompute it without a draw of its own (INV-SEL-09).
        State.RoulettePick pick = newState.randomlyPickActionRecorded(getRandom(), cappedFilter, true);
        ModelAction roulettePick = pick.getAction();
        if (roulettePick != null) {
            List<ModelAction> candidates = candidatesOf(newState, cappedFilter, null);
            recordDraw(pick.getDrawIndex(), pick.getTotal());
            recordRouletteCounterfactual(candidates, roulettePick, pick.getDrawIndex(),
                    pick.getTotal());
            trace().backMenuCapped = removedByCap(newState, ActionFilter.ENABLED_VALID,
                    cappedFilter, null);
            recordBackMenuPick(roulettePick.getType(), activity);
        }
        return chose(roulettePick, ModelAction.PickChannel.ROULETTE_GREEDY, "eps_roulette");
    }

    // back-menu-pick-cap: per-run count of discretionary MODEL_BACK/MODEL_MENU picks, keyed by
    // (activity, type). NamingFactory refinement re-arms the "unvisited" BACK/MENU short-circuits
    // for every sibling state; this cap bounds discretionary re-picks across them (INV-SEL-NAV-01).
    // Navigation-essential BACK sites do not consult this map. The action objects are never
    // mutated — only the roulette/short-circuit candidate sets are shaped.
    private final Map<String, Integer> backMenuPicks = new HashMap<>();

    /**
     * INV-MOP-33: the Nav MOP-tiebreak decision line, or {@code null} when density did not decide
     * (all-equal random fallback). Emitted only by the X4 MOP variant ({@code MopTrivialPathPick}),
     * at the path-selection site; never alters which path is chosen. Pure.
     */
    public static String navMopTiebreakLog(int winningDensity, int pathCount, boolean densityDecided) {
        if (!densityDecided) {
            return null;
        }
        return String.format("[APE-RV] Nav MOP tiebreak: density=%d paths=%d",
                winningDensity, pathCount);
    }

    /**
     * Whether a BACK/MENU key may still be picked by the discretionary channels. True when the cap
     * is disabled ({@code cap <= 0}), when the key is uncountable ({@code key == null}), or when the
     * key's recorded count is below the cap. Pure read — no mutation, no logging.
     */
    static boolean eligibleForCappedPick(Map<String, Integer> picks, String key, int cap) {
        if (cap <= 0 || key == null) {
            return true;
        }
        return picks.getOrDefault(key, 0) < cap;
    }

    /**
     * Records one discretionary pick for {@code key}. Returns true iff this increment brought the
     * count to exactly {@code cap} — the single moment to log the cap event (subsequent picks are
     * filtered by {@link #eligibleForCappedPick}, so the key never reaches the cap twice). No-op
     * returning false when the cap is disabled or the key is uncountable (INV-SEL-NAV-02: no
     * counter update). Mutates only {@code picks}.
     */
    static boolean recordCappedPick(Map<String, Integer> picks, String key, int cap) {
        if (cap <= 0 || key == null) {
            return false;
        }
        int n = picks.getOrDefault(key, 0) + 1;
        picks.put(key, n);
        return n == cap;
    }

    /**
     * back-menu-pick-cap key: {@code activity + "|" + type.name()} for the two target-less
     * discretionary types (MODEL_BACK, MODEL_MENU); null for any other type or a null activity
     * (uncountable → always eligible). Activity-scoped (not stateKey-scoped) so it absorbs
     * NamingFactory refinement re-arm across sibling states. Pure. (INV-SEL-NAV-01)
     */
    static String backMenuPickKey(ActionType type, String activity) {
        if (activity == null || (type != ActionType.MODEL_BACK && type != ActionType.MODEL_MENU)) {
            return null;
        }
        return activity + "|" + type.name();
    }

    /**
     * Wraps {@code base} so a capped MODEL_BACK/MODEL_MENU is additionally excluded — the discretionary
     * least-visited and roulette channels must not re-elect a type the short-circuit already capped
     * (a refinement-minted sibling carries {@code visitedCount == 0}, which would win least-visited
     * outright). Eligibility is read once per type here, so the returned filter is stable across the
     * roulette's count/pick passes (INV-SEL-NAV-04). Pure — no mutation, mirrors the base for every
     * other action. Returned filter reads only the captured snapshot booleans.
     */
    static ActionFilter cappedBackMenuFilter(final ActionFilter base, Map<String, Integer> picks,
            String activity, int cap) {
        final boolean backEligible =
                eligibleForCappedPick(picks, backMenuPickKey(ActionType.MODEL_BACK, activity), cap);
        final boolean menuEligible =
                eligibleForCappedPick(picks, backMenuPickKey(ActionType.MODEL_MENU, activity), cap);
        return new BaseActionFilter() {
            @Override
            public boolean include(ModelAction action) {
                if (!base.include(action)) {
                    return false;
                }
                ActionType t = action.getType();
                if (t == ActionType.MODEL_BACK) {
                    return backEligible;
                }
                if (t == ActionType.MODEL_MENU) {
                    return menuEligible;
                }
                return true;
            }
        };
    }

    /**
     * Records a discretionary BACK/MENU pick against {@code backMenuPicks} and logs once when the
     * (activity, type) key reaches the cap. No-op when the type is not BACK/MENU, the activity is
     * null, or the cap is disabled. Instance method — mutates only {@code backMenuPicks}.
     */
    private void recordBackMenuPick(ActionType type, String activity) {
        int cap = exploration.backMenuPickCap();
        String key = backMenuPickKey(type, activity);
        if (recordCappedPick(backMenuPicks, key, cap)) {
            Logger.iformat("[APE-RV] BACK/MENU capped: activity=%s type=%s picks=%d",
                    activity, type.name(), cap);
        }
    }

    /**
     * Returns {@code candidates} with any capped target-less MODEL_BACK/MODEL_MENU removed (used by
     * the EARLY_STAGE forward roulette). Returns {@code candidates} unchanged when the cap is
     * disabled. Read-only — never mutates the actions or {@code backMenuPicks}.
     */
    private List<ModelAction> dropCappedBackMenu(List<ModelAction> candidates, String activity) {
        int cap = exploration.backMenuPickCap();
        if (cap <= 0) {
            return candidates;
        }
        ActionFilter capped = cappedBackMenuFilter(ActionFilter.ALL, backMenuPicks, activity, cap);
        List<ModelAction> out = new ArrayList<>(candidates.size());
        for (ModelAction a : candidates) {
            if (capped.include(a)) {
                out.add(a);
            }
        }
        return out;
    }


    public void onRefillBuffer(Subsequence seq) {
        logEvent(SataEventType.FILL_BUFFER);
    }

    protected ModelAction fillTransition(State[] states) {
        return fillTransition(states, exploration.fillTransitionsByHistory(),
                exploration.fallbackToGraphTransition());
    }

    protected ModelAction fillTransition(State[] states, boolean byHistory, boolean fallback) {
        if (byHistory) {
            Subsequence path = getGraph().fillTransitionsByHistory(states);
            if (path != null) {
                return refillBuffer(path);
            }
            Logger.println("Fill transitions by history failed!");
            if (!fallback) {
                return null;
            }
        }
        List<Subsequence> selectedPaths = new ArrayList<Subsequence>();
        getGraph().fillTransitions(selectedPaths, states);
        if (!selectedPaths.isEmpty()) {
            int total = selectedPaths.size();
            int index = this.nextInt(total);
            Subsequence path = selectedPaths.get(index);
            refillBuffer(path);
            return path.getFirstAction();
        }
        return null;
    }

    protected ModelAction moveToState(State start, State end, boolean includeBack) {
        List<Subsequence> selectedPaths = new ArrayList<Subsequence>();
        getGraph().moveToState(selectedPaths, start, end, includeBack, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = RandomHelper.randomPick(selectedPaths);
            return refillBuffer(path);
        }
        return null;
    }

    /**
     * ABA is a circle in the model graph, e.g., a path starting from A, B and eventualy ending at A.
     * @param fromA
     * @param toB
     * @param verbose
     * @return
     */
    protected boolean doABA(State fromA, State toB, boolean verbose) {
        if (isDialogState(toB)) {
            if (verbose) {
                Logger.iformat("Never move to a saturated dialog state (%s) in ABA.", toB);
            }
            return false;
        }
        if (fromA.getActivity().equals(toB.getActivity())) {
            if (toB.getVisitedCount() >= fromA.getVisitedCount()) {
                if (verbose) {
                    Logger.iformat("Never move from a cold state (%s) to a hot state (%s) in ABA.", fromA, toB);
                }
                return false;
            }
        } else {
            ActivityNode AA = getGraph().getActivityNode(fromA.getActivity());
            ActivityNode BA = getGraph().getActivityNode(toB.getActivity());
            if (BA.getVisitedCount() >= AA.getVisitedCount()) {
                if (verbose) {
                    Logger.iformat("Never move from a cold activity (%s)(%s) to a hot activity (%s)(%s) in ABA.",
                            fromA, AA, toB, BA);
                }
                return false;
            }
        }
        Logger.iformat("Move from A (%s) to B (%s).", fromA, toB);
        return true;
    }

    protected List<ModelAction> getGreedyActions(State to) {
        return getGreedyActions(null, to);
    }

    protected boolean isGreedyState(State to) {
        return isGreedyState(null, to);
    }

    /**
     * We will visited every ``unvisited action''.
     * The identifier of an ``unvisited action'' may be its target (widget) only or
     * the combination of its state (the set of all widgets) and target (widget).
     * @param from
     * @param to
     * @return
     */
    protected List<ModelAction> getGreedyActions(State from, State to) {
        if (exploration.useActionDiffer()) {
            return this.actionDiffer.getUnsaturated(from, to);
        }
        return to.collectActions(new BaseActionFilter() {

            @Override
            public boolean include(ModelAction action) {
                if (!ActionFilter.ENABLED_VALID.include(action)) {
                    return false;
                }
                if (!action.requireTarget()) {
                    return false;
                }
                if (action.isScroll()) {
                    return action.isUnvisited();
                }
                return getGraph().isActionUnvisitedByName(action);
            }

        });
    }

    protected boolean isGreedyState(State from, State to) {
        return !getGreedyActions(from, to).isEmpty();
    }

    protected ModelAction selectNewActionEarlyStageForABAInternal() {
        if (currentState == null) {
            return null;
        }
        State A = newState;
        State B = currentState;
        Logger.iprintln("Check A*BA->B, try to move from A to B.");
        Logger.iformat("> - A: %s", A);
        Logger.iformat("> - B: %s", B);
        if (!doABA(A, B, true)) {
            return null;
        }
        List<Subsequence> forwardPaths = getGraph().moveToState(A, B, false);
        if (forwardPaths.isEmpty()) {
            Logger.iprintln("A cannot reach B.");
            return null;
        }
        List<Subsequence> backwardPaths = getGraph().moveToState(B, A, true);
        if (backwardPaths.isEmpty()) {
            Logger.iprintln("B cannot reach A.");
            return null;
        }
        Subsequence path = randomPickShortest(forwardPaths);
        Logger.iformat("Try to find a path (1/%d) to a greedy state that we want to greedily visit in ABA.", forwardPaths.size());
        path.print();
        StateTransition[] edges = path.getEdges();
        int lastIndex = -1;
        State lastTarget = null;
        for (int i  = 0; i < edges.length; i++) {
            State source = edges[i].getSource();
            State target = edges[i].getTarget();
            if (!doABA(source, target, true)) {
                break;
            }
            if (isGreedyState(source, target)) {
                if (lastTarget == null) {
                    lastIndex = i;
                    lastTarget = target;
                } else {
                    // Prefer the colder state within an activity, the colder activity across two;
                    // an equal count is X3's tie.
                    int cmp;
                    if (target.getActivity().equals(lastTarget.getActivity())) {
                        cmp = Integer.compare(target.getVisitedCount(), lastTarget.getVisitedCount());
                    } else {
                        ActivityNode an1 = getGraph().getActivityNode(target.getActivity());
                        ActivityNode an2 = getGraph().getActivityNode(lastTarget.getActivity());
                        cmp = Integer.compare(an1.getVisitedCount(), an2.getVisitedCount());
                    }
                    boolean replace = cmp < 0;
                    if (cmp == 0) {
                        AbaTargetTie tie = abaTargetTie();
                        replace = tie.replaces(lastTarget, target);
                        NavVariant variant = (NavVariant) tie;
                        recordNav("X3", variant.variant(), variant.decidedByOwnRule(), false);
                    }
                    if (replace) {
                        lastIndex = i;
                        lastTarget = target;
                    }
                }
            }
        }
        if (lastIndex < 0) {
            Logger.iprintln("No state or path that we want to greedily visit in ABA.");
            return null;
        }
        if (lastIndex != edges.length - 1) {
            List<StateTransition> newEdges = new ArrayList<>(lastIndex + 1);
            for (int i = 0; i <= lastIndex; i++) {
                newEdges.add(edges[i]);
            }
            path = new Subsequence(newEdges);
            Logger.iformat("Update B from %s to %s", B, path.getLastState());
            B = path.getLastState();
        }
        ModelAction action = chose(refillBuffer(path), ModelAction.PickChannel.SATA_OTHER, "aba");
        if (action != null) {
            Logger.iformat("Move from A (%s) to B (%s) for ABA within %d steps that start from action %s",
                    A, B, path.size(), action);
        }
        return action;
    }

    @Override
    public ModelAction selectNewActionBackToActivity() {
        if (this.backToActivity == null) {
            return null;
        }
        Stack stack = AndroidDevice.getFocusedStack();
        if (stack.getTasks().isEmpty()) {
            this.backToActivity = null;
            return null;
        }
        if (stack.getTasks().get(0).getActivities().isEmpty()) {
            this.backToActivity = null;
            return null;
        }
        stack.dump();
        int totalActivities = 0;
        int onStackIndex = -1;
        for (Task task : stack.getTasks()) {
            for (Activity a : task.getActivities()) {
                if (a.activity.getClassName().equals(backToActivity.activity)) {
                    onStackIndex = totalActivities;
                }
                totalActivities ++;
            }
        }
        if (totalActivities == 1 || onStackIndex == -1) {
            this.backToActivity = null;
            return null;
        }
        if (totalActivities > 1) { // not the topmost
            if (onStackIndex != 0) {
                if (newState.isBackEnabled()) {
                    Logger.iformat("Backtrack to %s, total=%d", backToActivity.activity, totalActivities);
                    return chose(newState.getBackAction(), ModelAction.PickChannel.SATA_OTHER,
                            "back_to_activity");
                }
            } else { // top most trivial activity
                Logger.iformat("Backtrack stopped at %s, total=%d", backToActivity.activity, totalActivities);
                this.backToActivity = null;
                return null;
            }

        }
        return null;
    }

    /**
     * Explore connected component first.
     * @return
     */
    protected ModelAction selectNewActionEarlyStageForABA() {
        return selectNewActionEarlyStageForABAInternal();
    }

    @Override
    public String getLoggerName() {
        return "SATA";
    }

    @Override
    public void onActivityStopped() {
        super.onActivityStopped();
    }

    protected boolean egreedy() {
        double effectiveEpsilon = computeDynamicEpsilon();
        // The agent's RNG is reached through getRandom() (ApeAgent:322, `return ape.getRandom()`),
        // the same accessor the roulette (:681), the component trigger (:548) and handleNullAction
        // already use. Identical stream, and it is what lets a test subclass pin this coin flip:
        // the harness has no MonkeySourceApe to put in `ape`, so reading the field directly closed
        // both legs of this rung to the parity oracle (rearch-01, INV-ORA-01).
        double v = getRandom().nextDouble();
        Logger.iformat("EGreedy value=%f, epsilon=%f.", v, effectiveEpsilon);
        if (v < effectiveEpsilon) {
            return false;
        }
        return true;
    }

    protected double computeDynamicEpsilon() {
        if (!exploration.dynamicEpsilon()) {
            return epsilon;
        }
        UICoverageTracker tracker = getCoverageTracker();
        if (tracker == null) {
            return epsilon;
        }
        float coverageGap = tracker.getCoverageGap(newState);
        return exploration.minEpsilon()
                + (exploration.maxEpsilon() - exploration.minEpsilon()) * coverageGap;
    }

    @Override
    public ModelAction selectNewActionEarlyStageForward() {
        // A simple Depth First
        ModelAction action = selectNewActionEarlyStageForABA();
        if (action != null) {
            return action;
        }
        return selectNewActionEarlyStageForwardGreedy();
    }

    protected Set<ActivityNode> collectTrivialActivities() {
        ActivityNode[] activities = getGraph().getActivityNodes();
        if (activities.length <= exploration.trivialActivityRankThreshold()) {
            return Collections.emptySet();
        }
        Arrays.sort(activities, new Comparator<ActivityNode>() {

            @Override
            public int compare(ActivityNode o1, ActivityNode o2) {
                int ret = o1.getVisitedCount() - o2.getVisitedCount();
                if (ret != 0) {
                    return ret;
                }
                return o1.activity.compareTo(o2.activity);
            }

        });
        int median = getMedianVisitedCount(activities);
        int mean = getMeanVisitedCount(activities);
        int threshold = Math.max(median, mean);
        Set<ActivityNode> trivialActivities = new HashSet<>();
        for (int i = 0; i < activities.length; i++) {
            if (activities[i].getVisitedCount() <= threshold) {
                if (isTrivialActivity(activities[i])) {
                    trivialActivities.add(activities[i]);
                }
            } else {
                break;
            }
        }
        return trivialActivities;
    }

    /**
     * A heuristic of identifying trivial activity.
     * @param activityNode
     * @return
     */
    private boolean isTrivialActivity(ActivityNode activityNode) {
        int stateSize = activityNode.getStates().size();
        float visitedRate = activityNode.getVisitedRate();
        int visitCount = activityNode.getVisitedCount();
        if (stateSize < 5) { // simple activity
            if (visitCount > stateSize >> 2) { // hard to visit
                if (visitedRate > 0.8) {
                    return false; // e.g., Login, About, Help, Feedback
                } else {
                    return true;
                }
            } else {
                return true;
            }
        } else {
            if (visitCount > stateSize >> 2) {
                if (visitedRate > 0.5) {
                    return false; // Login
                } else {
                    return true;
                }
            } else { // less visited
                return true;
            }
        }
    }

    @Override
    public ModelAction selectNewActionForTrivialActivity() {
        final Set<ActivityNode> trivialActivities = collectTrivialActivities();
        if (trivialActivities.isEmpty()) {
            return null;
        }
        ActivityNode an = getGraph().getActivityNode(newState.getActivity());
        if (trivialActivities.contains(an)) {
            return null;
        }
        Logger.iprintln("List trivial activities.");
        for (ActivityNode a : trivialActivities) {
            Logger.iformat("- %s", a);
        }
        int pathLength = Integer.MAX_VALUE;
        SubsequenceFilter filter = new SubsequenceFilter() {

            @Override
            public boolean include(Subsequence path) {
                if (path.isClosed()) {
                    return false;
                }
                State last = path.getLastState();
                ActivityNode an = getGraph().getActivityNode(last.getActivity());
                if (!trivialActivities.contains(an)) {
                    return false;
                }
                if (isGreedyState(last)) {
                    return true;
                }
                return last.firstEnabledUnvisitedValidAction() != null;
            }

            @Override
            public boolean extend(Subsequence path, StateTransition edge) {
                if (edge.action.isBack()) {
                    return false;
                }
                if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                    return false;
                }
                return edge.isStrong();
            }

        };
        {
            List<Subsequence> selectedPaths = getGraph().findShortestPaths(newState, filter, pathLength);
            if (!selectedPaths.isEmpty()) {
                TrivialPathPick pathPick = trivialPathPick();
                Subsequence path = pathPick.pick(selectedPaths);
                if (selectedPaths.size() > 1) {
                    // X4 decided among two or more paths: by the MOP variant's density without a
                    // draw (alt), otherwise by randomPick's one helper-stream draw.
                    NavVariant variant = (NavVariant) pathPick;
                    boolean alt = variant.decidedByOwnRule();
                    recordNav("X4", variant.variant(), alt, !alt);
                }
                Logger.iformat("Find a path (1/%d) to a trivial activity %s.", selectedPaths.size(),
                        getGraph().getActivityNode(path.getLastState().getActivity()));
                return chose(refillBuffer(path), ModelAction.PickChannel.SATA_OTHER, "trivial");
            }
        }
        if (exploration.doBackToTrivialActivity()) {
            return chose(backToTrivialActivity(trivialActivities),
                    ModelAction.PickChannel.SATA_OTHER, "trivial");
        }
        return null;
    }

    /**
     * The budget's navigation (design D-B20): the trivial-activity search, run with the SATA buffer
     * detached so that the path it plans neither weakens nor is mistaken for the buffer it preempts.
     * No navigation puts the buffer back untouched; a navigation abandons a non-empty detached buffer
     * ({@code dec.buf:"budget"}) and leaves the new path buffered for {@code SataChain} to follow.
     */
    @Override
    public ModelAction budgetNavigation() {
        LinkedList<StateTransition> preempted = detachBuffer();
        ModelAction action = selectNewActionForTrivialActivity();
        if (action == null) {
            restoreBuffer(preempted);
            return null;
        }
        if (!preempted.isEmpty()) {
            recordAbandonment(BudgetStage.OWNER);
        }
        // The X4 tie the search may have decided is the budget step's only SATA telemetry: its
        // dec.nav, without the dec.strat the search's chose() stamps in the trace (design D-B21).
        SataTrace t = trace();
        if (t.navPoint != null) {
            RunContext.current().sink().nav(t.navPoint, t.navVariant, t.navAlt, t.navDrew);
        }
        return action;
    }

    private ModelAction backToTrivialActivity(Set<ActivityNode> trivialActivities) {
        Stack taskStack = AndroidDevice.getFocusedStack();
        taskStack.dump();
        ActivityNode topActivity = null;
        for (Task task : taskStack.getTasks()) {
            for (Activity a : task.getActivities()) {
                Logger.iformat("Checking activity %s", a.activity.getClassName());
                ActivityNode taskAN = getGraph().getActivityNode(a.activity.getClassName());
                if (taskAN != null && trivialActivities.contains(taskAN)) {
                    topActivity = taskAN;
                }
            }
        }
        if (topActivity == null) {
            return null;
        }
        if (newState.isBackEnabled()) {
            Logger.iformat("Try to backtrack to trivial activity %s", topActivity.activity);
            backToActivity = topActivity;
            return newState.getBackAction();
        }
        return null;
    }

    private int getMedianVisitedCount(ActivityNode[] activities) {
        int total = activities.length;
        if (total == 0) {
            return -1;
        }
        return activities[total >> 1].getVisitedCount();
    }

    private int getMeanVisitedCount(ActivityNode[] activities) {
        int total = activities.length;
        if (total == 0) {
            return -1;
        }
        int count = 0;
        for (ActivityNode an : activities) {
            count += an.getVisitedCount();
        }
        return count / total;
    }

    protected ModelAction selectNewActionEarlyStageForwardGreedy() {
        assertEmptyActionBuffer();
        ModelAction action = findGreedyActionForward(currentState, newState);
        if (action != null) {
            return action;
        }
        return null;
    }

    protected ModelAction selectNewActionEarlyStageBackwardGreedy() {
        ModelAction action = findGreedyActionBackward(currentState, newState);
        if (action != null) {
            return action;
        }
        return null;
    }

    protected boolean hasGreedyActionForward(State state) {
        List<ModelAction> actions = getGreedyActions(state);
        if (!actions.isEmpty()) {
            return true;
        }
        // 2). Greedy action in the neighbor hood.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(state, greedySubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            return true;
        }
        return false;
    }

    protected Subsequence randomPickShortest(List<Subsequence> subsequences) {
        Collections.sort(subsequences, new Comparator<Subsequence>() {

            @Override
            public int compare(Subsequence o1, Subsequence o2) {
                return o1.size() - o2.size();
            }

        });
        return subsequences.get(0);
    }

    protected ModelAction findGreedyActionForward(State prev, State next) {
        // 0-step
        ModelAction action = null;
        // 1). Greedy action on this state.
        List<ModelAction> actions = getGreedyActions(prev, next); // this.actionDiffer.getUnsaturated(currentState, newState, true);
        if (!actions.isEmpty()) {
            // Form-submit guard (INV-FORM-06): EARLY_STAGE consumes unvisited actions here, before
            // the epsilon-greedy rung is reached, so the submit exclusion applies to this roulette
            // too. Mounted with FORM_COMPLETION only.
            List<ModelAction> candidates = actions;
            ModelAction submit = submitExcluded(next);
            if (submit != null) {
                candidates = new ArrayList<>(actions.size());
                for (ModelAction a : actions) {
                    if (a != submit) {
                        candidates.add(a);
                    }
                }
            }
            // back-menu-pick-cap (INV-SEL-NAV-05): drop capped target-less BACK/MENU from the
            // EARLY_STAGE roulette candidates. This composes with the submit exclusion above
            // (BACK/MENU are never the submit candidate, so the two filters are independent).
            List<ModelAction> rouletteCandidates = dropCappedBackMenu(candidates, next.getActivity());
            RandomHelper.Draw<ModelAction> draw =
                    RandomHelper.randomPickWithPriorityRecorded(rouletteCandidates);
            action = draw.getValue();
            if (action != null) {
                Logger.iprintln("Find a greedy action in 0-step");
                // super.disableRestart();
                checkDisableRestart(action);
                recordDraw(draw.getIndex(), draw.getTotal());
                recordRouletteCounterfactual(rouletteCandidates, action, draw.getIndex(),
                        draw.getTotal());
                trace().backMenuCapped = candidates.size() - rouletteCandidates.size();
                recordBackMenuPick(action.getType(), next.getActivity());
                return chose(action, ModelAction.PickChannel.ROULETTE_EARLY, "early_roulette");
            }
        }
        for (ModelAction a : next.targetedActions()) {
            if (!ActionFilter.ENABLED_VALID_UNVISITED.include(a)) { // unvisited hence no transitions.
                continue;
            }
            State t = getGraph().getNameGlobalTarget(a);
            if (t != null && isGreedyState(next, t)) {
                Logger.iformat("Find a greedy state %s via a global action %s.", t, a);
                return chose(a, ModelAction.PickChannel.SATA_OTHER, "greedy_global");
            }
        }
        // 2). Greedy action in the neighbor hood.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(next, greedySubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = randomPickShortest(selectedPaths);
            Logger.iformat("Find a path (1/%d) to a greedy state %s.", selectedPaths.size(), path.getLastState());
            if (path.size() <= 1) {
                ModelAction firstAction = path.getFirstAction();
                checkDisableRestart(firstAction);
            }
            return chose(refillBuffer(path), ModelAction.PickChannel.SATA_OTHER, "greedy_path");
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The step carries the roulette's {@code dec.draw} and {@code dec.cfc} (action-selection
     * "Per-step counterfactual attribution"). The counterfactual's candidates are read after the
     * roulette: its filter validated every action, so each action's validity flag is the filter's
     * decision, read without resolving anything again.
     */
    @Override
    protected ModelAction nullActionPicked(State.RoulettePick pick, ModelAction resolved) {
        recordDraw(pick.getDrawIndex(), pick.getTotal());
        recordRouletteCounterfactual(candidatesOf(newState, ActionFilter.VALID, null),
                pick.getAction(), pick.getDrawIndex(), pick.getTotal());
        return chose(resolved, ModelAction.PickChannel.SATA_OTHER, "null");
    }

    protected void checkDisableRestart(ModelAction action) {
        if (model.getGraph().isActionUnvisitedByName(action)) {
            super.disableRestart();
        }
    }

    protected ModelAction findGreedyActionBackward(State prev, State next) {
        // 3). Back
        // back-menu-pick-cap (INV-SEL-NAV-05): under the default useActionDiffer=true this is the
        // DOMINANT unvisited-BACK channel, reached at phase :441 before the epsilon short-circuit
        // ever runs. Gate the direct pick on BACK-key eligibility; when capped, skip it and fall
        // through to the backtrack path (:4) rather than re-pick BACK on every sibling state.
        if (ActionFilter.ENABLED_VALID_UNVISITED.include(next.getBackAction())
                && eligibleForCappedPick(backMenuPicks,
                        backMenuPickKey(ActionType.MODEL_BACK, next.getActivity()),
                        exploration.backMenuPickCap())) {
            Logger.iprintln("Find a greedy (unvisited) back action.");
            recordBackMenuPick(ActionType.MODEL_BACK, next.getActivity());
            return chose(next.getBackAction(), ModelAction.PickChannel.SATA_OTHER, "early_backward");
        }
        // 4). Backtrack to parent.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(next, backtrackSubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = randomPickShortest(selectedPaths);
            Logger.iformat("Find a path (1/%d) to state %s for backtrack.", selectedPaths.size(), path.getLastState());
            if (path.size() <= 1) {
                checkDisableRestart(path.getFirstAction());
            }
            return chose(refillBuffer(path), ModelAction.PickChannel.SATA_OTHER, "backtrack");
        }
        return null;
    }

    protected void printStrategy() {
        Logger.format("Sata Strategy: buffer size (%d)", actionBufferSize());
        printCounters();
        newState.printActions();
    }

    @Override
    public void onActivityBlocked(ComponentName blockedActivity) {

    }

    @Override
    public boolean onVoidGUITree(int retryCounter) {
        return false;
    }

    @Override
    public void onBadState(int lastBadStateCount, int badStateCounter) {
        logEvent(SataEventType.BAD_STATE);
    }

}
