package com.android.commands.monkey.ape.utils;

import android.content.Intent;
import android.util.JsonReader;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lookup table of system broadcast actions with typed extras, sourced from
 * VLM-Fuzz's system-broadcast.json (187 entries, 120 with extras).
 *
 * The JSON file is pushed to the device at /data/local/tmp/system-broadcast.json
 * alongside the ape-rv.jar. Each entry has:
 *   {"action": "android.intent.action.BOOT_COMPLETED", "adb": ["adb shell am broadcast ..."]}
 *
 * Extras are parsed from the adb command flags: --es (String), --ei (int),
 * --ez (boolean), --el (long), --ef (float).
 */
public class SystemBroadcastCatalog {

    private static final String TAG = "SystemBroadcastCatalog";
    private static final String DEFAULT_PATH = "/data/local/tmp/system-broadcast.json";

    private final Map<String, List<IntentExtra>> catalog;

    public static class IntentExtra {
        public final String type; // "es", "ei", "ez", "el", "ef"
        public final String key;
        public final String value;

        public IntentExtra(String type, String key, String value) {
            this.type = type;
            this.key = key;
            this.value = value;
        }

        /** Apply this extra to an Intent. */
        public void applyTo(Intent intent) {
            switch (type) {
                case "es":
                    intent.putExtra(key, value);
                    break;
                case "ei":
                    try { intent.putExtra(key, Integer.parseInt(value)); } catch (NumberFormatException e) { /* skip */ }
                    break;
                case "ez":
                    intent.putExtra(key, Boolean.parseBoolean(value));
                    break;
                case "el":
                    try { intent.putExtra(key, Long.parseLong(value)); } catch (NumberFormatException e) { /* skip */ }
                    break;
                case "ef":
                    try { intent.putExtra(key, Float.parseFloat(value)); } catch (NumberFormatException e) { /* skip */ }
                    break;
            }
        }
    }

    private SystemBroadcastCatalog(Map<String, List<IntentExtra>> catalog) {
        this.catalog = catalog;
    }

    /** Creates an empty catalog (no extras for any action). */
    public SystemBroadcastCatalog() {
        this.catalog = new HashMap<>();
    }

    /**
     * Load the catalog from the default device path.
     * Returns an empty catalog (not null) if the file is missing or malformed.
     */
    public static SystemBroadcastCatalog load() {
        return load(DEFAULT_PATH);
    }

    public static SystemBroadcastCatalog load(String path) {
        Map<String, List<IntentExtra>> catalog = new HashMap<>();
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
            reader.beginArray();
            while (reader.hasNext()) {
                parseEntry(reader, catalog);
            }
            reader.endArray();
            Log.i(TAG, "Loaded " + catalog.size() + " broadcast actions from " + path);
        } catch (IOException e) {
            Log.w(TAG, "Catalog not available at " + path + " (component triggering will use action-only intents)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse catalog at " + path + ": " + e.getMessage());
        }
        return new SystemBroadcastCatalog(catalog);
    }

    /** For unit tests. */
    static SystemBroadcastCatalog forTest(Map<String, List<IntentExtra>> catalog) {
        return new SystemBroadcastCatalog(catalog != null ? catalog : new HashMap<String, List<IntentExtra>>());
    }

    private static void parseEntry(JsonReader reader, Map<String, List<IntentExtra>> catalog)
            throws IOException {
        String action = null;
        String adbCommand = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "action":
                    action = reader.nextString();
                    break;
                case "adb":
                    reader.beginArray();
                    if (reader.hasNext()) {
                        adbCommand = reader.nextString();
                    }
                    while (reader.hasNext()) {
                        reader.skipValue();
                    }
                    reader.endArray();
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        if (action != null && adbCommand != null) {
            List<IntentExtra> extras = parseExtrasFromAdb(adbCommand);
            if (!extras.isEmpty() && !catalog.containsKey(action)) {
                catalog.put(action, extras);
            }
        }
    }

    /**
     * Parse extras from an adb broadcast command string.
     * Handles: --es key "value", --ei key value, --ez key value, --el key value, --ef key value
     */
    static List<IntentExtra> parseExtrasFromAdb(String adbCommand) {
        List<IntentExtra> extras = new ArrayList<>();
        String[] tokens = adbCommand.split("\\s+");

        for (int i = 0; i < tokens.length - 2; i++) {
            String token = tokens[i];
            if (token.equals("--es") || token.equals("--ei") || token.equals("--ez")
                    || token.equals("--el") || token.equals("--ef")) {
                String type = token.substring(2); // "es", "ei", etc.
                String key = tokens[i + 1];
                // Value may be quoted — collect until next flag or end
                StringBuilder value = new StringBuilder();
                int j = i + 2;
                while (j < tokens.length && !tokens[j].startsWith("--")
                        && !tokens[j].contains("/.")) { // stop at component name
                    if (value.length() > 0) value.append(" ");
                    value.append(tokens[j].replace("\"", ""));
                    j++;
                }
                if (value.length() > 0) {
                    extras.add(new IntentExtra(type, key, value.toString()));
                }
                i = j - 1; // skip consumed tokens
            }
        }
        return extras;
    }

    /**
     * Look up extras for a broadcast action.
     * @return typed extras for the action, or empty list if not in catalog
     */
    public List<IntentExtra> lookup(String action) {
        List<IntentExtra> extras = catalog.get(action);
        return extras != null ? extras : Collections.<IntentExtra>emptyList();
    }

    /** Returns true if the catalog has an entry for this action. */
    public boolean hasAction(String action) {
        return catalog.containsKey(action);
    }

    /** Number of actions in the catalog. */
    public int size() {
        return catalog.size();
    }
}
