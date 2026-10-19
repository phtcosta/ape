package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import org.junit.Before;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.*;

/**
 * mop-guidance "Handler Stamp Lookup" (llm-coordinate-single-base, design D17, task 12.1): one
 * lookup per (node, event) — the handler stamp's class when {@code handlers} lists it, else the id
 * key with today's per-event → aggregate fallback for the flags and, for a click on a widget with no
 * {@code click} key, the widget's click aggregate of pairs (amendment of 2026-10-09), else
 * {@code NONE}.
 *
 * <p>The cryptoapp scenarios read the format-2 fixture derived from the gh120 document (task 10.1);
 * the synthetic ones ({@code com.x.*}) author the smallest format-2 artifact that states them.
 * Nodes are built on the JVM through the android-free surface of {@link GUITreeNode}, as
 * {@code MopScorerContainmentTest} does; the stamp fields are set through their setters, the values
 * {@code GUITreeBuilder.fillNode} copies from {@code rvsec.click}/{@code rvsec.longClick}.
 */
public class MopDataLookupTest {

    /**
     * {@code MopData.load} reads the MOP activity source from the plan (guidance-groups
     * INV-GRP-06), so every case runs under one: the jar defaults, whose source is the widgets.
     */
    @Before
    public void installThePlan() {
        TestRunSpecs.install();
    }

    private static final String CRYPTO = "br.unb.cic.cryptoapp.generated.CryptographyActivity";
    private static final String CRYPTO_LAMBDA =
            "br.unb.cic.cryptoapp.generated.CryptographyActivity$$ExternalSyntheticLambda0";
    private static final String MDA = "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity";
    private static final String DECLARED_ON_CLICK =
            "androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener";

    private static final String ACT = "com.x.MainActivity";

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static MopData cryptoapp() {
        java.net.URL url = MopDataLookupTest.class.getResource("/cryptoapp.apk.mop.json");
        assertNotNull("fixture not on classpath", url);
        MopData data = StaticArtifactLoader.load(new File(url.getFile()).getAbsolutePath(), null, null, new NoopSink()).mopData;
        assertNotNull(data);
        return data;
    }

    /** A format-2 artifact with the given {@code widgets} body for {@link #ACT} and handlers body. */
    private static MopData synthetic(String widgetsOfAct, String handlers) throws Exception {
        String artifact = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{" + handlers + "}}";
        File f = File.createTempFile("mopdatalookup", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(artifact);
        }
        MopData data = StaticArtifactLoader.load(f.getAbsolutePath(), null, null, new NoopSink()).mopData;
        assertNotNull("synthetic artifact must load", data);
        return data;
    }

    private static GUITreeNode node(String shortId, String handlerClick, String handlerLongClick) {
        GUITreeNode n = new GUITreeNode(null);
        if (shortId != null) {
            n.setResourceID("com.x:id/" + shortId);
        }
        n.setHandlerClick(handlerClick);
        n.setHandlerLongClick(handlerLongClick);
        return n;
    }

    private static void assertPairs(int[][] expected, int[][] actual) {
        assertNotNull("dist is never null", actual);
        assertEquals("pair count", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals("pair " + i, expected[i], actual[i]);
        }
    }

    private static void assertNone(MopData.Lookup l) {
        assertNotNull("lookup never returns null", l);
        assertEquals(MopData.Lookup.Source.NONE, l.source);
        assertFalse(l.direct);
        assertFalse(l.transitive);
        assertPairs(new int[0][], l.dist);
    }

    // -------------------------------------------------------------------------
    // Scenario: listed stamp wins over the id key
    // -------------------------------------------------------------------------

    @Test
    public void listedStampWins_noResourceId() {
        MopData data = cryptoapp();
        MopData.Lookup l = data.lookup(CRYPTO, node(null, CRYPTO_LAMBDA, null), "click");
        assertEquals(MopData.Lookup.Source.STAMP, l.source);
        assertEquals("the lookup names the stamped class (D-B17)", CRYPTO_LAMBDA, l.handler);
        assertTrue(l.transitive);
        assertFalse(l.direct);
        assertPairs(new int[][] {{16, 3}, {17, 3}, {18, 3}}, l.dist);
    }

    @Test
    public void listedStampWins_whateverTheResourceId() {
        MopData data = cryptoapp();
        // An id that resolves to a different widget of another activity, and one that resolves
        // to nothing: neither is consulted once the stamp names a listed class.
        for (String id : new String[] {"buttonGenerateHash", "no_such_widget", "executeButton"}) {
            MopData.Lookup l = data.lookup(CRYPTO, node(id, CRYPTO_LAMBDA, null), "click");
            assertEquals(id, MopData.Lookup.Source.STAMP, l.source);
            assertTrue(id, l.transitive);
            assertFalse(id, l.direct);
            assertPairs(new int[][] {{16, 3}, {17, 3}, {18, 3}}, l.dist);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario: listed stamp with none wins over a flagged id key
    // -------------------------------------------------------------------------

    @Test
    public void listedStampWithNoneBeatsFlaggedIdKey() throws Exception {
        MopData data = synthetic(
                "\"btn\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[1,0]]}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"none\"}}");
        MopData.Lookup l = data.lookup(ACT, node("btn", "com.x.Foo$1", null), "click");
        assertEquals(MopData.Lookup.Source.STAMP, l.source);
        assertFalse(l.direct);
        assertFalse(l.transitive);
        assertPairs(new int[0][], l.dist);
    }

    /**
     * "An event missing from a listed class's record reads as none with no pairs": the stamp step
     * does not inherit the id key's aggregate fallback, even though the record's aggregate is set.
     */
    @Test
    public void listedStampMissingEventReadsNoneWithoutAggregateFallback() throws Exception {
        MopData data = synthetic(
                "\"btn\":{\"mop\":{\"longclick\":\"direct\"}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[2,0]]}}");
        MopData.Lookup l = data.lookup(ACT, node("btn", null, "com.x.Foo$1"), "longClick");
        assertEquals(MopData.Lookup.Source.STAMP, l.source);
        assertEquals("the long-click stamp is the one named", "com.x.Foo$1", l.handler);
        assertFalse("no aggregate fallback through a stamp", l.direct);
        assertFalse(l.transitive);
        assertPairs(new int[0][], l.dist);
    }

    // -------------------------------------------------------------------------
    // Scenario: unlisted stamp falls back to the id key
    // -------------------------------------------------------------------------

    @Test
    public void unlistedStampFallsBackToIdKey() {
        MopData data = cryptoapp();
        assertNull("the fixture must not list the dispatcher", data.getHandler(DECLARED_ON_CLICK));
        MopData.Lookup l = data.lookup(MDA, node("buttonGenerateHash", DECLARED_ON_CLICK, null),
                "click");
        assertEquals(MopData.Lookup.Source.ID, l.source);
        assertNull("an unlisted stamp is not the handler of an id-key lookup", l.handler);
        assertTrue(l.transitive);
        assertFalse(l.direct);
        assertPairs(new int[][] {{22, 2}}, l.dist);
    }

    // -------------------------------------------------------------------------
    // Scenario: no stamp uses the id key
    // -------------------------------------------------------------------------

    @Test
    public void noStampUsesIdKey() {
        MopData data = cryptoapp();
        MopData.Lookup l = data.lookup(MDA, node("buttonGenerateHash", null, null), "click");
        assertEquals(MopData.Lookup.Source.ID, l.source);
        assertNull(l.handler);
        assertTrue(l.transitive);
        assertFalse(l.direct);
        assertPairs(new int[][] {{22, 2}}, l.dist);
    }

    @Test
    public void noStampAndUnresolvedIdIsNone() {
        MopData data = cryptoapp();
        assertNone(data.lookup(MDA, node("no_such_widget", null, null), "click"));
        assertNone(data.lookup(MDA, node(null, null, null), "click"));
        assertNone(data.lookup("com.unknown.Activity", node("buttonGenerateHash", null, null),
                "click"));
    }

    @Test
    public void nullNodeIsNone() {
        assertNone(cryptoapp().lookup(MDA, null, "click"));
    }

    // -------------------------------------------------------------------------
    // Scenario: itemSelected and scroll ignore stamps
    // -------------------------------------------------------------------------

    @Test
    public void itemSelectedAndScrollIgnoreStamps() throws Exception {
        MopData data = synthetic(
                "\"spinner\":{\"mop\":{\"itemselected\":\"transitive\"},"
                        + "\"dist\":{\"itemselected\":[[5,2]]}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"direct\",\"longclick\":\"direct\","
                        + "\"itemselected\":\"direct\",\"scroll\":\"direct\"}}");
        GUITreeNode spinner = node("spinner", "com.x.Foo$1", "com.x.Foo$1");
        MopData.Lookup item = data.lookup(ACT, spinner, "itemSelected");
        assertEquals(MopData.Lookup.Source.ID, item.source);
        assertTrue(item.transitive);
        assertFalse(item.direct);
        assertPairs(new int[][] {{5, 2}}, item.dist);

        MopData.Lookup scroll = data.lookup(ACT, spinner, "scroll");
        assertEquals("scroll never consults handlers", MopData.Lookup.Source.ID, scroll.source);
        // The widget has no "scroll" key: today's per-event → aggregate fallback answers.
        assertTrue(scroll.transitive);
        assertFalse(scroll.direct);
        assertPairs(new int[0][], scroll.dist);

        // The same stamp does answer click, which proves the record was listed.
        assertEquals(MopData.Lookup.Source.STAMP, data.lookup(ACT, spinner, "click").source);
    }

    @Test
    public void nullEventIgnoresStampAndUsesAggregateWithoutPairs() throws Exception {
        MopData data = synthetic(
                "\"btn\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[1,0]]}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"none\"}}");
        MopData.Lookup l = data.lookup(ACT, node("btn", "com.x.Foo$1", null), null);
        assertEquals(MopData.Lookup.Source.ID, l.source);
        assertTrue("null event uses the aggregate", l.direct);
        // Only a click reads an aggregate of pairs: a null event carries none.
        assertPairs(new int[0][], l.dist);
    }

    // -------------------------------------------------------------------------
    // The click aggregate of the id key (design D17, amendment of 2026-10-09)
    // -------------------------------------------------------------------------

    /**
     * Scenario "a click on a widget flagged under another event reads its aggregate pairs": a touch
     * listener (GATOR's {@code drag}) with no {@code click} key. The click reads the drag pairs; a
     * long-click keeps the flags' aggregate fallback but gets no pair.
     */
    @Test
    public void clickOnAWidgetFlaggedUnderAnotherEventReadsItsAggregatePairs() throws Exception {
        MopData data = synthetic(
                "\"touch\":{\"mop\":{\"drag\":\"transitive\"},\"dist\":{\"drag\":[[9,2]]}}", "");
        MopData.Lookup click = data.lookup(ACT, node("touch", null, null), "click");
        assertEquals(MopData.Lookup.Source.ID, click.source);
        assertTrue(click.transitive);
        assertFalse(click.direct);
        assertPairs(new int[][] {{9, 2}}, click.dist);

        MopData.Lookup longClick = data.lookup(ACT, node("touch", null, null), "longClick");
        assertEquals(MopData.Lookup.Source.ID, longClick.source);
        assertTrue("the flags' aggregate fallback", longClick.transitive);
        assertPairs(new int[0][], longClick.dist);
    }

    /** Scenario "the click aggregate leaves out long-press events". */
    @Test
    public void theClickAggregateLeavesOutLongPressEvents() throws Exception {
        MopData data = synthetic("\"w\":{\"mop\":{\"longclick\":\"transitive\","
                + "\"select\":\"transitive\",\"itemlongclick\":\"transitive\","
                + "\"implicitcreatecontextmenu\":\"transitive\",\"scroll\":\"transitive\"},"
                + "\"dist\":{\"longclick\":[[3,1]],\"select\":[[9,2]],\"itemlongclick\":[[4,0]],"
                + "\"implicitcreatecontextmenu\":[[5,0]],\"scroll\":[[6,0]]}}", "");
        assertPairs(new int[][] {{9, 2}}, data.lookup(ACT, node("w", null, null), "click").dist);
    }

    /**
     * The click aggregate merges the events a click fires by the minimum per target
     * ({@code MopData.mergeByMinimum}), in wire order.
     */
    @Test
    public void theClickAggregateMergesByTheMinimumPerTarget() throws Exception {
        MopData data = synthetic("\"w\":{\"mop\":{\"select\":\"transitive\","
                + "\"itemclick\":\"transitive\"},"
                + "\"dist\":{\"select\":[[9,2],[4,3]],\"itemclick\":[[9,1],[7,3]]}}", "");
        assertPairs(new int[][] {{9, 1}, {4, 3}, {7, 3}},
                data.lookup(ACT, node("w", null, null), "click").dist);
    }

    /** Scenario "an explicit event key blocks the aggregate pairs". */
    @Test
    public void anExplicitEventKeyBlocksTheAggregatePairs() throws Exception {
        MopData data = synthetic("\"w\":{\"mop\":{\"click\":\"none\",\"select\":\"transitive\"},"
                + "\"dist\":{\"select\":[[9,2]]}}", "");
        MopData.Lookup l = data.lookup(ACT, node("w", null, null), "click");
        assertEquals(MopData.Lookup.Source.ID, l.source);
        assertFalse(l.direct);
        assertFalse(l.transitive);
        assertPairs(new int[0][], l.dist);
    }

    /**
     * A stamp never reads an aggregate (D17, step 1): a listed record with no {@code click} key
     * answers none, although its other event carries pairs.
     */
    @Test
    public void aStampNeverReadsTheClickAggregate() throws Exception {
        MopData data = synthetic("",
                "\"com.x.Foo$1\":{\"mop\":{\"longclick\":\"transitive\"},"
                        + "\"dist\":{\"longclick\":[[3,1]]}}");
        MopData.Lookup l = data.lookup(ACT, node(null, "com.x.Foo$1", null), "click");
        assertEquals(MopData.Lookup.Source.STAMP, l.source);
        assertPairs(new int[0][], l.dist);
    }

    // -------------------------------------------------------------------------
    // Scenario: a Compose invoke class answers click and longClick
    // -------------------------------------------------------------------------

    @Test
    public void composeInvokeClassAnswersClickAndLongClick() throws Exception {
        MopData data = synthetic("",
                "\"com.x.ui.ScreenKt$Body$1$1\":{\"mop\":{\"click\":\"transitive\","
                        + "\"longclick\":\"transitive\"},"
                        + "\"dist\":{\"click\":[[3,2]],\"longclick\":[[3,2]]}}");
        MopData.Lookup click = data.lookup(ACT, node(null, "com.x.ui.ScreenKt$Body$1$1", null),
                "click");
        assertEquals(MopData.Lookup.Source.STAMP, click.source);
        assertTrue(click.transitive);
        assertFalse(click.direct);
        assertPairs(new int[][] {{3, 2}}, click.dist);

        MopData.Lookup longClick = data.lookup(ACT,
                node(null, null, "com.x.ui.ScreenKt$Body$1$1"), "longClick");
        assertEquals(MopData.Lookup.Source.STAMP, longClick.source);
        assertTrue(longClick.transitive);
        assertFalse(longClick.direct);
        assertPairs(new int[][] {{3, 2}}, longClick.dist);

        // The click stamp does not answer longClick: each event reads its own stamp field.
        assertNone(data.lookup(ACT, node(null, "com.x.ui.ScreenKt$Body$1$1", null), "longClick"));
    }

    @Test
    public void eventSpellingsMeetInTheNormalizedForm() throws Exception {
        MopData data = synthetic("",
                "\"com.x.Foo$1\":{\"mop\":{\"longclick\":\"direct\"},\"dist\":{\"longclick\":[[4,0]]}}");
        for (String event : new String[] {"longClick", "long_click", "longclick"}) {
            MopData.Lookup l = data.lookup(ACT, node(null, null, "com.x.Foo$1"), event);
            assertEquals(event, MopData.Lookup.Source.STAMP, l.source);
            assertTrue(event, l.direct);
            assertPairs(new int[][] {{4, 0}}, l.dist);
        }
    }
}
