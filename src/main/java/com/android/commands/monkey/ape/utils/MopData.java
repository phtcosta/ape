package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.StaticGuiModel.WtgTransition;

/**
 * The specification targets of the analysed app, read-only: per widget and per handler class the
 * MOP flags and distance pairs of each event, the per-activity distances, the MOP activity set,
 * the OPTIONSMENU gateways, and which components reach a monitored operation.
 *
 * <p>It is one of the two providers {@link StaticArtifactLoader} splits the format-2 artifact into
 * (guidance-groups "Static Data Root and Split Providers", plan P-1); {@link StaticGuiModel} is the
 * other, and holds the WTG, the widget metadata, the package, the main activity and the
 * components. Only the MOP members read this model; they read {@code StaticGuiModel} for the WTG
 * and the components.
 *
 * <h3>Naming boundary: the wire speaks {@code MOP}</h3>
 *
 * The static analyser generalized its output to any target method set, so its documents speak the
 * neutral word <em>Target</em> ({@code reachesTarget}, {@code directlyReachesTarget},
 * {@code targetMethods}) while aperv, an exclusively JavaMOP consumer, speaks <em>MOP</em>. That
 * translation happens host-side: the artifact generator is the one component that reads the
 * analyser's document, so it performs the rename and ships {@code mop}, {@code mopActivities} and
 * {@code reachesMop} (design D7, relocated by D2). One {@code Target} name survives on the wire,
 * {@code hasTargetMethods}, the generator's own compaction of a component's signature list, which
 * no reader consumes: the component trigger reads no MOP data (component-triggering INV-CT-16).
 *
 * <h3>Reader, not parser</h3>
 *
 * Every parse-time semantic the on-device parser used to perform — the listener-handler ×
 * reachability cross-reference, the synthetic-lambda recovery, the collision policy, the empty-id
 * drop, the DIALOG re-keying, the A′ union — runs host-side in the generator. The loader decodes
 * precomputed values into the lookup structures this class serves and derives exactly two things:
 * the OPTIONSMENU gateway set ({@link #recomputeMopOptionsMenus}), because it depends on which
 * MOP-activity set the run's flag selects (INV-MOP-35, design D3), and a widget's click aggregate.
 */
public class MopData {

    /** Map: base activity class name → (shortResourceId → widget flags and pairs). */
    private final Map<String, Map<String, Widget>> widgetData;
    /** The selected MOP activity set (INV-MOP-27), keyed by base activity. */
    private final Set<String> mopActivities;
    /** Activities whose OPTIONSMENU is a MOP gateway (T1.2, D13). */
    private final Set<String> activitiesWithMopOptionsMenu;
    /** Class names of the components whose wire {@code reachesMop} is true. */
    private final Set<String> componentsReachingMop;

    /** The shared empty answer of every distance accessor: no pair. Callers read only. */
    static final int[][] NO_PAIRS = new int[0][];

    /**
     * Length of the producer's target list (format 2 {@code targets}, design D15): the bound every
     * stored pair index lies below. 0 when the artifact has no target.
     */
    private final int targetCount;
    /** Map: base activity → distance pairs of the activity's class and widgets (D15, D20). */
    private final Map<String, int[][]> activityDist;
    /** Map: handler binary class name → its flags and pairs (format 2 {@code handlers}, D15). */
    private final Map<String, Handler> handlers;

    /**
     * Count of MOP-flagged widgets the generator dropped for having no resource id (INV-MOP-20).
     * Observability only — echoed from the artifact's {@code stats}, never recomputed here.
     */
    private final int droppedFlaggedNoId;

    /** Built by {@link StaticArtifactLoader} and by the test factories; every argument non-null. */
    MopData(Map<String, Map<String, Widget>> widgetData, Set<String> mopActivities,
            Set<String> activitiesWithMopOptionsMenu, Set<String> componentsReachingMop,
            int targetCount,
            Map<String, int[][]> activityDist, Map<String, Handler> handlers,
            int droppedFlaggedNoId) {
        this.widgetData = widgetData;
        this.mopActivities = mopActivities;
        this.activitiesWithMopOptionsMenu = activitiesWithMopOptionsMenu;
        this.componentsReachingMop = componentsReachingMop;
        this.targetCount = targetCount;
        this.activityDist = activityDist;
        this.handlers = handlers;
        this.droppedFlaggedNoId = droppedFlaggedNoId;
    }

    // -------------------------------------------------------------------------
    // Test factories — build a MopData from pre-built structures, bypassing the wire.
    // -------------------------------------------------------------------------

    /**
     * Builds a MopData directly from the query structures, for tests about the <em>query</em> layer
     * rather than about the reader: scoring, frontier and trigger suites state the widget map and
     * the activity set they want instead of authoring an artifact that derives to it.
     *
     * <p>Public rather than package-private because its callers are not: the scoring and agent
     * suites live in {@code ape.agent} and {@code ape.agent.scoring}. It builds no gateway set —
     * a caller that needs one goes through {@link StaticArtifactLoader}, which is where the
     * recompute runs.
     */
    public static MopData forTest(Map<String, Map<String, Widget>> widgetData,
            Set<String> mopActivities) {
        return new MopData(
                widgetData != null ? widgetData : new HashMap<String, Map<String, Widget>>(),
                mopActivities != null ? mopActivities : new HashSet<String>(),
                new HashSet<String>(),
                new HashSet<String>(),
                0, new HashMap<String, int[][]>(), new HashMap<String, Handler>(), 0);
    }

    // -------------------------------------------------------------------------
    // Load-time derivations
    // -------------------------------------------------------------------------

    /**
     * The OPTIONSMENU gateway set (INV-MOP-13), recomputed on-device because condition 2 reads the
     * <em>selected</em> MOP-activity set and the selection is a run flag. An activity qualifies
     * when its own menu holds a flagged widget, or when any click edge out of that base activity
     * lands on a member of {@code mopActivities}. The WTG is the static model's
     * ({@link StaticGuiModel#wtgTransitions}, INV-WTG-05). Pure: reads its three arguments and
     * nothing else.
     */
    static Set<String> recomputeMopOptionsMenus(Map<String, Boolean> optionsMenus,
            StaticGuiModel staticModel, Set<String> mopActivities) {
        Set<String> gateways = new HashSet<>();
        for (Map.Entry<String, Boolean> record : optionsMenus.entrySet()) {
            String activity = record.getKey();
            if (Boolean.TRUE.equals(record.getValue())) {
                gateways.add(activity);
                continue;
            }
            for (WtgTransition t : staticModel.wtgTransitions(activity)) {
                if (mopActivities.contains(t.targetActivity)) {
                    gateways.add(activity);
                    break;
                }
            }
        }
        return gateways;
    }

    /**
     * The pairs of the events a click fires, merged by the minimum per target (design D17 as
     * amended 2026-10-09): every event of {@code byEvent} but the long-press events and
     * {@code scroll} ({@link #NOT_FIRED_BY_A_CLICK}). With one such event its stored array is
     * returned as is; with none, {@link #NO_PAIRS}.
     */
    static int[][] clickAggregate(Map<String, int[][]> byEvent) {
        int[][] aggregate = NO_PAIRS;
        for (Map.Entry<String, int[][]> e : byEvent.entrySet()) {
            if (!NOT_FIRED_BY_A_CLICK.contains(e.getKey())) {
                aggregate = aggregate.length == 0 ? e.getValue()
                        : mergeByMinimum(aggregate, e.getValue());
            }
        }
        return aggregate;
    }

    /**
     * The normalized events a click does not fire, which the click aggregate leaves out: the
     * long-press events and {@code scroll} (design D17 as amended 2026-10-09).
     */
    private static final Set<String> NOT_FIRED_BY_A_CLICK = new HashSet<>(Arrays.asList(
            normalizeEventType("longClick"), normalizeEventType("itemLongClick"),
            normalizeEventType("implicitCreateContextMenu"), normalizeEventType("scroll")));

    /**
     * Two pair lists merged by the minimum distance per target, sorted by distance and then index —
     * the order the wire gives. The one merge rule of the distances: it merges two wire event keys
     * that normalize onto one event (which a conforming generator, normalizing itself, never emits)
     * and the events of a widget's click aggregate (design D17 as amended 2026-10-09).
     */
    static int[][] mergeByMinimum(int[][] a, int[][] b) {
        Map<Integer, Integer> best = new HashMap<>();
        for (int[][] list : new int[][][]{a, b}) {
            for (int[] p : list) {
                Integer prev = best.get(p[0]);
                if (prev == null || p[1] < prev) {
                    best.put(p[0], p[1]);
                }
            }
        }
        List<int[]> merged = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : best.entrySet()) {
            merged.add(new int[]{e.getKey(), e.getValue()});
        }
        Collections.sort(merged, (x, y) -> x[1] != y[1]
                ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]));
        return merged.toArray(new int[0][]);
    }

    /**
     * Canonicalize an {@code eventType} token so producer snake_case and consumer
     * camelCase forms of the same event compare equal (INV-MOP-08): lowercase and
     * strip separators, so {@code long_click} and {@code longClick} both map to
     * {@code longclick}. Returns null for null. Applied on both the map-building
     * side (JSON keys) and the query side (Widget.isDirectMop/isTransitiveMop).
     */
    static String normalizeEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(eventType.length());
        for (int i = 0; i < eventType.length(); i++) {
            char c = eventType.charAt(i);
            if (c == '_' || c == '-') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * The MOP flags and pairs of a widget, or null if the artifact lists none. Package-visible:
     * production reads a node's MOP facts through {@link #lookup}, and a widget's static metadata
     * through {@link StaticGuiModel#widget}.
     */
    Widget getWidget(String activity, String shortId) {
        Map<String, Widget> widgets = widgetData.get(activity);
        if (widgets == null) return null;
        return widgets.get(shortId);
    }

    /**
     * The number of targets every distance pair indexes (format 2 {@code targets}, design D15). 0
     * for an artifact with no target, which loads and runs without distance data.
     */
    public int targetCount() {
        return targetCount;
    }

    /**
     * The distance pairs {@code {targetIndex, distance}} of a base activity (format 2
     * {@code activityDist}, design D15; read by the launcher, D20), in wire order, or an empty
     * array when the activity has none. The array is the stored one; callers read only.
     */
    public int[][] activityDist(String activity) {
        int[][] pairs = activity == null ? null : activityDist.get(activity);
        return pairs != null ? pairs : NO_PAIRS;
    }

    /**
     * The {@code handlers} record of a binary class name, or null when the table does not list the
     * class (design D15). For the stamp-first lookup (D17), which falls back to the id key on null.
     */
    public Handler getHandler(String binaryClassName) {
        return binaryClassName == null ? null : handlers.get(binaryClassName);
    }

    /**
     * What MOP knows about one node for one event (mop-guidance "Handler Stamp Lookup", design
     * D17, INV-MOP-36). Every reader of MOP flags or distances for a node goes through here.
     *
     * <ol>
     *   <li><b>Stamp.</b> For {@code click} and {@code longClick} only, the node's stamp for that
     *       event ({@link GUITreeNode#getHandlerClick()} answers the wire key {@code click},
     *       {@link GUITreeNode#getHandlerLongClick()} the wire key {@code longclick}), when
     *       {@code handlers} lists its class: that class's record for the event, source
     *       {@code STAMP}, even when it reaches nothing. The record is read for the event alone —
     *       an event missing from it reads as {@code none} with no pair, with no aggregate fallback.
     *   <li><b>Id key.</b> Otherwise the widget of {@code (activity, shortId)}: its flags for the
     *       event with today's per-event → aggregate fallback (INV-MOP-14), and its pairs under the
     *       event's key, source {@code ID} — except that a click on a widget whose {@code mop} map
     *       has no {@code click} key reads the widget's {@linkplain Widget#clickAggregate() click
     *       aggregate} (amended 2026-10-09). Every other event, a null one included, reads its own
     *       key alone.
     *   <li>Otherwise {@link Lookup#NONE}.
     * </ol>
     *
     * <p>A stamp naming a class {@code handlers} does not list (a library dispatcher such as
     * {@code AppCompatViewInflater$DeclaredOnClickListener}) falls back to the id key. Never null;
     * pure — it reads the artifact and the node, never the retirement state.
     *
     * @param eventType the event as {@code MopScorer.eventTypeOf} spells it; null skips the stamp
     *                  and reads the id key's aggregate flags with no pair
     */
    public Lookup lookup(String activity, GUITreeNode node, String eventType) {
        if (node == null) {
            return Lookup.NONE;
        }
        String event = normalizeEventType(eventType);
        String stamp = stampFor(node, event);
        Handler stamped = getHandler(stamp);
        if (stamped != null) {
            return new Lookup(stamped.isDirectMopForEvent(event),
                    stamped.isTransitiveMopForEvent(event), stamped.dist(event),
                    Lookup.Source.STAMP, stamp);
        }
        Widget widget = getWidget(activity, extractShortId(node.getResourceID()));
        if (widget != null) {
            // A click on a widget with no click key reads the click aggregate: the flags'
            // condition (key absence; an explicit key, none included, blocks it), not their scope.
            boolean clickAggregate = EVENT_CLICK.equals(event)
                    && !widget.directMopByEventType.containsKey(event);
            return new Lookup(widget.isDirectMop(event), widget.isTransitiveMop(event),
                    clickAggregate ? widget.clickAggregate() : widget.dist(event),
                    Lookup.Source.ID);
        }
        return Lookup.NONE;
    }

    /**
     * The node's stamp for a normalized event, or null: gh121 stamps click and long-click handlers
     * only, so every other event (and a null one) has no stamp to consult.
     */
    private static String stampFor(GUITreeNode node, String normalizedEvent) {
        if (EVENT_CLICK.equals(normalizedEvent)) {
            return node.getHandlerClick();
        }
        if (EVENT_LONG_CLICK.equals(normalizedEvent)) {
            return node.getHandlerLongClick();
        }
        return null;
    }

    /** The normalized wire keys of the two stamped events (D17: {@code click}, {@code longclick}). */
    private static final String EVENT_CLICK = normalizeEventType("click");
    private static final String EVENT_LONG_CLICK = normalizeEventType("longClick");

    /** True if the (base) activity is in the selected MOP activity set. */
    public boolean activityHasMop(String activity) {
        return mopActivities.contains(activity);
    }

    /**
     * The selected MOP activity set (INV-MOP-27) — the membership the launcher orders by. Backed
     * by the live set (not a copy); callers read only. Distinct from the per-activity
     * {@link #activityHasMop} query so the pure launcher seam can take it as a parameter.
     */
    public Set<String> getMopActivities() {
        return mopActivities;
    }

    /** Count of MOP-flagged widgets the generator dropped for lacking a resource id (INV-MOP-20). */
    public int getDroppedFlaggedNoId() {
        return droppedFlaggedNoId;
    }

    /** True if the activity's OPTIONSMENU is a MOP gateway (T1.2, D13). */
    public boolean activityHasMopOptionsMenu(String activity) {
        return activitiesWithMopOptionsMenu.contains(activity);
    }

    /**
     * The wire's {@code reachesMop} for the component of that class name: whether it reaches a
     * monitored operation. False for a class the artifact does not list.
     */
    public boolean reachesMop(String className) {
        return componentsReachingMop.contains(className);
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
    // POJOs (all nested under MopData — D1/D2)
    // -------------------------------------------------------------------------

    /**
     * The MOP side of one addressable widget of the wire's {@code widgets} map: through
     * {@link MopEntry}, the MOP flags scoring reads and the format-2 distance pairs, plus the click
     * aggregate. The widget's static metadata is {@link StaticGuiModel.WidgetMeta}. Every widget of
     * the wire map has an entry here, a metadata-only one with empty maps, so a lookup by its id
     * key answers from source {@code ID}.
     */
    public static class Widget extends MopEntry {
        /** See {@link #clickAggregate()}; set once at load. */
        int[][] clickAggregate = NO_PAIRS;

        /**
         * The widget's click aggregate (mop-guidance "Handler Stamp Lookup", design D17 as amended
         * 2026-10-09): its pairs of every event a click fires, every event but
         * {@code longclick}, {@code itemlongclick}, {@code implicitcreatecontextmenu} and
         * {@code scroll}, merged by the minimum per target ({@link MopData#mergeByMinimum}) and
         * in wire order. {@link MopData#lookup} reads it for a click when the widget's {@code mop}
         * map has no {@code click} key. Never null; the array is the stored one, callers read only.
         */
        public int[][] clickAggregate() {
            return clickAggregate;
        }
    }

    /**
     * What the artifact knows about one entry, per normalized event: the MOP flags of its
     * {@code mop} map and the distance pairs of its {@code dist} map. Both entry kinds of format 2
     * carry this pair of maps under the same keys — a {@link Widget} of the {@code widgets} map
     * and a {@link Handler} of the {@code handlers} table — so they decode through one reader and
     * answer through one set of accessors.
     */
    public static class MopEntry {
        // Decoded from the wire's per-eventType `mop` map — INV-MOP-17.
        public boolean directMop;
        public boolean transitiveMop;
        public final Map<String, Boolean> directMopByEventType = new HashMap<>();
        public final Map<String, Boolean> transitiveMopByEventType = new HashMap<>();
        /**
         * Decoded from the wire's per-eventType {@code dist} map (format 2, design D15): normalized
         * event → pairs {@code {targetIndex, distance}}, in wire order (ascending distance, then
         * index; the generator keeps every target at {@code d <= 3}, and the loader reads any
         * length). Every stored index lies in {@code [0, targetCount())}: a
         * malformed pair is dropped at load and counted ({@code MOP_DATA.droppedPairs}).
         * Callers read only.
         */
        public final Map<String, int[][]> distByEventType = new HashMap<>();

        /**
         * The distance pairs {@code {targetIndex, distance}} for the given event type alone, or an
         * empty array: an event with no {@code dist} entry has no pair here. Unlike the flags, this
         * accessor has no aggregate fallback; the one aggregate of pairs, a widget's click
         * aggregate, is {@link Widget#clickAggregate()}, which {@link MopData#lookup} chooses. The
         * array is the stored one; callers read only.
         */
        public int[][] dist(String eventType) {
            String key = normalizeEventType(eventType);
            int[][] pairs = key == null ? null : distByEventType.get(key);
            return pairs != null ? pairs : NO_PAIRS;
        }

        /** Direct-MOP flag for the given event type, falling back to the aggregate (match-any). */
        public boolean isDirectMop(String eventType) {
            return flag(directMopByEventType, eventType, directMop);
        }

        /** Transitive-MOP flag for the given event type, falling back to the aggregate. */
        public boolean isTransitiveMop(String eventType) {
            return flag(transitiveMopByEventType, eventType, transitiveMop);
        }

        /**
         * Direct-MOP flag for the given event type alone: an event with no key reads as
         * {@code none}. The stamp step of {@link MopData#lookup} reads a handler record this way
         * (design D17), because the aggregate of a handler class would answer an event its stamp
         * never named.
         */
        public boolean isDirectMopForEvent(String eventType) {
            return flag(directMopByEventType, eventType, false);
        }

        /** Transitive-MOP flag for the given event type alone; see {@link #isDirectMopForEvent}. */
        public boolean isTransitiveMopForEvent(String eventType) {
            return flag(transitiveMopByEventType, eventType, false);
        }

        /** The per-event flag when the map has the event's key, else {@code absent}. */
        private static boolean flag(Map<String, Boolean> byEvent, String eventType,
                boolean absent) {
            String key = normalizeEventType(eventType);
            if (key != null && byEvent.containsKey(key)) {
                return Boolean.TRUE.equals(byEvent.get(key));
            }
            return absent;
        }
    }

    /**
     * One record of the format-2 {@code handlers} table: the flags and distance pairs of a handler
     * class, keyed on the wire by its binary class name, the form gh121's stamp and
     * {@code reachability[].className} use (design D15). A listed class whose {@code mop} is
     * {@code none} and that carries no pair is kept on purpose: it states that this handler
     * reaches no target, which the stamp-first lookup relies on (D17).
     */
    public static class Handler extends MopEntry {
        public final String className;

        public Handler(String className) {
            this.className = className;
        }
    }

    /**
     * The answer of {@link MopData#lookup} for one node and one event (design D17, API Design
     * "Part B: {@code MopData.Lookup}"): the direct and transitive flags, the distance pairs
     * {@code {targetIndex, distance}} (possibly empty, never null) and where they came from.
     * Immutable; {@link #dist} is the artifact's stored array, which callers read only.
     */
    public static final class Lookup {

        /** Which step of the lookup answered. */
        public enum Source {
            /** The node's handler stamp named a class {@code handlers} lists. */
            STAMP,
            /** The id key {@code (activity, shortId)} resolved a widget. */
            ID,
            /** Neither: no flag and no pair. */
            NONE
        }

        /** The lookup that found nothing. */
        public static final Lookup NONE = new Lookup(false, false, NO_PAIRS, Source.NONE);

        public final boolean direct;
        public final boolean transitive;
        public final int[][] dist;
        public final Source source;
        /** The stamped handler's binary class name when {@code source} is STAMP; null otherwise. */
        public final String handler;

        Lookup(boolean direct, boolean transitive, int[][] dist, Source source) {
            this(direct, transitive, dist, source, null);
        }

        Lookup(boolean direct, boolean transitive, int[][] dist, Source source, String handler) {
            this.direct = direct;
            this.transitive = transitive;
            this.dist = dist != null ? dist : NO_PAIRS;
            this.source = source;
            this.handler = handler;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Lookup{").append(source)
                    .append(direct ? ",direct" : "").append(transitive ? ",transitive" : "");
            for (int[] pair : dist) {
                sb.append(",[").append(pair[0]).append(',').append(pair[1]).append(']');
            }
            return sb.append('}').toString();
        }
    }
}
