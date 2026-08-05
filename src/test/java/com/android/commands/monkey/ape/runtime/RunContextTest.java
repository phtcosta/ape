/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.runtime;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

import android.util.Log;

import com.android.commands.monkey.ape.utils.RandomHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The run context: established once, identified once, seeded once.
 *
 * <p>Every test starts from a cleared holder. A JVM test class shares a process with every other
 * one, which a run never does — so without the reset the once-only contract would be observable
 * only in whichever test happened to run first, and the suite's result would depend on ordering.
 */
public class RunContextTest {

    @Before
    public void clearTheHolder() {
        RunContext.resetForTest();
    }

    @After
    public void leaveNoContextBehind() {
        RunContext.resetForTest();
    }

    private static RunSpec spec(Map<String, String> entries, long seed) {
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", seed, null));
    }

    private static Map<String, String> entries(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    public void aSecondInitializeThrows() {
        // A run that re-established its identity or re-seeded its randomness half way through
        // would produce a trace whose first line no longer described it.
        RunContext.initialize(spec(entries(), 42L), 42L);
        try {
            RunContext.initialize(spec(entries(), 43L), 43L);
            fail("expected the second initialize to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("one run"));
        }
    }

    @Test
    public void currentBeforeInitializeThrowsRatherThanReturningADefault() {
        // A read site running before the plan is resolved is an ordering bug. Handing it an empty
        // context would let the run continue under a plan nobody chose, which is the failure mode
        // this whole change exists to close.
        try {
            RunContext.current();
            fail("expected current() to refuse before initialize");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no run context"));
        }
    }

    @Test
    public void theGeneratedRunIdCarriesTheStampTheSeedAndTheArm() {
        RunSpec spec = spec(entries(), 12345L);
        RunContext.initialize(spec, 12345L);
        String runId = RunContext.current().runId();

        assertTrue("run id should be <utc>-<seed>-<digest prefix>, was " + runId,
                runId.matches("\\d{8}T\\d{6}Z-12345-[0-9a-f]{8}"));
        assertTrue("the digest prefix identifies the arm",
                runId.endsWith(spec.digest().substring(0, 8)));
    }

    @Test
    public void twoRunsOfOneArmShareTheDigestPrefixAndDifferInTheSeed() {
        // This is what the id is for: the prefix joins runs of the same plan, the seed separates
        // the individual runs of it.
        RunSpec first = spec(entries(), 1L);
        RunSpec second = spec(entries(), 2L);
        assertEquals(first.digest(), second.digest());

        RunContext.initialize(first, 1L);
        String firstId = RunContext.current().runId();
        RunContext.resetForTest();
        RunContext.initialize(second, 2L);
        String secondId = RunContext.current().runId();

        assertEquals(first.digest().substring(0, 8),
                firstId.substring(firstId.lastIndexOf('-') + 1));
        assertEquals(second.digest().substring(0, 8),
                secondId.substring(secondId.lastIndexOf('-') + 1));
        assertTrue(firstId.contains("-1-"));
        assertTrue(secondId.contains("-2-"));
    }

    @Test
    public void aHarnessSuppliedRunIdWins() {
        RunContext.initialize(spec(entries("ape.runId", "e3-rep2-fossify"), 42L), 42L);
        assertEquals("e3-rep2-fossify", RunContext.current().runId());
    }

    @Test
    public void aBlankHarnessRunIdIsTreatedAsAbsent() {
        // An identity of "" would join every run that pushed an empty value to every other one.
        RunContext.initialize(spec(entries("ape.runId", "   "), 42L), 42L);
        assertTrue(RunContext.current().runId().matches("\\d{8}T\\d{6}Z-42-[0-9a-f]{8}"));
    }

    @Test
    public void establishingTheContextSeedsTheRunsRandomStream() {
        // INV-RUN-08/INV-EXPL-14: the context is the single seeding point, so a context cannot
        // exist over an unseeded stream. Two contexts on one seed draw the same sequence.
        RunContext.initialize(spec(entries(), 777L), 777L);
        int[] drawn = {RandomHelper.nextInt(1000), RandomHelper.nextInt(1000),
                RandomHelper.nextInt(1000)};

        RunContext.resetForTest();
        RunContext.initialize(spec(entries(), 777L), 777L);
        assertEquals(drawn[0], RandomHelper.nextInt(1000));
        assertEquals(drawn[1], RandomHelper.nextInt(1000));
        assertEquals(drawn[2], RandomHelper.nextInt(1000));
    }

    @Test
    public void installForTestGivesAFullContextWithoutPropertyFiles() {
        // What the migrated read sites use: a plan stated as a value, not written to a file and
        // not poked into a static field.
        RunSpec spec = spec(entries("ape.mopDataPath", "/data/local/tmp/sa.json"), 99L);
        RunContext.installForTest(spec);

        assertEquals(spec, RunContext.current().spec());
        assertNotNull(RunContext.current().rng());
        assertTrue(RunContext.current().runId().contains("-99-"));
        assertTrue(RunContext.current().spec().has(Feature.MOP));
    }

    /**
     * The heartbeat flag reaches the sink, which is the half of it a sink test cannot see.
     *
     * <p>{@code NdjsonSinkTest} pins what the sink does with the flag; this pins that the plan's
     * value is what it gets. The two together are what makes {@code ape.telemetryHeartbeat=false}
     * mean anything — a key nothing reads would have looked identical from either side alone.
     */
    @Test
    public void theHeartbeatFlagReachesTheSinkFromThePlan() {
        PrintStream realOut = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));

            RunContext.installForTest(spec(entries("ape.telemetryHeartbeat", "false"), 5L));
            Log.reset();
            RunContext.current().sink().beginStep(1, 1L, "com.foo/.Main", false, "S1");
            assertTrue("a plan that turned the heartbeat off gets no logcat line",
                    Log.entries().isEmpty());

            RunContext.resetForTest();
            RunContext.installForTest(spec(entries(), 5L));
            RunContext.current().sink().beginStep(1, 1L, "com.foo/.Main", false, "S1");
            assertEquals("and a plan that says nothing gets one: the default is on", 1,
                    Log.entries().size());
        } finally {
            System.setOut(realOut);
            Log.reset();
        }
    }
}
