package com.android.commands.monkey.ape.model;

import android.graphics.Rect;

import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the gh15 A-5 decision-source and per-mechanism boost telemetry
 * on {@link ModelAction}. The constructor does not dereference the State, so a
 * null state is sufficient for these field-level tests. The end-to-end
 * {@code [APE-STEP]} emission lives in {@code StatefulAgent.resolveNewAction} and
 * is device-validated (tasks.md 6.3b).
 */
public class ModelActionTest {

    @Test
    public void testDecisionSourceDefaultsToSata() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        assertEquals(ModelAction.DecisionSource.SATA, a.getDecisionSource());
    }

    @Test
    public void testDecisionSourceSetGet() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setDecisionSource(ModelAction.DecisionSource.LLM);
        assertEquals(ModelAction.DecisionSource.LLM, a.getDecisionSource());
    }

    @Test
    public void testDecisionSourceEnumCoversAllSources() {
        // SATA, MOP, MopFrontier, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget, Form.
        assertEquals(11, ModelAction.DecisionSource.values().length);
        // Spot-check the sources attributed by the selection paths.
        assertNotNull(ModelAction.DecisionSource.valueOf("SATA"));
        assertNotNull(ModelAction.DecisionSource.valueOf("LLM"));
        assertNotNull(ModelAction.DecisionSource.valueOf("Budget"));
        assertNotNull(ModelAction.DecisionSource.valueOf("Form"));
        assertNotNull(ModelAction.DecisionSource.valueOf("MopFrontier"));
    }

    @Test
    public void testResetBoostsZeroesAllMechanisms() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(500);
        a.setMopFrontierBoost(200);
        a.setWtgBoost(200);
        a.setCoverageBoost(100);
        a.setMenuBoost(250);
        a.setFormBoost(150);
        a.resetBoosts();
        assertEquals(0, a.getMopBoost());
        assertEquals(0, a.getMopFrontierBoost());
        assertEquals(0, a.getWtgBoost());
        assertEquals(0, a.getCoverageBoost());
        assertEquals(0, a.getMenuBoost());
        assertEquals(0, a.getFormBoost());
    }

    @Test
    public void testFormBoostAccessorAndDefault() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        assertEquals("formBoost defaults to 0", 0, a.getFormBoost());
        a.setFormBoost(150);
        assertEquals(150, a.getFormBoost());
    }

    // ---- A8: widget text never breaks the [APE-STEP] line (INV-SEL-07) -------

    @Test
    public void testResolvedInfoFlattensNewlinesInWidgetText() throws Exception {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.Button");
        node.setBoundsInScreen(new Rect(100, 200, 300, 250));
        node.setText("Sign\nIn");
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});

        String line = action.toString();

        assertTrue("the label is flattened, not dropped", line.contains("Sign In"));
        assertEquals("the action's string form occupies exactly one physical line",
                1, line.split("\n", -1).length);
    }

    @Test
    public void testResolvedInfoFlattensCarriageReturnsToo() {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.TextView");
        node.setBoundsInScreen(new Rect(0, 0, 10, 10));
        node.setText("a\r\nb");
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});

        assertTrue(action.toString().contains("a  b"));
    }
}
