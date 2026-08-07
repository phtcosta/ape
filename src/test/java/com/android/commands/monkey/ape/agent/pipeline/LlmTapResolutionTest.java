package com.android.commands.monkey.ape.agent.pipeline;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * llm-coordinate-tap §5 — the guard that decides whether an LLM-routed action must be resolved
 * against the current state before dispatch ({@link LlmGate#requiresSynthesizedResolution}).
 *
 * <p>The resolution the guard gates is agent-side ({@code SataAgent.resolveSynthesizedTap}) and needs
 * a live {@code State}, GUITree and model graph, none of which the JVM suite has, so it is exercised
 * end-to-end on the emulator (tasks.md 6.3): a canvas-app run confirms the synthesized tap acquires a
 * non-null {@code GUITreeAction} and survives {@code resolveNewAction} without the NPE that D7
 * prevents. Here we pin the pure branch decision that gates it.
 *
 * <p>The correctness crux (D7): the synthesized off-tree tap is {@code isModelAction()==true} yet
 * absent from {@code State.actions}, so it alone must be resolved by the accepting stage; a matched widget
 * action is already resolved and MUST NOT be re-resolved. The guard is exactly that distinction.
 *
 * <p>The {@link ModelAction} / {@link LlmTapAction} constructors do not dereference the State, so a
 * null-state action is sufficient.
 */
public class LlmTapResolutionTest {

    @Test
    public void synthesizedTapRequiresResolution() {
        // The off-tree tap is the ONLY action an LLM stage must resolve before selecting it (D7).
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        assertTrue("MODEL_LLM_TAP must be resolved in the LLM stages",
                LlmGate.requiresSynthesizedResolution(tap));
    }

    @Test
    public void matchedWidgetActionIsNotReResolved() {
        // A matched widget action returned on the same path is already resolved; re-resolving it
        // would re-pick its node. The guard must therefore skip every widget type.
        assertFalse(LlmGate.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_CLICK)));
        assertFalse(LlmGate.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_LONG_CLICK)));
    }

    @Test
    public void targetlessModelActionsFromStateAreNotReResolved() {
        // MODEL_BACK / MODEL_MENU are targetless too, but they ARE members of State.actions and are
        // resolved by the normal per-state pass — the stages must not touch them either.
        assertFalse(LlmGate.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_BACK)));
        assertFalse(LlmGate.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_MENU)));
    }
}
