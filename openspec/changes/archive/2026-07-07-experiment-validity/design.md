# Design: experiment-validity

## Context

Seven independent audits (docs/analise_*.md, synthesized 2026-07-02) identified defects that distort experiment measurements rather than merely lowering exploration quality. Every item below was adversarially re-verified against this worktree's source before inclusion. This change groups the seven verified fixes because they share one rationale: **a measurement taken by APE-RV must reflect what actually happened on the device, and a run must be reproducible and always produce its outputs**.

Current state (verified, file:line):

1. `Graph.rebuildHistory()` (`Graph.java:1285-1293`) self-assigns `firstVisitTimestamp`/`lastVisitTimestamp` (no-op) and runs an unconditional `edge.visitedCount++` per tree-transition — on top of the `visitedCount++` already applied by `markVisited` when `Model.rebuild` re-adds each transition (`Model.java:272` → `Graph.java:429` → `GraphElement.java:63`). `ActivityNode` counters are preserved across rebuild and then replayed (`Graph.java:427→588→600`). Every naming refinement therefore inflates edge and activity visit counts; refined (typically complex, MOP-bearing) screens look "hot" and are deprioritized by `greedyPickLeastVisited`, saturation, and trivial-activity heuristics.
2. `RandomHelper.getRandom()` (`RandomHelper.java:27`) returns `ThreadLocalRandom.current()` — non-seedable. The `-s` seed feeds only `Monkey.mRandom` (`Monkey.java:697`). 38 `RandomHelper.*` call sites drive agent decisions (`randomPickWithPriority` at `SataAgent.java:1072`, `randomPick`, `toss`, all of `ApeFuzzer`). No run is reproducible.
3. `MonkeySourceApe.checkAppActivity()` (`MonkeySourceApe.java:1190-1194`): while `waitForActivity` is true and the foreground package is not allowed, each `getNextEvent()` enqueues a 100 ms throttle and returns — no counter, no timeout, no relaunch. The block/restart path (`:1204-1206`) is only reachable when `waitForActivity` is false. A wedged app consumes the entire `--running-minutes` budget with zero actions.
4. `Monkey.java:771-782`: only `runMonkeyCycles()` is inside the `try`; the `finally` restores rotation only. `tearDown()` (model serialization, coverage dump, timeline — `MonkeySourceApe.java:202-212`) sits after the block and is skipped when a RuntimeException propagates.
5. `GUITree.contains()` (`GUITree.java:283-287`) tests `Arrays.binarySearch(...) == -1` then indexes `currentNodes[index]`; any not-found with insertion point ≠ 0 yields index ≤ -2 → AIOOBE on the per-step `Model.update` path. The four sibling call sites in the same file use `< 0` correctly. `Naming.select()` (`Naming.java:438`) has the identical `== -1` pattern on `Collections.binarySearch` — a missing namelet with insertion point ≠ 0 is treated as present (wrong abstraction chosen, no crash).
6. `StatefulAgent.java:162` calls the 1-arg `MopData.load(path)`, which delegates `load(path, null, null)` — the package/mainActivity mismatch checks (`MopData.java:237,242`) and `mopStrictPackageMatch` (`:247`) are unreachable. On any load failure the method returns null with only `wprintln` warnings and the agent silently runs as pure SATA. This silent-degradation class already invalidated one experiment round (build-skew incident, docs/20260622_investigacao_mop.md).
7. `StatefulAgent.dispatchTrigger` (`StatefulAgent.java:1099-1102`) derives the package as `className.substring(0, lastDot)` — wrong for any component in a subpackage (e.g. `br.unb.app.receivers.MyReceiver` → package `br.unb.app.receivers`). All three trigger kinds (broadcast/service/activity) route through it. The correct package is already parsed: `MopData.getPackageName()` (`MopData.java:665`, from the JSON `package` field).

Constraints: P1 (smallest fix that makes the measurement honest), P3 (no compatibility shims — inflated-count behavior is deleted, not flagged), no producer/JSON change, no new config flags.

## Architecture

No new components. Seven local fixes in existing classes:

### Key Components

| Component | Responsibility | Change |
|-----------|---------------|--------|
| `Graph.rebuildHistory()` | Replay transition history after refinement | Delete unconditional `visitedCount++` and dead self-assignments; reset `ActivityNode` visit counters before replay |
| `RandomHelper` | RNG for all agent decisions | Backing RNG becomes a `Random` seeded from Monkey's `-s` via one static `seed(long)` call at startup |
| `MonkeySourceApe.checkAppActivity()` | Foreground-package policing | Count consecutive wait iterations; on threshold, clear wait and relaunch app |
| `Monkey.runMonkeyCycles` caller | Run lifecycle | Move `tearDown()` into the existing `finally` |
| `GUITree.contains()` / `Naming.select()` | Hot-path lookups | `== -1` → `< 0` |
| `StatefulAgent` (constructor, `:162`) | MOP data load | Call 3-arg `load(path, packageName, mainActivity)`; abort on null when `mopDataPath` set |
| `MopData.load()` | Parse static-analysis JSON | Emit one `[APE-MOP-DATA]` status line (success and failure) |
| `StatefulAgent.dispatchTrigger()` | Component triggering | `ComponentName(_mopData.getPackageName(), c.className)` |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-MODEL-11 rebuild count-preserving | `Graph.rebuildHistory`, `Model.rebuild` | Unit: build graph, record counts, force two rebuilds, assert counts unchanged |
| INV-EXPL-14 seeded reproducibility | `RandomHelper.seed(long)` + wiring in `MonkeySourceApe` ctor | Unit: seed twice, assert identical sequences from `randomPick`/`toss` |
| INV-EXPL-15 bounded waitForActivity | `MonkeySourceApe.checkAppActivity` | Device-validated (no host-side Android runtime) |
| INV-EXPL-16 teardown on abnormal exit | `Monkey.java` try/finally | Device-validated (inspect outputs after induced crash) |
| INV-TREE-08 negative binarySearch = absent | `GUITree.contains` | Unit: tree without target node whose insertion point ≠ 0 → `contains` false, no throw |
| INV-NAME-13 negative binarySearch = absent | `Naming.select` | Unit: namelet absent with insertion point ≠ 0 → not selected |
| INV-MOP-21 load status line | `MopData.load` | Unit: assert `[APE-MOP-DATA]` fields on success/failure fixtures |
| INV-MOP-22 fail-fast when mopDataPath set | `StatefulAgent` constructor | Unit: null load + configured path → StopTestingException |
| INV-CT-04 ComponentName package from JSON | `StatefulAgent.dispatchTrigger` | Unit: subpackaged component → ComponentName package equals JSON `package` |

## Goals / Non-Goals

**Goals:**
- Visit counts reflect history, not the number of refinements (idempotent rebuild).
- Same `-s` ⇒ same agent decision sequence.
- No run can silently produce zero actions (wedge) or zero outputs (crash before teardown).
- No run in the `sata_mop` arm can silently be a `sata` run.
- Hot-path binary searches cannot abort a run or select a wrong namelet on not-found.
- Component triggers reach subpackaged components.

**Non-Goals:**
- No change to what rebuild replays (source-only marking, target-only states — known, separate).
- No redesign of saturation, theta, `isStrong`, or `RefinementResult` (high-risk items, deferred to their own ablations).
- No retry/queue semantics for component triggers beyond the package fix.
- No new config flags; the wait threshold is a constant.

## Decisions

1. **Seed wiring: static `RandomHelper.seed(long)` called once from `MonkeySourceApe`'s constructor with the Monkey seed** — over passing `mRandom` through 38 call sites (invasive) or seeding `ThreadLocalRandom` (impossible). The backing field becomes a plain `java.util.Random`. Thread-safety note: agent decisions run on the event-source thread; `ApeFuzzer` runs on the same thread. `java.util.Random` is internally thread-safe regardless.
2. **Rebuild fix: delete the extra increment + reset ActivityNode counters before replay** — over snapshotting/restoring counts (P3: the inflated behavior is simply wrong; replay already recounts correctly through `markVisited`).
3. **waitForActivity: counter + relaunch via the existing blocked-activity path** — reuse `onActivityBlocked`/`startRandomMainApp` machinery already used by the `waitForActivity == false` branch; threshold constant 100 iterations (~10 s at 100 ms throttle). No flag (P1).
4. **Fail-fast semantics: `mopDataPath` set ⇒ load failure aborts the run** (StopTestingException) — over a warn-only default. The operator setting the path *is* the declaration that this run is a MOP-arm run; running it as SATA produces a silently mislabeled arm, the worst possible outcome for the experiment. `mopStrictPackageMatch` keeps governing whether a package mismatch counts as failure (warn-only by default, unchanged).
5. **One `[APE-MOP-DATA]` line, both outcomes** — success: `status=loaded package=<pkg> windows=<n> widgets=<n> flagged=<n> droppedNoId=<n> transitions=<n>`; failure: `status=rejected reason=<...>`. Single line, stdout via `Logger` (never logcat — hard project constraint).

## Data Flow

Unchanged. All fixes are local to existing flows.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| MOP JSON unreadable/incomplete/mismatched | `MopData.load` | `[APE-MOP-DATA] status=rejected reason=…` + abort when `mopDataPath` set | Operator fixes the JSON/path; rerun |
| Foreground package never allowed | `checkAppActivity` | After 100 wait iterations: log, clear wait, relaunch app | Automatic |
| RuntimeException in event loop | `Monkey` | Propagates as today, but `tearDown()` now runs in `finally` first | Outputs preserved for diagnosis |
| binarySearch not-found | `GUITree.contains`/`Naming.select` | Return false / skip namelet | N/A (correct behavior) |

## Risks / Trade-offs

- [Post-change traces not count-comparable with pre-change traces] → document in the experiment protocol; all §7.5 arms run the same jar, so within-experiment comparisons are unaffected.
- [Seeding changes decision streams vs. historical runs] → same mitigation: comparisons are within-experiment.
- [Relaunch-after-wedge may mask genuinely broken app launches] → the wedge log line (`[APE-RV] waitForActivity exceeded N cycles, relaunching`) makes occurrences countable post-hoc.
- [Fail-fast turns previously "successful" (but mislabeled) runs into failed runs] → intended; the pipeline surfaces them instead of averaging them into the arm.

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit | rebuild idempotence; RandomHelper determinism; binarySearch contracts; load status line; fail-fast; ComponentName derivation | JVM tests (existing suite conventions: `GraphTest`-style fixtures, `MopDataTest`, `StatefulAgent` helpers where testable without Android runtime) |
| Device | waitForActivity relaunch; teardown-on-crash | Standalone RVSec AVD validation per CLAUDE.md |

## Open Questions

- OQ1: wait threshold 100 iterations (~10 s) — validate on device that slow-but-healthy launches (cold start) stay comfortably below it; raise the constant if a legitimate cold start approaches it.
