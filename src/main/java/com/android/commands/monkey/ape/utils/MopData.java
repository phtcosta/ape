package com.android.commands.monkey.ape.utils;

import android.util.JsonReader;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the static analysis JSON produced by rv-android and builds an
 * in-memory map: activity → shortWidgetId → MOP reachability flags.
 *
 * JSON format:
 *   { "windows": [...], "reachability": [...], "transitions": [...] }
 *
 * Cross-reference: windows[].widgets[].listeners[].handler matches
 * reachability[].methods[].signature.
 *
 * Pass 3 (WTG): transitions[].sourceId/targetId reference windows[].id;
 * transitions[].events[] with type="click" are stored as WtgTransition.
 */
public class MopData {

    private static final String TAG = "MopData";

    /** Map: activityClassName → (shortResourceId → WidgetMopFlags) */
    private final Map<String, Map<String, WidgetMopFlags>> widgetData;

    /** Activities that have at least one MOP-reachable widget */
    private final Set<String> mopActivities;

    /** Map: sourceActivity → List of WTG click transitions (Pass 3) */
    private final Map<String, List<WtgTransition>> wtgTransitions;

    private MopData(Map<String, Map<String, WidgetMopFlags>> widgetData,
                    Set<String> mopActivities,
                    Map<String, List<WtgTransition>> wtgTransitions) {
        this.widgetData = widgetData;
        this.mopActivities = mopActivities;
        this.wtgTransitions = wtgTransitions;
    }

    /**
     * Package-private factory for unit tests that cannot use android.util.JsonReader.
     * Constructs a MopData with pre-built data structures.
     */
    static MopData forTest(Map<String, Map<String, WidgetMopFlags>> widgetData,
                           Set<String> mopActivities,
                           Map<String, List<WtgTransition>> wtgTransitions) {
        return new MopData(
                widgetData != null ? widgetData : new HashMap<String, Map<String, WidgetMopFlags>>(),
                mopActivities != null ? mopActivities : new HashSet<String>(),
                wtgTransitions != null ? wtgTransitions : new HashMap<String, List<WtgTransition>>());
    }

    /**
     * Load MOP data from a static analysis JSON file.
     *
     * @param path device-local path to static_analysis.json, or null
     * @return populated MopData, or null if path is null / file missing / malformed
     */
    public static MopData load(String path) {
        if (path == null) {
            return null;
        }

        // First pass: build signature → [directMop, transitiveMop] map
        Map<String, boolean[]> bySignature = new HashMap<>();
        // Second pass result
        Map<String, Map<String, WidgetMopFlags>> widgetData = new HashMap<>();
        Set<String> mopActivities = new HashSet<>();
        // Window id→name map (populated during Pass 2, consumed by Pass 3)
        Map<Integer, String> windowIdToName = new HashMap<>();
        // Third pass result
        Map<String, List<WtgTransition>> wtgTransitions = new HashMap<>();

        try {
            // Pass 1: reachability[]
            try (JsonReader reader = new JsonReader(
                    new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
                parseReachability(reader, bySignature);
            }
            // Pass 2: windows[] (also populates windowIdToName for Pass 3)
            try (JsonReader reader = new JsonReader(
                    new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
                parseWindows(reader, bySignature, widgetData, mopActivities, windowIdToName);
            }
            // Pass 3: transitions[] → WTG map (INV-WTG-03: uses windowIdToName from Pass 2)
            try (JsonReader reader = new JsonReader(
                    new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
                parseTransitions(reader, windowIdToName, wtgTransitions);
            }
        } catch (IOException e) {
            Log.w(TAG, "MopData: failed to load " + path + ": " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "MopData: malformed JSON at " + path + ": " + e.getMessage());
            return null;
        }

        Log.i(TAG, "MopData: loaded " + countWidgets(widgetData) + " widgets, "
                + countTransitions(wtgTransitions) + " WTG transitions from " + path);
        return new MopData(widgetData, mopActivities, wtgTransitions);
    }

    // -------------------------------------------------------------------------
    // Pass 1: parse reachability[] → bySignature map
    // -------------------------------------------------------------------------

    private static void parseReachability(JsonReader reader,
                                          Map<String, boolean[]> bySignature)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("reachability".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    parseReachabilityEntry(reader, bySignature);
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private static void parseReachabilityEntry(JsonReader reader,
                                               Map<String, boolean[]> bySignature)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("methods".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    parseMethod(reader, bySignature);
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private static void parseMethod(JsonReader reader,
                                    Map<String, boolean[]> bySignature)
            throws IOException {
        String signature = null;
        boolean directlyReachesMop = false;
        boolean reachesMop = false;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "signature":
                    signature = reader.nextString();
                    break;
                case "directlyReachesMop":
                    directlyReachesMop = reader.nextBoolean();
                    break;
                case "reachesMop":
                    reachesMop = reader.nextBoolean();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (signature != null && (directlyReachesMop || reachesMop)) {
            bySignature.put(signature, new boolean[]{directlyReachesMop, reachesMop});
        }
    }

    // -------------------------------------------------------------------------
    // Pass 2: parse windows[] → widgetData map
    // -------------------------------------------------------------------------

    private static void parseWindows(JsonReader reader,
                                     Map<String, boolean[]> bySignature,
                                     Map<String, Map<String, WidgetMopFlags>> widgetData,
                                     Set<String> mopActivities,
                                     Map<Integer, String> windowIdToName)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("windows".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    parseWindow(reader, bySignature, widgetData, mopActivities, windowIdToName);
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private static void parseWindow(JsonReader reader,
                                    Map<String, boolean[]> bySignature,
                                    Map<String, Map<String, WidgetMopFlags>> widgetData,
                                    Set<String> mopActivities,
                                    Map<Integer, String> windowIdToName)
            throws IOException {
        String activityName = null;
        int windowId = -1;
        Map<String, WidgetMopFlags> widgets = new HashMap<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "id":
                    windowId = reader.nextInt();
                    break;
                case "name":
                    activityName = reader.nextString();
                    break;
                case "widgets":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        parseWidget(reader, bySignature, widgets);
                    }
                    reader.endArray();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        // Collect window id→name for Pass 3 (INV-WTG-03)
        if (activityName != null && windowId >= 0) {
            windowIdToName.put(windowId, activityName);
        }

        if (activityName != null && !widgets.isEmpty()) {
            widgetData.put(activityName, widgets);
            // Mark activity as having MOP if any widget has flags
            for (WidgetMopFlags f : widgets.values()) {
                if (f.directMop || f.transitiveMop) {
                    mopActivities.add(activityName);
                    break;
                }
            }
        }
    }

    private static void parseWidget(JsonReader reader,
                                    Map<String, boolean[]> bySignature,
                                    Map<String, WidgetMopFlags> widgets)
            throws IOException {
        String idName = null;
        boolean directMop = false;
        boolean transitiveMop = false;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "idName":
                    idName = reader.nextString();
                    break;
                case "listeners":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String lf = reader.nextName();
                            if ("handler".equals(lf)) {
                                String handler = reader.nextString();
                                boolean[] flags = bySignature.get(handler);
                                if (flags != null) {
                                    if (flags[0]) directMop = true;
                                    if (flags[1]) transitiveMop = true;
                                }
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();
                    }
                    reader.endArray();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (idName != null) {
            WidgetMopFlags flags = new WidgetMopFlags();
            flags.directMop = directMop;
            flags.transitiveMop = transitiveMop;
            widgets.put(idName, flags);
        }
    }

    // -------------------------------------------------------------------------
    // Pass 3: parse transitions[] → WTG map (INV-WTG-01: only click events)
    // -------------------------------------------------------------------------

    private static void parseTransitions(JsonReader reader,
                                         Map<Integer, String> windowIdToName,
                                         Map<String, List<WtgTransition>> wtgTransitions)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("transitions".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    parseTransition(reader, windowIdToName, wtgTransitions);
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private static void parseTransition(JsonReader reader,
                                        Map<Integer, String> windowIdToName,
                                        Map<String, List<WtgTransition>> wtgTransitions)
            throws IOException {
        int sourceId = -1;
        int targetId = -1;
        List<String[]> clickEvents = new ArrayList<>(); // [widgetName, widgetClass]

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "sourceId":
                    sourceId = reader.nextInt();
                    break;
                case "targetId":
                    targetId = reader.nextInt();
                    break;
                case "events":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        parseTransitionEvent(reader, clickEvents);
                    }
                    reader.endArray();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        // Resolve IDs to activity names (INV-WTG-03)
        String sourceActivity = windowIdToName.get(sourceId);
        String targetActivity = windowIdToName.get(targetId);
        if (sourceActivity == null || targetActivity == null || clickEvents.isEmpty()) {
            return;
        }

        List<WtgTransition> list = wtgTransitions.get(sourceActivity);
        if (list == null) {
            list = new ArrayList<>();
            wtgTransitions.put(sourceActivity, list);
        }
        for (String[] evt : clickEvents) {
            list.add(new WtgTransition(evt[0], evt[1], targetActivity));
        }
    }

    /**
     * Parse a single event from transitions[].events[].
     * INV-WTG-01: only events with type="click" are collected.
     */
    private static void parseTransitionEvent(JsonReader reader,
                                             List<String[]> clickEvents)
            throws IOException {
        String type = null;
        String widgetName = null;
        String widgetClass = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "type":
                    type = reader.nextString();
                    break;
                case "widgetName":
                    widgetName = reader.nextString();
                    break;
                case "widgetClass":
                    widgetClass = reader.nextString();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if ("click".equals(type)) {
            clickEvents.add(new String[]{
                    widgetName != null ? widgetName : "",
                    widgetClass != null ? widgetClass : ""
            });
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns MOP flags for the given widget, or null if no match.
     *
     * @param activity activity class name (from newState.getActivity())
     * @param shortId  short widget resource ID (e.g. "btn_encrypt")
     */
    public WidgetMopFlags getWidget(String activity, String shortId) {
        Map<String, WidgetMopFlags> widgets = widgetData.get(activity);
        if (widgets == null) return null;
        return widgets.get(shortId);
    }

    /**
     * Returns true if the activity has at least one MOP-reachable widget.
     */
    public boolean activityHasMop(String activity) {
        return mopActivities.contains(activity);
    }

    /**
     * Returns true if WTG transition data was loaded (transitions[] was present and non-empty).
     */
    public boolean hasWtgData() {
        return !wtgTransitions.isEmpty();
    }

    /**
     * Returns the WTG click transitions originating from the given activity.
     *
     * @param activityName source activity class name (may include window suffix like "#OptionsMenu")
     * @return list of transitions, or empty list if no data
     */
    public List<WtgTransition> getWtgTransitions(String activityName) {
        List<WtgTransition> list = wtgTransitions.get(activityName);
        return list != null ? list : Collections.<WtgTransition>emptyList();
    }

    /**
     * Extract the short resource ID from a full Android resource ID string.
     * "com.example:id/btn_encrypt" → "btn_encrypt"
     * null or no ":id/" → ""
     */
    public static String extractShortId(String resourceId) {
        if (resourceId == null) return "";
        int idx = resourceId.indexOf(":id/");
        return idx < 0 ? "" : resourceId.substring(idx + 4);
    }

    // -------------------------------------------------------------------------
    // Inner classes / helpers
    // -------------------------------------------------------------------------

    public static class WidgetMopFlags {
        public boolean directMop;
        public boolean transitiveMop;
    }

    /**
     * Represents a single click event from the WTG that navigates from one activity to another.
     * Populated from transitions[].events[] where type="click".
     */
    public static class WtgTransition {
        /** Resource name of the triggering widget (e.g., "menu_item_cipher", "buttonCipher") */
        public final String widgetName;
        /** Widget class (e.g., "android.view.MenuItem", "android.widget.Button") */
        public final String widgetClass;
        /** Resolved target activity name (e.g., "com.example.CipherActivity") */
        public final String targetActivity;

        public WtgTransition(String widgetName, String widgetClass, String targetActivity) {
            this.widgetName = widgetName;
            this.widgetClass = widgetClass;
            this.targetActivity = targetActivity;
        }
    }

    private static int countWidgets(Map<String, Map<String, WidgetMopFlags>> widgetData) {
        int count = 0;
        for (Map<String, WidgetMopFlags> m : widgetData.values()) {
            count += m.size();
        }
        return count;
    }

    private static int countTransitions(Map<String, List<WtgTransition>> wtgTransitions) {
        int count = 0;
        for (List<WtgTransition> list : wtgTransitions.values()) {
            count += list.size();
        }
        return count;
    }
}
