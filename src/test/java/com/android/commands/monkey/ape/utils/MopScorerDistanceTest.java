package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.agent.scoring.MopWidgetPass;
import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * mop-guidance "MopScorer — Priority Boost", the {@code distance} mode and the tie split
 * (llm-coordinate-single-base, design D18, task 13.3): the weight of the nearest unretired target
 * (500/500/400/300/0 for d = 0/1/2/3/4, 0 with no pair), the split among the actions tied at the
 * state's best weight level as {@code max(W / n, W_next)}, the undivided weight kept beside the
 * split one as {@code mopWeight}, and {@code flag} unchanged on the format-2 fixture.
 *
 * <p>Single-action scenarios go through {@link MopScorer#score}; the split needs every action of the
 * state, so those go through {@link MopWidgetPass#apply}, the one place it is applied.
 */
public class MopScorerDistanceTest {

    private static final String ACT = "com.x.MainActivity";

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** A format-2 artifact whose widgets {@code w<k>} carry the given click pairs (or none). */
    private static MopData artifact(String widgetsOfAct) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{}}";
        File f = File.createTempFile("mopdistance", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(data);
        return data;
    }

    /** A widget entry {@code "id":{"mop":{"click":"transitive"},"dist":{"click":[[i,d]]}}}. */
    private static String widget(String id, int i, int d) {
        return "\"" + id + "\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[" + i + "," + d + "]]}}";
    }

    private static MopData cryptoapp() {
        java.net.URL url = MopScorerDistanceTest.class.getResource("/cryptoapp.apk.mop.json");
        assertNotNull("fixture not on classpath", url);
        MopData data = MopData.load(new File(url.getFile()).getAbsolutePath(), null, null,
                new NoopSink());
        assertNotNull(data);
        return data;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static void set(Object target, Class<?> owner, String field, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static GUITreeNode node(String shortId) {
        GUITreeNode n = new GUITreeNode(null);
        n.setClassName("android.widget.Button");
        if (shortId != null) {
            n.setResourceID("com.x:id/" + shortId);
        }
        return n;
    }

    private static ModelAction click(String shortId) throws Exception {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setValid(true);
        set(a, ModelAction.class, "resolvedNode", node(shortId));
        Field ts = ModelAction.class.getDeclaredField("resovledTimestamp");
        ts.setAccessible(true);
        ts.setInt(a, 1);
        return a;
    }

    private static State state(String activity, ModelAction... actions) throws Exception {
        StateKey key = (StateKey) allocate(StateKey.class);
        set(key, StateKey.class, "activity", activity);
        set(key, StateKey.class, "widgets", new Name[0]); // the pass's log line prints the key
        State state = (State) allocate(State.class);
        set(state, State.class, "stateKey", key);
        set(state, State.class, "actions", actions);
        return state;
    }

    private static MopContext distance(MopData data, MopRetirement retirement) {
        return new MopContext(data, MopScoring.DISTANCE, 500, 300, 500, 400, 300, 3, 6,
                retirement);
    }

    /** Runs the MOP widget pass over {@code actions} on a state of {@code activity}. */
    private static void pass(MopContext mop, String activity, ModelAction... actions)
            throws Exception {
        ScoringContext ctx = new ScoringContext() {
            @Override public MopContext getMopContext() { return mop; }
            @Override public UICoverageTracker getCoverageTracker() { return new UICoverageTracker(); }
            @Override public Graph getGraph() { return null; }
            @Override public int getTimestamp() { return 1; }
            @Override public boolean menuPickEligible(String a) { return true; }
        };
        for (ModelAction a : actions) {
            a.resetBoosts();
            a.setPriority(10);
        }
        new MopWidgetPass(ctx, new NoopSink()).apply(state(activity, actions), actions, ctx);
    }

    private static void assertBoost(String what, int weight, int boost, ModelAction a) {
        assertEquals(what + ": mopWeight", weight, a.getMopWeight());
        assertEquals(what + ": mopBoost", boost, a.getMopBoost());
        assertEquals(what + ": priority", 10 + boost, a.getPriority());
    }

    // -------------------------------------------------------------------------
    // The weight of the nearest unretired target
    // -------------------------------------------------------------------------

    /** Scenario "Distance weights by nearest unretired target". */
    @Test
    public void weightsByDistanceLevel() throws Exception {
        MopData data = artifact(widget("w0", 0, 0) + "," + widget("w1", 1, 1) + ","
                + widget("w2", 2, 2) + "," + widget("w3", 3, 3) + "," + widget("w4", 4, 4)
                + ",\"wn\":{\"mop\":{\"click\":\"transitive\"}}");
        MopContext mop = distance(data, null);
        int[] expected = {500, 500, 400, 300, 0};
        for (int d = 0; d <= 4; d++) {
            ModelAction a = click("w" + d);
            assertEquals("d = " + d, expected[d], MopScorer.score(mop, state(ACT, a), a));
        }
        ModelAction noPair = click("wn");
        assertEquals("a transitive flag without a pair weighs 0 under distance",
                0, MopScorer.score(mop, state(ACT, noPair), noPair));
        ModelAction unknown = click("nothing");
        assertEquals(0, MopScorer.score(mop, state(ACT, unknown), unknown));
    }

    @Test
    public void theLevelMappingIsTheContextsWeights() throws Exception {
        MopContext mop = new MopContext(artifact(""), MopScoring.DISTANCE, 500, 300, 41, 31, 23,
                3, 6, null);
        assertEquals(41, MopScorer.distanceWeight(mop, 0));
        assertEquals(41, MopScorer.distanceWeight(mop, 1));
        assertEquals(31, MopScorer.distanceWeight(mop, 2));
        assertEquals(23, MopScorer.distanceWeight(mop, 3));
        assertEquals(0, MopScorer.distanceWeight(mop, 4));
        assertEquals(0, MopScorer.distanceWeight(mop, 10));
        assertEquals(0, MopScorer.distanceWeight(mop, Integer.MAX_VALUE));
    }

    /** The nearest pair of all the containment lookups decides; a pass-through own d = 3 loses. */
    @Test
    public void theSmallestDistanceOverTheContainmentLookupsWins() throws Exception {
        MopData data = artifact(widget("self", 1, 3) + "," + widget("kid", 2, 1));
        GUITreeNode self = node("self");
        GUITreeNode kid = new GUITreeNode(self);
        kid.setResourceID("com.x:id/kid");
        self.addChild(kid);
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setValid(true);
        set(a, ModelAction.class, "resolvedNode", self);
        MopScorer.Scored scored = MopScorer.weigh(distance(data, null), state(ACT, a), a);
        assertEquals(500, scored.weight);
        assertEquals(1, scored.distance);
        assertEquals(1, scored.index);
    }

    /** Scenario "retired target ignored by distance scoring" (mop-guidance "Target Retirement"). */
    @Test
    public void aRetiredTargetIsSkippedInFavorOfTheNextPair() throws Exception {
        MopData data = artifact("\"w\":{\"mop\":{\"click\":\"direct\"},"
                + "\"dist\":{\"click\":[[4,1],[7,2]]}}");
        MopRetirement r = new MopRetirement(data.targetCount(), 1);
        MopContext mop = distance(data, r);
        ModelAction a = click("w");
        assertEquals(500, MopScorer.score(mop, state(ACT, a), a));

        r.onFirstInteraction(new MopData.Lookup(false, false, new int[][] {{4, 1}},
                MopData.Lookup.Source.ID));
        assertEquals("target 4 retired: d = 2", 400, MopScorer.score(mop, state(ACT, a), a));

        r.onFirstInteraction(new MopData.Lookup(false, false, new int[][] {{7, 2}},
                MopData.Lookup.Source.ID));
        assertEquals("both retired", 0, MopScorer.score(mop, state(ACT, a), a));
    }

    // -------------------------------------------------------------------------
    // The tie split
    // -------------------------------------------------------------------------

    /** Scenario "Weight split among the actions tied at the state's best distance", first part. */
    @Test
    public void twoAtD1SplitWithTheFloorAndTheWorseLevelKeepsItsWeight() throws Exception {
        MopData data = artifact(widget("a", 1, 1) + "," + widget("b", 2, 1) + ","
                + widget("c", 3, 3));
        ModelAction a = click("a");
        ModelAction b = click("b");
        ModelAction c = click("c");
        ModelAction none = click("none");
        pass(distance(data, null), ACT, a, b, c, none);
        assertBoost("d = 1", 500, 400, a);
        assertBoost("d = 1", 500, 400, b);
        assertBoost("d = 3, a worse level, undivided", 300, 300, c);
        assertBoost("no pair", 0, 0, none);
    }

    /** Scenario "d = 0 and d = 1 are one tie level". */
    @Test
    public void d0AndD1AreOneTieLevel() throws Exception {
        MopData data = artifact(widget("a", 1, 0) + "," + widget("b", 2, 0) + ","
                + widget("c", 3, 1));
        ModelAction a = click("a");
        ModelAction b = click("b");
        ModelAction c = click("c");
        pass(distance(data, null), ACT, a, b, c);
        assertBoost("d = 0", 500, 400, a);
        assertBoost("d = 0", 500, 400, b);
        assertBoost("d = 1", 500, 400, c);
    }

    @Test
    public void threeAtD2SplitToTheD3Floor() throws Exception {
        MopData data = artifact(widget("a", 1, 2) + "," + widget("b", 2, 2) + ","
                + widget("c", 3, 2));
        ModelAction a = click("a");
        ModelAction b = click("b");
        ModelAction c = click("c");
        pass(distance(data, null), ACT, a, b, c);
        for (ModelAction x : new ModelAction[] {a, b, c}) {
            assertBoost("d = 2 x3", 400, 300, x);
        }
    }

    @Test
    public void fourAtD3SplitWithoutAFloor() throws Exception {
        MopData data = artifact(widget("a", 1, 3) + "," + widget("b", 2, 3) + ","
                + widget("c", 3, 3) + "," + widget("d", 4, 3));
        ModelAction[] all = {click("a"), click("b"), click("c"), click("d")};
        pass(distance(data, null), ACT, all);
        for (ModelAction x : all) {
            assertBoost("d = 3 x4", 300, 75, x);
        }
    }

    @Test
    public void aSingleActionAtTheBestLevelIsNotSplit() throws Exception {
        MopData data = artifact(widget("a", 1, 2) + "," + widget("b", 2, 3) + ","
                + widget("c", 3, 3));
        ModelAction a = click("a");
        ModelAction b = click("b");
        ModelAction c = click("c");
        pass(distance(data, null), ACT, a, b, c);
        assertBoost("alone at d = 2", 400, 400, a);
        assertBoost("d = 3, worse level", 300, 300, b);
        assertBoost("d = 3, worse level", 300, 300, c);
    }

    /** The split routine itself: integer division, the next level's floor, flag untouched. */
    @Test
    public void splitTiesIsMaxOfTheShareAndTheNextLevel() throws Exception {
        MopData data = artifact("");
        MopContext mop = distance(data, null);
        assertArrayEquals(new int[] {400, 400, 300, 0},
                MopScorer.splitTies(mop, new int[] {500, 500, 300, 0}));
        assertArrayEquals(new int[] {300, 300, 300},
                MopScorer.splitTies(mop, new int[] {400, 400, 400}));
        assertArrayEquals(new int[] {75, 75, 75, 75},
                MopScorer.splitTies(mop, new int[] {300, 300, 300, 300}));
        assertArrayEquals(new int[] {0, 0}, MopScorer.splitTies(mop, new int[] {0, 0}));
        assertArrayEquals(new int[0], MopScorer.splitTies(mop, new int[0]));
        // A share above the floor: W = 900, next 100, n = 2 -> 450.
        MopContext wide = new MopContext(data, MopScoring.DISTANCE, 500, 300, 900, 100, 50, 3, 6,
                null);
        assertArrayEquals(new int[] {450, 450, 50},
                MopScorer.splitTies(wide, new int[] {900, 900, 50}));
        // flag: no split.
        assertArrayEquals(new int[] {500, 500, 300},
                MopScorer.splitTies(TestMopContexts.flag(data, 500, 300),
                        new int[] {500, 500, 300}));
    }

    // -------------------------------------------------------------------------
    // mopWeight beside mopBoost
    // -------------------------------------------------------------------------

    @Test
    public void resetBoostsResetsTheWeightWithTheBoost() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(400);
        a.setMopWeight(500);
        a.resetBoosts();
        assertEquals(0, a.getMopBoost());
        assertEquals(0, a.getMopWeight());
    }

    /** Under flag, two direct actions keep the full weight each, and mopWeight equals mopBoost. */
    @Test
    public void underFlagTheWeightEqualsTheBoostAndNothingIsSplit() throws Exception {
        MopData data = artifact("\"a\":{\"mop\":{\"click\":\"direct\"}},"
                + "\"b\":{\"mop\":{\"click\":\"direct\"}},"
                + "\"c\":{\"mop\":{\"click\":\"transitive\"}}");
        ModelAction a = click("a");
        ModelAction b = click("b");
        ModelAction c = click("c");
        pass(TestMopContexts.flag(data, 500, 300), ACT, a, b, c);
        assertBoost("direct", 500, 500, a);
        assertBoost("direct", 500, 500, b);
        assertBoost("transitive", 300, 300, c);
    }

    // -------------------------------------------------------------------------
    // The format-2 fixture, unstamped
    // -------------------------------------------------------------------------

    /** Scenario "flag mode reproduces the E6 mark on an unstamped APK". */
    @Test
    public void flagReproducesTheE6MarkAndDistanceWeighsTheSameClicks() throws Exception {
        MopData data = cryptoapp();
        String[][] cases = {
                {"br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity", "buttonGenerateHash", "400"},
                {"br.unb.cic.cryptoapp.cipher.CipherActivity", "btn_cipher_encrypt", "0"},
                {"br.unb.cic.cryptoapp.generated.CryptographyActivity", "executeButton", "300"},
        };
        MopContext flag = TestMopContexts.defaults(data);
        assertEquals(MopScoring.FLAG, flag.scoring());
        MopContext distance = distance(data, new MopRetirement(data.targetCount(), 3));
        for (String[] c : cases) {
            ModelAction a = click(c[1]);
            State st = state(c[0], a);
            assertEquals(c[1] + " under flag", 300, MopScorer.score(flag, st, a));
            assertEquals(c[1] + " under distance", Integer.parseInt(c[2]),
                    MopScorer.score(distance, st, a));

            pass(flag, c[0], a);
            assertBoost(c[1] + " flag pass", 300, 300, a);
            pass(distance, c[0], a);
            int w = Integer.parseInt(c[2]);
            assertBoost(c[1] + " distance pass, alone", w, w, a);
        }
    }
}
