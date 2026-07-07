# Proposal: exploration-effectiveness

## Why

The 2026-07-02 synthesis of seven independent APE-RV audits (docs/analise_*.md) surfaced a set of verified, low-risk defects that directly cap the tool's three experiment metrics — UI coverage, class/method/MOP-method coverage, and JavaMOP violations found. Every step currently pays PNG+XML I/O that nothing in the pipeline consumes; ~1/3 of fuzz gestures are built and silently discarded; text-sparse login screens can crash the generic input path; password fields never receive password-shaped input because the accessibility flag is never captured (breaking a behavior the heuristic-input spec already mandates); keyword substring matching feeds numbers to "account" fields and URLs to "security" fields; the per-state GUITree cap declared in the naming spec never engages; WebView pruning leaves phantom clickable nodes and discards legitimate web content; off-screen clicks are silently retargeted to the screen center and credited as the original action, corrupting the model. All fixes are arm-neutral: the same jar runs in every experiment arm, so they raise the tool's baseline without biasing the sata × sata_mop comparison.

## What Changes

- **Per-step debug artifacts off by default**: `ape.takeScreenshotForEveryStep` and `ape.saveGUITreeToXmlEveryStep` defaults flip to `false`. The rv-android pipeline consumes neither (verified: coverage comes from logcat, trace from stdout; aperv-tool pulls no PNG/XML), and the LLM path captures its own screenshots on demand (`ScreenshotCapture`, independent of the flag). Debug runs re-enable via `ape.properties`. **BREAKING** for any workflow that relied on the per-step artifacts existing by default.
- **Pinch/zoom fuzz events are actually emitted**: `generatePinchOrZoomEvent` gains the missing `events.add(...)`; the `ApePinchOrZoomEvent` constructor guard tightens to the real minimum (6 points).
- **`StringCache.nextString()` survives an empty cache**: the empty-list check moves before the `nextInt` draw; empty cache returns a formatted random string instead of throwing.
- **`isPassword` is captured**: `GUITreeBuilder.fillNode` copies `AccessibilityNodeInfo.isPassword()` into the node, making the PASSWORD priority-1 detection (already required by the heuristic-input spec) reachable.
- **Keyword matching is token-based**: `InputValueGenerator.matchKeywords` splits identifiers on separators and camelCase and compares tokens, eliminating `account→NUMBER` (via "count") and `security→URL` (via "uri") mislabels.
- **Per-state GUITree cap engages**: the two `NamingFactory` guards test the state's GUITree count instead of the activity's state count (copy-paste defect), enforcing the `ape.maxGUITreesPerState` contract already declared in the naming spec (OOM mitigation).
- **WebView pruning is correct**: `clearChildren` removes all children (live-NodeList iteration bug left ~half as phantom actionable nodes); the WebView-removal threshold counts only actionable descendants, as its inline comment always intended.
- **Off-screen actions are not faked**: when an action's bounds do not intersect the visible screen, no event is emitted and the occurrence is logged — the previous behavior clicked the screen center while crediting the original action (false model edges). The invalid-bounds branch keeps its no-event behavior but also logs. (Not crediting the wasted step remains out of scope — see Non-Goals in design.)
- **Typed-input resolution reuses containment**: `ApeAgent.generateInputText` resolves the static widget with the same ±2-level parent/child containment policy the MOP scorer already uses, instead of an exact-id-only lookup that usually misses.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `exploration`: per-step debug artifact defaults; fuzz gesture emission; off-screen action handling.
- `ui-tree`: `isPassword` capture; WebView pruning correctness (clearChildren + actionable-only threshold).
- `naming`: `maxGUITreesPerState` guard enforcement.
- `heuristic-input`: token-based keyword matching; StringCache empty-cache behavior.
- `mop-guidance`: typed-input widget resolution via containment.

## Impact

- **Components**: `Config` (two defaults), `ApeFuzzer`, `ApePinchOrZoomEvent`, `StringCache`, `GUITreeBuilder`, `GUITreeNode`, `InputValueGenerator`, `NamingFactory`, `MonkeySourceApe`, `ApeAgent`.
- **Experiments**: throughput gain from the artifact-defaults flip applies equally to all arms (same jar); the fuzz/input/pruning fixes raise baseline coverage. No arm-specific mechanism changes.
- **Behavioral risk**: WebView-threshold and off-screen-action fixes alter which nodes/actions the model sees; both are bounded (threshold still applies, off-screen actions were already not executed as targeted) and device-validated before the fair test.
- **No producer/JSON contract change; no new config flags.**
