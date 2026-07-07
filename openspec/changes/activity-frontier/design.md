# Design — activity-frontier

## Context

Depth problem (cmpft2 §8): activity coverage is median 66.7% (mean 66.5%; 72/219 apps at 100%, 5 at 0), but navigation stays shallow — the median run traverses only **2 distinct activities** (mean 3.8, p75=5, max 22). Apps with up to 22 activities are explored two-deep. SATA has no signal rewarding "this widget opens a new screen", and the only direct-invocation machinery is (a) dormant and (b) wrong-shaped for depth: the old activity branch was per-step-probabilistic, filtered by producer-broken `reachesTarget`, invisible to the model (side-effect, no `[APE-STEP]`, `DecisionSource.Component` never assigned), and never used the parsed deep-link `data{}`.

Building blocks already in place (verified in `ape/docs/20260707_investigacao_components_triggering.md`):
- `MopData` parses `components{}` fully: `exported`, `permission`, `intentFilters[].{actions,categories,data{schemes,hosts,paths,...}}` (`MopData.java:589-671`, `ComponentInfo.java`).
- WTG scoring pass: `StatefulAgent.java:1442-1465` boosts widgets whose WTG transition targets a MOP-reachable activity (`MopScorer.scoreWtg`, resource-id matching, `Config.mopWeightWtg`).
- Stagnation signal: `StatefulAgent.graphStableCounter` (`:128`); the LLM stagnation hook fires at `graphStableCounter == graphStableRestartThreshold / 2` (`SataAgent.java:394-400`).
- First-class non-GUI step template: `EVENT_RESTART` (`ActionType.java:31`; the real dispatch is the `generateEventsForActionInternal` switch, `case EVENT_RESTART` at `MonkeySourceApe.java:841` — note `:618` is `validateResolvedAction(ModelAction)`, a validation switch, not dispatch); per the exploration spec, EVENT_* types are **not graph edge labels** — which is exactly the attribution-safe semantics a launch step needs (no orphan edge possible).
- `AndroidDevice.startActivity` (`:509-531`, reflective, Q+ signature) and the trigger log-line helpers (`StatefulAgent.java:1104-1106`).

## Architecture

Lever A lives entirely inside the existing WTG scoring pass. Lever B is a new early-return in `SataAgent.selectNewActionNonnull` (after the LLM hooks) returning a new `Action` subtype that `MonkeySourceApe` knows how to dispatch.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.frontierBoostWeight` | flag `ape.frontierBoostWeight` (default 200; 0 = off) | properties | int |
| frontier term in the WTG pass | add weight when transition target is unvisited | WTG match + `Graph.getActivityNode(target)` | priority += weight |
| `Config.activityTriggerEnabled` (repurposed) | gate for the stagnation launcher (default flips to true) | properties | boolean |
| `ActionType.EVENT_TRIGGER_ACTIVITY` | new non-model constant (`requireTarget()=false`, `isModelAction()=false`) | — | — |
| `ActivityTriggerAction extends Action` | carries target `className` + optional deep-link URI string | candidate | action instance |
| launcher block in `selectNewActionNonnull` | stagnation check + candidate pick + return action | `graphStableCounter`, MopData activities, Graph | `ActivityTriggerAction` or fall-through |
| candidate seams (static, pure) | filter + round-robin + URI building | `List<ComponentInfo>`, visited-set, rr-index | candidate / URI |
| dispatch case in `MonkeySourceApe` | build intent (explicit or ACTION_VIEW) + `AndroidDevice.startActivity` | `ActivityTriggerAction` | launch + throttle |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-WTG-06 (frontier boost only for unvisited targets, via `wtgBoost`) | own `getWtgTransitions` loop + unvisited check, `setWtgBoost` | scoring-pass test with stub graph |
| INV-WTG-07 (weight 0 = off; stacks with mopWeightWtg into `wtgBoost`) | guard + additive `wtgBoost` term | disabled/stacking tests |
| INV-CT-05 (launcher fires only at the stagnation point, once per episode) | equality check on `graphStableCounter` + reset on success | seam test on the gate predicate |
| INV-CT-06 (candidate = exported ∧ permission==null ∧ same-package ∧ unvisited at fire time) | pure `selectTriggerCandidate` seam | candidate-matrix test |
| INV-CT-07 (deep-link URI when a VIEW filter carries schemes) | pure `buildDeepLinkUri` seam | URI-building tests |
| exploration delta (EVENT_TRIGGER_ACTIVITY predicates) | `ActionType` enum + predicates | predicate tests |
| `[APE-STEP] decision_source=Component` | teach the non-model branch of `resolveNewAction` to read the action's source (attribute `EVENT_TRIGGER_ACTIVITY` as `Component`) instead of hardcoding `SATA` | decision-source test |
| launch `ComponentName` package = `MopData.getPackageName()` (main INV-CT-04) | dispatch builds `ComponentName(MopData.getPackageName(), className)` | dispatch/intent test |

## Goals / Non-Goals

**Goals:** reward frontier-opening widgets in the roulette; convert dead stagnation stretches into one bounded jump to an unvisited exported activity; consume the parsed-but-unused deep-link data; make every launch model-visible (`[APE-STEP]`, Component source) and attribution-safe (EVENT_* semantics, no edge).

**Non-Goals:** login/credential flows (user-excluded); changing the receiver/service/provider probabilistic path; `reachesTarget`-based gating for depth (producer-broken); multi-launch bursts; model-edge labeling for launches (EVENT_* contract says no).

## Decisions

1. **Frontier filter = manifest facts, not `reachesTarget`.** `exported`/`intentFilters`/`data{}` come from the manifest (DefaultXMLParser) and survive R8 obfuscation; `reachesTarget` joins on renamed handlers and is empty for ~82% of obfuscated apps (B1/B8). Using it would make both levers inert exactly where depth is worst.
2. **A rides the existing WTG pass, additively — but with its own transition lookup.** It cannot piggyback on `MopScorer.scoreWtg`'s `int` return: that value hides which activity the transition targets and is non-zero only when the target is MOP-reachable, so it can neither identify the frontier target nor fire on non-MOP activities. Lever A therefore does its own `MopData.getWtgTransitions(activity)` lookup with the same `widgetName`/resource-id match the MOP boost uses, and adds the frontier term when `Graph.getActivityNode(WtgTransition.targetActivity) == null`. The term is accumulated into the action's existing `wtgBoost` field (`setWtgBoost`) — the same field the MOP-reach boost feeds — so it is visible in `[APE-STEP] wtg=` and exemptable by downstream changes that key on `wtgBoost > 0` (e.g. `sibling-state-depriority`). Stacking with `mopWeightWtg` is intentional: MOP-reachable + unvisited is the best possible frontier (combined `wtgBoost = mopWeightWtg + frontierBoostWeight`). Default 200 sits between the visited-CLICK base (32) and the discriminative MOP boosts (300/500) — strong enough to steer, never outbidding direct MOP evidence.
3. **B is a first-class step, not a side-effect.** The old side-effect design made launches invisible ([APE-STEP] absent, `DecisionSource.Component` dead) and let the next observed screen be attributed to an unrelated ModelAction. Returning an `EVENT_TRIGGER_ACTIVITY` action mirrors `EVENT_RESTART`: one `[APE-STEP]` line (INV-SEL-04 covers every selection path), no graph edge (EVENT_* are not edge labels), event generation owns the dispatch. **Attribution caveat:** `DecisionSource`/`get`/`setDecisionSource` live only on `ModelAction`, not on the non-model `Action` base — and the sole `[APE-STEP]` emitter for non-model actions (`resolveNewAction`'s else-branch, `StatefulAgent.java:1308-1315`) currently **hardcodes** `decision_source=SATA`. So `decision_source=Component` is not achievable by constructing the action; it requires teaching that else-branch to read the source from the action (special-case `EVENT_TRIGGER_ACTIVITY` → `Component`, or have `ActivityTriggerAction` expose a source the branch reads). Without that change no launch can ever emit `Component`.
4. **Stagnation gate reuses `graphStableRestartThreshold / 2`, evaluated after the LLM hooks.** Same escalation point the LLM uses; no new threshold flag (P1). If the LLM stagnation hook fires first (LLM enabled), it consumes the episode — B is the non-LLM arm's (and LLM-failure) fallback. Equality (not `>=`) makes it once per episode; a successful launch resets the counter (same as the LLM hook at `SataAgent.java:400`).
5. **Candidate eligibility at fire time, round-robin over the manifest list.** "Unvisited" changes during the run, so the static tuple-list approach is wrong; a pure seam filters `getActivities()` by exported/permission/unvisited (via `Graph.getActivityNode(name) == null`) and skips `isMain` (already the entry point). Round-robin index persists per run so repeated stagnation episodes walk the frontier.
6. **Deep-link when available, explicit component otherwise.** If any intent-filter has `ACTION_VIEW` + non-empty `data.schemes`: `Intent(ACTION_VIEW, scheme://firstHost + firstPath)` (best-effort URI from parsed parts, empty host allowed) targeted at the component. Otherwise explicit `ComponentName(MopData.getPackageName(), className)` intent with the first filter action when present. Uses the 88-filter deep-link inventory that is parsed today and consumed nowhere.
7. **Delete the probabilistic activity branch; flip `activityTriggerEnabled` to true (BREAKING, P3).** The gh11 -45pp evidence measured unfiltered per-step-probabilistic jumps — that code path is deleted, so the evidence no longer gates anything real. The repurposed flag gates a mechanism bounded by stagnation (rare), manifest filters (small candidate set), and once-per-episode semantics. Keeping it default-false would ship the depth fix disabled in the exact validation run meant to measure it.
8. **Permission-gated activities are skipped, not attempted.** The investigation showed permission failures are silently swallowed today; skipping is the honest bounded behavior (49 gated recv/svc analog on the activity side).

## API Design

### `static ComponentInfo selectTriggerCandidate(List<ComponentInfo> activities, Set<String> visitedActivities, String mainActivity, int rrIndex)`
- Pure. Walks the list from `rrIndex`, returns the first with `exported && permission == null && !isMain && !visitedActivities.contains(className)`; null when none. Caller owns the index increment.

### `static String buildDeepLinkUri(ComponentInfo activity)`
- Pure. First intent-filter having `ACTION_VIEW` in actions and non-empty `data.schemes` → `scheme + "://" + (firstHost|"") + (firstPath|"")`; null otherwise (explicit-intent fallback).

### Launcher block (in `selectNewActionNonnull`, after the LLM hooks)
```
if (Config.activityTriggerEnabled && getMopData() != null
        && graphStableCounter == graphStableRestartThreshold / 2) {
    candidate = selectTriggerCandidate(...);          // fire-time eligibility
    if (candidate != null) {
        graphStableCounter = 0;
        // The action is non-model; there is no withDecisionSource/setDecisionSource on
        // the Action base. decision_source=Component is produced downstream by teaching
        // resolveNewAction's non-model else-branch to attribute EVENT_TRIGGER_ACTIVITY as
        // Component (it currently hardcodes SATA — StatefulAgent.java:1308-1315).
        return new ActivityTriggerAction(candidate.className, buildDeepLinkUri(candidate));
    }
}
```

### Dispatch (new case in `MonkeySourceApe` event generation, EVENT_RESTART template)
Explicit intent (`setComponent(new ComponentName(MopData.getPackageName(), className))`, `FLAG_ACTIVITY_NEW_TASK`) or `ACTION_VIEW` + `Uri.parse(deepLink)` when non-null; `AndroidDevice.startActivity(intent)`; throttle; failures logged WARNING and the run continues (existing `startActivity` error contract). The package component is `MopData.getPackageName()`, never derived from the target class name (main-spec INV-CT-04, ComponentName Package Derivation).

## Data Flow

Static `components{}`/`transitions` (already parsed) → A: WTG pass adds frontier weight each scoring round (target unvisited?) → roulette steers to frontier widgets. B: stagnation point → candidate seam (manifest filters × live Graph) → `ActivityTriggerAction` step → `[APE-STEP] decision_source=Component` → intent dispatch → next `generateEvents` models the new screen as current state (no edge, same as restart).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `startActivity` fails (reflection gap pre-Q, SecurityException, bad URI) | dispatch | WARNING log (existing contract), no crash | next cycle proceeds normally; counter already reset avoids immediate re-fire |
| Candidate crashes on launch (missing setup state) | app under test | app crash handled as today (logcat/app_events) | APE restarts per existing flow |
| No candidate (all visited/gated) | launcher | fall through to normal SATA chain | graph-stable restart ladder unaffected |

## Risks / Trade-offs

- [Launch lands on an activity needing session state → immediate crash/finish] → bounded: once per stagnation episode, candidate set shrinks as activities get visited, crash handling unchanged; measurable via `decision_source=Component` count vs cov_act delta in validation.
- [Frontier boost mis-steers when WTG static transition is stale] → weight 200 is roulette-relative, not deterministic; `0` rollback.
- [Deep-link URI built from parts may be malformed for path-pattern-only filters] → best-effort with explicit-intent fallback; malformed URI = WARNING + continue.
- [Both levers activate only in MOP arms → confounds the MOP fair test] → **not arm-neutral.** Lever A rides the WTG pass, which is gated `if (_mopData != null && _mopData.hasWtgData())` (`StatefulAgent.java:1443`); Lever B is gated on `getMopData() != null`. Both are inert without `mopDataPath`, so a default-on frontier boost activates **only** in arms that load MopData — exactly the MOP arms — and would confound a MOP-vs-baseline comparison. This is a config obligation, not a decoupling (decoupling either lever from `_mopData` would be a code redesign, out of scope): **experiment configs MUST disable both levers for any non-MOP baseline arm** — `ape.frontierBoostWeight=0` AND `ape.activityTriggerEnabled=false` — so the two arms differ only in what the change intends to measure.

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit (seams) | candidate matrix (exported/permission/main/visited), round-robin wrap, deep-link URI building (schemes/hosts/paths permutations, VIEW-less filters) | plain JUnit, pure statics |
| Unit (enum) | `EVENT_TRIGGER_ACTIVITY.requireTarget()==false`, `isModelAction()==false` | ActionType predicate tests |
| Unit (scoring) | frontier term: unvisited target boosted, visited not, weight 0 off, stacking with mopWeightWtg | scoring-pass tests with stub graph/MopData |
| Unit (gate) | launcher fires only at the equality point, resets counter, yields when no candidate | seam test on the gate predicate |
| Device smoke | deep app from the depth cohort (e.g. `dev.ukanth.ufirewall`, 4/32 activities visited in cmpft2): `Frontier boost` lines present; on stagnation, `[APE-STEP] decision_source=Component` + `Triggering activity` line; cov_act rises vs the cmpft2 trace of the same APK; no crash storm | future validation run |

## Open Questions

- Default `frontierBoostWeight=200` and the once-per-episode launch rate are first settings; the validation run calibrates both.
