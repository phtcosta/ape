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


import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;

import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.BaseActionFilter;
import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionCounters;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.GraphListener;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.Model.ActionRecord;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Naming;
import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.StepContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringParams;
import com.android.commands.monkey.ape.agent.scoring.ScoringPipeline;
import com.android.commands.monkey.ape.tree.GUITreeBuilder;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.tree.GUITreeWidgetDiffer;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.StopTestingException;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.SystemBroadcastCatalog;
import com.android.commands.monkey.ape.utils.UICoverageTracker;
import com.android.commands.monkey.ape.utils.Utils;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

public abstract class StatefulAgent extends ApeAgent implements GraphListener, StepContext {

    private static final boolean debug = false;


    protected State lastState;
    protected GUITree lastGUITree;
    protected ModelAction lastAction;
    protected GUITreeAction lastGUITreeAction;

    protected State currentState;
    protected GUITree currentGUITree;
    protected ModelAction currentAction;
    protected GUITreeAction currentGUITreeAction;

    // Decision buffer: the action of the last model-action selection. An action selected at step N
    // produces its transition only during step N+1's updateGraph(), so the decision must be held
    // across the boundary for its outcome to be attributable to it. Two consumers read it at that
    // point: the step record's closure and the dead-pair ban's outcome feedback. The step itself is
    // no longer buffered with it — the record carries its own step number and closes on the object
    // identity below, so a second copy of the number would only be a chance to disagree.
    // lastDecisionAction == null means "no decision buffered".
    private ModelAction lastDecisionAction = null;

    protected State newState;
    protected GUITree newGUITree;
    protected ModelAction newAction;
    protected GUITreeAction newGUITreeAction;

    protected StateTransition currentStateTransition;

    protected Model model;

    private ActionCounters actionCounters = new ActionCounters();

    private LinkedList<StateTransition> actionBuffer = new LinkedList<StateTransition>();

    protected int graphStableCounter;
    protected int stateStableCounter;
    protected int activityStableCounter;
    private boolean appActivityJustStarted;


    protected GUITreeWidgetDiffer widgetDiffer = new GUITreeWidgetDiffer();

    private final MopData _mopData;
    private final UICoverageTracker _coverageTracker;
    private final ActivityBudgetTracker _budgetTracker;

    // rv-scoring-pipeline: the RV scoring path, extracted from adjustActionsByGUITree() into an
    // ordered set of passes assembled once from the plan. The context is the seam through which the
    // passes read this agent's collaborators (live), so a pass carries no run state (INV-ARCH-02/05).
    private final ScoringContext scoringContext;
    private final ScoringPipeline scoringPipeline;

    /**
     * The plan's exploration parameters, taken once in the constructor and read as a field at every
     * site below (INV-DP-12). It sits here rather than on {@link SataAgent} because both classes
     * read from it, and a second field on the subclass would shadow this one — including for the
     * oracle harness, whose field injection walks the hierarchy and stops at the first match.
     *
     * <p>Reaching the run context is injection at assembly and a violation at decision time; that
     * this field is assigned in the constructor and only read afterwards is what makes the
     * distinction structural rather than a convention.
     */
    protected final RunSpec.ExplorationParams exploration;

    // LLM integration fields
    private final SystemBroadcastCatalog _broadcastCatalog;
    protected boolean _isNewState;
    protected State _lastState;
    protected State _stateBeforeLast;
    protected List<ApePromptBuilder.ActionHistoryEntry> _actionHistory = new ArrayList<>();

    protected ActionFilter validatedActionFilter = new BaseActionFilter() {
        @Override
        public boolean include(ModelAction action) {
            return validateNewAction(action) != null;
        }
    };
    private Set<State> refreshStatesCheckingBlacklist = new HashSet<>();
    private boolean currentStateRecovered;
    private boolean appActivityJustStartedFromClean;

    public StatefulAgent(MonkeySourceApe ape, Graph graph) {
        super(ape);
        graph.addListener(this);
        this.model = new Model(graph);
        this.timestamp = graph.getTimestamp();
        ComponentName mainApp = ape.getMainApp();
        RunSpec spec = RunContext.current().spec();
        this.exploration = spec.exploration();
        // The MOP substrate's path. A plan carries MopParams exactly when it carries the MOP
        // feature, and that feature is derived from ape.mopDataPath being set — so a null path here
        // and an absent feature are the same statement, and requireMopArm below reads the null as
        // "this run declares no MOP arm" exactly as it read an unset Config field.
        String mopDataPath = spec.has(Feature.MOP) ? spec.mop().dataPath() : null;
        this._mopData = requireMopArm(
                MopData.load(mopDataPath, mainApp.getPackageName(), mainApp.getClassName(),
                        RunContext.current().sink()),
                mopDataPath);
        this._coverageTracker = new UICoverageTracker();
        // rv-scoring-pipeline (activityBudgetEnabled): the activity budget is a fork addition; upstream
        // has none. Off -> no tracker is built and the budget check is skipped (INV-ARCH-01).
        this._budgetTracker = spec.has(Feature.ACTIVITY_BUDGET)
                ? new ActivityBudgetTracker(exploration.integer("ape.activityBaseBudget"),
                        exploration.integer("ape.activityBudgetPerWidget"))
                : null;
        this._broadcastCatalog = _mopData != null ? SystemBroadcastCatalog.load() : new SystemBroadcastCatalog();
        // rv-scoring-pipeline: build the scoring context (a live view onto this agent's collaborators)
        // and assemble the pipeline once, now that _mopData/_coverageTracker/graph/timestamp are set.
        // Pass isEnabled() reads only its injected weight and getMopData() here (run-fixed) — never
        // menuPickEligible — so no subclass field is touched before the subclass constructor runs.
        this.scoringContext = new ScoringContext() {
            @Override public MopData getMopData() { return StatefulAgent.this.getMopData(); }
            @Override public UICoverageTracker getCoverageTracker() { return StatefulAgent.this.getCoverageTracker(); }
            @Override public Graph getGraph() { return StatefulAgent.this.getGraph(); }
            @Override public int getTimestamp() { return StatefulAgent.this.getTimestamp(); }
            @Override public boolean menuPickEligible(String activity) { return StatefulAgent.this.menuPickEligible(activity); }
        };
        this.scoringPipeline = ScoringPipeline.fromParams(
                ScoringParams.fromSpec(spec), this.scoringContext, RunContext.current().sink());
        // Assembly provenance, recorded here because this is the one place both rosters are known:
        // the decision stages the plan assembles (a pure function of the plan — INV-DP-03 leaves
        // nothing to enumerate at runtime) and the scoring passes with the census of every
        // candidate. Without the census, "the arm turned the frontier family off" and "this
        // application's data could not support it" are the same three missing names.
        RunContext.current().sink().pipeline(stageNames(spec), scoringPipeline.passNames(),
                scoringPipeline.candidates());
    }

    /** The names of the decision stages this plan assembles, in the fixed candidate order. */
    private static List<String> stageNames(RunSpec spec) {
        List<String> names = new ArrayList<>();
        for (DecisionPipeline.Candidate candidate : DecisionPipeline.assembledCandidates(spec)) {
            names.add(candidate.stageName());
        }
        return names;
    }

    protected MopData getMopData() {
        return _mopData;
    }

    /**
     * INV-MOP-22: a run with {@code ape.mopDataPath} set SHALL either have MOP data or abort —
     * it SHALL never silently run as pure SATA and mislabel the arm (the failure class that
     * invalidated the earlier build-skew round). Returns {@code loaded} unchanged when the path
     * is unset (MOP disabled) or the data loaded; throws when the path is set but load failed.
     */
    static MopData requireMopArm(MopData loaded, String path) {
        if (loaded == null && path != null) {
            throw new StopTestingException("MOP arm cannot arm: ape.mopDataPath is set (" + path
                    + ") but MopData.load returned null; aborting rather than running as pure SATA"
                    + " and mislabeling the arm (INV-MOP-22)");
        }
        return loaded;
    }

    protected UICoverageTracker getCoverageTracker() {
        return _coverageTracker;
    }

    /**
     * The MOP-screen bit of an activity (INV-SEL-06): 1 when MOP data is loaded and the activity is
     * in its pre-computed MOP set, 0 otherwise — including whenever `MopData` is null, which is how
     * a MOP-off arm reports. Recorded on the step record's `dec` (where the step started) and its
     * `out` (where it landed), the two halves of the evidential link that says whether a
     * decision happened on, or reached, a monitored screen. The lookup is O(1) over a set built at
     * load time.
     */
    private int activityHasMop(String activity) {
        MopData mopData = getMopData();
        if (mopData == null || activity == null) return 0;
        return mopData.activityHasMop(activity) ? 1 : 0;
    }

    /**
     * The form-completion context for the current state: true when {@code currentState} carries at
     * least one unfilled {@code EditText}. Read by {@link ApeAgent#checkInput} to fill
     * deterministically (bypassing the inputRate toss). {@code checkInput} runs after
     * {@code moveForward} has promoted {@code newState} to {@code currentState} and nulled
     * {@code newState}, so this reads {@code currentState} (INV-FORM-07).
     */
    @Override
    protected boolean inFormCompletionContext() {
        return currentState != null && FormCompletion.hasUnfilledEditText(currentState, timestamp);
    }

    protected ActivityBudgetTracker getBudgetTracker() {
        return _budgetTracker;
    }

    // --- StepContext: what a decision stage may know about the step being decided ----------------
    //
    // The agent is the view rather than a snapshot handed to it, for the reason design D13 records:
    // the stages run in sequence within one step and one of them writes graphStableCounter, so a copy
    // would be stale before the step ended. Every method below reads live off this object, which also
    // means the oracle harness needs nothing injected for the context — it allocates the agent, and
    // the agent is the context.

    @Override
    public State newState() {
        return newState;
    }

    @Override
    public GUITree newGUITree() {
        return newGUITree;
    }

    @Override
    public boolean isNewState() {
        return _isNewState;
    }

    @Override
    public int graphStableCounter() {
        return graphStableCounter;
    }

    @Override
    public int timestamp() {
        return getTimestamp();
    }

    @Override
    public Random random() {
        return getRandom();
    }

    @Override
    public MopData mopData() {
        return getMopData();
    }

    @Override
    public Graph graph() {
        return getGraph();
    }

    @Override
    public ActivityBudgetTracker budgetTracker() {
        return getBudgetTracker();
    }

    @Override
    public List<ApePromptBuilder.ActionHistoryEntry> actionHistory() {
        return _actionHistory;
    }

    @Override
    public void resetGraphStableCounter() {
        graphStableCounter = 0;
    }

    public void updateModel(Model newModel) {
        this.model = newModel;
        if (currentState != null) {
            currentState = model.update(currentGUITree);
        }
        if (currentAction != null) {
            ModelAction prevCurrentAction = currentAction;
            currentAction = model.update(currentAction, currentGUITreeAction);
            // Refinement replaces currentAction with the rebuilt model's object before updateGraph()
            // runs. Remap the outcome buffer through the same mapping, or the reference guard
            // would silently drop the outcome on exactly the non-deterministic (refinement) steps.
            if (lastDecisionAction != null && lastDecisionAction == prevCurrentAction) {
                lastDecisionAction = currentAction;
            }
        }
        if (lastState != null) {
            lastState = model.update(lastGUITree);
        }
        if (lastAction != null) {
            lastAction = model.update(lastAction, lastGUITreeAction);
        }
        if (newState != null) {
            newState = model.update(newGUITree);
        }
        if (newAction != null) {
            newAction = model.update(newAction, newGUITreeAction);
        }
        if (!actionBuffer.isEmpty()) {
            Logger.println("Update action buffer...");
            LinkedList<StateTransition> newBuffer = new LinkedList<>();
            for (StateTransition st : actionBuffer) {
                Logger.println("Updating " + st);
                st = newModel.update(st);
                Logger.println("Updated " + st);
                newBuffer.add(st);
            }
            actionBuffer = newBuffer;
        }
        List<ActionRecord> actionHistory = getActionHistory();
        final int size = actionHistory.size();
        for (int i = 0; i < size; i++) {
            ActionRecord actionPair = actionHistory.get(i);
            Action action = actionPair.modelAction;
            GUITreeAction guiAction = actionPair.guiAction;
            if (action.isModelAction() && action.requireTarget()) {
                if (guiAction == null) {
                    throw new RuntimeException("Sanity check failed!");
                }
                action = newModel.update((ModelAction)action, guiAction);
                updateActionHistory(i, new ActionRecord(actionPair.clockTimestamp,
                        actionPair.agentTimestamp, action, guiAction));
            }
        }
    }

    public State getLastState() {
        return lastState;
    }

    public void setLastState(State lastState) {
        this.lastState = lastState;
    }

    public GUITree getLastGUITree() {
        return lastGUITree;
    }

    public void setLastGUITree(GUITree lastGUITree) {
        this.lastGUITree = lastGUITree;
    }

    public ModelAction getLastAction() {
        return lastAction;
    }

    public void setLastAction(ModelAction lastAction) {
        this.lastAction = lastAction;
    }

    public GUITreeAction getLastGUITreeAction() {
        return lastGUITreeAction;
    }

    public void setLastGUITreeAction(GUITreeAction lastGUITreeAction) {
        this.lastGUITreeAction = lastGUITreeAction;
    }

    public State getCurrentState() {
        return currentState;
    }

    public void setCurrentState(State currentState) {
        this.currentState = currentState;
    }

    public GUITree getCurrentGUITree() {
        return currentGUITree;
    }

    public void setCurrentGUITree(GUITree currentGUITree) {
        this.currentGUITree = currentGUITree;
    }

    public GUITreeAction getCurrentGUITreeAction() {
        return currentGUITreeAction;
    }

    public void setCurrentGUITreeAction(GUITreeAction currentGUITreeAction) {
        this.currentGUITreeAction = currentGUITreeAction;
    }

    public State getNewState() {
        return newState;
    }

    public void setNewState(State newState) {
        this.newState = newState;
    }

    public GUITree getNewGUITree() {
        return newGUITree;
    }

    protected ModelAction checkFuzzing(ModelAction action) {
        if (!action.requireTarget()) {
            if (!action.isBack()) {
                return action;
            }
        }
        if (action.getState() == null) {
            return action;
        }
        ActivityNode an = getGraph().getActivityNode(action.getState().getActivity());
        if (an == null) {
            return action;
        }
        if (an.getVisitedCount() < exploration.fuzzingActivityVisitThreshold()) {
            this.disableFuzzing = true;
        }
        return action;
    }

    public void setNewGUITree(GUITree newGUITree) {
        this.newGUITree = newGUITree;
    }

    public void setNewAction(ModelAction newAction) {
        this.newAction = newAction;
    }

    public GUITreeAction getNewGUITreeAction() {
        return newGUITreeAction;
    }

    public void setNewGUITreeAction(GUITreeAction newGUITreeAction) {
        this.newGUITreeAction = newGUITreeAction;
    }

    public StateTransition getCurrentStateTransition() {
        return currentStateTransition;
    }

    public void setCurrentStateTransition(StateTransition currentStateTransition) {
        this.currentStateTransition = currentStateTransition;
    }

    public void setCurrentAction(ModelAction currentAction) {
        this.currentAction = currentAction;
    }

    public abstract void onBufferLoss(State actual, State expected);

    public abstract void onRefillBuffer(Subsequence path);

    protected void assertEmptyActionBuffer() {
        if (!actionBuffer.isEmpty()) {
            throw new IllegalStateException("Try actions in the buffer first");
        }
    }

    protected void clearBuffer() {
        if (!actionBuffer.isEmpty()) {
            StateTransition transition = actionBuffer.removeFirst();
            getGraph().weakenStateTransition(transition.getSource(), transition.getAction(), transition.getTarget());
            actionBuffer.clear();
        }
    }

    public int actionBufferSize() {
        return this.actionBuffer.size();
    }

    protected ModelAction refillBuffer(Subsequence seq) {
        onRefillBuffer(seq);
        clearBuffer();
        return fillBuffer(seq);
    }

    protected ModelAction fillBuffer(Subsequence seq) {
        seq.fillBuffer(actionBuffer);
        ModelAction action = seq.getFirstAction();
        int throttle = Math.max(action.getThrottle(), actionBuffer.peekFirst().getThrottle());
        action.setThrottle(throttle);
        return action;
    }

    public Graph getGraph() {
        return model.getGraph();
    }

    /**
     * Verify last action and new state
     * 
     * @return
     */
    public ModelAction selectNewActionFromBuffer() {
        if (actionBuffer.isEmpty()) {
            return null;
        }
        StateTransition t = actionBuffer.removeFirst();
        ModelAction expectedCurrentAction = t.action;
        State expectedNewState = t.target;
        if (!expectedCurrentAction.equals(currentAction)) {
            Logger.iprintln("Inconsistent actions in action buffer: expected " + expectedCurrentAction + ", get "
                    + currentAction);
            clearBuffer();
            return null;
        }
        if (expectedNewState != null && !expectedNewState.equals(newState)) {
            Logger.iprintln("Inconsistent states in action buffer: expected " + expectedNewState + ", get " + newState);
            widgetDiffer.diff(newState, expectedNewState);
            widgetDiffer.print();
            if (!actionBuffer.isEmpty() && currentStateTransition.isSameActivity()) {
                // Two states have the same activity and have the same actions sets.
                ModelAction action = actionBuffer.peekFirst().action;
                ModelAction relocatedAction = newState.relocate(action);
                if (relocatedAction == null) {
                    getGraph().weakenStateTransition(currentState, currentAction, expectedNewState);
                    onBufferLoss(newState, expectedNewState);
                } else {
                    Logger.format("Relocate %s to %s ", action, relocatedAction);
                    int throttle = Math.max(relocatedAction.getThrottle(), actionBuffer.peekFirst().getThrottle()); // transition throttle
                    Logger.iformat("Buffer action throttle: original: %d, tracked: %d", relocatedAction.getThrottle(), actionBuffer.peekFirst().getThrottle());
                    relocatedAction.setThrottle(throttle);
                }
                clearBuffer();
                return relocatedAction;
            }
            getGraph().weakenStateTransition(currentState, currentAction, expectedNewState);
            onBufferLoss(newState, expectedNewState);
            clearBuffer();
            return null;
        }
        if (actionBuffer.isEmpty()) {
            return null;
        }
        ModelAction action = actionBuffer.peekFirst().action;
        Logger.iprintln("Peek an action from buffer " + action);
        ModelAction check;
        if (action.getTarget() != null) {
            check = newState.getAction(action.getTarget(), action.getType());
        } else {
            check = newState.getAction(action.getType());
        }
        if (check != action) {
            expectedNewState.dumpState();
            newState.dumpState();
            clearBuffer();
            return null;
        }
        int throttle = Math.max(action.getThrottle(), actionBuffer.peekFirst().getThrottle()); // transition throttle
        Logger.iformat("Buffer action throttle: original: %d, tracked: %d", action.getThrottle(), actionBuffer.peekFirst().getThrottle());
        action.setThrottle(throttle);
        return action;
    }

    public Rect getCurrentRootNodeBounds() {
        if (currentState == null) {
            return null;
        }
        return currentState.getLatestGUITree().getRootNode().getBoundsInScreen();
    }

    public ModelAction getCurrentAction() {
        return this.currentAction;
    }

    protected void resetTrace() {
        this.clearBuffer();
        this.currentStateTransition = null;
        this.currentState = null;
        this.currentAction = null;
        this.currentGUITree = null;
        this.currentGUITreeAction = null;
        this.newState = null;
        this.newAction = null;
        this.newGUITree = null;
        this.newGUITreeAction = null;
        this.lastAction = null;
        this.lastGUITree = null;
        this.lastGUITreeAction = null;
        this.lastState = null;
    }

    @Override
    public void startNewEpisode() {
        resetTrace();
        this.graphStableCounter = 0;
        this.stateStableCounter = 0;
        this.activityStableCounter = 0;
    }

    @Override
    public boolean appCrashed(String arg0, int arg1, String arg2, String arg3, long arg4, String arg5) {
        return super.appCrashed(arg0, arg1, arg2, arg3, arg4, arg5);
    }

    @Override
    public int appEarlyNotResponding(String arg0, int arg1, String arg2) {
        return super.appEarlyNotResponding(arg0, arg1, arg2);
    }

    @Override
    public int appNotResponding(String arg0, int arg1, String arg2) {
        return super.appNotResponding(arg0, arg1, arg2);
    }

    Bitmap captureBitmap() {
        return ape.captureBitmap();
    }

    protected State refreshNewState() {
        ComponentName topComp = newState.getLatestGUITree().getActivityName();
        int retry = 5;
        while (--retry >= 0) {
            Logger.iformat("Checking new state: %s, iteration: #%d", newState, retry);
            long begin = SystemClock.elapsedRealtimeNanos();
            AccessibilityNodeInfo newInfo = ape.getRootInActiveWindowSlow();
            long end = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("getRootInActiveWindowSlow takes %d ms.", TimeUnit.NANOSECONDS.toMillis(end - begin));
            State newNewState = null;
            if (newInfo == null) {
                continue;
            }
            Bitmap newNewBitmap = captureBitmap();
            newNewState = buildState(topComp, newInfo, newNewBitmap); // this will append a new tree to
            if (newNewState == null) {
                continue;
            }
            if (!newNewState.equals(newState)) {
                if (newState.isUnvisited()) {
                    if (!getGraph().remove(newState).isEmpty()) {
                        throw new RuntimeException("An unvisited state has non-empty transitions.");
                    }
                }
                newState = newNewState;
                return newState;
            } else {
                GUITree removed = newState.removeLastLastGUITree();
                if (removed == null) {
                    throw new IllegalStateException("At least two GUI trees.");
                }
                GUITreeBuilder.release(removed);
                model.release(removed);
            }
            // idle-timeout-cap: break threshold derives from the same flag as the
            // getRootInActiveWindowSlow ceiling (÷1000), so lowering the ceiling keeps
            // this "window stuck animating" break firing (INV-EXPL-25). Default 10s.
            if (TimeUnit.NANOSECONDS.toSeconds(end - begin) >= exploration.maxIdleTimeoutMs() / 1000) {
                break;
            }
        }
        return null;
    }

    protected void checkAndRefreshNewState() {
        State oldNewState = newState;
        ComponentName topComp = newState.getLatestGUITree().getActivityName();
        if (refreshStatesCheckingBlacklist.contains(newState)) {
            Logger.iformat("State %s is in blacklist for refresh check.", newState);
            return;
        }
        int retry = 5;
        while (--retry >= 0) {
            Logger.iformat("Checking new state: %s, iteration: #%d", newState, retry);
            if (!newState.isTrivialState()) {
                break;
            }
            long begin = SystemClock.elapsedRealtimeNanos();
            AccessibilityNodeInfo newInfo = ape.getRootInActiveWindowSlow();
            long end = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("getRootInActiveWindowSlow takes %d ms.", TimeUnit.NANOSECONDS.toMillis(end - begin));
            State newNewState = null;
            if (newInfo == null) {
                continue;
            }
            Bitmap newNewBitmap = captureBitmap();
            newNewState = buildState(topComp, newInfo, newNewBitmap); // this will append a new tree to
            if (newNewState == null) {
                continue;
            }
            if (!newNewState.equals(newState)) {
                if (newState.isUnvisited()) {
                    Set<StateTransition> transitions = getGraph().remove(newState);
                    if (!transitions.isEmpty()) {
                        Logger.iformat("Non empty transitions on unvisited states: %s", newState);
                        for (StateTransition st : transitions) {
                            Logger.iformat("- %s", st);
                        }
                        throw new RuntimeException("An unvisited state has non-empty transitions.");
                    }
                }
                newState = newNewState;
                retry = Math.min(retry, 0); // at most try once
            } else {
                GUITree removed = newState.removeLastLastGUITree();
                if (removed == null) {
                    throw new IllegalStateException("At least two GUI trees.");
                }
                GUITree last = newState.getLatestGUITree();
                // The equivalence is computed before the release cycle, not after it:
                // isTopNamingEquivalent reaches GUITreeBuilder.getStateKey(topNaming, removed),
                // which memoizes, so asking afterwards re-inserted a cache entry for the tree
                // that had just been released and made release() the second-to-last touch
                // instead of the last (V12). The value is unaffected — a state key is a pure
                // function of the naming and the tree, and neither is mutated by release.
                boolean topNamingEquivalent = isTopNamingEquivalent(removed, last);
                GUITreeBuilder.release(removed);
                model.release(removed);
                if (topNamingEquivalent) {
                    Logger.iprintln("Checking trivial new state: top naming equivalent.");
                    retry = Math.min(2, retry); // at most try twice
                } else {
                    Logger.iprintln("Checking trivial new state: NOT top naming equivalent.");
                }
            }
            // idle-timeout-cap: same "window stuck animating" break as refreshNewState, over the same
            // getRootInActiveWindowSlow duration — derived from the same flag (÷1000) so the ceiling
            // and both retry-loop breaks stay coupled (INV-EXPL-25). Default 10s. This loop also has
            // retry/early-exit paths, but the break must not diverge from the ceiling by construction.
            if (TimeUnit.NANOSECONDS.toSeconds(end - begin) >= exploration.maxIdleTimeoutMs() / 1000) {
                break;
            }
        }
        if (oldNewState == newState) {
            Logger.iformat("State %s is blacklisted from refresh check.", newState);
            refreshStatesCheckingBlacklist.add(newState);
        }
    }

    private boolean isTopNamingEquivalent(GUITree tree1, GUITree tree2) {
        Naming naming = model.getNamingManager().getTopNaming();
        StateKey state1 = GUITreeBuilder.getStateKey(naming, tree1);
        StateKey state2 = GUITreeBuilder.getStateKey(naming, tree2);
        return state1.equals(state2);
    }

    protected void preCheckTrivialNewState() {
        if (newState.isTrivialState()) {
            State oldNewState = newState;
            checkAndRefreshNewState();
            if (oldNewState != newState) {
                Logger.iformat("New (trivial) state is updated from %s to %s.", oldNewState, newState);
            }
        }
    }

    protected State buildAndValidateNewState(ComponentName topComp, AccessibilityNodeInfo info) {
        newState = buildState(topComp, info, captureBitmap());
        preCheckTrivialNewState();
        validateAllNewActions();
        newGUITree = newState.getLatestGUITree();
        newGUITree.setTimestamp(getTimestamp());
        return newState;
    }

    /**
     * 
     */
    protected Action updateStateInternal(ComponentName topComp, AccessibilityNodeInfo info) {
        recoverCurrentState();
        buildAndValidateNewState(topComp, info);
        preEvolveModel();
        _stateBeforeLast = _lastState;
        _lastState = currentState;
        _isNewState = (newState.getVisitedCount() == 0);
        getGraph().markVisited(newState, getTimestamp());
        saveGUI();
        updateGraph();
        // checkCircleTransition();
        checkNonDeterministicTransitions();
        if (newState.isUnvisited()) {
            getGraph().markVisited(newState, getTimestamp());
        }
        // gh9: register widgets and activity for coverage/budget tracking
        _coverageTracker.registerScreenElements(newState, newState.getActions());
        String activity = newState.getActivity();
        int widgetCount = 0;
        for (ModelAction a : newState.getActions()) {
            if (a.requireTarget()) widgetCount++;
        }
        if (_budgetTracker != null) {
            _budgetTracker.registerActivity(activity, widgetCount);
        }

        Action action = resolveNewAction();
        if (action.isModelAction()) {
            getGraph().markVisited((ModelAction) action, getTimestamp());
            recordActionHistory((ModelAction) action);
            moveForward();
        } else {
            this.resetTrace();
        }
        if (debug) {
            return Action.NOP;
        }
        return action; // newAction are moved to currentAction in moveForward
    }

    public void notifyActionConsumed() {
        GUITree.releaseLoadedData();
    }

    protected void checkNonDeterministicTransitions() {
        if (!exploration.evolveModel()) {
            return;
        }
        if (currentStateTransition == null) {
            return;
        }
        if (currentStateRecovered) {
            return;
        }
        Model newModel = model.resolveNonDeterministicTransitions(currentStateTransition);
        if (newModel != null) {
            Logger.iprintln("Model has been refined, reset stateful..");
            updateModel(newModel);
            validateAllNewActions();
        }
    }

    protected State checkUnderAbstractedState() {
        Naming naming = newState.getCurrentNaming();
        if (naming.getParent() == null) {
            return newState;
        }
        int iteration = 0;
        while (true) {
            Logger.iformat("Check under-abstracted states %s: #%d", newState, iteration++);
            State state = newState;
            checkAndAbstractUnderAbstractedState();
            if (state == newState) {
                break;
            }
        }
        return newState;
    }

    protected void checkAndAbstractUnderAbstractedState() {
        Naming naming = newState.getCurrentNaming();
        Naming parentNaming = naming;
        int iteration = 0;
        while (parentNaming.getParent() != null) {
            Set<State> states = getGraph().getAllStates(parentNaming);
            Logger.iformat("Check under-abstracted states collected %d targets for naming %s. The state is %s. #%d",
                    states.size(), parentNaming, newState, iteration++);
            Model newModel = model.stateAbstraction(naming, newState, parentNaming, states);
            if (newModel != null) {
                model = newModel;
                updateModel(model);
                validateAllNewActions();
                return;
            }
            parentNaming = parentNaming.getParent();
        }
    }

    protected void preEvolveModel() {
        if (!exploration.evolveModel()) {
            return;
        }
        {
            long begin = SystemClock.elapsedRealtimeNanos();
            checkUnderAbstractedState();
            long end = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("Pre-checking under-abstracted states takes %d ms", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }
        {
            long begin = SystemClock.elapsedRealtimeNanos();
            checkOverAbstractedState();
            long end = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("Checking over-abstracted states takes %d ms", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }
        {
            long begin = SystemClock.elapsedRealtimeNanos();
            checkUnderAbstractedState();
            long end = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("Post-checking under-abstracted states takes %d ms", TimeUnit.NANOSECONDS.toMillis(end - begin));
        }
    }

    protected State checkOverAbstractedState() {
        int iteration = 0;
        while (true) {
            Logger.iformat("Check over-abstracted states %s: #%d", newState, iteration++);
            State state = newState;
            checkAndRefineOverAbstractedState();
            if (state == newState) {
                break;
            }
        }
        return newState;
    }

    static int compareArrays(Object[] a1, Object[] a2) {
        if (a1 == null) {
            if (a2 == null) {
                return 0;
            }
            return 1;
        }
        if (a2 == null) {
            return -1;
        }
        return a1.length - a2.length;
    }

    protected void checkAndRefineOverAbstractedState() {
        List<ModelAction> actions = newState.targetedActions();
        Collections.sort(actions, new Comparator<ModelAction>() {

            @Override
            public int compare(ModelAction o1, ModelAction o2) {
                if (o1.requireTarget() && o2.requireTarget()) {
                    return compareArrays(o1.getResolvedNodes(), o2.getResolvedNodes());
                }
                if (!o1.requireTarget() && !o2.requireTarget()) {
                    return o1.getType().compareTo(o2.getType());
                }
                if (o1.requireTarget() && !o2.requireTarget()) {
                    return 1;
                }
                if (!o1.requireTarget() && o2.requireTarget()) {
                    return -1;
                }
                return 0;
            }

        });
        Set<Name> names = new HashSet<>();
        for (ModelAction action : actions) {
            if (!action.requireTarget()) {
                continue;
            }
            if (names.contains(action.getTarget())) {
                continue;
            }
            names.add(action.getTarget());
            Model newModel = model.actionRefinement(action);
            if (newModel != null) {
                model = newModel;
                updateModel(model);
                validateAllNewActions();
                return;
            }
        }
    }

    @Override
    public boolean activityStarting(Intent intent, String pkg) {
        boolean allow = super.activityStarting(intent, pkg);
        return allow;
    }

    protected void saveGUI() {
        if (exploration.saveGUITreeToXmlEveryStep()) {
            checkOutputDir();
            File xmlFile = new File(checkOutputDir(), String.format("step-%d.xml", getTimestamp()));
            Logger.iformat("Saving GUI tree to %s at step %d", xmlFile, getTimestamp());
            try {
                Utils.saveXml(xmlFile.getAbsolutePath(), newGUITree.getDocument());
            } catch (Exception e) {
                e.printStackTrace();
                Logger.wformat("Fail to save GUI tree to %s at step %d", xmlFile, getTimestamp());
            }
        }
        if (exploration.takeScreenshot() && exploration.takeScreenshotForEveryStep()) {
            checkOutputDir();
            File screenshotFile = new File(checkOutputDir(), String.format("step-%d.png", getTimestamp()));
            Logger.iformat("Saving screen shot to %s at step %d", screenshotFile, getTimestamp());
            ape.takeScreenshot(screenshotFile);
        }
    }

    protected State buildState(ComponentName topComp, AccessibilityNodeInfo rootInfo, Bitmap bitmap) {
        return model.getState(topComp, rootInfo, bitmap);
    }

    public void onAppActivityStarted(ComponentName app, boolean clean) {
        String className = app.getClassName();
        Logger.iprintln("App Activity " + className + " started.");
        appActivityJustStarted = true;
        appActivityJustStartedFromClean = clean;
    }

    protected void recoverCurrentState() {
        currentStateRecovered = false;
        if (currentState != null) {
            return;
        }
        List<ActionRecord> history = getActionHistory();
        if (history.isEmpty()) {
            return;
        }
        ActionRecord record = null;
        for (int index = history.size() - 1; index >= 0; index--) {
            record = history.get(index);
            if (record.modelAction.canStartApp()) {
                // do nothing if is start
                return;
            }
            if (record.modelAction.isModelAction()) {
                break;
            }
        }
        if (record == null || !record.modelAction.isModelAction()) {
            return; // no valid action
        }
        ModelAction modelAction = (ModelAction) record.modelAction;
        GUITreeAction guiAction = record.guiAction;
        currentState = modelAction.getState();
        currentAction = modelAction;
        currentGUITree = guiAction.getGUITree();
        currentGUITreeAction = guiAction;
        Logger.iprintln("Recover current states and actions...");
        Logger.iformat("> recovered current state: %s", currentState);
        Logger.iformat("> recovered current action: %s", currentAction);
        currentStateRecovered = true;
    }

    protected void updateGraph() {
        if (currentState == null) {
            if (this.appActivityJustStarted) {
                Logger.iformat("Entry state: %s", newState);
                model.getGraph().addEntryGUITree(newGUITree);
                if (appActivityJustStartedFromClean) {
                    model.getGraph().addCleanEntryGUITree(newGUITree);
                }
                this.appActivityJustStartedFromClean = false;
                this.appActivityJustStarted = false;
            }
        }
        currentStateTransition = model.addTransition(currentState, currentAction, newState, currentGUITree,
                currentGUITreeAction, newGUITree);
        // Outcome attribution (scoring-pipeline delta, INV-ARCH-08/09): resolve here — after
        // addTransition, never inside Model/Graph, so the refinement rebuild replay (which re-records
        // via the Graph.addTransition(GUITreeTransition) overload) cannot resolve. Guards: (1) non-null
        // transition — addTransition returns null on the run's first step, post-restart, and the
        // stale-ephemeral drop; (2) the buffered decision is reference-equal to currentAction — state
        // recovery / non-model interludes install a foreign action. The buffer is consumed (single-shot)
        // so the BadStateException selection-retry and recovery re-record cannot close a record twice.
        if (currentStateTransition != null
                && lastDecisionAction != null && lastDecisionAction == currentAction) {
            // Closing the record is what writes it. The target's activity travels with its state key
            // because the target may be seen here for the first time — that is what a new state is —
            // and the reader derives the outcome-side MOP flag through the dictionary entry this
            // call creates (INV-SNK-08).
            RunContext.current().sink().outcome(_isNewState, newState.getStateKey().toString(),
                    newState.getActivity(), activityHasMop(newState.getActivity()) == 1,
                    !currentStateTransition.isSameActivity());
            // B1 outcome feedback (llm-routing INV-RTR-15): the ban record cannot observe outcomes,
            // so the agent hands it the executed LLM decision and the new_state bit computed here.
            // Only LLM-originated decisions feed the record — SATA-selected actions are never
            // banned. The feedback rides the same buffered-decision guards as the closure above.
            if (RunContext.current().hasLlm()
                    && currentAction.getDecisionSource() == ModelAction.DecisionSource.LLM) {
                RunContext.current().coordinateMapper().recordLlmOutcome(currentAction, _isNewState);
            }
            lastDecisionAction = null;
        }
        checkStable();
    }

    protected void checkStable() {
        Logger.format("Graph Stable Counter: graph (%d), state (%d), activity (%d)", graphStableCounter,
                stateStableCounter, activityStableCounter);
        if (graphStableCounter > 0) {
            if (onGraphStable(graphStableCounter)) {
                graphStableCounter = 0;
            }
        }
        if (stateStableCounter > 0) {
            if (onStateStable(stateStableCounter)) {
                stateStableCounter = 0;
            }
        }
        if (activityStableCounter > 0) {
            if (onActivityStable(activityStableCounter)) {
                activityStableCounter = 0;
            }
        }
    }

    public boolean onActivityStable(int counter) {
        if (counter > exploration.activityStableRestartThreshold()) {
            Logger.format("Activity is stable for %d", counter);
            requestRestart();
            return true;
        }
        return false;
    }

    @Override
    public boolean onGraphStable(int counter) {
        if (counter > exploration.graphStableRestartThreshold()) {
            Logger.format("Graph is stable for %d", counter);
            requestRestart();
            return true;
        }
        return false;
    }

    /** Lazily-built tuple lists, cached for the session. */
    private java.util.List<TriggerTuple> _triggerTuples;
    private java.util.List<ProviderTuple> _providerTuples;
    private boolean _tuplesBuilt;

    /** A (component × intentFilter × action) trigger candidate (gh13 T1.4). */
    static final class TriggerTuple {
        final ComponentInfo component;
        final ComponentInfo.IntentFilter filter; // null ⇒ component-name-only intent
        final String action;                     // null ⇒ no setAction
        TriggerTuple(ComponentInfo component, ComponentInfo.IntentFilter filter, String action) {
            this.component = component;
            this.filter = filter;
            this.action = action;
        }
    }

    /** A (provider × operation) trigger candidate (gh13 T1.5). operation ∈ {query, insert, update}. */
    static final class ProviderTuple {
        final ComponentInfo.ProviderInfo provider;
        final String operation;
        ProviderTuple(ComponentInfo.ProviderInfo provider, String operation) {
            this.provider = provider;
            this.operation = operation;
        }
    }

    /**
     * activity-frontier Lever A (pure). Returns {@code weight} when {@code shortId} matches the
     * {@code widgetName} of some WTG transition whose {@code targetActivity} is NOT in
     * {@code visitedTargets}; 0 otherwise (or when {@code weight <= 0} / {@code shortId} is empty).
     * Mirrors {@code MopScorer.scoreWtg}'s resource-id match but keys on unvisited-ness instead of
     * MOP-reachability (INV-WTG-06). Testable without the Graph via the visited-target set.
     */
    public static int frontierBoost(String shortId, java.util.List<MopData.WtgTransition> transitions,
            Set<String> visitedTargets, int weight) {
        if (weight <= 0 || shortId == null || shortId.isEmpty() || transitions == null) {
            return 0;
        }
        for (MopData.WtgTransition t : transitions) {
            if (shortId.equals(t.widgetName) && !visitedTargets.contains(t.targetActivity)) {
                return weight;
            }
        }
        return 0;
    }

    /**
     * activity-frontier (INV-CT-07): the decision source for a non-model action's step record
     * line. {@code EVENT_TRIGGER_ACTIVITY} (the stagnation launcher) is a {@code Component} decision;
     * every other non-model action stays on the {@code SATA} chain that produced it. Pure.
     */
    static ModelAction.DecisionSource nonModelDecisionSource(ActionType type) {
        return type == ActionType.EVENT_TRIGGER_ACTIVITY
                ? ModelAction.DecisionSource.Component
                : ModelAction.DecisionSource.SATA;
    }

    /**
     * The {@code dec.patched} bit, or {@link EventSink#ABSENT} for an action with no resolved target
     * (INV-SEL-10). {@code MODEL_BACK}, {@code MODEL_MENU} and {@code MODEL_LLM_TAP} report absent,
     * consistently with the record's other target-derived fields.
     *
     * <p>The bit says the node's clickability was written by {@code patchGUITree}. Two boundaries
     * come with it: it is the node's provenance and not the action's causality (a causal reading
     * holds for {@code MODEL_CLICK}, which is derived from {@code clickable || checkable}, and not
     * for a scroll or long-click on the same node), and where a {@code Name} resolves to several
     * nodes it describes the one this record names — exact for that node, a sample for its siblings.
     */
    static int patchedValue(ModelAction action) {
        if (!action.requireTarget()) return EventSink.ABSENT;
        GUITreeNode node = action.getResolvedNode();
        if (node == null) return EventSink.ABSENT;
        return node.isPatchedClickable() ? 1 : 0;
    }

    /**
     * The {@code dec.cf.changed} bit, or {@link EventSink#ABSENT} for a channel where MOP boosts do
     * not participate in the pick (INV-SEL-08). Only the four MOP-sensitive channels carry it; the
     * LLM, the launcher, the buffer and every other path carry none.
     *
     * <p>A recomputation that failed reports {@code 0}: the factual pick was already made, so a
     * failure costs the record its contrast and nothing else.
     *
     * <p>Reading rule, part of the contract: this is the divergence point of one step, not a
     * trajectory effect. Summing it over a run does not measure what the MOP guidance achieved —
     * only an arm-level contrast does.
     */
    static int counterfactualChanged(ModelAction action) {
        switch (action.getPickChannel()) {
        case SHORT_CIRCUIT_UNVISITED:
        case SHORT_CIRCUIT_0STEP:
        case ROULETTE_GREEDY:
        case ROULETTE_EARLY:
            break;
        default:
            return EventSink.ABSENT;
        }
        ModelAction counterfactual = action.getCounterfactualPick();
        return counterfactual != null && counterfactual != action ? 1 : 0;
    }

    /**
     * The counterfactual action, and only when it diverges from the factual pick.
     *
     * <p>Null everywhere else, which includes the unchanged case: the retired line repeated the
     * factual action there, and the record already carries it as {@code dec.a} — a second copy on
     * every MOP-sensitive step would be the largest string in the trace written twice for nothing.
     */
    static String counterfactualAction(ModelAction action) {
        return counterfactualChanged(action) == 1 ? action.getCounterfactualPick().toString() : null;
    }

    /**
     * The pick channel for a non-model action's step record (INV-SEL-05). These actions
     * carry no provenance field of their own — they are not {@code ModelAction}s — so the channel is
     * read off the type: {@code EVENT_TRIGGER_ACTIVITY} is the stagnation activity launcher, and
     * every other non-model action is outside the four MOP-sensitive channels. Pure.
     */
    static ModelAction.PickChannel nonModelPickChannel(ActionType type) {
        return type == ActionType.EVENT_TRIGGER_ACTIVITY
                ? ModelAction.PickChannel.LAUNCHER
                : ModelAction.PickChannel.SATA_OTHER;
    }

    private static final String[] PROVIDER_OPERATIONS = {"query", "insert", "update"};

    /**
     * gh13 T1.4+T1.5: build the round-robin trigger candidates from MOP data. Package-visible
     * and side-effect-free so it can be unit-tested without the Android runtime.
     *
     * Rules (INV-MOP-15): the probabilistic tuple pool holds only BroadcastReceivers and Services
     * (ContentProviders keep their separate provider path; activities are handled exclusively by
     * the stagnation launcher — EVENT_TRIGGER_ACTIVITY, decision_source=Component — and never enter
     * this pool). Skip components with reachesTarget=false. Each surviving component yields one
     * tuple per (intentFilter × action); a component with no filters but non-empty targetMethods
     * yields one component-name-only tuple (filter=null, action=null).
     */
    static java.util.List<TriggerTuple> buildTriggerTuples(MopData data) {
        java.util.List<TriggerTuple> tuples = new java.util.ArrayList<>();
        if (data == null) return tuples;
        java.util.List<ComponentInfo> candidates = new java.util.ArrayList<>();
        candidates.addAll(data.getReceivers());
        candidates.addAll(data.getServices());
        for (ComponentInfo c : candidates) {
            if (!c.reachesTarget) continue;
            boolean emitted = false;
            for (ComponentInfo.IntentFilter f : c.intentFilters) {
                for (String action : f.actions) {
                    tuples.add(new TriggerTuple(c, f, action));
                    emitted = true;
                }
            }
            if (!emitted && !c.targetMethods.isEmpty()) {
                tuples.add(new TriggerTuple(c, null, null)); // component-name-only (D15)
            }
        }
        return tuples;
    }

    static java.util.List<ProviderTuple> buildProviderTuples(MopData data) {
        java.util.List<ProviderTuple> tuples = new java.util.ArrayList<>();
        if (data == null) return tuples;
        for (ComponentInfo.ProviderInfo p : data.getProviders()) {
            if (!p.reachesTarget || p.authorities == null || p.authorities.isEmpty()) continue;
            for (String op : PROVIDER_OPERATIONS) {
                tuples.add(new ProviderTuple(p, op));
            }
        }
        return tuples;
    }

    /**
     * The size of the MOP-reachable trigger pool: every (component × filter × action) tuple
     * followed by every (provider × operation) tuple (gh13 T1.4+T1.5). Built on the first ask and
     * cached for the session, so the count a caller walks over cannot change under it.
     *
     * @return the pool size; zero when the census yields no triggerable target
     */
    public int mopComponentTargetCount() {
        if (!_tuplesBuilt) {
            _triggerTuples = buildTriggerTuples(_mopData);
            _providerTuples = buildProviderTuples(_mopData);
            _tuplesBuilt = true;
        }
        return _triggerTuples.size() + _providerTuples.size();
    }

    /**
     * Fires one target of that pool. The trigger tuples occupy the low indices and the provider
     * tuples the rest, which is what makes a single cursor walk both kinds.
     *
     * <p>Which target is fired is the caller's choice, not this method's: the round-robin cursor is
     * episode state owned by the component-trigger stage (INV-DP-07), while the intent and
     * {@code content}-command dispatch below is device-facing machinery that stays with the agent
     * (design D5).
     *
     * @param target an index below {@link #mopComponentTargetCount}
     */
    public void triggerMopComponent(int target) {
        if (target < _triggerTuples.size()) {
            dispatchTrigger(_triggerTuples.get(target));
            return;
        }
        dispatchProvider(_providerTuples.get(target - _triggerTuples.size()));
    }

    /** Build the per-trigger log line (static for testability). */
    static String triggerLogLine(TriggerTuple t) {
        StringBuilder cats = new StringBuilder();
        if (t.filter != null) {
            for (String category : t.filter.categories) {
                if (cats.length() > 0) cats.append(',');
                cats.append(category);
            }
        }
        // gh60 D15: surface the permission gate so a SecurityException trigger failure is diagnosable.
        String perm = t.component.hasPermissionGate() ? t.component.permission : "none";
        return String.format(
                "[APE-RV] Triggering %s: %s action=%s categories=%s permission=%s reachesTarget=true",
                t.component.componentType, t.component.className, t.action, cats.toString(), perm);
    }

    /** Build the `content` shell command for a provider operation (static for testability). */
    static String[] buildContentCommand(ProviderTuple t) {
        String uri = "content://" + t.provider.authorities;
        if ("insert".equals(t.operation) || "update".equals(t.operation)) {
            return new String[]{"content", t.operation, "--uri", uri, "--bind", "ape_probe:s:"};
        }
        return new String[]{"content", t.operation, "--uri", uri};
    }

    private boolean dispatchTrigger(TriggerTuple t) {
        ComponentInfo c = t.component;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(_mopData.getPackageName(), c.className));
        if (t.action != null) {
            intent.setAction(t.action);
        }
        if (t.filter != null) {
            for (String category : t.filter.categories) {
                intent.addCategory(category);
            }
        }
        Logger.iprintln(triggerLogLine(t));
        if (c instanceof ComponentInfo.ReceiverInfo) {
            if (_broadcastCatalog != null && t.action != null) {
                for (SystemBroadcastCatalog.IntentExtra extra : _broadcastCatalog.lookup(t.action)) {
                    extra.applyTo(intent);
                }
            }
            return AndroidDevice.sendBroadcast(intent);
        } else if (c instanceof ComponentInfo.ServiceInfo) {
            return AndroidDevice.startService(intent);
        }
        // activity-frontier: the tuple pool no longer carries ActivityInfo (activities are launched
        // only by the stagnation launcher's own startActivity path). Providers use dispatchProvider.
        return false;
    }

    private boolean dispatchProvider(ProviderTuple t) {
        String[] cmd = buildContentCommand(t);
        Logger.iformat("[APE-RV] Triggering provider: %s op=%s uri=content://%s reachesTarget=true",
                t.provider.className, t.operation, t.provider.authorities);
        int exit = runContentCommand(cmd);
        if (exit != 0) {
            Logger.wformat("[APE-RV] provider %s op=%s exit=%d", t.provider.className, t.operation, exit);
        }
        return exit == 0;
    }

    /** Overridable seam for content-provider shell invocation (testability). */
    protected int runContentCommand(String[] cmd) {
        try {
            return AndroidDevice.executeCommandAndWaitFor(cmd);
        } catch (Exception e) {
            Logger.wformat("[APE-RV] content command failed: %s", e.getMessage());
            return -1;
        }
    }

    @Override
    public void onActivityStopped() {
        this.currentAction = null;
        this.currentState = null;
        this.newAction = null;
        this.newState = null;
        this.lastAction = null;
        this.lastState = null;
        clearBuffer();
        clearCounters();
    }

    private void clearCounters() {
        graphStableCounter = 0;
        stateStableCounter = 0;
        activityStableCounter = 0;
    }

    @Override
    public boolean onStateStable(int counter) {
        if (counter > exploration.stateStableRestartThreshold()) {
            Logger.format("State is stable for %d", counter);
            requestRestart();
            return true;
        }
        return false;
    }

    protected void doMoveForward() {
        // Do switch
        Logger.format("Last  state: %s", lastState);
        Logger.format("Last action: %s", lastAction);
        Logger.format("Curr  state: %s", currentState);
        Logger.format("Curr action: %s", currentAction);
        Logger.format("New   state: %s", newState);
        Logger.format("New  action: %s", newAction);

        lastState = currentState;
        lastAction = currentAction;
        lastGUITree = currentGUITree;
        lastGUITreeAction = currentGUITreeAction;

        currentState = newState;
        currentAction = newAction;
        currentGUITree = newGUITree;
        currentGUITreeAction = newGUITreeAction;

        newState = null;
        newAction = null;
        newGUITree = null;
        newGUITreeAction = null;
        currentStateTransition = null;
    }

    protected void moveForward() {
        // gh9: record interaction and budget iteration before shifting state pointers
        if (newAction != null && newState != null) {
            _coverageTracker.recordInteraction(newState, newAction);
            if (_budgetTracker != null) {
                _budgetTracker.recordIteration(newState.getActivity());
            }
        }
        doMoveForward();
    }

    public void onAddNode(State node) {
        if (exploration.takeScreenshot() && exploration.takeScreenshotForNewState()) {
            checkOutputDir();
            String id = node.getGraphId();
            File screenshotFile = new File(checkOutputDir(), String.format("%s.png", id));
            Logger.format("Saving screen shot for new state %s to %s", id, screenshotFile);
            ape.takeScreenshot(screenshotFile);
        }
    }

    @Override
    public void onVisitStateTransition(StateTransition edge) {
        currentStateTransition = edge;
        switch (edge.getType()) {
        case NEW_ACTION:
        case NEW_ACTION_TARGET:
            graphStableCounter = 0;
            break;
        case EXISTING:
            graphStableCounter++;
            break;
        }
        if (edge.isCircle() && edge.getTheta() == 0) {
            stateStableCounter++;
        } else {
            stateStableCounter = 0;
        }
        if (edge.isSameActivity()) {
            activityStableCounter++;
        } else {
            activityStableCounter = 0;
        }
    }

    protected ModelAction validateNewAction(ModelAction action) {
        if (action == null) {
            return null;
        }
        action = newState.resolveAction(this, action, getThrottleForNewAction(newState, action));
        if (ape.validateResolvedAction(action)) {
            action.setValid(true);
            return action;
        }
        Logger.wformat("Mark an action (%s) invalid", action);
        action.setValid(false);
        return null;
    }

    protected void validateAllNewActions() {
        Utils.assertNotNull(newState);
        for (ModelAction action : newState.getActions()) {
            validateNewAction(action);
        }
    }

    protected Action resolveNewAction() {
        Utils.assertNotNull(newState);
        // The step's record opens before selection rather than after it, so the LLM attempts and the
        // MOP exposure pair that happen on the way to a decision land inside the step that made
        // them. The envelope is everything already known at this point: which step, how far into
        // the run, and where the agent is standing (INV-SNK-03).
        RunContext.current().sink().beginStep(getTimestamp(), RunContext.current().elapsedMs(),
                newState.getActivity(), activityHasMop(newState.getActivity()) == 1,
                newState.getStateKey().toString());
        adjustActionsByGUITree();
        Action action = selectNewActionNonnull();
        Utils.assertNotNull(action);
        if (action.isModelAction()) {
            newAction = (ModelAction) action;
            newGUITreeAction = newAction.getResolvedGUITreeAction();
            Utils.assertNotNull(newGUITreeAction);
            // A-5: one decision per finally-selected action (INV-SEL-04), filling the `dec` section
            // of the record this step opened. The boosts arrive as their raw values and the record
            // decides what to omit; the two tri-states arrive as themselves, because for them an
            // absent field is a different statement from a zero.
            RunContext.current().sink().decision(newAction.toString(),
                    newAction.getDecisionSource().name(),
                    newAction.getPickChannel().getLabel(),
                    newAction.getPriority(),
                    newAction.getMopBoost(), newAction.getMopFrontierBoost(),
                    newAction.getWtgBoost(), newAction.getCoverageBoost(),
                    newAction.getMenuBoost(), newAction.getFormBoost(),
                    newAction.getWtgSource(),
                    patchedValue(newAction),
                    counterfactualChanged(newAction), counterfactualAction(newAction));
            // Buffer this model decision so the record closes on the outcome that belongs to it, and
            // so the dead-pair ban receives the decision's outcome. The buffer is the closure guard
            // as much as the join key: a step whose buffered action is replaced never resolves.
            lastDecisionAction = newAction;
            return newAction;
        } else {
            // Non-model actions (e.g. event-level) carry no per-mechanism boosts. The decision
            // source is derived from the action's type: the stagnation launcher
            // (EVENT_TRIGGER_ACTIVITY) is a Component decision (INV-CT-07); every other non-model
            // action stays on the SATA chain that produced it (INV-SEL-04).
            RunContext.current().sink().decisionNonModel(action.toString(),
                    nonModelDecisionSource(action.getType()).name(),
                    nonModelPickChannel(action.getType()).getLabel());
            // Non-model actions produce no transition under their own identity; clear the buffer so
            // a stale model decision cannot be resurrected as currentAction by state recovery.
            lastDecisionAction = null;
            return action;
        }
    }


    /**
     * Empirical priority.
     * @param actionType
     * @return
     */
    protected int getActionBasePriority(ActionType actionType) {
        switch (actionType) {
        case MODEL_CLICK:
            return 4;
        case MODEL_LONG_CLICK:
            return 2;
        case MODEL_SCROLL_TOP_DOWN:
            return 2;
        case MODEL_SCROLL_BOTTOM_UP:
            return 3;
        case MODEL_SCROLL_LEFT_RIGHT:
            return 3;
        case MODEL_SCROLL_RIGHT_LEFT:
            return 2;
        default:
            return 1;
        }
    }

    /**
     * back-menu-pick-cap hook: whether the gh13 OPTIONSMENU gateway boost may still be applied on
     * {@code activity}. The base agent is always eligible (no cap state); {@code SataAgent} overrides
     * this with a cap check over its per-(activity, MODEL_MENU) pick count. Consulted in the menu-boost
     * pass of {@link #adjustActionsByGUITree()}.
     */
    protected boolean menuPickEligible(String activity) {
        return true;
    }

    protected void adjustActionsByGUITree() {
        // Rect displayBounds = ape.getDisplayBounds();
        for (ModelAction action : newState.getActions()) {
            int basePriority = getActionBasePriority(action.getType()) << 3;
            action.setPriority(basePriority);
            if (!action.requireTarget()) {
                if (action.isUnvisited()) {
                    int priority = action.getPriority();
                    priority += 5;
                    action.setPriority(priority);
                }
                continue;
            }
            if (!action.isValid()) {
                continue;
            }
            if (!action.isResolvedAt(timestamp)) {
                continue;
            }
            GUITreeNode node = action.getResolvedNode();
            action.setEnabled(node.isEnabled());
            Collection<StateTransition> edges = getGraph().getOutStateTransitions(action);
            int priority = action.getPriority();
            if (action.isUnvisited()) {
                priority += 20; // Select unvisited priority
            }
            if (!action.isSaturated()) {
                List<GUITreeNode> nodes = newGUITree.getNodes(action.getTarget());
                int size = nodes.size();
                if (size > 1) {
                    priority += Math.min(size, exploration.maxExtraPriorityAliasedActions()) * getActionBasePriority(action.getType());
                }
            }
            for (StateTransition edge : edges) {
                if (edge.isStrong()) {
                    priority += 0;
                    if (edge.getTarget().isSaturated()) {
                        priority += -10; // no saturated states
                    } else {
                        if (edge.isSameActivity()) {
                            priority += 10;
                        } else {
                            priority += 0;
                        }
                    }
                } else {
                    if (edges.size() > 1) {
                        priority += 10; // make it weaker
                    }
                }
            }
            if (priority <= 0) {
                priority = 1;
            }
            action.setPriority(priority);
        }
        // rv-scoring-pipeline (INV-ARCH-05): every RV scoring term is now a ScoringPass in the
        // pipeline assembled once from the plan (MopWidget → MenuGateway → WTG → Frontier → Coverage →
        // FormCompletion). apply() clears provenance then runs the enabled passes; an empty pipeline
        // (pure arm) is a strict no-op, leaving the upstream base priorities above untouched.
        scoringPipeline.apply(newState, newState.getActions().toArray(new ModelAction[0]), scoringContext);
    }

    boolean isTopLeftClick(ModelAction action, Rect displayBounds) {
        if (!action.requireTarget()) {
            return false;
        }
        if (!action.isClick()) {
            return false;
        }
        GUITreeNode node = action.getResolvedNode();
        if (node == null) {
            return false;
        }
        Rect nodeBounds = action.getResolvedNode().getBoundsInScreen();
        int top = displayBounds.top;
        int left = displayBounds.left;
        Rect topLeft = new Rect(left, top, left + 300, top + 300);
        return topLeft.contains(nodeBounds);
    }


    protected int getThrottleForNewAction(State state, ModelAction action) {
        if (state != action.getState()) {
            throw new IllegalStateException("Oops");
        }
        int throttle = exploration.baseThrottle();
        Collection<StateTransition> edges = getGraph().getOutStateTransitions(action);
        boolean hasActivityTransition = false;
        for (StateTransition edge : edges) {
            if (edge.action.isBack()) {
                continue;
            }
            if (!edge.isSameActivity()) {
                hasActivityTransition = true;
            }
        }
        if (action.isUnvisited()) {
            throttle += exploration.throttleForUnvisitedAction();
            Logger.dformat("Add throttle for unvisited activity state transition: %d", throttle);
        }
        if (hasActivityTransition) {
            throttle += exploration.throttleForActivityTransition();
            Logger.dformat("Add throttle for weak activity state transition: %d", throttle);
        }
        throttle = Math.min(throttle, exploration.maxThrottle());

        GUITreeNode node = action.getResolvedNode();
        if (node != null) {
            throttle += node.getExtraThrottle();
            Logger.dformat("Add user-defined throttle for state transition: %d", throttle);
        }

        if (throttle > 0) {
            Logger.dformat("Append a throttle %d for action %s", throttle, action);
        }
        return throttle;
    }

    /**
     * 
     * @return must return a non-null action
     */
    public ModelAction handleNullAction() {
        ModelAction action = newState.randomlyPickAction(getRandom(), validatedActionFilter);
        if (action != null) {
            ModelAction resolved = validateNewAction(action);
            if (resolved != null) {
                return resolved;
            }
        }
        throw new BadStateException("No available action on the current state");
    }

    protected ModelAction selectNewActionRandomly() {
        ModelAction action = newState.randomlyPickAction(getRandom());
        return action;
    }

    protected ModelAction selectNewValidActionRandomly() {
        ModelAction action = newState.randomlyPickValidAction(getRandom());
        return action;
    }

    protected abstract Action selectNewActionNonnull();

    /**
     * Records the most recently executed action into the LLM action history ring buffer (max 5 entries).
     * Called after resolveNewAction() when the action is a ModelAction.
     */
    protected void recordActionHistory(ModelAction action) {
        // The ring buffer feeds the LLM prompt and nothing else, so a run with no LLM would fill it
        // for a reader that will never come. The test is the plan's, not a unit's nullness: a plan
        // without the feature builds no units and assembles no LLM stage.
        if (!RunContext.current().hasLlm()) {
            return;
        }
        try {
            String actionType = "click";
            if (action.getType() != null) {
                switch (action.getType()) {
                    case MODEL_LONG_CLICK:
                        actionType = "long_click";
                        break;
                    case MODEL_BACK:
                        actionType = "back";
                        break;
                    default:
                        actionType = "click";
                        break;
                }
            }
            String widgetClass = null;
            String widgetText = null;
            int normX = 0;
            int normY = 0;
            String typedText = null;
            com.android.commands.monkey.ape.tree.GUITreeNode node = action.getResolvedNode();
            if (node != null) {
                try { widgetClass = node.getClassName(); } catch (Exception ignored) {}
                try { widgetText = node.getText(); } catch (Exception ignored) {}
                try {
                    android.graphics.Rect bounds = node.getBoundsInScreen();
                    if (bounds != null) {
                        int cx = (bounds.left + bounds.right) / 2;
                        int cy = (bounds.top + bounds.bottom) / 2;
                        // Normalize to [0, 1000)
                        android.graphics.Rect display = com.android.commands.monkey.ape.AndroidDevice.getDisplayBounds();
                        int w = display != null && display.right > 0 ? display.right : 1080;
                        int h = display != null && display.bottom > 0 ? display.bottom : 1920;
                        normX = (int)((cx * 1000.0) / w);
                        normY = (int)((cy * 1000.0) / h);
                    }
                } catch (Exception ignored) {}
                try {
                    typedText = node.getInputText();
                    if (typedText != null && !typedText.isEmpty()) {
                        actionType = "type_text";
                    }
                } catch (Exception ignored) {}
            }
            String result;
            if (newState != null && _lastState != null && newState.equals(_lastState)) {
                result = "same";
            } else if (newState != null && _stateBeforeLast != null && newState.equals(_stateBeforeLast)) {
                result = "previous screen";
            } else {
                result = "new screen";
            }
            ApePromptBuilder.ActionHistoryEntry entry = new ApePromptBuilder.ActionHistoryEntry(
                    actionType, widgetClass, widgetText, normX, normY, typedText, result);
            _actionHistory.add(entry);
            if (_actionHistory.size() > 5) {
                _actionHistory.remove(0);
            }
        } catch (Exception e) {
            Logger.println("[APE-RV] recordActionHistory error: " + e.getMessage());
        }
    }

    /**
     * INV-EXPL-29: runs one teardown step in isolation. A step that throws costs only its own
     * artifact — the later steps still run, and the exception never escapes to replace the
     * exception that ended the exploration loop.
     */
    private void safeStep(String label, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            Logger.wformat("[APE-RV] tearDown step failed: %s (%s)", label, t);
            t.printStackTrace();
        }
    }

    /**
     * The teardown chain, whose first and last steps belong to the trace.
     *
     * <p><b>{@code flushPendingStep} runs first</b> because it is the step that bounds loss: the
     * record for the step that was in flight when the run ended is still open, and every moment
     * between here and its write is a moment a SIGKILL takes it. It is the only teardown step whose
     * artifact is already half-written, so it goes before the ones that build theirs from scratch.
     *
     * <p><b>{@code runEnd} runs last</b> so that {@code RUN_END} is the last sink record of a normal
     * termination (INV-SNK-09) — a reader that finds it knows nothing else was going to be written.
     * Later stdout lines from other teardown steps may still follow it; it is the last <em>record</em>,
     * not the last line, and nothing validates either (owner decision D5).
     *
     * <p>Both are ordinary {@code safeStep}s, so a failure in either costs only its own artifact and
     * the steps after it still run (INV-EXPL-16/29) — including {@code runEnd} itself, which is why
     * a run whose naming dump throws still ends with a record saying how it ended.
     */
    public void tearDown() {
        safeStep("flushPendingStep", () -> RunContext.current().sink().flushPendingStep());
        safeStep("superTearDown", super::tearDown);
        safeStep("coverageDump", this::dumpCoverage);
        safeStep("actionCounters", () -> actionCounters.print());
        safeStep("activityNodes", () -> getGraph().printActivityNodes());
        safeStep("namingDump", () -> model.getNamingManager().dump());
        safeStep("modelCounters", () -> model.printCounters());
        safeStep("runEnd", () -> {
            RunContext context = RunContext.current();
            context.sink().runEnd(context.terminationReason(), context.terminationDetail(),
                    context.hasLlm() ? context.llmTelemetry().counters() : null);
        });
    }

    /**
     * The teardown step that emits the UI-coverage dump (INV-COV-10). No-op here: only
     * {@code SataAgent} can supply the dump's {@code mopReach} predicate, so it overrides this.
     *
     * <p>Ordering is the whole mechanism, and the boundary is <b>first among the steps that produce
     * output</b>: this step runs before {@code actionCounters}, the first of the free-text dumps.
     * No teardown step writes a file any more — the boundary was the model serialization, then the
     * action-history save, and both are deleted — so the ordering is stated against what remains to
     * be lost. It lands third in the chain, after {@code flushPendingStep} and {@code superTearDown};
     * the first of those writes one already-serialized record to the trace and is itself
     * loss-bounding, which is why it sits outside the boundary rather than violating it. Stating the
     * boundary on chain position among producers of output rather than on a fixed index is what
     * keeps it verifiable as later stages add and remove steps.
     *
     * <p>The reason the property exists is measured. Across 800 {@code aperv} calibration runs the
     * dump was the last instruction of the whole teardown, behind the {@code /sdcard} writes, and
     * 338 of those runs (42.3%) carry no dump — 330 of them cut mid-write. Emitting the dump ahead
     * of the writes recovers 333 of the 338; the remaining 5 never reached teardown at all, and no
     * teardown-side mechanism can reach them.
     *
     * <p>A shutdown hook cannot substitute for this: the trace is the host's {@code adb} stdout,
     * which the harness SIGKILLs and closes, so anything the device writes afterwards has nowhere
     * to land.
     */
    protected void dumpCoverage() {
    }

    public List<ActionRecord> getActionHistory() {
        return this.model.getActionHistory();
    }

    public void updateActionHistory(int index, ActionRecord record) {
        this.model.updateActionHistory(index, record);
    }

    public void appendToActionHistory(long clockTimestamp, Action action) {
        int agentTimestamp = getTimestamp();
        this.model.appendToActionHistory(clockTimestamp, action, agentTimestamp);
        //actionCounters.logEvent(action.getType());
    }

}
