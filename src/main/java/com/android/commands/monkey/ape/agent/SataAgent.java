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

import static com.android.commands.monkey.ape.utils.Config.defaultEpsilon;
import static com.android.commands.monkey.ape.utils.Config.doBackToTrivialActivity;
import static com.android.commands.monkey.ape.utils.Config.fallbackToGraphTransition;
import static com.android.commands.monkey.ape.utils.Config.fillTransitionsByHistory;
import static com.android.commands.monkey.ape.utils.Config.graphStableRestartThreshold;
import static com.android.commands.monkey.ape.utils.Config.trivialActivityRankThreshold;
import static com.android.commands.monkey.ape.utils.Config.useActionDiffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.AndroidDevice.Activity;
import com.android.commands.monkey.ape.AndroidDevice.Stack;
import com.android.commands.monkey.ape.AndroidDevice.Task;
import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.BaseActionFilter;
import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.SubsequenceFilter;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.model.StateActionDiffer;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import android.content.ComponentName;

public class SataAgent extends StatefulAgent {


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

    static enum SataEventType {
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

    public SataAgent(MonkeySourceApe ape, Graph graph) {
        this(ape, graph, defaultEpsilon);
    }

    public SataAgent(MonkeySourceApe ape, Graph graph, double epsilon) {
        super(ape, graph);
        this.epsilon = epsilon;


        this.actionCounters = new int[SataEventType.values().length];
    }

    @Override
    public void startNewEpisode() {
        super.startNewEpisode();
    }

    protected boolean isEntryState(State state) {
        return this.getGraph().isEntryState(state);
    }

    protected void logActionSelected(Action action, SataEventType type) {
        Logger.iformat("Select action %s by strategy %s", action, type);
        // Decision-source attribution is set at the priority-consuming PICK SITES
        // (the roulettes and the MOP short-circuits inside EARLY_STAGE/EPSILON_GREEDY),
        // so those two branches own the source of their finalized action. Every other
        // branch selects for reasons other than priority, so its action is attributed
        // SATA here — a fresh write that also clears any stale source carried over from
        // a previous step. INV-SEL-04: this only changes which enum value is carried,
        // never the number of [APE-STEP] lines and never a boost field.
        if (action != null && action.isModelAction()
                && type != SataEventType.EARLY_STAGE && type != SataEventType.EPSILON_GREEDY) {
            ((ModelAction) action).setDecisionSource(ModelAction.DecisionSource.SATA);
        }
        logEvent(type);
    }

    /**
     * The mechanism holding the largest positive boost on this action, ties resolved by the
     * fixed precedence MOP &gt; WTG &gt; Menu &gt; Form &gt; Coverage; SATA when no boost is
     * positive. This is largest-contributing-boost attribution on a priority-consuming pick:
     * it reports which mechanism boosted the chosen action the most, NOT a counterfactual
     * claim that the boost changed the outcome (P4). It is applied only at the pick sites that
     * actually consume priority — the epsilon-greedy roulette ({@code randomlyPickAction}),
     * the EARLY_STAGE unvisited roulette ({@code randomPickWithPriority}), and the two MOP
     * short-circuits ({@code selectUnvisitedMopTarget}, {@code pickBestMopTarget}). Back-/Menu-
     * unvisited, least-visited, and graph-navigation picks are attributed SATA at their own
     * return sites even though they live inside EARLY_STAGE/EPSILON_GREEDY.
     */
    static ModelAction.DecisionSource attributeByLargestBoost(ModelAction action) {
        int mop = action.getMopBoost();
        int wtg = action.getWtgBoost();
        int menu = action.getMenuBoost();
        int form = action.getFormBoost();
        int coverage = action.getCoverageBoost();
        int max = Math.max(Math.max(Math.max(mop, wtg), Math.max(menu, form)), coverage);
        if (max <= 0) {
            return ModelAction.DecisionSource.SATA;
        }
        // Tie precedence MOP > WTG > Menu > Form > Coverage.
        if (mop == max) {
            return ModelAction.DecisionSource.MOP;
        }
        if (wtg == max) {
            return ModelAction.DecisionSource.WTG;
        }
        if (menu == max) {
            return ModelAction.DecisionSource.Menu;
        }
        if (form == max) {
            return ModelAction.DecisionSource.Form;
        }
        return ModelAction.DecisionSource.Coverage;
    }

    protected void logEvent(SataEventType type) {
        int ordinal = type.ordinal();
        actionCounters[ordinal] = actionCounters[ordinal] + 1;
    }

    public void tearDown() {
        super.tearDown();
        printCounters();
        // Per-state UI-coverage dump (read-only). mopReach is computed here because the
        // tracker holds no MopData reference (D4): a state's Activity gates a MOP target
        // iff _mopData != null && activityHasMop(activity).
        MopData mopData = getMopData();
        getCoverageTracker().dump(
                state -> mopData != null && mopData.activityHasMop(state.getActivity()));
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
        // gh9: budget exhaustion check — before LLM hooks
        // Soft constraint: when exhausted, try trivial activity navigation.
        // If unavailable, fall through to normal SATA chain (no BACK, no RESTART).
        // EVENT_RESTART caused restart loops; MODEL_BACK caused stuck loops.
        // Fallthrough is the correct behavior — lets SATA chain handle exploration.
        if (Config.activityBudgetEnabled && getBudgetTracker().isBudgetExhausted(newState.getActivity())) {
            ModelAction trivial = selectNewActionForTrivialActivity();
            if (trivial != null) {
                Logger.iformat("[APE-RV] Budget exhausted for %s, navigating to trivial activity", newState.getActivity());
                trivial.setDecisionSource(ModelAction.DecisionSource.Budget);
                return trivial;
            }
            // Fall through to normal SATA chain — budget is advisory, not blocking
        }

        // LLM new-state hook
        if (actionBufferSize() == 0 && newState.getActions().size() > 2
                && _llmRouter != null && _llmRouter.shouldRouteNewState(_isNewState)) {
            ModelAction result = _llmRouter.selectAction(newGUITree, newState,
                    newState.getActions(), getMopData(), _actionHistory, "new-state");
            if (result != null) {
                result.setDecisionSource(ModelAction.DecisionSource.LLM);
                return result;
            }
        }
        // LLM stagnation hook (single-shot at midpoint). Keeps its own fixed
        // graphStableRestartThreshold / 2 point on purpose: mop-census-launcher moved the LAUNCHER
        // off graphStableCounter entirely (it now fires on a dedicated cadence counter), so the two
        // mechanisms no longer share any firing point. cmpft5 runs the LLM OFF, so there is no
        // interaction; an enabled LLM hook here still runs before the launcher block below.
        if (actionBufferSize() == 0 && newState.getActions().size() > 2
                && graphStableCounter == graphStableRestartThreshold / 2
                && _llmRouter != null && _llmRouter.shouldRouteStagnation(graphStableCounter)) {
            ModelAction result = _llmRouter.selectAction(newGUITree, newState,
                    newState.getActions(), getMopData(), _actionHistory, "stagnation");
            if (result != null) {
                result.setDecisionSource(ModelAction.DecisionSource.LLM);
                graphStableCounter = 0;
                return result;
            }
        }
        // LLM random hook (probabilistic, fires with Config.llmPercentage probability)
        if (actionBufferSize() == 0 && newState.getActions().size() > 2
                && _llmRouter != null && _llmRouter.shouldRouteRandom()) {
            ModelAction result = _llmRouter.selectAction(newGUITree, newState,
                    newState.getActions(), getMopData(), _actionHistory, "random");
            if (result != null) {
                result.setDecisionSource(ModelAction.DecisionSource.LLM);
                return result;
            }
        }
        // mop-census-launcher (Lever B): cadence-based MOP-activity launcher. Fires every
        // Config.activityTriggerStagnationStep passes through this block (a dedicated per-pass
        // counter, decoupled from graphStableCounter), launching the next unvisited activity from
        // the arm's MOP census. A first-class EVENT_TRIGGER_ACTIVITY step — model-visible,
        // attributed Component in resolveNewAction, no graph edge (INV-CT-05/06/07). Evaluated after
        // the LLM hooks, so an enabled LLM hook at a shared step takes precedence.
        _stepsSinceLauncherFiring++;
        if (shouldFireLauncher(Config.activityTriggerEnabled, getMopData() != null,
                _stepsSinceLauncherFiring, Config.activityTriggerStagnationStep,
                _activityTriggerLaunchCount, Config.activityTriggerMaxPerRun)) {
            // Reset at the firing point regardless of candidate outcome (keeps firing periodic and
            // avoids per-step rescans once the census is exhausted). The launch BUDGET, in contrast,
            // is consumed only on an actual launch below (INV-CT-12).
            _stepsSinceLauncherFiring = 0;
            Set<String> visited = new HashSet<>();
            for (ActivityNode an : getGraph().getActivityNodes()) {
                visited.add(an.activity);
            }
            ComponentInfo candidate = selectTriggerCandidate(getMopData().getActivities(),
                    visited, getMopData().getMainActivity(), _triggerRoundRobinIndex,
                    getMopData().getMopActivities());
            _triggerRoundRobinIndex++;
            if (candidate != null) {
                _activityTriggerLaunchCount++; // budget consumed only on an actual launch (INV-CT-12)
                Logger.iformat("[APE-RV] Triggering activity: %s", candidate.className);
                // Package captured from MopData (never from the class name — INV-CT-04).
                return new ActivityTriggerAction(getMopData().getPackageName(),
                        candidate.className, buildDeepLinkUri(candidate));
            }
        }
        // gh11: component triggering (probabilistic, fires with Config.componentPercentage)
        if (Config.componentPercentage > 0 && getMopData() != null && getMopData().hasComponents()
                && getRandom().nextDouble() < Config.componentPercentage) {
            triggerMopComponent();
            // No return — trigger is a side-effect, continue with normal SATA selection
        }
        Action resolved = null;
        resolved = selectNewActionFromBuffer();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.USE_BUFFER);
            return resolved;
        }
        resolved = selectNewActionBackToActivity();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.TRIVIAL_ACTIVITY);
            return resolved;
        }
        resolved = selectNewActionEarlyStageForward();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.EARLY_STAGE);
            return resolved;
        }
        resolved = selectNewActionForTrivialActivity();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.TRIVIAL_ACTIVITY);
            return resolved;
        }
        resolved = selectNewActionEarlyStageBackward();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.EARLY_STAGE);
            return resolved;
        }
        resolved = selectNewActionEpsilonGreedyRandomly();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.EPSILON_GREEDY);
            return resolved;
        }
        resolved = handleNullAction();
        if (resolved != null) {
            logActionSelected(resolved, SataEventType.NULL);
            return resolved;
        }
        throw new BadStateException("No available action on the current state");
    }

    protected ModelAction selectNewActionEarlyStageBackward() {
        return selectNewActionEarlyStageBackwardGreedy();
    }

    public void onBufferLoss(State actual, State expected) {
        logEvent(SataEventType.BUFFER_LOSS);
    }

    protected ModelAction selectNewActionEpsilonGreedyRandomly() {
        // back-menu-pick-cap: bound discretionary BACK/MENU re-picks per (activity, type) across
        // NamingFactory-minted sibling states (INV-SEL-NAV-01). Only the discretionary channels
        // here are capped — the short-circuits, the least-visited pick, and the roulette. The
        // navigation-essential BACK sites (selectNewActionBackToActivity, backToTrivialActivity,
        // checkBackTrack, handleNullAction) never consult backMenuPicks (INV-SEL-NAV-03).
        String activity = newState.getActivity();
        int cap = Config.backMenuPickCap;
        String backKey = backMenuPickKey(ActionType.MODEL_BACK, activity);
        String menuKey = backMenuPickKey(ActionType.MODEL_MENU, activity);
        ModelAction back = newState.getBackAction();
        if (back.isValid()) {
            if (back.isUnvisited() && eligibleForMopPick(backMenuPicks, backKey, cap)) {
                Logger.iprintln("Select Back because Back action is unvisited.");
                // Chosen because Back is unvisited, not because of any boost — SATA.
                back.setDecisionSource(ModelAction.DecisionSource.SATA);
                recordBackMenuPick(ActionType.MODEL_BACK, activity);
                return back;
            }
        }
        ModelAction menu = newState.getMenuAction();
        if (menu.isValid()) {
            if (menu.isUnvisited() && eligibleForMopPick(backMenuPicks, menuKey, cap)) {
                Logger.iprintln("Select Menu because Menu action is unvisited.");
                // Chosen because Menu is unvisited, not because of its menuBoost — SATA.
                menu.setDecisionSource(ModelAction.DecisionSource.SATA);
                recordBackMenuPick(ActionType.MODEL_MENU, activity);
                return menu;
            }
        }
        // Form-submit guard (extended INV-FORM-06): while the form has unfilled EditTexts, exclude
        // the submit candidate from BOTH the MOP short-circuit and the least-visited pick so it is
        // not clicked on an empty form. Computed once per step; the convergent predicate
        // (INV-FORM-07) lifts it once every field is filled.
        ModelAction submitExcluded = null;
        if (FormCompletion.hasUnfilledEditText(newState, timestamp)) {
            submitExcluded = FormCompletion.selectSubmitCandidate(newState, timestamp);
        }
        // MOP-target greedy short-circuit: a valid, enabled, unvisited action carrying a
        // discriminative MOP boost is selected before roulette, mirroring the Back/Menu-
        // unvisited short-circuits above. Bounded to unvisited mopBoost>0 so it fires once
        // per MOP target and never overrides least-visited for visited actions
        // (INV-SEL-MOP-01/02). The caller attributes it via logActionSelected(.,EPSILON_GREEDY).
        ModelAction mopTarget = selectUnvisitedMopTarget(submitExcluded);
        if (mopTarget != null) {
            Logger.iformat("Select MOP target %s because it is unvisited (mopBoost=%d).",
                    mopTarget, mopTarget.getMopBoost());
            // Boost-based deterministic pick: attribute by largest contributing boost.
            mopTarget.setDecisionSource(attributeByLargestBoost(mopTarget));
            return mopTarget;
        }
        // back-menu-pick-cap: capped BACK/MENU must also fall out of the discretionary least-visited
        // and roulette channels — else a refinement-minted sibling (visitedCount == 0) would win
        // least-visited outright, merely relabeling the re-pick channel (Decision 3). One wrapped
        // filter, stable across the roulette's count/pick passes (INV-SEL-NAV-04).
        ActionFilter cappedFilter =
                cappedBackMenuFilter(ActionFilter.ENABLED_VALID, backMenuPicks, activity, cap);
        if (egreedy()) { // TODO: this is different from Sarsa.
            Logger.iformat("Try to select the least visited action.");
            // Least-visited pick: priority (and any boost) is only a tie-break, not the
            // reason for selection — SATA.
            ModelAction leastVisited = newState.greedyPickLeastVisited(cappedFilter, submitExcluded);
            if (leastVisited != null) {
                leastVisited.setDecisionSource(ModelAction.DecisionSource.SATA);
                recordBackMenuPick(leastVisited.getType(), activity);
            }
            return leastVisited;
        }
        Logger.iformat("Try to randomly select a visited action.");
        // Priority roulette: attribute by largest contributing boost.
        ModelAction roulettePick = newState.randomlyPickAction(getRandom(), cappedFilter);
        if (roulettePick != null) {
            roulettePick.setDecisionSource(attributeByLargestBoost(roulettePick));
            recordBackMenuPick(roulettePick.getType(), activity);
        }
        return roulettePick;
    }

    /**
     * The enabled, valid, UNVISITED action with the largest discriminative MOP boost; null
     * when none exists (the short-circuit is then a no-op — INV-SEL-MOP-02). The unvisited/
     * enabled/valid gating is the {@code ENABLED_VALID_UNVISITED} filter; the ranking is
     * {@link #pickBestMopTarget}.
     */
    protected ModelAction selectUnvisitedMopTarget(ModelAction excluded) {
        // INV-FORM-06: while the form has unfilled EditTexts, the submit candidate (typically the
        // mopBoost>0 action — INV-FORM-05) must NOT be short-circuited; fields are filled first.
        // {@code excluded} is computed once by the caller and shared with the least-visited guard
        // so the submit is not clicked on an empty form. The revisit cap (mop-target-revisit-cap)
        // filters candidates whose (widget,type,activity) key has reached ape.mopTargetPickCap.
        return pickCappedMopTarget(newState.collectActions(ActionFilter.ENABLED_VALID_UNVISITED),
                excluded, newState.getActivity());
    }

    /** As {@link #pickBestMopTarget(Iterable, ModelAction)} with no exclusion. */
    static ModelAction pickBestMopTarget(Iterable<ModelAction> candidates) {
        return pickBestMopTarget(candidates, null);
    }

    /**
     * The action with the largest discriminative MOP boost ({@code mopBoost > 0}) among the
     * candidates, ties broken by highest priority; null when none carries a positive boost.
     * Pure — the caller supplies the already-filtered (unvisited/enabled/valid) candidates.
     * {@code excluded}, when non-null, is skipped (the INV-FORM-06 form-submit guard).
     */
    static ModelAction pickBestMopTarget(Iterable<ModelAction> candidates, ModelAction excluded) {
        ModelAction best = null;
        for (ModelAction action : candidates) {
            if (action == excluded) {
                continue;
            }
            if (action.getMopBoost() <= 0) {
                continue;
            }
            if (best == null
                    || action.getMopBoost() > best.getMopBoost()
                    || (action.getMopBoost() == best.getMopBoost()
                            && action.getPriority() > best.getPriority())) {
                best = action;
            }
        }
        return best;
    }

    // mop-target-revisit-cap: per-run count of deterministic MOP picks, keyed by
    // (widget XPath, action type, activity). NamingFactory refinement re-arms the "unvisited"
    // MOP short-circuit for the same physical widget in every near-duplicate state; this cap
    // bounds the deterministic override across those states (INV-SEL-MOP-04). The boost itself
    // is untouched, so the action stays in the priority roulette.
    private final Map<String, Integer> mopTargetPicks = new HashMap<>();

    // back-menu-pick-cap: per-run count of discretionary MODEL_BACK/MODEL_MENU picks, keyed by
    // (activity, type). NamingFactory refinement re-arms the "unvisited" BACK/MENU short-circuits
    // for every sibling state; this cap bounds discretionary re-picks across them (INV-SEL-NAV-01).
    // Navigation-essential BACK sites do not consult this map. The action objects are never
    // mutated — only the roulette/short-circuit candidate sets are shaped.
    private final Map<String, Integer> backMenuPicks = new HashMap<>();

    // activity-frontier (Lever B): round-robin cursor over the manifest activity list, persisted
    // across stagnation episodes so repeated launches walk the frontier (INV-CT-06).
    private int _triggerRoundRobinIndex = 0;

    // mop-census-launcher: count of EVENT_TRIGGER_ACTIVITY actions actually returned this run.
    // Feeds the shouldFireLauncher per-run cap (Config.activityTriggerMaxPerRun). Incremented ONLY
    // where the ActivityTriggerAction is returned (candidate != null branch) — a firing whose
    // candidate scan comes up empty consumes no budget (INV-CT-12).
    private int _activityTriggerLaunchCount = 0;

    // mop-census-launcher: dedicated per-pass launcher step counter, incremented once each time
    // selectNewActionNonnull reaches the launcher block and reset to 0 at every firing point
    // (regardless of candidate outcome). Decoupled from graphStableCounter, so firing is periodic in
    // selection steps and does not depend on graph growth (INV-CT-05).
    private int _stepsSinceLauncherFiring = 0;

    /**
     * mop-census-launcher cadence gate seam (pure, INV-CT-05/08/12). The launcher fires only when it
     * is enabled, MopData is loaded, the dedicated per-pass counter is exactly at the configured
     * {@code cadence} (equality, not {@code >=}: the caller resets the counter to 0 at each firing
     * point, so it never overshoots while firing is active), and the per-run launch budget is not
     * exhausted ({@code maxPerRun == 0} means unlimited, else fire only while
     * {@code launchesSoFar < maxPerRun}). Decoupled from {@code graphStableCounter} — periodic in
     * selection steps, independent of graph growth. Off entirely when disabled or no MopData.
     */
    static boolean shouldFireLauncher(boolean enabled, boolean hasMopData,
            int stepsSinceFiring, int cadence, int launchesSoFar, int maxPerRun) {
        return enabled && hasMopData && stepsSinceFiring == cadence
                && (maxPerRun == 0 || launchesSoFar < maxPerRun);
    }

    /**
     * mop-census-launcher candidate seam (pure, INV-CT-06/10). Round-robin walk over
     * {@code activities} from {@code rrIndex} (wrapping once), returning the first that satisfies, at
     * call time, ALL of: a member of the arm's MOP census ({@code className} in {@code mopActivities}),
     * not framework/tooling-namespaced, no permission gate ({@code permission == null}), not the main
     * activity (by {@code isMain} flag or by name equal to {@code mainActivity}), and currently
     * unvisited ({@code className} not in {@code visitedActivities}). Exported status is NOT consulted
     * — the dispatch path ({@code IActivityManager.startActivity} from uid 2000) launches non-exported
     * activities. There is no non-census fallback: a null/empty census, or no eligible census member,
     * yields null. The census is the reachability-augmented {@code MopData.activityHasMop} truth
     * (INV-MOP-27), NOT the component-level {@code ComponentInfo.reachesTarget} field, which
     * false-negatives lambda-triggered activities. The caller owns the index increment.
     */
    static ComponentInfo selectTriggerCandidate(List<? extends ComponentInfo> activities,
            Set<String> visitedActivities, String mainActivity, int rrIndex, Set<String> mopActivities) {
        if (activities == null || activities.isEmpty() || mopActivities == null) {
            return null;
        }
        int n = activities.size();
        for (int i = 0; i < n; i++) {
            int idx = (((rrIndex + i) % n) + n) % n; // safe modulo for any rrIndex
            ComponentInfo c = activities.get(idx);
            if (!mopActivities.contains(c.className)) {
                continue; // census-only: never launch outside the arm's MOP census (no fallback)
            }
            if (isFrameworkActivity(c.className)) {
                continue;
            }
            if (c.permission == null && !c.isMain
                    && !c.className.equals(mainActivity)
                    && !visitedActivities.contains(c.className)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Framework/tooling class-name prefixes that are never genuine app screens (Compose preview,
     * abstract framework activities, leakcanary, test scaffolds). Debug-build manifest merging pulls
     * these into the app package and over-approximated reachability can pull them into the MOP
     * census, so they otherwise pass the eligibility conjunction; the class namespace is the
     * discriminator (app code is never authored here). A fixed correctness filter, not a tunable —
     * no Config flag (INV-CT-10). cmpft4: 76% of 114 launches hit these.
     */
    private static final String[] FRAMEWORK_ACTIVITY_PREFIXES = {
            "android.", "androidx.", "com.google.android.", "kotlin.", "kotlinx.",
            "junit.", "org.junit.", "leakcanary.",
    };

    /**
     * INV-MOP-33: the Nav MOP-tiebreak decision line, or {@code null} when density did not decide
     * (all-equal random fallback). Emitted only from the path-selection site; never alters which
     * path is chosen. Pure.
     */
    static String navMopTiebreakLog(int winningDensity, int pathCount, boolean densityDecided) {
        if (!densityDecided) {
            return null;
        }
        return String.format("[APE-RV] Nav MOP tiebreak: density=%d paths=%d",
                winningDensity, pathCount);
    }

    /** Whether {@code className} starts with any {@link #FRAMEWORK_ACTIVITY_PREFIXES} entry
     * (prefix match, never substring). */
    private static boolean isFrameworkActivity(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : FRAMEWORK_ACTIVITY_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }


    /**
     * activity-frontier deep-link seam (pure). Returns {@code scheme + "://" + host + path} built
     * from the first intent-filter that declares {@code ACTION_VIEW} and a non-empty scheme list
     * (host/path default to empty when absent); null when no such filter exists (explicit-component
     * fallback). Best-effort URI from the parsed manifest parts (INV-CT-07 dispatch precondition).
     */
    static String buildDeepLinkUri(ComponentInfo activity) {
        if (activity == null) {
            return null;
        }
        for (ComponentInfo.IntentFilter f : activity.intentFilters) {
            if (!f.actions.contains("android.intent.action.VIEW")) {
                continue;
            }
            if (f.data.schemes.isEmpty()) {
                continue;
            }
            String scheme = f.data.schemes.get(0);
            String host = f.data.hosts.isEmpty() ? "" : f.data.hosts.get(0);
            String path = f.data.paths.isEmpty() ? "" : f.data.paths.get(0);
            return scheme + "://" + host + path;
        }
        return null;
    }

    /**
     * Physical-widget identity for the pick cap: {@code target.toXPath() + "|" + actionType + "|" +
     * activity}, matching the {@code UICoverageTracker.widgetId} convention (xpath|type) scoped by
     * activity. Null when the action or its target/XPath is null — such actions are uncountable and
     * stay pickable (defensive; production {@code mopBoost>0} actions always carry a target). Pure.
     */
    static String mopPickKey(ModelAction action, String activity) {
        if (action == null) {
            return null;
        }
        Name target = action.getTarget();
        if (target == null) {
            return null;
        }
        String xpath = target.toXPath();
        if (xpath == null) {
            return null;
        }
        return xpath + "|" + action.getType().name() + "|" + activity;
    }

    /**
     * Whether a candidate may still be selected by the deterministic MOP sites. True when the cap
     * is disabled ({@code cap <= 0}), when the key is uncountable ({@code key == null}), or when the
     * key's recorded count is below the cap. Pure read — no mutation, no logging.
     */
    static boolean eligibleForMopPick(Map<String, Integer> picks, String key, int cap) {
        if (cap <= 0 || key == null) {
            return true;
        }
        return picks.getOrDefault(key, 0) < cap;
    }

    /**
     * Records one deterministic MOP pick for {@code key}. Returns true iff this increment brought
     * the count to exactly {@code cap} — the single moment to log the cap event (subsequent picks
     * are filtered by {@link #eligibleForMopPick}, so the key never reaches the cap twice). No-op
     * returning false when the cap is disabled or the key is uncountable (INV-SEL-MOP-05: no
     * counter update). Mutates only {@code picks}.
     */
    static boolean recordMopPick(Map<String, Integer> picks, String key, int cap) {
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
                eligibleForMopPick(picks, backMenuPickKey(ActionType.MODEL_BACK, activity), cap);
        final boolean menuEligible =
                eligibleForMopPick(picks, backMenuPickKey(ActionType.MODEL_MENU, activity), cap);
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
        int cap = Config.backMenuPickCap;
        String key = backMenuPickKey(type, activity);
        if (recordMopPick(backMenuPicks, key, cap)) {
            Logger.iformat("[APE-RV] BACK/MENU capped: activity=%s type=%s picks=%d",
                    activity, type.name(), cap);
        }
    }

    /**
     * back-menu-pick-cap (menu-boost gate, INV-SEL-NAV): the gh13 OPTIONSMENU gateway boost is
     * suppressed once this activity's MODEL_MENU key reaches the cap, so a capped MENU stops
     * re-dominating the priority roulette. Below the cap the gh13 semantics are identical.
     */
    @Override
    protected boolean menuPickEligible(String activity) {
        return eligibleForMopPick(backMenuPicks,
                backMenuPickKey(ActionType.MODEL_MENU, activity), Config.backMenuPickCap);
    }

    /**
     * Returns {@code candidates} with any capped target-less MODEL_BACK/MODEL_MENU removed (used by
     * the EARLY_STAGE forward roulette). Returns {@code candidates} unchanged when the cap is
     * disabled. Read-only — never mutates the actions or {@code backMenuPicks}.
     */
    private List<ModelAction> dropCappedBackMenu(List<ModelAction> candidates, String activity) {
        int cap = Config.backMenuPickCap;
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

    /**
     * Filters {@code candidates} through the revisit cap, invokes the pure {@link #pickBestMopTarget}
     * on the eligible subset, and records the selected key (logging once when it reaches the cap).
     * With the cap disabled this is exactly {@code pickBestMopTarget(candidates, excluded)} with no
     * counting (INV-SEL-MOP-05).
     */
    private ModelAction pickCappedMopTarget(Iterable<ModelAction> candidates, ModelAction excluded,
            String activity) {
        int cap = Config.mopTargetPickCap;
        Iterable<ModelAction> eligible = candidates;
        if (cap > 0) {
            List<ModelAction> filtered = new ArrayList<>();
            for (ModelAction a : candidates) {
                if (eligibleForMopPick(mopTargetPicks, mopPickKey(a, activity), cap)) {
                    filtered.add(a);
                }
            }
            eligible = filtered;
        }
        ModelAction picked = pickBestMopTarget(eligible, excluded);
        if (picked != null) {
            String key = mopPickKey(picked, activity);
            if (recordMopPick(mopTargetPicks, key, cap)) {
                Logger.iformat("[APE-RV] MOP target capped: activity=%s widget=%s picks=%d",
                        activity, picked.getTarget().toXPath(), cap);
            }
        }
        return picked;
    }

    public void onRefillBuffer(Subsequence seq) {
        logEvent(SataEventType.FILL_BUFFER);
    }

    protected ModelAction fillTransition(State[] states) {
        return fillTransition(states, fillTransitionsByHistory, fallbackToGraphTransition);
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
        if (useActionDiffer) {
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
                    if (target.getActivity().equals(lastTarget.getActivity())) {
                        if (target.getVisitedCount() < lastTarget.getVisitedCount()) {
                            lastIndex = i;
                            lastTarget = target; // prefer colder state
                        } else if (target.getVisitedCount() == lastTarget.getVisitedCount()
                                && getMopData() != null
                                && MopScorer.stateMopDensity(target, getMopData(), getTimestamp()) > MopScorer.stateMopDensity(lastTarget, getMopData(), getTimestamp())) {
                            lastIndex = i;
                            lastTarget = target; // MOP density tiebreaker
                        }
                    } else { // prefer colder activity
                        ActivityNode an1 = getGraph().getActivityNode(target.getActivity());
                        ActivityNode an2 = getGraph().getActivityNode(lastTarget.getActivity());
                        if (an1.getVisitedCount() < an2.getVisitedCount()) {
                            lastIndex = i;
                            lastTarget = target;
                        } else if (an1.getVisitedCount() == an2.getVisitedCount()
                                && getMopData() != null
                                && MopScorer.stateMopDensity(target, getMopData(), getTimestamp()) > MopScorer.stateMopDensity(lastTarget, getMopData(), getTimestamp())) {
                            lastIndex = i;
                            lastTarget = target; // MOP density tiebreaker
                        }
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
        ModelAction action = refillBuffer(path);
        if (action != null) {
            // ABA graph-navigation pick: not priority-driven — SATA.
            action.setDecisionSource(ModelAction.DecisionSource.SATA);
            Logger.iformat("Move from A (%s) to B (%s) for ABA within %d steps that start from action %s",
                    A, B, path.size(), action);
        }
        return action;
    }

    protected ModelAction selectNewActionBackToActivity() {
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
                    return newState.getBackAction();
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
        double v = ape.getRandom().nextDouble();
        Logger.iformat("EGreedy value=%f, epsilon=%f.", v, effectiveEpsilon);
        if (v < effectiveEpsilon) {
            return false;
        }
        return true;
    }

    protected double computeDynamicEpsilon() {
        if (!Config.dynamicEpsilon) {
            return epsilon;
        }
        UICoverageTracker tracker = getCoverageTracker();
        if (tracker == null) {
            return epsilon;
        }
        float coverageGap = tracker.getCoverageGap(newState);
        return Config.minEpsilon + (Config.maxEpsilon - Config.minEpsilon) * coverageGap;
    }

    protected ModelAction selectNewActionEarlyStageForward() {
        // A simple Depth First
        ModelAction action = selectNewActionEarlyStageForABA();
        if (action != null) {
            return action;
        }
        return selectNewActionEarlyStageForwardGreedy();
    }

    protected Set<ActivityNode> collectTrivialActivities() {
        ActivityNode[] activities = getGraph().getActivityNodes();
        if (activities.length <= trivialActivityRankThreshold) {
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

    protected ModelAction selectNewActionForTrivialActivity() {
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
                Subsequence path;
                if (getMopData() != null && selectedPaths.size() > 1) {
                    // Prefer path to state with highest MOP density
                    Subsequence best = selectedPaths.get(0);
                    int bestDensity = MopScorer.stateMopDensity(best.getLastState(), getMopData(), getTimestamp());
                    for (int pi = 1; pi < selectedPaths.size(); pi++) {
                        Subsequence candidate = selectedPaths.get(pi);
                        int density = MopScorer.stateMopDensity(candidate.getLastState(), getMopData(), getTimestamp());
                        if (density > bestDensity) {
                            best = candidate;
                            bestDensity = density;
                        }
                    }
                    // If all densities equal, fall back to random
                    int finalDensity = bestDensity;
                    boolean allEqual = selectedPaths.stream()
                            .allMatch(p -> MopScorer.stateMopDensity(p.getLastState(), getMopData(), getTimestamp()) == finalDensity);
                    path = allEqual ? RandomHelper.randomPick(selectedPaths) : best;
                    // INV-MOP-33: log only when density actually decided the path (not the
                    // all-equal random fallback). Never alters the selection above.
                    String tiebreakLog = navMopTiebreakLog(bestDensity, selectedPaths.size(), !allEqual);
                    if (tiebreakLog != null) {
                        Logger.iprintln(tiebreakLog);
                    }
                } else {
                    path = RandomHelper.randomPick(selectedPaths);
                }
                Logger.iformat("Find a path (1/%d) to a trivial activity %s.", selectedPaths.size(),
                        getGraph().getActivityNode(path.getLastState().getActivity()));
                return refillBuffer(path);
            }
        }
        if (doBackToTrivialActivity) {
            return backToTrivialActivity(trivialActivities);
        }
        return null;
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
            // Form-submit guard (extended INV-FORM-06): EARLY_STAGE consumes unvisited actions HERE,
            // before the epsilon-greedy short-circuit (selectNewActionEpsilonGreedyRandomly) is ever
            // reached, so the submit exclusion must also apply to this roulette. Drop the submit
            // candidate while the form has unfilled EditTexts; computed once, reused for the MOP
            // probe and the pick below.
            List<ModelAction> candidates = actions;
            if (FormCompletion.hasUnfilledEditText(next, timestamp)) {
                ModelAction submit = FormCompletion.selectSubmitCandidate(next, timestamp);
                if (submit != null) {
                    candidates = new ArrayList<>(actions.size());
                    for (ModelAction a : actions) {
                        if (a != submit) {
                            candidates.add(a);
                        }
                    }
                }
            }
            // MOP-target short-circuit (INV-SEL-MOP-03): take the unvisited action with the largest
            // discriminative MOP boost deterministically, before the probabilistic roulette dilutes
            // it — exactly where EARLY_STAGE consumes unvisited actions (the epsilon-greedy probe is
            // shadowed here). Null (no boosted candidate) is a no-op; the roulette then runs
            // unchanged. Chain order intact. The revisit cap (mop-target-revisit-cap) filters
            // candidates whose (widget,type,activity) key has reached ape.mopTargetPickCap.
            ModelAction mopTarget = pickCappedMopTarget(candidates, null, next.getActivity());
            if (mopTarget != null) {
                Logger.iformat("Find a greedy MOP target %s in 0-step (mopBoost=%d).",
                        mopTarget, mopTarget.getMopBoost());
                checkDisableRestart(mopTarget);
                // Boost-based deterministic pick: attribute by largest contributing boost.
                mopTarget.setDecisionSource(attributeByLargestBoost(mopTarget));
                return mopTarget;
            }
            // back-menu-pick-cap (INV-SEL-NAV-05): drop capped target-less BACK/MENU from the EARLY_STAGE
            // roulette candidates. This composes with the INV-FORM-06 submit exclusion above (BACK/MENU
            // are never the submit candidate, so the two filters are independent). The MOP probe above
            // is untouched — it ignores target-less BACK/MENU anyway (mopBoost==0).
            List<ModelAction> rouletteCandidates = dropCappedBackMenu(candidates, next.getActivity());
            action = RandomHelper.randomPickWithPriority(rouletteCandidates);
            if (action != null) {
                Logger.iprintln("Find a greedy action in 0-step");
                // super.disableRestart();
                checkDisableRestart(action);
                // Priority roulette over EARLY_STAGE unvisited candidates: attribute by
                // largest contributing boost.
                action.setDecisionSource(attributeByLargestBoost(action));
                recordBackMenuPick(action.getType(), next.getActivity());
                return action;
            }
        }
        for (ModelAction a : next.targetedActions()) {
            if (!ActionFilter.ENABLED_VALID_UNVISITED.include(a)) { // unvisited hence no transitions.
                continue;
            }
            State t = getGraph().getNameGlobalTarget(a);
            if (t != null && isGreedyState(next, t)) {
                Logger.iformat("Find a greedy state %s via a global action %s.", t, a);
                // Graph-navigation pick (greedy state via a global action): not priority-
                // driven — SATA.
                a.setDecisionSource(ModelAction.DecisionSource.SATA);
                return a;
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
            // Shortest-path navigation pick: not priority-driven — SATA.
            ModelAction navAction = refillBuffer(path);
            if (navAction != null) {
                navAction.setDecisionSource(ModelAction.DecisionSource.SATA);
            }
            return navAction;
        }
        return null;
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
                && eligibleForMopPick(backMenuPicks,
                        backMenuPickKey(ActionType.MODEL_BACK, next.getActivity()),
                        Config.backMenuPickCap)) {
            Logger.iprintln("Find a greedy (unvisited) back action.");
            // Chosen because Back is unvisited, not because of any boost — SATA.
            ModelAction backAction = next.getBackAction();
            backAction.setDecisionSource(ModelAction.DecisionSource.SATA);
            recordBackMenuPick(ActionType.MODEL_BACK, next.getActivity());
            return backAction;
        }
        // 4). Backtrack to parent.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(next, backtrackSubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = randomPickShortest(selectedPaths);
            Logger.iformat("Find a path (1/%d) to state %s for backtrack.", selectedPaths.size(), path.getLastState());
            if (path.size() <= 1) {
                checkDisableRestart(path.getFirstAction());
            }
            // Backtrack navigation pick: not priority-driven — SATA.
            ModelAction navAction = refillBuffer(path);
            if (navAction != null) {
                navAction.setDecisionSource(ModelAction.DecisionSource.SATA);
            }
            return navAction;
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
