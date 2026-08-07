package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.StopTestingException;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * gh13 §19 — StatefulAgent tuple-based component triggering (T1.4+T1.5).
 *
 * Exercises the static, side-effect-free tuple/command/log builders. The dispatch side
 * (Intent send / shell exec) requires the Android runtime and is covered by the deferred
 * integration smoke (§22).
 */
public class StatefulAgentTriggerTest {

    private static ComponentInfo.IntentFilter filter(List<String> actions, List<String> cats) {
        return new ComponentInfo.IntentFilter(actions, cats);
    }

    private static MopData data(List<ComponentInfo.ReceiverInfo> r,
                                List<ComponentInfo.ServiceInfo> s,
                                List<ComponentInfo.ActivityInfo> a,
                                List<ComponentInfo.ProviderInfo> p) {
        return MopData.forTest(null, null, null, r, s, a, p);
    }

    @Test // 19.1
    public void testTriggerSkipsNonReachableComponents() {
        ComponentInfo.ReceiverInfo reaches = new ComponentInfo.ReceiverInfo("p.R1", false,
                Arrays.asList(filter(Arrays.asList("a"), Collections.<String>emptyList())),
                true, Collections.<String>emptyList());
        ComponentInfo.ReceiverInfo noReach = new ComponentInfo.ReceiverInfo("p.R2", false,
                Arrays.asList(filter(Arrays.asList("b"), Collections.<String>emptyList())),
                false, Collections.<String>emptyList());
        MopData d = data(Arrays.asList(reaches, noReach), null, null, null);
        List<StatefulAgent.TriggerTuple> tuples = StatefulAgent.buildTriggerTuples(d);
        for (StatefulAgent.TriggerTuple t : tuples) {
            assertNotEquals("p.R2", t.component.className);
        }
        assertEquals(1, tuples.size());
    }

    @Test // 19.2 (activity-frontier): activities NEVER enter the probabilistic tuple pool,
    // exported or not — the old activityTriggerEnabled branch is deleted; activities are launched
    // only by the stagnation launcher (EVENT_TRIGGER_ACTIVITY).
    public void testTriggerNeverIncludesActivities() {
        ComponentInfo.ActivityInfo exported = new ComponentInfo.ActivityInfo("p.Exported", false,
                Arrays.asList(filter(Arrays.asList("a"), Collections.<String>emptyList())),
                true, Collections.<String>emptyList());
        ComponentInfo.ActivityInfo nonExported = new ComponentInfo.ActivityInfo("p.Hidden", false,
                Arrays.asList(filter(Arrays.asList("b"), Collections.<String>emptyList())),
                true, Collections.<String>emptyList());
        MopData d = data(null, null, Arrays.asList(exported, nonExported), null);
        assertTrue(StatefulAgent.buildTriggerTuples(d).isEmpty());
    }

    @Test // 19.3
    public void testTriggerRoundRobinsAllIntentFilterActions() {
        ComponentInfo.ReceiverInfo r = new ComponentInfo.ReceiverInfo("p.R", false,
                Arrays.asList(filter(Arrays.asList("action1", "action2"), Collections.<String>emptyList())),
                true, Collections.<String>emptyList());
        List<StatefulAgent.TriggerTuple> tuples = StatefulAgent.buildTriggerTuples(data(
                Arrays.asList(r), null, null, null));
        assertEquals(2, tuples.size());
        List<String> actions = new ArrayList<>();
        for (StatefulAgent.TriggerTuple t : tuples) actions.add(t.action);
        assertTrue(actions.contains("action1"));
        assertTrue(actions.contains("action2"));
    }

    @Test // 19.4
    public void testTriggerProviderRoundRobinsOperations() {
        ComponentInfo.ProviderInfo p = new ComponentInfo.ProviderInfo("p.Prov", false,
                Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                Collections.<String>emptyList(), "p.auth");
        List<StatefulAgent.ProviderTuple> tuples = StatefulAgent.buildProviderTuples(data(
                null, null, null, Arrays.asList(p)));
        assertEquals(3, tuples.size());
        assertEquals("query", tuples.get(0).operation);
        assertEquals("insert", tuples.get(1).operation);
        assertEquals("update", tuples.get(2).operation);
    }

    @Test // 19.5
    public void testTriggerLogsContainExpectedFields() {
        ComponentInfo.ReceiverInfo r = new ComponentInfo.ReceiverInfo("p.R", false,
                Arrays.asList(filter(Arrays.asList("a1"), Arrays.asList("c1", "c2"))),
                true, Collections.<String>emptyList());
        StatefulAgent.TriggerTuple t = StatefulAgent.buildTriggerTuples(data(
                Arrays.asList(r), null, null, null)).get(0);
        String line = StatefulAgent.triggerLogLine(t);
        assertTrue(line.contains("p.R"));
        assertTrue(line.contains("action=a1"));
        assertTrue(line.contains("categories=c1,c2"));
        assertTrue(line.contains("reachesTarget=true"));
    }

    @Test // 19.6 (activity-frontier, INV-MOP-15): activities are excluded from the tuple pool
    // unconditionally — Config.activityTriggerEnabled now gates ONLY the stagnation launcher, not
    // buildTriggerTuples. Only the receiver survives; the activity never appears regardless of flag.
    public void testActivityExcludedFromTupleListUnconditionally() {
        ComponentInfo.ActivityInfo act = new ComponentInfo.ActivityInfo("p.A", false,
                Arrays.asList(filter(Arrays.asList("a"), Collections.<String>emptyList())),
                true, Collections.<String>emptyList());
        ComponentInfo.ReceiverInfo rec = new ComponentInfo.ReceiverInfo("p.R", false,
                Arrays.asList(filter(Arrays.asList("b"), Collections.<String>emptyList())),
                true, Collections.<String>emptyList());
        MopData d = data(Arrays.asList(rec), null, Arrays.asList(act), null);

        List<StatefulAgent.TriggerTuple> tuples = StatefulAgent.buildTriggerTuples(d);
        assertEquals(1, tuples.size());
        assertEquals("p.R", tuples.get(0).component.className);
    }

    @Test // 19.7
    public void testTriggerReturnsFalseOnEmptyComponentList() {
        // cryptoapp-like: all components reachesTarget=false ⇒ no tuples ⇒ triggerMopComponent no-op.
        ComponentInfo.ActivityInfo a = new ComponentInfo.ActivityInfo("p.A", true,
                Collections.<ComponentInfo.IntentFilter>emptyList(), false, Collections.<String>emptyList());
        ComponentInfo.ProviderInfo p = new ComponentInfo.ProviderInfo("p.P", false,
                Collections.<ComponentInfo.IntentFilter>emptyList(), false,
                Collections.<String>emptyList(), "p.auth");
        MopData d = data(null, null, Arrays.asList(a), Arrays.asList(p));
        assertTrue(StatefulAgent.buildTriggerTuples(d).isEmpty());
        assertTrue(StatefulAgent.buildProviderTuples(d).isEmpty());
    }

    @Test // 19.8 (D15)
    public void testTriggerEmitsComponentNameOnlyTupleWhenFiltersEmpty() {
        ComponentInfo.ReceiverInfo r = new ComponentInfo.ReceiverInfo("p.R", false,
                Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                Arrays.asList("<p.R: void onReceive()>"));
        List<StatefulAgent.TriggerTuple> tuples = StatefulAgent.buildTriggerTuples(data(
                Arrays.asList(r), null, null, null));
        assertEquals(1, tuples.size());
        assertNull("component-name-only ⇒ no filter", tuples.get(0).filter);
        assertNull("component-name-only ⇒ no action", tuples.get(0).action);
    }

    @Test // 19.9
    public void testProviderContentCommandShape() {
        ComponentInfo.ProviderInfo p = new ComponentInfo.ProviderInfo("p.Prov", false,
                Collections.<ComponentInfo.IntentFilter>emptyList(), true,
                Collections.<String>emptyList(), "p.auth");
        List<StatefulAgent.ProviderTuple> tuples = StatefulAgent.buildProviderTuples(data(
                null, null, null, Arrays.asList(p)));
        String[] query = StatefulAgent.buildContentCommand(tuples.get(0));
        assertArrayEquals(new String[]{"content", "query", "--uri", "content://p.auth"}, query);
        String[] insert = StatefulAgent.buildContentCommand(tuples.get(1));
        assertEquals("insert", insert[1]);
        assertEquals("--bind", insert[4]);
    }

    // 5.4 — INV-MOP-22 fail-fast: mopDataPath set but load failed → abort, never run as pure SATA
    @Test
    public void testRequireMopArmThrowsWhenPathSetAndLoadFailed() {
        try {
            StatefulAgent.requireMopArm(null, "/data/local/tmp/ape.mop.json");
            fail("expected StopTestingException when path is set and load returned null");
        } catch (StopTestingException expected) {
            // ok
        }
    }

    @Test
    public void testRequireMopArmPassesThroughWhenLoaded() {
        MopData d = data(null, null, null, null);
        assertSame(d, StatefulAgent.requireMopArm(d, "/data/local/tmp/ape.mop.json"));
    }

    @Test
    public void testRequireMopArmNoThrowWhenPathUnset() {
        // mopDataPath unset → MOP disabled, null is a valid outcome (SATA as today)
        assertNull(StatefulAgent.requireMopArm(null, null));
    }

    /**
     * The version-skew drill, end to end at JVM level: a pre-change full static-analysis JSON
     * reaching the post-change jar.
     *
     * <p>The two halves are asserted separately elsewhere — the loader's rejection in
     * {@code MopDataTest}, the abort in the three tests above — and separately they leave the
     * question the drill actually asks unanswered, because it is a question about the join: does
     * the null a rejected artifact produces reach the code that aborts on it? A deployment where
     * the wrong document was pushed either stops loudly here or explores as pure SATA while
     * reporting itself a MOP arm, which is the silent-degradation class this composition exists to
     * close. What no JVM test can attest is that the <em>deployed</em> pair behaves this way; that
     * half is the counterpart campaign's pre-flight.
     */
    @Test
    public void testLegacyJsonRejectionAbortsTheMopArm() {
        java.net.URL fixture = StatefulAgentTriggerTest.class
                .getResource("/cryptoapp.apk.gh60-fresh.json");
        assertNotNull("legacy full-JSON fixture not on classpath", fixture);
        String path = new java.io.File(fixture.getFile()).getAbsolutePath();

        MopData loaded = MopData.load(path, null, null, new NoopSink());
        assertNull("a full static-analysis JSON carries no formatVersion (INV-MOP-34)", loaded);
        try {
            StatefulAgent.requireMopArm(loaded, path);
            fail("expected StopTestingException rather than a silent SATA run");
        } catch (StopTestingException expected) {
            // ok
        }
    }
}
