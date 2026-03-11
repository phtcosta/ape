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

---

## Requirements

### Requirement: GUITree Construction from AccessibilityService

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

#### Scenario: GUITreeWidgetDiffer identifies changed widgets between two trees
- **WHEN** `GUITreeWidgetDiffer` is given a before-`GUITree` and an after-`GUITree` where one widget node changed its `text` property
- **THEN** the diff result MUST identify that widget node as changed
- **AND** the diff result MUST NOT report unchanged nodes as changed

#### Scenario: Identical trees produce empty diff
- **WHEN** `GUITreeWidgetDiffer` is given two `GUITree` instances built from identical `AccessibilityNodeInfo` hierarchies
- **THEN** the diff result MUST be empty (no added, removed, or changed nodes reported)
