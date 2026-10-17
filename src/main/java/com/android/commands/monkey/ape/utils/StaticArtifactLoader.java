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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.utils.StaticGuiModel.WidgetMeta;
import com.android.commands.monkey.ape.utils.StaticGuiModel.WtgTransition;

/**
 * Reads the compact static-analysis artifact (wire format 2) once and splits it into the run's two
 * read-only providers (guidance-groups "Static Data Root and Split Providers", plan P-1):
 * {@link StaticGuiModel} — the WTG, the widget metadata, the package, the main activity and the
 * components with their intent filters — and {@link MopData} — the per-event flags and distances of
 * widgets and handlers, {@code activityDist}, the selected MOP activity set, the OPTIONSMENU
 * gateways, the components' {@code reachesMop} and the target count.
 *
 * <p>The artifact is generated host-side by {@code aperv-tool} from the full static-analysis JSON,
 * and every parse-time semantic the on-device parser used to perform runs there (see
 * {@link MopData}, "Reader, not parser"). It is read whole into an {@code org.json.JSONObject} and
 * navigated in memory (design D21 — {@code android.util.JsonReader} is excluded from the surefire
 * test classpath, so a streaming reader could not be unit-tested). No parse budget and no
 * {@code OutOfMemoryError} containment: the artifact is a projection bounded by construction, so a
 * pathological one is a generator bug caught on the host, not a device-runtime condition to
 * survive (design D5).
 *
 * <p>The load records one {@code MOP_DATA} record, the census of the loaded artifact or the reason
 * there is none, whichever group asked for it (design D-B14.9).
 */
public final class StaticArtifactLoader {

    /**
     * The only artifact wire version this jar reads (INV-MOP-34). Format 2 keeps every member of
     * format 1 with its meaning and adds {@code targets}, per-widget {@code dist},
     * {@code activityDist} and {@code handlers} (design D15), and, additively, the reduced
     * {@code data} of a receiver's or service's intent filter (design D-B13). There is no reader
     * for format 1.
     */
    private static final int SUPPORTED_FORMAT_VERSION = 2;

    private StaticArtifactLoader() {}

    /** The two providers of one loaded artifact; both non-null. */
    public static final class Loaded {
        public final StaticGuiModel staticModel;
        public final MopData mopData;

        Loaded(StaticGuiModel staticModel, MopData mopData) {
            this.staticModel = staticModel;
            this.mopData = mopData;
        }
    }

    /**
     * Load the artifact at {@code path} and split it into its two providers.
     *
     * <p>A legacy full static-analysis JSON reaching this method is rejected, not tolerated: it
     * carries no {@code formatVersion}, so it takes the {@code version-mismatch} branch, and the
     * null returned aborts the run (INV-MOP-22). There is no second format and no fallback (design
     * D8): a format-1 artifact takes the same branch.
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
     * @return both providers, or null on: null path / missing file / malformed JSON /
     *         unsupported formatVersion / strict-mode package mismatch
     */
    public static Loaded load(String path, String expectedPackage, String expectedMainActivity,
            EventSink sink) {
        return load(path, expectedPackage, expectedMainActivity,
                RunContext.current().spec().has(Feature.MOP_ACTIVITY_SOURCE), sink);
    }

    /**
     * Package-visible test seam: {@code load} with the A′ source choice passed in, so the JVM
     * suite can drive both branches of INV-MOP-27 without a plan that mounts
     * {@code MOP_ACTIVITY_SOURCE}. The public entry reads the plan.
     */
    static Loaded load(String path, String expectedPackage, String expectedMainActivity,
            boolean activitySourceComponents, EventSink sink) {
        if (path == null) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(new JSONTokener(readFile(path)));
        } catch (IOException e) {
            Logger.wprintln("StaticArtifactLoader: failed to read " + path + ": " + e.getMessage());
            reject(sink, "file-missing");
            return null;
        } catch (JSONException e) {
            Logger.wprintln("StaticArtifactLoader: malformed JSON at " + path + ": "
                    + e.getMessage());
            reject(sink, "parse-error");
            return null;
        }

        // Version gate (INV-MOP-34). This is also the branch a legacy full static-analysis JSON
        // takes: it carries no formatVersion, so the mismatch names it. The version says nothing
        // about the analysis: the generator does not read the producer's "complete" sentinel and
        // has no guard (design D15, gh120 D9), so an artifact derived from an incomplete or
        // pre-gh120 analysis passes here (empty WTG, stats.wtgEdges == 0).
        int formatVersion = root.optInt("formatVersion", -1);
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            Logger.wprintln("StaticArtifactLoader: unsupported formatVersion " + formatVersion
                    + " at " + path + " — this jar reads " + SUPPORTED_FORMAT_VERSION);
            reject(sink, "version-mismatch");
            return null;
        }

        try {
            String packageName = optStringOrNull(root, "package");
            String mainActivity = optStringOrNull(root, "mainActivity");

            // Format 2's two mandatory members (design D15). `get`/`getJSONObject` throw when the
            // member is absent or of the wrong kind, which lands in the parse-error branch below.
            PairReader pairs = new PairReader(requireTargetCount(root));
            Map<String, MopData.Handler> handlers =
                    parseHandlers(root.getJSONObject("handlers"), pairs);

            Map<String, Map<String, MopData.Widget>> widgetMop = new HashMap<>();
            Map<String, Map<String, WidgetMeta>> widgetMeta = new HashMap<>();
            parseWidgets(root.optJSONObject("widgets"), pairs, widgetMop, widgetMeta);
            Map<String, int[][]> activityDist =
                    parseActivityDist(root.optJSONObject("activityDist"), pairs);
            Set<String> widgetDerivedActivities = stringSet(root.optJSONArray("mopActivities"));
            Set<String> augmentedActivities =
                    stringSet(root.optJSONArray("mopActivitiesAugmented"));

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
            Set<String> reachingMop = new HashSet<>();
            parseComponents(root.optJSONObject("components"), receivers, services, activities,
                    providers, reachingMop);

            StaticGuiModel staticModel = new StaticGuiModel(packageName, mainActivity,
                    parseWtg(root.optJSONObject("wtg")), widgetMeta,
                    receivers, services, activities, providers);

            // Sanity check (T1.7) — the generator copies both scalars verbatim from the full JSON,
            // so a mismatch means the wrong artifact was derived or pushed for this APK. The check
            // is on the static model's scalars, so its switch is a STATIC_DATA key (D-B14.3).
            boolean mismatch = false;
            if (expectedPackage != null && !expectedPackage.equals(packageName)) {
                Logger.wprintln("StaticArtifactLoader: package mismatch — expected '"
                        + expectedPackage + "' but artifact has '" + packageName + "'");
                mismatch = true;
            }
            if (expectedMainActivity != null && !expectedMainActivity.equals(mainActivity)) {
                Logger.wprintln("StaticArtifactLoader: mainActivity mismatch — expected '"
                        + expectedMainActivity + "' but artifact has '" + mainActivity + "'");
                mismatch = true;
            }
            if (mismatch && RunContext.current().spec().staticData().strictPackageMatch()) {
                Logger.wprintln("StaticArtifactLoader: strict package match enabled — rejecting "
                        + path);
                reject(sink, "package-mismatch");
                return null;
            }

            // The stats block is echoed, never recomputed: droppedFlaggedNoId and the handler-join
            // counters are facts about a derivation this jar does not perform, so the only honest
            // source for them is the generator that did. stats.orphanDialogs is not echoed into
            // MOP_DATA; it stays in the artifact.
            JSONObject stats = root.optJSONObject("stats");
            if (stats == null) {
                stats = new JSONObject();
            }
            MopData mopData = new MopData(widgetMop, selectedActivities,
                    MopData.recomputeMopOptionsMenus(
                            parseOptionsMenus(root.optJSONArray("optionsMenus")),
                            staticModel, selectedActivities),
                    reachingMop, pairs.targets, activityDist, handlers,
                    stats.optInt("droppedFlaggedNoId", 0));

            // The provenance pair: which wire contract was read, and the digest of the
            // static-analysis document this artifact was derived from. The digest is what lets a
            // trace name its exact input — the generator chains it from the source bytes, so a run
            // and the analysis it was steered by are joinable after the fact.
            JSONObject source = root.optJSONObject("source");
            String sourceDigest = source == null ? null : optStringOrNull(source, "digest");
            sink.mopData("loaded", null, formatVersion, sourceDigest, packageName,
                    stats.optInt("windows", 0), stats.optInt("widgetsTotal", 0),
                    stats.optInt("flagged", 0), mopData.getDroppedFlaggedNoId(),
                    stats.optInt("wtgEdges", 0), stats.optInt("handlersUnmatched", 0),
                    stats.optInt("syntheticLambda", 0), stats.optInt("recovered", 0),
                    selectedActivities.size(),
                    countOnlyIn(augmentedActivities, widgetDerivedActivities),
                    receivers.size() + services.size() + activities.size() + providers.size(),
                    pairs.targets, handlers.size(), pairs.dropped);
            return new Loaded(staticModel, mopData);
        } catch (JSONException e) {
            Logger.wprintln("StaticArtifactLoader: malformed artifact structure at " + path + ": "
                    + e.getMessage());
            reject(sink, "parse-error");
            return null;
        }
    }

    /**
     * Decode the wire {@code widgets} map ({@code baseActivity → shortId → widget}) into the two
     * lookup structures: the MOP flags and pairs of each widget into {@code mop}, its static
     * metadata into {@code meta}. The wire key <em>is</em> the short resource id: base-activity
     * scoping, the collision policy and the empty-id drop all ran host-side. Every wire widget gets
     * an entry in both maps, so either provider answers for it by its id key.
     */
    private static void parseWidgets(JSONObject widgetsObj, PairReader pairs,
            Map<String, Map<String, MopData.Widget>> mop,
            Map<String, Map<String, WidgetMeta>> meta) throws JSONException {
        if (widgetsObj == null) {
            return;
        }
        for (Iterator<String> ai = widgetsObj.keys(); ai.hasNext(); ) {
            String activity = ai.next();
            JSONObject byId = widgetsObj.optJSONObject(activity);
            if (byId == null) {
                continue;
            }
            Map<String, MopData.Widget> mopById = new LinkedHashMap<>();
            Map<String, WidgetMeta> metaById = new LinkedHashMap<>();
            for (Iterator<String> wi = byId.keys(); wi.hasNext(); ) {
                String shortId = wi.next();
                JSONObject wo = byId.optJSONObject(shortId);
                if (wo != null) {
                    MopData.Widget w = new MopData.Widget();
                    decodeEntry(wo, w, pairs);
                    w.clickAggregate = MopData.clickAggregate(w.distByEventType);
                    mopById.put(shortId, w);
                    metaById.put(shortId, parseWidgetMeta(shortId, wo));
                }
            }
            mop.put(activity, mopById);
            meta.put(activity, metaById);
        }
    }

    /** The consumed metadata fields of one wire widget; absent ⇒ null, absent entries ⇒ empty. */
    private static WidgetMeta parseWidgetMeta(String shortId, JSONObject wo) {
        JSONArray entriesArr = wo.optJSONArray("entries");
        List<String> entries = new ArrayList<>();
        if (entriesArr != null) {
            for (int i = 0; i < entriesArr.length(); i++) {
                entries.add(entriesArr.optString(i, ""));
            }
        }
        return new WidgetMeta(shortId, optStringOrNull(wo, "hint"),
                optStringOrNull(wo, "inputType"), entries, optStringOrNull(wo, "prompt"),
                optStringOrNull(wo, "spinnerMode"), optStringOrNull(wo, "contentDescription"),
                optStringOrNull(wo, "tooltipText"));
    }

    /**
     * Decode the per-event maps every format-2 entry carries — a widget of {@code widgets} and a
     * class of {@code handlers} alike: the {@code mop} map of normalized eventType →
     * {@code none|direct|transitive|both}, and the {@code dist} map of normalized eventType →
     * {@code [[i, d], …]}.
     *
     * <p>The four tokens decode positionally into the two bits the query side reads
     * ({@code none}=00, {@code direct}=10, {@code transitive}=01, {@code both}=11). An entry is
     * created for <em>every</em> wire key, {@code none} included: key presence is what makes
     * {@code isDirectMop}/{@code isTransitiveMop} answer per-event instead of falling back to the
     * aggregate (INV-MOP-14), so omitting a {@code none} would change the answer. The aggregates
     * are the OR across the map, which is INV-MOP-17 by construction.
     *
     * <p>The pairs keep wire order: an event without a {@code dist} entry has no pair under its own
     * key. Malformed pairs are dropped and counted by {@code pairs}.
     */
    private static void decodeEntry(JSONObject wire, MopData.MopEntry into, PairReader pairs)
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
                String key = MopData.normalizeEventType(eventType);
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
                    String key = MopData.normalizeEventType(eventType);
                    int[][] prev = into.distByEventType.get(key);
                    into.distByEventType.put(key,
                            prev == null ? read : MopData.mergeByMinimum(prev, read));
                }
            }
        }
    }

    /**
     * Decode the format-2 {@code handlers} table (binary class name → record). Every listed class
     * is kept, including one that reaches nothing: the table's membership is itself a statement
     * the stamp-first lookup reads (design D17).
     */
    private static Map<String, MopData.Handler> parseHandlers(JSONObject handlersObj,
            PairReader pairs) throws JSONException {
        Map<String, MopData.Handler> handlers = new HashMap<>();
        for (Iterator<String> it = handlersObj.keys(); it.hasNext(); ) {
            String className = it.next();
            JSONObject ho = handlersObj.optJSONObject(className);
            if (ho != null) {
                MopData.Handler h = new MopData.Handler(className);
                decodeEntry(ho, h, pairs);
                handlers.put(className, h);
            }
        }
        return handlers;
    }

    /** Decode the format-2 {@code activityDist} map (base activity → pairs), absent ⇒ empty. */
    private static Map<String, int[][]> parseActivityDist(JSONObject activityDistObj,
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
                return MopData.NO_PAIRS;
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
            return kept.isEmpty() ? MopData.NO_PAIRS : kept.toArray(new int[0][]);
        }
    }

    /**
     * Decode the wire {@code wtg} map ({@code sourceBaseActivity → [{widget, target}]}). Both
     * ends are already base activities and exact duplicates are already removed (INV-WTG-04,
     * INV-DRV-03), so this is a transcription — no keying, no dedup, no window lookup.
     */
    private static Map<String, List<WtgTransition>> parseWtg(JSONObject wtg) {
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
    private static Map<String, Boolean> parseOptionsMenus(JSONArray arr) {
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
     * Decode the wire {@code components} block. Each entry carries only the trigger surface the
     * inventory records a production reader for; the component type comes from the parent array
     * key (D19). The entry's MOP fact, {@code reachesMop}, goes to {@link MopData} by class name;
     * the trigger surface to the static model. {@code hasTargetMethods} has no reader and is not
     * decoded.
     */
    private static void parseComponents(JSONObject components,
            List<ComponentInfo.ReceiverInfo> receivers, List<ComponentInfo.ServiceInfo> services,
            List<ComponentInfo.ActivityInfo> activities, List<ComponentInfo.ProviderInfo> providers,
            Set<String> reachingMop) throws JSONException {
        if (components == null) {
            return;
        }
        JSONArray recvArr = components.optJSONArray("receivers");
        if (recvArr != null) {
            for (int i = 0; i < recvArr.length(); i++) {
                JSONObject co = recvArr.getJSONObject(i);
                receivers.add(new ComponentInfo.ReceiverInfo(optStringOrNull(co, "className"),
                        co.optBoolean("isMain", false), parseIntentFilters(co),
                        optStringOrNull(co, "permission")));
                recordMopFacts(co, reachingMop);
            }
        }
        JSONArray svcArr = components.optJSONArray("services");
        if (svcArr != null) {
            for (int i = 0; i < svcArr.length(); i++) {
                JSONObject co = svcArr.getJSONObject(i);
                services.add(new ComponentInfo.ServiceInfo(optStringOrNull(co, "className"),
                        co.optBoolean("isMain", false), parseIntentFilters(co),
                        optStringOrNull(co, "permission")));
                recordMopFacts(co, reachingMop);
            }
        }
        JSONArray actArr = components.optJSONArray("activities");
        if (actArr != null) {
            for (int i = 0; i < actArr.length(); i++) {
                JSONObject co = actArr.getJSONObject(i);
                activities.add(new ComponentInfo.ActivityInfo(optStringOrNull(co, "className"),
                        co.optBoolean("isMain", false),
                        new ArrayList<ComponentInfo.IntentFilter>(),
                        optStringOrNull(co, "permission"), optStringOrNull(co, "deepLinkUri")));
                recordMopFacts(co, reachingMop);
            }
        }
        JSONArray provArr = components.optJSONArray("providers");
        if (provArr != null) {
            for (int i = 0; i < provArr.length(); i++) {
                JSONObject co = provArr.getJSONObject(i);
                providers.add(new ComponentInfo.ProviderInfo(optStringOrNull(co, "className"),
                        co.optBoolean("isMain", false),
                        new ArrayList<ComponentInfo.IntentFilter>(),
                        optStringOrNull(co, "authorities"), optStringOrNull(co, "permission")));
                recordMopFacts(co, reachingMop);
            }
        }
    }

    /** The entry's {@code reachesMop}, recorded by class name. */
    private static void recordMopFacts(JSONObject co, Set<String> reachingMop) {
        String className = optStringOrNull(co, "className");
        if (className == null) {
            return;
        }
        if (co.optBoolean("reachesMop", false)) {
            reachingMop.add(className);
        }
    }

    /**
     * Wire intent filters: the actions, the categories and the optional reduced {@code data}
     * member (design D-B13, D-B14.11), which is absent when the filter has no data.
     */
    private static List<ComponentInfo.IntentFilter> parseIntentFilters(JSONObject co)
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
                    stringList(fo.optJSONArray("categories")),
                    parseFilterData(fo.optJSONObject("data"))));
        }
        return filters;
    }

    /**
     * The reduced {@code data} member of a filter: its five lists, each empty when the wire omits
     * it; null when the member is absent. {@code ports} and {@code pathPatterns} are not projected
     * by the derive and are not read.
     */
    private static ComponentInfo.IntentFilter.Data parseFilterData(JSONObject data) {
        if (data == null) {
            return null;
        }
        return new ComponentInfo.IntentFilter.Data(
                stringList(data.optJSONArray("schemes")),
                stringList(data.optJSONArray("hosts")),
                stringList(data.optJSONArray("paths")),
                stringList(data.optJSONArray("pathPrefixes")),
                stringList(data.optJSONArray("mimeTypes")));
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
     * How many members of {@code superset} are absent from {@code base} — the number the load
     * record reports as {@code mopActsAugmented}: what the augmented source <em>would</em>
     * contribute, which does not vary with the run's source flag. The applied augmentation stays
     * recoverable as flag × availability — {@code RUN_START} publishes the flag in its feature
     * list.
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

    private static String optStringOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    /**
     * Reads the whole file into a single, pre-sized {@code byte[]} and decodes it once as UTF-8.
     * A file larger than {@code Integer.MAX_VALUE} cannot be array-backed and is rejected; no
     * artifact approaches that. Whole-file read is mandatory: the Android-bundled org.json only
     * offers {@code JSONTokener(String)}.
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

    /**
     * Records a load that produced no data, with the reason it produced none. A rejection has no
     * census to report — nothing was parsed — so every count is zero and the provenance pair is
     * absent, including on version-mismatch, where a formatVersion was read but is by definition
     * not one this jar can vouch for. It is a record rather than a line because the analysis side
     * excludes and annotates runs by it (INV-MOP-21/26).
     */
    private static void reject(EventSink sink, String reason) {
        sink.mopData("rejected", reason, 0, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0);
    }
}
