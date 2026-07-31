package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * fixTextEdit (B6(iv)), agent half — the guard that makes an LLM decision on an input-capable
 * widget fill its text deterministically instead of taking the {@code inputRate} toss.
 *
 * <p>{@code checkInput} itself needs a live agent (MopData, foreground activity, the generator), so
 * only the guard is JVM-testable; it is static for exactly that reason, following
 * {@code SataAgent.requiresSynthesizedResolution}. The generation it gates is the same path a
 * SATA-selected input action uses and is exercised by the device smoke.
 */
public class ApeAgentLlmInputTest {

    private static GUITreeNode node(String className) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        return node;
    }

    private static ModelAction action(ModelAction.DecisionSource source) {
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.setDecisionSource(source);
        return action;
    }

    @Test
    public void llmDecisionOnEveryInputCapableClassFillsDeterministically() {
        String[] inputClasses = {
                "android.widget.EditText",
                "android.widget.AutoCompleteTextView",
                "android.widget.SearchView",
                "androidx.appcompat.widget.SearchView"
        };
        for (String className : inputClasses) {
            assertTrue(className + " is part of the input-capable set the prompt and the ban share",
                    ApeAgent.isLlmInputDecision(action(ModelAction.DecisionSource.LLM), node(className)));
        }
    }

    @Test
    public void algorithmicArmsKeepTheirExistingInputBehavior() {
        // The widened, deterministic fill is LLM-only: a SATA-selected click on the same widget
        // still goes through isEditText() and the inputRate toss.
        assertFalse(ApeAgent.isLlmInputDecision(action(ModelAction.DecisionSource.SATA),
                node("android.widget.EditText")));
        assertFalse(ApeAgent.isLlmInputDecision(action(ModelAction.DecisionSource.MOP),
                node("android.widget.SearchView")));
    }

    @Test
    public void llmDecisionOnANonInputWidgetIsNotATextEntry() {
        assertFalse(ApeAgent.isLlmInputDecision(action(ModelAction.DecisionSource.LLM),
                node("android.widget.Button")));
    }

    @Test
    public void nullNodeIsNeverATextEntry() {
        assertFalse(ApeAgent.isLlmInputDecision(action(ModelAction.DecisionSource.LLM), null));
    }
}
