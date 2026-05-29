package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android component data parsed from the static analysis JSON's components{} section.
 * Each subclass represents one of the four Android component types. The component type
 * is derived from the parent dict key in the JSON (activities/receivers/services/providers),
 * not from a per-element field (D19).
 *
 * <p>gh60 D15 enrichment: each component now also carries the data needed to manually
 * construct a launching Intent — the {@code <data>} block per intent filter
 * ({@link IntentFilter.DataSpec}) plus the {@code permission} gate (and, for providers,
 * granular {@code readPermission}/{@code writePermission}). These fields are additive and
 * default to empty/null so JSON written before gh60 D15 still parses.
 */
public class ComponentInfo {

    public final String className;
    /** "activity" / "receiver" / "service" / "provider" — derived from the JSON dict key. */
    public final String componentType;
    public final boolean isMain;
    public final boolean exported;
    public final List<IntentFilter> intentFilters;
    /** Read from the JSON reachesTarget field (not hardcoded). */
    public final boolean reachesTarget;
    public final List<String> targetMethods;
    /**
     * {@code android:permission} the caller must hold to launch this component, or null
     * if none declared (gh60 D15). When non-null, a trigger attempt without the permission
     * fails with SecurityException, so consumers should treat it as a gate.
     */
    public final String permission;

    protected ComponentInfo(String className, String componentType, boolean isMain,
                            boolean exported, List<IntentFilter> intentFilters,
                            boolean reachesTarget, List<String> targetMethods, String permission) {
        this.className = className;
        this.componentType = componentType;
        this.isMain = isMain;
        this.exported = exported;
        this.intentFilters = intentFilters != null
                ? Collections.unmodifiableList(intentFilters) : Collections.<IntentFilter>emptyList();
        this.reachesTarget = reachesTarget;
        this.targetMethods = targetMethods != null
                ? Collections.unmodifiableList(targetMethods) : Collections.<String>emptyList();
        this.permission = permission;
    }

    /** Flat union of every action across all intent filters (order preserved, no dedup). */
    public List<String> getActions() {
        List<String> all = new ArrayList<>();
        for (IntentFilter f : intentFilters) {
            all.addAll(f.actions);
        }
        return all;
    }

    /** Flat union of every category across all intent filters (order preserved, no dedup). */
    public List<String> getCategories() {
        List<String> all = new ArrayList<>();
        for (IntentFilter f : intentFilters) {
            all.addAll(f.categories);
        }
        return all;
    }

    /** True when a {@code android:permission} gate is declared (caller must hold it). */
    public boolean hasPermissionGate() {
        return permission != null && !permission.isEmpty();
    }

    /** A single intent filter: its declared actions, categories, and (gh60 D15) data block. */
    public static class IntentFilter {
        public final List<String> actions;
        public final List<String> categories;
        /** The {@code <data>} URI/MIME constraints; never null (empty when absent). */
        public final DataSpec data;

        public IntentFilter(List<String> actions, List<String> categories) {
            this(actions, categories, null);
        }

        public IntentFilter(List<String> actions, List<String> categories, DataSpec data) {
            this.actions = actions != null
                    ? Collections.unmodifiableList(actions) : Collections.<String>emptyList();
            this.categories = categories != null
                    ? Collections.unmodifiableList(categories) : Collections.<String>emptyList();
            this.data = data != null ? data : DataSpec.EMPTY;
        }

        /** True when any URI scheme or MIME type is declared (deep-link / typed dispatch). */
        public boolean hasData() {
            return !data.schemes.isEmpty() || !data.mimeTypes.isEmpty();
        }
    }

    /**
     * The {@code <data>} block of an intent filter (gh60 D15). Holds the constraints needed
     * to build a deep-link ({@code Intent(VIEW, uri)}) or MIME-typed ({@code ACTION_SEND}) Intent.
     */
    public static class DataSpec {
        public static final DataSpec EMPTY = new DataSpec(null, null, null, null, null, null, null);

        public final List<String> schemes;
        public final List<String> hosts;
        public final List<String> ports;
        public final List<String> paths;
        public final List<String> pathPrefixes;
        public final List<String> pathPatterns;
        public final List<String> mimeTypes;

        public DataSpec(List<String> schemes, List<String> hosts, List<String> ports,
                        List<String> paths, List<String> pathPrefixes,
                        List<String> pathPatterns, List<String> mimeTypes) {
            this.schemes = ro(schemes);
            this.hosts = ro(hosts);
            this.ports = ro(ports);
            this.paths = ro(paths);
            this.pathPrefixes = ro(pathPrefixes);
            this.pathPatterns = ro(pathPatterns);
            this.mimeTypes = ro(mimeTypes);
        }

        private static List<String> ro(List<String> l) {
            return l != null ? Collections.unmodifiableList(l) : Collections.<String>emptyList();
        }
    }

    /** Receiver: triggered via am broadcast. */
    public static class ReceiverInfo extends ComponentInfo {
        public ReceiverInfo(String className, boolean isMain, boolean exported,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods) {
            this(className, isMain, exported, intentFilters, reachesTarget, targetMethods, null);
        }

        public ReceiverInfo(String className, boolean isMain, boolean exported,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String permission) {
            super(className, "receiver", isMain, exported, intentFilters, reachesTarget,
                    targetMethods, permission);
        }
    }

    /** Service: triggered via am startservice. */
    public static class ServiceInfo extends ComponentInfo {
        public ServiceInfo(String className, boolean isMain, boolean exported,
                           List<IntentFilter> intentFilters, boolean reachesTarget,
                           List<String> targetMethods) {
            this(className, isMain, exported, intentFilters, reachesTarget, targetMethods, null);
        }

        public ServiceInfo(String className, boolean isMain, boolean exported,
                           List<IntentFilter> intentFilters, boolean reachesTarget,
                           List<String> targetMethods, String permission) {
            super(className, "service", isMain, exported, intentFilters, reachesTarget,
                    targetMethods, permission);
        }
    }

    /** Activity: can be launched directly via intent. */
    public static class ActivityInfo extends ComponentInfo {
        public ActivityInfo(String className, boolean isMain, boolean exported,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods) {
            this(className, isMain, exported, intentFilters, reachesTarget, targetMethods, null);
        }

        public ActivityInfo(String className, boolean isMain, boolean exported,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String permission) {
            super(className, "activity", isMain, exported, intentFilters, reachesTarget,
                    targetMethods, permission);
        }
    }

    /** ContentProvider: triggered via content query/insert/update/delete. */
    public static class ProviderInfo extends ComponentInfo {
        public final String authorities;
        /** {@code android:readPermission} gate, or null (gh60 D15). */
        public final String readPermission;
        /** {@code android:writePermission} gate, or null (gh60 D15). */
        public final String writePermission;

        public ProviderInfo(String className, boolean isMain, boolean exported,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String authorities) {
            this(className, isMain, exported, intentFilters, reachesTarget, targetMethods,
                    authorities, null, null, null);
        }

        public ProviderInfo(String className, boolean isMain, boolean exported,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String authorities,
                            String permission, String readPermission, String writePermission) {
            super(className, "provider", isMain, exported, intentFilters, reachesTarget,
                    targetMethods, permission);
            this.authorities = authorities != null ? authorities : "";
            this.readPermission = readPermission;
            this.writePermission = writePermission;
        }
    }
}
