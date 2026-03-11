# APE vs rvsmart: Technical Comparison

**Date**: 2026-03-10

---

## 1. Tech Stack

| Dimension | APE | rvsmart |
|---|---|---|
| Language | Java 7 (broken on Java 25) | Java 8 |
| Build system | Apache Ant + `dx` (broken: dx removed in build-tools 28+) | Maven + `d8` (working) |
| Fat JAR | `dx --dex` → single `classes.dex` | Maven Shade → fat JAR → `d8` |
| Framework stubs | `framework/classes-full-debug.jar` (API 23, 2015) | `android.jar` SDK API 29 |
| Dependencies | None (beyond framework) | Gson, JUnit 5, Mockito |
| Entry point | `Monkey.main()` via `app_process` | `Main.main()` via `app_process` |
| Android interaction | Monkey event system (extends AOSP Monkey) | Pure AccessibilityService + InputManager |

---

## 2. State Representation

| Dimension | APE | rvsmart |
|---|---|---|
| Abstraction model | **Adaptive Naming hierarchy** — dynamically refined on non-determinism | **Dual-hash** — fixed (contentHash + structHash) |
| State identity | `Name` objects: XPath-like paths (text, resourceId, type, index...) assembled into `Naming` | contentHash: sorted (className, resourceId, text, flags) tuples |
| Structural clustering | N/A — one unified state space | structHash: (className\|resourceId\|interactionMask) — no text |
| State space | GSTG: abstract states refined over time | ContentGraph (exact) + StructuralGraph (structural clusters) |
| Refinement | **Yes** — automatic state/action refinement when non-determinism detected | **No** — dual hash is fixed schema |
| Widget model | `GUITreeNode` with full property set (35+ attributes) | `ScreenItem` with ~15 attributes, capped at 2000 nodes |
| State cap | `maxStatesPerActivity=10`, `maxGUITreesPerState=20` | `contentHash` capped at 1000 entries |

**Key difference**: APE's state abstraction is the core research contribution — it starts coarse and refines automatically. rvsmart's dual-hash is fixed and simpler, trading adaptivity for predictability.

---

## 3. Exploration Algorithm

| Dimension | APE (SataAgent) | rvsmart (AgentLoop) |
|---|---|---|
| Paradigm | Model-based RL-inspired (SATA) | Phase-based coverage-guided |
| Phase 1 | ABA exploration: visit unvisited actions in current state, backtrack to cold states | DFS: untested actions first, BFS on StructuralGraph to nearest unvisited cluster |
| Phase 2 | Epsilon-greedy: random action with prob 0.05, else least-visited | Coverage-guided: target structural cluster with highest coverage gap |
| Phase 3 | Buffer-based: replay path to reach unsaturated state | Stochastic: softmax selection with boosted probability (0.5) |
| Action priority | Unsaturation-first (unvisited widget signature combos) → epsilon-greedy | 6-scorer pipeline (MOP + decay + type priority + WTG + coverage) |
| Backtracking | Navigate state graph by path history | NavigationMap BFS replay on structural transitions |
| Loop detection | State saturation → restart | PlateauDetector + StuckDetector |
| BACK action | Modeled as state transition, used for backtracking | Modeled but **broken** (BUG-01: contentHash vs structHash mismatch) |

---

## 4. Action Selection

| Dimension | APE | rvsmart |
|---|---|---|
| Selection unit | `ModelAction` (abstract: maps widget class to action type) | Concrete coordinate (bounds centroid from AccessibilityNodeInfo) |
| Saturation concept | Widget signature combination not yet seen | Execution count per action per contentHash |
| Scoring | Saturation + visit count + action type heuristics | 6 scorers: MopScorer, GradualDecayScorer, ComponentPriorityScorer, WtgScorer, ConfirmedCoverageScorer, SystemElementFilter |
| Static analysis | None | **MOP integration** (MopScorer): +500 direct, +300 transitive reachability |
| Stochastic selection | Epsilon-greedy (ε=0.05) | Softmax with configurable probability (default 15%, 50% in Phase 3) |
| Action filtering | `ActionFilter.ENABLED_VALID_UNSATURATED` | Failure filter (≥3 consecutive failures excluded) |
| Text input | 80% probability for EditText (`inputRate=0.8`) | `SET_TEXT` scorer boosted (+200), value saturation after 6 unique values |

---

## 5. Android Interaction

| Dimension | APE | rvsmart |
|---|---|---|
| UI capture | `AccessibilityNodeInfo` tree via UiAutomation | `AccessibilityNodeInfo` BFS (cap 2000 items, interactive-first priority) |
| Click injection | `MotionEvent` via Monkey event queue | `InputManager.injectInputEvent()` directly |
| Text injection | `MonkeyKeyEvent` sequence | `AccessibilityNodeInfo.setText()` or EditText direct fill |
| SCROLL | Swipe gesture (configurable duration) | `MotionEvent.ACTION_SCROLL` with delta |
| Crash detection | Monkey ANR/crash monitor | `CrashInterceptor` (Java crash + ANR + native) |
| System dialogs | Minimal (basic package check) | `SystemDialogDetector` with escalation (BACK → forceStop at 3/6 failures) |
| App restart | Force-stop + launch | `ActivityManager.forceStopPackage()` + Intent |
| Screenshots | Via `UiAutomation`, async `ImageWriterQueue` | `UiAutomation.takeScreenshot()` (for LLM only) |

---

## 6. LLM Integration

| Dimension | APE | rvsmart |
|---|---|---|
| LLM support | **None** | Optional (3 modes: pure_algorithm, multimode, llm_only) |
| Routing | N/A | `RoutingManager`: PROBABILISTIC / NEW_SCREEN_ONLY / STUCK_ONLY / ARRIVAL_FIRST |
| LLM backend | N/A | SGLang (OpenAI-compatible API), circuit breaker protection |
| Model used | N/A | Qwen3-VL-4B-Instruct (multimodal: screenshot + text) |
| Decision type | N/A | Action selection (click coordinates, set_text, scroll, back) |
| Prompt | N/A | V13/V17: system role + screenshot + visited activities + recent actions + MOP context |
| Experiment result | N/A | Hybrid LLM **worse** than pure algorithm (-1.65pp, p=0.003); LLM helps only in text-input-heavy apps |

---

## 7. MOP / Security Spec Integration

| Dimension | APE | rvsmart |
|---|---|---|
| MOP awareness | **None** | **Core feature**: static analysis JSON loaded at startup |
| Coverage metric | Activity/action coverage only | Method coverage + MOP spec violation detection |
| Static data | N/A | `--static-data <path>`: reachability per widget per API spec |
| Scoring | N/A | MopScorer boosts actions that reach MOP-reachable methods |
| Violation detection | N/A | Real-time via RVTrack logcat monitoring |

---

## 8. Output & Observability

| Dimension | APE | rvsmart |
|---|---|---|
| Trace format | Custom log lines | CSV with 20+ fields per iteration (action, timing, phase, score breakdown) |
| Metrics | Activity coverage, state count | Method coverage, MOP coverage, phase distribution, LLM stats, crash counts |
| State graph | Saved as serialized Java object (`.obj`) | Not persisted (in-memory only) |
| Screenshots | Saved per step (configurable) | Saved only when LLM is invoked |
| Real-time logging | Logcat via Monkey | RVTrack logcat API (route, action, rank, crash events) |

---

## 9. Strengths & Weaknesses Summary

### APE
**Strengths:**
- Adaptive state abstraction (core research innovation) — handles complex UI dynamics
- Explicit state machine enables systematic graph traversal and backtracking
- Stronger activity coverage (64.11% vs 58.14% for rvsmart in experiments)
- Proven: 28.38% method coverage vs 24.04% rvsmart (p<0.001)
- Models ViewPager/tabs as distinct states and explores systematically

**Weaknesses:**
- Build broken on modern toolchain (Java 25, build-tools 35)
- Framework stubs from API 23 (2015)
- No MOP/security spec awareness
- No LLM integration
- Higher variance (CV 10.5% vs 8.1% rvsmart)

### rvsmart
**Strengths:**
- Modern stack (Maven, d8, API 29, working build)
- MOP integration from the ground up
- More deterministic (CV 8.1%)
- Detects violations in more APKs (44 vs 43)
- Exclusive detections: 3 APKs only rvsmart finds
- LLM-optional architecture (helps for text-input-heavy apps)

**Weaknesses:**
- BUG-01: BACK never executes (0/251k actions) — major exploration blocker
- BUG-02: 24.5% iterations wasted in ping-pong cycles
- BUG-03: saturation loop (getSaturationRate=1.0 when totalActions=0)
- Fixed dual-hash: no adaptive abstraction
- Blind to ViewPager/tabs (no SWIPE actions)
- OptionsMenu not systematically explored

---

## 10. Ideas from APE Worth Bringing to rvsmart

| APE Feature | Description | Value |
|---|---|---|
| **Structural state graph** | Explicit GSTG enables path-based backtracking and ABA navigation | High — fixes stuck-in-activity problem |
| **ViewPager / tab modeling** | Each tab modeled as distinct state; SWIPE_LEFT/RIGHT as actions | High — 7/10 worst APKs in experiment use ViewPager |
| **OptionsMenu exploration** | MENU button + systematic item traversal | Medium — 4/10 worst APKs affected |
| **Action saturation (widget combos)** | Track *which* widget was targeted, not just action type | Medium — more precise saturation than execution count |
| **State refinement trigger** | Detect non-determinism and adapt abstraction | Low (complex) — dual-hash may be simpler equivalent |
| **ABA backtracking** | Navigate via state graph to cold/unsaturated regions | Medium — alternative to BFS on StructuralGraph |
