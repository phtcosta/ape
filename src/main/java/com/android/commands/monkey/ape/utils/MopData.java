package com.android.commands.monkey.ape.utils;

import android.util.JsonReader;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses the static analysis JSON produced by rv-android and builds an
 * in-memory map: activity → shortWidgetId → MOP reachability flags.
 *
 * JSON format:
 *   { "windows": [...], "reachability": [...] }
 *
 * Cross-reference: windows[].widgets[].listeners[].handler matches
 * reachability[].methods[].signature.
 */
public class MopData {

    private static final String TAG = "MopData";

    /** Map: activityClassName → (shortResourceId → WidgetMopFlags) */
    private final Map<String, Map<String, WidgetMopFlags>> widgetData;

    /** Activities that have at least one MOP-reachable widget */
    private final Set<String> mopActivities;

    private MopData(Map<String, Map<String, WidgetMopFlags>> widgetData,
                    Set<String> mopActivities) {
        this.widgetData = widgetData;
        this.mopActivities = mopActivities;
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

        try {
            // Pass 1: reachability[]
            try (JsonReader reader = new JsonReader(
                    new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
                parseReachability(reader, bySignature);
            }
            // Pass 2: windows[]
            try (JsonReader reader = new JsonReader(
                    new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
                parseWindows(reader, bySignature, widgetData, mopActivities);
            }
        } catch (IOException e) {
            Log.w(TAG, "MopData: failed to load " + path + ": " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "MopData: malformed JSON at " + path + ": " + e.getMessage());
            return null;
        }

        Log.i(TAG, "MopData: loaded " + countWidgets(widgetData) + " widgets from " + path);
        return new MopData(widgetData, mopActivities);
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
                                     Set<String> mopActivities)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("windows".equals(name)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    parseWindow(reader, bySignature, widgetData, mopActivities);
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
                                    Set<String> mopActivities)
            throws IOException {
        String activityName = null;
        Map<String, WidgetMopFlags> widgets = new HashMap<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
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

    private static int countWidgets(Map<String, Map<String, WidgetMopFlags>> widgetData) {
        int count = 0;
        for (Map<String, WidgetMopFlags> m : widgetData.values()) {
            count += m.size();
        }
        return count;
    }
}
