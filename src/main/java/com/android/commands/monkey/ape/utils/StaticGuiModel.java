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

import java.util.Collections;
import java.util.List;

/**
 * The static GUI model of the analysed app, read-only: its window transition graph, the static
 * metadata of its widgets, its package and main activity, and its manifest components with their
 * intent filters (guidance-groups "Static Data Root and Split Providers", plan P-1).
 *
 * <p>It is one of the two providers the artifact is split into; {@link MopData} is the other, and
 * holds the specification targets. The STATIC members read this model only. The MOP members that
 * need the WTG or the components read it too, and read {@code MopData} for the targets. Nothing in
 * this model is a MOP fact, which is what lets the STATIC group run on the same artifact with MOP
 * off.
 *
 * <p>The queries answer from the artifact the run loaded, which is parsed once per run.
 */
public final class StaticGuiModel {

    private final MopData source;

    /**
     * @param source the loaded artifact the queries answer from
     */
    public StaticGuiModel(MopData source) {
        this.source = source;
    }

    /** The analysed app's package, which the component trigger addresses its intents to. */
    public String packageName() {
        return source.getPackageName();
    }

    /** The analysed app's main activity. */
    public String mainActivity() {
        return source.getMainActivity();
    }

    /** Whether the artifact carries at least one WTG click edge. */
    public boolean hasWtgData() {
        return source.hasWtgData();
    }

    /**
     * The WTG click edges out of {@code activity}, or an empty list.
     *
     * @param activity the activity class name
     */
    public List<WtgTransition> wtgTransitions(String activity) {
        return source.getWtgTransitions(activity);
    }

    /**
     * The static metadata of the widget {@code shortId} in {@code activity}, or null when the
     * artifact lists no such widget.
     *
     * @param activity the activity class name
     * @param shortId the widget's resource-id entry name ({@link MopData#extractShortId})
     */
    public WidgetMeta widget(String activity, String shortId) {
        MopData.Widget widget = source.getWidget(activity, shortId);
        return widget == null ? null : new WidgetMeta(widget.idName, widget.hint, widget.inputType,
                widget.entries, widget.prompt, widget.spinnerMode, widget.contentDescription,
                widget.tooltipText);
    }

    /** The manifest's receivers, with their intent filters. */
    public List<ComponentInfo.ReceiverInfo> receivers() {
        return source.getReceivers();
    }

    /** The manifest's services, with their intent filters. */
    public List<ComponentInfo.ServiceInfo> services() {
        return source.getServices();
    }

    /** The manifest's activities, in manifest order. */
    public List<ComponentInfo.ActivityInfo> activities() {
        return source.getActivities();
    }

    /** The manifest's providers. */
    public List<ComponentInfo.ProviderInfo> providers() {
        return source.getProviders();
    }

    /** Whether the manifest lists any component. */
    public boolean hasComponents() {
        return source.hasComponents();
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
                    : Collections.unmodifiableList(new java.util.ArrayList<>(entries));
            this.prompt = prompt;
            this.spinnerMode = spinnerMode;
            this.contentDescription = contentDescription;
            this.tooltipText = tooltipText;
        }
    }
}
