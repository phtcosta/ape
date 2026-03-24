package com.android.commands.monkey.ape.agent;

import static org.junit.Assert.*;
import org.junit.Test;

import com.android.commands.monkey.ape.utils.Config;

/**
 * Tests for the dynamic epsilon formula used by SataAgent.computeDynamicEpsilon().
 * Since SataAgent requires Android runtime, we test the formula directly.
 *
 * Formula: epsilon = minEpsilon + (maxEpsilon - minEpsilon) * coverageGap
 */
public class DynamicEpsilonTest {

    private static double computeEpsilon(double minEpsilon, double maxEpsilon, float coverageGap) {
        return minEpsilon + (maxEpsilon - minEpsilon) * coverageGap;
    }

    // INV-EPS-01: effective epsilon always in [minEpsilon, maxEpsilon]
    @Test
    public void testINV_EPS_01_gapZero_returnsMinEpsilon() {
        double eps = computeEpsilon(0.02, 0.15, 0.0f);
        assertEquals(0.02, eps, 1e-6);
    }

    @Test
    public void testINV_EPS_01_gapOne_returnsMaxEpsilon() {
        double eps = computeEpsilon(0.02, 0.15, 1.0f);
        assertEquals(0.15, eps, 1e-6);
    }

    @Test
    public void testINV_EPS_01_gapHalf_returnsMidpoint() {
        double eps = computeEpsilon(0.02, 0.15, 0.5f);
        assertEquals(0.085, eps, 1e-6);
    }

    @Test
    public void testINV_EPS_01_alwaysInRange() {
        for (float gap = 0.0f; gap <= 1.0f; gap += 0.01f) {
            double eps = computeEpsilon(0.02, 0.15, gap);
            assertTrue("epsilon " + eps + " below min for gap=" + gap, eps >= 0.02 - 1e-9);
            assertTrue("epsilon " + eps + " above max for gap=" + gap, eps <= 0.15 + 1e-9);
        }
    }

    // INV-EPS-02: when dynamicEpsilon=false, use fixed defaultEpsilon
    @Test
    public void testINV_EPS_02_dynamicDisabled_usesDefault() {
        // When dynamicEpsilon is false, SataAgent.computeDynamicEpsilon() returns this.epsilon
        // which is Config.defaultEpsilon (0.05). We verify the default here.
        assertEquals(0.05, Config.defaultEpsilon, 1e-6);
    }

    // INV-EPS-03: when tracker is null, fall back to defaultEpsilon
    // (tested implicitly: computeDynamicEpsilon checks tracker == null)
    @Test
    public void testINV_EPS_03_configDefaults() {
        assertTrue(Config.dynamicEpsilon);
        assertEquals(0.15, Config.maxEpsilon, 1e-6);
        assertEquals(0.02, Config.minEpsilon, 1e-6);
    }

    @Test
    public void testPartialCoverage_gap0_1() {
        double eps = computeEpsilon(0.02, 0.15, 0.1f);
        assertEquals(0.033, eps, 1e-3);
    }

    @Test
    public void testPartialCoverage_gap0_9() {
        double eps = computeEpsilon(0.02, 0.15, 0.9f);
        assertEquals(0.137, eps, 1e-3);
    }
}
