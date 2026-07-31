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

import org.json.JSONException;
import org.json.JSONObject;

import com.android.commands.monkey.ApeRRFormatter;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.NamerFactory;
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
     * the per-action {@code [APE-STEP]} telemetry (A-5, INV-SEL-04). The SATA chain
     * sets {@code SATA} via {@code logActionSelected}; the LLM hooks and the
     * budget-exhausted early-return set their own source explicitly.
     */
    public enum DecisionSource {
        SATA, MOP, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget, Form
    }

    /**
     * The selection channel that picked this action, for the {@code pick_channel} field of
     * {@code [APE-STEP]} (A-5, INV-SEL-05). Independent of {@link DecisionSource}: the source names
     * the mechanism whose boost was largest, the channel names the code path that consumed it.
     * Measured motivation — the unvisited-MOP short-circuit yields 15.1% new states while the
     * MOP-boosted roulette yields 1.4%, and aggregating them under {@code decision_source=MOP}
     * mixes mechanism with noise.
     *
     * <p>The enum is total: every path that is not one of the named channels reports
     * {@code sata_other}, so the field is never absent and never free-form.
     */
    public enum PickChannel {
        SHORT_CIRCUIT_UNVISITED("short_circuit_unvisited"),
        SHORT_CIRCUIT_0STEP("short_circuit_0step"),
        ROULETTE_GREEDY("roulette_greedy"),
        ROULETTE_EARLY("roulette_early"),
        LAUNCHER("launcher"),
        LLM("llm"),
        BUFFER("buffer"),
        SATA_OTHER("sata_other");

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

    // A-5 [APE-STEP] telemetry: decision source + per-mechanism boosts applied in
    // the most recent adjustActionsByGUITree pass. Boosts are reset each pass.
    private DecisionSource decisionSource = DecisionSource.SATA;
    private PickChannel pickChannel = PickChannel.SATA_OTHER;
    private int mopBoost;
    private int wtgBoost;
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
                resolvedNode.getText());
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
        this.wtgBoost = 0;
        this.coverageBoost = 0;
        this.menuBoost = 0;
        this.formBoost = 0;
    }

    public int getMopBoost() { return this.mopBoost; }

    public void setMopBoost(int mopBoost) { this.mopBoost = mopBoost; }

    public int getWtgBoost() { return this.wtgBoost; }

    public void setWtgBoost(int wtgBoost) { this.wtgBoost = wtgBoost; }

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

    public JSONObject toJSONObject() throws JSONException {
        JSONObject jAction = super.toJSONObject();
        if (requireTarget()) {
            String xpath = getTarget().toXPath();
            jAction.put("target", xpath);
        }
        GUITreeNode node = getResolvedNode();
        if (node != null) {
            Name full = NamerFactory.fullNamer().naming(node);
            jAction.put("full", full.toXPath());
            Rect bounds = node.getBoundsInScreen();
            jAction.put("bounds", ApeRRFormatter.formatRect(bounds));
            String inputText = node.getInputText();
            if (inputText != null) {
                jAction.put("inputText", inputText);
            }
        }
        GUITreeAction guiAction = this.resolvedGUITreeAction;
        if (guiAction != null) {

        }
        return jAction;
    }
}
