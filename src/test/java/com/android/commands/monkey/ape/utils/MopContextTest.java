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
        return MopData.forTest(null, null, null);
    }

    @Test
    public void fromSpecCarriesThePlanDefaults() {
        MopData data = someData();
        MopContext mop = MopContext.fromSpec(data, TestRunSpecs.mopSpec().mop());
        assertSame(data, mop.data());
        assertEquals(MopScoring.FLAG, mop.scoring());
        assertEquals(500, mop.weightDirect());
        assertEquals(300, mop.weightTransitive());
        assertEquals(500, mop.weightD1());
        assertEquals(400, mop.weightD2());
        assertEquals(300, mop.weightD3());
        assertEquals(3, mop.retireAfter());
        assertEquals(6, mop.launcherDmax());
        assertNull("no retirement under flag", mop.retirement());
    }

    @Test
    public void fromSpecCarriesStatedValues() {
        RunSpec.MopParams params = TestRunSpecs.mopSpec("ape.mopScoring", "distance",
                "ape.mopWeightDirect", "71", "ape.mopWeightTransitive", "53",
                "ape.mopWeightD1", "41", "ape.mopWeightD2", "31", "ape.mopWeightD3", "23",
                "ape.mopRetireAfter", "5", "ape.mopLauncherDmax", "4").mop();
        MopContext mop = MopContext.fromSpec(someData(), params);
        assertEquals(MopScoring.DISTANCE, mop.scoring());
        assertEquals(71, mop.weightDirect());
        assertEquals(53, mop.weightTransitive());
        assertEquals(41, mop.weightD1());
        assertEquals(31, mop.weightD2());
        assertEquals(23, mop.weightD3());
        assertEquals(5, mop.retireAfter());
        assertEquals(4, mop.launcherDmax());
        assertNotNull("distance builds the run's retirement (task 13.6)", mop.retirement());
    }

    @Test
    public void noArtifactNoContext() {
        assertNull(MopContext.fromSpec(null, TestRunSpecs.mopSpec().mop()));
        assertNull(MopContext.fromSpec(null, null));
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
        new MopContext(someData(), MopScoring.FLAG, 500, 300, 500, 400, 300, 3, 6, retirement);
    }
}
