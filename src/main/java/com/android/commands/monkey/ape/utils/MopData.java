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
 * Typed model of the static-analysis JSON produced by rv-android, plus the
 * activity → shortWidgetId → reachability index used for MOP-guided scoring.
 *
 * <h3>Naming boundary: {@code Target} on the wire, {@code MOP} inside aperv</h3>
 *
 * The JSON wire format (rv-android gh60 producer) speaks the neutral word
 * <em>Target</em> ({@code reachesTarget}, {@code directlyReachesTarget},
 * {@code targetMethods}) because its static analysis was generalized to any
 * target method set. aperv is exclusively a JavaMOP consumer — the only targets
 * it cares about are MOP monitored operations — so its Java model speaks
 * <em>MOP</em> ({@code MopData}, {@code directMop}/{@code transitiveMop},
 * {@code activityHasMop}, {@code Config.mopWeight*}). The mapping is 1:1:
 *
 * <pre>
 *   JSON wire key            →  aperv Java concept
 *   ----------------------------------------------
 *   directlyReachesTarget    →  directMop
 *   reachesTarget            →  transitiveMop
 *   targetMethods            →  ComponentInfo.targetMethods
 * </pre>
 *
 * The one rule: {@code *Target} appears <em>only</em> where JSON is read (method
 * and component parsing below). Everywhere else inside aperv the concept is
 * {@code *Mop}. See design decision D7.
 *
 * <h3>Parser</h3>
 *
 * The file is parsed once into an {@code org.json.JSONObject} and navigated in
 * memory (design D21 — {@code android.util.JsonReader} is excluded from the
 * surefire test classpath, so a streaming parser could not be unit-tested).
 * Cross-reference: {@code windows[].widgets[].listeners[].handler} matches
 * {@code reachability[].methods[].signature}; the per-widget MOP flags are
 * derived from that cross-reference (gh60 does not emit them). Widget MOP flags
 * are computed per {@code eventType} and OR-aggregated (INV-MOP-17).
 */
public class MopData {

    private static final String TAG = "MopData";

    private final String packageName;
    private final String mainActivity;
    private final boolean complete;

    private final List<ReachabilityClass> reachability;
    private final List<Window> windows;
    private final Map<Integer, Window> windowsById;

    /** Map: base activity class name → (shortResourceId → Widget). */
    private final Map<String, Map<String, Widget>> widgetData;
    /** Base activity class names that have at least one MOP-reachable widget. */
    private final Set<String> mopActivities;
    /** Map: source window name → click-only WTG transitions (convenience view). */
    private final Map<String, List<WtgTransition>> wtgTransitions;
    private final List<Transition> transitions;

    private final List<ComponentInfo.ReceiverInfo> receivers;
    private final List<ComponentInfo.ServiceInfo> services;
    private final List<ComponentInfo.ActivityInfo> activities;
    private final List<ComponentInfo.ProviderInfo> providers;

    /** Activities whose OPTIONSMENU is a MOP gateway (T1.2, D13). */
    private final Set<String> activitiesWithMopOptionsMenu;

    /**
     * Count of MOP-flagged widgets dropped during parsing for having no resource id
     * (INV-MOP-20). Observability only — set once after parsing, logged on load.
     */
    private int droppedFlaggedNoId;

    private MopData(String packageName, String mainActivity, boolean complete,
                    List<ReachabilityClass> reachability,
                    List<Window> windows, Map<Integer, Window> windowsById,
                    Map<String, Map<String, Widget>> widgetData, Set<String> mopActivities,
                    Map<String, List<WtgTransition>> wtgTransitions, List<Transition> transitions,
                    List<ComponentInfo.ReceiverInfo> receivers,
                    List<ComponentInfo.ServiceInfo> services,
                    List<ComponentInfo.ActivityInfo> activities,
                    List<ComponentInfo.ProviderInfo> providers,
                    Set<String> activitiesWithMopOptionsMenu) {
        this.packageName = packageName;
        this.mainActivity = mainActivity;
        this.complete = complete;
        this.reachability = reachability;
        this.windows = windows;
        this.windowsById = windowsById;
        this.widgetData = widgetData;
        this.mopActivities = mopActivities;
        this.wtgTransitions = wtgTransitions;
        this.transitions = transitions;
        this.receivers = receivers;
        this.services = services;
        this.activities = activities;
        this.providers = providers;
        this.activitiesWithMopOptionsMenu = activitiesWithMopOptionsMenu;
    }

    // -------------------------------------------------------------------------
    // Test factory (package-private) — builds a MopData from pre-built structures.
    // -------------------------------------------------------------------------

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
        return new MopData(null, null, true,
                new ArrayList<ReachabilityClass>(),
                new ArrayList<Window>(), new HashMap<Integer, Window>(),
                widgetData != null ? widgetData : new HashMap<String, Map<String, Widget>>(),
                mopActivities != null ? mopActivities : new HashSet<String>(),
                wtgTransitions != null ? wtgTransitions : new HashMap<String, List<WtgTransition>>(),
                new ArrayList<Transition>(),
                receivers != null ? receivers : new ArrayList<ComponentInfo.ReceiverInfo>(),
                services != null ? services : new ArrayList<ComponentInfo.ServiceInfo>(),
                activities != null ? activities : new ArrayList<ComponentInfo.ActivityInfo>(),
                providers != null ? providers : new ArrayList<ComponentInfo.ProviderInfo>(),
                new HashSet<String>());
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /**
     * Empirical, conservative footprint multiplier for the whole-file org.json parse
     * (≈1× file bytes + ~2× String chars + ~3× DOM). Not a derivation — the single measured
     * datapoint (redreader 50.6 MB OOM at ~201 MB heap) was dominated by the StringBuilder-doubling
     * spike this change removes. A code constant, not a flag (P1: no gratuitous tuning knobs);
     * recalibrate from the status-line size/budget telemetry if a loadable file is ever rejected.
     */
    private static final int PARSE_FOOTPRINT_FACTOR = 6;

    /**
     * Whole-file parse budget: the process's maximum heap. Static (read once) so the reject
     * decision is a pure function of file size for a given device heap config, rather than flipping
     * pass/reject across runs with live-heap/GC state at the margin.
     */
    private static final long PARSE_BUDGET_BYTES = Runtime.getRuntime().maxMemory();

    /**
     * Load MOP data from a static-analysis JSON file.
     *
     * @param path                  device-local path, or null
     * @param expectedPackage       package to compare against (T1.7), or null to skip
     * @param expectedMainActivity  main activity to compare against (T1.7), or null to skip
     * @param sink                  where the load census, or the reason there is none, is recorded
     * @return populated MopData, or null on: null path / missing file / malformed JSON /
     *         sentinel absent or false / strict-mode package mismatch
     */
    public static MopData load(String path, String expectedPackage, String expectedMainActivity,
            EventSink sink) {
        return load(path, expectedPackage, expectedMainActivity, PARSE_BUDGET_BYTES, sink);
    }

    /**
     * Package-visible test seam: {@code load} with an explicit parse budget so the JVM suite can
     * drive the too-large guard deterministically. The public entry passes {@link #PARSE_BUDGET_BYTES}.
     */
    static MopData load(String path, String expectedPackage, String expectedMainActivity,
            long budgetBytes, EventSink sink) {
        if (path == null) {
            return null;
        }
        // Single OUTER catch around the ENTIRE load body — the budget guard, read, sentinel check,
        // JSONObject construction, all typed parsing, and MopData construction. Any OutOfMemoryError
        // from any phase is converted into the same reject-and-null contract every other failure
        // uses, so it never escapes into the StatefulAgent constructor (INV-MOP-26). Scoped to
        // OutOfMemoryError only — IOException/JSONException stay handled by the inner catches below.
        try {
            // Pre-read budget guard: reject files whose parse footprint (~FACTOR× the file size for
            // the org.json DOM) cannot fit the process heap, before allocating anything. Division
            // avoids the overflow of `fileSize * FACTOR`. File.length() is 0 for unreadable paths →
            // guard passes and the existing IOException path handles it.
            long fileSize = new File(path).length();
            if (fileSize > budgetBytes / PARSE_FOOTPRINT_FACTOR) {
                Logger.iformat("MopData: %s is %d bytes, over the %d-byte parse budget",
                        path, fileSize, budgetBytes);
                reject(sink, "too-large");
                return null;
            }
            JSONObject root;
            try {
                // The Android-bundled org.json only offers JSONTokener(String), so read fully first.
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

            // Sentinel (INV-MOP-09) — position-independent single key read (D5/D21).
            if (!root.optBoolean("complete", false)) {
                Logger.wprintln("MopData: '\"complete\": true' sentinel absent or false at " + path
                        + " — treating as no MOP data (truncated analysis)");
                reject(sink, "incomplete");
                return null;
            }

            try {
                String packageName = optStringOrNull(root, "package");
            String mainActivity = optStringOrNull(root, "mainActivity");

            // Pass 1: reachability[] → typed list + bySignature index + FIX-2 lambda index.
            Map<String, boolean[]> bySignature = new HashMap<>();
            Map<String, boolean[]> lambdaReachByClass = new HashMap<>();
            List<ReachabilityClass> reachability =
                    parseReachability(root.optJSONArray("reachability"), bySignature, lambdaReachByClass);

            // Pass 2: windows[] → typed windows + widgetData + derived MOP flags (with FIX-2 recovery).
            List<Window> windows = new ArrayList<>();
            Map<Integer, Window> windowsById = new HashMap<>();
            Map<String, Map<String, Widget>> widgetData = new HashMap<>();
            Set<String> mopActivities = new HashSet<>();
            int[] droppedFlaggedNoId = new int[1];
            parseWindows(root.optJSONArray("windows"), bySignature, lambdaReachByClass,
                    windows, windowsById, widgetData, mopActivities, droppedFlaggedNoId);

            // Pass 3: transitions[] → typed transitions + click-only WTG view.
            Map<String, List<WtgTransition>> wtgTransitions = new HashMap<>();
            List<Transition> transitions =
                    parseTransitions(root.optJSONArray("transitions"), windowsById, wtgTransitions);

            // Pass 3.5: re-key DIALOG windows to their host activity (INV-MOP-25, D5/D6).
            // Runs after transitions (needs the activity→dialog edges) and before the
            // OPTIONSMENU precompute + status line, which both read mopActivities/widgetData.
            int[] orphanDialogs = new int[1];
            rekeyDialogsToHost(windows, transitions, windowsById, widgetData,
                    mopActivities, orphanDialogs);
            if (orphanDialogs[0] > 0) {
                Logger.iprintln("[APE-RV] MopData: " + orphanDialogs[0]
                        + " orphan DIALOG windows (no incoming transition)");
            }

            // Pass 4: components{} → typed component lists.
            List<ComponentInfo.ReceiverInfo> receivers = new ArrayList<>();
            List<ComponentInfo.ServiceInfo> services = new ArrayList<>();
            List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
            List<ComponentInfo.ProviderInfo> providers = new ArrayList<>();
            parseComponents(root.optJSONObject("components"),
                    receivers, services, activities, providers);

            // A′ (INV-MOP-27): widen mopActivities to the 3-source union when the flag is on. Runs
            // before the OPTIONSMENU precompute (which reads mopActivities) so the wider set feeds
            // every downstream consumer. Seam takes the flag as a param (static-final wall).
            int preAugmentActivities = mopActivities.size();
            augmentActivitiesFromSources(mopActivities, activities, reachability,
                    Config.mopActivitySourceComponents);
            int augmentedActivities = mopActivities.size() - preAugmentActivities;

            // Precompute OPTIONSMENU gateway set (T1.2, D13) — needs WTG + mopActivities.
            Set<String> menuGateways = precomputeMopOptionsMenus(
                    windows, wtgTransitions, mopActivities);

            MopData data = new MopData(packageName, mainActivity, true,
                    reachability, windows, windowsById, widgetData, mopActivities,
                    wtgTransitions, transitions, receivers, services, activities, providers,
                    menuGateways);
            data.droppedFlaggedNoId = droppedFlaggedNoId[0];
            if (data.droppedFlaggedNoId > 0) {
                Logger.iprintln("[APE-RV] MopData: dropped " + data.droppedFlaggedNoId
                        + " flagged widgets with no resource id");
            }

            // Sanity check (T1.7).
            boolean mismatch = false;
            if (expectedPackage != null && !expectedPackage.equals(packageName)) {
                Logger.wprintln("MopData: package mismatch — expected '" + expectedPackage
                        + "' but JSON has '" + packageName + "'");
                mismatch = true;
            }
            if (expectedMainActivity != null && !expectedMainActivity.equals(mainActivity)) {
                Logger.wprintln("MopData: mainActivity mismatch — expected '" + expectedMainActivity
                        + "' but JSON has '" + mainActivity + "'");
                mismatch = true;
            }
            if (mismatch && RunContext.current().spec().mop().strictPackageMatch()) {
                Logger.wprintln("MopData: strict package match enabled — rejecting " + path);
                reject(sink, "package-mismatch");
                return null;
            }

            // FIX 3 (INV-MOP-31): diagnostics on the handler→MOP join, so a silent collapse is visible.
            int[] joinDiag = computeHandlerJoinDiagnostics(windows, bySignature, lambdaReachByClass);
            // wtgEdges is the click-only view's summed size — the number the three frontier
            // passes actually gate on. The flat `transitions` list is deliberately not carried:
            // 14 of the decisive campaign's 40 applications report 9-29 transitions with the whole
            // frontier family disabled, so reporting it invites exactly that misreading again.
            // formatVersion 0 and a null sourceDigest are the neutral values, not a claim: this
            // path reads the producer's own document, which carries neither a wire version nor a
            // digest of itself. `components` gets no such treatment — the four typed lists are
            // right here, so reporting 0 would be a falsehood about something this path knows.
            sink.mopData("loaded", null, 0, null,
                    packageName, windows.size(), countWidgets(widgetData), countFlagged(widgetData),
                    data.droppedFlaggedNoId, countWtgEdges(wtgTransitions),
                    joinDiag[0], joinDiag[1], joinDiag[2],
                    mopActivities.size(), augmentedActivities,
                    receivers.size() + services.size() + activities.size() + providers.size());
            return data;
            } catch (JSONException e) {
                Logger.wprintln("MopData: malformed JSON structure at " + path + ": " + e.getMessage());
                reject(sink, "parse-error");
                return null;
            }
        } catch (OutOfMemoryError oom) {
            // The try-scoped locals (root, the parsed maps/lists, data) are already unreachable
            // here, so returning null lets the GC reclaim them. Emit the reject status line so the
            // run is excludable/annotatable by the analysis pipeline (INV-MOP-21/26).
            reject(sink, "oom");
            return null;
        }
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
     * <p>Every parse-time semantic the full-JSON path performs — the listener-handler ×
     * reachability cross-reference, the D8 synthetic-lambda recovery, the {@code mopRank}
     * collision policy, the empty-id drop, the DIALOG re-keying, the base-activity WTG keying
     * and the A′ union — has already run in the generator. This reader decodes precomputed
     * values and derives nothing (INV-MOP-35), which is why it needs neither the call graph nor
     * a parse budget: the artifact is bounded by construction.
     *
     * @param path                  device-local path, or null
     * @param expectedPackage       package to compare against (T1.7), or null to skip
     * @param expectedMainActivity  main activity to compare against (T1.7), or null to skip
     * @param sink                  where the load census, or the reason there is none, is recorded
     * @return populated MopData, or null on: null path / missing file / malformed JSON /
     *         unsupported formatVersion / strict-mode package mismatch
     */
    public static MopData loadCompact(String path, String expectedPackage,
            String expectedMainActivity, EventSink sink) {
        return loadCompact(path, expectedPackage, expectedMainActivity,
                Config.mopActivitySourceComponents, sink);
    }

    /**
     * Package-visible test seam: {@code loadCompact} with the A′ source choice passed in, so the
     * JVM suite can drive both branches of INV-MOP-27 past the {@code static final} wall that
     * {@link Config#mopActivitySourceComponents} sits behind. The public entry reads the flag.
     */
    static MopData loadCompact(String path, String expectedPackage, String expectedMainActivity,
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

            MopData data = new MopData(packageName, mainActivity, true,
                    new ArrayList<ReachabilityClass>(),
                    new ArrayList<Window>(), new HashMap<Integer, Window>(),
                    widgetData, selectedActivities, wtgTransitions,
                    new ArrayList<Transition>(),
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
                    list.add(new WtgTransition(e.optString("widget", ""), "",
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
     * key, as it does in the full JSON (D19).
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
                        false, parseCompactIntentFilters(co),
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
                        false, parseCompactIntentFilters(co),
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
                        false, Collections.<ComponentInfo.IntentFilter>emptyList(),
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
                        false, Collections.<ComponentInfo.IntentFilter>emptyList(),
                        co.optBoolean("reachesMop", false), Collections.<String>emptyList(),
                        optStringOrNull(co, "authorities"),
                        optStringOrNull(co, "permission"), null, null));
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

    // -------------------------------------------------------------------------
    // Pass 1: reachability[]
    // -------------------------------------------------------------------------

    private static List<ReachabilityClass> parseReachability(JSONArray arr,
                                                             Map<String, boolean[]> bySignature,
                                                             Map<String, boolean[]> lambdaReachByClass)
            throws JSONException {
        List<ReachabilityClass> classes = new ArrayList<>();
        if (arr == null) return classes;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject co = arr.getJSONObject(i);
            ReachabilityClass rc = new ReachabilityClass();
            rc.className = optStringOrNull(co, "className");
            rc.componentType = optStringOrNull(co, "componentType");
            rc.isMain = co.optBoolean("isMain", false);
            JSONArray methods = co.optJSONArray("methods");
            if (methods != null) {
                for (int j = 0; j < methods.length(); j++) {
                    JSONObject mo = methods.getJSONObject(j);
                    ReachabilityMethod m = new ReachabilityMethod();
                    m.name = optStringOrNull(mo, "name");
                    m.signature = optStringOrNull(mo, "signature");
                    m.reachable = mo.optBoolean("reachable", false);
                    // Wire keys are *Target; stored as MOP concepts (D7).
                    m.reachesTarget = mo.optBoolean("reachesTarget", false);
                    m.directlyReachesTarget = mo.optBoolean("directlyReachesTarget", false);
                    rc.methods.add(m);
                    if (m.signature != null && (m.reachesTarget || m.directlyReachesTarget)) {
                        // boolean[]{directMop, transitiveMop}
                        bySignature.put(m.signature,
                                new boolean[]{m.directlyReachesTarget, m.reachesTarget});
                    }
                    // FIX 2 (INV-MOP-30): index the enclosing class of any reaching javac-desugared
                    // lambda body (name "lambda$…"), so a widget's D8 synthetic-lambda wrapper handler
                    // (X$$ExternalSyntheticLambdaN) — which the producer call graph marks reachesTarget=
                    // false — can be recovered from X's reaching lambda methods. boolean[]{direct, any}.
                    if (rc.className != null && m.name != null && m.name.startsWith("lambda$")
                            && (m.reachesTarget || m.directlyReachesTarget)) {
                        boolean[] agg = lambdaReachByClass.get(rc.className);
                        if (agg == null) {
                            agg = new boolean[]{false, false};
                            lambdaReachByClass.put(rc.className, agg);
                        }
                        agg[0] |= m.directlyReachesTarget;
                        agg[1] |= m.reachesTarget || m.directlyReachesTarget;
                    }
                }
            }
            classes.add(rc);
        }
        return classes;
    }

    /** Matches a D8 desugared-lambda wrapper class handler and captures the enclosing class (FIX 2). */
    private static final java.util.regex.Pattern SYNTH_LAMBDA =
            java.util.regex.Pattern.compile("^<(.+?)\\$\\$ExternalSyntheticLambda\\d+:");

    /** Enclosing class of a D8 synthetic-lambda handler signature, or null when it is not one. */
    static String syntheticLambdaEnclosingClass(String handler) {
        if (handler == null) {
            return null;
        }
        java.util.regex.Matcher mt = SYNTH_LAMBDA.matcher(handler);
        return mt.find() ? mt.group(1) : null;
    }

    // -------------------------------------------------------------------------
    // Pass 2: windows[] + widgets (flat, no recursion — D3)
    // -------------------------------------------------------------------------

    private static void parseWindows(JSONArray arr, Map<String, boolean[]> bySignature,
                                     Map<String, boolean[]> lambdaReachByClass,
                                     List<Window> windows, Map<Integer, Window> windowsById,
                                     Map<String, Map<String, Widget>> widgetData,
                                     Set<String> mopActivities, int[] droppedFlaggedNoId)
            throws JSONException {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Window w = parseWindow(arr.getJSONObject(i), bySignature, lambdaReachByClass);
            windows.add(w);
            if (w.id >= 0) {
                windowsById.put(w.id, w);
            }
            if (w.name == null) continue;
            String activity = baseActivity(w.name);
            Map<String, Widget> widgets = widgetData.get(activity);
            if (widgets == null) {
                widgets = new LinkedHashMap<>();
                widgetData.put(activity, widgets);
            }
            for (Widget wd : w.widgets) {
                boolean flagged = wd.directMop || wd.transitiveMop;
                // A flagged widget marks the activity even when it has no resource id,
                // so activityHasMop stays correct.
                if (flagged) {
                    mopActivities.add(activity);
                }
                if (wd.idName == null || wd.idName.isEmpty()) {
                    // Empty/absent short id: extractShortId does yield "" for id-less nodes at
                    // runtime, so the "" bucket is reachable — but a single "" key would collapse
                    // every id-less widget onto one entry, overwriting siblings and surfacing an
                    // arbitrary one. Drop rather than store so the loss is explicit, not hidden as
                    // noise (INV-MOP-20).
                    if (flagged) {
                        droppedFlaggedNoId[0]++;
                    }
                    continue;
                }
                // On shortId collision keep the strongest MOP flag (direct > transitive >
                // unflagged); a tie keeps the resident, so this is order-independent and an
                // unflagged sibling never overwrites a flagged widget (INV-MOP-19).
                Widget resident = widgets.get(wd.idName);
                if (resident == null || mopRank(wd) > mopRank(resident)) {
                    widgets.put(wd.idName, wd);
                }
            }
        }
    }

    /** MOP-flag strength used for collision resolution: 2=direct, 1=transitive, 0=unflagged. */
    private static int mopRank(Widget w) {
        if (w.directMop) return 2;
        if (w.transitiveMop) return 1;
        return 0;
    }

    private static Window parseWindow(JSONObject wo, Map<String, boolean[]> bySignature,
                                      Map<String, boolean[]> lambdaReachByClass)
            throws JSONException {
        Window w = new Window();
        w.id = wo.optInt("id", -1);
        w.type = optStringOrNull(wo, "type");
        w.name = optStringOrNull(wo, "name");
        w.isMain = wo.optBoolean("isMain", false);
        JSONArray widgets = wo.optJSONArray("widgets");
        if (widgets != null) {
            for (int i = 0; i < widgets.length(); i++) {
                w.widgets.add(parseWidget(widgets.getJSONObject(i), bySignature, lambdaReachByClass));
            }
        }
        return w;
    }

    private static Widget parseWidget(JSONObject wo, Map<String, boolean[]> bySignature,
                                      Map<String, boolean[]> lambdaReachByClass)
            throws JSONException {
        Widget w = new Widget();
        w.id = wo.optInt("id", -1);
        w.idName = optStringOrNull(wo, "idName");
        w.type = optStringOrNull(wo, "type");
        w.text = optStringOrNull(wo, "text");
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
        JSONArray listeners = wo.optJSONArray("listeners");
        if (listeners != null) {
            for (int i = 0; i < listeners.length(); i++) {
                w.listeners.add(parseListener(listeners.getJSONObject(i)));
            }
        }
        deriveWidgetMopFlags(w, bySignature, lambdaReachByClass);
        return w;
    }

    private static Listener parseListener(JSONObject lo) throws JSONException {
        Listener l = new Listener();
        l.eventType = optStringOrNull(lo, "eventType");
        l.handler = optStringOrNull(lo, "handler");
        // gh60-C3 forward compat — nullable; null on every listener until C3 lands (D8).
        l.handlerReachesTarget = optBooleanOrNull(lo, "handlerReachesTarget");
        l.handlerDirectlyReachesTarget = optBooleanOrNull(lo, "handlerDirectlyReachesTarget");
        return l;
    }

    /**
     * Derive per-eventType and aggregate MOP flags from the widget's listeners
     * (INV-MOP-17, D18). Producer-supplied handlerReachesTarget wins over the
     * local cross-reference when present (INV-MOP-12, D8).
     */
    private static void deriveWidgetMopFlags(Widget w, Map<String, boolean[]> bySignature,
                                             Map<String, boolean[]> lambdaReachByClass) {
        for (Listener l : w.listeners) {
            boolean direct;
            boolean transitive;
            if (l.handlerDirectlyReachesTarget != null || l.handlerReachesTarget != null) {
                direct = Boolean.TRUE.equals(l.handlerDirectlyReachesTarget);
                transitive = Boolean.TRUE.equals(l.handlerReachesTarget) || direct;
            } else {
                boolean[] flags = l.handler != null ? bySignature.get(l.handler) : null;
                if (flags == null && l.handler != null) {
                    // FIX 2 (INV-MOP-30): exact join missed. If the handler is a D8 synthetic-lambda
                    // wrapper (X$$ExternalSyntheticLambdaN), recover the flag from X's reaching
                    // javac lambda methods — the same JSON already carries them (no GATOR re-run).
                    String encl = syntheticLambdaEnclosingClass(l.handler);
                    if (encl != null) {
                        flags = lambdaReachByClass.get(encl);
                    }
                }
                direct = flags != null && flags[0];
                transitive = flags != null && flags[1];
            }
            orInto(w.directMopByEventType, normalizeEventType(l.eventType), direct);
            orInto(w.transitiveMopByEventType, normalizeEventType(l.eventType), transitive);
            w.directMop |= direct;
            w.transitiveMop |= transitive;
        }
    }

    private static void orInto(Map<String, Boolean> map, String key, boolean value) {
        Boolean prev = map.get(key);
        map.put(key, (prev != null && prev) || value);
    }

    /**
     * A′ (INV-MOP-27): widen {@code mopActivities} to the 3-source union when {@code enabled}.
     * Source 1 (widget-derived) is already in {@code mopActivities} on entry. When enabled, adds
     * source 2 — every {@code components.activities[]} with {@code reachesTarget=true} — and source 3
     * — every {@code reachability[]} class with {@code componentType=="activity"} that has ≥1
     * {@code reachesTarget} method. Source 3 is the lambda-call-graph-gap-immune substrate: the
     * component-level flag false-negatives lambda-triggered activities (cryptoapp: all activities
     * {@code components.reachesTarget=false} yet {@code CryptographyActivity} has 13 reaching methods).
     * When {@code enabled==false} the set is left exactly as the widget-derived source (byte-identical).
     * Package-private seam so a test can drive the flag past the {@code static final} wall.
     */
    static void augmentActivitiesFromSources(Set<String> mopActivities,
                                             List<ComponentInfo.ActivityInfo> activities,
                                             List<ReachabilityClass> reachability,
                                             boolean enabled) {
        if (!enabled) {
            return;
        }
        if (activities != null) {
            for (ComponentInfo.ActivityInfo a : activities) {
                if (a != null && a.reachesTarget && a.className != null) {
                    mopActivities.add(baseActivity(a.className));
                }
            }
        }
        if (reachability != null) {
            for (ReachabilityClass rc : reachability) {
                if (rc == null || rc.className == null || !"activity".equals(rc.componentType)) {
                    continue;
                }
                for (ReachabilityMethod m : rc.methods) {
                    if (m.reachesTarget || m.directlyReachesTarget) {
                        mopActivities.add(baseActivity(rc.className));
                        break;
                    }
                }
            }
        }
    }

    /**
     * FIX 3 (INV-MOP-31): pure diagnostic counters over the widget→MOP handler join. Returns
     * {@code [handlersUnmatched, syntheticLambda, recovered]} over the set of DISTINCT listener
     * handler signatures: {@code handlersUnmatched} = handlers with no exact {@code bySignature}
     * match; {@code syntheticLambda} = of those, D8 {@code $$ExternalSyntheticLambda} wrappers;
     * {@code recovered} = of those, ones whose enclosing class has a reaching lambda (FIX 2 would
     * flag). Never alters any parsed state; {@code recovered ≤ syntheticLambda ≤ handlersUnmatched}.
     */
    private static int[] computeHandlerJoinDiagnostics(List<Window> windows,
                                                       Map<String, boolean[]> bySignature,
                                                       Map<String, boolean[]> lambdaReachByClass) {
        Set<String> handlers = new HashSet<>();
        for (Window w : windows) {
            for (Widget wd : w.widgets) {
                for (Listener l : wd.listeners) {
                    if (l.handler != null) {
                        handlers.add(l.handler);
                    }
                }
            }
        }
        int unmatched = 0;
        int synthetic = 0;
        int recovered = 0;
        for (String h : handlers) {
            if (bySignature.containsKey(h)) {
                continue;
            }
            unmatched++;
            String encl = syntheticLambdaEnclosingClass(h);
            if (encl != null) {
                synthetic++;
                boolean[] agg = lambdaReachByClass.get(encl);
                if (agg != null && agg[1]) {
                    recovered++;
                }
            }
        }
        return new int[]{unmatched, synthetic, recovered};
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
    // Pass 3: transitions[]
    // -------------------------------------------------------------------------

    private static List<Transition> parseTransitions(JSONArray arr,
                                                     Map<Integer, Window> windowsById,
                                                     Map<String, List<WtgTransition>> wtgTransitions)
            throws JSONException {
        List<Transition> transitions = new ArrayList<>();
        if (arr == null) return transitions;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject to = arr.getJSONObject(i);
            Transition t = new Transition();
            t.sourceId = to.optInt("sourceId", -1);
            t.targetId = to.optInt("targetId", -1);
            JSONArray events = to.optJSONArray("events");
            if (events != null) {
                for (int j = 0; j < events.length(); j++) {
                    JSONObject eo = events.getJSONObject(j);
                    TransitionEvent e = new TransitionEvent();
                    e.type = optStringOrNull(eo, "type");
                    e.handler = optStringOrNull(eo, "handler");
                    e.widgetId = eo.optInt("widgetId", -1);
                    e.widgetClass = optStringOrNull(eo, "widgetClass");
                    e.widgetName = optStringOrNull(eo, "widgetName");
                    t.events.add(e);
                }
            }
            transitions.add(t);

            // Click-only convenience view keyed by base SOURCE activity, with the base
            // TARGET activity stored on each WtgTransition (INV-WTG-04). Both consumers —
            // the runtime WTG pass and scoreWtg — query/test by base activity, so menu- and
            // fragment-sourced edges (e.g. MainActivity#OptionsMenu) must collapse to the
            // base; otherwise they are stored under a key no consumer ever queries.
            Window source = windowsById.get(t.sourceId);
            Window target = windowsById.get(t.targetId);
            if (source == null || target == null || source.name == null || target.name == null) {
                continue;
            }
            String sourceActivity = baseActivity(source.name);
            String targetActivity = baseActivity(target.name);
            for (TransitionEvent e : t.events) {
                if ("click".equals(e.type)) {
                    List<WtgTransition> list = wtgTransitions.get(sourceActivity);
                    if (list == null) {
                        list = new ArrayList<>();
                        wtgTransitions.put(sourceActivity, list);
                    }
                    list.add(new WtgTransition(
                            e.widgetName != null ? e.widgetName : "",
                            e.widgetClass != null ? e.widgetClass : "",
                            targetActivity));
                }
            }
        }
        return transitions;
    }

    // -------------------------------------------------------------------------
    // Pass 4: components{} — type derived from the parent dict key (D19)
    // -------------------------------------------------------------------------

    private static void parseComponents(JSONObject components,
                                        List<ComponentInfo.ReceiverInfo> receivers,
                                        List<ComponentInfo.ServiceInfo> services,
                                        List<ComponentInfo.ActivityInfo> activities,
                                        List<ComponentInfo.ProviderInfo> providers)
            throws JSONException {
        if (components == null) return;
        JSONArray recvArr = components.optJSONArray("receivers");
        if (recvArr != null) {
            for (int i = 0; i < recvArr.length(); i++) {
                JSONObject co = recvArr.getJSONObject(i);
                receivers.add(new ComponentInfo.ReceiverInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        co.optBoolean("exported", false), parseIntentFilters(co),
                        co.optBoolean("reachesTarget", false), parseTargetMethods(co),
                        optStringOrNull(co, "permission")));
            }
        }
        JSONArray svcArr = components.optJSONArray("services");
        if (svcArr != null) {
            for (int i = 0; i < svcArr.length(); i++) {
                JSONObject co = svcArr.getJSONObject(i);
                services.add(new ComponentInfo.ServiceInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        co.optBoolean("exported", false), parseIntentFilters(co),
                        co.optBoolean("reachesTarget", false), parseTargetMethods(co),
                        optStringOrNull(co, "permission")));
            }
        }
        JSONArray actArr = components.optJSONArray("activities");
        if (actArr != null) {
            for (int i = 0; i < actArr.length(); i++) {
                JSONObject co = actArr.getJSONObject(i);
                activities.add(new ComponentInfo.ActivityInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        co.optBoolean("exported", false), parseIntentFilters(co),
                        co.optBoolean("reachesTarget", false), parseTargetMethods(co),
                        optStringOrNull(co, "permission")));
            }
        }
        JSONArray provArr = components.optJSONArray("providers");
        if (provArr != null) {
            for (int i = 0; i < provArr.length(); i++) {
                JSONObject co = provArr.getJSONObject(i);
                providers.add(new ComponentInfo.ProviderInfo(
                        optStringOrNull(co, "className"), co.optBoolean("isMain", false),
                        co.optBoolean("exported", false), parseIntentFilters(co),
                        co.optBoolean("reachesTarget", false), parseTargetMethods(co),
                        optStringOrNull(co, "authorities"),
                        optStringOrNull(co, "permission"),
                        optStringOrNull(co, "readPermission"),
                        optStringOrNull(co, "writePermission")));
            }
        }
    }

    private static List<ComponentInfo.IntentFilter> parseIntentFilters(JSONObject co)
            throws JSONException {
        List<ComponentInfo.IntentFilter> filters = new ArrayList<>();
        JSONArray arr = co.optJSONArray("intentFilters");
        if (arr == null) return filters;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject fo = arr.getJSONObject(i);
            filters.add(new ComponentInfo.IntentFilter(
                    stringList(fo.optJSONArray("actions")),
                    stringList(fo.optJSONArray("categories")),
                    parseDataSpec(fo.optJSONObject("data"))));
        }
        return filters;
    }

    /** gh60 D15: the {@code <data>} block of an intent filter (deep-link / MIME constraints). */
    private static ComponentInfo.DataSpec parseDataSpec(JSONObject data) {
        if (data == null) return ComponentInfo.DataSpec.EMPTY;
        return new ComponentInfo.DataSpec(
                stringList(data.optJSONArray("schemes")),
                stringList(data.optJSONArray("hosts")),
                stringList(data.optJSONArray("ports")),
                stringList(data.optJSONArray("paths")),
                stringList(data.optJSONArray("pathPrefixes")),
                stringList(data.optJSONArray("pathPatterns")),
                stringList(data.optJSONArray("mimeTypes")));
    }

    private static List<String> parseTargetMethods(JSONObject co) {
        return stringList(co.optJSONArray("targetMethods"));
    }

    // -------------------------------------------------------------------------
    // Precompute OPTIONSMENU gateway set (T1.2, D13)
    // -------------------------------------------------------------------------

    /**
     * Package-visible for the same reason {@link #augmentActivitiesFromSources} is: the equivalence
     * gate has to drive this path with both MOP-activity sets, and the load-time caller can only
     * pass the one {@link Config#mopActivitySourceComponents} names. Visibility only — no caller
     * outside the gate exists, and both this method and the gate are deleted at the cutover.
     */
    static Set<String> precomputeMopOptionsMenus(
            List<Window> windows, Map<String, List<WtgTransition>> wtgTransitions,
            Set<String> mopActivities) {
        Set<String> result = new HashSet<>();
        for (Window w : windows) {
            if (w.name == null || !"OPTIONSMENU".equals(w.type)) continue;
            String activity = baseActivity(w.name);   // shared helper (removes the ad hoc suffix strip)
            boolean qualifies = false;
            // Condition 1: a widget in the menu itself reaches MOP.
            for (Widget wd : w.widgets) {
                if (wd.directMop || wd.transitiveMop) {
                    qualifies = true;
                    break;
                }
            }
            // Condition 2 (gateway): a click edge from this base activity navigates (WTG)
            // to a MOP activity. wtgTransitions is now keyed by base activity (INV-WTG-04),
            // so query the base `activity`, not the "#OptionsMenu" window name, which is no
            // longer a key (INV-WTG-05, D3a). This widens the test from "a menu item reaches
            // MOP" to "any base-activity click edge reaches MOP" — a deliberate over-
            // approximation that never misses a real gateway.
            if (!qualifies) {
                List<WtgTransition> outgoing = wtgTransitions.get(activity);
                if (outgoing != null) {
                    for (WtgTransition t : outgoing) {
                        if (mopActivities.contains(t.targetActivity)) {
                            qualifies = true;
                            break;
                        }
                    }
                }
            }
            if (qualifies) {
                result.add(activity);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Pass 3.5: DIALOG re-keying to host activity (INV-MOP-25)
    // -------------------------------------------------------------------------

    /**
     * Re-key DIALOG windows to their host activity (D5/D6). A DIALOG window's name is the
     * dialog class (e.g. {@code android.app.AlertDialog}), which never equals
     * {@code newState.getActivity()} at runtime, so its already-parsed widgets are
     * unreachable for scoring. The activity→dialog edge in {@code transitions[]} recovers
     * the host: merge {@code widgetData[dialogClass]} into {@code widgetData[host]} under
     * the {@code mopRank} collision policy, move the widget-map key (not copy) so counts do
     * not inflate (A2), and promote host to {@code mopActivities} on a flagged merge (D6).
     * The dialog class's own Pass-2 {@code mopActivities} entry is retained — WTG edges into
     * the dialog are keyed by it and the OPTIONSMENU-gateway precompute tests
     * {@code mopActivities.contains(targetActivity)}, so dropping it would silently disable
     * that detection (A6). Orphan dialogs (no incoming transition) keep their key and are
     * counted for the caller's {@code [APE-RV]} diagnostic line.
     */
    private static void rekeyDialogsToHost(
            List<Window> windows, List<Transition> transitions,
            Map<Integer, Window> windowsById,
            Map<String, Map<String, Widget>> widgetData,
            Set<String> mopActivities, int[] orphanDialogs) {
        for (Window w : windows) {
            if (w.name == null || !"DIALOG".equals(w.type)) continue;
            String host = null;
            if (w.id >= 0) {
                for (Transition t : transitions) {
                    if (t.targetId != w.id) continue;
                    Window source = windowsById.get(t.sourceId);
                    if (source != null && source.name != null) {
                        host = baseActivity(source.name);   // first incoming edge wins (A3)
                        break;
                    }
                }
            }
            if (host == null) {
                orphanDialogs[0]++;   // unreachable dialog — keep the dialog-class key as-is
                continue;
            }
            String dialogClass = baseActivity(w.name);
            if (dialogClass.equals(host)) continue;   // already keyed under the host
            Map<String, Widget> dialogWidgets = widgetData.get(dialogClass);
            if (dialogWidgets == null) continue;      // no stored widgets (all empty-id dropped)
            Map<String, Widget> hostWidgets = widgetData.get(host);
            if (hostWidgets == null) {
                hostWidgets = new LinkedHashMap<>();
                widgetData.put(host, hostWidgets);
            }
            boolean flaggedMerged = false;
            for (Map.Entry<String, Widget> e : dialogWidgets.entrySet()) {
                Widget wd = e.getValue();
                Widget resident = hostWidgets.get(e.getKey());
                if (resident == null || mopRank(wd) > mopRank(resident)) {
                    hostWidgets.put(e.getKey(), wd);
                }
                if (wd.directMop || wd.transitiveMop) {
                    flaggedMerged = true;
                }
            }
            widgetData.remove(dialogClass);           // move, not copy — widget map only (A2)
            if (flaggedMerged) {
                mopActivities.add(host);              // D6; dialogClass entry retained (A6)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String getPackageName() { return packageName; }

    public String getMainActivity() { return mainActivity; }

    public boolean isComplete() { return complete; }

    public List<ReachabilityClass> getReachability() {
        return Collections.unmodifiableList(reachability);
    }

    public List<Window> getWindows() {
        return Collections.unmodifiableList(windows);
    }

    /** Returns the window with the given id, or null. */
    public Window getWindow(int id) {
        return windowsById.get(id);
    }

    public List<Transition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }

    /**
     * True when no parsed window carries any widget — a widget-less substrate (e.g. a
     * Compose/canvas UI where static analysis found no addressable widgets, so widget-level
     * MOP guidance has nothing to bind to). Pure function of the parsed {@code windows[].widgets}
     * counts; reads nothing else and affects no scoring, routing, or load outcome (INV-MOP-28).
     * No consumer yet.
     */
    public boolean isWidgetlessSubstrate() {
        for (Window w : windows) {
            if (!w.widgets.isEmpty()) {
                return false;
            }
        }
        return true;
    }

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

    /** Count of MOP-flagged widgets dropped during parsing for lacking a resource id (INV-MOP-20). */
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
     * The old incremental {@code StringBuilder} doubled its backing array as it grew, spiking the
     * transient footprint to ~4× the file size — the direct cause of the redreader OOM. A file
     * larger than {@code Integer.MAX_VALUE} cannot be array-backed and is rejected (in practice the
     * {@code load} budget guard rejects far smaller files first). Whole-file read is mandatory: the
     * Android-bundled org.json only offers {@code JSONTokener(String)}.
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

    private static Boolean optBooleanOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        return o.optBoolean(key, false);
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

    /** Strip a window-name suffix like "#OptionsMenu" to recover the owning activity. */
    private static String baseActivity(String windowName) {
        int idx = windowName.indexOf('#');
        return idx >= 0 ? windowName.substring(0, idx) : windowName;
    }

    private static int countWidgets(Map<String, Map<String, Widget>> widgetData) {
        int count = 0;
        for (Map<String, Widget> m : widgetData.values()) {
            count += m.size();
        }
        return count;
    }

    /**
     * The click-only WTG view's edge count — the quantity the three frontier passes gate on, and
     * therefore the one the record carries.
     */
    private static int countWtgEdges(Map<String, List<WtgTransition>> wtgTransitions) {
        int count = 0;
        for (List<WtgTransition> edges : wtgTransitions.values()) {
            count += edges.size();
        }
        return count;
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

    private static int countFlagged(Map<String, Map<String, Widget>> widgetData) {
        int count = 0;
        for (Map<String, Widget> m : widgetData.values()) {
            for (Widget w : m.values()) {
                if (w.directMop || w.transitiveMop) {
                    count++;
                }
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // POJOs (all nested under MopData — D1/D2)
    // -------------------------------------------------------------------------

    public static class Window {
        public int id = -1;
        public String type;
        public String name;
        public boolean isMain;
        public final List<Widget> widgets = new ArrayList<>();
    }

    public static class Widget {
        public int id = -1;
        public String idName;
        public String type;
        public String text;
        public String hint;
        public String inputType;
        public final List<String> entries = new ArrayList<>();
        public String prompt;
        public String spinnerMode;
        public String contentDescription;
        public String tooltipText;
        public final List<Listener> listeners = new ArrayList<>();
        // Derived locally (gh60 does not emit these) — INV-MOP-17.
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

    public static class Listener {
        public String eventType;
        public String handler;
        /** gh60-C3 forward compat — null until the producer emits it (D8). */
        public Boolean handlerReachesTarget;
        public Boolean handlerDirectlyReachesTarget;
    }

    public static class Transition {
        public int sourceId = -1;
        public int targetId = -1;
        public final List<TransitionEvent> events = new ArrayList<>();
    }

    public static class TransitionEvent {
        public String type;
        public String handler;
        public int widgetId = -1;
        public String widgetClass;
        public String widgetName;
    }

    public static class ReachabilityClass {
        public String className;
        public String componentType;
        public boolean isMain;
        public final List<ReachabilityMethod> methods = new ArrayList<>();
    }

    public static class ReachabilityMethod {
        public String name;
        public String signature;
        public boolean reachable;
        public boolean reachesTarget;
        public boolean directlyReachesTarget;
    }

    /** Click transition from one window/activity to another (convenience view). */
    public static class WtgTransition {
        public final String widgetName;
        public final String widgetClass;
        public final String targetActivity;

        public WtgTransition(String widgetName, String widgetClass, String targetActivity) {
            this.widgetName = widgetName;
            this.widgetClass = widgetClass;
            this.targetActivity = targetActivity;
        }
    }
}
