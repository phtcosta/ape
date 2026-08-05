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
package com.android.commands.monkey.ape.model;

import static com.android.commands.monkey.ape.utils.Config.activityManagerType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.android.commands.monkey.ape.naming.ActivityNamingManager;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Naming;
import com.android.commands.monkey.ape.naming.NamingFactory;
import com.android.commands.monkey.ape.naming.NamingManager;
import com.android.commands.monkey.ape.naming.StateNamingManager;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.tree.GUITreeBuilder;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.tree.GUITreeTransition;
import com.android.commands.monkey.ape.utils.Logger;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

public class Model implements Serializable {

    static enum ModelEvent {
        NON_DETERMINISTIC_TRANSITION,
        ACTION_REFINEMENT,
        STATE_ABSTRACTION,
    }

    /**
     * A snapshot of one executed action, kept for post-hoc diagnostics.
     *
     * <p>The record holds primitives and strings only — never an {@link Action}, a
     * {@link GUITreeAction}, a {@link GUITree} or a {@link GUITreeNode} (INV-MODEL-18). It used to
     * hold the action and its {@code GUITreeAction}, and through the latter a whole GUI tree: one
     * full tree pinned per executed step for the rest of the run, which is the retainer the history
     * field's own TODO blamed for the {@code OutOfMemoryError} (V11). Every field below is captured
     * in {@link Model#appendToActionHistory}, where the action's resolved objects are still valid
     * because the append happens in the step that resolved them.
     *
     * <p>Conventions for the absent cases, chosen so a reader can tell them apart from real data:
     * {@code stateId} is null for a non-model action, {@code targetXPath} is null for a targetless
     * one, and {@code treeId}/{@code treeTimestamp} are -1 when the action carried no resolved
     * {@code GUITreeAction} — the crash records, which were appended with a null one before this
     * change too.
     */
    public static class ActionRecord {
        public final long clockTimestamp;
        public final int agentTimestamp;
        public final String actionType;
        public final String stateId;
        public final String targetXPath;
        public final int treeId;
        public final int treeTimestamp;
        public final int throttle;

        public ActionRecord(long clockTimestamp, int agentTimestamp, String actionType, String stateId,
                String targetXPath, int treeId, int treeTimestamp, int throttle) {
            this.clockTimestamp = clockTimestamp;
            this.agentTimestamp = agentTimestamp;
            this.actionType = actionType;
            this.stateId = stateId;
            this.targetXPath = targetXPath;
            this.treeId = treeId;
            this.treeTimestamp = treeTimestamp;
            this.throttle = throttle;
        }
    }

    /**
     * The rich {@code (ModelAction, GUITreeAction)} pair {@link Model#actionHistory} keeps at
     * depth 1 so that {@code StatefulAgent.recoverCurrentState} can restore a lost current state.
     *
     * <p>This is the only rich retention left in the history subsystem, and it retains at most one
     * GUI tree — one the owning state's {@code treeHistory} retains anyway.
     */
    public static class RecoveryPoint implements Serializable {

        private static final long serialVersionUID = 1L;

        public final ModelAction modelAction;
        public final GUITreeAction guiAction;

        public RecoveryPoint(ModelAction modelAction, GUITreeAction guiAction) {
            this.modelAction = modelAction;
            this.guiAction = guiAction;
        }
    }

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    // the abstraction function of the model
    // the model use namingManager to decide the specific abstraction function for a given GUI tree.
    protected NamingManager namingManager;
    // the state machine
    protected Graph graph;
    // A snapshot per executed action, for post-hoc diagnostics; nothing reads it at runtime. It is
    // O(steps) records of primitives and strings — hundreds of KB over a 600 s run — and retains no
    // model object, so it is no longer the OOM retainer its predecessor was (V11, INV-MODEL-18).
    protected List<ActionRecord> actionHistory = new ArrayList<ActionRecord>();

    // The depth-1 rich recovery point that replaces the backward scan over the history.
    // recoverCurrentState used to walk the rich records from the end for the most recent
    // model-action record, stopping at a more recent record that canStartApp(). The snapshots above
    // cannot serve that, so the same predicate is maintained incrementally here on every append:
    // a start action blocks recovery, a model action becomes the point and unblocks it, and
    // anything else leaves both alone. The precedence matches the scan's — canStartApp is tested
    // before isModelAction — because an action that is both would have stopped the scan.
    protected RecoveryPoint recoveryPoint;
    protected boolean recoveryBlocked;

    protected int version;

    protected EnumCounters<ModelEvent> eventCounters = new EnumCounters<ModelEvent>() {

        /**
         * 
         */
        private static final long serialVersionUID = 1L;

        @Override
        public ModelEvent[] getEnums() {
            return ModelEvent.values();
        }

    };

    public Model(NamingManager nm) {
        this(new Graph(), nm);
    }

    public synchronized List<ActionRecord> getActionHistory() {
        return Collections.unmodifiableList(this.actionHistory);
    }

    /**
     * The rich pair to recover a lost current state from, or null if none was ever appended.
     *
     * <p>Callers must consult {@link #isRecoveryBlocked()} as well: a point can be present and
     * blocked, which is the scan's "a start action is more recent than the last model action" case.
     */
    public synchronized RecoveryPoint getRecoveryPoint() {
        return this.recoveryPoint;
    }

    public synchronized boolean isRecoveryBlocked() {
        return this.recoveryBlocked;
    }

    /**
     * Replaces the recovery point, leaving the blocked flag alone.
     *
     * <p>Used by {@code StatefulAgent.updateModel} to remap the point's action reference across a
     * naming-refinement rebuild — the append rules are the only other writer.
     */
    public synchronized void setRecoveryPoint(RecoveryPoint recoveryPoint) {
        this.recoveryPoint = recoveryPoint;
    }

    public synchronized void appendToActionHistory(long clockTimestamp, Action action, int agentTimestamp) {
        GUITreeAction guiAction = null;
        String stateId = null;
        if (action.isModelAction()) {
            ModelAction modelAction = (ModelAction) action;
            guiAction = modelAction.getResolvedGUITreeAction();
            State state = modelAction.getState();
            stateId = state == null ? null : state.getGraphId();
        }
        // A crash record is appended with no resolved GUI action; the tree fields say so with -1
        // rather than guessing, and the throttle falls back to the action's own.
        int treeId = -1;
        int treeTimestamp = -1;
        int throttle = action.getThrottle();
        if (guiAction != null) {
            GUITree tree = guiAction.getGUITree();
            treeId = tree.getId();
            treeTimestamp = tree.getTimestamp();
            throttle = guiAction.getThrotlle();
        }
        Name target = action.getTarget();
        this.actionHistory.add(new ActionRecord(clockTimestamp, agentTimestamp, action.getType().name(),
                stateId, target == null ? null : target.toXPath(), treeId, treeTimestamp, throttle));
        updateRecoveryPoint(action, guiAction);
    }

    /**
     * The three rules that keep the recovery point equivalent to the backward scan it replaced.
     *
     * <p>The scan's outcome is "the most recent model-action record, unless a {@code canStartApp()}
     * record is more recent" — so a start action blocks (rule 1), a model action becomes the point
     * and clears the block (rule 2), and everything else — fuzz events, crash records, lifecycle
     * events — is invisible to it (rule 3), exactly as the scan skipped them.
     */
    private void updateRecoveryPoint(Action action, GUITreeAction guiAction) {
        if (action.canStartApp()) {
            this.recoveryBlocked = true;
        } else if (action.isModelAction()) {
            this.recoveryPoint = new RecoveryPoint((ModelAction) action, guiAction);
            this.recoveryBlocked = false;
        }
    }

    public Model(Graph graph) {
        this.graph = graph;
        if (activityManagerType.equals("activity")) {
            this.namingManager = new ActivityNamingManager(new NamingFactory());
        } else {
            this.namingManager = new StateNamingManager(new NamingFactory());
        }
    }

    public Model(Graph graph, NamingManager namingManager) {
        this.graph = graph;
        this.namingManager = namingManager;
    }

    /**
     * Rebuild the whole model after abstraction functions of some GUI tree are updated.
     * @return
     */
    public Model rebuild() {
        long begin = SystemClock.elapsedRealtimeNanos();
        Logger.iprintln("Start rebuilding model... ");
        Set<State> statesToRemove = new HashSet<>();
        List<GUITreeTransition> treeTransitions = new ArrayList<>();
        Set<StateTransition> stateTransitions = new HashSet<>();
        List<GUITree> affectedTrees = new ArrayList<>();
        {
            // Remove model
            // long b = SystemClock.elapsedRealtimeNanos();
            for (State state : graph.getStates()) {
                for (GUITree tree : state.getGUITrees()) {
                    Naming naming = tree.getCurrentNaming();
                    {
                        Naming check = namingManager.getNaming(tree);
                        if (naming != check) {
                            statesToRemove.add(state);
                            break;
                        }
                    }
/*                    {
                        Naming check = namingManager.getNaming(tree, tree.getActivityName(), tree.getDocument());
                        if (naming != check) {
                            statesToRemove.add(state);
                            break;
                        }
                    }*/
                }
            }
            for (State state : statesToRemove) {
                Logger.iformat("> Removing state %s", state);
                affectedTrees.addAll(state.getGUITrees());
                graph.remove(state, stateTransitions);
            }
            treeTransitions.addAll(collectReplayTreeTransitions(stateTransitions));
            Collections.sort(treeTransitions, new Comparator<GUITreeTransition>() {

                @Override
                public int compare(GUITreeTransition o1, GUITreeTransition o2) {
                    return (int) (o1.getSource().getTimestamp() - o2.getSource().getTimestamp());
                }

            });
            long e = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("> Removing (%d) old states and (%d) transitions finished in %d ms.", statesToRemove.size(),
                    stateTransitions.size(), TimeUnit.NANOSECONDS.toMillis(e - begin));
            for (State state : statesToRemove) {
                Logger.iformat(">> state: %s", state);
            }
            for (StateTransition st : stateTransitions) {
                Logger.iformat(">> transition: %s", st.toShortString());
            }
        }
        {
            long b = SystemClock.elapsedRealtimeNanos();
            graph.disableGraphEvents();
            graph.setRebuilding(true);
            graph.setVerbose(false);
            version++;
            graph.setVersion(version);
            Collections.sort(affectedTrees, new Comparator<GUITree>() {

                @Override
                public int compare(GUITree o1, GUITree o2) {
                    return o1.getTimestamp() - o2.getTimestamp();
                }

            });
            for (GUITree tree : affectedTrees) {
                getState(rebuild(tree));
            }
            for (GUITreeTransition tt : treeTransitions) {
                GUITree sourceTree = tt.getSource();
                GUITree targetTree = tt.getTarget();
                State source = sourceTree.getCurrentState();
                if (source == null) { // a GUI tree has been rebuilt by somebody
                    throw new NullPointerException("Source state should not be null for #" + sourceTree.getTimestamp());
                }
                State target = targetTree.getCurrentState();
                if (target == null) {
                    throw new NullPointerException("Target state should not be null for #" + targetTree.getTimestamp());
                }
                if (statesToRemove.contains(source)) {
                    throw new IllegalStateException("State " + source + " has been removed.");
                }
                if (statesToRemove.contains(target)) {
                    throw new IllegalStateException("State " + target + " has been removed.");
                }
                ModelAction action = rebuild(sourceTree, source, tt.getAction()).getModelAction();
                graph.addTransition(source, action, target, tt);
            }
            graph.rebuildHistory();
            graph.setRebuilding(false);
            graph.setVerbose(true);
            graph.enableGraphEvents();
            long e = SystemClock.elapsedRealtimeNanos();
            Logger.iformat("> Readding transitions finished in %d ms.", TimeUnit.NANOSECONDS.toMillis(e - b));
        }
        long end = SystemClock.elapsedRealtimeNanos();
        Logger.iformat(
                "Rebuilding model finished in %d ms, removed %d states and %d state transitions, and rebuild %d tree transitions.",
                TimeUnit.NANOSECONDS.toMillis(end - begin), statesToRemove.size(), stateTransitions.size(),
                treeTransitions.size());
        return this;
    }

    /**
     * Flattens the removed edges' {@code GUITreeTransition}s for the rebuild replay. Extracted
     * from {@link #rebuild()} so the collection contract is JVM-testable (the full rebuild needs
     * the Android runtime).
     *
     * <p>INV-MODEL-16: ephemeral edges are excluded — an ephemeral action is never a member of
     * {@code State.getActions()} (INV-MODEL-14), so the replay's {@code state.getAction(type)}
     * re-anchor would throw {@code No such action [MODEL_LLM_TAP]}. Their tree transitions are
     * also purged from the graph's history, otherwise {@code rebuildHistory} would re-insert the
     * removed edge (dangling) into the state-transition history.
     */
    List<GUITreeTransition> collectReplayTreeTransitions(Collection<StateTransition> stateTransitions) {
        List<GUITreeTransition> treeTransitions = new ArrayList<>();
        int ephemeralDropped = 0;
        for (StateTransition st : stateTransitions) {
            if (st.getAction().isEphemeral()) {
                graph.removeFromTreeHistory(st.getGUITreeTransitions());
                ephemeralDropped++;
                continue;
            }
            treeTransitions.addAll(st.getGUITreeTransitions());
        }
        if (ephemeralDropped > 0) {
            Logger.iformat("[APE-RV] Rebuild: dropped %d ephemeral edge(s) from replay (INV-MODEL-16)",
                    ephemeralDropped);
        }
        return treeTransitions;
    }

    public List<GUITreeTransition> getGUITreeTransitions(StateTransition st) {
        return st.getGUITreeTransitions();
    }

    public GUITreeTransition getLatestGUITreeTransition(StateTransition st) {
        List<GUITreeTransition> ret = getGUITreeTransitions(st);
        if (ret == null || ret.isEmpty()) {
            return null;
        }
        return ret.get(ret.size() - 1);
    }

    /**
     * set the target for each GUI tree node
     * return a new GUI tree.
     * 
     * @param tree
     * @return
     */
    public GUITree rebuild(GUITree tree) {
        Logger.iprintln("> rebuilding tree #" + tree.getTimestamp());
        GUITreeBuilder treeBuilder = new GUITreeBuilder(namingManager, tree);
        return treeBuilder.getGUITree();
    }

    private GUITreeAction rebuild(GUITree tree, State state, GUITreeAction treeAction) {
        if (tree.getCurrentState() == null || state == null || tree.getCurrentState() != state) {
            throw new IllegalStateException();
        }
        ActionType type = treeAction.getActionType();
        ModelAction action;
        if (type.requireTarget()) {
            Name widget = treeAction.getGUITreeNode().getXPathName();
            if (!state.containsTarget(widget)) {
                Logger.wprintln("Given tree #" + tree.getTimestamp());
                Logger.wprintln("Action tree #" + treeAction.getGUITree().getTimestamp());
            }
            action = state.getAction(widget, type);
        } else {
            action = state.getAction(type);
        }
        treeAction.rebuild(action);
        return treeAction;
    }

    public StateTransition addTransition(State source, ModelAction action, State target, GUITree sourceTree,
            GUITreeAction treeAction, GUITree targetTree) {
        return graph.addTransition(source, action, target, sourceTree, treeAction, targetTree);
    }

    public Model resolveNonDeterministicTransitions(StateTransition edge) {
        if (edge.getType() == StateTransitionVisitType.NEW_ACTION_TARGET) {
            if (edge.getAction().isBack()) {
                return null; // back should be deterministic.
            }
            if (edge.getAction().isEphemeral()) {
                // INV-MODEL-14: an ephemeral action's outcome follows its own payload (a raw
                // coordinate) or a non-deterministic surface such as a game canvas. No naming
                // refinement can separate those, so refining here would rebuild the model chasing a
                // difference no abstraction expresses.
                return null;
            }
            int version = this.version;
            long begin = SystemClock.elapsedRealtimeNanos();
            namingManager.resolveNonDeterminism(this, edge);
            long end = SystemClock.elapsedRealtimeNanos();
            if (version == this.version) {
                return null;
            }
            eventCounters.logEvent(ModelEvent.NON_DETERMINISTIC_TRANSITION);
            Logger.iformat("Eliminating non-deterministic transitions takes %s ms.",
                    TimeUnit.NANOSECONDS.toMillis(end - begin));
            return this;
        }
        return null;
    }

    /**
     * 
     * @param st
     * @return
     */
    public StateTransition update(StateTransition st) {
        return update(st, st.getLastGUITreeTransition());
    }

    public StateTransition update(StateTransition st, GUITreeTransition tt) {
        if (!isStale(st.getSource()) && !isStale(st.getTarget())) {
            return st;
        }
        return graph.getStateTransition(tt);
    }

    public ModelAction update(ModelAction action, GUITreeAction guiAction) {
        if (action.isEphemeral()) {
            // INV-MODEL-16: an ephemeral action's identity is its payload (INV-MODEL-13), never
            // State.getActions() membership — the membership lookup below would throw
            // "No such action" for it. Keep the reference payload-bound.
            return action;
        }
        State state = action.getState();
        if (isStale(state)) {
            GUITree tree = guiAction.getGUITree();
            state = tree.getCurrentState();
            if (isStale(state)) {
                state = getState(tree);
            }
            if (isStale(state)) {
                throw new IllegalStateException("Sanity check failed!");
            }
            GUITreeNode node = guiAction.getGUITreeNode();
            ActionType type = action.getType();
            if (action.requireTarget()) {
                Name widget = node.getXPathName();
                return state.getAction(widget, type);
            } else {
                return state.getAction(action.getType());
            }
        }
        return action;
    }

    public ModelAction update(ModelAction action) {
        State state = action.getState();
        if (isStale(state)) {
            GUITree tree = state.getLatestGUITree();
            state = tree.getCurrentState();
            if (isStale(state)) {
                state = getState(tree);
            }
            GUITreeNode node = null;
            ActionType type = action.getType();
            if (action.requireTarget()) {
                node = action.getResolvedNode();
                if (node == null) {
                    return null;
                }
                if (!tree.contains(node)) {
                    return null;
                }
                Name widget = node.getXPathName();
                return state.getAction(widget, type);
            } else {
                return state.getAction(action.getType());
            }
        }
        return action;
    }

    public State update(GUITree tree) {
        State state = tree.getCurrentState();
        if (isStale(state)) {
            return getState(tree);
        }
        return state;
    }

    /**
     * A state or action has been removed from the graph.
     * @param state
     * @return
     */
    public boolean isStale(State state) {
        if (state == null) {
            return true;
        }
        return !graph.contains(state);
    }

    public Graph getGraph() {
        return graph;
    }

    public GUITree buildGUITree(ComponentName activity, AccessibilityNodeInfo rootInfo, Bitmap bitmap) {
        GUITreeBuilder treeBuilder = new GUITreeBuilder(namingManager, activity, rootInfo, bitmap);
        GUITree guiTree = treeBuilder.getGUITree();
        return guiTree;
    }

    public State getState(ComponentName activity, AccessibilityNodeInfo rootInfo, Bitmap bitmap) {
        return checkAndAddStateData(buildGUITree(activity, rootInfo, bitmap));
    }

    public State getState(GUITree guiTree) {
        return checkAndAddStateData(guiTree);
    }

    private State checkAndAddStateData(GUITree tree) {
        Naming naming = tree.getCurrentNaming();
        StateKey stateKey = GUITreeBuilder.getStateKey(naming, tree);
        State state = graph.getOrCreateState(stateKey);
        state.append(tree);
        Logger.iformat("Create state %s for GUI tree %s", state, tree);
        return state;
    }

    public NamingManager getNamingManager() {
        return namingManager;
    }

    public StateTransition getStateTransition(GUITreeTransition tt) {
        return graph.getStateTransition(tt);
    }

    public Iterator<GUITree> getGUITrees() {
        return graph.getGUITrees();
    }

    public Model actionRefinement(ModelAction action) {
        int version = this.version;
        long begin = SystemClock.elapsedRealtimeNanos();
        namingManager.actionRefinement(this, action);
        long end = SystemClock.elapsedRealtimeNanos();
        if (version == this.version) {
            return null;
        }
        eventCounters.logEvent(ModelEvent.ACTION_REFINEMENT);
        Logger.iformat("Action refinement takes %s ms.", TimeUnit.NANOSECONDS.toMillis(end - begin));
        return this;
    }

    public Model stateAbstraction(Naming naming, State target, Naming parentNaming, Set<State> states) {
        int version = this.version;
        long begin = SystemClock.elapsedRealtimeNanos();
        namingManager.stateAbstraction(this, naming, target, parentNaming, states);
        long end = SystemClock.elapsedRealtimeNanos();
        if (version == this.version) {
            return null;
        }
        Logger.iformat("State abstraction takes %s ms.", TimeUnit.NANOSECONDS.toMillis(end - begin));
        eventCounters.logEvent(ModelEvent.STATE_ABSTRACTION);
        return this;
    }

    public void printCounters() {
        this.eventCounters.print();
    }

    /**
     * Releases everything the model holds on behalf of a removed GUI tree.
     *
     * <p>Beyond the naming manager's own release, this sweeps the actions of the tree's owning
     * state and drops their references into it (V24). Both release call sites —
     * {@code StatefulAgent.checkAndRefreshNewState} and replay's {@code refreshNewState} — reach
     * here already, so the sweep covers both with no new wiring, in the same cycle as the
     * {@code GUITreeBuilder} cache sweep.
     *
     * <p>The state is null-guarded: a tree can be released before it has been attached to one.
     */
    public void release(GUITree removed) {
        this.namingManager.release(removed);
        State owner = removed.getCurrentState();
        if (owner == null) {
            return;
        }
        for (ModelAction action : owner.getActions()) {
            action.releaseResolved(removed);
        }
    }

}
