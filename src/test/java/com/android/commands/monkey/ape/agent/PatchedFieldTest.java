package com.android.commands.monkey.ape.agent;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * O4 (INV-SEL-10), emission half: the step record carries {@code dec.patched} as 0 or 1 for an
 * action with a resolved target and reports it absent for a targetless one — the same rule the
 * record's other target-derived fields follow. The absence is a third state and not a default,
 * which is why it is exempt from the record's defaults-omitted rule.
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
        assertEquals(1, StatefulAgent.patchedValue(clickOn(true)));
    }

    @Test
    public void aClickOnANativelyClickableWidgetReportsZero() {
        assertEquals(0, StatefulAgent.patchedValue(clickOn(false)));
    }

    @Test
    public void targetlessActionsOmitTheField() {
        assertEquals(EventSink.ABSENT,
                StatefulAgent.patchedValue(new ModelAction(null, ActionType.MODEL_BACK)));
        assertEquals(EventSink.ABSENT,
                StatefulAgent.patchedValue(new ModelAction(null, ActionType.MODEL_MENU)));
        assertEquals("the off-tree tap has a coordinate, not a node",
                EventSink.ABSENT,
                StatefulAgent.patchedValue(new LlmTapAction(null, 500, 900, false)));
    }

    @Test
    public void anUnresolvedTargetActionOmitsTheField() {
        assertEquals(EventSink.ABSENT,
                StatefulAgent.patchedValue(new ModelAction(null, ActionType.MODEL_CLICK)));
    }
}
