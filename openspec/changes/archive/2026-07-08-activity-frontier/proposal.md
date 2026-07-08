# activity-frontier

## Why

The cmpft2 depth analysis (`rvsec/rv-android/docs/20260707_verificacao_mecanismos_cmpft2.md` §8) showed the real depth problem: activity coverage is a middling median 66.7% (mean 66.5%; 72/219 apps reach 100%, 5 reach 0), but navigation stays shallow — the median run traverses only **2 distinct activities** (mean 3.8, p75=5, max 22), so apps with up to 22 activities are explored barely two screens deep. SATA optimizes unvisited *actions*, not unreached *activities*: nothing rewards the widget that opens a new screen, and the direct component-invocation machinery is dormant (`componentPercentage=0.0`, `activityTriggerEnabled=false`).

The components investigation (`ape/docs/20260707_investigacao_components_triggering.md`) established: (a) `MopData` already parses 100% of the manifest `components{}` section, including `exported`, deep-link `data{}` blocks (88 filters in 23/37 sampled apps) and permissions — the data is there, unconsumed; (b) the gh11 "-45pp sandwichroulette" evidence that keeps activity triggering off is outdated — it measured *unfiltered, per-step-probabilistic* `startActivity()` jumps on a pre-gh57/gh13 schema, a mechanism that no longer exists; (c) `reachesTarget` is the wrong filter for depth — it is producer-broken on obfuscated apps (B1/B8), while `exported`/`intentFilters`/`data{}` come straight from the manifest and are immune to that break.

## What Changes

Two complementary levers, GUI-first with a direct-launch fallback:

- **(A) Activity-frontier boost** (GUI path, always on when data exists): the existing WTG scoring pass in `StatefulAgent.adjustActionsByGUITree` additionally boosts widgets whose static WTG transition targets an **unvisited** activity (`Graph.getActivityNode(target) == null`), by new `Config.frontierBoostWeight` (`ape.frontierBoostWeight`, default 200; 0 = off). Independent of MOP reachability; stacks with the existing `mopWeightWtg` boost when both apply. New trace line `[APE-RV] Frontier boost: ...`.
- **(B) Stagnation-triggered activity launch** (fallback when the GUI path stalls): when exploration stagnates (the existing `graphStableCounter` reaching `graphStableRestartThreshold / 2` — same signal the LLM stagnation hook uses; evaluated after the LLM hooks so an enabled LLM takes precedence), the agent selects a manifest activity that is **exported, un-permission-gated, same-package and currently unvisited** (round-robin) and returns a first-class `EVENT_TRIGGER_ACTIVITY` action as the step — mirroring the `EVENT_RESTART` pattern: a real `[APE-STEP]` with `decision_source=Component` (resurrecting the currently dead enum value), no graph edge label (EVENT_* types are not edge labels), event generation dispatches `AndroidDevice.startActivity` with an explicit component intent — or an `ACTION_VIEW` deep-link intent when the activity's intent-filter carries a `data{}` scheme. A successful trigger resets `graphStableCounter`.
- **BREAKING (P3)**: activities leave the probabilistic `componentPercentage` trigger pool entirely (the old `activityTriggerEnabled` branch in `buildTriggerTuples` is deleted); `Config.activityTriggerEnabled` is repurposed as the gate for the stagnation launcher and its default flips to **true** — the gh11 rationale for `false` measured a mechanism this change deletes; the new launcher is stagnation-gated, manifest-filtered, and model-visible. Rollback: `ape.activityTriggerEnabled=false` (B off) and `ape.frontierBoostWeight=0` (A off).
- Receivers/services/providers keep the existing probabilistic side-effect path unchanged (still gated by `componentPercentage`, default 0.0).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `wtg-navigation`: add the frontier (unvisited-activity) boost requirement alongside the existing MOP-reach WTG boost.
- `component-triggering`: add the stagnation-triggered activity launch requirement; activity triggering via the probabilistic pool is removed (supersedes the "Activities are excluded" clause of INV-CT-03 — activities are now handled exclusively by the stagnation launcher, never by the per-step probabilistic pool).
- `exploration`: `ActionType Classification` gains the non-model `EVENT_TRIGGER_ACTIVITY` constant (`requireTarget()=false`, `isModelAction()=false`, not a graph edge label).

## Impact

- **Code**: `MopScorer`/`StatefulAgent` (frontier term in the WTG pass), `SataAgent.selectNewActionNonnull` (stagnation launcher after the LLM hooks), `ActionType` + `Action` subclass + `MonkeySourceApe` event-generation case (EVENT_RESTART template), `StatefulAgent.buildTriggerTuples` (delete the activity branch), `Config.java` (one new flag + one default flip + P4 comments).
- **Behavior**: widgets that open new screens win roulette weight; when the graph stalls, the agent jumps once to an unvisited exported activity instead of only restarting; deep-linked activities (88 filters/23 apps in the sample) become reachable with correct URIs.
- **Telemetry**: `[APE-RV] Frontier boost:` lines; `[APE-STEP] ... decision_source=Component` (first-ever occurrences); `[APE-RV] Triggering activity:` retains its existing line shape at dispatch.
- **Interactions**: no conflict with `foreign-activity-guard` (launched activities are same-package, `shouldModel` passes); the launcher yields to the LLM stagnation hook when LLM is enabled. **Not arm-neutral:** both levers are inert without `mopDataPath` (Lever A rides the `_mopData != null && hasWtgData()`-gated WTG pass; Lever B is `getMopData() != null`-gated), so default-on they activate only in MOP arms. Any non-MOP baseline arm MUST set `ape.frontierBoostWeight=0` AND `ape.activityTriggerEnabled=false` so the two arms differ only in what is being measured.
- **Tests**: pure seams for candidate filtering/URI building/round-robin; ActionType predicate tests; scoring-pass tests for the frontier term.
- **Archive ordering**: targets main specs (`wtg-navigation`, `component-triggering`, `exploration` are all in main). No behavioral dependency on unarchived deltas. The invariant IDs are numbered to avoid collisions with invariants already merged into main: the WTG invariants are INV-WTG-06/07 (main's `wtg-navigation` spec already carries INV-WTG-04/05 from the archived `mop-parser-fidelity` change, so 06/07 is the next free pair against main's current content), and the component-triggering invariants INV-CT-05..08 (main already occupies INV-CT-04 — ComponentName Package Derivation, from the archived `experiment-validity` change — which this change's launcher also complies with).
- **Risk**: a stagnation launch can land on an activity that crashes without setup (the gh11 failure mode, now bounded: once per stagnation episode, only exported+unfiltered-by-permission, and the run continues on crash as today); frontier boost depends on WTG widget matching (resource-id based — survives R8 default obfuscation, unlike `reachesTarget`). Expectation management: in the cmpft2 corpus the WTG substrate is near-empty (WTG drives only 0.09% of decisions and static widgets = 0 in 32.4% of runs), so Lever A (frontier boost) is expected to be trace-thin there; Lever B, the manifest-derived stagnation launcher, is independent of that substrate and is the primary depth lever in this corpus.
