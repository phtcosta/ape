package com.android.commands.monkey.ape.agent;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * O4 (INV-SEL-10), emission half: the `[APE-STEP]` line carries `patched=0|1` for an action with a
 * resolved target and omits the field entirely for a targetless one — the same rule the line's
 * other target-derived fields follow.
 */
public class PatchedFieldTest {

    private static ModelAction clickOn(boolean patched) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.Button");
        node.setBoundsInScreen(new Rect(0, 0, 100, 50));
        node.setPatchedClickable(patched);
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});
        return action;
    }

    @Test
    public void aClickOnAPatchFabricatedWidgetReportsOne() {
        assertEquals(" patched=1", StatefulAgent.patchedField(clickOn(true)));
    }

    @Test
    public void aClickOnANativelyClickableWidgetReportsZero() {
        assertEquals(" patched=0", StatefulAgent.patchedField(clickOn(false)));
    }

    @Test
    public void targetlessActionsOmitTheField() {
        assertEquals("", StatefulAgent.patchedField(new ModelAction(null, ActionType.MODEL_BACK)));
        assertEquals("", StatefulAgent.patchedField(new ModelAction(null, ActionType.MODEL_MENU)));
        assertEquals("the off-tree tap has a coordinate, not a node",
                "", StatefulAgent.patchedField(new LlmTapAction(null, 500, 900, false)));
    }

    @Test
    public void anUnresolvedTargetActionOmitsTheField() {
        assertEquals("", StatefulAgent.patchedField(new ModelAction(null, ActionType.MODEL_CLICK)));
    }
}
