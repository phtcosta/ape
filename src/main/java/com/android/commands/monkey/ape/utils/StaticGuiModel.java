/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The static GUI model of the analysed app, read-only: its window transition graph, the static
 * metadata of its widgets, its package and main activity, and its manifest components with their
 * intent filters, the filter's reduced {@code data} included (guidance-groups "Static Data Root
 * and Split Providers", plan P-1).
 *
 * <p>It is one of the two providers {@link StaticArtifactLoader} splits the artifact into;
 * {@link MopData} is the other, and holds the specification targets. The STATIC members read this
 * model only. The MOP members that need the WTG or the components read it too, and read
 * {@code MopData} for the targets. Nothing in this model is a MOP fact — no flag, no distance, no
 * {@code reachesMop} — which is what lets the STATIC group run on the same artifact with MOP off.
 *
 * <p>Immutable; built once per run by the loader, which parses the artifact once.
 */
public final class StaticGuiModel {

    /** A model with no edge, no widget, no component and no package; for tests that read none. */
    public static final StaticGuiModel EMPTY =
            new StaticGuiModel(null, null, null, null, null, null, null, null);

    private final String packageName;
    private final String mainActivity;
    /** Base source activity → its click-only WTG edges (INV-WTG-04). */
    private final Map<String, List<WtgTransition>> wtg;
    /** Base activity → short resource id → the widget's static metadata. */
    private final Map<String, Map<String, WidgetMeta>> widgets;
    private final List<ComponentInfo.ReceiverInfo> receivers;
    private final List<ComponentInfo.ServiceInfo> services;
    private final List<ComponentInfo.ActivityInfo> activities;
    private final List<ComponentInfo.ProviderInfo> providers;

    /**
     * Public because the suites outside this package state the model they need instead of
     * authoring an artifact that loads to it; production builds it through
     * {@link StaticArtifactLoader}. A null map or list reads as empty.
     *
     * @param wtg base source activity → click edges
     * @param widgets base activity → short resource id → metadata
     */
    public StaticGuiModel(String packageName, String mainActivity,
            Map<String, List<WtgTransition>> wtg, Map<String, Map<String, WidgetMeta>> widgets,
            List<ComponentInfo.ReceiverInfo> receivers, List<ComponentInfo.ServiceInfo> services,
            List<ComponentInfo.ActivityInfo> activities,
            List<ComponentInfo.ProviderInfo> providers) {
        this.packageName = packageName;
        this.mainActivity = mainActivity;
        this.wtg = wtg != null ? wtg : new HashMap<String, List<WtgTransition>>();
        this.widgets = widgets != null ? widgets : new HashMap<String, Map<String, WidgetMeta>>();
        this.receivers = immutable(receivers);
        this.services = immutable(services);
        this.activities = immutable(activities);
        this.providers = immutable(providers);
    }

    private static <T> List<T> immutable(List<T> list) {
        return list != null ? Collections.unmodifiableList(new ArrayList<>(list))
                : Collections.<T>emptyList();
    }

    /** The analysed app's package, which the component trigger addresses its intents to. */
    public String packageName() {
        return packageName;
    }

    /** The analysed app's main activity. */
    public String mainActivity() {
        return mainActivity;
    }

    /** Whether the artifact carries at least one WTG source activity. */
    public boolean hasWtgData() {
        return !wtg.isEmpty();
    }

    /**
     * The WTG click edges out of {@code activity}, or an empty list.
     *
     * @param activity the base source activity (the view is keyed by base activity, with any
     *                 {@code #OptionsMenu}/fragment suffix stripped host-side — INV-WTG-04/05)
     */
    public List<WtgTransition> wtgTransitions(String activity) {
        List<WtgTransition> list = wtg.get(activity);
        return list != null ? list : Collections.<WtgTransition>emptyList();
    }

    /**
     * The static metadata of the widget {@code shortId} in {@code activity}, or null when the
     * artifact lists no such widget.
     *
     * @param activity the activity class name
     * @param shortId the widget's resource-id entry name ({@link MopData#extractShortId})
     */
    public WidgetMeta widget(String activity, String shortId) {
        Map<String, WidgetMeta> byId = widgets.get(activity);
        return byId == null ? null : byId.get(shortId);
    }

    /** The manifest's receivers, with their intent filters. */
    public List<ComponentInfo.ReceiverInfo> receivers() {
        return receivers;
    }

    /** The manifest's services, with their intent filters. */
    public List<ComponentInfo.ServiceInfo> services() {
        return services;
    }

    /** The manifest's activities, in manifest order. */
    public List<ComponentInfo.ActivityInfo> activities() {
        return activities;
    }

    /** The manifest's providers. */
    public List<ComponentInfo.ProviderInfo> providers() {
        return providers;
    }

    /** Whether the manifest lists any component. */
    public boolean hasComponents() {
        return !receivers.isEmpty() || !services.isEmpty()
                || !activities.isEmpty() || !providers.isEmpty();
    }

    /**
     * A click edge of the WTG view: which widget, and which base activity it navigates to. The
     * producer's {@code widgetClass} is not carried — the consumers match on {@code widgetName}
     * and read {@code targetActivity}, and none has ever consulted the class.
     */
    public static class WtgTransition {
        public final String widgetName;
        public final String targetActivity;

        public WtgTransition(String widgetName, String targetActivity) {
            this.widgetName = widgetName;
            this.targetActivity = targetActivity;
        }
    }

    /**
     * What the static analysis says about one widget, as typed input and the LLM prompt read it:
     * its id name, its input type and hint, the entries of a list or spinner, and its labels.
     * Immutable; a field the artifact omits is null, and {@link #entries} is then empty.
     */
    public static final class WidgetMeta {
        public final String idName;
        public final String hint;
        public final String inputType;
        public final List<String> entries;
        public final String prompt;
        public final String spinnerMode;
        public final String contentDescription;
        public final String tooltipText;

        public WidgetMeta(String idName, String hint, String inputType, List<String> entries,
                String prompt, String spinnerMode, String contentDescription, String tooltipText) {
            this.idName = idName;
            this.hint = hint;
            this.inputType = inputType;
            this.entries = entries == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(entries));
            this.prompt = prompt;
            this.spinnerMode = spinnerMode;
            this.contentDescription = contentDescription;
            this.tooltipText = tooltipText;
        }
    }
}
