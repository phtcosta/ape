package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * llm-coordinate-tap §5 — the guard that decides whether an LLM-routed action must be resolved
 * against the current state before dispatch ({@link SataAgent#requiresSynthesizedResolution}).
 *
 * <p>SataAgent is not instantiable on the JVM (its selection chain touches Android — see
 * {@link SataAgentBackMenuCapTest} / {@link SataAgentDecisionSourceTest}), so the instance hook
 * {@code acceptLlmResult} — which calls {@code newState.resolveAction(...)} and
 * {@code getThrottleForNewAction(...)}, both requiring a live {@code State}, GUITree, and model
 * graph — is exercised end-to-end on the emulator (tasks.md 6.3): a canvas-app run confirms the
 * synthesized tap acquires a non-null {@code GUITreeAction} and survives {@code resolveNewAction}
 * without the NPE that D7 prevents. Here we pin the pure branch decision the hook consumes.
 *
 * <p>The correctness crux (D7): the synthesized off-tree tap is {@code isModelAction()==true} yet
 * absent from {@code State.actions}, so it alone must be resolved in the hook; a matched widget
 * action is already resolved and MUST NOT be re-resolved. The guard is exactly that distinction.
 *
 * <p>The {@link ModelAction} / {@link LlmTapAction} constructors do not dereference the State, so a
 * null-state action is sufficient (same approach as {@link SataAgentDecisionSourceTest}).
 */
public class SataAgentLlmTapTest {

    @Test
    public void synthesizedTapRequiresResolution() {
        // The off-tree tap is the ONLY action the hook must resolve before returning it (D7).
        LlmTapAction tap = new LlmTapAction(null, 600, 900, false);
        assertTrue("MODEL_LLM_TAP must be resolved in the LLM hook",
                SataAgent.requiresSynthesizedResolution(tap));
    }

    @Test
    public void matchedWidgetActionIsNotReResolved() {
        // A matched widget action returned on the same path is already resolved; re-resolving it
        // would re-pick its node. The guard must therefore skip every widget type.
        assertFalse(SataAgent.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_CLICK)));
        assertFalse(SataAgent.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_LONG_CLICK)));
    }

    @Test
    public void targetlessModelActionsFromStateAreNotReResolved() {
        // MODEL_BACK / MODEL_MENU are targetless too, but they ARE members of State.actions and are
        // resolved by the normal per-state pass — the hook must not touch them either.
        assertFalse(SataAgent.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_BACK)));
        assertFalse(SataAgent.requiresSynthesizedResolution(
                new ModelAction(null, ActionType.MODEL_MENU)));
    }
}
