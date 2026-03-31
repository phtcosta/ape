package com.android.commands.monkey.ape.utils;

import java.util.Collections;
import java.util.List;

/**
 * Base class for Android component MOP data parsed from the static analysis JSON's
 * components{} section. Each subclass represents one of the four Android component types.
 */
public class ComponentInfo {

    public final String className;
    public final List<String> actions;
    public final boolean reachesMop;

    protected ComponentInfo(String className, List<String> actions, boolean reachesMop) {
        this.className = className;
        this.actions = actions != null ? Collections.unmodifiableList(actions) : Collections.<String>emptyList();
        this.reachesMop = reachesMop;
    }

    /** Receiver: triggered via am broadcast. */
    public static class ReceiverInfo extends ComponentInfo {
        public ReceiverInfo(String className, List<String> actions) {
            super(className, actions, true);
        }
    }

    /** Service: triggered via am startservice. */
    public static class ServiceInfo extends ComponentInfo {
        public ServiceInfo(String className, List<String> actions) {
            super(className, actions, true);
        }
    }

    /** Activity: can be launched directly via intent. */
    public static class ActivityInfo extends ComponentInfo {
        public ActivityInfo(String className, List<String> actions) {
            super(className, actions, true);
        }
    }

    /** ContentProvider: triggered via content query/insert/update/delete. */
    public static class ProviderInfo extends ComponentInfo {
        public final String authorities;

        public ProviderInfo(String className, String authorities) {
            super(className, Collections.<String>emptyList(), true);
            this.authorities = authorities != null ? authorities : "";
        }
    }
}
