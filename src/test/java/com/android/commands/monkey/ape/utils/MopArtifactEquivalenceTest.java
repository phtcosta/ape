package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.agent.pipeline.MopLauncherStage;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NoopSink;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * The cutover's equivalence gate: the full-JSON parser and the compact-artifact reader must answer
 * every consumed query identically, for every member of the input set.
 *
 * <p>The old parser is the <b>oracle</b>. When this gate is red the generator is wrong, never the
 * parser — adjusting the oracle to make its own gate pass is how a cutover ships a regression it
 * has already proved it cannot see. The parser is deleted immediately after this gate is green
 * (group 5), and this test goes with it.
 *
 * <p><b>What this gate is and is not.</b> It runs on a designed case set — one real application
 * plus one synthetic per relocated rule the application cannot exercise — and not on the 345-app
 * corpus its first design assumed. Equality over cases someone thought of is evidence about those
 * cases. The breadth it no longer measures was paid for elsewhere and is recorded in the change:
 * the generator itself has met all 345 producer documents (`gh96` 7.1/7.2), and real-application
 * variety on the jar side is deferred to the counterpart campaign. A real-app shape that neither
 * the fixture nor a synthetic anticipates is not covered here.
 *
 * <p><b>Why the artifacts are generated, not written.</b> Every {@code .mop.json} below came out of
 * the real generator, run over the {@code .sa.json} beside it. A hand-written artifact would make
 * the gate compare the parser against the author's belief about the generator, which is the one
 * comparison that cannot fail usefully.
 */
public class MopArtifactEquivalenceTest {

    /**
     * The input set: {full static-analysis JSON, derived artifact, the rule the member fires}.
     *
     * <p>Each synthetic exists because the cryptoapp application does not exercise the rule — it
     * has no empty-id widgets, no orphan dialogs, no deep links, and an augmented activity set
     * equal to its widget-derived one. A member that derived to an artifact where its rule did not
     * fire would be a defect in the member rather than coverage, which is why the rule each one
     * fires is recorded here and in the change's task notes.
     */
    private static final String[][] MEMBERS = {
        {"cryptoapp.apk.gh60-fresh.json", "cryptoapp.apk.mop.json",
         "the real application: D8 lambda recovery, dialog-free, 3 flagged widgets"},
        {"gate-empty-id.sa.json", "gate-empty-id.mop.json",
         "INV-DRV-02: a flagged id-less widget marks its activity, then is dropped"},
        {"gate-synthetic-lambda.sa.json", "gate-synthetic-lambda.mop.json",
         "INV-DRV-01: D8 recovery, its negative case, and the exact join"},
        {"gate-dialog-rekey.sa.json", "gate-dialog-rekey.mop.json",
         "INV-DRV-03: DIALOG re-keying with host promotion, plus an orphan"},
        {"gate-activity-union.sa.json", "gate-activity-union.mop.json",
         "INV-DRV-06: an A-prime union with all three sources contributing"},
        {"gate-deep-link.sa.json", "gate-deep-link.mop.json",
         "INV-DRV-07: the assembly rule, host/path defaulting, and three null cases"},
    };

    /** The seven metadata fields with a production reader (INV-MOP-10). */
    private static final String[] META = {
        "hint", "inputType", "prompt", "spinnerMode", "contentDescription", "tooltipText"};

    @Before
    public void installMopPlan() {
        TestRunSpecs.installMop();
    }

    @After
    public void clearPlan() {
        RunContext.resetForTest();
    }

    private static String fixture(String name) {
        java.net.URL url = MopArtifactEquivalenceTest.class.getResource("/" + name);
        assertNotNull("fixture not on classpath: " + name, url);
        return new File(url.getFile()).getAbsolutePath();
    }

    private static MopData oldSide(String source) {
        MopData d = MopData.load(fixture(source), null, null, new NoopSink());
        assertNotNull("oracle failed to parse " + source, d);
        return d;
    }

    private static MopData newSide(String artifact, boolean activitySourceComponents) {
        MopData d = MopData.loadCompact(fixture(artifact), null, null, activitySourceComponents,
                new NoopSink());
        assertNotNull("artifact failed to load " + artifact, d);
        return d;
    }

    /** The old side's A-prime set: what its own load would have built with the flag on. */
    private static Set<String> oldAugmentedSet(MopData old) {
        Set<String> augmented = new HashSet<>(old.getMopActivities());
        MopData.augmentActivitiesFromSources(augmented, old.getActivities(),
                old.getReachability(), true);
        return augmented;
    }

    /** The old side's WTG view, reassembled from the activities it could possibly key. */
    private static Map<String, List<MopData.WtgTransition>> oldWtg(MopData old, String artifact)
            throws Exception {
        Map<String, List<MopData.WtgTransition>> view = new HashMap<>();
        for (String activity : activityUniverse(old, artifact)) {
            List<MopData.WtgTransition> edges = old.getWtgTransitions(activity);
            if (!edges.isEmpty()) {
                view.put(activity, edges);
            }
        }
        return view;
    }

    private static JSONObject wire(String artifact) throws Exception {
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(fixture(artifact))),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> names = new TreeSet<>();
        if (o != null) {
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                names.add(it.next());
            }
        }
        return names;
    }

    /**
     * Every activity name either side could key something under: the base name of every window the
     * oracle parsed, plus every activity the artifact names. The union matters — a dialog widget
     * re-keyed to its host is filed under a name that is a window on one side and a widget-map key
     * on the other.
     */
    private static Set<String> activityUniverse(MopData old, String artifact) throws Exception {
        Set<String> activities = new TreeSet<>();
        for (MopData.Window w : old.getWindows()) {
            if (w.name != null) {
                activities.add(w.name);
                int hash = w.name.indexOf('#');
                activities.add(hash < 0 ? w.name : w.name.substring(0, hash));
            }
        }
        JSONObject root = wire(artifact);
        activities.addAll(keys(root.optJSONObject("widgets")));
        activities.addAll(keys(root.optJSONObject("wtg")));
        return activities;
    }

    /** Every short resource id either side could key a widget under. */
    private static Set<String> widgetIdUniverse(MopData old, String artifact) throws Exception {
        Set<String> ids = new TreeSet<>();
        for (MopData.Window w : old.getWindows()) {
            for (MopData.Widget wd : w.widgets) {
                if (wd.idName != null && !wd.idName.isEmpty()) {
                    ids.add(wd.idName);
                }
            }
        }
        JSONObject widgets = wire(artifact).optJSONObject("widgets");
        for (String activity : keys(widgets)) {
            ids.addAll(keys(widgets.getJSONObject(activity)));
        }
        return ids;
    }

    /**
     * Null and the empty string are the same value to every metadata consumer: the prompt builder
     * emits a field only when it is non-null and non-empty, and {@code generateInputText} tests the
     * same way. The producer writes {@code ""} for an absent hint and the generator omits the key,
     * so a comparison that distinguished them would fail on a difference no consumer can observe.
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean carriesMetadata(MopData.Widget w) {
        return !text(w.hint).isEmpty() || !text(w.inputType).isEmpty()
                || !text(w.prompt).isEmpty() || !text(w.spinnerMode).isEmpty()
                || !text(w.contentDescription).isEmpty() || !text(w.tooltipText).isEmpty()
                || !w.entries.isEmpty();
    }

    private static String metaOf(MopData.Widget w, String field) {
        if ("hint".equals(field)) return text(w.hint);
        if ("inputType".equals(field)) return text(w.inputType);
        if ("prompt".equals(field)) return text(w.prompt);
        if ("spinnerMode".equals(field)) return text(w.spinnerMode);
        if ("contentDescription".equals(field)) return text(w.contentDescription);
        return text(w.tooltipText);
    }

    // -------------------------------------------------------------------------
    // The comparisons
    // -------------------------------------------------------------------------

    @Test
    public void packageAndMainActivityAgree() {
        for (String[] member : MEMBERS) {
            MopData old = oldSide(member[0]);
            MopData fresh = newSide(member[1], false);
            assertEquals(member[2], old.getPackageName(), fresh.getPackageName());
            assertEquals(member[2], old.getMainActivity(), fresh.getMainActivity());
        }
    }

    /**
     * Widget flags and metadata, over every key either side could hold.
     *
     * <p>The one asymmetry the gate permits is the artifact's emit rule: a widget that is neither
     * flagged nor carrying metadata is not written to the wire at all. That omission is checked
     * rather than waived — the test asserts the omitted widget was in fact inert — and it is
     * observationally void, because both readers of the widget map treat a missing widget exactly
     * as they treat an inert one: {@code MopScorer.score} returns 0 for "null or resolved-but-
     * unflagged", and {@code ApePromptBuilder.widgetMetadata} returns the empty string for null.
     */
    @Test
    public void widgetFlagsAndMetadataAgree() throws Exception {
        for (String[] member : MEMBERS) {
            MopData old = oldSide(member[0]);
            MopData fresh = newSide(member[1], false);
            int compared = 0;
            for (String activity : activityUniverse(old, member[1])) {
                for (String id : widgetIdUniverse(old, member[1])) {
                    String at = member[2] + " @ " + activity + "#" + id;
                    MopData.Widget a = old.getWidget(activity, id);
                    MopData.Widget b = fresh.getWidget(activity, id);
                    if (a == null) {
                        assertNull("the artifact carries a widget the oracle does not: " + at, b);
                        continue;
                    }
                    if (b == null) {
                        assertFalse("dropped a flagged widget: " + at, a.directMop || a.transitiveMop);
                        assertFalse("dropped a widget carrying metadata: " + at, carriesMetadata(a));
                        continue;
                    }
                    compared++;
                    assertEquals("directMop: " + at, a.directMop, b.directMop);
                    assertEquals("transitiveMop: " + at, a.transitiveMop, b.transitiveMop);
                    assertEquals("per-event direct map: " + at,
                            a.directMopByEventType, b.directMopByEventType);
                    assertEquals("per-event transitive map: " + at,
                            a.transitiveMopByEventType, b.transitiveMopByEventType);
                    for (String field : META) {
                        assertEquals(field + ": " + at, metaOf(a, field), metaOf(b, field));
                    }
                    assertEquals("entries: " + at, a.entries, b.entries);
                }
            }
            assertTrue("no widget was compared at all for " + member[2],
                    compared > 0 || "gate-deep-link.sa.json".equals(member[0]));
        }
    }

    /** Both MOP-activity sets, under both selections of the run flag (INV-MOP-27). */
    @Test
    public void mopActivitySetsAgreeUnderBothFlagStates() {
        for (String[] member : MEMBERS) {
            MopData old = oldSide(member[0]);
            assertEquals("widget-derived set: " + member[2],
                    old.getMopActivities(), newSide(member[1], false).getMopActivities());
            assertEquals("augmented set: " + member[2],
                    oldAugmentedSet(old), newSide(member[1], true).getMopActivities());
        }
    }

    /**
     * The OPTIONSMENU gateway set under both selections.
     *
     * <p>The universe is asserted before the values are: the artifact must carry a record for every
     * activity the oracle judged, because a gateway the new side never considers cannot be caught
     * by comparing answers about the ones it does.
     */
    @Test
    public void optionsMenuGatewaysAgreeUnderBothFlagStates() throws Exception {
        for (String[] member : MEMBERS) {
            MopData old = oldSide(member[0]);
            MopData off = newSide(member[1], false);
            MopData on = newSide(member[1], true);

            Set<String> oracleMenus = new TreeSet<>();
            for (MopData.Window w : old.getWindows()) {
                if ("OPTIONSMENU".equals(w.type) && w.name != null) {
                    int hash = w.name.indexOf('#');
                    oracleMenus.add(hash < 0 ? w.name : w.name.substring(0, hash));
                }
            }
            Set<String> wireMenus = new TreeSet<>();
            org.json.JSONArray records = wire(member[1]).optJSONArray("optionsMenus");
            for (int i = 0; records != null && i < records.length(); i++) {
                wireMenus.add(records.getJSONObject(i).getString("activity"));
            }
            assertEquals("menu-owning activities: " + member[2], oracleMenus, wireMenus);

            Set<String> onGateways = MopData.precomputeMopOptionsMenus(
                    old.getWindows(), oldWtg(old, member[1]), oldAugmentedSet(old));
            for (String activity : oracleMenus) {
                assertEquals("gateway, flag off: " + member[2] + " @ " + activity,
                        old.activityHasMopOptionsMenu(activity),
                        off.activityHasMopOptionsMenu(activity));
                assertEquals("gateway, flag on: " + member[2] + " @ " + activity,
                        onGateways.contains(activity), on.activityHasMopOptionsMenu(activity));
            }
        }
    }

    /**
     * The WTG click view, compared as sets of {@code (widget, target)} edges.
     *
     * <p>Set-based is not a convenience. The oracle keeps exact-duplicate edges and the derivation
     * removes them, so cryptoapp alone diverges 17 against 16 on the very first member — a list
     * comparison would report a rule working as a bug. The licence is the multiplicity audit: every
     * WTG consumer either returns on first match or folds into a set, so no decision in the tree
     * can observe an edge's multiplicity. For the same reason the {@code stats} block is not
     * compared at all: {@code wtgEdges} legitimately differs across the cut.
     */
    @Test
    public void wtgViewsAgreeAsSets() throws Exception {
        for (String[] member : MEMBERS) {
            MopData old = oldSide(member[0]);
            MopData fresh = newSide(member[1], false);
            for (String activity : activityUniverse(old, member[1])) {
                assertEquals("WTG edges out of " + activity + ": " + member[2],
                        edgeSet(old.getWtgTransitions(activity)),
                        edgeSet(fresh.getWtgTransitions(activity)));
            }
            assertEquals("hasWtgData: " + member[2], old.hasWtgData(), fresh.hasWtgData());
        }
    }

    private static Set<String> edgeSet(List<MopData.WtgTransition> edges) {
        Set<String> set = new TreeSet<>();
        for (MopData.WtgTransition t : edges) {
            set.add(t.widgetName + " -> " + t.targetActivity);
        }
        return set;
    }

    /**
     * The component trigger surface, field by field, and the deep-link URI per activity.
     *
     * <p>Tuples are not built here and do not need to be: {@code buildTriggerTuples} reads exactly
     * {@code reachesTarget}, {@code intentFilters[].actions} and {@code targetMethods.isEmpty()}
     * over the receivers-then-services lists, and {@code buildProviderTuples} reads
     * {@code reachesTarget} and {@code authorities}. Those fields are compared below in list order,
     * which fixes the tuples exactly. Activities are compared separately because they are excluded
     * from the tuple pool by construction and reach the launcher instead — where the only field
     * that matters is the one the wire now carries precomputed.
     */
    @Test
    public void componentSurfacesAndDeepLinksAgree() {
        for (String[] member : MEMBERS) {
            MopData old = oldSide(member[0]);
            MopData fresh = newSide(member[1], false);
            assertEquals("hasComponents: " + member[2], old.hasComponents(), fresh.hasComponents());

            compareTriggerable(member[2] + " receivers",
                    new ArrayList<ComponentInfo>(old.getReceivers()),
                    new ArrayList<ComponentInfo>(fresh.getReceivers()));
            compareTriggerable(member[2] + " services",
                    new ArrayList<ComponentInfo>(old.getServices()),
                    new ArrayList<ComponentInfo>(fresh.getServices()));

            assertEquals("provider count: " + member[2],
                    old.getProviders().size(), fresh.getProviders().size());
            for (int i = 0; i < old.getProviders().size(); i++) {
                ComponentInfo.ProviderInfo a = old.getProviders().get(i);
                ComponentInfo.ProviderInfo b = fresh.getProviders().get(i);
                String at = member[2] + " provider " + a.className;
                assertEquals(at, a.className, b.className);
                assertEquals(at, a.reachesTarget, b.reachesTarget);
                assertEquals(at, a.authorities, b.authorities);
                assertEquals(at, a.permission, b.permission);
            }

            assertEquals("activity count: " + member[2],
                    old.getActivities().size(), fresh.getActivities().size());
            for (int i = 0; i < old.getActivities().size(); i++) {
                ComponentInfo.ActivityInfo a = old.getActivities().get(i);
                ComponentInfo.ActivityInfo b = fresh.getActivities().get(i);
                String at = member[2] + " activity " + a.className;
                assertEquals(at, a.className, b.className);
                assertEquals(at, a.isMain, b.isMain);
                assertEquals(at, a.reachesTarget, b.reachesTarget);
                assertEquals(at, a.permission, b.permission);
                // The relocation this stage is about: the launcher walked the filters to build
                // this string and now reads it off the wire. Null must survive as null — it is
                // what selects the explicit-component intent.
                assertEquals("deepLinkUri: " + at,
                        MopLauncherStage.buildDeepLinkUri(a), b.deepLinkUri);
                assertTrue("no filter structure remains for the launcher to walk: " + at,
                        b.intentFilters.isEmpty());
            }
        }
    }

    private static void compareTriggerable(String what, List<ComponentInfo> oracle,
                                           List<ComponentInfo> wire) {
        assertEquals(what + " count", oracle.size(), wire.size());
        for (int i = 0; i < oracle.size(); i++) {
            ComponentInfo a = oracle.get(i);
            ComponentInfo b = wire.get(i);
            String at = what + " " + a.className;
            assertEquals(at, a.className, b.className);
            assertEquals(at, a.isMain, b.isMain);
            assertEquals(at, a.reachesTarget, b.reachesTarget);
            assertEquals(at, a.permission, b.permission);
            assertEquals("targetMethods emptiness is the only thing read: " + at,
                    a.targetMethods.isEmpty(), b.targetMethods.isEmpty());
            assertEquals("filter count: " + at, a.intentFilters.size(), b.intentFilters.size());
            for (int f = 0; f < a.intentFilters.size(); f++) {
                assertEquals("actions: " + at, a.intentFilters.get(f).actions,
                        b.intentFilters.get(f).actions);
                assertEquals("categories: " + at, a.intentFilters.get(f).categories,
                        b.intentFilters.get(f).categories);
            }
        }
    }
}
