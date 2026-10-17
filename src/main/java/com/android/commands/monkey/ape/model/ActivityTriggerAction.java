package com.android.commands.monkey.ape.model;

/**
 * activity-frontier (Lever B) — a first-class, non-model {@link Action} representing a stagnation
 * triggered activity launch. It carries the launch package name (captured from
 * {@code StaticGuiModel.packageName()} by the launcher — never derived from the class name,
 * INV-CT-04), the target activity class name, and, when the manifest provided one, a deep-link URI.
 *
 * <p>The type is {@link ActionType#EVENT_TRIGGER_ACTIVITY}, so {@code requireTarget()} and
 * {@code isModelAction()} both return false (it is not a GUI gesture and never a graph edge label,
 * mirroring {@code EVENT_RESTART}). {@code DecisionSource} lives only on {@link ModelAction}, not on
 * this non-model {@code Action}; its {@code dec.src} is {@code launcher}, named by the stage that
 * selects it and read back from this action's type ({@code StatefulAgent.nonModelDecisionSource},
 * INV-CT-07).
 */
public class ActivityTriggerAction extends Action {

    private static final long serialVersionUID = 1L;

    private final String packageName;
    private final String className;
    /** Deep-link URI string ({@code scheme://host+path}) when the manifest provided one, else null. */
    private final String deepLinkUri;

    public ActivityTriggerAction(String packageName, String className, String deepLinkUri) {
        super(ActionType.EVENT_TRIGGER_ACTIVITY);
        this.packageName = packageName;
        this.className = className;
        this.deepLinkUri = deepLinkUri;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    /** The deep-link URI, or null when the launch should use an explicit-component intent. */
    public String getDeepLinkUri() {
        return deepLinkUri;
    }

    @Override
    public String toString() {
        return super.toString() + "@" + className + (deepLinkUri != null ? " (" + deepLinkUri + ")" : "");
    }
}
