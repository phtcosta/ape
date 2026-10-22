## MODIFIED Requirements

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
