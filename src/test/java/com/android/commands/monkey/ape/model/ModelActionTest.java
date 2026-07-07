package com.android.commands.monkey.ape.model;

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
        // SATA, MOP, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget, Form (Form added in §5.2).
        assertEquals(10, ModelAction.DecisionSource.values().length);
        // Spot-check the sources attributed by the selection paths.
        assertNotNull(ModelAction.DecisionSource.valueOf("SATA"));
        assertNotNull(ModelAction.DecisionSource.valueOf("LLM"));
        assertNotNull(ModelAction.DecisionSource.valueOf("Budget"));
        assertNotNull(ModelAction.DecisionSource.valueOf("Form"));
    }

    @Test
    public void testResetBoostsZeroesAllMechanisms() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(500);
        a.setWtgBoost(200);
        a.setCoverageBoost(100);
        a.setMenuBoost(250);
        a.setFormBoost(150);
        a.resetBoosts();
        assertEquals(0, a.getMopBoost());
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
}
