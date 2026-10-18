package com.android.commands.monkey.ape.telemetry;

import org.json.JSONObject;
import org.junit.Test;

import com.android.commands.monkey.ape.runtime.Json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The step record's members of isolate-guidance-groups (event-sink "StepRecord Schema"), each
 * round-tripped through {@code org.json}: the routing record {@code own}, the rotation, the
 * selection members, and the volume rule that omits each at its default except {@code own} and
 * {@code rot}.
 */
public class StepRecordTest {

    private static JSONObject written(StepRecord record) throws Exception {
        Json.Buf buf = new Json.Buf();
        record.writeTo(buf);
        String line = buf.toLine();
        assertEquals("a record is one line", -1, line.indexOf('\n'));
        return new JSONObject(line);
    }

    private static StepRecord opened() {
        StepRecord record = new StepRecord();
        record.open(42, 1L, 0, 0);
        return record;
    }

    @Test
    public void everyRoutingAndSelectionMemberRoundTrips() throws Exception {
        StepRecord record = opened();
        record.rotation(1);
        record.owner("llm", "coin", 0.31d, Double.NaN, null, null);
        record.owner(null, null, Double.NaN, 0.75d, "breaker", "gate");
        record.decision("model=CLICK@x", "sata", "roulette_greedy", 9,
                500, 0, 0, 0, 0, 250, 0, 1, EventSink.ABSENT, EventSink.MOP_SOURCE_ID, null);
        record.cfc("mop", true);
        record.cfc("menu", false);
        record.cfc("mop", false);
        record.draw(731, 1290);
        record.nav("X4", "mop", true, false);
        record.input("typed");
        record.backMenuCapped(2);
        record.strategy("eps_roulette");
        record.bufferAbandoned("mop");
        record.componentTrigger("service", "com.foo.SyncService");

        JSONObject dec = written(record).getJSONObject("dec");
        JSONObject own = dec.getJSONObject("own");
        assertEquals("llm", own.getString("i"));
        assertEquals("coin", own.getString("trg"));
        assertEquals(0.31d, own.getDouble("uL"), 0.0);
        assertEquals(0.75d, own.getDouble("uM"), 0.0);
        assertEquals("breaker", own.getString("g"));
        assertEquals("gate", own.getString("fb"));
        assertEquals(1, dec.getInt("rot"));
        JSONObject cfc = dec.getJSONObject("cfc");
        assertEquals("a scorer appears once; the later value wins", 2, cfc.length());
        assertEquals(0, cfc.getInt("mop"));
        assertEquals(0, cfc.getInt("menu"));
        assertEquals(731, dec.getJSONArray("draw").getInt(0));
        assertEquals(1290, dec.getJSONArray("draw").getInt(1));
        JSONObject nav = dec.getJSONObject("nav");
        assertEquals("X4", nav.getString("x"));
        assertEquals("mop", nav.getString("c"));
        assertEquals(1, nav.getInt("alt"));
        assertEquals(0, nav.getInt("drew"));
        assertEquals("typed", dec.getString("inp"));
        assertEquals(2, dec.getInt("bmc"));
        assertEquals("eps_roulette", dec.getString("strat"));
        assertEquals("mop", dec.getString("buf"));
        assertEquals("service", dec.getJSONObject("trig").getString("k"));
        assertEquals("com.foo.SyncService", dec.getJSONObject("trig").getString("c"));
        assertFalse("restart is written only on a restart step", dec.has("restart"));
    }

    @Test
    public void ownAndRotAreAlwaysWrittenAndTheRestOmittedAtDefault() throws Exception {
        StepRecord record = opened();
        record.rotation(0);
        record.backMenuCapped(0);
        record.decision("model=BACK", "sata", "sata_other", 1,
                0, 0, 0, 0, 0, 0, 0, EventSink.ABSENT, EventSink.ABSENT, null, null);

        JSONObject dec = written(record).getJSONObject("dec");
        assertEquals("rot 0 is a value", 0, dec.getInt("rot"));
        assertEquals("own is written even when nothing claimed", 0,
                dec.getJSONObject("own").length());
        for (String member : new String[] {"cfc", "draw", "nav", "inp", "restart", "bmc", "strat",
                "buf", "trig", "navf", "cf", "wtgsrc"}) {
            assertFalse(member + " is omitted at its default", dec.has(member));
        }
    }

    @Test
    public void aRestartRecordNamesItsKindAndHostileStringsKeepTheLineIntact() throws Exception {
        StepRecord record = opened();
        record.rotation(-1);
        String hostileOwner = "restart\n\"x ";
        String hostileFallback = "fb\u0000\r\n";
        record.owner(hostileOwner, null, Double.NaN, Double.NaN, null, hostileFallback);
        record.restart("periodic");
        record.decisionNonModel("EVENT_RESTART", "restart", "restart");

        JSONObject dec = written(record).getJSONObject("dec");
        assertEquals("periodic", dec.getString("restart"));
        assertEquals(-1, dec.getInt("rot"));
        assertEquals(hostileOwner, dec.getJSONObject("own").getString("i"));
        assertEquals(hostileFallback, dec.getJSONObject("own").getString("fb"));
    }

    @Test
    public void resetClearsEveryNewMember() throws Exception {
        StepRecord record = opened();
        record.rotation(3);
        record.owner("mop", null, Double.NaN, 0.1d, null, null);
        record.cfc("navf", true);
        record.draw(1, 2);
        record.nav("X1", "ape", false, false);
        record.input("form");
        record.restart("state");
        record.backMenuCapped(1);
        record.strategy("buffer");
        record.bufferAbandoned("llm");
        record.componentTrigger("provider", "com.foo.Provider");
        record.reset();
        record.open(43, 2L, 0, 0);
        record.decision("model=BACK", "sata", "sata_other", 1,
                0, 0, 0, 0, 0, 0, 0, EventSink.ABSENT, EventSink.ABSENT, null, null);

        JSONObject dec = written(record).getJSONObject("dec");
        assertEquals(0, dec.getJSONObject("own").length());
        for (String member : new String[] {"rot", "cfc", "draw", "nav", "inp", "restart", "bmc",
                "strat", "buf", "trig"}) {
            assertFalse(member + " survived reset", dec.has(member));
        }
    }
}
