package com.android.commands.monkey.ape.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String OPTIONS_MENU_SUFFIX = "#OptionsMenu";

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

    /** Load MOP data from a static-analysis JSON file (no package sanity check). */
    public static MopData load(String path) {
        return load(path, null, null);
    }

    /**
     * Load MOP data from a static-analysis JSON file.
     *
     * @param path                  device-local path, or null
     * @param expectedPackage       package to compare against (T1.7), or null to skip
     * @param expectedMainActivity  main activity to compare against (T1.7), or null to skip
     * @return populated MopData, or null on: null path / missing file / malformed JSON /
     *         sentinel absent or false / strict-mode package mismatch
     */
    public static MopData load(String path, String expectedPackage, String expectedMainActivity) {
        if (path == null) {
            return null;
        }
        JSONObject root;
        try {
            // The Android-bundled org.json only offers JSONTokener(String), so read fully first.
            root = new JSONObject(new JSONTokener(readFile(path)));
        } catch (IOException e) {
            Logger.wprintln("MopData: failed to read " + path + ": " + e.getMessage());
            return null;
        } catch (JSONException e) {
            Logger.wprintln("MopData: malformed JSON at " + path + ": " + e.getMessage());
            return null;
        }

        // Sentinel (INV-MOP-09) — position-independent single key read (D5/D21).
        if (!root.optBoolean("complete", false)) {
            Logger.wprintln("MopData: '\"complete\": true' sentinel absent or false at " + path
                    + " — treating as no MOP data (truncated analysis)");
            return null;
        }

        try {
            String packageName = optStringOrNull(root, "package");
            String mainActivity = optStringOrNull(root, "mainActivity");

            // Pass 1: reachability[] → typed list + bySignature index.
            Map<String, boolean[]> bySignature = new HashMap<>();
            List<ReachabilityClass> reachability =
                    parseReachability(root.optJSONArray("reachability"), bySignature);

            // Pass 2: windows[] → typed windows + widgetData + derived MOP flags.
            List<Window> windows = new ArrayList<>();
            Map<Integer, Window> windowsById = new HashMap<>();
            Map<String, Map<String, Widget>> widgetData = new HashMap<>();
            Set<String> mopActivities = new HashSet<>();
            parseWindows(root.optJSONArray("windows"), bySignature,
                    windows, windowsById, widgetData, mopActivities);

            // Pass 3: transitions[] → typed transitions + click-only WTG view.
            Map<String, List<WtgTransition>> wtgTransitions = new HashMap<>();
            List<Transition> transitions =
                    parseTransitions(root.optJSONArray("transitions"), windowsById, wtgTransitions);

            // Pass 4: components{} → typed component lists.
            List<ComponentInfo.ReceiverInfo> receivers = new ArrayList<>();
            List<ComponentInfo.ServiceInfo> services = new ArrayList<>();
            List<ComponentInfo.ActivityInfo> activities = new ArrayList<>();
            List<ComponentInfo.ProviderInfo> providers = new ArrayList<>();
            parseComponents(root.optJSONObject("components"),
                    receivers, services, activities, providers);

            // Precompute OPTIONSMENU gateway set (T1.2, D13) — needs WTG + mopActivities.
            Set<String> menuGateways = precomputeMopOptionsMenus(
                    windows, wtgTransitions, mopActivities);

            MopData data = new MopData(packageName, mainActivity, true,
                    reachability, windows, windowsById, widgetData, mopActivities,
                    wtgTransitions, transitions, receivers, services, activities, providers,
                    menuGateways);

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
            if (mismatch && Config.mopStrictPackageMatch) {
                Logger.wprintln("MopData: strict package match enabled — rejecting " + path);
                return null;
            }

            Logger.iprintln("MopData: loaded " + countWidgets(widgetData) + " widgets, "
                    + reachability.size() + " reachability classes, "
                    + transitions.size() + " transitions, "
                    + (receivers.size() + services.size() + activities.size() + providers.size())
                    + " components, " + menuGateways.size() + " MOP option-menus from " + path);
            return data;
        } catch (JSONException e) {
            Logger.wprintln("MopData: malformed JSON structure at " + path + ": " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Pass 1: reachability[]
    // -------------------------------------------------------------------------

    private static List<ReachabilityClass> parseReachability(JSONArray arr,
                                                             Map<String, boolean[]> bySignature)
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
                }
            }
            classes.add(rc);
        }
        return classes;
    }

    // -------------------------------------------------------------------------
    // Pass 2: windows[] + widgets (flat, no recursion — D3)
    // -------------------------------------------------------------------------

    private static void parseWindows(JSONArray arr, Map<String, boolean[]> bySignature,
                                     List<Window> windows, Map<Integer, Window> windowsById,
                                     Map<String, Map<String, Widget>> widgetData,
                                     Set<String> mopActivities) throws JSONException {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Window w = parseWindow(arr.getJSONObject(i), bySignature);
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
                if (wd.idName != null) {
                    widgets.put(wd.idName, wd);
                }
                if (wd.directMop || wd.transitiveMop) {
                    mopActivities.add(activity);
                }
            }
        }
    }

    private static Window parseWindow(JSONObject wo, Map<String, boolean[]> bySignature)
            throws JSONException {
        Window w = new Window();
        w.id = wo.optInt("id", -1);
        w.type = optStringOrNull(wo, "type");
        w.name = optStringOrNull(wo, "name");
        w.isMain = wo.optBoolean("isMain", false);
        JSONArray widgets = wo.optJSONArray("widgets");
        if (widgets != null) {
            for (int i = 0; i < widgets.length(); i++) {
                w.widgets.add(parseWidget(widgets.getJSONObject(i), bySignature));
            }
        }
        return w;
    }

    private static Widget parseWidget(JSONObject wo, Map<String, boolean[]> bySignature)
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
        deriveWidgetMopFlags(w, bySignature);
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
    private static void deriveWidgetMopFlags(Widget w, Map<String, boolean[]> bySignature) {
        for (Listener l : w.listeners) {
            boolean direct;
            boolean transitive;
            if (l.handlerDirectlyReachesTarget != null || l.handlerReachesTarget != null) {
                direct = Boolean.TRUE.equals(l.handlerDirectlyReachesTarget);
                transitive = Boolean.TRUE.equals(l.handlerReachesTarget) || direct;
            } else {
                boolean[] flags = l.handler != null ? bySignature.get(l.handler) : null;
                direct = flags != null && flags[0];
                transitive = flags != null && flags[1];
            }
            orInto(w.directMopByEventType, l.eventType, direct);
            orInto(w.transitiveMopByEventType, l.eventType, transitive);
            w.directMop |= direct;
            w.transitiveMop |= transitive;
        }
    }

    private static void orInto(Map<String, Boolean> map, String key, boolean value) {
        Boolean prev = map.get(key);
        map.put(key, (prev != null && prev) || value);
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

            // Click-only convenience view keyed by source window name (INV-WTG-01/03).
            Window source = windowsById.get(t.sourceId);
            Window target = windowsById.get(t.targetId);
            if (source == null || target == null || source.name == null || target.name == null) {
                continue;
            }
            for (TransitionEvent e : t.events) {
                if ("click".equals(e.type)) {
                    List<WtgTransition> list = wtgTransitions.get(source.name);
                    if (list == null) {
                        list = new ArrayList<>();
                        wtgTransitions.put(source.name, list);
                    }
                    list.add(new WtgTransition(
                            e.widgetName != null ? e.widgetName : "",
                            e.widgetClass != null ? e.widgetClass : "",
                            target.name));
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

    private static Set<String> precomputeMopOptionsMenus(
            List<Window> windows, Map<String, List<WtgTransition>> wtgTransitions,
            Set<String> mopActivities) {
        Set<String> result = new HashSet<>();
        for (Window w : windows) {
            if (w.name == null || !"OPTIONSMENU".equals(w.type)) continue;
            int idx = w.name.indexOf(OPTIONS_MENU_SUFFIX);
            String activity = idx >= 0 ? w.name.substring(0, idx) : w.name;
            boolean qualifies = false;
            // Condition 1: a widget in the menu itself reaches MOP.
            for (Widget wd : w.widgets) {
                if (wd.directMop || wd.transitiveMop) {
                    qualifies = true;
                    break;
                }
            }
            // Condition 2 (gateway): a menu item navigates (WTG) to a MOP activity.
            if (!qualifies) {
                List<WtgTransition> outgoing = wtgTransitions.get(w.name);
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

    /** True if the activity's OPTIONSMENU is a MOP gateway (T1.2, D13). */
    public boolean activityHasMopOptionsMenu(String activity) {
        return activitiesWithMopOptionsMenu.contains(activity);
    }

    public boolean hasWtgData() {
        return !wtgTransitions.isEmpty();
    }

    /**
     * Click transitions originating from the given window/activity name.
     *
     * @param activityName source window name (may include the "#OptionsMenu" suffix)
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

    private static String readFile(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        try (InputStreamReader in = new InputStreamReader(new FileInputStream(path), "UTF-8")) {
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
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
            if (eventType != null && directMopByEventType.containsKey(eventType)) {
                return Boolean.TRUE.equals(directMopByEventType.get(eventType));
            }
            return directMop;
        }

        /** Transitive-MOP flag for the given event type, falling back to the aggregate. */
        public boolean isTransitiveMop(String eventType) {
            if (eventType != null && transitiveMopByEventType.containsKey(eventType)) {
                return Boolean.TRUE.equals(transitiveMopByEventType.get(eventType));
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
