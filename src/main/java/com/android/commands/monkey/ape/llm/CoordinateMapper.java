package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns what the model said into what the agent can execute, and remembers what did not work.
 *
 * <p>Two responsibilities live together here because they act on the same thing. The first is
 * geometry: a normalized coordinate becomes a pixel, a pixel becomes the widget action it names,
 * and when it names none, either an off-tree tap or nothing. The second is the dead-pair ban, which
 * is the memory of which of those answers already executed without producing a new state. They are
 * one unit because the ban's key material is exactly what the mapping produced — separating them
 * would mean computing the key twice or passing the mapping's internals across a boundary.
 *
 * <p><b>A banned answer is a refused answer, not a failed pipeline.</b> The check runs after the
 * mapping, so the screenshot, the call and the parse have all already been paid for; what the ban
 * changes is which action executes, never the latency. It leaves through the caller's {@code null}
 * path exactly as {@code no_match} does, and the breaker still records success (INV-RTR-15/16).
 */
public final class CoordinateMapper {

    // B1 dead-pair ban (INV-RTR-15): a pair dies after five executions whose recorded outcome had
    // new_state=false. The threshold is uniform over every widget class the ban covers; the class
    // that is not covered — input-capable — is exempt outright rather than given a larger k, since
    // no finite threshold reproduces an exemption. Measured on the 84 cal_a1 calibration runs: k=5
    // refuses 27.5% of LLM decisions (k=3 refuses 37.6%, breaking the 30% ceiling that keeps the
    // arm interpretable as "the LLM exploring" rather than as its SATA fallback).
    private static final int DEAD_PAIR_STRIKES = 5;

    private final int snapTolerancePx;
    private final double boundaryTopPct;
    private final double boundaryBottomPct;

    // Per-run, in-memory strike record of the dead-pair ban: ban key → unproductive executions of
    // that pair. No persistence and no cross-run state — a run's bans die with the run — and no size
    // cap, since it is bounded by the number of LLM decisions the run executes.
    private final Map<String, Integer> deadPairStrikes = new HashMap<>();

    /**
     * @param llm the plan's LLM parameters, from which the snap floor and the two boundary bands
     *        come; nothing here reads static configuration during the run
     */
    public CoordinateMapper(RunSpec.LlmParams llm) {
        this.snapTolerancePx = llm.integer("ape.llmSnapTolerancePx");
        this.boundaryTopPct = llm.dbl("ape.llmBoundaryTopPct");
        this.boundaryBottomPct = llm.dbl("ape.llmBoundaryBottomPct");
    }

    /**
     * The model's normalized answer, in device pixels.
     *
     * @param x the model's x, in its own normalized range
     * @param y the model's y
     * @param deviceWidth display width in pixels
     * @param deviceHeight display height in pixels
     * @return {@code {pixelX, pixelY}}
     */
    public int[] toPixels(int x, int y, int deviceWidth, int deviceHeight) {
        return CoordinateNormalizer.normalize(x, y, deviceWidth, deviceHeight);
    }

    // -------------------------------------------------------------------------
    // Action mapping
    // -------------------------------------------------------------------------

    /**
     * Map pixel coordinates and action type to the best matching ModelAction.
     *
     * <p>Matching strategy (in order):
     * <ol>
     *   <li>back → return state.getBackAction()</li>
     *   <li>Boundary reject: y in the configured top or bottom band</li>
     *   <li>type_text: filter to input-field widgets only</li>
     *   <li>Bounds containment: smallest widget whose bounds contain (pixelX, pixelY)</li>
     *   <li>Edge-distance fallback: nearest widget by point-to-rectangle distance,
     *       within tolerance</li>
     *   <li>Off-tree synthesis: a targetless tap carrying the coordinate</li>
     * </ol>
     *
     * <p><b>A known defect is reproduced here deliberately, and naming it is mandatory.</b> A
     * {@code type_text} answer can execute a {@code MODEL_LONG_CLICK}, measured at 28 of 1,233 LLM
     * responses (2.3%): the containment pass restricts the candidate's {@code ActionType} only when
     * the tool is {@code click}, and {@link #fixTextEdit} returns the match untouched for any tool
     * that is neither {@code click} nor {@code long_click}, so the long-click preference can win.
     * The fix belongs to a separate change against this class — a silently inherited defect in a
     * newly extracted unit is indistinguishable from a slicing regression when the parity oracle
     * later disagrees, which is why it is written down rather than quietly carried.
     *
     * @param pixelX     x coordinate in device pixels
     * @param pixelY     y coordinate in device pixels
     * @param actionType LLM action type string ("click", "long_click", "type_text", "back")
     * @param text       typed text for type_text (may be null)
     * @param actions    candidate ModelActions
     * @param state      current state (for back action)
     * @param deviceWidth  display width in pixels
     * @param deviceHeight display height in pixels
     * @return matched ModelAction, or null if no suitable match found
     */
    public ModelAction map(int pixelX, int pixelY,
                           String actionType, String text,
                           List<ModelAction> actions,
                           State state,
                           int deviceWidth, int deviceHeight) {
        if (actionType == null) return null;

        // Handle back action
        if ("back".equals(actionType)) {
            try {
                return state.getBackAction();
            } catch (Exception e) {
                return null;
            }
        }

        // Boundary reject: top and bottom bands of the screen, keeping the model off the status and
        // navigation bars — and off any degenerate (0,0) emission, which the top band catches.
        if (pixelY < deviceHeight * boundaryTopPct
                || pixelY > deviceHeight * boundaryBottomPct) {
            Logger.println("[APE-RV] LLM coordinate rejected (boundary): pixelY=" + pixelY
                    + " deviceHeight=" + deviceHeight);
            return null;
        }

        if (actions == null || actions.isEmpty()) return null;

        // Determine preferred ActionType for click vs long_click
        boolean preferLongClick = "long_click".equals(actionType);

        // --- Bounds containment pass ---
        ModelAction bestBounds  = null;
        long        bestArea    = Long.MAX_VALUE;

        for (ModelAction action : actions) {
            try {
                if (!action.requireTarget() || !action.isValid()) continue;
                GUITreeNode node = action.getResolvedNode();
                if (node == null) continue;

                // For type_text: restrict to input-capable widgets
                if ("type_text".equals(actionType) && !ApePromptBuilder.isInputClass(node)) continue;

                // For click: the tool the model called constrains the ActionType (INV-RTR-17). Without
                // this, any action sharing the widget's bounds could answer a click — measured, a click
                // answer executed a CLICK only 80.9% of the time, the rest being long-clicks and
                // scrolls. A click that finds no MODEL_CLICK falls to the snap pass or off-tree
                // synthesis, both of which stay honest about what the model asked for.
                if ("click".equals(actionType) && action.getType() != ActionType.MODEL_CLICK) continue;

                // For long_click: prefer MODEL_LONG_CLICK; fall through to MODEL_CLICK if needed
                if (preferLongClick && action.getType() != ActionType.MODEL_LONG_CLICK) continue;

                Rect bounds = node.getBoundsInScreen();
                if (bounds.contains(pixelX, pixelY)) {
                    long area = (long)(bounds.width()) * bounds.height();
                    if (area < bestArea) {
                        bestArea   = area;
                        bestBounds = action;
                    }
                }
            } catch (Exception ignored) { /* skip bad actions */ }
        }

        if (bestBounds != null) return fixTextEdit(bestBounds, actions, actionType);

        // If long_click had no match with MODEL_LONG_CLICK, retry with any click type
        if (preferLongClick) {
            for (ModelAction action : actions) {
                try {
                    if (!action.requireTarget() || !action.isValid()) continue;
                    GUITreeNode node = action.getResolvedNode();
                    if (node == null) continue;
                    Rect bounds = node.getBoundsInScreen();
                    if (bounds.contains(pixelX, pixelY)) {
                        long area = (long)(bounds.width()) * bounds.height();
                        if (area < bestArea) {
                            bestArea   = area;
                            bestBounds = action;
                        }
                    }
                } catch (Exception ignored) { /* skip bad actions */ }
            }
            if (bestBounds != null) return fixTextEdit(bestBounds, actions, actionType);
        }

        // --- Edge-distance fallback ---
        ModelAction bestEdge = null;
        double      bestDist = Double.MAX_VALUE;

        for (ModelAction action : actions) {
            try {
                if (!action.requireTarget() || !action.isValid()) continue;
                GUITreeNode node = action.getResolvedNode();
                if (node == null) continue;

                if ("type_text".equals(actionType) && !ApePromptBuilder.isInputClass(node)) continue;

                // Same ActionType constraint as the containment pass (INV-RTR-17): the fallback must
                // not re-admit what the primary pass filtered out.
                if ("click".equals(actionType) && action.getType() != ActionType.MODEL_CLICK) continue;

                Rect bounds = node.getBoundsInScreen();
                // Point-to-rectangle (edge) distance, clamped per axis: zero when the point is
                // inside, otherwise how far outside the widget's own border it fell (INV-RTR-18).
                // Centre distance punished elongated widgets — on a 1080×150 bar only points within
                // ~75 px of the centre could snap, leaving ~450 px of the bar's own edge
                // unsnappable, so a tap 20 px outside a wide widget failed while being visually on
                // target.
                int dx = Math.max(Math.max(bounds.left - pixelX, 0), pixelX - bounds.right);
                int dy = Math.max(Math.max(bounds.top - pixelY, 0), pixelY - bounds.bottom);
                double dist = Math.hypot(dx, dy);

                // tolerance = max(floor, min(nodeWidth, nodeHeight) / 2), the floor coming from the
                // plan so a campaign can widen snapping without touching this code.
                int nodeWidth  = bounds.width();
                int nodeHeight = bounds.height();
                double tolerance = Math.max((double) snapTolerancePx,
                        Math.min(nodeWidth, nodeHeight) / 2.0);

                if (dist <= tolerance && dist < bestDist) {
                    bestDist = dist;
                    bestEdge = action;
                }
            } catch (Exception ignored) { /* skip bad actions */ }
        }

        if (bestEdge != null) {
            return fixTextEdit(bestEdge, actions, actionType);
        }

        // --- Off-tree coordinate tap (dynamic element) ---
        // No widget contains the point and none is within edge-distance tolerance. The boundary reject
        // ran first, so a coordinate reaching here is guaranteed in-bounds and non-degenerate. For a
        // click/long_click, synthesize a targetless MODEL_LLM_TAP carrying the LLM coordinate so APE
        // can act on elements invisible to UIAutomator (game canvas, custom view, Compose-without-
        // semantics). type_text and any other type stay null — a raw coordinate has no node to
        // receive text. (llm-coordinate-tap, D4)
        if ("click".equals(actionType) || "long_click".equals(actionType)) {
            return new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType));
        }
        return null;
    }

    /**
     * fixTextEdit (B6(iv)): a {@code click}/{@code long_click} that resolves to an input-capable
     * widget is a text-entry decision, not a bare press. The <i>where</i> is the LLM's — its
     * grounding on these widgets is its best (93.1% on EditText) — and the <i>what</i> is the
     * harness's: APE types a node's {@code inputText} as part of dispatching a {@code MODEL_CLICK}
     * on it, and {@code ApeAgent.checkInput} fills that text for an LLM decision on an
     * input-capable widget using the same generator a SATA-selected input action uses. No second
     * LLM call is made. This removes the bare click on input widgets from the LLM's effective
     * action space — banning by subtraction, the mechanism that outperforms prompt instruction, and
     * the answer to the measured {@code type_text≈0} collapse.
     *
     * <p>All this method has to do, then, is make sure the returned action is the one whose
     * dispatch carries text: {@code MODEL_LONG_CLICK} does not type, so a long-press match on an
     * input widget is swapped for the {@code MODEL_CLICK} on the same node. When the widget offers
     * no such action the long-press stands — there is nothing on it that could carry the text.
     */
    private static ModelAction fixTextEdit(ModelAction match, List<ModelAction> actions,
                                           String actionType) {
        if (!"click".equals(actionType) && !"long_click".equals(actionType)) return match;
        GUITreeNode node = safeResolvedNode(match);
        if (!ApePromptBuilder.isInputClass(node)) return match;
        if (match.getType() == ActionType.MODEL_CLICK) return match;
        for (ModelAction candidate : actions) {
            try {
                if (candidate.getType() != ActionType.MODEL_CLICK || !candidate.isValid()) continue;
                if (candidate.getResolvedNode() == node) return candidate;
            } catch (Exception ignored) { /* skip bad actions */ }
        }
        return match;
    }

    // -------------------------------------------------------------------------
    // Nearest-widget geometry
    // -------------------------------------------------------------------------

    /** What the telemetry line reports about the screen the coordinate landed on. */
    public static final class Nearest {

        /** Simple class name of the closest targetable widget, or {@code none}. */
        public final String className;

        /** Centre distance to it in pixels, or {@code -1} when the screen offered none. */
        public final double distance;

        /** How many targetable widgets the screen offered. */
        public final int widgetCount;

        Nearest(String className, double distance, int widgetCount) {
            this.className = className;
            this.distance = distance;
            this.widgetCount = widgetCount;
        }
    }

    /**
     * The closest targetable widget to the model's coordinate, for telemetry only.
     *
     * <p>Centre distance, not the edge distance the snap pass uses: this answers "how far off was
     * the model" offline, where a centre is the stable anchor to compare across widget shapes,
     * while snapping asks "is this close enough to act on", where the border is what matters.
     * Deliberately unfiltered by tool — it describes the screen, not the decision, so a click
     * answer's nearest widget may be one no {@code click} could ever have matched.
     *
     * @param actions the actions offered on the state
     * @param pixelX the model's x in device pixels
     * @param pixelY the model's y
     * @return the report, never null
     */
    public Nearest nearest(List<ModelAction> actions, int pixelX, int pixelY) {
        String nearestClass = "none";
        double nearestDist = -1;
        int widgetCount = 0;
        if (actions != null) {
            for (ModelAction a : actions) {
                try {
                    if (a == null || !a.requireTarget() || !a.isValid()) continue;
                    GUITreeNode n = a.getResolvedNode();
                    if (n == null) continue;
                    widgetCount++;
                    Rect b = n.getBoundsInScreen();
                    int cx = (b.left + b.right) / 2;
                    int cy = (b.top + b.bottom) / 2;
                    double d = Math.hypot(cx - pixelX, cy - pixelY);
                    if (nearestDist < 0 || d < nearestDist) {
                        nearestDist = d;
                        String cn = n.getClassName();
                        nearestClass = cn != null ? cn.substring(cn.lastIndexOf('.') + 1) : "View";
                    }
                } catch (Exception ignored) {}
            }
        }
        return new Nearest(nearestClass, nearestDist, widgetCount);
    }

    // -------------------------------------------------------------------------
    // Dead-pair ban (B1)
    // -------------------------------------------------------------------------

    /**
     * The ban key of a mapped result, or null when the result carries no bannable pair (a
     * {@code back} answer returns the state's targetless BACK action).
     *
     * <p>Two shapes, per INV-RTR-15. An off-tree tap keys on the exact emitted coordinate, because
     * the measured waste is exact-coordinate repetition (the spatial collapse x∈{499,500} is 36.7%
     * of emissions). A matched widget keys on the action {@code Name}'s XPath — the same widget
     * identity {@code UICoverageTracker.widgetId} and the MOP revisit cap already use — plus the
     * event type. That anchor is deliberately <b>abstraction-level</b>, not per-node: a {@code Name}
     * may resolve to several nodes (16.3% of targeted steps in the calibration corpus), so one ban
     * withdraws the action from all of them, and a ban count is a count of abstract pairs that must
     * never be reported as a count of widgets. The anchor is never a list index — index anchoring is
     * the bug class this project's autopsy catalogued.
     */
    public String banKey(ModelAction action) {
        if (action == null) return null;
        try {
            String stateKey = String.valueOf(action.getState() != null
                    ? action.getState().getStateKey() : "none");
            if (action instanceof LlmTapAction) {
                LlmTapAction tap = (LlmTapAction) action;
                return "tap|" + stateKey + "|" + tap.getPixelX() + "," + tap.getPixelY();
            }
            Name target = action.getTarget();
            if (target == null) return null;
            return "matched|" + stateKey + "|" + target.toXPath() + "|" + action.getType();
        } catch (Exception ignored) { return null; }
    }

    /** True once the pair has accumulated its five unproductive executions. */
    public boolean isDeadPair(String key) {
        if (key == null) return false;
        Integer strikes = deadPairStrikes.get(key);
        return strikes != null && strikes >= DEAD_PAIR_STRIKES;
    }

    /**
     * Record the outcome of an executed LLM-originated decision. Called by {@code StatefulAgent} at
     * the point where {@code new_state} is computed for the step record's {@code out}, under the
     * same single-shot buffered-decision discipline that guards that emission — this unit cannot
     * observe outcomes itself.
     *
     * <p>Only unproductive executions are recorded: a {@code new_state=true} execution neither
     * counts toward death nor resets the accumulated count, so the counter only ever grows and
     * counts exactly the unproductive executions of that pair.
     *
     * <p>An input-capable target is exempt at any strike count, and the exemption is realized here
     * by <b>not recording the strike</b> rather than by filtering at the ban check: the pair never
     * enters the record, so there is no state to keep for it and nothing to consult. It keys on the
     * widget rather than on the event type, which is what carries the exemption through the
     * fixTextEdit conversion of a click on that same widget. It reaches the {@code matched} half
     * only — that is a property of the evidence, not a scoping choice: an off-tree tap has no
     * matched widget (and hence no resolved node), so no class-based exemption of any design can
     * see a class there.
     */
    public void recordLlmOutcome(ModelAction action, boolean newState) {
        if (action == null || newState) return;
        if (ApePromptBuilder.isInputClass(safeResolvedNode(action))) return;
        String key = banKey(action);
        if (key == null) return;
        Integer strikes = deadPairStrikes.get(key);
        deadPairStrikes.put(key, strikes == null ? 1 : strikes + 1);
    }

    /**
     * Number of pairs holding at least one strike. Exists because the exemption's invariant is
     * about the record itself — an input-capable pair never enters it — and that is not observable
     * from {@link #isDeadPair} alone, which cannot distinguish "recorded but never dead" from
     * "never recorded".
     */
    int deadPairRecordSize() { return deadPairStrikes.size(); }

    private static GUITreeNode safeResolvedNode(ModelAction action) {
        try { return action.getResolvedNode(); } catch (Exception ignored) { return null; }
    }
}
