package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import org.junit.Before;
import com.android.commands.monkey.ape.StopTestingException;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.StaticArtifactLoader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * gh13 §19 — StatefulAgent tuple-based component triggering (T1.4+T1.5), as a STATIC member
 * (component-triggering, design D-B13).
 *
 * Exercises the static, side-effect-free tuple/command/log/intent-data builders. The pool reads
 * the static manifest's receivers, services and providers and no MOP data (INV-CT-16); activities
 * never reach it, since only receivers and services are handed to it. The dispatch side (Intent
 * send / shell exec) requires the Android runtime and is covered by the device smoke.
 */
public class StatefulAgentTriggerTest {

    /**
     * {@code MopData.load} reads the MOP activity source from the plan (guidance-groups
     * INV-GRP-06), so every case runs under one: the jar defaults, whose source is the widgets.
     */
    @Before
    public void installThePlan() {
        TestRunSpecs.install();
    }

    private static ComponentInfo.IntentFilter filter(List<String> actions, List<String> cats) {
        return new ComponentInfo.IntentFilter(actions, cats);
    }

    private static ComponentInfo.IntentFilter filterWithData(String action,
            ComponentInfo.IntentFilter.Data data) {
        return new ComponentInfo.IntentFilter(Arrays.asList(action),
                Collections.<String>emptyList(), data);
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> none() {
        return Collections.<String>emptyList();
    }

    private static ComponentInfo.ReceiverInfo receiver(String name,
            ComponentInfo.IntentFilter... filters) {
        return new ComponentInfo.ReceiverInfo(name, false, Arrays.asList(filters));
    }

    private static ComponentInfo.ServiceInfo service(String name,
            ComponentInfo.IntentFilter... filters) {
        return new ComponentInfo.ServiceInfo(name, false, Arrays.asList(filters));
    }

    private static ComponentInfo.ProviderInfo provider(String name, String auth) {
        return new ComponentInfo.ProviderInfo(name, false,
                Collections.<ComponentInfo.IntentFilter>emptyList(), auth);
    }

    private static List<String> names(List<StatefulAgent.TriggerTuple> tuples) {
        List<String> names = new ArrayList<>();
        for (StatefulAgent.TriggerTuple t : tuples) names.add(t.component.className);
        return names;
    }

    @Test // INV-CT-16: the pool is the manifest's, in manifest order; no MOP fact is an input
    public void thePoolReadsNoMopData() {
        List<ComponentInfo.ReceiverInfo> receivers = Arrays.asList(
                receiver("p.R1", filter(list("a"), none())),
                receiver("p.R2", filter(list("b"), none())));
        assertEquals(list("p.R1", "p.R2"), names(StatefulAgent.buildTriggerTuples(receivers, null)));
        List<StatefulAgent.ProviderTuple> providers = StatefulAgent.buildProviderTuples(
                Arrays.asList(provider("p.P", "p.auth")));
        assertEquals("every provider with authorities is triggered", 3, providers.size());
    }

    @Test // 19.3
    public void testTriggerRoundRobinsAllIntentFilterActions() {
        List<StatefulAgent.TriggerTuple> tuples = StatefulAgent.buildTriggerTuples(
                Arrays.asList(receiver("p.R", filter(list("action1", "action2"), none()))),
                null);
        assertEquals(2, tuples.size());
        List<String> actions = new ArrayList<>();
        for (StatefulAgent.TriggerTuple t : tuples) actions.add(t.action);
        assertTrue(actions.contains("action1"));
        assertTrue(actions.contains("action2"));
    }

    @Test // 19.4
    public void testTriggerProviderRoundRobinsOperations() {
        List<StatefulAgent.ProviderTuple> tuples = StatefulAgent.buildProviderTuples(
                Arrays.asList(provider("p.Prov", "p.auth")));
        assertEquals(3, tuples.size());
        assertEquals("query", tuples.get(0).operation);
        assertEquals("insert", tuples.get(1).operation);
        assertEquals("update", tuples.get(2).operation);
    }

    @Test // 19.5
    public void testTriggerLogsContainExpectedFields() {
        StatefulAgent.TriggerTuple t = StatefulAgent.buildTriggerTuples(Arrays.asList(
                receiver("p.R", filter(list("a1"), list("c1", "c2")))), null).get(0);
        String line = StatefulAgent.triggerLogLine(t);
        assertTrue(line.contains("p.R"));
        assertTrue(line.contains("action=a1"));
        assertTrue(line.contains("categories=c1,c2"));
        assertFalse("the line claims no reachability (INV-CT-16)", line.contains("reachesTarget"));
    }

    @Test // 19.7: a provider without authorities and a filterless receiver yield nothing
    public void testTriggerReturnsEmptyWhenNothingIsTriggerable() {
        assertTrue(StatefulAgent.buildTriggerTuples(
                Arrays.asList(receiver("p.R")), null).isEmpty());
        assertTrue(StatefulAgent.buildProviderTuples(
                Arrays.asList(provider("p.P", ""))).isEmpty());
        assertTrue(StatefulAgent.buildTriggerTuples(null, null).isEmpty());
        assertTrue(StatefulAgent.buildProviderTuples(null).isEmpty());
    }

    @Test // 19.8 (D-B14.15): a filterless service yields a name-only tuple
    public void testFilterlessServiceYieldsComponentNameOnlyTuple() {
        List<StatefulAgent.TriggerTuple> tuples = StatefulAgent.buildTriggerTuples(null,
                Arrays.asList(service("p.S")));
        assertEquals(1, tuples.size());
        assertNull("component-name-only ⇒ no filter", tuples.get(0).filter);
        assertNull("component-name-only ⇒ no action", tuples.get(0).action);
        assertEquals("service", StatefulAgent.triggerKind(tuples.get(0).component));
    }

    @Test // 19.9
    public void testProviderContentCommandShape() {
        List<StatefulAgent.ProviderTuple> tuples = StatefulAgent.buildProviderTuples(
                Arrays.asList(provider("p.Prov", "p.auth")));
        String[] query = StatefulAgent.buildContentCommand(tuples.get(0));
        assertArrayEquals(new String[]{"content", "query", "--uri", "content://p.auth"}, query);
        String[] insert = StatefulAgent.buildContentCommand(tuples.get(1));
        assertEquals("insert", insert[1]);
        assertEquals("--bind", insert[4]);
    }

    // 8.9.5 — INV-CT-17: the intent carries the filter's data and type

    @Test // scheme + MIME: setDataAndType
    public void bothSchemeAndMimeYieldDataAndType() {
        ComponentInfo.IntentFilter f = filterWithData("com.x.IMPORT", new ComponentInfo.IntentFilter.Data(
                list("content"), list("com.x.files"), list("/in"), none(), list("text/csv", "text/plain")));
        assertArrayEquals(new String[] {"content://com.x.files/in", "text/csv"},
                StatefulAgent.intentDataAndType(f));
    }

    @Test // scheme only: setData, with empty host and path
    public void aSchemeOnlyYieldsDataWithoutType() {
        ComponentInfo.IntentFilter f = filterWithData("a", new ComponentInfo.IntentFilter.Data(
                list("myapp"), none(), none(), none(), none()));
        assertArrayEquals(new String[] {"myapp://", null}, StatefulAgent.intentDataAndType(f));
    }

    @Test // MIME only: setType
    public void aMimeOnlyYieldsTypeWithoutData() {
        ComponentInfo.IntentFilter f = filterWithData("a", new ComponentInfo.IntentFilter.Data(
                none(), none(), none(), none(), list("image/png")));
        assertArrayEquals(new String[] {null, "image/png"}, StatefulAgent.intentDataAndType(f));
    }

    @Test // the first path prefix stands in for an absent path
    public void aPathPrefixStandsInForAnAbsentPath() {
        ComponentInfo.IntentFilter f = filterWithData("a", new ComponentInfo.IntentFilter.Data(
                list("https"), list("x.org"), none(), list("/docs"), none()));
        assertArrayEquals(new String[] {"https://x.org/docs", null},
                StatefulAgent.intentDataAndType(f));
    }

    @Test // no data member, or a name-only tuple: no data and no type
    public void noDataMemberYieldsNeither() {
        assertArrayEquals(new String[] {null, null},
                StatefulAgent.intentDataAndType(filter(list("a"), none())));
        assertArrayEquals(new String[] {null, null}, StatefulAgent.intentDataAndType(null));
        assertEquals("broadcast", StatefulAgent.triggerKind(receiver("p.R")));
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
        java.net.URL fixture = StatefulAgentTriggerTest.class.getResource("/cryptoapp.apk.mop.json");
        StaticArtifactLoader.Loaded d = StaticArtifactLoader.load(
                new java.io.File(fixture.getFile()).getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(d);
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

        StaticArtifactLoader.Loaded loaded =
                StaticArtifactLoader.load(path, null, null, new NoopSink());
        assertNull("a full static-analysis JSON carries no formatVersion (INV-MOP-34)", loaded);
        try {
            StatefulAgent.requireMopArm(loaded, path);
            fail("expected StopTestingException rather than a silent SATA run");
        } catch (StopTestingException expected) {
            // ok
        }
    }
}
