package com.android.commands.monkey.ape.agent;

import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** design D-B9: four streams, deterministic per seed, independent of each other. */
public class StepRngTest {

    @Test
    public void eachStreamIsDeterministicPerSeed() {
        StepRng a = new StepRng(42L);
        StepRng b = new StepRng(42L);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.llmCoin(), b.llmCoin(), 0.0);
            assertEquals(a.mopCoin(), b.mopCoin(), 0.0);
            assertEquals(a.componentCoin(), b.componentCoin(), 0.0);
            assertEquals(a.fuzzToss(0.5), b.fuzzToss(0.5));
        }
    }

    @Test
    public void drawingOneStreamDoesNotMoveAnother() {
        StepRng quiet = new StepRng(7L);
        StepRng busy = new StepRng(7L);
        for (int i = 0; i < 50; i++) {
            busy.llmCoin();
            busy.componentCoin();
            busy.fuzzToss(0.02);
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("the MOP coin is its own stream", quiet.mopCoin(), busy.mopCoin(), 0.0);
        }
    }

    @Test
    public void theStreamsDifferFromEachOtherAndFromTheAgentStream() {
        StepRng rng = new StepRng(1234L);
        double agent = new Random(1234L).nextDouble();
        double llm = rng.llmCoin();
        double mop = rng.mopCoin();
        double component = rng.componentCoin();
        assertNotEquals(agent, llm, 0.0);
        assertNotEquals(llm, mop, 0.0);
        assertNotEquals(mop, component, 0.0);
        assertNotEquals(llm, component, 0.0);
    }

    @Test
    public void theFuzzTossDrawsEvenAtRateZero() {
        StepRng zero = new StepRng(9L);
        StepRng half = new StepRng(9L);
        assertFalse(zero.fuzzToss(0.0));
        half.fuzzToss(0.5);
        // Both streams advanced by one draw, so their next draws agree.
        assertEquals(zero.fuzzToss(1.0), half.fuzzToss(1.0));
        assertTrue(new StepRng(9L).fuzzToss(1.0));
    }
}
