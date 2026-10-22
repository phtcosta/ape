## Purpose

APE maintains its own `GUITree` abstraction rather than operating directly on Android's `AccessibilityNodeInfo` hierarchy for two reasons. First, `AccessibilityNodeInfo` objects are live handles to the accessibility service and become invalid after the view hierarchy changes; caching them across action steps would yield stale data. `GUITree` is a plain Java snapshot — a detached, immutable record of the UI state at a specific instant — that can be stored, compared, and serialized without any coupling to the Android runtime. Second, the raw accessibility API exposes every attribute as a separate cursor query; `GUITree` flattens this into typed fields (`className`, `resourceId`, `text`, `bounds`, `clickable`, `scrollable`, `enabled`) that downstream components — `NamingFactory`, `Agent`, event generators — can read without touching the Android API.

Widget properties on `GUITreeNode` directly determine which `ModelAction`s are available for a node. A node that is `clickable` receives `MODEL_CLICK`; a node that is `scrollable` receives scroll actions whose direction is determined by `getScrollType()`. This property-to-action mapping is centralized in `resetActions()`. The strict correspondence means that the action set for any widget is fully determined by its properties, without inspecting widget state elsewhere.

Scroll direction assignment is non-trivial because Android's accessibility API does not expose a first-class "scroll direction" attribute. `getScrollType()` returns a string that matches a lower-case `ScrollType` constant (`none`, `horizontal`, `vertical`, `all`). It is `"none"` for a non-scrollable node; otherwise it infers direction from `className` — well-known vertical containers (`ScrollView`, `ListView`, `ExpandableListView`, leanback `VerticalGridView`) give `"vertical"`, well-known horizontal containers (`HorizontalScrollView`, leanback `HorizontalGridView`, the ViewPager family) give `"horizontal"` — and for any other class it falls back to the node's scroll bits: `1` → `"vertical"`, `2` → `"horizontal"`, anything else → `"all"`. A node captured from the accessibility API with `isScrollable()` true carries bits `3`, so an unknown scrollable class reads `"all"`; only `resetActions()` sets the single-direction bits. `RecyclerView` is explicitly excluded from the horizontal list because its scroll orientation is set programmatically at runtime via a `LayoutManager` and cannot be inferred from class name alone. The AndroidX ViewPager variants are recognized as horizontal only when `TREE_ENHANCEMENTS`, an ADDONS member (`ape.treeEnhancementsEnabled`, read through `RunContext.current().spec().has(Feature.TREE_ENHANCEMENTS)`), is in the plan — the four `as*` cells; in `base` and `mop` the tree reproduces upstream APE's recognition set and upstream's WebView-pruning threshold. The `clearChildren` correctness fixes are always on, in every cell.

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

- **INV-TREE-13**: After `GUITreeBuilder.release(tree)` returns, no static `GUITreeBuilder` cache (`namingToGUITreeCache`, `namingToGUITreeNodesCache`, `namingToGUITreeNodeCache`) SHALL hold an entry keyed by that tree or by any `GUITreeNode` of that tree, under any `Naming`: the sweep covers every naming key and runs before the `currentNaming == null` guard that only `Naming.release(tree)` keeps. No operation of the same release cycle SHALL query `getStateKey`/`getNodeName` for the released tree afterwards (in `StatefulAgent.checkAndRefreshNewState` the top-naming equivalence is computed before `GUITreeBuilder.release` and `Model.release`). Cache clearing MUST be decision-neutral: the caches memoize pure functions of frozen trees, so same seed ⇒ identical action sequence with and without the entries present. This is verified by the release-cycle unit tests and the recompute-equality property, not by the parity-oracle goldens, which build and release no GUITrees.

## MODIFIED Requirements

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
