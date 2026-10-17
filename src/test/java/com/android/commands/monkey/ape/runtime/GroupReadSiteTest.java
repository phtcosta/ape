package com.android.commands.monkey.ape.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.android.commands.monkey.ape.tree.GUITreeBuilder;
import com.android.commands.monkey.ape.utils.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * guidance-groups INV-GRP-06: every read site of an ADDONS mechanism reads the plan, and the BASE
 * keys the plan owns are read through it too (design D-B6, verification A10). With the base cell
 * installed, every such site reads off; with the addons, on.
 */
public class GroupReadSiteTest {

    /** The switches whose {@code Config} twins the plan reads replaced; none may come back. */
    private static final List<String> PLAN_READ_SWITCHES = Arrays.asList(
            "modelMenuEnabled", "treeEnhancementsEnabled", "heuristicInput",
            "foreignActivityGuard", "treePackageGuard", "mopActivitySourceComponents",
            "doFuzzing", "fuzzingRate", "checkRestart", "minEpsilon", "maxEpsilon",
            "mopDataPath", "mopTargetPickCap", "leastVisitedPriorityTiebreak",
            "frontierBoostWeight");

    /** The ADDONS members a site asks the plan for (INV-GRP-06's table). */
    private static final Feature[] ADDON_SITES = {
            Feature.MODEL_MENU, Feature.TREE_ENHANCEMENTS, Feature.HEURISTIC_INPUT,
            Feature.FOREIGN_ACTIVITY_GUARD, Feature.TREE_PACKAGE_GUARD, Feature.FORM_COMPLETION,
            Feature.BACK_MENU_CAP, Feature.ACTIVITY_STABLE_RESTART};

    @After
    public void clearPlan() {
        RunContext.resetForTest();
    }

    @Test
    public void configKeepsNoTwinOfAPlanReadSwitch() {
        List<String> twins = new ArrayList<>();
        for (Field field : Config.class.getDeclaredFields()) {
            if (PLAN_READ_SWITCHES.contains(field.getName())) {
                twins.add(field.getName());
            }
        }
        assertEquals("Config must not be a second authority over a planned key",
                new ArrayList<String>(), twins);
    }

    @Test
    public void underTheBaseCellEveryAddonSiteReadsOff() {
        RunSpec spec = TestRunSpecs.install("ape.preset", Presets.BASE);
        for (Feature member : ADDON_SITES) {
            assertFalse(member + " must be off in base", spec.has(member));
        }
        assertFalse(GUITreeBuilder.treeEnhancementsEnabled());
        assertFalse(spec.has(Feature.MOP_ACTIVITY_SOURCE));
        assertEquals("no BACK/MENU cap", 0, spec.exploration().backMenuPickCap());
        assertEquals("upstream: no activity-stable restart", Integer.MAX_VALUE,
                spec.exploration().activityStableRestartThreshold());
        // The two BASE switches read through the plan keep their defaults in every cell.
        assertTrue(spec.has(Feature.FUZZING));
        assertEquals(0.02, spec.exploration().fuzzingRate(), 0.0);
        assertTrue(spec.exploration().checkRestart());
    }

    @Test
    public void withTheAddonsEveryAddonSiteReadsOn() {
        RunSpec spec = TestRunSpecs.install("ape.addons", "true");
        for (Feature member : ADDON_SITES) {
            assertTrue(member + " must be on with ADDONS", spec.has(member));
        }
        assertTrue(GUITreeBuilder.treeEnhancementsEnabled());
        assertEquals(3, spec.exploration().backMenuPickCap());
        assertEquals(200, spec.exploration().activityStableRestartThreshold());
    }

    @Test
    public void theBaseSwitchesFollowThePlan() {
        RunSpec spec = TestRunSpecs.install("ape.doFuzzing", "false", "ape.checkRestart", "false");
        assertFalse(spec.has(Feature.FUZZING));
        assertEquals(0.0, spec.exploration().fuzzingRate(), 0.0);
        assertFalse(spec.exploration().checkRestart());
    }
}
