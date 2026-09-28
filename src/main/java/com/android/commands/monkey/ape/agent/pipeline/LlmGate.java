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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import android.graphics.Rect;

/**
 * What every LLM stage does around its engine call: the precondition before, the acceptance after;
 * and the classifier of the step's screen that the precondition and the step record share.
 *
 * <p>Both halves were written out three times in the ladder, once per hook, which is what made them
 * worth a name. The precondition in particular — {@code actionBufferSize() == 0 &&
 * newState.getActions().size() > 2} — was verbatim at three sites, so a change to one of them was a
 * change to the policy at one hook and not the others, silently.
 *
 * <p>The third conjunct of those three copies, a null test on the run's LLM, is not here: an LLM
 * stage exists only on a plan carrying its feature, and such a plan builds the units. The guard
 * dissolved into assembly rather than moving (INV-DP-03).
 *
 * <p>The class's second role is to classify the screen: {@link #isOpaque} reads the abstract
 * state, {@link #hasDynamicRegion} reads the step's tree. The gate routes on them and the agent
 * writes them as {@code dec.opaque} and {@code dec.dyn} on every arm, so the routing and the
 * record cannot disagree.
 */
public final class LlmGate {

    private LlmGate() {
    }

    /**
     * The least share of the root's area a {@linkplain #hasDynamicRegion dynamic region} must
     * cover.
     *
     * <p>A constant, not a plan key (design D9): a key would enter the plan values and change
     * every LLM arm's digest, and there is no measurement to set it by. The one measured surface,
     * a LibGDX canvas, covers the whole root; half is a margin under that and above any decorative
     * view. {@code dec.dyn} records the verdict on every arm, so a later calibration has data.
     */
    static final double DYNAMIC_REGION_MIN_AREA = 0.5;

    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";
    private static final String WEB_VIEW = "android.webkit.WebView";
    private static final String PLAIN_VIEW = "android.view.View";

    /**
     * Whether this step is the LLM's to redirect at all.
     *
     * <p>A non-empty action buffer closes it unconditionally. The agent is then mid-sequence,
     * executing a path it already committed to, and redirecting there would abandon the path rather
     * than choose within it — which is as true on an opaque screen as on any other.
     *
     * <p>With the buffer empty, a state offering more than two actions opens it. Two actions or
     * fewer is, for the chain, a screen with nothing to reason about: the model's answer would cost
     * a round trip to pick from a set the chain picks from just as well. That premise fails on an
     * {@linkplain #isOpaqueDynamic opaque step whose tree holds a dynamic region} — a game canvas
     * — where the two actions are {@code MODEL_BACK} and {@code MODEL_MENU}, the chain can only
     * leave or open the menu, and the model, which sees the screenshot and can answer with an
     * off-tree tap, is the one decider with anything to offer. So when the plan turns opaque
     * routing on, such a step opens it too. An opaque step without a dynamic region — a stuck
     * progress dialog, a camera preview, a splash — does not: the model has nothing there to touch
     * (design D8).
     *
     * <p>With {@code opaqueEnabled} false the result is the size rule alone, for every input, and
     * the tree is never walked — the property that keeps a plan without opaque routing on the
     * gate, and therefore on the draw sequence, it always had (INV-RTR-22).
     *
     * @param ctx the step being decided
     * @param opaqueEnabled whether the plan turns opaque routing on, injected at assembly
     * @return whether an LLM stage may consult its trigger predicate
     */
    static boolean allows(StepContext ctx, boolean opaqueEnabled) {
        if (ctx.actionBufferSize() != 0) {
            return false;
        }
        return ctx.newState().getActions().size() > 2 || opaqueRouted(ctx, opaqueEnabled);
    }

    /**
     * Whether the step is routed as opaque: the plan turns opaque routing on and the step is opaque
     * with a dynamic region. {@link #allows} opens on it, and the three LLM stages pass it to the
     * engine as {@code edgeBandsOff} (INV-RTR-27).
     *
     * <p>{@code opaqueEnabled} is tested first, so with it false the tree is never read
     * (INV-RTR-22).
     *
     * @param ctx the step being decided
     * @param opaqueEnabled whether the plan turns opaque routing on, injected at assembly
     * @return {@code opaqueEnabled && isOpaqueDynamic(ctx)}
     */
    static boolean opaqueRouted(StepContext ctx, boolean opaqueEnabled) {
        return opaqueEnabled && isOpaqueDynamic(ctx);
    }

    /**
     * Whether the step is opaque and its tree holds a dynamic region — the opaque-routing trigger
     * (INV-RTR-26).
     *
     * <p>The state is tested first, so the tree is walked only on an opaque step.
     *
     * @param ctx the step being decided
     * @return {@code isOpaque(newState) && hasDynamicRegion(newGUITree)}
     */
    static boolean isOpaqueDynamic(StepContext ctx) {
        return isOpaque(ctx.newState()) && hasDynamicRegion(ctx.newGUITree());
    }

    /**
     * Whether {@code state} is opaque: it offers at least one action and none of them needs a
     * target (INV-RTR-21).
     *
     * <p>This is the explorer's own knowledge of the screen, read from the abstract state and from
     * nothing else. It does not tell a LibGDX canvas from a camera preview or a loading screen with
     * an empty tree, which is why it is not the whole routing trigger: the gate also asks
     * {@link #hasDynamicRegion}. A screen whose one widget yields no model action is opaque; a
     * one-button dialog is not, even at two actions.
     *
     * <p>An empty action list is not opaque: {@code CoordinateMapper.map} discards every answer on
     * it before reaching the tap path, so a call made there would be thrown away by construction.
     *
     * <p>The gate and the step record's {@code dec.opaque} both read this method, so the two cannot
     * disagree about which steps were opaque.
     *
     * @param state the state the step is decided on
     * @return whether the state offers only targetless actions
     */
    public static boolean isOpaque(State state) {
        List<ModelAction> actions = state.getActions();
        if (actions.isEmpty()) {
            return false;
        }
        for (ModelAction action : actions) {
            if (action.requireTarget()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code tree} holds a dynamic region: a surface the application draws and takes touch
     * on, which the tree does not describe (INV-RTR-26, design D8).
     *
     * <p>A node qualifies when it lies outside every subtree rooted at a {@code ComposeView} or a
     * {@code WebView} and
     * <ul>
     * <li>its class is exactly {@code android.view.View} — {@code SurfaceView},
     * {@code GLSurfaceView}, {@code TextureView} and custom views do not override their
     * accessibility class name, so this is how a surface reaches the tree, while widgets report
     * their own class;</li>
     * <li>it has no children, and empty text and content description — the content is drawn, not
     * described;</li>
     * <li>it is focusable, natively clickable or long-clickable — with not-important views excluded
     * from the tree, that is what brings a surface into it at all, and it means the view takes
     * input. Clickability {@code GUITreeBuilder.patchGUITree} copied onto the child of a clickable
     * container ({@link GUITreeNode#isPatchedClickable}) does not count: the child does not take
     * touch itself;</li>
     * <li>its bounds, intersected with the root's, cover at least {@link #DYNAMIC_REGION_MIN_AREA}
     * of the root's area — the screen's content, not an icon.</li>
     * </ul>
     * Compose semantics nodes report {@code android.view.View} too, and an HTML canvas becomes one
     * inside a WebView, which is why those subtrees are not entered.
     *
     * <p>The verdict is the rule's, not a truth about the screen: it is what the gate routes on and
     * what {@code dec.dyn} records. One pass over the tree, no IPC. A known false positive is not
     * excluded: Flutter semantics nodes also report {@code android.view.View}, so an unlabeled,
     * focusable, full-screen Flutter node passes. No Flutter application is in the corpus, and
     * {@code dec.dyn} would show one if it appeared.
     *
     * @param tree the step's tree; may be null
     * @return {@code false} for a null tree or a root with empty bounds; otherwise whether some
     *         node satisfies every clause above
     */
    public static boolean hasDynamicRegion(GUITree tree) {
        if (tree == null) {
            return false;
        }
        GUITreeNode root = tree.getRootNode();
        if (root == null) {
            return false;
        }
        Rect rootBounds = root.getBoundsInScreen();
        long rootArea = area(rootBounds.left, rootBounds.top, rootBounds.right, rootBounds.bottom);
        if (rootArea == 0) {
            return false;
        }
        Deque<GUITreeNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            GUITreeNode node = pending.pop();
            String className = node.getClassName();
            if (COMPOSE_VIEW.equals(className) || WEB_VIEW.equals(className)) {
                continue;
            }
            if (node.getChildCount() == 0) {
                if (isRegion(node, rootBounds, rootArea)) {
                    return true;
                }
                continue;
            }
            for (Iterator<GUITreeNode> it = node.getChildren(); it.hasNext();) {
                pending.push(it.next());
            }
        }
        return false;
    }

    private static boolean isRegion(GUITreeNode leaf, Rect rootBounds, long rootArea) {
        if (!PLAIN_VIEW.equals(leaf.getClassName()) || !isEmpty(leaf.getText())
                || !isEmpty(leaf.getContentDesc())) {
            return false;
        }
        boolean nativelyClickable = leaf.isClickable() && !leaf.isPatchedClickable();
        if (!leaf.isFocusable() && !nativelyClickable && !leaf.isLongClickable()) {
            return false;
        }
        Rect b = leaf.getBoundsInScreen();
        long covered = area(Math.max(b.left, rootBounds.left), Math.max(b.top, rootBounds.top),
                Math.min(b.right, rootBounds.right), Math.min(b.bottom, rootBounds.bottom));
        return covered >= DYNAMIC_REGION_MIN_AREA * rootArea;
    }

    private static long area(int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return 0;
        }
        return (long) (right - left) * (bottom - top);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * Stamps an accepted answer with its provenance and resolves it if it needs resolving.
     *
     * @param result the action the engine returned, never null
     * @param resolveSynthesizedTap the agent's per-state resolution, applied only to the synthesized
     *        tap — see {@link #requiresSynthesizedResolution}
     * @return {@code result}, stamped
     */
    public static ModelAction accept(ModelAction result, Consumer<ModelAction> resolveSynthesizedTap) {
        result.setDecisionSource(ModelAction.DecisionSource.LLM);
        result.setPickChannel(ModelAction.PickChannel.LLM);
        if (requiresSynthesizedResolution(result)) {
            resolveSynthesizedTap.accept(result);
        }
        return result;
    }

    /**
     * True when an LLM-routed action must be resolved against the current state before dispatch
     * (llm-coordinate-tap, D7).
     *
     * <p>Only the synthesized off-tree tap ({@link ActionType#MODEL_LLM_TAP}) needs it: the tap is
     * {@code isModelAction()==true} yet absent from {@code State.actions}, so it never passed the
     * per-state resolution pass and its {@code GUITreeAction} is null — which the agent's
     * {@code resolveNewAction()} would {@code assertNotNull} on. A matched widget action returned on
     * the same path is already resolved and MUST NOT be re-resolved, which would re-pick its node, so
     * this returns {@code false} for every widget/targetless type other than the synthesized tap.
     * Pure, so the guard decision is unit-testable without a live State or graph.
     *
     * @param result the action the engine returned
     * @return whether it needs the per-state resolution pass
     */
    public static boolean requiresSynthesizedResolution(ModelAction result) {
        return result.getType() == ActionType.MODEL_LLM_TAP;
    }
}
