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

import android.graphics.Rect;

/**
 * llm-coordinate-tap (D2) — a targetless {@link ModelAction} of type
 * {@link ActionType#MODEL_LLM_TAP} carrying an LLM-supplied pixel coordinate. It represents a tap
 * on an element that is absent from the {@code GUITree} (game canvas, {@code SurfaceView},
 * Compose-without-semantics, custom-drawn keyboard), where coordinate matching found no widget.
 *
 * <p>Constructed only by {@code CoordinateMapper} for the off-tree case and returned directly to the
 * agent; it is <b>not</b> a member of any {@code State.actions} array. The tap target is the raw
 * pixel {@code (pixelX, pixelY)} rather than a resolved {@code GUITreeNode}, so {@code target} is
 * null and {@code requireTarget()} is false — mirroring {@code MODEL_MENU} / {@code MODEL_BACK}.
 *
 * <p>Identity extends the inherited {@link ModelAction} identity ({@code state} +
 * {@code target=null} + type) with the {@code (pixelX, pixelY, longClick)} payload (D3,
 * INV-MODEL-13), so taps at different coordinates are different actions. This matters beyond
 * bookkeeping: sharing one action key would make a second tap at a different coordinate reaching a
 * different destination look like non-determinism of a single action, which
 * {@code Model.resolveNonDeterministicTransitions} hands to naming refinement — a phantom no
 * abstraction can resolve, since the difference is the coordinate. {@code ModelAction.equals} also
 * guards {@code getClass()}, so a tap is never equal to a {@code MODEL_MENU} / {@code MODEL_BACK} /
 * widget action.
 *
 * <p>The tap is {@link Action#isEphemeral() ephemeral} (INV-MODEL-14): dispatched once and recorded
 * as an observational edge, never registered in the graph's action inventory and never re-selected.
 */
public class LlmTapAction extends ModelAction {

    private static final long serialVersionUID = 1L;

    private final int pixelX;
    private final int pixelY;
    private final boolean longClick;

    public LlmTapAction(State state, int pixelX, int pixelY, boolean longClick) {
        super(state, null, ActionType.MODEL_LLM_TAP);
        this.pixelX = pixelX;
        this.pixelY = pixelY;
        this.longClick = longClick;
    }

    public int getPixelX() {
        return pixelX;
    }

    public int getPixelY() {
        return pixelY;
    }

    public boolean isLongClick() {
        return longClick;
    }

    /**
     * Dispatch geometry (INV-EXPL-30): the minimal non-degenerate rect anchored at the decided
     * pixel, whose truncated center is exactly {@code (pixelX, pixelY)}. A zero-area
     * {@code Rect(x, y, x, y)} can never pass the INV-EXPL-19 visibility guard — empty rects fail
     * both {@code Rect.intersect}-as-nonempty and {@code Rect.contains} — so dispatching one
     * silently disables the tap.
     */
    public Rect toInjectableRect() {
        return new Rect(pixelX, pixelY, pixelX + 1, pixelY + 1);
    }

    /**
     * Guard bounds for dispatch (INV-EXPL-30, amended): the validity domain of a coordinate tap is
     * the <b>physical display bounds</b>, not the app root-node visible bounds. The pixel is decided
     * from a full-display screenshot, so its legitimate targets include cross-window elements (IME
     * keyboards, dialogs in other windows, surfaces under insets) that lie inside the display but
     * outside the app root node — the region the root-node domain wrongly rejected (the
     * llm-tap-injection gate measured 6/7 thumbkey taps dropped there).
     *
     * <p>Returns the intersection of {@code displayBounds} with {@link #toInjectableRect()} when the
     * decided pixel lies inside the display, or {@code null} when the pixel is outside it or
     * {@code displayBounds} is {@code null} (drop, never NPE — {@code getDisplayBounds()} can fail
     * before the display is ready). The display rect is defensively copied because Android's
     * {@code Rect.intersect} mutates its receiver.
     */
    public Rect clipToDisplay(Rect displayBounds) {
        if (displayBounds == null) {
            return null;
        }
        Rect clipped = new Rect(displayBounds);
        if (!clipped.intersect(toInjectableRect())) {
            return null;
        }
        return clipped;
    }

    /**
     * Extends the inherited identity with the tap payload (INV-MODEL-13). {@code super.equals}
     * already covers {@code state}, {@code target} and {@code getClass()}.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        LlmTapAction other = (LlmTapAction) obj;
        return pixelX == other.pixelX && pixelY == other.pixelY && longClick == other.longClick;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + pixelX;
        result = prime * result + pixelY;
        result = prime * result + (longClick ? 1231 : 1237);
        return result;
    }

    @Override
    public String toString() {
        return super.toString() + "@(" + pixelX + "," + pixelY + ")" + (longClick ? "[long]" : "");
    }
}
