package com.android.commands.monkey.ape.utils;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

/**
 * INV-EXPL-14 (task 3.3): after {@link RandomHelper#seed(long)}, the shared generator is
 * reproducible — two runs seeded with the same value produce identical draw sequences across
 * {@code nextInt}, {@code toss}, {@code nextLong}, {@code nextBoolean}, and {@code randomPick}.
 * With the former {@code ThreadLocalRandom.current()} backing this was impossible.
 */
public class RandomHelperSeedTest {

    /** A fixed, mixed sequence of draws exercising the common entry points. */
    private static long[] drawSequence() {
        long[] out = new long[9];
        out[0] = RandomHelper.nextInt();
        out[1] = RandomHelper.nextInt(100);
        out[2] = RandomHelper.toss(0.5) ? 1 : 0;
        out[3] = RandomHelper.nextInt(7);
        out[4] = RandomHelper.nextLong();
        out[5] = Double.doubleToLongBits(RandomHelper.nextDouble());
        out[6] = RandomHelper.nextBoolean() ? 1 : 0;
        out[7] = RandomHelper.randomPick(Arrays.asList(10, 20, 30, 40)).longValue();
        out[8] = RandomHelper.nextInt(1000);
        return out;
    }

    @Test
    public void sameSeedYieldsIdenticalDrawSequences() {
        RandomHelper.seed(42);
        long[] first = drawSequence();
        RandomHelper.seed(42);
        long[] second = drawSequence();
        assertArrayEquals(first, second);
    }

    @Test
    public void differentSeedsDiverge() {
        RandomHelper.seed(42);
        long[] a = drawSequence();
        RandomHelper.seed(43);
        long[] b = drawSequence();
        assertFalse("independent seeds should not produce identical sequences", Arrays.equals(a, b));
    }
}
