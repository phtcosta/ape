package com.android.commands.monkey.ape.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The distance-pair cut at artifact load (mop-guidance "Distance-Pair Cut at Load", "Cut Pairs
 * Counted Apart From Malformed Pairs", "Cut Applied Before the Merges", "No Cut Outside the
 * Distance Mode", "Readers Unchanged on the Remaining Pairs"; design D5, D6, D7).
 *
 * <p>Synthetic format-2 artifacts are written to a temporary folder. The cases that pin the
 * pair reader call the loader's test seam with an explicit cut; the cases about where the cut
 * comes from install a plan and call the public {@code load}.
 */
public class MopDataCutTest {

    private static final String ACT = "p.A";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void resetThePlan() {
        RunContext.resetForTest();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** A format-2 artifact for activity {@code p.A}: the given targets and members. */
    private String artifact(int targets, String members) throws Exception {
        File f = folder.newFile();
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"formatVersion\":2,\"package\":\"p\",\"mainActivity\":\"" + ACT + "\","
                    + "\"targets\":" + targets + ",\"mopActivities\":[\"" + ACT + "\"]" + members + "}");
        }
        return f.getAbsolutePath();
    }

    /** A loaded artifact and the one {@code MOP_DATA} record its load wrote. */
    private static final class Load {
        final MopData data;
        final JSONObject record;

        Load(MopData data, JSONObject record) {
            this.data = data;
            this.record = record;
        }
    }

    /** Through the test seam, with an explicit cut. */
    private static Load seam(String path, int cut) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StaticArtifactLoader.Loaded loaded = StaticArtifactLoader.load(path, null, null, false, cut,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8")));
        return new Load(loaded == null ? null : loaded.mopData, onlyRecord(buffer));
    }

    /** Through the public entry, under the installed plan. */
    private static Load planned(String path) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        StaticArtifactLoader.Loaded loaded = StaticArtifactLoader.load(path, null, null,
                new NdjsonSink(new PrintStream(buffer, true, "UTF-8")));
        return new Load(loaded == null ? null : loaded.mopData, onlyRecord(buffer));
    }

    private static JSONObject onlyRecord(ByteArrayOutputStream buffer) throws Exception {
        String[] lines = new String(buffer.toByteArray(), StandardCharsets.UTF_8).split("\\n");
        assertEquals("one MOP_DATA record per load", 1, lines.length);
        JSONObject record = new JSONObject(lines[0]);
        assertEquals("MOP_DATA", record.getString("type"));
        assertEquals("loaded", record.getString("status"));
        return record;
    }

    private static String pairs(int[][] pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i++) {
            sb.append(i == 0 ? "" : ",").append(Arrays.toString(pairs[i]).replace(" ", ""));
        }
        return sb.append("]").toString();
    }

    /** {@code "[[i,d],...]"} over the given distances, target index = position + {@code first}. */
    private static String list(int first, int... distances) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < distances.length; k++) {
            sb.append(k == 0 ? "" : ",").append('[').append(first + k).append(',')
                    .append(distances[k]).append(']');
        }
        return sb.append(']').toString();
    }

    // -------------------------------------------------------------------------
    // The pair reader: what is kept and how it is counted
    // -------------------------------------------------------------------------

    /** Scenario "Cut below the generator's depth". */
    @Test
    public void aCutBelowTheGeneratorsDepthKeepsThePairsUpToIt() throws Exception {
        String path = artifact(4, ",\"handlers\":{},\"widgets\":{\"" + ACT + "\":{\"b\":{"
                + "\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[1,1],[2,2],[3,3]]}}}}");
        Load load = seam(path, 2);
        assertEquals("[[1,1],[2,2]]", pairs(load.data.getWidget(ACT, "b").dist("click")));
        assertEquals(1, load.record.getInt("cutPairs"));
        assertEquals(0, load.record.getInt("droppedPairs"));
    }

    /** Scenario "Malformed and cut pairs counted apart". */
    @Test
    public void malformedAndCutPairsAreCountedApart() throws Exception {
        String path = artifact(27, ",\"handlers\":{},\"widgets\":{\"" + ACT + "\":{\"b\":{"
                + "\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[0,1],[1,7],[2,-1],[99,2]]}}}}");
        Load load = seam(path, 3);
        assertEquals("[[0,1]]", pairs(load.data.getWidget(ACT, "b").dist("click")));
        assertEquals("[2,-1] and [99,2]", 2, load.record.getInt("droppedPairs"));
        assertEquals("[1,7]", 1, load.record.getInt("cutPairs"));
    }

    /** Scenario "Activity pair beyond the cut": activityDist is never cut. */
    @Test
    public void anActivityPairBeyondTheCutIsKept() throws Exception {
        String path = artifact(4, ",\"handlers\":{},\"activityDist\":{\"" + ACT + "\":[[0,5]]}");
        Load load = seam(path, 3);
        assertEquals("[[0,5]]", pairs(load.data.activityDist(ACT)));
        assertEquals(0, load.record.getInt("cutPairs"));
        assertEquals("the launcher still orders the activity at distance 5 under dmax 6",
                5, new MopRetirement(4, 3).nearest(load.data.activityDist(ACT), 6));
    }

    /** Scenario "Click aggregate over cut lists". */
    @Test
    public void theClickAggregateIsBuiltFromTheCutLists() throws Exception {
        String path = artifact(10, ",\"handlers\":{},\"widgets\":{\"" + ACT + "\":{\"t\":{"
                + "\"mop\":{\"drag\":\"transitive\",\"select\":\"transitive\"},"
                + "\"dist\":{\"drag\":[[5,3]],\"select\":[[5,1],[6,4]]}}}}");
        Load load = seam(path, 2);
        assertEquals("[[5,1]]", pairs(load.data.getWidget(ACT, "t").clickAggregate()));
        int[][] uncut = seam(path, StaticArtifactLoader.NO_CUT).data.getWidget(ACT, "t").clickAggregate();
        List<int[]> cutAfter = new ArrayList<>();
        for (int[] pair : uncut) {
            if (pair[1] <= 2) {
                cutAfter.add(pair);
            }
        }
        assertEquals("cutting before the merge equals cutting the merged list",
                pairs(cutAfter.toArray(new int[0][])), pairs(load.data.getWidget(ACT, "t").clickAggregate()));
        assertEquals(2, load.record.getInt("cutPairs"));
    }

    /** Scenario "Stamped handler emptied by the cut still wins". */
    @Test
    public void aStampedHandlerEmptiedByTheCutStillWins() throws Exception {
        String path = artifact(5, ",\"handlers\":{\"p.L\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[4,3]]}}},\"widgets\":{\"" + ACT + "\":{\"b\":{"
                + "\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[1,0]]}}}}");
        Load load = seam(path, 2);
        assertEquals(0, load.data.getHandler("p.L").dist("click").length);
        assertEquals(1, load.record.getInt("cutPairs"));

        GUITreeNode node = new GUITreeNode(null);
        node.setResourceID("p:id/b");
        node.setHandlerClick("p.L");
        MopData.Lookup lookup = load.data.lookup(ACT, node, "click");
        assertEquals(MopData.Lookup.Source.STAMP, lookup.source);
        assertEquals(0, lookup.dist.length);
        MopContext mop = new MopContext(load.data, StaticGuiModel.EMPTY,
                MopScoring.DISTANCE, 500, 300,
                new int[] {500, 400}, 3, 6, null);
        assertEquals("a click on the node weighs 0",
                0, MopScorer.distanceWeight(mop, MopRetirement.nearest(null, lookup.dist, Integer.MAX_VALUE)));
    }

    /**
     * Scenario "Deeper artifact under the default cut": an artifact whose widget and handler lists
     * reach d = 10, loaded at c = 3, equals the same artifact pre-cut at 3; the two records differ
     * in {@code cutPairs} only.
     */
    @Test
    public void aDeeperArtifactUnderTheCutEqualsThePreCutArtifact() throws Exception {
        String deep = artifact(40, ",\"handlers\":{\"p.L\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":" + list(20, 1, 3, 4, 10) + "}}},"
                + "\"activityDist\":{\"" + ACT + "\":[[0,1],[1,7]]},"
                + "\"widgets\":{\"" + ACT + "\":{"
                + "\"a\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":" + list(0, 0, 2, 3, 4, 5, 10) + "}},"
                + "\"b\":{\"mop\":{\"drag\":\"transitive\",\"select\":\"transitive\"},"
                + "\"dist\":{\"drag\":" + list(10, 6, 2) + ",\"select\":" + list(10, 3, 9) + "}},"
                + "\"c\":{\"mop\":{\"longclick\":\"transitive\"},\"dist\":{\"longclick\":" + list(30, 8) + "}}}}");
        String shallow = artifact(40, ",\"handlers\":{\"p.L\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[20,1],[21,3]]}}},"
                + "\"activityDist\":{\"" + ACT + "\":[[0,1],[1,7]]},"
                + "\"widgets\":{\"" + ACT + "\":{"
                + "\"a\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[0,0],[1,2],[2,3]]}},"
                + "\"b\":{\"mop\":{\"drag\":\"transitive\",\"select\":\"transitive\"},"
                + "\"dist\":{\"drag\":[[11,2]],\"select\":[[10,3]]}},"
                + "\"c\":{\"mop\":{\"longclick\":\"transitive\"}}}}");
        Load cut = seam(deep, 3);
        Load reference = seam(shallow, 3);

        for (String id : new String[] {"a", "b", "c"}) {
            MopData.Widget w = cut.data.getWidget(ACT, id);
            MopData.Widget r = reference.data.getWidget(ACT, id);
            for (String event : new String[] {"click", "drag", "select", "longClick"}) {
                assertEquals(id + "/" + event, pairs(r.dist(event)), pairs(w.dist(event)));
                assertEquals(id + "/" + event + " direct", r.isDirectMop(event), w.isDirectMop(event));
                assertEquals(id + "/" + event + " transitive",
                        r.isTransitiveMop(event), w.isTransitiveMop(event));
            }
            assertEquals(id + " click aggregate", pairs(r.clickAggregate()), pairs(w.clickAggregate()));
        }
        assertEquals(pairs(reference.data.getHandler("p.L").dist("click")),
                pairs(cut.data.getHandler("p.L").dist("click")));
        assertEquals("[[0,1],[1,7]]", pairs(cut.data.activityDist(ACT)));
        assertEquals(reference.data.getMopActivities(), cut.data.getMopActivities());

        assertEquals("handler 2 + a 3 + b 2 + c 1", 8, cut.record.getInt("cutPairs"));
        assertEquals(0, reference.record.getInt("cutPairs"));
        cut.record.remove("cutPairs");
        reference.record.remove("cutPairs");
        assertEquals(reference.record.toString(), cut.record.toString());
    }

    // -------------------------------------------------------------------------
    // Where the cut comes from: the plan (design D6)
    // -------------------------------------------------------------------------

    private String depth7Artifact() throws Exception {
        return artifact(10, ",\"handlers\":{},\"widgets\":{\"" + ACT + "\":{\"b\":{"
                + "\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[1,1],[2,3],[3,7]]}}}}");
    }

    @Test
    public void theDistanceModeCutsAtTheLadderLength() throws Exception {
        TestRunSpecs.installMop("ape.mopScoring", "distance", "ape.mopWeights", "500,400");
        Load load = planned(depth7Artifact());
        assertEquals("[[1,1]]", pairs(load.data.getWidget(ACT, "b").dist("click")));
        assertEquals(2, load.record.getInt("cutPairs"));
    }

    /** Scenario "Ladder longer than the artifact's depth". */
    @Test
    public void aLadderLongerThanTheArtifactCutsNothing() throws Exception {
        TestRunSpecs.installMop("ape.mopScoring", "distance", "ape.mopWeights", "500,400,300,300,300,300");
        String path = artifact(10, ",\"handlers\":{},\"widgets\":{\"" + ACT + "\":{\"b\":{"
                + "\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[1,1],[2,2],[3,3]]}}}}");
        Load load = planned(path);
        assertEquals("[[1,1],[2,2],[3,3]]", pairs(load.data.getWidget(ACT, "b").dist("click")));
        assertEquals(0, load.record.getInt("cutPairs"));
    }

    /** Scenario "No cut under flag". */
    @Test
    public void nothingIsCutUnderFlag() throws Exception {
        TestRunSpecs.installMop("ape.mopScoring", "flag", "ape.mopWeights", "500");
        Load load = planned(depth7Artifact());
        assertEquals("[[1,1],[2,3],[3,7]]", pairs(load.data.getWidget(ACT, "b").dist("click")));
        assertEquals(0, load.record.getInt("cutPairs"));
    }

    /** A STATIC-only cell loads the artifact for the static model, and cuts nothing. */
    @Test
    public void nothingIsCutWithoutMop() throws Exception {
        TestRunSpecs.install("ape.staticDataPath", TestRunSpecs.MOP_PATH, "ape.static", "true");
        Load load = planned(depth7Artifact());
        assertEquals("[[1,1],[2,3],[3,7]]", pairs(load.data.getWidget(ACT, "b").dist("click")));
        assertEquals(0, load.record.getInt("cutPairs"));
    }

    /** Scenario "E7 artifact at the default ladder": the cryptoapp fixture loses no pair. */
    @Test
    public void theE7FixtureAtTheDefaultLadderIsNotCut() throws Exception {
        TestRunSpecs.installMop("ape.mopScoring", "distance");
        URL url = MopDataCutTest.class.getResource("/cryptoapp.apk.mop.json");
        assertNotNull("fixture not on classpath", url);
        Load load = planned(new File(url.toURI()).getAbsolutePath());
        assertEquals(0, load.record.getInt("cutPairs"));
        assertEquals(0, load.record.getInt("droppedPairs"));
    }
}
