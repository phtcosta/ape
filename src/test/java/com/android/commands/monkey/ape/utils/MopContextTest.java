package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link MopContext} (design D17 "Plumbing", API Design "Part B: {@code MopContext}"): built from
 * the plan once the artifact loads, null without an artifact, carrying a retirement under
 * {@code distance} and never under {@code flag}.
 */
public class MopContextTest {

    private static MopData someData() {
        return MopData.forTest(null, null);
    }

    @Test
    public void fromSpecCarriesThePlanDefaults() {
        MopData data = someData();
        MopContext mop = MopContext.fromSpec(data, StaticGuiModel.EMPTY, TestRunSpecs.mopSpec().mop());
        assertSame(data, mop.data());
        assertEquals(MopScoring.FLAG, mop.scoring());
        assertEquals(500, mop.weightDirect());
        assertEquals(300, mop.weightTransitive());
        // Calibration v6's F1 (gh-tbd-mop-defaults-f1): three levels, so the cut stays 3.
        assertArrayEquals(new int[] {194, 146, 97}, mop.weights());
        assertEquals(3, mop.cut());
        assertEquals(10, mop.retireAfter());
        assertEquals(6, mop.launcherDmax());
        assertNull("no retirement under flag", mop.retirement());
    }

    @Test
    public void fromSpecCarriesStatedValues() {
        RunSpec.MopParams params = TestRunSpecs.mopSpec("ape.mopScoring", "distance",
                "ape.mopWeightDirect", "71", "ape.mopWeightTransitive", "53",
                "ape.mopWeights", "41,31,23,11",
                "ape.mopRetireAfter", "5", "ape.mopLauncherDmax", "4").mop();
        MopContext mop = MopContext.fromSpec(someData(), StaticGuiModel.EMPTY, params);
        assertEquals(MopScoring.DISTANCE, mop.scoring());
        assertEquals(71, mop.weightDirect());
        assertEquals(53, mop.weightTransitive());
        assertArrayEquals(new int[] {41, 31, 23, 11}, mop.weights());
        assertEquals(4, mop.cut());
        assertEquals(5, mop.retireAfter());
        assertEquals(4, mop.launcherDmax());
        assertNotNull("distance builds the run's retirement (task 13.6)", mop.retirement());
    }

    @Test
    public void levelWeightReadsTheLadderAndIsZeroBeyondTheCut() {
        MopContext mop = new MopContext(someData(), StaticGuiModel.EMPTY, MopScoring.DISTANCE, 500, 300,
                new int[] {500, 400, 300}, 3, 6, null);
        assertEquals(500, mop.levelWeight(1));
        assertEquals(400, mop.levelWeight(2));
        assertEquals(300, mop.levelWeight(3));
        assertEquals(0, mop.levelWeight(4));
        assertEquals(0, mop.levelWeight(Integer.MAX_VALUE));
    }

    @Test
    public void theLadderIsCopiedInAndOut() {
        int[] ladder = {500, 400, 300};
        MopContext mop = new MopContext(someData(), StaticGuiModel.EMPTY, MopScoring.DISTANCE, 500, 300,
                ladder, 3, 6, null);
        ladder[0] = 1;
        mop.weights()[1] = 1;
        assertArrayEquals(new int[] {500, 400, 300}, mop.weights());
    }

    @Test
    public void noArtifactNoContext() {
        assertNull(MopContext.fromSpec(null, StaticGuiModel.EMPTY, TestRunSpecs.mopSpec().mop()));
        assertNull(MopContext.fromSpec(null, StaticGuiModel.EMPTY, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void noRetirementUnderFlag() throws Exception {
        // Allocated past its skeleton constructor: only the type's presence matters here.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        MopRetirement retirement = (MopRetirement) unsafeClass
                .getMethod("allocateInstance", Class.class).invoke(unsafe, MopRetirement.class);
        new MopContext(someData(), StaticGuiModel.EMPTY, MopScoring.FLAG, 500, 300, new int[] {500, 400, 300}, 3, 6, retirement);
    }
}
