## Why

The known `OutOfMemoryError` risk has two verified, unequivocal defects: the per-node naming cache is never cleared (`GUITreeBuilder.namingToGUITreeNodeCache`, keyed by `GUITreeNode`, untouched by `release()` — V12), and diagnostic retainers keep entire GUI trees where IDs would do (`Model.actionHistory` → `ActionRecord` → `GUITreeAction` → full `GUITreeNode` subtree, with the TODO OOM in the code itself — V11; plus the independent retainer `ModelAction.resolvedGUITreeAction`/`resolvedTree` — V24). Anything beyond these — bounds on `Graph`'s 17 collections, `treeHistory`, or naming/refinement structures — changes scientific behavior and is explicitly deferred until profiling proves it necessary (report Sec. 6.7, rejecting day-1 LRU bounds).

This change is **stage 6 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 6.7).

## What Changes

- Clear `namingToGUITreeNodeCache` in the same cycle as the existing `release()` path (V12).
- `ActionRecord` (diagnostic history) stores IDs + a minimal snapshot instead of the resolved `GUITreeAction` → full tree — **conditional on** a prior caller audit proving no semantic path (rebuild/replay) depends on the rich objects.
- `ModelAction` releases `resolvedTree`/`resolvedGUITreeAction` beyond the last resolve (V24), under the same caller-audit condition.
- Action-sequence parity test after each retention change (same seed ⇒ same decisions) — memory fixes must be observationally neutral.
- **Explicitly out of scope**: any bound/eviction on `Graph`, `treeHistory`, or naming structures; those require a heap profile by retention root on 600 s runs first (report Sec. 6.7). OOM handling stays "process death + task FAILED in the supervisor" — no heroic catch, no serialization on a dying heap.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ui-tree`: cache lifecycle requirement — the per-node naming cache is released with its tree.
- `model`: diagnostic-history retention requirement — history holds identifiers/minimal snapshots, not resolved trees; `ModelAction` resolved-tree lifetime bounded to the last resolve.

## Impact

- **Java**: `GUITreeBuilder` (release path), `Model`/`ActionRecord`, `ModelAction`, `GUITreeAction`.
- **Python/rv-android**: none.
- **Depends on**: `rearch-01-parity-oracle` (neutrality evidence for every fix). The V12 cache release and the V24 `ModelAction` release are independent of stages 2–5. The **V11 `ActionRecord` snapshot fix is hard-blocked on stages 2 and 4**: stage 2 deletes `saveGraph`/`sataModel.obj` and stage 4 deletes `saveActionHistory`/`action-history.log`, the only consumer that re-resolves every deep record through the rich `GUITreeAction` (design "Ordering", task 1.1 verifies this and blocks group 3 if either has not landed). Ordered after the pipeline work to avoid double-churn in the same files.
- **Risk** (report Sec. 11): a "surgical" eviction touching an unmapped semantic path — mitigated by the caller audit before and sequence parity after.
- Grounding: report Sec. 6.7, verified V11/V12/V24, rejection of speculative bounds (Sec. 7).
