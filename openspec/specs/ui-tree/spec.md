# Specification: UI Tree and Widget Representation

## Purpose

APE maintains its own `GUITree` abstraction rather than operating directly on Android's `AccessibilityNodeInfo` hierarchy for two reasons. First, `AccessibilityNodeInfo` objects are live handles to the accessibility service and become invalid after the view hierarchy changes; caching them across action steps would yield stale data. `GUITree` is a plain Java snapshot — a detached, immutable record of the UI state at a specific instant — that can be stored, compared, and serialized without any coupling to the Android runtime. Second, the raw accessibility API exposes every attribute as a separate cursor query; `GUITree` flattens this into typed fields (`className`, `resourceId`, `text`, `bounds`, `clickable`, `scrollable`, `enabled`) that downstream components — `NamingFactory`, `Agent`, event generators — can read without touching the Android API.

Widget properties on `GUITreeNode` directly determine which `ModelAction`s are available for a node. A node that is `clickable` receives `MODEL_CLICK`; a node that is `scrollable` receives scroll actions whose direction is determined by `getScrollType()`. This property-to-action mapping is centralized in `resetActions()`. The strict correspondence means that the action set for any widget is fully determined by its properties, without inspecting widget state elsewhere.

Scroll direction assignment is non-trivial because Android's accessibility API does not expose a first-class "scroll direction" attribute. `getScrollType()` infers direction from `className`: well-known horizontal containers receive `ScrollType.HORIZONTAL`; everything else defaults to `ScrollType.VERTICAL`. `RecyclerView` is explicitly excluded from the horizontal list because its scroll orientation is set programmatically at runtime via a `LayoutManager` and cannot be inferred from class name alone.

`MODEL_BACK` and `MODEL_MENU` are global actions issued at the device level, not targeting any particular widget. Allowing `resetActions()` to assign either type to a widget node would corrupt the per-node action set. Both types MUST be rejected by `resetActions()` with an `IllegalStateException`.

---

## Data Contracts

### Input

- **`GUITreeBuilder`** receives an `AccessibilityNodeInfo` root obtained from the Android `AccessibilityService` via `AndroidDevice.getCurrentGUITree()`. Each node in the accessibility tree contributes one `GUITreeNode`. Attributes read per node: class name (String), resource-id (String, nullable), text (String, nullable), content-description (String, nullable), screen bounds (Rect), clickable (boolean), long-clickable (boolean), scrollable (boolean), enabled (boolean), child count (int).
- **`GUITreeNode.resetActions(ActionType[])`** accepts an array of `ActionType` values. The array MUST NOT contain `MODEL_BACK`, `MODEL_MENU`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, or `EVENT_ACTIVATE`.
- **`GUITreeWidgetDiffer`** accepts two `GUITree` instances (expected, observed) of compatible structure for diff computation.

### Output

- **`GUITreeBuilder`** produces a `GUITree` with a non-null root `GUITreeNode` and a flat node list accessible via `GUITree.getNodes()`.
- **`GUITreeNode.getScrollType()`** returns one of the String values `"none"`, `"vertical"`, `"horizontal"`, or `"all"`. (The enum constant names `ScrollType.HORIZONTAL` / `ScrollType.VERTICAL` correspond to these string values in the implementation.)
- **`GUITreeNode.resetActions(ActionType[])`** mutates the receiver's internal scrollable/clickable/long-clickable flags to match the supplied array.
- **`GUITreeWidgetDiffer`** produces a diff object identifying added, removed, and changed widget nodes between the two trees.
- **`GUITreeTransition`** is a value object pairing a before-`GUITree` and an after-`GUITree`; it has no side effects.

### Side-Effects

- `GUITreeBuilder` may call `AndroidDevice` APIs (accessibility service, display metrics) during construction.
- `GUITreeNode.resetActions()` throws `IllegalStateException` if the type signature has already been built (the node is frozen).
- `GUITreeNode.resetActions()` throws `IllegalStateException` if the input array contains `MODEL_BACK` or `MODEL_MENU`; the node's flags MUST NOT be modified after such a throw.

### Error

- `GUITreeNode.resetActions()` MUST throw `IllegalStateException` when called with any of the globally blocked `ActionType` values: `MODEL_BACK`, `MODEL_MENU`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, `EVENT_ACTIVATE`.
- `GUITreeNode.resetActions()` MUST throw `IllegalStateException` when called after `typeSignature` has been set (i.e., once the node is used by the naming layer).
- `GUITreeNode.getDomNode()` MUST throw `IllegalStateException` if called before the DOM document has been attached to the tree.

---

## Invariants

- **INV-TREE-01**: `GUITree.getRoot()` MUST return a non-null `GUITreeNode` for any `GUITree` instance produced by `GUITreeBuilder`.
- **INV-TREE-02**: `GUITreeNode.getScrollType()` MUST return `"horizontal"` when `className` equals `"android.support.v4.view.ViewPager"`, `"androidx.viewpager.widget.ViewPager"`, or `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`.
- **INV-TREE-03**: `GUITreeNode.getScrollType()` MUST NOT return `"horizontal"` for a node whose `className` equals `"androidx.recyclerview.widget.RecyclerView"` based on class name alone.
- **INV-TREE-04**: `GUITreeNode.resetActions()` MUST NOT assign `MODEL_BACK` or `MODEL_MENU` to any widget node; the method MUST throw `IllegalStateException` if either appears in the input array.
- **INV-TREE-05**: Every `GUITreeNode` in a tree produced by `GUITreeBuilder` MUST have screen bounds that are representable within the device's screen dimensions (no negative dimensions).
- **INV-TREE-06**: Two `GUITree` instances built from structurally identical `AccessibilityNodeInfo` hierarchies MUST produce the same structural hash, enabling non-determinism detection by `NamingFactory`.
- **INV-TREE-07**: `GUITreeNode.getScrollType()` MUST return `"none"` when `isScrollable()` returns `false`, regardless of `className`.
- **INV-TREE-08**: `GUITree.contains` SHALL never index `currentNodes` with a negative binary-search result; a negative result always yields `false`.
- **INV-TREE-09**: Every `GUITreeNode` built from an accessibility node SHALL carry that node's `isPassword()` value.
- **INV-TREE-10**: After `clearChildren` returns without a logged DOM abort, the DOM element SHALL have zero children; the in-memory child list SHALL be empty in every case.
- **INV-TREE-11**: The WebView-removal threshold SHALL be evaluated over actionable descendants only.
- **INV-TREE-12**: `clearChildren` SHALL NOT propagate `DOMException` to its caller; a rejected removal degrades to a logged partial DOM prune.

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

`GUITreeNode.getScrollType()` SHALL recognise all three ViewPager class name variants — legacy support library and both AndroidX variants — as horizontal-scroll containers. The complete set of class names that map to `"horizontal"` for the ViewPager family is:

- `"android.support.v4.view.ViewPager"` (legacy support library)
- `"androidx.viewpager.widget.ViewPager"` (AndroidX)
- `"androidx.viewpager2.widget.ViewPager2"` (AndroidX 2)

`RecyclerView` (`androidx.recyclerview.widget.RecyclerView`) SHALL NOT be added to any explicit horizontal-class list. RecyclerView's scroll orientation is set programmatically via `LayoutManager` and cannot be inferred from class name.

#### Scenario: Legacy support ViewPager node is recognized as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"android.support.v4.view.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager node is recognised as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.viewpager.widget.ViewPager"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: AndroidX ViewPager2 node is recognised as horizontal
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST return `"horizontal"`

#### Scenario: RecyclerView is NOT assigned horizontal scroll direction by class name
- **WHEN** a `GUITreeNode` has `className` equal to `"androidx.recyclerview.widget.RecyclerView"` and `isScrollable()` returns `true`
- **THEN** `getScrollType()` MUST NOT return `"horizontal"` based solely on the class name
- **AND** the actual return value MUST reflect the runtime-set scroll direction (vertical by default, or as encoded in the scrollable field)

---

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

When `resetActions()` encounters `MODEL_BACK` or `MODEL_MENU` in its input array it MUST throw `IllegalStateException` immediately. The node's `clickable`, `longClickable`, and `scrollable` flags MUST remain unchanged after the exception.

The following types also remain on the blocklist: `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, `EVENT_ACTIVATE`.

#### Scenario: resetActions() rejects MODEL_BACK
- **WHEN** `GUITreeNode.resetActions()` is called with an array containing `MODEL_BACK`
- **THEN** the method MUST throw an `IllegalStateException`
- **AND** the node's action flags MUST NOT be modified

#### Scenario: resetActions() rejects MODEL_MENU
- **WHEN** `GUITreeNode.resetActions()` is called with an array containing `MODEL_MENU`
- **THEN** the method MUST throw an `IllegalStateException`
- **AND** the node's `clickable`, `longClickable`, and `scrollable` flags MUST NOT be modified

---

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

WebView pruning SHALL be structurally correct on both of its halves, and its DOM half SHALL be exception-safe:

1. `GUITreeNode.clearChildren` SHALL remove **all** DOM children in the normal case, iterating until the element is empty. The in-memory prune (`childCount = 0`, `children = null`) SHALL be applied unconditionally, before any DOM mutation.
2. If the DOM element rejects a removal with `org.w3c.dom.DOMException`, `clearChildren` SHALL stop the DOM removal loop, log one line — `[APE-RV] clearChildren DOM prune aborted: <exception message>` — and return normally. The exception SHALL NOT propagate to the caller (INV-TREE-12).
3. `GUITreeBuilder.checkAndRemoveWebView` SHALL compare the `ape.ignoreWebViewThreshold` (default 64) against the count of **actionable** descendants only (as its inline comment always stated), not the total descendant count. Counting all descendants made virtually every real WebView exceed the bar on non-actionable nodes, so legitimate web content was discarded and never explored.

#### Scenario: clearChildren empties the DOM
- **WHEN** `clearChildren` is called on a node with 10 DOM children
- **THEN** the node SHALL have 0 DOM children afterwards

#### Scenario: DOM rejection does not abort the run
- **WHEN** `clearChildren` is called on a node whose DOM element throws `DOMException` on the third `removeChild` call
- **THEN** `clearChildren` SHALL return normally (no exception reaches the caller)
- **AND** the node's in-memory child list SHALL be empty (`getChildCount() == 0`)
- **AND** one `[APE-RV] clearChildren DOM prune aborted` line SHALL be logged

#### Scenario: content-heavy but action-sparse WebView kept
- **WHEN** a WebView subtree has 100 non-actionable descendants and 10 actionable ones, with `ape.ignoreWebViewThreshold=64`
- **THEN** the WebView SHALL be kept (10 ≤ 64)

#### Scenario: action-heavy WebView still pruned
- **WHEN** a WebView subtree has 80 actionable descendants, with the default threshold
- **THEN** the WebView SHALL be pruned, and in the normal (non-degraded) case no phantom child SHALL remain in the DOM
