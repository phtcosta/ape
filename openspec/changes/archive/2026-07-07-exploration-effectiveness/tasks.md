# Tasks: exploration-effectiveness

## 1. Throughput and fuzz

- [x] 1.1 `Config.java:40-41`: flip `takeScreenshotForEveryStep` and `saveGUITreeToXmlEveryStep` defaults to `false` (INV-EXPL-17); update the flag docs in CLAUDE.md
- [x] 1.2 `ApeFuzzer.generatePinchOrZoomEvent` (`:167-192`): (a) fix the array over-allocation — `new PointF[4 + count << 1]` is `(4+count)<<1` = `8+2·count` but only `6+2·count` entries are written, leaving 2 trailing `null`s; size the array to exactly the written count so it has no `null` entries; (b) then `events.add(new ApePinchOrZoomEvent(points))` (INV-EXPL-18). No `null` element may reach the constructor.
- [x] 1.3 `ApePinchOrZoomEvent` ctor (`:41-42`): move the length guard BEFORE `fromPointsArray(points)` (currently `:41` dereferences every element before the `:42` guard runs) and reject `points.length < 6` (real minimum) — the guard must run before any element is dereferenced, so a malformed array is rejected instead of throwing NPE
- [x] 1.4 Unit tests: defaults false + property override; pinch/zoom branch emits exactly one event with ≥6 non-`null` points (no NPE) for `count=0` and larger; ctor rejects 5 points before dereferencing

## 2. Input quality

- [x] 2.1 `StringCache.nextString` (drifted to `:107`): empty check moved before `nextInt`; empty cache → `RandomHelper.nextFormattedString()` (INV-INP-06)
- [x] 2.2 `GUITreeBuilder.fillNode` (drifted to `:605`): added `node.setIsPassword(info.isPassword())` alongside the boolean-property copies (INV-TREE-09)
- [x] 2.3 `InputValueGenerator.matchKeywords` (`:132`): replaced substring `contains` with token-equality matching — tokenize on `[_\-./: ]` + camelCase, compare tokens for equality against the unchanged keyword table (INV-INP-05)
- [x] 2.4 `ApeAgent.generateInputText` (drifted to `:215`): resolves the static widget via the scorer's ±2-level containment policy. Extracted `MopScorer.containmentShortIds(GUITreeNode)` (ape.utils, no package cycle — MopScorer already imports GUITreeNode); `StatefulAgent.mopBoostWithContainment` refactored to iterate it (behavior preserved byte-for-byte); `ApeAgent` iterates it, first widget with non-empty inputType/hint wins, node's own id first (INV-MOP-23)
- [x] 2.5 Unit tests: `StringCacheTest` (empty + populated cache non-null, JVM); `InputValueGeneratorTest` (+account_name/security_answer/hotel → GENERIC, userEmailField → EMAIL, phone_number → PHONE, item_count → NUMBER, JVM); `MopScorerContainmentTest` (id ordering — @Ignore device-gated, GUITreeNode links Android); `GUITreeBuilderPasswordTest` (isPassword capture — @Ignore device-gated, fillNode private + AccessibilityNodeInfo)

## 3. Tree fidelity

- [x] 3.1 `GUITreeNode.clearChildren` (`:551-559`): remove via `while (childNodes.getLength() > 0) domNode.removeChild(childNodes.item(0))` (INV-TREE-10)
- [x] 3.2 `GUITreeBuilder.checkAndRemoveWebView` (`:465`): threshold over actionable descendants — no `count(node, actionNodeFilter)` helper existed, added `countActionableDescendants(GUITreeNode)` using the codebase's canonical action predicate (clickable/checkable/scrollable/long-clickable, per `ActionPatchNamer`) (INV-TREE-11)
- [x] 3.3 `NamingFactory.java:280` and `:1180`: guards test `state.getGUITrees().size() > maxGUITreesPerState` (INV-NAME-14)
- [x] 3.4 Unit tests: `GUITreeWebViewPruneTest` — clearChildren empties N children (even + odd); actionable-descendant count = 10 for 100 non-actionable + 10 actionable (action-sparse kept) and nested count. NamingFactory over-cap guard is Android-gated (needs live State/ActivityNode) → device-covered

## 4. Off-screen action handling

- [x] 4.1 `MonkeySourceApe.generateClickEventAt` (`:360-365`): deleted the `bounds = AndroidDevice.getDisplayBounds()` substitution; off-screen (`getVisibleBounds(nodeRect)==null`) → no event + `[APE-RV] off-screen action dropped: <nodeRect>` log (INV-EXPL-19). `<action>` has no in-scope variable; `nodeRect` (the resolved node's bounds) is the best available identifier at that site
- [x] 4.2 Invalid-bounds branch (`:405-409`): added the same `[APE-RV] off-screen action dropped: <nodeRect>` log line to the existing no-event return
- [~] 4.3 `MonkeySourceApe.generateClickEventAt` is Android-gated (`getVisibleBounds` → framework `Display`/`Rect`, excluded from surefire classpath) → not JVM-mockable; covered by device task 5.1

## 5. Device validation (RVSec AVD, per CLAUDE.md standalone flow)

- [x] 5.1 cryptoapp run: confirm throughput gain with artifacts off, password field receives password-shaped input, no StringCache crash on the login screen, off-screen drop lines present and center-click absent — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md; 0 StringCache crashes across 219 APKs×3 reps)
- [x] 5.2 One WebView-bearing APK: confirm web content is explored (threshold fix) without phantom-node actions and without OOM (cap fix active) — DONE via cmpft/cmpft2 device experiments (rvsec/rv-android/docs/20260707_relatorio_cmpft2.md; 0 VerifyError/OOM)

## 6. Verification

- [x] 6.1 Run /sdd-test-run ape (full `mvn test` suite green: 453 tests, 0 fail, 0 err, 19 skipped)
- [x] 6.2 Run /sdd-qa-lint-fix ape (no-op: .sdd linter=none, checkstyle not installed)
- [x] 6.3 Run /sdd-verify ape (PASS: 453 tests, 0 fail; lint skipped)
- [x] 6.4 Run /sdd-code-reviewer — DONE: SDD gate suite run 2026-07-06 across the change set (shared, single run); forensic 6-agent audit + review pass, no code bugs
