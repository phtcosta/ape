# Proposal: experiment-validity

## Why

Seven independent code audits of APE-RV (docs/analise_claude_fable5.md, analise_claude_opus48.md, analise_claude_sonnet5.md, analise_deepseek.md, analise_gemini.md, analise_gpt5.md, analise_mimo.md, synthesized 2026-07-02) converged on a set of defects that do not merely lower exploration quality — they **distort what the experiments measure**. Visit counters silently inflate on every naming refinement, the `-s` seed does not govern agent decisions, a wedged app can burn an entire run producing zero actions, a crash loses the model and coverage dump, a hot-path binary search can abort a run, and the MOP arm can silently degrade to pure SATA when its static-analysis JSON fails to load — the exact failure class that already invalidated one experiment round (build-skew incident). Each defect was adversarially re-verified against this worktree's code before inclusion here.

## What Changes

- **Model rebuild becomes count-preserving**: remove the unconditional `edge.visitedCount++` in `Graph.rebuildHistory()` (double-counts on top of `markVisited` during transition re-add) and reset `ActivityNode` visit counters before rebuild replays them. Two rebuilds of the same history yield the same counts.
- **Agent decisions honor the Monkey seed**: `RandomHelper` (38 decision call sites: priority roulettes, `toss`, fuzzing) switches from non-seedable `ThreadLocalRandom` to a `Random` seeded from Monkey's `-s`. Two runs with the same seed produce the same decision sequence.
- **`waitForActivity` gets a give-up path**: bounded wait (counter over the 100 ms throttle loop in `checkAppActivity`) after which APE clears the wait and relaunches the app under test, instead of throttling forever.
- **`tearDown` runs in `finally`**: model serialization, coverage dump, and timeline are written even when the exploration loop dies with a RuntimeException.
- **Binary-search not-found checks fixed**: `GUITree.contains()` and `Naming.select()` test `index < 0` instead of `index == -1` (a not-found at any insertion point other than 0 currently indexes with a negative value — AIOOBE on the per-step `Model.update` path).
- **MOP data load is validated and loud**: the production call site passes the runtime package and main activity to `MopData.load` (making the existing `mopStrictPackageMatch` check reachable), and load emits one `[APE-MOP-DATA]` status line (loaded/rejected + counters). When `ape.mopDataPath` is explicitly set and the load fails, the run aborts instead of silently continuing as pure SATA.
- **Component triggers use the real package**: `dispatchTrigger` builds the `ComponentName` from the JSON `package` field already parsed into `MopData`, instead of deriving it by truncating the component class name (wrong for any component living in a subpackage).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `model`: the Model Rebuild requirement gains count-preservation (idempotent rebuild — visit counters reflect history, not the number of refinements).
- `naming`: `Naming.select` not-found contract (negative binary-search results mean "absent", never a match).
- `ui-tree`: `GUITree.contains` not-found contract (negative binary-search results are "absent", never an index).
- `exploration`: seeded reproducibility of agent decisions; bounded `waitForActivity`; output persistence guaranteed on abnormal termination.
- `mop-guidance`: `MopData.load` receives runtime package/mainActivity, emits a load-status line, and fail-fast applies when `mopDataPath` is set; the callerless 1-arg `load` overload is deleted.
- `component-triggering`: `ComponentName` package derivation comes from the parsed JSON `package` field.

## Impact

- **Components**: `Graph`/`Model` (rebuild), `RandomHelper` (+ `MonkeySourceApe`/`Monkey` seed wiring), `MonkeySourceApe` (`checkAppActivity`), `Monkey` (teardown placement), `GUITree`, `Naming`, `StatefulAgent` (load call site, `dispatchTrigger`), `MopData` (load signature use + status line).
- **Experiments**: BREAKING for cross-run comparability of visit-count-derived trace fields (counts were previously inflated; post-change traces are not comparable to pre-change traces on those fields). Seeded runs become reproducible for the first time — enables variance-controlled arm comparisons.
- **Arms**: all fixes are arm-neutral (same jar in both arms); the MOP-load gate only affects runs with `ape.mopDataPath` set, where silent degradation is precisely what must not happen.
- **No producer/JSON contract change; no new config flags** except none — fail-fast is implied by `mopDataPath` being set.
