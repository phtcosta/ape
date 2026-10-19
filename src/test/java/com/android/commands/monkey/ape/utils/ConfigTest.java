package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.KeyOwnership;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The jar defaults of the exploration keys. A key the plan reads has no {@code Config} field
 * (design D-B24), so its default is asserted where it is declared, {@code KeyOwnership}.
 *
 * Tests run on the JVM only; no Android device required.
 */
public class ConfigTest {

    // ---------------------------------------------------------------------------
    // gh9: activityStableRestartThreshold default changed from MAX_VALUE to 200
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityStableRestartThreshold_defaultIs200() {
        assertEquals("200", KeyOwnership.defaultOf("ape.activityStableRestartThreshold"));
    }

    // ---------------------------------------------------------------------------
    // gh9: coverageBoostWeight
    // ---------------------------------------------------------------------------
    @Test
    public void testCoverageBoostWeight_defaultIs100() {
        assertEquals("100", KeyOwnership.defaultOf("ape.coverageBoostWeight"));
    }

    // ---------------------------------------------------------------------------
    // gh9: activityBaseBudget
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityBaseBudget_defaultIs50() {
        assertEquals("50", KeyOwnership.defaultOf("ape.activityBaseBudget"));
    }

    // ---------------------------------------------------------------------------
    // gh9: activityBudgetPerWidget
    // ---------------------------------------------------------------------------
    @Test
    public void testActivityBudgetPerWidget_defaultIs5() {
        assertEquals("5", KeyOwnership.defaultOf("ape.activityBudgetPerWidget"));
    }

    // ---------------------------------------------------------------------------
    // gh9: mopWeightWtg
    // ---------------------------------------------------------------------------
    @Test
    public void testMopWeightWtg_defaultIs200() {
        assertEquals("200", KeyOwnership.defaultOf("ape.mopWeightWtg"));
    }

    // ---------------------------------------------------------------------------
    // gh9: dynamicEpsilon
    // ---------------------------------------------------------------------------
    @Test
    public void testDynamicEpsilon_defaultIsTrue() {
        assertEquals("true", KeyOwnership.defaultOf("ape.dynamicEpsilon"));
    }

    // ---------------------------------------------------------------------------
    // gh9: maxEpsilon
    // ---------------------------------------------------------------------------
    @Test
    public void testMaxEpsilon_defaultIs015() {
        assertEquals("0.15", KeyOwnership.defaultOf("ape.maxEpsilon"));
    }

    // ---------------------------------------------------------------------------
    // gh9: minEpsilon
    // ---------------------------------------------------------------------------
    @Test
    public void testMinEpsilon_defaultIs002() {
        assertEquals("0.02", KeyOwnership.defaultOf("ape.minEpsilon"));
    }

    // ---------------------------------------------------------------------------
    // gh9: heuristicInput
    // ---------------------------------------------------------------------------
    @Test
    public void testHeuristicInput_defaultIsTrue() {
        assertEquals("true", KeyOwnership.defaultOf("ape.heuristicInput"));
    }

    // ---------------------------------------------------------------------------
    // gh15 A-3: componentPercentage's jar default is 0.0 (triggering disabled);
    // the STATIC block states 0.03 (INV-CT-01).
    // ---------------------------------------------------------------------------
    @Test
    public void testComponentPercentage_defaultIsZero() {
        assertEquals(0.0, Double.parseDouble(KeyOwnership.defaultOf("ape.componentPercentage")), 1e-9);
    }

    @Test
    public void testComponentPercentage_explicitOverrideHonoured() {
        String prev = Config.get("ape.componentPercentage");
        try {
            Config.set("ape.componentPercentage", "0.10");
            assertEquals(0.10, Config.getDouble("ape.componentPercentage", 0.0), 1e-9);
        } finally {
            if (prev != null) {
                Config.set("ape.componentPercentage", prev);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // gh15 A-6: llmPercentage's jar default; RunSpec clamps it to [0,1] (INV-RTR-08,
    // RunSpecResolveTest).
    // ---------------------------------------------------------------------------
    @Test
    public void testLlmPercentage_defaultInRangeUnchanged() {
        assertEquals(0.02, Double.parseDouble(KeyOwnership.defaultOf("ape.llmPercentage")), 1e-9);
    }

    // ---------------------------------------------------------------------------
    // exploration-effectiveness 1.1: per-step debug artifacts default false for
    // throughput (INV-EXPL-17).
    // ---------------------------------------------------------------------------
    @Test
    public void testTakeScreenshotForEveryStep_defaultIsFalse() {
        assertEquals("false", KeyOwnership.defaultOf("ape.takeScreenshotForEveryStep"));
    }

    @Test
    public void testSaveGUITreeToXmlEveryStep_defaultIsFalse() {
        assertEquals("false", KeyOwnership.defaultOf("ape.saveGUITreeToXmlEveryStep"));
    }

    // ---------------------------------------------------------------------------
    // idle-timeout-cap 1.1: ape.maxIdleTimeoutMs parametrizes the getRootInActive
    // WindowSlow idle-wait ceiling and (÷1000) the refreshNewState break threshold.
    // The default 10000 is byte-identical to the former `1000 * 10` literal, and
    // the derived break threshold 10000/1000 == 10 matches the former `>= 10`
    // literal (INV-EXPL-23, INV-EXPL-25).
    // ---------------------------------------------------------------------------
    @Test
    public void testMaxIdleTimeoutMs_defaultIs10000() {
        assertEquals(10000L, Config.maxIdleTimeoutMs);
    }

    @Test
    public void testMaxIdleTimeoutMs_derivedBreakSecondsIs10() {
        assertEquals(10L, Config.maxIdleTimeoutMs / 1000);
    }

    // ---------------------------------------------------------------------------
    // isolate-guidance-groups D-B24: ape.llmPercentageNoSubstrate never had a
    // consumer and is retired; stating it aborts with retired_key.
    // ---------------------------------------------------------------------------
    @Test
    public void testLlmPercentageNoSubstrate_isRetired() {
        assertTrue(KeyOwnership.isRetired("ape.llmPercentageNoSubstrate"));
    }

    // ---------------------------------------------------------------------------
    // llm-native-toolcall-repair J1b/J1c: four LLM knobs exposed as config keys
    // with defaults reproducing the previously hard-coded values bit-for-bit. No
    // clamping (researcher-facing, like llmTimeoutMs/llmTopK).
    // ---------------------------------------------------------------------------
    @Test
    public void testLlmMaxTokens_defaultIs1024() {
        assertEquals("1024", KeyOwnership.defaultOf("ape.llmMaxTokens"));
    }

    @Test
    public void testLlmSnapTolerancePx_defaultIs50() {
        assertEquals("50", KeyOwnership.defaultOf("ape.llmSnapTolerancePx"));
    }

    @Test
    public void testLlmBoundaryTopPct_defaultIs0_035() {
        // The campaign AVD's 63 px status bar over its 1794 px display (llm-routing INV-RTR-22)
        assertEquals(0.035, Double.parseDouble(KeyOwnership.defaultOf("ape.llmBoundaryTopPct")), 1e-9);
    }
}
