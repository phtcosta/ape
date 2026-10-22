# Specification: UI Tree and Widget Representation

## Purpose

APE maintains its own `GUITree` abstraction rather than operating directly on Android's `AccessibilityNodeInfo` hierarchy for two reasons. First, `AccessibilityNodeInfo` objects are live handles to the accessibility service and become invalid after the view hierarchy changes; caching them across action steps would yield stale data. `GUITree` is a plain Java snapshot — a detached, immutable record of the UI state at a specific instant — that can be stored, compared, and serialized without any coupling to the Android runtime. Second, the raw accessibility API exposes every attribute as a separate cursor query; `GUITree` flattens this into typed fields (`className`, `resourceId`, `text`, `bounds`, `clickable`, `scrollable`, `enabled`) that downstream components — `NamingFactory`, `Agent`, event generators — can read without touching the Android API.

Widget properties on `GUITreeNode` directly determine which `ModelAction`s are available for a node. A node that is `clickable` receives `MODEL_CLICK`; a node that is `scrollable` receives scroll actions whose direction is determined by `getScrollType()`. This property-to-action mapping is centralized in `resetActions()`. The strict correspondence means that the action set for any widget is fully determined by its properties, without inspecting widget state elsewhere.

Scroll direction assignment is non-trivial because Android's accessibility API does not expose a first-class "scroll direction" attribute. `getScrollType()` returns a string that matches a lower-case `ScrollType` constant (`none`, `horizontal`, `vertical`, `all`). It is `"none"` for a non-scrollable node; otherwise it infers direction from `className` — well-known vertical containers (`ScrollView`, `ListView`, `ExpandableListView`, leanback `VerticalGridView`) give `"vertical"`, well-known horizontal containers (`HorizontalScrollView`, leanback `HorizontalGridView`, the ViewPager family) give `"horizontal"` — and for any other class it falls back to the node's scroll bits: `1` → `"vertical"`, `2` → `"horizontal"`, anything else → `"all"`. A node captured from the accessibility API with `isScrollable()` true carries bits `3`, so an unknown scrollable class reads `"all"`; only `resetActions()` sets the single-direction bits. `RecyclerView` is explicitly excluded from the horizontal list because its scroll orientation is set programmatically at runtime via a `LayoutManager` and cannot be inferred from class name alone. The AndroidX ViewPager variants are recognized as horizontal only when `TREE_ENHANCEMENTS`, an ADDONS member (`ape.treeEnhancementsEnabled`, read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)`), is in the plan — the four `as*` cells; in `base` and `mop` the tree reproduces upstream APE's recognition set and upstream's WebView-pruning threshold. The `clearChildren` removal (the first-child loop, with no exception path) is the same in every cell.

`MODEL_BACK` and `MODEL_MENU` are global actions issued at the device level, not targeting any particular widget. Allowing `resetActions()` to assign either type to a widget node would corrupt the per-node action set. Both types are rejected by `resetActions()` with an `IllegalStateException`. As in upstream APE, `resetActions()` clears the node's `checkable`, `clickable`, `longClickable` and `scrollable` flags before it walks the array, so a rejected call leaves those flags cleared.

## Data Contracts

### Input

- **`GUITreeBuilder`** receives an `AccessibilityNodeInfo` root obtained through `UiAutomation`: `MonkeySourceApe.getRootInActiveWindow()` returns `mUiAutomation.getRootInActiveWindow()`, and `Model` constructs `GUITreeBuilder(namingManager, activity, rootInfo, bitmap)` from it. Each node in the accessibility tree contributes one `GUITreeNode`. Attributes read per node: class name (String), resource-id (String, nullable), text (String, nullable), content-description (String, nullable), screen bounds (Rect), clickable (boolean), long-clickable (boolean), scrollable (boolean), enabled (boolean), child count (int). From the node's extras bundle, only the two handler-stamp keys written by an APK instrumented with rv-android gh121 `--stamp-handlers`: `rvsec.click` and `rvsec.longClick` (String, nullable; the binary class name of the click and long-click handler), kept as `GUITreeNode.getHandlerClick()` / `getHandlerLongClick()` (INV-TREE-14). They are null on an unstamped APK, and on a missing bundle, a missing key, a non-string value or an extras read that throws.
- **`GUITreeNode.resetActions(ActionType[])`** accepts an array of `ActionType` values. The array MUST NOT contain `MODEL_BACK`, `MODEL_MENU`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, or `EVENT_ACTIVATE`.
- **`GUITreeWidgetDiffer`** accepts two `GUITree` instances (expected, observed) of compatible structure for diff computation.

### Output

- **`GUITreeBuilder`** produces a `GUITree` with a non-null root `GUITreeNode` and a flat node list accessible via `GUITree.getNodes()`.
- **`GUITreeNode.getScrollType()`** returns one of the String values `"none"`, `"vertical"`, `"horizontal"`, or `"all"` — the names of the lower-case enum constants `ScrollType.none`, `ScrollType.vertical`, `ScrollType.horizontal`, `ScrollType.all`, so `ScrollType.valueOf(node.getScrollType())` (as `ActionPatchNamer` does) maps it back.
- **`GUITreeNode.resetActions(ActionType[])`** mutates the receiver's internal checkable/clickable/long-clickable/scrollable flags: it clears all four, then sets clickable for `MODEL_CLICK`, long-clickable for `MODEL_LONG_CLICK`, scroll bit `1` for `MODEL_SCROLL_TOP_DOWN`/`MODEL_SCROLL_BOTTOM_UP` and bit `2` for `MODEL_SCROLL_LEFT_RIGHT`/`MODEL_SCROLL_RIGHT_LEFT`; `EVENT_NOP` is ignored. A `null` array is a no-op.
- **`GUITreeWidgetDiffer`** produces a diff object identifying added, removed, and changed widget nodes between the two trees.
- **`GUITreeTransition`** is a value object pairing a before-`GUITree` and an after-`GUITree`; it has no side effects.

### Side-Effects

- `GUITreeBuilder` may call `AndroidDevice` APIs (accessibility service, display metrics) during construction.
- `GUITreeNode.resetActions()` throws `IllegalStateException` if the type signature has already been built (the node is frozen).
- `GUITreeNode.resetActions()` throws `IllegalStateException` if the input array contains `MODEL_BACK` or `MODEL_MENU`. The flags are cleared before the array is walked (upstream behaviour), so after such a throw the node's `checkable`, `clickable`, `longClickable` and `scrollable` flags are all cleared, and any flag set by an earlier element of the array stays set.

### Error

- `GUITreeNode.resetActions()` MUST throw `IllegalStateException` when called with any of the globally blocked `ActionType` values: `MODEL_BACK`, `MODEL_MENU`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, `EVENT_ACTIVATE`.
- `GUITreeNode.resetActions()` throws `RuntimeException("Should not reach here")` for every other non-widget type it does not handle (`PHANTOM_CRASH`, `EVENT_TRIGGER_ACTIVITY`, `MODEL_LLM_TAP`).
- `GUITreeNode.resetActions()` MUST throw `IllegalStateException` when called after `typeSignature` has been set (i.e., once the node is used by the naming layer).
- `GUITreeNode.getDomNode()` MUST throw `IllegalStateException` if called before the DOM document has been attached to the tree.

## Invariants

- **INV-TREE-01**: `GUITree.getRoot()` MUST return a non-null `GUITreeNode` for any `GUITree` instance produced by `GUITreeBuilder`.
- **INV-TREE-02**: `GUITreeNode.getScrollType()` MUST return `"horizontal"` when `className` equals `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`. The two AndroidX variants (`"androidx.viewpager.widget.ViewPager"`, `"androidx.viewpager2.widget.ViewPager2"`) are additionally recognized only when `TREE_ENHANCEMENTS` is in the plan (the `as*` cells); without it (`base`, `mop`) only the legacy support-library class is recognized (upstream perception).
- **INV-TREE-03**: `GUITreeNode.getScrollType()` MUST NOT return `"horizontal"` for a node whose `className` equals `"androidx.recyclerview.widget.RecyclerView"` based on class name alone.
- **INV-TREE-04**: `GUITreeNode.resetActions()` MUST NOT assign `MODEL_BACK` or `MODEL_MENU` to any widget node; the method MUST throw `IllegalStateException` if either appears in the input array.
- **INV-TREE-05**: Every `GUITreeNode` in a tree produced by `GUITreeBuilder` MUST have screen bounds that are representable within the device's screen dimensions (no negative dimensions).
- **INV-TREE-06**: Two `GUITree` instances built from structurally identical `AccessibilityNodeInfo` hierarchies MUST produce the same structural hash, enabling non-determinism detection by `NamingFactory`.
- **INV-TREE-07**: `GUITreeNode.getScrollType()` MUST return `"none"` when `isScrollable()` returns `false`, regardless of `className`.
- **INV-TREE-08**: `GUITree.contains` SHALL never index `currentNodes` with a negative binary-search result; a negative result always yields `false`.
- **INV-TREE-09**: Every `GUITreeNode` built from an accessibility node SHALL carry that node's `isPassword()` value.
- **INV-TREE-10**: After `clearChildren` returns, the DOM element SHALL have zero children and the in-memory child list SHALL be empty, on the JVM DOM (Xerces) and on the Android DOM, in every cell.
- **INV-TREE-11**: When `TREE_ENHANCEMENTS` is in the plan, the WebView-removal threshold SHALL be evaluated over actionable descendants only; when it is not (`base`, `mop`), it is evaluated over the total descendant count (upstream over-prune).
- **INV-TREE-12**: `clearChildren` SHALL NOT catch `DOMException` and SHALL NOT log a DOM-prune abort: it removes the element's current first child while one exists, so no removal names a node that is not a child of the element. (Not gated by `TREE_ENHANCEMENTS`: it holds in every cell, `base` and `mop` included.)
- **INV-TREE-13**: After `GUITreeBuilder.release(tree)` returns, no static `GUITreeBuilder` cache (`namingToGUITreeCache`, `namingToGUITreeNodesCache`, `namingToGUITreeNodeCache`) SHALL hold an entry keyed by that tree or by any `GUITreeNode` of that tree, under any `Naming`: the sweep covers every naming key and runs before the `currentNaming == null` guard that only `Naming.release(tree)` keeps. No operation of the same release cycle SHALL query `getStateKey`/`getNodeName` for the released tree afterwards (in `StatefulAgent.checkAndRefreshNewState` the top-naming equivalence is computed before `GUITreeBuilder.release` and `Model.release`). Cache clearing MUST be decision-neutral: the caches memoize pure functions of frozen trees, so same seed ⇒ identical action sequence with and without the entries present. This is verified by the release-cycle unit tests and the recompute-equality property, not by the parity-oracle goldens, which build and release no GUITrees.
- **INV-TREE-14**: Every `GUITreeNode` built from an accessibility node SHALL carry, as `handlerClick` and `handlerLongClick`, the string values of that node's extras `rvsec.click` and `rvsec.longClick`, or null when the bundle, the key or a string value is missing, or when reading the extras throws. No other extra SHALL be read. The two fields SHALL NOT enter the node's naming attributes or its structural hash (INV-TREE-06), so the abstraction of a stamped APK is the same as that of its unstamped build.

---

## Requirements

### Requirement: GUITree Construction from AccessibilityService

`GUITreeBuilder` SHALL construct a `GUITree` from the Android `AccessibilityService` node hierarchy, producing a non-null root `GUITreeNode` and a flat node list in which each node carries the `className`, `bounds`, `clickable`, `scrollable`, and `enabled` values read from its `AccessibilityNodeInfo`.

#### Scenario: Builder constructs tree from accessibility hierarchy
- **WHEN** `GUITreeBuilder` is invoked with a valid `AccessibilityNodeInfo` root from the Android `AccessibilityService`
- **THEN** the resulting `GUITree` MUST have a non-null root `GUITreeNode` returned by `GUITree.getRoot()`
- **AND** `GUITree.getNodes()` MUST return a flat list containing every node present in the accessibility hierarchy
- **AND** each `GUITreeNode` in the list MUST carry the `className`, `bounds`, `clickable`, `scrollable`, and `enabled` values read from the corresponding `AccessibilityNodeInfo`

---

### Requirement: ViewPager Scroll Direction

Recognition of the AndroidX ViewPager class-name variants as horizontal-scroll containers SHALL be gated by `Feature.TREE_ENHANCEMENTS`, the ADDONS member activated by `ape.treeEnhancementsEnabled` and read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)`. No site SHALL read `Config.treeEnhancementsEnabled`. The ADDONS block states `ape.treeEnhancementsEnabled=true`, so the feature is in the plan of the `as`, `as_mop`, `as_llm` and `as_mop_llm` cells and absent from `base` and `mop`.

When `TREE_ENHANCEMENTS` is in the plan, `GUITreeNode.getScrollType()` SHALL recognise all three ViewPager class name variants — legacy support library and both AndroidX variants — as horizontal-scroll containers. The complete set of class names that map to `"horizontal"` for the ViewPager family is:

- `"android.support.v4.view.ViewPager"` (legacy support library)
- `"androidx.viewpager.widget.ViewPager"` (AndroidX)
- `"androidx.viewpager2.widget.ViewPager2"` (AndroidX 2)

`RecyclerView` (`androidx.recyclerview.widget.RecyclerView`) SHALL NOT be added to any explicit horizontal-class list. RecyclerView's scroll orientation is set programmatically via `LayoutManager` and cannot be inferred from class name.

When `TREE_ENHANCEMENTS` is not in the plan (the `base` and `mop` cells, or an `as*` cell with `ape.treeEnhancementsEnabled=false`), `getScrollType()` SHALL reproduce upstream APE's recognition set, which does not include the AndroidX ViewPager variants. INV-TREE-02 and INV-TREE-03 describe the behavior with the feature in the plan.

#### Scenario: Legacy support ViewPager node is recognized as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`, in any cell
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager node is recognised as horizontal (flag on)
- **WHEN** the run's preset is `as` (so `TREE_ENHANCEMENTS` is in the plan) and a `GUITreeNode` has `className` equal to `"androidx.viewpager.widget.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager2 node is recognised as horizontal (flag on)
- **WHEN** the run's preset is `as_mop` (so `TREE_ENHANCEMENTS` is in the plan) and a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager not recognised when the flag is off
- **WHEN** the run's preset is `base` or `mop` (no `ADDONS`, so `TREE_ENHANCEMENTS` is absent) and a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` (upstream perception)

#### Scenario: RecyclerView is NOT assigned horizontal scroll direction by class name
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.recyclerview.widget.RecyclerView"` and `isScrollable()` returns `true`, in any cell
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` based solely on the class name
- **AND** the actual return value MUST reflect the runtime-set scroll direction (vertical by default, or as encoded in the scrollable field)

### Requirement: Action Assignment to Widget Nodes

`GUITreeNode` action assignment SHALL be fully determined by the node's widget properties via `resetActions()`: a `clickable` node MUST receive `MODEL_CLICK`, and a `scrollable` node MUST receive scroll actions whose direction follows `getScrollType()`.

#### Scenario: Clickable widget node receives MODEL_CLICK
- **WHEN** `resetActions()` is called on a `GUITreeNode` with an array that contains `MODEL_CLICK`
- **THEN** the node's `isClickable()` flag MUST return `true` after the call
- **AND** the `MODEL_CLICK` action type MUST be available for that node in subsequent action selection

#### Scenario: Scrollable non-horizontal node receives vertical scroll actions
- **WHEN** a `GUITreeNode` has `className` equal to `"android.widget.ScrollView"` and `isScrollable()` returns `true`
- **THEN** the actions derived from this node MUST include `MODEL_SCROLL_TOP_DOWN` and `MODEL_SCROLL_BOTTOM_UP`
- **AND** the actions MUST NOT include `MODEL_SCROLL_LEFT_RIGHT` or `MODEL_SCROLL_RIGHT_LEFT`

---

### Requirement: MODEL_BACK and MODEL_MENU Exclusion from Widget Nodes

`GUITreeNode.resetActions()` enforces a blocklist of `ActionType` values that operate at the device level and MUST NOT be assigned to individual widget nodes. These are `MODEL_BACK` and `MODEL_MENU`: both are issued to the Android device without targeting a specific UI element. Assigning either to a widget node would produce an invalid per-node action set.

When `resetActions()` encounters `MODEL_BACK` or `MODEL_MENU` in its input array it MUST throw `IllegalStateException("Cannot set <type> to widget.")` when it reaches that element. As in upstream APE, the method clears the node's `checkable`, `clickable`, `longClickable` and `scrollable` flags before walking the array, so the flags are NOT preserved across the exception: after the throw they are cleared, except any flag set by an element that precedes the rejected one.

The following types are also on the blocklist and throw the same `IllegalStateException`: `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, `EVENT_ACTIVATE`. Any other type the method does not handle (`PHANTOM_CRASH`, `EVENT_TRIGGER_ACTIVITY`, `MODEL_LLM_TAP`) reaches the `default` branch and throws `RuntimeException("Should not reach here")`.

#### Scenario: resetActions() rejects MODEL_BACK
- **WHEN** `GUITreeNode.resetActions()` is called with an array containing `MODEL_BACK`
- **THEN** the method MUST throw an `IllegalStateException`
- **AND** the node's `checkable`, `clickable`, `longClickable` and `scrollable` flags MUST read cleared after the throw when `MODEL_BACK` is the array's first element (they are reset before the array is walked)

#### Scenario: resetActions() rejects MODEL_MENU
- **WHEN** `GUITreeNode.resetActions()` is called with an array containing `MODEL_MENU`
- **THEN** the method MUST throw an `IllegalStateException`
- **AND** a flag set by an element preceding `MODEL_MENU` (e.g. `clickable` from `[MODEL_CLICK, MODEL_MENU]`) MUST stay set, and every other flag MUST read cleared

### Requirement: Structural Diff Between GUI Trees

`GUITreeWidgetDiffer` SHALL compute a structural diff between a before-`GUITree` and an after-`GUITree`, identifying added, removed, and changed widget nodes, and MUST NOT report unchanged nodes as changed.

#### Scenario: GUITreeWidgetDiffer identifies changed widgets between two trees
- **WHEN** `GUITreeWidgetDiffer` is given a before-`GUITree` and an after-`GUITree` where one widget node changed its `text` property
- **THEN** the diff result MUST identify that widget node as changed
- **AND** the diff result MUST NOT report unchanged nodes as changed

#### Scenario: Identical trees produce empty diff
- **WHEN** `GUITreeWidgetDiffer` is given two `GUITree` instances built from identical `AccessibilityNodeInfo` hierarchies
- **THEN** the diff result MUST be empty (no added, removed, or changed nodes reported)

---

### Requirement: GUITree.contains Not-Found Contract

`GUITree.contains(GUITreeNode)` locates the node's `Name` in the sorted `currentNames` array via `Arrays.binarySearch` before comparing the resident node. A negative return value SHALL be treated as "absent" (`index < 0`), never tested with `index == -1`. `Model.update` calls `tree.contains(node)` on every step precisely to discard stale nodes gracefully; the previous `== -1` test turned a not-found with insertion point ≠ 0 into a negative array index (`ArrayIndexOutOfBoundsException`), converting a graceful guard into a run-aborting crash. The four sibling lookups in `GUITree` already use `index < 0`; `contains` SHALL match them.

#### Scenario: stale node absent with non-zero insertion point
- **WHEN** `GUITree.contains(node)` is called for a node whose `Name` is absent from `currentNames` and whose insertion point is greater than 0 (binarySearch returns ≤ -2)
- **THEN** `contains` SHALL return false
- **AND** no exception SHALL be thrown

#### Scenario: present node unchanged
- **WHEN** the node's `Name` is found (binarySearch returns ≥ 0)
- **THEN** behavior SHALL be identical to the previous implementation

---

### Requirement: isPassword Capture

`GUITreeBuilder.fillNode` SHALL copy `AccessibilityNodeInfo.isPassword()` into the `GUITreeNode` (`setIsPassword`), alongside the other per-node properties it already captures. The heuristic-input capability's category detection lists `node.isPassword()` as its priority-1 PASSWORD signal; without the capture the flag is constant `false` and that rule is unreachable, so real password fields fall through to keyword matching or GENERIC values, authentication flows fail, and code paths behind login (typical MOP-bearing handlers in crypto apps) are never reached. The XML serialization of the node reflects the captured value.

#### Scenario: password field captured
- **WHEN** a `GUITreeNode` is built from an `AccessibilityNodeInfo` whose `isPassword()` returns true
- **THEN** `node.isPassword()` SHALL return true
- **AND** the node's XML serialization SHALL carry `password="true"`

#### Scenario: non-password field unchanged
- **WHEN** the accessibility node's `isPassword()` returns false
- **THEN** `node.isPassword()` SHALL return false

---

### Requirement: WebView Pruning Correctness

The WebView-pruning **actionable-descendant threshold** (point 3 below) is gated by `Feature.TREE_ENHANCEMENTS`, the ADDONS member read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)`; no site SHALL read `Config.treeEnhancementsEnabled`. The feature is in the plan of the four `as*` cells and absent from `base` and `mop`. The `clearChildren` removal (points 1–2, INV-TREE-10/INV-TREE-12) is not gated by any feature, so it holds in every cell, `base` and `mop` included. WebView pruning SHALL be structurally correct on both of its halves:

1. `GUITreeNode.clearChildren` SHALL apply the in-memory prune (`childCount = 0`, `children = null`) unconditionally, before any DOM mutation, and SHALL then, when the node has a DOM element, remove every DOM child with `while (domNode.getFirstChild() != null) domNode.removeChild(domNode.getFirstChild())`. When it returns, the DOM element SHALL have zero children, on the JVM DOM (Xerces) and on the Android DOM, where `getChildNodes()` returns a copy rather than a live list.
2. `clearChildren` SHALL contain no `try`/`catch` and SHALL log nothing: there is no `DOMException` handler and no `[APE-RV] clearChildren DOM prune aborted` line. Because each removal names the element's current first child, no removal names a node that is not a child of the element.
3. `GUITreeBuilder.checkAndRemoveWebView` SHALL compare the `ape.ignoreWebViewThreshold` (default 64) against the count of **actionable** descendants only (as its inline comment always stated), not the total descendant count.

When `TREE_ENHANCEMENTS` is not in the plan, the threshold (point 3) SHALL be compared against the total descendant count (the upstream over-prune). Points 1–2 hold regardless of the plan. INV-TREE-11 describes the feature-gated threshold. INV-TREE-10 and INV-TREE-12 are plan-independent: after `clearChildren` returns, the DOM element SHALL have zero children and the in-memory child list SHALL be empty, on Xerces and on the Android DOM, in every cell (INV-TREE-10); `clearChildren` SHALL NOT catch `DOMException` and SHALL NOT log a DOM-prune abort (INV-TREE-12).

#### Scenario: clearChildren empties the DOM (flag on)
- **WHEN** `clearChildren` is called on a node whose JVM (Xerces) DOM element has 10 children, in the `as` cell and equally in the `base` cell
- **THEN** the node SHALL have 0 DOM children afterwards in both cells
- **AND** the node's in-memory child list SHALL be empty (`getChildCount() == 0`)

#### Scenario: DOM rejection does not abort the run (flag on)
- **WHEN** `clearChildren` is called, in any of the six cells, on a node whose DOM element has 10 children and returns a copy from `getChildNodes()` (the Android DOM behaviour)
- **THEN** `clearChildren` SHALL return normally with the DOM element holding 0 children
- **AND** the node's in-memory child list SHALL be empty (`getChildCount() == 0`)
- **AND** no `[APE-RV] clearChildren DOM prune aborted` line SHALL be logged

#### Scenario: content-heavy but action-sparse WebView kept (flag on)
- **WHEN** the run's preset is `as` (so `TREE_ENHANCEMENTS` is in the plan) and a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the WebView SHALL be kept (10 ≤ 64)

#### Scenario: action-heavy WebView still pruned (flag on)
- **WHEN** the run's preset is `as_mop_llm` (so `TREE_ENHANCEMENTS` is in the plan) and a WebView subtree has 80 actionable descendants, with the default threshold 64
- **THEN** the WebView SHALL be pruned
- **AND** no child SHALL remain in the pruned node's DOM element

#### Scenario: upstream over-prune reproduced when the flag is off
- **WHEN** the run's preset is `base` or `mop` (so `TREE_ENHANCEMENTS` is absent) and a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the threshold SHALL be compared against the total descendant count (110 > 64), reproducing upstream APE's over-prune (the WebView is pruned)
- **AND** no child SHALL remain in the pruned node's DOM element

### Requirement: No XPathlet Overlay Input

`GUITreeBuilder` SHALL build GUI trees exclusively from the live accessibility snapshot (plus the flag-gated perception enhancements specified elsewhere in this capability). The user-configurable XPathlet overlay — the static-initializer read of `/sdcard/ape.xpath` into a `List<XPathlet>` and every use of that list — SHALL NOT exist (owner decision D6: no arm uses it, the aperv deployment never pushes the file, and an undeclared device file silently reshaping tree construction is exactly the class of unecho'd behavioral input the run-spec capability eliminates; the main specification never covered the mechanism — this requirement records its removal explicitly). Behavior is byte-identical to the only condition ever deployed: overlay absent, empty rule list.

Note: this removes only the `/sdcard` *overlay* reader. The naming lattice's own XPath machinery (`Namelet` selectors, `Name.toXPath()`) is unrelated and untouched.

#### Scenario: legacy overlay file has no effect

- **WHEN** a legacy `/sdcard/ape.xpath` file exists on the device
- **THEN** `GUITreeBuilder` class initialization SHALL NOT open it
- **AND** tree construction SHALL be identical to a device with no such file

#### Scenario: tree construction reads only the accessibility snapshot

- **WHEN** a GUI tree is built during a run
- **THEN** its structure SHALL derive solely from the `AccessibilityNodeInfo` hierarchy and the in-jar perception logic
- **AND** no filesystem input SHALL participate in tree construction

### Requirement: GUITree Release Clears Static Naming Caches

`GUITreeBuilder` maintains three static memoization caches over immutable snapshot data: `namingToGUITreeCache` (`Naming → GUITree → StateKey`), `namingToGUITreeNodesCache` (`Naming → GUITree → Object[]`), and the per-node cache `namingToGUITreeNodeCache` (keyed `Naming → GUITree → (GUITreeNode → Name)`). All three memoize pure functions — `State.buildStateKey` and `Naming.getName` are deterministic over a frozen tree — so cache eviction can only cause recomputation of identical values, never a different answer.

`GUITreeBuilder.release(GUITree removed)` SHALL remove, in the same release cycle, every entry of all three caches that is keyed by the released tree — under **every** `Naming`, not only `removed.getCurrentNaming()`. Sweeping only the current naming would leave behind the entries that refinement probes create under candidate namings, holding the tree and its node subtree reachable after it left `State.treeHistory` (INV-TREE-13).

The static-cache sweep SHALL run unconditionally, before the existing `currentNaming == null` early return; `naming.release(removed)` keeps that guard (naming-internal structures are not swept).

Release SHALL be the last operation of the release cycle that touches the removed tree: no caller may query `getStateKey`/`getNodeName` for a tree after releasing it, because that re-inserts cache entries for a dead tree. `StatefulAgent.checkAndRefreshNewState` computes `isTopNamingEquivalent(removed, last)` before it calls `GUITreeBuilder.release(removed)` and `model.release(removed)`; the computation is pure, so its position is decision-neutral.

#### Scenario: release clears the per-node naming cache with its tree

- **WHEN** a `GUITree` with cached `getNodeName` entries (for one or more of its `GUITreeNode`s, under the current naming) is released via `GUITreeBuilder.release`
- **THEN** the per-node cache SHALL contain no entry for any node of that tree
- **AND** the two per-tree caches SHALL contain no entry for that tree

#### Scenario: release clears entries created under a non-current naming

- **WHEN** refinement probing has populated cache entries for a tree under a candidate `Naming` different from `tree.getCurrentNaming()`, and the tree is then released
- **THEN** those entries SHALL also be removed — the sweep covers every naming key present in the caches

#### Scenario: a released tree is never re-cached

- **WHEN** the instability recheck (`checkAndRefreshNewState`) removes a duplicate tree, needs its top-naming state key for the equivalence check, and releases it
- **THEN** the equivalence SHALL be computed before the release cycle runs
- **AND** after `release` returns, no subsequent operation of that cycle SHALL insert a cache entry keyed by the released tree

#### Scenario: cache eviction is recomputation-only

- **WHEN** a cache entry for a **live** tree is absent (never cached, or hypothetically evicted) and `getNodeName`/`getStateKey` is queried again
- **THEN** the recomputed value SHALL be equal to the previously cached value — the caches memoize deterministic functions of frozen snapshot data, so no decision can depend on an entry's presence

### Requirement: Handler Stamp Capture

`GUITreeBuilder.fillNode` SHALL read `AccessibilityNodeInfo.getExtras()` and copy two strings into the `GUITreeNode` it fills:

- `rvsec.click` → `handlerClick`, served by `GUITreeNode.getHandlerClick()`;
- `rvsec.longClick` → `handlerLongClick`, served by `GUITreeNode.getHandlerLongClick()`.

Each value is the binary class name gh121 wrote (`Class.getName()` form, e.g. `br.unb.cic.cryptoapp.cipher.CipherActivity$1`), copied unchanged. A field SHALL be null when `getExtras()` returns null, when the key is absent, or when the value under the key is not a string. An exception thrown while reading the extras SHALL leave both fields null and SHALL NOT propagate out of `fillNode` (design, Error Handling). The read uses `getExtras()` and `BaseBundle.getString`, both present in the framework stub `framework/classes-full-debug.jar` (checked with `javap`, design D16).

The fields sit beside `isPassword` (INV-TREE-09) and are carried wherever the node's other captured properties are. `fillNode` SHALL read no other key of the bundle. The capture is unconditional: no plan key enables or disables it, and it runs on every arm. The run reports how many captured nodes carried at least one stamp (`RUN_END` counter `stampNodes`, `event-sink`, design D23).

The `getExtras()` call inside `fillNode` is not reachable in the JVM suite, because surefire excludes the framework stubs (`classpathDependencyExcludes` in `pom.xml`). The rules applied to the bundle (string values copied, absent key, non-string value, a read that throws) are reachable: they run on the JVM over a test-side `android.os.Bundle` stub that mimics AOSP `getString`, as the `Rect` and `Log` stubs do. The scenarios are also written as an `@Ignore`d Android-runtime test beside `GUITreeBuilderPasswordTest`, and the device smoke on a stamped `cryptoapp` is the gate for the capture itself (tasks 11.3 and 17.2).

#### Scenario: stamped View node
- **WHEN** a `GUITreeNode` is built from an `AccessibilityNodeInfo` whose extras hold `rvsec.click = "com.x.Foo$1"` and `rvsec.longClick = "com.x.Foo$2"`
- **THEN** `node.getHandlerClick()` SHALL return `"com.x.Foo$1"`
- **AND** `node.getHandlerLongClick()` SHALL return `"com.x.Foo$2"`

#### Scenario: unstamped node
- **WHEN** a node is built from an `AccessibilityNodeInfo` whose `getExtras()` returns an empty bundle, or a bundle without `rvsec.click` and `rvsec.longClick` (an APK instrumented without `--stamp-handlers`)
- **THEN** `node.getHandlerClick()` and `node.getHandlerLongClick()` SHALL both return null
- **AND** every other captured property of the node SHALL be the same as without this requirement

#### Scenario: non-string value
- **WHEN** the extras hold `rvsec.click` as an integer and `rvsec.longClick` as a string `"com.x.Foo$3"`
- **THEN** `node.getHandlerClick()` SHALL return null
- **AND** `node.getHandlerLongClick()` SHALL return `"com.x.Foo$3"`
- **AND WHEN** reading the extras throws
- **THEN** both fields SHALL be null and the tree SHALL still be built

#### Scenario: Compose node with only rvsec.click
- **WHEN** a Compose node with no resource id carries extras holding only `rvsec.click = "com.x.ui.ScreenKt$Body$1$1"`, the class of its app handler lambda
- **THEN** `node.getHandlerClick()` SHALL return `"com.x.ui.ScreenKt$Body$1$1"`
- **AND** `node.getHandlerLongClick()` SHALL return null
- **AND** the node's resource id SHALL stay empty: the stamp does not stand in for an id anywhere in the tree
