package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An Android component of the compact MOP artifact's {@code components} block. Each subclass is
 * one of the four component types; the type comes from the parent dict key
 * (activities/receivers/services/providers), not from a per-element field (D19).
 *
 * <p>Every field here has a production reader on the trigger or launch path. The manifest surface
 * the artifact does <em>not</em> carry is as deliberate as the surface it does: {@code exported}
 * is absent because eligibility must never test it (the dispatch path launches non-exported
 * components from uid 2000, so an export test would silently shrink the frontier) — with no field
 * to consult, that prohibition is structural rather than a rule someone must remember. The
 * intent-filter {@code <data>} block is absent because its one consumer wanted a URI, not a
 * structure: the generator assembles {@link #deepLinkUri} from it host-side (INV-DRV-07). The
 * providers' granular {@code readPermission}/{@code writePermission} are absent because only the
 * component-level {@code permission} gate was ever read.
 */
public class ComponentInfo {

    public final String className;
    /** "activity" / "receiver" / "service" / "provider" — derived from the wire dict key. */
    public final String componentType;
    public final boolean isMain;
    public final List<IntentFilter> intentFilters;
    /** The wire's {@code reachesMop}: does this component reach a monitored operation. */
    public final boolean reachesTarget;
    /**
     * A list whose <em>emptiness</em> is the fact: the wire carries {@code hasTargetMethods} and
     * the one consumer ({@code StatefulAgent.buildTriggerTuples}) only asks whether it is empty.
     */
    public final List<String> targetMethods;
    /**
     * {@code android:permission} the caller must hold to launch this component, or null
     * if none declared. When non-null, a trigger attempt without the permission
     * fails with SecurityException, so consumers should treat it as a gate.
     */
    public final String permission;
    /**
     * The activity's deep link — {@code scheme://host + path} of its first {@code ACTION_VIEW}
     * intent filter with a non-empty scheme list — or null when it declares none, which the
     * dispatch path reads as "launch the explicit component instead" (INV-CT-13). The string is
     * assembled host-side by the artifact generator (INV-DRV-07), so the rule lives in the one
     * component that still sees the intent-filter structure. Null on every non-activity: only
     * activities are launched by URI.
     */
    public final String deepLinkUri;

    protected ComponentInfo(String className, String componentType, boolean isMain,
                            List<IntentFilter> intentFilters,
                            boolean reachesTarget, List<String> targetMethods, String permission,
                            String deepLinkUri) {
        this.className = className;
        this.componentType = componentType;
        this.isMain = isMain;
        this.intentFilters = intentFilters != null
                ? Collections.unmodifiableList(intentFilters) : Collections.<IntentFilter>emptyList();
        this.reachesTarget = reachesTarget;
        this.targetMethods = targetMethods != null
                ? Collections.unmodifiableList(targetMethods) : Collections.<String>emptyList();
        this.permission = permission;
        this.deepLinkUri = deepLinkUri;
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

    /** A single intent filter: the actions and categories the broadcast/service tuples read. */
    public static class IntentFilter {
        public final List<String> actions;
        public final List<String> categories;

        public IntentFilter(List<String> actions, List<String> categories) {
            this.actions = actions != null
                    ? Collections.unmodifiableList(actions) : Collections.<String>emptyList();
            this.categories = categories != null
                    ? Collections.unmodifiableList(categories) : Collections.<String>emptyList();
        }
    }

    /** Receiver: triggered via am broadcast. */
    public static class ReceiverInfo extends ComponentInfo {
        public ReceiverInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods) {
            this(className, isMain, intentFilters, reachesTarget, targetMethods, null);
        }

        public ReceiverInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String permission) {
            super(className, "receiver", isMain, intentFilters, reachesTarget,
                    targetMethods, permission, null);
        }
    }

    /** Service: triggered via am startservice. */
    public static class ServiceInfo extends ComponentInfo {
        public ServiceInfo(String className, boolean isMain,
                           List<IntentFilter> intentFilters, boolean reachesTarget,
                           List<String> targetMethods) {
            this(className, isMain, intentFilters, reachesTarget, targetMethods, null);
        }

        public ServiceInfo(String className, boolean isMain,
                           List<IntentFilter> intentFilters, boolean reachesTarget,
                           List<String> targetMethods, String permission) {
            super(className, "service", isMain, intentFilters, reachesTarget,
                    targetMethods, permission, null);
        }
    }

    /** Activity: launched by URI when it carries a deep link, by explicit component otherwise. */
    public static class ActivityInfo extends ComponentInfo {
        public ActivityInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods) {
            this(className, isMain, intentFilters, reachesTarget, targetMethods, null, null);
        }

        public ActivityInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String permission) {
            this(className, isMain, intentFilters, reachesTarget, targetMethods, permission, null);
        }

        public ActivityInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String permission, String deepLinkUri) {
            super(className, "activity", isMain, intentFilters, reachesTarget,
                    targetMethods, permission, deepLinkUri);
        }
    }

    /** ContentProvider: triggered via content query/insert/update/delete. */
    public static class ProviderInfo extends ComponentInfo {
        public final String authorities;

        public ProviderInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String authorities) {
            this(className, isMain, intentFilters, reachesTarget, targetMethods,
                    authorities, null);
        }

        public ProviderInfo(String className, boolean isMain,
                            List<IntentFilter> intentFilters, boolean reachesTarget,
                            List<String> targetMethods, String authorities,
                            String permission) {
            super(className, "provider", isMain, intentFilters, reachesTarget,
                    targetMethods, permission, null);
            this.authorities = authorities != null ? authorities : "";
        }
    }
}
