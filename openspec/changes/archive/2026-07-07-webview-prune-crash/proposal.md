# Proposal: webview-prune-crash

## Why

The cmpft validation run (219 APKs × 3 reps, 2026-07-05/06) surfaced a P0 defect introduced by the `exploration-effectiveness` WebView-pruning fix: an uncaught `org.w3c.dom.DOMException` thrown from `GUITreeNode.clearChildren` (via `GUITreeBuilder.checkAndRemoveWebView`) aborts the entire Monkey run. 14 traces across 7 WebView-bearing apps crashed (spell4wiki and treehouses 3/3 reps, wikipedia/owncloud/http_shortcuts 2/3, photoprism, lnaddr2invoice 1 each); the cmpds baseline had zero occurrences. The crash truncates runs mid-exploration — http_shortcuts lost 6 of 9 unique MOP violations to it — and it caps apps that were otherwise improving (spell4wiki gained +16pp cov_method even while crashing at ~87 steps).

The causal chain: INV-TREE-11 made real WebViews survive the removal threshold, so `clearChildren` now actually executes on large WebView subtrees; the INV-TREE-10 rewrite removes children exhaustively (the old forward-index loop over the live `NodeList` silently removed only half and never hit the failing node); the Android Harmony DOM (`InnerNodeImpl.removeChild`) rejects one of those removals and the exception propagates uncaught through `buildNodeAndXmlFromNodeInfo` into Monkey's fatal-error path.

## What Changes

- `GUITreeNode.clearChildren` becomes exception-safe: the existing exhaustive DOM removal loop is wrapped in a `DOMException` catch (outside the loop), so a `DOMException` from any single `removeChild` degrades to a logged partial prune (`[APE-RV] clearChildren DOM prune aborted: ...`) instead of propagating. The in-memory side (`childCount = 0`, `children = null`) is always applied. In the degraded (partial-prune) case the residual DOM children MAY re-enter the naming pass — which walks the DOM, not the in-memory child list — and become named widgets/actions for that one node, equivalent to an under-pruned WebView (the state already handled whenever the actionable count is below `ape.ignoreWebViewThreshold`); the unconditional in-memory prune is retained but is not what protects naming.
- A JVM regression test covers both halves: full removal on a well-formed element (INV-TREE-10 preserved) and non-propagation when the DOM element rejects a removal (fault-injecting `org.w3c.dom` stub).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ui-tree`: WebView pruning gains an exception-safety requirement — a DOM-level failure during `clearChildren` SHALL NOT abort the run (new INV-TREE-12); INV-TREE-10 is restated as best-effort-exhaustive under that constraint.

## Impact

- **Components**: `GUITreeNode.clearChildren` only. `GUITreeBuilder.checkAndRemoveWebView` is unchanged (its threshold logic is correct; the failure is confined to DOM removal).
- **Archive ordering**: this change MODIFIES the `WebView Pruning Correctness` requirement, which exists only in the unarchived `exploration-effectiveness` delta — the main spec (`openspec/specs/ui-tree/spec.md`) has no WebView requirement yet. This change MUST therefore be archived AFTER `exploration-effectiveness`, so the requirement it modifies is present in the main spec at archive time.
- **Experiments**: recovers full-length runs for the 7 crashing APKs in the next validation cycle; arm-neutral (same jar in every arm).
- **Risk**: degradation path leaves residual DOM children on the (rare) rejecting node. Because naming walks the DOM, that residue MAY re-enter naming and become named widgets/actions for that one node — equivalent to an under-pruned WebView, the same state the system already handles when the actionable count is below `ape.ignoreWebViewThreshold`. The degradation is bounded to one node per tree build, flagged by the WARN log line, and strictly better than the current behavior (an uncaught `DOMException` aborts the whole run).
