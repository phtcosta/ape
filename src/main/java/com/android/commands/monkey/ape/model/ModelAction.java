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


import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Config;

import android.graphics.Rect;

public class ModelAction extends Action {

    private static final long serialVersionUID = 6905861873801801029L;
    private static final int saturatedVisitedThreshold = 2;

    /**
     * The mechanism that selected this action, attributed at selection time for
     * the per-action step record (A-5, INV-SEL-04). The SATA chain
     * sets {@code SATA} via {@code logActionSelected}; the LLM hooks and the
     * budget-exhausted early-return set their own source explicitly.
     */
    public enum DecisionSource {
        SATA, MOP, LLM, Budget, RESTART, LAUNCHER;

        /**
         * The owner this source names on the wire as {@code dec.src} (design D-B7):
         * {@code budget}, {@code restart}, {@code llm}, {@code launcher}, {@code mop} or
         * {@code sata}.
         */
        public String owner() {
            switch (this) {
            case SATA:
                return "sata";
            case MOP:
                return "mop";
            case LLM:
                return "llm";
            case Budget:
                return "budget";
            case RESTART:
                return "restart";
            case LAUNCHER:
                return "launcher";
            default:
                throw new AssertionError(this);
            }
        }
    }

    /**
     * The selection channel that picked this action, for the {@code pick_channel} field of
     * the step record (A-5, INV-SEL-05). Independent of {@link DecisionSource}: the source names
     * the owner that selected the action, the channel the code path that picked it.
     *
     * <p>The enum is total: every path that is not one of the named channels reports
     * {@code sata_other}, so the field is never absent and never free-form.
     */
    public enum PickChannel {
        ROULETTE_GREEDY("roulette_greedy"),
        ROULETTE_EARLY("roulette_early"),
        LAUNCHER("launcher"),
        LLM("llm"),
        BUFFER("buffer"),
        SATA_OTHER("sata_other"),
        /** A step the MOP coin took (design D-B10). */
        MOP_COIN("mop_coin"),
        /** A step the Budget guard took (design D-B14.17). */
        BUDGET("budget"),
        /** A step the RESTART guard took (design D-B14.17). */
        RESTART("restart");

        private final String label;

        PickChannel(String label) {
            this.label = label;
        }

        /** The value written to the trace. */
        public String getLabel() {
            return label;
        }
    }

    // Resolution information
    private final State state;
    private final Name target;
    private int resovledTimestamp = -1;
    private GUITreeNode[] resolvedNodes;
    private GUITreeNode resolvedNode;
    private GUITreeAction resolvedGUITreeAction;
    private float resolvedSaturation;
    private GUITree resolvedTree;

    // A-5 step-record telemetry: decision source + per-mechanism boosts applied in
    // the most recent adjustActionsByGUITree pass. Boosts are reset each pass.
    private DecisionSource decisionSource = DecisionSource.SATA;
    private PickChannel pickChannel = PickChannel.SATA_OTHER;
    private int mopBoost;
    /**
     * The MOP weight before the {@code distance} mode's tie split (mop-guidance "MopScorer —
     * Priority Boost", design D18): the undivided weight the MOP coin ranks by (D-B10), stored by
     * {@code MopWidgetPass} beside the split {@link #mopBoost}. Equal to {@link #mopBoost} under
     * {@code flag}; 0 when the action has no MOP weight.
     */
    private int mopWeight;
    /** The MOP-reach WTG term ({@code WtgPass}, its only writer — INV-ARCH-10). */
    private int wtgBoost;
    /** The MOP-frontier term ({@code MopFrontierPass}, INV-ARCH-10). */
    private int mopFrontierBoost;
    /**
     * The navigation-frontier term ({@code NavFrontierPass}, STATIC), in its own field so that the
     * STATIC frontier and the MOP {@code WtgPass} never share {@link #wtgBoost}; with one producer
     * per field no source stamp is needed.
     */
    private int navFrontierBoost;
    private int coverageBoost;
    private int menuBoost;
    private int formBoost;

    public ModelAction(State state, ActionType type) {
        this(state, null, type);
    }

    public ModelAction(State state, Name target, ActionType type) {
        super(type);
        this.state = state;
        this.target = target;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((state == null) ? 0 : state.hashCode());
        result = prime * result + ((target == null) ? 0 : target.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        ModelAction other = (ModelAction) obj;
        if (state == null) {
            if (other.state != null)
                return false;
        } else if (!state.equals(other.state))
            return false;
        if (target == null) {
            if (other.target != null)
                return false;
        } else if (!target.equals(other.target))
            return false;
        return true;
    }

    public State getState() {
        return state;
    }

    public boolean isSaturated() {
        if (!requireTarget()) {
            return this.isVisited();
        }
        return this.resolvedSaturation >= 1.0F;
    }

    public boolean isOverAbstracted() {
        if (!requireTarget()) {
            return false;
        }
        if (resolvedNodes == null) {
            throw new RuntimeException("Action is not resolved.");
        }
        return resolvedNodes.length >= Config.actionRefinmentThreshold;
    }

    public Name getTarget() {
        return target;
    }

    public String toString() {
        return super.toString()
                + (target != null ? target : (state != null ? state.toString() : "")) + resolvedInfo();
    }

    protected String resolvedInfo() {
        if (!requireTarget() || resolvedNode == null) {
            return super.resolvedInfo();
        }
        Rect bounds = resolvedNode.getBoundsInScreen();
        return String.format("%s[S=%f][RN=%d][%d,%d,%d,%d][%s]", super.resolvedInfo(), resolvedSaturation,
                resolvedNodes.length, bounds.left, bounds.top, bounds.right, bounds.bottom,
                flatten(resolvedNode.getText()));
    }

    /**
     * Widget text with {@code \n}/{@code \r} replaced by spaces (INV-SEL-07). This string is
     * interpolated into the action's {@code toString()}, which the step record's {@code dec.a}
     * prints, so a multi-line label used to split the line in two: 752 of 166,359 lines in the
     * calibration corpus, distributed unevenly across arms (32–116 each), which biased every
     * {@code decision_source} count by 0.45%. Flattening here fixes all emitters at once.
     */
    private static String flatten(String text) {
        if (text == null) return null;
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    public String toFullString() {
        return toString();
    }

    public boolean isResolvedAt(int timestamp) {
        if (this.resovledTimestamp != timestamp) {
            return false;
        }
        if (!requireTarget()) {
            return true;
        }
        return this.resolvedNode != null;
    }

    public GUITreeAction getResolvedGUITreeAction() {
        return this.resolvedGUITreeAction;
    }

    public void setResolvedGUITreeAction(GUITreeAction resolvedGUITreeAction) {
        this.resolvedGUITreeAction = resolvedGUITreeAction;
    }

    public GUITree getResolvedGUITree() {
        return this.resolvedTree;
    }

    public void resolveAt(int timestamp, int throttle, GUITree tree, GUITreeNode node, GUITreeNode[] nodes) {
        super.setThrottle(throttle);
        this.resovledTimestamp = timestamp;
        this.resolvedTree = tree;
        this.resolvedGUITreeAction = new GUITreeAction(tree, node, this);
        this.resolvedGUITreeAction.setThrottle(throttle);
        if (!requireTarget()) {
            return;
        }
        this.resolvedNode = node;
        this.resolvedNodes = nodes;
        if (nodes.length == 0) {
            throw new IllegalStateException("Fail to resolve a node for this action: " + this);
        }
        if (nodes.length == 1) {
            this.resolvedSaturation = this.isVisited() ? 1.0F : 0.0F;
        } else {
            float total = Math.min(nodes.length, saturatedVisitedThreshold);
            this.resolvedSaturation = Math.min(1.0F, this.visitedCount / total);
        }
        return;
    }

    /**
     * Drops the references into {@code released}, iff this action was last resolved against it.
     *
     * <p>Resolution overwrites, it never clears, so an action last resolved against a tree the
     * model later releases kept that tree — and its whole node subtree — reachable after it had
     * left {@code State.treeHistory} (V24). This is the clearing half, invoked from
     * {@code Model.release} in the same cycle as the {@code GUITreeBuilder} cache sweep.
     *
     * <p>The comparison is reference identity: an action resolved against a different, live tree is
     * left alone, and a re-resolve after this call restores everything.
     *
     * <p>What is deliberately <em>not</em> touched is {@code resolvedSaturation}. It is the one
     * cross-step semantic output of a resolve — {@code isSaturated()} feeds the action filters and
     * the SATA ladder on later steps — so clearing it would turn a retention fix into a decision
     * change (audit row B4, INV-MODEL-19). Priority, the boost fields and the telemetry provenance
     * are left alone for the same reason.
     *
     * <p>The timestamp goes back to {@code -1}, which is the field's own initial value: after this
     * call the action is in exactly the state it was in before its first resolve, rather than in a
     * new third state that readers would have to know about.
     */
    public void releaseResolved(GUITree released) {
        if (this.resolvedTree != released) {
            return;
        }
        this.resovledTimestamp = -1;
        this.resolvedTree = null;
        this.resolvedGUITreeAction = null;
        this.resolvedNode = null;
        this.resolvedNodes = null;
    }

    public DecisionSource getDecisionSource() {
        return this.decisionSource;
    }

    public void setDecisionSource(DecisionSource decisionSource) {
        this.decisionSource = decisionSource;
    }

    public PickChannel getPickChannel() {
        return this.pickChannel;
    }

    /**
     * Stamp the channel that picked this action. Written on every selection path, exactly like
     * {@link #setDecisionSource}: an action object outlives the step that selected it, so a fresh
     * write is what keeps a later step from reporting the previous step's channel.
     */
    public void setPickChannel(PickChannel pickChannel) {
        this.pickChannel = pickChannel;
    }

    /** Reset the per-mechanism boost telemetry before a fresh scoring pass. */
    public void resetBoosts() {
        this.mopBoost = 0;
        this.mopWeight = 0;
        this.wtgBoost = 0;
        this.mopFrontierBoost = 0;
        this.navFrontierBoost = 0;
        this.coverageBoost = 0;
        this.menuBoost = 0;
        this.formBoost = 0;
    }

    public int getMopBoost() { return this.mopBoost; }

    public void setMopBoost(int mopBoost) { this.mopBoost = mopBoost; }

    public int getMopWeight() { return this.mopWeight; }

    public void setMopWeight(int mopWeight) { this.mopWeight = mopWeight; }

    public int getWtgBoost() { return this.wtgBoost; }

    public void setWtgBoost(int wtgBoost) { this.wtgBoost = wtgBoost; }

    public int getMopFrontierBoost() { return this.mopFrontierBoost; }

    public void setMopFrontierBoost(int mopFrontierBoost) { this.mopFrontierBoost = mopFrontierBoost; }

    public int getNavFrontierBoost() { return this.navFrontierBoost; }

    public void setNavFrontierBoost(int navFrontierBoost) { this.navFrontierBoost = navFrontierBoost; }

    public int getCoverageBoost() { return this.coverageBoost; }

    public void setCoverageBoost(int coverageBoost) { this.coverageBoost = coverageBoost; }

    public int getFormBoost() { return this.formBoost; }

    public void setFormBoost(int formBoost) { this.formBoost = formBoost; }

    public int getMenuBoost() { return this.menuBoost; }

    public void setMenuBoost(int menuBoost) { this.menuBoost = menuBoost; }

    public GUITreeNode getResolvedNode() {
        return this.resolvedNode;
    }

    public GUITreeNode[] getResolvedNodes() {
        return this.resolvedNodes;
    }

    public float getResolvedSaturation() {
        if (!requireTarget()) {
            return isVisited() ? 1.0F : 0;
        }
        return Math.min(Math.max(0, this.resolvedSaturation), 1.0F);
    }

}
