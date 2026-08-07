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
                    Set<String> activitiesWithMopOptionsMenu) {
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
                new HashSet<String>());
    }

    // -------------------------------------------------------------------------
    // Compact MOP artifact (formatVersion 1)
    // -------------------------------------------------------------------------

    /** The only compact-artifact wire version this jar reads (INV-MOP-34). */
    private static final int SUPPORTED_FORMAT_VERSION = 1;

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
     * (design D8).
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

            Map<String, Map<String, Widget>> widgetData =
                    parseCompactWidgets(root.optJSONObject("widgets"));
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
                            wtgTransitions, selectedActivities));

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
                    receivers.size() + services.size() + activities.size() + providers.size());
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
    private static Map<String, Map<String, Widget>> parseCompactWidgets(JSONObject widgetsObj)
            throws JSONException {
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
                    widgets.put(shortId, parseCompactWidget(shortId, wo));
                }
            }
            widgetData.put(activity, widgets);
        }
        return widgetData;
    }

    /**
     * Decode one wire widget: the consumed metadata fields (absent ⇒ null/empty) and the
     * {@code mop} map of normalized eventType → {@code none|direct|transitive|both}.
     *
     * <p>The four tokens decode positionally into the two bits the query side reads
     * ({@code none}=00, {@code direct}=10, {@code transitive}=01, {@code both}=11), so the map
     * is lossless with respect to the two per-eventType maps the full-JSON derivation built. An
     * entry is created for <em>every</em> wire key, {@code none} included: key presence is what
     * makes {@code isDirectMop}/{@code isTransitiveMop} answer per-event instead of falling back
     * to the aggregate (INV-MOP-14), so omitting a {@code none} would change the answer. The
     * aggregates are the OR across the map, which is INV-MOP-17 by construction.
     */
    private static Widget parseCompactWidget(String shortId, JSONObject wo) throws JSONException {
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
        JSONObject mop = wo.optJSONObject("mop");
        if (mop == null) {
            return w;
        }
        for (Iterator<String> it = mop.keys(); it.hasNext(); ) {
            String eventType = it.next();
            String encoded = mop.optString(eventType, "none");
            boolean direct = "direct".equals(encoded) || "both".equals(encoded);
            boolean transitive = "transitive".equals(encoded) || "both".equals(encoded);
            // Normalize on ingest rather than trust the generator to have done it: the query side
            // normalizes (INV-MOP-08), so a key that arrived unnormalized would be present in the
            // map and unreachable through every accessor. orInto also merges two wire keys that
            // collapse onto the same normalized event.
            String key = normalizeEventType(eventType);
            orInto(w.directMopByEventType, key, direct);
            orInto(w.transitiveMopByEventType, key, transitive);
            w.directMop |= direct;
            w.transitiveMop |= transitive;
        }
        return w;
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
        sink.mopData("rejected", reason, 0, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // -------------------------------------------------------------------------
    // POJOs (all nested under MopData — D1/D2)
    // -------------------------------------------------------------------------

    /**
     * One addressable widget of the wire's {@code widgets} map: the metadata the prompt builder
     * and the typed-input path consume, plus the MOP flags scoring reads. The fields are exactly
     * what the artifact carries — the producer's raw {@code id}, {@code type}, {@code text} and
     * {@code listeners[]} are inputs to a derivation that now happens host-side, so they never
     * reach the device.
     */
    public static class Widget {
        public String idName;
        public String hint;
        public String inputType;
        public final List<String> entries = new ArrayList<>();
        public String prompt;
        public String spinnerMode;
        public String contentDescription;
        public String tooltipText;
        // Decoded from the wire's per-eventType `mop` map — INV-MOP-17.
        public boolean directMop;
        public boolean transitiveMop;
        public final Map<String, Boolean> directMopByEventType = new HashMap<>();
        public final Map<String, Boolean> transitiveMopByEventType = new HashMap<>();

        /** Direct-MOP flag for the given event type, falling back to the aggregate (match-any). */
        public boolean isDirectMop(String eventType) {
            String key = normalizeEventType(eventType);
            if (key != null && directMopByEventType.containsKey(key)) {
                return Boolean.TRUE.equals(directMopByEventType.get(key));
            }
            return directMop;
        }

        /** Transitive-MOP flag for the given event type, falling back to the aggregate. */
        public boolean isTransitiveMop(String eventType) {
            String key = normalizeEventType(eventType);
            if (key != null && transitiveMopByEventType.containsKey(key)) {
                return Boolean.TRUE.equals(transitiveMopByEventType.get(key));
            }
            return transitiveMop;
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
