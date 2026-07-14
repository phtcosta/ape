# Design: webview-prune-crash

## Context

`GUITreeNode.clearChildren` (src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:551-563) prunes a WebView node on both sides of the dual representation: the in-memory `GUITreeNode` child list and the mirrored `org.w3c.dom` element. The naming pass consumes the DOM side (`Naming.namingInternal` BFS-walks `Element.getChildNodes()` and maps Elements back to `GUITreeNode`s), so the DOM prune — not the in-memory reset — is what keeps pruned children out of naming/actions; the XML serialization also reads the DOM. The exploration-effectiveness change replaced the half-removing forward-index loop with an exhaustive `while (childNodes.getLength() > 0) removeChild(childNodes.item(0))` loop. On device, the Android Harmony DOM (`org.apache.harmony.xml.dom.InnerNodeImpl.removeChild:181`) rejects one of these removals on large real-world WebView subtrees with `org.w3c.dom.DOMException`. The call chain — `checkAndRemoveWebView` (GUITreeBuilder.java:472) ← `buildNodeAndXmlFromNodeInfo` (GUITreeBuilder.java:459) — has no handler, so the exception reaches Monkey's fatal-error path and aborts the run. Evidence: 14 aborted traces / 7 apps in cmpft, 0 in cmpds; stack identical in all 14.

The exact Harmony-side trigger (parent/document identity check at line 181) is not reproducible on the JVM, whose Xerces DOM accepts the same sequence — so the fix must be correct by construction (exception containment), not dependent on pinpointing the device-side DOM state that provokes the rejection.

## Architecture

Single-method change. `clearChildren` is restructured so the in-memory prune is unconditional and the DOM prune is best-effort:

```
clearChildren()
├── childCount = 0; children = null          // unconditional — in-memory list always cleared (NOT the naming input; see Risks)
└── if domNode != null:
    └── try:                                 // try/catch OUTSIDE the loop (see below)
        │   NodeList childNodes = domNode.getChildNodes();       // existing loop, unchanged
        │   while (childNodes.getLength() > 0)
        │       domNode.removeChild(childNodes.item(0));
        └── catch DOMException → Logger.wformat("[APE-RV] clearChildren DOM prune aborted: %s", e)
                                  // partial DOM prune; return normally
```

The try/catch MUST sit **outside** the loop. A catch inside the loop would re-fetch the same failing child on the next iteration and spin forever if `removeChild` throws without mutating the child list.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `GUITreeNode.clearChildren` | Prune in-memory children unconditionally; empty DOM element best-effort | — | mutated node; log line on DOM rejection |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-TREE-10 (exhaustive removal, normal case) | existing removal loop in `clearChildren` | `GUITreeWebViewPruneTest.clearChildrenEmptiesEvenChildCount` / `clearChildrenEmptiesOddChildCount` (existing, kept green) |
| INV-TREE-12 (no propagation) | `catch (DOMException)` around the loop | `GUITreeWebViewPruneTest.domRejectionDoesNotPropagate` (new, fault-injecting Element stub) |
| In-memory prune unconditional | field resets before DOM loop | assertion in `domRejectionDoesNotPropagate` (`getChildCount() == 0`) |

## Goals / Non-Goals

**Goals:**
- A DOM-level rejection during WebView pruning never aborts the run.
- The in-memory tree is always fully pruned (unconditional model-side prune).
- The degraded path is observable (one WARN-level line per occurrence).

**Non-Goals:**
- Root-causing the Harmony `InnerNodeImpl` rejection (not reproducible off-device; the containment fix is correct regardless of the trigger).
- Broad exception handling around GUITree building (`buildGUITree` call sites) — containment at the throwing method is sufficient for the observed failure class and keeps the blast radius minimal (P1).
- Retrying or repairing the DOM residue in the degraded case.

## Decisions

1. **Catch at `clearChildren`, not at the `checkAndRemoveWebView` or `buildNodeAndXmlFromNodeInfo` call sites.** The method owns the DOM mutation; catching where the mutation happens keeps every caller safe (there are two: WebView pruning and `GUITreeNode.removeChild` is separate) and avoids blanket handlers that would mask unrelated build failures. Alternative rejected: wrapping the whole tree build in try/catch — hides genuine defects and violates P1.
2. **Keep the existing removal loop verbatim; wrap it in the try/catch.** The exploration-effectiveness loop (`while (childNodes.getLength() > 0) domNode.removeChild(childNodes.item(0))`) is already exhaustive and correct; rewriting it to `getFirstChild()` would be a cosmetic change that adds churn without behavioral gain. The only structural requirement is that the try/catch sit **outside** the loop — a catch inside would re-fetch the same failing child and spin forever if `removeChild` throws without mutating the child list.
3. **Catch `DOMException` specifically, not `Throwable`.** The observed failure is a `DOMException`; catching wider would swallow programming errors (NPEs) that should fail loudly in testing.
4. **In-memory prune before the DOM loop.** The model side must be clean even when the DOM side degrades; ordering makes that unconditional rather than depending on catch placement.

## API Design

### `clearChildren() -> void`

- **Precondition**: none (callable on any node; `domNode` may be null).
- **Postcondition (always)**: `getChildCount() == 0`, in-memory `children == null`.
- **Postcondition (normal)**: `domNode` has zero children.
- **Postcondition (degraded)**: `domNode` retains the not-yet-removed children; exactly one `[APE-RV] clearChildren DOM prune aborted: <msg>` line logged; no `DOMException` propagates.

## Data Flow

Unchanged: `buildNodeAndXmlFromNodeInfo` → `checkAndRemoveWebView` → `clearChildren`. Only the failure path changes (exception absorbed at the leaf instead of propagating to Monkey).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `DOMException` | `domNode.removeChild` (Harmony `InnerNodeImpl`) | Stop DOM loop, log WARN, return normally | None — in-memory side already pruned; the residual DOM children on this node persist until the next tree build and MAY re-enter naming as an under-pruned WebView (bounded to one node) |

## Risks / Trade-offs

- [Degraded case leaves residual DOM children on the rejecting node] → because naming walks the DOM (not the in-memory child list) and runs after the prune, that residue MAY re-enter naming and become named widgets/actions for that one node — equivalent to an under-pruned WebView, the same state the system already handles when the actionable count is below `ape.ignoreWebViewThreshold`. Bounded to the single rejecting node per tree build and flagged by the log line; strictly better than the pre-fix behavior, where the uncaught `DOMException` aborts the whole run. The unconditional in-memory prune is retained but is not what protects naming.
- [Catching may hide a systematic Harmony incompatibility] → the WARN line keeps frequency measurable in traces; if the next validation run shows it firing broadly (not just on the 7 known apps), escalate to a root-cause investigation.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | INV-TREE-10 normal removal; INV-TREE-12 containment + unconditional in-memory prune | Xerces document for the normal case; fault-injecting `org.w3c.dom.Element` stub whose `removeChild` throws after N calls | 2 (1 existing kept, 1 new) |
| Device (E2E) | No `DOMException` aborts on the 7 known crashing APKs | Next cmpft-protocol validation run (crash count in traces: expected 0) | corpus |

## Open Questions

None.
