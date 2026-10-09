package com.android.commands.monkey.ape.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * Typed model of the compact MOP artifact, plus the activity → shortWidgetId index that
 * MOP-guided scoring, routing and triggering query.
 *
 * <h3>Naming boundary: the wire speaks {@code MOP}</h3>
 *
 * The static analyser generalized its output to any target method set, so its documents speak the
 * neutral word <em>Target</em> ({@code reachesTarget}, {@code directlyReachesTarget},
 * {@code targetMethods}) while aperv, an exclusively JavaMOP consumer, speaks <em>MOP</em>. That
 * translation used to happen here, on device, and it no longer does: the artifact generator is the
 * one component that reads the analyser's document, so it performs the rename and ships
 * {@code mop}, {@code mopActivities} and {@code reachesMop} (design D7, relocated by D2).
 *
 * <p>Two {@code Target} names survive on this side, and stating them is more useful than claiming
 * the vocabulary is uniform: the wire key {@code hasTargetMethods} — the generator's own compaction
 * of the signature list, not a producer key — and the fields {@code ComponentInfo.reachesTarget}
 * and {@code targetMethods}, which {@link #parseCompactComponents} feeds from {@code reachesMop}
 * and {@code hasTargetMethods}. So one rename does still happen here, on exactly one field. What
 * left with the parser is the thing the boundary was really about: no {@code reachability[]}, no
 * {@code directlyReachesTarget}, no listener × handler cross-reference reaches the device at all.
 *
 * <h3>Reader, not parser</h3>
 *
 * Every parse-time semantic the on-device parser used to perform — the listener-handler ×
 * reachability cross-reference, the synthetic-lambda recovery, the collision policy, the empty-id
 * drop, the DIALOG re-keying, the base-activity WTG keying, the A′ union — now runs host-side in
 * the generator. What remains reads precomputed values into the lookup structures the query API
 * serves and derives exactly one thing, the OPTIONSMENU gateway set, because that one depends on
 * which MOP-activity set the run's flag selects (INV-MOP-35, design D3).
 *
 * <p>The artifact is read whole into an {@code org.json.JSONObject} and navigated in memory
 * (design D21 — {@code android.util.JsonReader} is excluded from the surefire test classpath, so a
 * streaming reader could not be unit-tested). No parse budget and no {@code OutOfMemoryError}
 * containment: the artifact is a projection bounded by construction, so a pathological one is a
 * generator bug caught on the host, not a device-runtime condition to survive (design D5).
 */
public class MopData {

    private final String packageName;
    private final String mainActivity;

    /** Map: base activity class name → (shortResourceId → Widget). */
    private final Map<String, Map<String, Widget>> widgetData;
    /** Base activity class names that have at least one MOP-reachable widget. */
    private final Set<String> mopActivities;
    /** Map: base source activity → click-only WTG transitions (INV-WTG-04). */
    private final Map<String, List<WtgTransition>> wtgTransitions;

    private final List<ComponentInfo.ReceiverInfo> receivers;
    private final List<ComponentInfo.ServiceInfo> services;
    private final List<ComponentInfo.ActivityInfo> activities;
    private final List<ComponentInfo.ProviderInfo> providers;

    /** Activities whose OPTIONSMENU is a MOP gateway (T1.2, D13). */
    private final Set<String> activitiesWithMopOptionsMenu;

    /** The shared empty answer of every distance accessor: no pair. Callers read only. */
    static final int[][] NO_PAIRS = new int[0][];

    /**
     * Length of the producer's target list (format 2 {@code targets}, design D15): the bound every
     * stored pair index lies below. 0 when the artifact has no target.
     */
    private final int targetCount;
    /** Map: base activity → distance pairs of the activity's class and widgets (D15, D20). */
    private final Map<String, int[][]> activityDist;
    /** Map: handler binary class name → its flags and pairs (format 2 {@code handlers}, D15). */
    private final Map<String, Handler> handlers;

    /**
     * Count of MOP-flagged widgets the generator dropped for having no resource id (INV-MOP-20).
     * Observability only — echoed from the artifact's {@code stats}, never recomputed here.
     */
    private int droppedFlaggedNoId;

    private MopData(String packageName, String mainActivity,
                    Map<String, Map<String, Widget>> widgetData, Set<String> mopActivities,
                    Map<String, List<WtgTransition>> wtgTransitions,
                    List<ComponentInfo.ReceiverInfo> receivers,
                    List<ComponentInfo.ServiceInfo> services,
                    List<ComponentInfo.ActivityInfo> activities,
                    List<ComponentInfo.ProviderInfo> providers,
                    Set<String> activitiesWithMopOptionsMenu,
                    int targetCount, Map<String, int[][]> activityDist,
                    Map<String, Handler> handlers) {
        this.packageName = packageName;
        this.mainActivity = mainActivity;
        this.widgetData = widgetData;
        this.mopActivities = mopActivities;
        this.wtgTransitions = wtgTransitions;
        this.receivers = receivers;
        this.services = services;
        this.activities = activities;
        this.providers = providers;
        this.activitiesWithMopOptionsMenu = activitiesWithMopOptionsMenu;
        this.targetCount = targetCount;
        this.activityDist = activityDist;
        this.handlers = handlers;
    }

    // -------------------------------------------------------------------------
    // Test factory — builds a MopData from pre-built structures, bypassing the wire.
    // -------------------------------------------------------------------------

    /**
     * Builds a MopData directly from the query structures, for tests about the <em>query</em> layer
     * rather than about the reader: scoring, frontier and trigger suites state the widget map and
     * the activity set they want instead of authoring an artifact that derives to it.
     *
     * <p>Public rather than package-private because its callers are not: the scoring and agent
     * suites live in {@code ape.agent} and {@code ape.agent.scoring}. It builds no gateway set —
     * a caller that needs one goes through {@link #load}, which is where the recompute lives.
     */
    public static MopData forTest(Map<String, Map<String, Widget>> widgetData,
                           Set<String> mopActivities,
                           Map<String, List<WtgTransition>> wtgTransitions) {
        return forTest(widgetData, mopActivities, wtgTransitions, null, null, null, null);
    }

    public static MopData forTest(Map<String, Map<String, Widget>> widgetData,
                           Set<String> mopActivities,
                           Map<String, List<WtgTransition>> wtgTransitions,
                           List<ComponentInfo.ReceiverInfo> receivers,
                           List<ComponentInfo.ServiceInfo> services,
                           List<ComponentInfo.ActivityInfo> activities,
                           List<ComponentInfo.ProviderInfo> providers) {
        return new MopData(null, null,
                widgetData != null ? widgetData : new HashMap<String, Map<String, Widget>>(),
                mopActivities != null ? mopActivities : new HashSet<String>(),
                wtgTransitions != null ? wtgTransitions : new HashMap<String, List<WtgTransition>>(),
                receivers != null ? receivers : new ArrayList<ComponentInfo.ReceiverInfo>(),
                services != null ? services : new ArrayList<ComponentInfo.ServiceInfo>(),
                activities != null ? activities : new ArrayList<ComponentInfo.ActivityInfo>(),
                providers != null ? providers : new ArrayList<ComponentInfo.ProviderInfo>(),
                new HashSet<String>(),
                0, new HashMap<String, int[][]>(), new HashMap<String, Handler>());
    }

    // -------------------------------------------------------------------------
    // Compact MOP artifact (formatVersion 2)
    // -------------------------------------------------------------------------

    /**
     * The only compact-artifact wire version this jar reads (INV-MOP-34). Format 2 keeps every
     * member of format 1 with its meaning and adds {@code targets}, per-widget {@code dist},
     * {@code activityDist} and {@code handlers} (design D15). There is no reader for format 1.
     */
    private static final int SUPPORTED_FORMAT_VERSION = 2;

    /**
     * Load MOP data from the derived compact MOP artifact that {@code aperv-tool} generates
     * host-side from the full static-analysis JSON.
     *
     * <p>Every parse-time semantic the on-device parser used to perform — the listener-handler ×
     * reachability cross-reference, the D8 synthetic-lambda recovery, the {@code mopRank}
     * collision policy, the empty-id drop, the DIALOG re-keying, the base-activity WTG keying
     * and the A′ union — has already run in the generator. This reader decodes precomputed
     * values and derives nothing (INV-MOP-35), which is why it needs neither the call graph nor
     * a parse budget: the artifact is bounded by construction.
     *
     * <p>A legacy full static-analysis JSON reaching this method is rejected, not tolerated: it
     * carries no {@code formatVersion}, so it takes the {@code version-mismatch} branch and the
     * null it returns aborts the arm (INV-MOP-22). There is no second format and no fallback
     * (design D8): a format-1 artifact takes the same branch.
     *
     * <p>Format 2's {@code targets} and {@code handlers} are always emitted by the generator, so a
     * format-2 artifact lacking either, or whose {@code targets} is not a non-negative integer, is a
     * generator↔jar skew and a {@code parse-error}. A malformed distance pair, by contrast, is
     * dropped and counted ({@code MOP_DATA.droppedPairs}) and the load continues.
     *
     * @param path                  device-local path, or null
     * @param expectedPackage       package to compare against (T1.7), or null to skip
     * @param expectedMainActivity  main activity to compare against (T1.7), or null to skip
     * @param sink                  where the load census, or the reason there is none, is recorded
     * @return populated MopData, or null on: null path / missing file / malformed JSON /
     *         unsupported formatVersion / strict-mode package mismatch
     */
    public static MopData load(String path, String expectedPackage,
            String expectedMainActivity, EventSink sink) {
        return load(path, expectedPackage, expectedMainActivity,
                Config.mopActivitySourceComponents, sink);
    }

    /**
     * Package-visible test seam: {@code load} with the A′ source choice passed in, so the JVM
     * suite can drive both branches of INV-MOP-27 past the {@code static final} wall that
     * {@link Config#mopActivitySourceComponents} sits behind. The public entry reads the flag.
     */
    static MopData load(String path, String expectedPackage, String expectedMainActivity,
            boolean activitySourceComponents, EventSink sink) {
        if (path == null) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(new JSONTokener(readFile(path)));
        } catch (IOException e) {
            Logger.wprintln("MopData: failed to read " + path + ": " + e.getMessage());
            reject(sink, "file-missing");
            return null;
        } catch (JSONException e) {
            Logger.wprintln("MopData: malformed JSON at " + path + ": " + e.getMessage());
            reject(sink, "parse-error");
            return null;
        }

        // Version gate (INV-MOP-34). This is also the branch a legacy full static-analysis JSON
        // takes: it carries no formatVersion, so the mismatch names it. The version replaces the
        // full-JSON era's "complete": true sentinel — the generator derives only from complete
        // analyses, so a versioned artifact is complete by construction.
        int formatVersion = root.optInt("formatVersion", -1);
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            Logger.wprintln("MopData: unsupported formatVersion " + formatVersion + " at " + path
                    + " — this jar reads " + SUPPORTED_FORMAT_VERSION);
            reject(sink, "version-mismatch");
            return null;
        }

        try {
            String packageName = optStringOrNull(root, "package");
            String mainActivity = optStringOrNull(root, "mainActivity");

            // Format 2's two mandatory members (design D15). `get`/`getJSONObject` throw when the
            // member is absent or of the wrong kind, which lands in the parse-error branch below.
            PairReader pairs = new PairReader(requireTargetCount(root));
            Map<String, Handler> handlers = parseCompactHandlers(root.getJSONObject("handlers"),
                    pairs);

            Map<String, Map<String, Widget>> widgetData =
                    parseCompactWidgets(root.optJSONObject("widgets"), pairs);
            Map<String, int[][]> activityDist =
                    parseCompactActivityDist(root.optJSONObject("activityDist"), pairs);
            Set<String> widgetDerivedActivities = stringSet(root.optJSONArray("mopActivities"));
            Set<String> augmentedActivities =
                    stringSet(root.optJSONArray("mopActivitiesAugmented"));
            Map<String, List<WtgTransition>> wtgTransitions =
                    parseCompactWtg(root.optJSONObject("wtg"));

            // INV-MOP-27: the wire ships both sources and the run flag picks one, so the choice
            // stays a run parameter instead of becoming a generation parameter (two artifacts per
            // app). Selecting once, here, is what makes every downstream consumer — launcher
            // census, substrate floor, frontier target tests, gateway condition 2 — read the same
            // set without any of them knowing the flag exists.
            Set<String> selectedActivities =
                    activitySourceComponents ? augmentedActivities : widgetDerivedActivities;

            List<ComponentInfo.ReceiverInfo> receivers = new ArrayList<>();
            List<ComponentInfo.ServiceInfo> services = new ArrayList<>();
            List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
            List<ComponentInfo.ProviderInfo> providers = new ArrayList<>();
            parseCompactComponents(root.optJSONObject("components"),
                    receivers, services, activities, providers);

            MopData data = new MopData(packageName, mainActivity,
                    widgetData, selectedActivities, wtgTransitions,
                    receivers, services, activities, providers,
                    recomputeMopOptionsMenus(
                            parseCompactOptionsMenus(root.optJSONArray("optionsMenus")),
                            wtgTransitions, selectedActivities),
                    pairs.targets, activityDist, handlers);

            // Sanity check (T1.7) — the generator copies both scalars verbatim from the full JSON,
            // so a mismatch means the wrong artifact was derived or pushed for this APK.
            boolean mismatch = false;
            if (expectedPackage != null && !expectedPackage.equals(packageName)) {
                Logger.wprintln("MopData: package mismatch — expected '" + expectedPackage
                        + "' but artifact has '" + packageName + "'");
                mismatch = true;
            }
            if (expectedMainActivity != null && !expectedMainActivity.equals(mainActivity)) {
                Logger.wprintln("MopData: mainActivity mismatch — expected '" + expectedMainActivity
                        + "' but artifact has '" + mainActivity + "'");
                mismatch = true;
            }
            if (mismatch && RunContext.current().spec().mop().strictPackageMatch()) {
                Logger.wprintln("MopData: strict package match enabled — rejecting " + path);
                reject(sink, "package-mismatch");
                return null;
            }

            // The stats block is echoed, never recomputed: droppedFlaggedNoId, the handler-join
            // counters and the orphan-dialog count are facts about a derivation this jar no longer
            // performs, so the only honest source for them is the generator that did.
            JSONObject stats = root.optJSONObject("stats");
            if (stats == null) {
                stats = new JSONObject();
            }
            data.droppedFlaggedNoId = stats.optInt("droppedFlaggedNoId", 0);
            // The provenance pair the full-JSON era could not report: which wire contract was read,
            // and the digest of the static-analysis document this artifact was derived from. The
            // digest is what lets a trace name its exact input — the generator chains it from the
            // source bytes, so a run and the analysis it was steered by are joinable after the fact
            // instead of being matched by filename and date.
            JSONObject source = root.optJSONObject("source");
            String sourceDigest = source == null ? null : optStringOrNull(source, "digest");
            sink.mopData("loaded", null, formatVersion, sourceDigest, packageName,
                    stats.optInt("windows", 0), stats.optInt("widgetsTotal", 0),
                    stats.optInt("flagged", 0), data.droppedFlaggedNoId,
                    stats.optInt("wtgEdges", 0), stats.optInt("handlersUnmatched", 0),
                    stats.optInt("syntheticLambda", 0), stats.optInt("recovered", 0),
                    data.mopActivities.size(),
                    countOnlyIn(augmentedActivities, widgetDerivedActivities),
                    receivers.size() + services.size() + activities.size() + providers.size(),
                    pairs.targets, handlers.size(), pairs.dropped);
            return data;
        } catch (JSONException e) {
            Logger.wprintln("MopData: malformed artifact structure at " + path + ": "
                    + e.getMessage());
            reject(sink, "parse-error");
            return null;
        }
    }

    /**
     * Decode the wire {@code widgets} map ({@code baseActivity → shortId → widget}) into the
     * exact lookup structure {@link #getWidget} serves. The wire key <em>is</em> the short
     * resource id: base-activity scoping, the collision policy and the empty-id drop all ran
     * host-side, so a key present here is a widget the query side can reach.
     */
    private static Map<String, Map<String, Widget>> parseCompactWidgets(JSONObject widgetsObj,
            PairReader pairs) throws JSONException {
        Map<String, Map<String, Widget>> widgetData = new HashMap<>();
        if (widgetsObj == null) {
            return widgetData;
        }
        for (Iterator<String> ai = widgetsObj.keys(); ai.hasNext(); ) {
            String activity = ai.next();
            JSONObject byId = widgetsObj.optJSONObject(activity);
            if (byId == null) {
                continue;
            }
            Map<String, Widget> widgets = new LinkedHashMap<>();
            for (Iterator<String> wi = byId.keys(); wi.hasNext(); ) {
                String shortId = wi.next();
                JSONObject wo = byId.optJSONObject(shortId);
                if (wo != null) {
                    widgets.put(shortId, parseCompactWidget(shortId, wo, pairs));
                }
            }
            widgetData.put(activity, widgets);
        }
        return widgetData;
    }

    /**
     * Decode one wire widget: the consumed metadata fields (absent ⇒ null/empty), then its
     * {@code mop} and {@code dist} maps through {@link #decodeEntry}, the reader the handler table
     * shares.
     */
    private static Widget parseCompactWidget(String shortId, JSONObject wo, PairReader pairs)
            throws JSONException {
        Widget w = new Widget();
        w.idName = shortId;
        w.hint = optStringOrNull(wo, "hint");
        w.inputType = optStringOrNull(wo, "inputType");
        w.prompt = optStringOrNull(wo, "prompt");
        w.spinnerMode = optStringOrNull(wo, "spinnerMode");
        w.contentDescription = optStringOrNull(wo, "contentDescription");
        w.tooltipText = optStringOrNull(wo, "tooltipText");
        JSONArray entries = wo.optJSONArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                w.entries.add(entries.optString(i, ""));
            }
        }
        decodeEntry(wo, w, pairs);
        return w;
    }

    /**
     * Decode the per-event maps every format-2 entry carries — a widget of {@code widgets} and a
     * class of {@code handlers} alike: the {@code mop} map of normalized eventType →
     * {@code none|direct|transitive|both}, and the {@code dist} map of normalized eventType →
     * {@code [[i, d], …]}.
     *
     * <p>The four tokens decode positionally into the two bits the query side reads
     * ({@code none}=00, {@code direct}=10, {@code transitive}=01, {@code both}=11), so the map
     * is lossless with respect to the two per-eventType maps the full-JSON derivation built. An
     * entry is created for <em>every</em> wire key, {@code none} included: key presence is what
     * makes {@code isDirectMop}/{@code isTransitiveMop} answer per-event instead of falling back
     * to the aggregate (INV-MOP-14), so omitting a {@code none} would change the answer. The
     * aggregates are the OR across the map, which is INV-MOP-17 by construction.
     *
     * <p>The pairs keep wire order and have no aggregate: an event without a {@code dist} entry
     * has no pair. Malformed pairs are dropped and counted by {@code pairs}.
     */
    private static void decodeEntry(JSONObject wire, MopEntry into, PairReader pairs)
            throws JSONException {
        JSONObject mop = wire.optJSONObject("mop");
        if (mop != null) {
            for (Iterator<String> it = mop.keys(); it.hasNext(); ) {
                String eventType = it.next();
                String encoded = mop.optString(eventType, "none");
                boolean direct = "direct".equals(encoded) || "both".equals(encoded);
                boolean transitive = "transitive".equals(encoded) || "both".equals(encoded);
                // Normalize on ingest rather than trust the generator to have done it: the query
                // side normalizes (INV-MOP-08), so a key that arrived unnormalized would be present
                // in the map and unreachable through every accessor. orInto also merges two wire
                // keys that collapse onto the same normalized event.
                String key = normalizeEventType(eventType);
                orInto(into.directMopByEventType, key, direct);
                orInto(into.transitiveMopByEventType, key, transitive);
                into.directMop |= direct;
                into.transitiveMop |= transitive;
            }
        }
        JSONObject dist = wire.optJSONObject("dist");
        if (dist != null) {
            for (Iterator<String> it = dist.keys(); it.hasNext(); ) {
                String eventType = it.next();
                int[][] read = pairs.read(dist.optJSONArray(eventType));
                if (read.length > 0) {
                    // Same normalization as the flags; two wire keys collapsing onto one event
                    // merge by the minimum per target, the collision rule of the distances (D15).
                    String key = normalizeEventType(eventType);
                    int[][] prev = into.distByEventType.get(key);
                    into.distByEventType.put(key, prev == null ? read : mergeByMinimum(prev, read));
                }
            }
        }
    }

    /**
     * Decode the format-2 {@code handlers} table (binary class name → record). Every listed class
     * is kept, including one that reaches nothing: the table's membership is itself a statement
     * the stamp-first lookup reads (design D17).
     */
    private static Map<String, Handler> parseCompactHandlers(JSONObject handlersObj,
            PairReader pairs) throws JSONException {
        Map<String, Handler> handlers = new HashMap<>();
        for (Iterator<String> it = handlersObj.keys(); it.hasNext(); ) {
            String className = it.next();
            JSONObject ho = handlersObj.optJSONObject(className);
            if (ho != null) {
                Handler h = new Handler(className);
                decodeEntry(ho, h, pairs);
                handlers.put(className, h);
            }
        }
        return handlers;
    }

    /** Decode the format-2 {@code activityDist} map (base activity → pairs), absent ⇒ empty. */
    private static Map<String, int[][]> parseCompactActivityDist(JSONObject activityDistObj,
            PairReader pairs) {
        Map<String, int[][]> activityDist = new HashMap<>();
        if (activityDistObj == null) {
            return activityDist;
        }
        for (Iterator<String> it = activityDistObj.keys(); it.hasNext(); ) {
            String activity = it.next();
            int[][] read = pairs.read(activityDistObj.optJSONArray(activity));
            if (read.length > 0) {
                activityDist.put(activity, read);
            }
        }
        return activityDist;
    }

    /**
     * The format-2 {@code targets} member: mandatory, a JSON integer, not negative. Anything else
     * is a generator↔jar skew, thrown as a {@link JSONException} so the load is a
     * {@code parse-error} (design, Error Handling). {@code "27"} is refused although
     * {@code optInt} would coerce it: the generator writes a number, and a string is a different
     * writer.
     */
    private static int requireTargetCount(JSONObject root) throws JSONException {
        Object targets = root.get("targets");
        if (!(targets instanceof Integer) || (Integer) targets < 0) {
            throw new JSONException("targets is not a non-negative integer: " + targets);
        }
        return (Integer) targets;
    }

    /**
     * Two pair lists of one key merged by the minimum distance per target, sorted by distance and
     * then index — the order the wire gives. Reached only when two wire event keys normalize onto
     * one event, which a conforming generator, normalizing itself, never emits.
     */
    private static int[][] mergeByMinimum(int[][] a, int[][] b) {
        Map<Integer, Integer> best = new HashMap<>();
        for (int[][] list : new int[][][]{a, b}) {
            for (int[] p : list) {
                Integer prev = best.get(p[0]);
                if (prev == null || p[1] < prev) {
                    best.put(p[0], p[1]);
                }
            }
        }
        List<int[]> merged = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : best.entrySet()) {
            merged.add(new int[]{e.getKey(), e.getValue()});
        }
        Collections.sort(merged, (x, y) -> x[1] != y[1]
                ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]));
        return merged.toArray(new int[0][]);
    }

    /**
     * The one reader of format-2 distance pairs, shared by a widget's {@code dist}, the
     * {@code activityDist} map and a handler's {@code dist}. It holds the artifact's target count,
     * which bounds every index, and counts what it drops for the load record's
     * {@code droppedPairs}.
     */
    private static final class PairReader {
        final int targets;
        int dropped;

        PairReader(int targets) {
            this.targets = targets;
        }

        /**
         * The well-formed pairs of a wire list, in wire order: each a two-integer array whose index
         * lies in {@code [0, targets)} and whose distance is not negative. Every other element is
         * dropped and counted, and the read goes on. A null list (absent, or not an array) has no
         * pair.
         */
        int[][] read(JSONArray list) {
            if (list == null) {
                return NO_PAIRS;
            }
            List<int[]> kept = new ArrayList<>(list.length());
            for (int i = 0; i < list.length(); i++) {
                JSONArray pair = list.optJSONArray(i);
                Object index = pair == null || pair.length() != 2 ? null : pair.opt(0);
                Object distance = index == null ? null : pair.opt(1);
                if (index instanceof Integer && distance instanceof Integer
                        && (Integer) index >= 0 && (Integer) index < targets
                        && (Integer) distance >= 0) {
                    kept.add(new int[]{(Integer) index, (Integer) distance});
                } else {
                    dropped++;
                }
            }
            return kept.isEmpty() ? NO_PAIRS : kept.toArray(new int[0][]);
        }
    }

    /**
     * Decode the wire {@code wtg} map ({@code sourceBaseActivity → [{widget, target}]}). Both
     * ends are already base activities and exact duplicates are already removed (INV-WTG-04,
     * INV-DRV-03), so this is a transcription — no keying, no dedup, no window lookup.
     */
    private static Map<String, List<WtgTransition>> parseCompactWtg(JSONObject wtg)
            throws JSONException {
        Map<String, List<WtgTransition>> view = new HashMap<>();
        if (wtg == null) {
            return view;
        }
        for (Iterator<String> it = wtg.keys(); it.hasNext(); ) {
            String sourceActivity = it.next();
            JSONArray edges = wtg.optJSONArray(sourceActivity);
            if (edges == null) {
                continue;
            }
            List<WtgTransition> list = new ArrayList<>();
            for (int i = 0; i < edges.length(); i++) {
                JSONObject e = edges.optJSONObject(i);
                if (e != null) {
                    list.add(new WtgTransition(e.optString("widget", ""),
                            e.optString("target", "")));
                }
            }
            view.put(sourceActivity, list);
        }
        return view;
    }

    /**
     * Decode the wire {@code optionsMenus} records into {@code activity → hasFlaggedWidget}. This
     * is all that survives of the {@code OPTIONSMENU} windows: the gateway set itself cannot be
     * shipped precomputed because it depends on which MOP-activity set the run selects (D3).
     */
    private static Map<String, Boolean> parseCompactOptionsMenus(JSONArray arr)
            throws JSONException {
        Map<String, Boolean> records = new LinkedHashMap<>();
        if (arr == null) {
            return records;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String activity = optStringOrNull(o, "activity");
            if (activity != null) {
                records.put(activity, o.optBoolean("hasFlaggedWidget", false));
            }
        }
        return records;
    }

    /**
     * The OPTIONSMENU gateway set (INV-MOP-13), recomputed on-device because condition 2 reads the
     * <em>selected</em> MOP-activity set and the selection is a run flag. An activity qualifies
     * when its own menu holds a flagged widget, or when any click edge out of that base activity
     * lands on a member of {@code mopActivities} — the same two conditions the full-JSON precompute
     * applied, over the same click-only WTG view. Pure: reads its three arguments and nothing else.
     */
    static Set<String> recomputeMopOptionsMenus(Map<String, Boolean> optionsMenus,
            Map<String, List<WtgTransition>> wtgTransitions, Set<String> mopActivities) {
        Set<String> gateways = new HashSet<>();
        for (Map.Entry<String, Boolean> record : optionsMenus.entrySet()) {
            String activity = record.getKey();
            if (Boolean.TRUE.equals(record.getValue())) {
                gateways.add(activity);
                continue;
            }
            for (WtgTransition t : getOrEmpty(wtgTransitions, activity)) {
                if (mopActivities.contains(t.targetActivity)) {
                    gateways.add(activity);
                    break;
                }
            }
        }
        return gateways;
    }

    private static List<WtgTransition> getOrEmpty(Map<String, List<WtgTransition>> wtg, String key) {
        List<WtgTransition> list = wtg.get(key);
        return list != null ? list : Collections.<WtgTransition>emptyList();
    }

    /**
     * Decode the wire {@code components} block. Each entry carries only the trigger surface the
     * inventory records a production reader for; the component type comes from the parent array
     * key (D19).
     */
    private static void parseCompactComponents(JSONObject components,
                                               List<ComponentInfo.ReceiverInfo> receivers,
                                               List<ComponentInfo.ServiceInfo> services,
                                               List<ComponentInfo.ActivityInfo> activities,
                                               List<ComponentInfo.ProviderInfo> providers)
            throws JSONException {
        if (components == null) {
            return;
        }
        JSONArray recvArr = components.optJSONArray("receivers");
        if (recvArr != null) {
            for (int i = 0; i < recvArr.length(); i++) {
                JSONObject co = recvArr.getJSONObject(i);
                receivers.add(new ComponentInfo.ReceiverInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        parseCompactIntentFilters(co),
                        co.optBoolean("reachesMop", false), targetMethodsOfWireArity(co),
                        optStringOrNull(co, "permission")));
            }
        }
        JSONArray svcArr = components.optJSONArray("services");
        if (svcArr != null) {
            for (int i = 0; i < svcArr.length(); i++) {
                JSONObject co = svcArr.getJSONObject(i);
                services.add(new ComponentInfo.ServiceInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        parseCompactIntentFilters(co),
                        co.optBoolean("reachesMop", false), targetMethodsOfWireArity(co),
                        optStringOrNull(co, "permission")));
            }
        }
        JSONArray actArr = components.optJSONArray("activities");
        if (actArr != null) {
            for (int i = 0; i < actArr.length(); i++) {
                JSONObject co = actArr.getJSONObject(i);
                activities.add(new ComponentInfo.ActivityInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        Collections.<ComponentInfo.IntentFilter>emptyList(),
                        co.optBoolean("reachesMop", false), Collections.<String>emptyList(),
                        optStringOrNull(co, "permission"),
                        optStringOrNull(co, "deepLinkUri")));
            }
        }
        JSONArray provArr = components.optJSONArray("providers");
        if (provArr != null) {
            for (int i = 0; i < provArr.length(); i++) {
                JSONObject co = provArr.getJSONObject(i);
                providers.add(new ComponentInfo.ProviderInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        Collections.<ComponentInfo.IntentFilter>emptyList(),
                        co.optBoolean("reachesMop", false), Collections.<String>emptyList(),
                        optStringOrNull(co, "authorities"),
                        optStringOrNull(co, "permission")));
            }
        }
    }

    /** Wire intent filters: actions and categories only — the {@code data} block has no reader. */
    private static List<ComponentInfo.IntentFilter> parseCompactIntentFilters(JSONObject co)
            throws JSONException {
        List<ComponentInfo.IntentFilter> filters = new ArrayList<>();
        JSONArray arr = co.optJSONArray("intentFilters");
        if (arr == null) {
            return filters;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject fo = arr.getJSONObject(i);
            filters.add(new ComponentInfo.IntentFilter(
                    stringList(fo.optJSONArray("actions")),
                    stringList(fo.optJSONArray("categories"))));
        }
        return filters;
    }

    /**
     * The wire carries {@code hasTargetMethods} rather than the signature list because the one
     * surviving consumer is an emptiness test — {@code StatefulAgent.buildTriggerTuples}' "no
     * intent filters but reachable target methods" tuple fallback. Rebuild a list of the right
     * emptiness; the signatures themselves have no reader on this side.
     */
    private static List<String> targetMethodsOfWireArity(JSONObject co) {
        return co.optBoolean("hasTargetMethods", false)
                ? Collections.singletonList("") : Collections.<String>emptyList();
    }

    private static Set<String> stringSet(JSONArray arr) {
        Set<String> set = new HashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null) {
                    set.add(s);
                }
            }
        }
        return set;
    }

    /**
     * How many members of {@code superset} are absent from {@code base} — the number the load
     * record reports as {@code mopActsAugmented}.
     *
     * <p>This is <em>not</em> the number the pre-change record carried, and the difference is the
     * point. That one counted the entries the augmentation applied, which is 0 on every run with
     * {@code mopActivitySourceComponents} off; this one is the difference between the two wire
     * sets, so it reports what the augmented source <em>would</em> contribute and does not vary
     * with the flag. Availability has no other carrier, while the applied augmentation stays
     * recoverable as flag × availability — {@code RUN_START} publishes the flag in its feature
     * list and its params echo. A flag-off run therefore reports N here where it used to report 0,
     * and that is the field working.
     */
    private static int countOnlyIn(Set<String> superset, Set<String> base) {
        int count = 0;
        for (String s : superset) {
            if (!base.contains(s)) {
                count++;
            }
        }
        return count;
    }

    private static void orInto(Map<String, Boolean> map, String key, boolean value) {
        Boolean prev = map.get(key);
        map.put(key, (prev != null && prev) || value);
    }

    /**
     * Canonicalize an {@code eventType} token so producer snake_case and consumer
     * camelCase forms of the same event compare equal (INV-MOP-08): lowercase and
     * strip separators, so {@code long_click} and {@code longClick} both map to
     * {@code longclick}. Returns null for null. Applied on both the map-building
     * side (JSON keys) and the query side (Widget.isDirectMop/isTransitiveMop).
     */
    static String normalizeEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(eventType.length());
        for (int i = 0; i < eventType.length(); i++) {
            char c = eventType.charAt(i);
            if (c == '_' || c == '-') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String getPackageName() { return packageName; }

    public String getMainActivity() { return mainActivity; }

    /** Returns the MOP flags / metadata for a widget, or null if no match. */
    public Widget getWidget(String activity, String shortId) {
        Map<String, Widget> widgets = widgetData.get(activity);
        if (widgets == null) return null;
        return widgets.get(shortId);
    }

    /**
     * The number of targets every distance pair indexes (format 2 {@code targets}, design D15). 0
     * for an artifact with no target, which loads and runs without distance data.
     */
    public int targetCount() {
        return targetCount;
    }

    /**
     * The distance pairs {@code {targetIndex, distance}} of a base activity (format 2
     * {@code activityDist}, design D15; read by the launcher, D20), in wire order, or an empty
     * array when the activity has none. The array is the stored one; callers read only.
     */
    public int[][] activityDist(String activity) {
        int[][] pairs = activity == null ? null : activityDist.get(activity);
        return pairs != null ? pairs : NO_PAIRS;
    }

    /**
     * The {@code handlers} record of a binary class name, or null when the table does not list the
     * class (design D15). For the stamp-first lookup (D17), which falls back to the id key on null.
     */
    public Handler getHandler(String binaryClassName) {
        return binaryClassName == null ? null : handlers.get(binaryClassName);
    }

    /**
     * What MOP knows about one node for one event (mop-guidance "Handler Stamp Lookup", design
     * D17, INV-MOP-36). Every reader of MOP flags or distances for a node goes through here.
     *
     * <ol>
     *   <li><b>Stamp.</b> For {@code click} and {@code longClick} only, the node's stamp for that
     *       event ({@link GUITreeNode#getHandlerClick()} answers the wire key {@code click},
     *       {@link GUITreeNode#getHandlerLongClick()} the wire key {@code longclick}), when
     *       {@code handlers} lists its class: that class's record for the event, source
     *       {@code STAMP}, even when it reaches nothing. The record is read for the event alone —
     *       an event missing from it reads as {@code none} with no pair, with no aggregate fallback.
     *   <li><b>Id key.</b> Otherwise the widget of {@code (activity, shortId)}: its flags for the
     *       event with today's per-event → aggregate fallback (INV-MOP-14), and its pairs under the
     *       event's key only, source {@code ID}.
     *   <li>Otherwise {@link Lookup#NONE}.
     * </ol>
     *
     * <p>A stamp naming a class {@code handlers} does not list (a library dispatcher such as
     * {@code AppCompatViewInflater$DeclaredOnClickListener}) falls back to the id key. Never null;
     * pure — it reads the artifact and the node, never the retirement state.
     *
     * @param eventType the event as {@code MopScorer.eventTypeOf} spells it; null skips the stamp
     *                  and reads the id key's aggregate flags with no pair
     */
    public Lookup lookup(String activity, GUITreeNode node, String eventType) {
        if (node == null) {
            return Lookup.NONE;
        }
        String event = normalizeEventType(eventType);
        Handler stamped = getHandler(stampFor(node, event));
        if (stamped != null) {
            return new Lookup(stamped.isDirectMopForEvent(event),
                    stamped.isTransitiveMopForEvent(event), stamped.dist(event),
                    Lookup.Source.STAMP);
        }
        Widget widget = getWidget(activity, extractShortId(node.getResourceID()));
        if (widget != null) {
            return new Lookup(widget.isDirectMop(event), widget.isTransitiveMop(event),
                    widget.dist(event), Lookup.Source.ID);
        }
        return Lookup.NONE;
    }

    /**
     * The node's stamp for a normalized event, or null: gh121 stamps click and long-click handlers
     * only, so every other event (and a null one) has no stamp to consult.
     */
    private static String stampFor(GUITreeNode node, String normalizedEvent) {
        if (EVENT_CLICK.equals(normalizedEvent)) {
            return node.getHandlerClick();
        }
        if (EVENT_LONG_CLICK.equals(normalizedEvent)) {
            return node.getHandlerLongClick();
        }
        return null;
    }

    /** The normalized wire keys of the two stamped events (D17: {@code click}, {@code longclick}). */
    private static final String EVENT_CLICK = normalizeEventType("click");
    private static final String EVENT_LONG_CLICK = normalizeEventType("longClick");

    /** True if the (base) activity has at least one MOP-reachable widget. */
    public boolean activityHasMop(String activity) {
        return mopActivities.contains(activity);
    }

    /**
     * The reachability-augmented MOP activity set (INV-MOP-27) — the membership E-mín's
     * {@code selectTriggerCandidate} orders by. Backed by the live set (not a copy); callers read
     * only. Distinct from the per-activity {@link #activityHasMop} query so the pure launcher seam
     * can take it as a parameter.
     */
    public Set<String> getMopActivities() {
        return mopActivities;
    }

    /** Count of MOP-flagged widgets the generator dropped for lacking a resource id (INV-MOP-20). */
    public int getDroppedFlaggedNoId() {
        return droppedFlaggedNoId;
    }

    /** True if the activity's OPTIONSMENU is a MOP gateway (T1.2, D13). */
    public boolean activityHasMopOptionsMenu(String activity) {
        return activitiesWithMopOptionsMenu.contains(activity);
    }

    public boolean hasWtgData() {
        return !wtgTransitions.isEmpty();
    }

    /**
     * Click transitions originating from the given base activity.
     *
     * @param activityName base source activity (the view is keyed by base activity, with any
     *                     "#OptionsMenu"/fragment suffix stripped — INV-WTG-04)
     */
    public List<WtgTransition> getWtgTransitions(String activityName) {
        List<WtgTransition> list = wtgTransitions.get(activityName);
        return list != null ? list : Collections.<WtgTransition>emptyList();
    }

    public List<ComponentInfo.ReceiverInfo> getReceivers() { return receivers; }

    public List<ComponentInfo.ServiceInfo> getServices() { return services; }

    public List<ComponentInfo.ActivityInfo> getActivities() { return activities; }

    public List<ComponentInfo.ProviderInfo> getProviders() { return providers; }

    public boolean hasComponents() {
        return !receivers.isEmpty() || !services.isEmpty()
                || !activities.isEmpty() || !providers.isEmpty();
    }

    /**
     * Extract the short resource ID from a full Android resource ID string.
     * "com.example:id/btn_encrypt" → "btn_encrypt"; null or no ":id/" → "".
     */
    public static String extractShortId(String resourceId) {
        if (resourceId == null) return "";
        int idx = resourceId.indexOf(":id/");
        return idx < 0 ? "" : resourceId.substring(idx + 4);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the whole file into a single, pre-sized {@code byte[]} and decodes it once as UTF-8.
     * A file larger than {@code Integer.MAX_VALUE} cannot be array-backed and is rejected; no
     * artifact approaches that, and nothing rejects a smaller one, because the size guard this
     * check used to backstop is gone with the parser that needed it. Whole-file read is mandatory:
     * the Android-bundled org.json only offers {@code JSONTokener(String)}.
     */
    private static String readFile(String path) throws IOException {
        File f = new File(path);
        long length = f.length();
        if (length > Integer.MAX_VALUE) {
            throw new IOException("file too large to read into memory: " + length + " bytes");
        }
        byte[] bytes = new byte[(int) length];
        int off = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) != -1) {
                off += n;
            }
        }
        // If the file shrank between length() and the read, decode only what was actually read.
        return new String(bytes, 0, off, StandardCharsets.UTF_8);
    }

    private static String optStringOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    private static List<String> stringList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.optString(i, ""));
            }
        }
        return list;
    }

    /**
     * Records a load that produced no data, with the reason it produced none.
     *
     * <p>A rejection has no census to report — nothing was parsed — so every count is zero and the
     * reason is the whole content. It is a record rather than a line because the analysis side
     * excludes and annotates runs by it (INV-MOP-21/26), and a run whose MOP arm never armed must
     * say so in the same stream that says everything else.
     */
    private static void reject(EventSink sink, String reason) {
        // A rejected load has no census to report: every counter is zero and the provenance pair is
        // absent, including on version-mismatch, where a formatVersion was read but is by
        // definition not one this jar can vouch for. The reason is the record's whole content.
        sink.mopData("rejected", reason, 0, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0);
    }

    // -------------------------------------------------------------------------
    // POJOs (all nested under MopData — D1/D2)
    // -------------------------------------------------------------------------

    /**
     * One addressable widget of the wire's {@code widgets} map: the metadata the prompt builder
     * and the typed-input path consume, plus, through {@link MopEntry}, the MOP flags scoring
     * reads and the format-2 distance pairs. The fields are exactly
     * what the artifact carries — the producer's raw {@code id}, {@code type}, {@code text} and
     * {@code listeners[]} are inputs to a derivation that now happens host-side, so they never
     * reach the device.
     */
    public static class Widget extends MopEntry {
        public String idName;
        public String hint;
        public String inputType;
        public final List<String> entries = new ArrayList<>();
        public String prompt;
        public String spinnerMode;
        public String contentDescription;
        public String tooltipText;
    }

    /**
     * What the artifact knows about one entry, per normalized event: the MOP flags of its
     * {@code mop} map and the distance pairs of its {@code dist} map. Both entry kinds of format 2
     * carry this pair of maps under the same keys — a {@link Widget} of the {@code widgets} map
     * and a {@link Handler} of the {@code handlers} table — so they decode through one reader and
     * answer through one set of accessors.
     */
    public static class MopEntry {
        // Decoded from the wire's per-eventType `mop` map — INV-MOP-17.
        public boolean directMop;
        public boolean transitiveMop;
        public final Map<String, Boolean> directMopByEventType = new HashMap<>();
        public final Map<String, Boolean> transitiveMopByEventType = new HashMap<>();
        /**
         * Decoded from the wire's per-eventType {@code dist} map (format 2, design D15): normalized
         * event → pairs {@code {targetIndex, distance}}, in wire order (ascending distance, then
         * index; at most three). Every stored index lies in {@code [0, targetCount())}: a
         * malformed pair is dropped at load and counted ({@code MOP_DATA.droppedPairs}).
         * Callers read only.
         */
        public final Map<String, int[][]> distByEventType = new HashMap<>();

        /**
         * The distance pairs {@code {targetIndex, distance}} for the given event type, or an empty
         * array. Unlike the flags, distances have no aggregate fallback: an event with no
         * {@code dist} entry has no pair. The array is the stored one; callers read only.
         */
        public int[][] dist(String eventType) {
            String key = normalizeEventType(eventType);
            int[][] pairs = key == null ? null : distByEventType.get(key);
            return pairs != null ? pairs : NO_PAIRS;
        }

        /** Direct-MOP flag for the given event type, falling back to the aggregate (match-any). */
        public boolean isDirectMop(String eventType) {
            return flag(directMopByEventType, eventType, directMop);
        }

        /** Transitive-MOP flag for the given event type, falling back to the aggregate. */
        public boolean isTransitiveMop(String eventType) {
            return flag(transitiveMopByEventType, eventType, transitiveMop);
        }

        /**
         * Direct-MOP flag for the given event type alone: an event with no key reads as
         * {@code none}. The stamp step of {@link MopData#lookup} reads a handler record this way
         * (design D17), because the aggregate of a handler class would answer an event its stamp
         * never named.
         */
        public boolean isDirectMopForEvent(String eventType) {
            return flag(directMopByEventType, eventType, false);
        }

        /** Transitive-MOP flag for the given event type alone; see {@link #isDirectMopForEvent}. */
        public boolean isTransitiveMopForEvent(String eventType) {
            return flag(transitiveMopByEventType, eventType, false);
        }

        /** The per-event flag when the map has the event's key, else {@code absent}. */
        private static boolean flag(Map<String, Boolean> byEvent, String eventType,
                boolean absent) {
            String key = normalizeEventType(eventType);
            if (key != null && byEvent.containsKey(key)) {
                return Boolean.TRUE.equals(byEvent.get(key));
            }
            return absent;
        }
    }

    /**
     * One record of the format-2 {@code handlers} table: the flags and distance pairs of a handler
     * class, keyed on the wire by its binary class name, the form gh121's stamp and
     * {@code reachability[].className} use (design D15). A listed class whose {@code mop} is
     * {@code none} and that carries no pair is kept on purpose: it states that this handler
     * reaches no target, which the stamp-first lookup relies on (D17).
     */
    public static class Handler extends MopEntry {
        public final String className;

        public Handler(String className) {
            this.className = className;
        }
    }

    /**
     * The answer of {@link MopData#lookup} for one node and one event (design D17, API Design
     * "Part B: {@code MopData.Lookup}"): the direct and transitive flags, the distance pairs
     * {@code {targetIndex, distance}} (possibly empty, never null) and where they came from.
     * Immutable; {@link #dist} is the artifact's stored array, which callers read only.
     */
    public static final class Lookup {

        /** Which step of the lookup answered. */
        public enum Source {
            /** The node's handler stamp named a class {@code handlers} lists. */
            STAMP,
            /** The id key {@code (activity, shortId)} resolved a widget. */
            ID,
            /** Neither: no flag and no pair. */
            NONE
        }

        /** The lookup that found nothing. */
        public static final Lookup NONE = new Lookup(false, false, NO_PAIRS, Source.NONE);

        public final boolean direct;
        public final boolean transitive;
        public final int[][] dist;
        public final Source source;

        Lookup(boolean direct, boolean transitive, int[][] dist, Source source) {
            this.direct = direct;
            this.transitive = transitive;
            this.dist = dist != null ? dist : NO_PAIRS;
            this.source = source;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Lookup{").append(source)
                    .append(direct ? ",direct" : "").append(transitive ? ",transitive" : "");
            for (int[] pair : dist) {
                sb.append(",[").append(pair[0]).append(',').append(pair[1]).append(']');
            }
            return sb.append('}').toString();
        }
    }

    /**
     * A click edge of the WTG view: which widget, and which base activity it navigates to. The
     * producer's {@code widgetClass} is not carried — the three consumers match on
     * {@code widgetName} and read {@code targetActivity}, and none has ever consulted the class.
     */
    public static class WtgTransition {
        public final String widgetName;
        public final String targetActivity;

        public WtgTransition(String widgetName, String targetActivity) {
            this.widgetName = widgetName;
            this.targetActivity = targetActivity;
        }
    }
}
