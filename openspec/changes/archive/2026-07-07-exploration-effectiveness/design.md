# Design: exploration-effectiveness

## Context

Companion change to `experiment-validity`: that change makes measurements honest; this one removes verified caps on what the tool can discover. All items were adversarially re-verified against this worktree's source (2026-07-02 synthesis of docs/analise_*.md). Everything here is arm-neutral — the same jar runs in every experiment arm.

Current state (verified, file:line):

1. `Config.java:40-41`: `takeScreenshotForEveryStep` and `saveGUITreeToXmlEveryStep` default `true`. The aperv-tool deployment does not override them (`rvsec/rv-android/modules/aperv-tool/.../tool.py` `APERV_PROPERTY_MAPPING` lacks both keys), the results pipeline consumes neither artifact (coverage from logcat Coverage.aj; trace from stdout; no PNG/XML pull), and the LLM path uses its own on-demand `ScreenshotCapture` (`ape/llm/ScreenshotCapture.java`, independent of the flag). Estimated 20-40% of step throughput is spent on unused I/O.
2. `ApeFuzzer.java:167-192`: `generatePinchOrZoomEvent(List<ApeEvent> events)` builds the gesture but never calls `events.add(...)` — the `events` parameter is unused. This is one of the three branches of the default fuzz switch (`:127`), so ~1/3 of default-branch fuzz iterations emit nothing. The `points.length < 4` guard in `ApePinchOrZoomEvent.java:42` is below the real minimum (6 = 1 count slot + 1 duration + 2·(count+1) points with count ≥ 0... concretely the fuzzer writes 6+2·count entries).
3. `StringCache.java:108-113`: `nextInt(stringList.size())` runs before the `isEmpty()` check (the check below it is dead code). The cache is filled from `/sdcard/ape.strings` (never pushed by aperv-tool) and from on-screen text captured during the run — on a text-sparse screen (typical login), the GENERIC input path throws `IllegalArgumentException`.
4. `GUITreeBuilder.fillNode` (`:582-606`) never calls `setIsPassword(info.isPassword())` — zero call sites repo-wide — so `node.isPassword()` is constant `false` and the heuristic-input spec's priority-1 PASSWORD detection is unreachable; XML serialization (`:565`) always writes `password="false"`.
5. `InputValueGenerator.matchKeywords` (`:132-157`) uses `String.contains`: "account"→NUMBER (via "count"), "security"→URL (via "uri", checked before NUMBER), "hotel"→PHONE (via "tel").
6. `NamingFactory.java:280` and `:1180`: both guards read `an.getStates().size() > maxGUITreesPerState` — a copy of the `maxStatesPerActivity` check one line above. The log lines print `state.getGUITrees().size()`, revealing the intent. The cap declared in `specs/naming/spec.md:83` never engages (feeds the known OOM).
7. `GUITreeNode.clearChildren` (`:551-559`) iterates a live W3C `NodeList` with `i++` while removing — every other child survives, so post-WebView-prune XML/naming sees phantom clickable nodes. `GUITreeBuilder.checkAndRemoveWebView` (`:465`) counts all descendants (`getDescendantCount()`; the trailing comment `// count(node, actionNodeFilter)` shows the intended actionable-only count) against `ignoreWebViewThreshold=64`, so real WebViews almost always exceed it on non-actionable nodes and get discarded.
8. `MonkeySourceApe.generateClickEventAt` (`:358-362`): when `getVisibleBounds` returns null (node rect does not intersect the screen), the code substitutes the full display rect and clicks its CENTER — the model credits the original action with whatever the center click did (false edges; 260 occurrences across 17/1513 baseline runs). The `:402-406` invalid-bounds branch silently emits nothing while the action was already counted.
9. `ApeAgent.generateInputText` (`:215-227`) resolves the static widget by exact `getWidget(activity, extractShortId(resourceID))`; the MOP scorer wraps the same lookup in `mopBoostWithContainment` (`StatefulAgent.java:1504-1539`, ±2-level ancestor/descendant probe) precisely because static and runtime id granularity often differ. The exact-only lookup misses most widgets, so typed (`inputType`/`hint`-aware) generation rarely activates. (The audits' claimed activity-derivation mismatch was refuted: both paths read `getTopActivityComponentName().getClassName()`.)

Constraints: P1 (minimal fixes, no new subsystems), P3 (defaults change outright, no compat flag), no logcat writes (hard project constraint), no new config flags.

## Architecture

No new components. Nine local fixes:

### Key Components

| Component | Change |
|-----------|--------|
| `Config` | Two defaults flip to `false` |
| `ApeFuzzer` / `ApePinchOrZoomEvent` | Emit the built gesture; guard `< 6` |
| `StringCache.nextString` | Empty check first; empty → `RandomHelper.nextFormattedString()` |
| `GUITreeBuilder.fillNode` | `node.setIsPassword(info.isPassword())` |
| `InputValueGenerator.matchKeywords` | Tokenize (separators `[_\-./: ]` + camelCase splits), token equality/prefix against the same keyword table |
| `NamingFactory` (2 sites) | Guard reads `state.getGUITrees().size()` |
| `GUITreeNode.clearChildren` | `while (length > 0) remove(item(0))` |
| `GUITreeBuilder.checkAndRemoveWebView` | Threshold over actionable descendants only |
| `MonkeySourceApe.generateClickEventAt` | Off-screen: no event + `[APE-RV] off-screen action dropped` log; delete the center-click substitution |
| `ApeAgent.generateInputText` | Resolve widget via the same containment policy as the scorer |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-EXPL-17 debug artifacts opt-in | `Config.java:40-41` | Unit: defaults false; property override true |
| INV-EXPL-18 every fuzz branch emits | `ApeFuzzer` | Unit: pinch/zoom branch adds exactly one event with ≥6 points |
| INV-EXPL-19 off-screen never retargeted | `MonkeySourceApe.generateClickEventAt` | Device-validated + unit where mockable |
| INV-TREE-09 isPassword captured | `GUITreeBuilder.fillNode` | Unit: node built from password info has `isPassword()==true` |
| INV-TREE-10 clearChildren empties | `GUITreeNode.clearChildren` | Unit: N children → 0 after call |
| INV-TREE-11 WebView threshold actionable-only | `GUITreeBuilder.checkAndRemoveWebView` | Unit: 100 non-actionable + 10 actionable descendants → kept at threshold 64 |
| INV-NAME-14 GUITree cap enforced | `NamingFactory:280,1180` | Unit: state with >20 trees suppresses refinement |
| INV-INP-05 token matching | `InputValueGenerator.matchKeywords` | Unit: "account"→not NUMBER; "security"→not URL; "email_input"→EMAIL |
| INV-INP-06 empty cache never throws | `StringCache.nextString` | Unit: empty cache returns non-null string |
| INV-MOP-23 typed resolution containment | `ApeAgent.generateInputText` | Unit: child id resolves parent-flagged widget |

## Goals / Non-Goals

**Goals:** more steps per run (I/O flip), more gesture diversity (fuzz emit), no input-path crashes, password/login flows receive plausible input, WebView content explorable without phantom nodes, model free of center-click false edges, typed input actually activates.

**Non-Goals:**
- Not crediting the wasted step when bounds are invalid (requires reordering markVisited/coverage vs. event generation — same pipeline family as checkRestart reorder; deferred with it).
- No change to fuzzing rate/threshold semantics (the dead `checkFuzzing(ModelAction)` overload is a separate, deferred item).
- No text-only LLM fallback for secure windows (deferred pending prevalence evidence from traces).
- No new keyword categories or value lists in `InputValueGenerator`.

## Decisions

1. **Defaults in `Config.java`, not in aperv-tool's `tool.py`** — one repo, one worktree, merge-or-discard together; a tool-side override would leave jar defaults lying about actual behavior (the hidden-coupling pattern behind the earlier build-skew incident). Debug re-enable stays one properties line away.
2. **Off-screen fix drops the event but keeps the credit** — full fix needs the markVisited/coverage reorder (high-risk pipeline change, deferred). Dropping the center-click alone already removes the model corruption (false edges), which is the damaging half; the log line makes the wasted-step frequency measurable on device to justify (or not) the deferred half.
3. **Containment reuse over a new matching layer** — `mopBoostWithContainment`'s ±2-level policy already encodes the granularity-reconciliation rule (spec: "Parent/child widget granularity reconciliation"); the typed-input path reuses that policy rather than inventing a second one (P1).
4. **Token matcher keeps the existing keyword table** — only the comparison changes (equality/prefix on tokens); no new categories, so behavior changes only where the substring match was a false positive.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Empty string cache | `StringCache.nextString` | Return `RandomHelper.nextFormattedString()` | N/A |
| Off-screen action | `generateClickEventAt` | No event; one `[APE-RV]` log line | Agent selects another action next step |
| Pinch/zoom with <6 points | `ApePinchOrZoomEvent` ctor | Reject (guard) | N/A (fuzzer always writes ≥6) |

## Risks / Trade-offs

- [WebView threshold fix retains larger trees → memory pressure] → the now-working `maxGUITreesPerState` cap (item 6) bounds per-state accumulation; validate together on device.
- [Dropping center-clicks removes incidental exploration those misclicks caused] → incidental center-clicks were unattributable noise; ~1% of runs affected, measured.
- [Default flip breaks debug workflows expecting per-step artifacts] → re-enable via `ape.properties`; CLAUDE.md documents the flags.
- [Token matcher changes input for identifiers where substring accidentally helped] → mislabeled input (numbers into account fields) is strictly worse than GENERIC; no such case is known.

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit | All 10 invariant rows above | JVM tests, existing suite conventions |
| Device | Throughput delta with artifacts off; WebView-bearing app exploration; off-screen drop frequency; password field receives password-shaped input | Standalone RVSec AVD, cryptoapp + one WebView app |

## Open Questions

- OQ1: after device validation, is the throughput gain large enough to re-baseline `--running-minutes` in the experiment protocol? (Protocol decision, not code.)
