## Purpose

Exception safety for WebView pruning. The `exploration-effectiveness` change made `GUITreeNode.clearChildren` remove all DOM children exhaustively (INV-TREE-10) and made real WebViews survive the removal threshold (INV-TREE-11). The combination exposed a latent failure mode in the Android Harmony DOM: on large real-world WebView subtrees, `InnerNodeImpl.removeChild` can reject a removal with `org.w3c.dom.DOMException`, and the exception — previously unreachable because the half-removing loop never touched the failing node — propagates uncaught through `GUITreeBuilder.buildNodeAndXmlFromNodeInfo` into Monkey's fatal-error path and aborts the whole run. The cmpft run (2026-07-05/06) recorded 14 such aborts across 7 WebView-bearing apps; the pre-fix baseline recorded zero.

This delta makes the DOM half of `clearChildren` best-effort: removal remains exhaustive in the normal case, but a `DOMException` on any single removal degrades to a logged partial prune instead of killing the run. The in-memory half (`childCount = 0`, `children = null`) is unconditional. In the degraded case the residual DOM children MAY re-enter the naming pass — naming walks the DOM (via `getChildNodes()`), not the in-memory child list, and runs after the prune — and become named widgets/actions for that one node. This is equivalent to an under-pruned WebView, the same state the system already handles whenever the actionable count is below `ape.ignoreWebViewThreshold`; the degradation is bounded to one node per tree build and flagged by a log line for diagnosis. It is strictly better than the current behavior, where an uncaught `DOMException` aborts the whole run.

## MODIFIED Requirements

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

## Invariants

- **INV-TREE-10**: After `clearChildren` returns without a logged DOM abort, the DOM element SHALL have zero children; the in-memory child list SHALL be empty in every case.
- **INV-TREE-11**: The WebView-removal threshold SHALL be evaluated over actionable descendants only.
- **INV-TREE-12**: `clearChildren` SHALL NOT propagate `DOMException` to its caller; a rejected removal degrades to a logged partial DOM prune.
