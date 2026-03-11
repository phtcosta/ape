# APE-RV Artifact Analysis Report

**Prepared by:** Claude Code (claude-sonnet-4-6)
**Date:** 2026-03-11
**Scope:** Rigorous analysis of SDD artifacts for the APE-RV project

---

## 1. Overview and Methodology

This report analyzes the following artifacts for internal consistency, completeness, accuracy against the current codebase, depth, and quality:

- `docs/PRD.md` — Product Requirements Document
- `openspec/specs/build/spec.md` — Build Infrastructure Specification
- `openspec/specs/exploration/spec.md` — Exploration Engine Specification
- `openspec/specs/model/spec.md` — Exploration Model Specification
- `openspec/specs/naming/spec.md` — Naming and Abstraction System Specification
- `openspec/specs/ui-tree/spec.md` — UI Tree and Widget Representation Specification
- `openspec/specs/aperv-tool/spec.md` — aperv-tool Plugin Specification
- `openspec/specs/mop-guidance/spec.md` — MOP-Guided Action Scoring Specification

The current source code was also read to establish the **ground truth** of what is actually implemented at the time of analysis.

---

## 2. Current Implementation State (Ground Truth)

Before assessing the artifacts, the actual source code was inspected to establish a factual baseline:

| Dimension | Current State |
|-----------|--------------|
| Build system | Ant + `dx` only. No `pom.xml` exists. No deprecation notice in `build.xml`. |
| `ActionType` enum | Has `MODEL_BACK`, `MODEL_CLICK`, `MODEL_LONG_CLICK`, `MODEL_SCROLL_*`. **No `MODEL_MENU`.** |
| `GUITreeNode.getScrollType()` | Detects `android.support.v4.view.ViewPager`. **No AndroidX ViewPager variants.** |
| `State.java` constructor | Creates `backAction` only. **No `menuAction` field.** |
| `MopData.java` / `MopScorer.java` | **Do not exist.** |
| `ape/utils/` package | Contains `Config.java`, `Logger.java`, `RandomHelper.java`, `Utils.java`, `StringCache.java`, `PriorityObject.java`, `XPathBuilder.java`. |
| Agent implementations | `ApeAgent`, `SataAgent`, `StatefulAgent`, `RandomAgent`, `ReplayAgent` — all present. |

This confirms that **Phase 1 (build modernization), Phase 2 (UI enhancements), Phase 3 (MOP guidance), and Phase 4 (aperv-tool plugin) are all unimplemented** as of this analysis.

---

## 3. Artifact-by-Artifact Analysis

### 3.1 PRD.md

**Status: STRONG — The best artifact in the set.**

#### Strengths

- **Exceptional context**: The PRD provides research motivation, ecosystem context (RVSEC, rv-android, Experiment 01 baseline numbers), and justifies every requirement against a concrete problem. This depth is rare in academic tool documentation.
- **Phased roadmap**: Sections 9 (roadmap) and the use of `SDD Track` annotations per phase show deliberate planning aligned with the SDD workflow.
- **Concrete metrics**: Coverage numbers from Experiment 01 (APE: 25.27% overall, 14.56% MOP; Humanoid: 26.77%, 17.16%) give the work measurable success criteria.
- **Research questions well formed**: RQ-APE-1 and RQ-APE-2 are well-scoped with explicit hypotheses, not just open-ended questions.
- **All diagrams use Mermaid**: Compliant. The pipeline diagram (Section 3.1), testing loop flowchart (Section 4.1), build graph (Section 4.5), naming lattice (Section 4.3), MOP scoring flow (FR11), and roadmap Gantt are all Mermaid.
- **Language**: Entirely in English. Narrative prose is clear and suitable for a human audience.
- **Out-of-scope section (Section 10)**: Explicit exclusions prevent scope creep and document architectural decisions.

#### Weaknesses and Issues

1. **File count discrepancy**: Section 1.1 claims "140 Java source files" and Section 4.2 table shows 28 + 14 + 5 + 22 + 7 + 28 + 10 + 7 + 6 = **127 files**. The actual counts don't add to 140. This is an internal arithmetic inconsistency.

2. **Section 7 File Map is incomplete**: The Java Component Map lists only 14 files with their roles. It covers the most important files but omits many. For example, `Graph.java`, `NamingFactory.java`, `GraphElement.java`, `StateKey.java`, `Naming.java`, `NamingManager.java` are not listed. This is acceptable for a PRD but creates a false impression of completeness.

3. **FR15 lists 5 variants but the table header says "Four Supported Variants"**: "FR15: Four Supported Variants" introduces five rows in the table (`default`, `sata`, `sata_mop`, `bfs`, `random`). The text says "MUST support the following variants" but the section title says "Four". This is a numbering error — there are effectively 4 distinct variants if `default` is an alias for `sata`.

4. **Gantt chart uses abstract phase units, not real dates**: The Gantt chart uses `X` as `dateFormat` and shows phases as `1, 2, 3, 4, 5` on the axis. This renders correctly in Mermaid but produces axis labels like `Phase 1, Phase 2`... which is acceptable for a roadmap without committed dates. However, the `done` marker on Phase 1 in the Gantt implies Phase 1 is complete, which contradicts the ground truth (no `pom.xml` exists).

5. **Dependency path in FR02 is fragile**: The default `${aperv_tool_dir}` path is described as `../rvsec/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/`. This relative path is correct only when the APE-RV repository is located at `workspace-rv/ape/` in the standard workspace layout. The PRD acknowledges this but doesn't document what `mvn -Daperv_tool_dir=...` looks like in practice. A concrete override example would help.

6. **Missing specification of `--ape-mop` flag parsing**: The PRD describes `--ape-mop /data/local/tmp/ape-mop.json` as a new command-line flag (FR15 variant `sata_mop`). However, `Monkey.java` argument parsing is not described anywhere in the specs — this is a gap. Where is this flag handled? How does it map to `Config.mopDataPath`? This needs a scenario in the exploration or build spec.

7. **NFR01 gap**: "The `d8` tool MUST be on the system PATH or specified via `$ANDROID_HOME/build-tools/<version>/d8`" — the PRD doesn't describe how the Maven build selects the d8 path. This is a concrete implementation ambiguity that should be resolved in the build spec.

---

### 3.2 build/spec.md

**Status: CRITICAL INCONSISTENCY — Documents the legacy system, not the target.**

This is the most problematic artifact in the set. The spec describes the **original Ant + `dx` build** with `ape.jar` as output, Java 1.7 source/target compatibility, and `dx` as the DEX compiler. The PRD (Phase 1) requires **replacing this with Maven + `d8`** producing `ape-rv.jar`.

The result is that `build/spec.md` documents what the PRD says is **broken and must be replaced**. Every invariant in this spec (`INV-BUILD-05`: "MUST use Java 1.7", the `dx` requirement, the `ape.jar` output name) describes the legacy system.

#### Detailed Findings

| Dimension | build/spec.md says | PRD (FR01-FR03) says | Ground truth |
|-----------|-------------------|---------------------|--------------|
| Output JAR | `ape.jar` | `ape-rv.jar` | `ape.jar` (legacy) |
| Build tool | Ant + `dx` | Maven + `d8` | Ant only, no `pom.xml` |
| Java compat | `source="1.7" target="1.7"` | `--release 11` | 1.7 (build.xml) |
| DEX compiler | `dx` | `d8 --min-api 23` | `dx` |
| Copy to plugin | Not mentioned | `mvn install` copies to aperv-tool | Not implemented |

**INV-BUILD-05** states: *"The Ant compile target MUST use Java 1.7 source and target compatibility."* This invariant, if read as a target system invariant, is **directly contradicted by NFR01** which says Java 7 compatibility is not a goal and the minimum is Java 11.

#### Root Cause

This spec appears to have been written to document the **current baseline**, not the future state. SDD practice requires specs to describe the **target behavior** (what WILL be true after implementation), not the current behavior. The build spec is the only one that documents existing behavior rather than target behavior.

#### Missing Content (Critical)

The following are absent from the build spec:
- Maven `pom.xml` structure and key plugin declarations
- `d8` invocation with `--min-api 23`
- `ape-rv.jar` output name
- `mvn install` auto-copy behavior (FR02)
- Ant build deprecation notice requirement (FR03)
- The `exec-maven-plugin` approach for invoking `d8`
- Build compatibility matrix (Java 11 / 17 / 21)
- Verification that `build.xml` is retained with deprecation header

#### Missing Diagrams

The build spec has no diagrams at all. The PRD already has an excellent build pipeline diagram (Section 4.5). This diagram should be replicated or referenced in the build spec to give it self-contained context. The SDD format benefits from visual architecture even for build artifacts.

---

### 3.3 exploration/spec.md

**Status: GOOD — Well-structured with one major gap (MODEL_MENU).**

#### Strengths

- **Excellent purpose section**: Articulates why model-based exploration beats random testing with concrete reasoning (re-visit avoidance, directed navigation, coverage quantification).
- **Complete SataAgent flowchart**: The Mermaid `flowchart TD` for the SataAgent selection algorithm is accurate for the current code and the most complex behavioral diagram in the set.
- **Good invariant coverage**: The invariants cover termination conditions, action type contracts, priority ordering, and stability counter semantics.
- **Configuration table**: The exploration spec has the most complete configuration reference table of any spec.

#### Issues

1. **MODEL_MENU is entirely absent**: The PRD (FR09) specifies adding `MODEL_MENU` as a Phase 2 feature. The exploration spec does not mention it anywhere:
   - `INV-EXPL-05` lists all `MODEL_*` types without `MODEL_MENU`
   - The ActionType classification table has no `MODEL_MENU` row
   - The SataAgent flowchart shows BACK but not MENU in the unvisited action priority chain
   - No scenario covers "State has unvisited MENU action"

   This means the spec **does not specify** a significant planned feature. The spec should either add MODEL_MENU (if it describes the Phase 2 target) or explicitly note that MODEL_MENU is deferred to Phase 2.

2. **Invariant numbering gap**: `INV-EXPL-03` is missing. The invariants jump from `INV-EXPL-02` to `INV-EXPL-04`. This could be an intentional deletion or an oversight. It should be clarified.

3. **Strategy selection default is ambiguous**: The strategy selection requirement says "If the argument is absent or does not match any legal value, the implementation SHALL default to `sata`." But the CLAUDE.md says "The `--ape` flag is a required argument." These are contradictory — is `--ape` optional or required? The spec says optional (defaults to `sata`); the CLAUDE.md implies required. The source code should be the arbiter.

4. **SataAgent "ABA" pattern lacks a diagram**: The ABA algorithm (Section: "SataAgent — ABA Graph Navigation") is the most complex algorithm in the codebase. It is described textually but has no diagram. A state-machine diagram or sequence diagram showing the A→B→A navigation path would significantly improve comprehensibility.

5. **`sataGraph.dot` default value inconsistency**: `INV-EXPL` does not list an invariant for `saveDotGraph`. The output section and the requirements section both say `ape.saveDotGraph` defaults to `false`. This is consistent. But the exploration spec and model spec both have this information, creating duplication. A normative statement in model/spec.md and a cross-reference in exploration/spec.md would be cleaner.

6. **Fuzzing rate verification scenario is weak**: The scenario "Fuzzing fires at expected rate" says "across a large number of steps, approximately 2% of steps SHALL inject a FuzzAction." This is not a deterministic scenario — it cannot be verified mechanically. A better scenario would specify a `mock Random` with seeded values and assert the exact call sequence.

---

### 3.4 model/spec.md

**Status: VERY GOOD — Comprehensive, accurate, minor gaps.**

#### Strengths

- **Deepest invariant coverage in the set**: 10 invariants cover state identity, action uniqueness, graph integrity, transition recording, and serialization. All are verifiable.
- **Accurate to current code**: The invariants match the actual `State.java`, `Graph.java`, `StateTransition.java` implementations. For example, `INV-MODEL-02`'s ordinal-range implementation for `requireTarget()` matches the actual code exactly.
- **Rebuild semantics**: The `Model.rebuild()` requirement is the most subtle operation in the codebase and the spec handles it well — it covers both the clearing of `GUITree.currentState` before re-append and the version counter increment.
- **Action saturation**: The saturation semantics (single-target vs. multi-target, threshold=2) are documented precisely.

#### Issues

1. **MODEL_MENU absent from all model invariants**: Like `exploration/spec.md`, this spec doesn't include MODEL_MENU. Specifically:
   - `INV-MODEL-02` states `requireTarget()` uses ordinal range `MODEL_CLICK.ordinal() <= ord <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`. If `MODEL_MENU` is inserted between `MODEL_BACK` and `MODEL_CLICK` in the enum (as FR09 requires), the ordinal-range implementation for `requireTarget()` would still work correctly (`MODEL_MENU` would have `requireTarget() = false` because it's before `MODEL_CLICK`). **However**, `INV-MODEL-03` says "all seven MODEL_* values" must have `isModelAction() = true`. After adding MODEL_MENU, there would be **eight** MODEL_* values. This count is hard-coded in prose and will be wrong after Phase 2.
   - `INV-MODEL-01` says "exactly one ModelAction with actionType = MODEL_BACK" per state. After Phase 2, there will also be one `MODEL_MENU` action. The invariant doesn't mention this.

2. **"NamingManager" usage inconsistency**: The model spec uses `NamingManager` throughout, while the naming spec uses both `NamingManager` (interface) and its implementations (`ActivityNamingManager`, `StateNamingManager`, `MonolithicNamingManager`). Model spec occasionally refers to "NamingManager" for what is actually a `NamingFactory` operation. In `INV-MODEL-09` and the rebuild requirement, the spec says `Model.rebuild()` is invoked and `NamingManager.version` is incremented — but in the actual code, version tracking is on `NamingManager` while the rebuild logic is in `NamingFactory.rebuild()`. This distinction is blurred.

3. **`StateTransition.type` semantics**: `INV-MODEL-08` says `treeTransitions` must be non-empty after first creation. The requirement for new vs. repeat transitions documents `StateTransitionVisitType.NEW_ACTION` and `NEW_ACTION_TARGET` and `EXISTING`. However, the actual logic for when `type` changes from `NEW_ACTION_TARGET` to `EXISTING` on subsequent visits is not specified. If a transition already has type `NEW_ACTION_TARGET` and the same target is observed again, does it stay `NEW_ACTION_TARGET` or become `EXISTING`? This is an ambiguity.

4. **Serialization requirement**: `INV-MODEL` has no invariant for the serialization compatibility of `Model`. `INV-MODEL-09` covers `version` increments but there is no invariant saying "the serialized model MUST be deserializable by `ObjectInputStream` without ClassNotFoundExceptions." Given that `sataModel.obj` is a critical output used for offline analysis, a serialization compatibility invariant would be valuable.

---

### 3.5 naming/spec.md

**Status: EXCELLENT — The most complete and technically rigorous spec.**

#### Strengths

- **Conceptually deep purpose section**: Explains the fundamental scalability problem and why CEGAR is the solution, with the right level of abstraction for a human reader unfamiliar with the paper.
- **Full CEGAR sequence diagram**: The Mermaid sequence diagram covers the complete flow from non-determinism detection through `GUITreeWidgetDiffer`, `NamerLattice` query, refinement choice, `Naming` extension, and model rebuild. This is the best diagram in the set.
- **12 well-formed invariants**: All invariants are testable (not just aspirational). The reflexivity and transitivity requirements on `refinesTo()` (`INV-NAME-11`) are mathematically precise. The cache consistency invariants (`INV-NAME-09`) are rare and important.
- **Edge cases covered**: EditText text exclusion in TextNamer, sibling disambiguation with AncestorNamer, the bottom element of the lattice (`EmptyNamer`) — all are explicitly specified.
- **Blacklists documented**: `NDActionBlacklist` and `guiTreeNamingBlacklist` are mentioned in side-effects, ensuring implementers know about these side-channel state changes.

#### Issues

1. **`ape.activityManagerType` default is confusing**: The data contracts section says the default is `"state"` and that `"activity"` selects `ActivityNamingManager` and "any other value (including the default 'state')" selects `StateNamingManager`. This reads as if the default `"state"` is not a recognized keyword but rather a fallback — which is correct, but communicating it this way creates confusion. A cleaner statement: `"activity"` activates `ActivityNamingManager`; all other values (including the default `"state"`) activate `StateNamingManager`.

2. **`NamingManager` role boundary unclear**: The spec says `NamingManager` "mediates between `NamingFactory` and the rest of the system." But the spec also shows `NamingFactory` calling `Model.rebuild()` directly. The boundary between `NamingFactory` (refinement algorithm) and `NamingManager` (per-tree naming registry) is described but could benefit from a component responsibility diagram distinguishing them.

3. **`Namelet` definition**: The term `Namelet` is used extensively but is never formally defined in the spec. A reader learns from context that it is an `(XPath selector, Namer)` pair, but there is no explicit "A Namelet is X" statement. This should be added to the Purpose section.

4. **`MonolithicNamingManager` context lacking**: The data contracts mention `MonolithicNamingManager` is "only used when constructed explicitly (e.g., in replay scenarios)." But `ReplayAgent` is one of the five supported strategies. A brief clarification of when and how `ReplayAgent` triggers `MonolithicNamingManager` would round out this section.

5. **Invariant INV-NAME-12 is potentially unverifiable**: "Abstraction MUST ignore irrelevant properties" is conceptually correct but specifying exactly which properties are "irrelevant" requires knowing the full `NamerType` set. The invariant references the `NamerType` set indirectly but doesn't enumerate properties (e.g., widget bounds, enabled flag) that are always irrelevant. The TextNamer scenario in the requirements section (`same-structure trees with ignored properties`) covers `bounds` and `enabled` — but these should be in an explicit list in INV-NAME-12.

---

### 3.6 ui-tree/spec.md

**Status: ADEQUATE for Phase 1 baseline, INCOMPLETE for Phase 2 target.**

#### Strengths

- **Clean, focused**: The spec covers its scope (GUITree construction, scroll detection, action assignment, diff) without overreach.
- **Rationale for design decisions**: The explanation of why `GUITree` exists (live handle vs. snapshot), why `RecyclerView` is excluded from horizontal scroll detection, and why `MODEL_BACK` is blocked from `resetActions()` are all present and correct.
- **INV-TREE-03 is important**: Explicitly specifying that RecyclerView MUST NOT get horizontal scroll treatment by class name protects against a likely mistake during Phase 2.

#### Issues

1. **AndroidX ViewPager detection is absent (critical for Phase 2)**: `INV-TREE-02` states that `android.support.v4.view.ViewPager` returns `"horizontal"`. The PRD (FR08) adds two AndroidX variants: `androidx.viewpager.widget.ViewPager` and `androidx.viewpager2.widget.ViewPager2`. Neither appears in this spec. There is no:
   - `INV-TREE-08`: `GUITreeNode.getScrollType()` MUST return `"horizontal"` when `className` equals `"androidx.viewpager.widget.ViewPager"` and `isScrollable()` returns `true`.
   - `INV-TREE-09`: `GUITreeNode.getScrollType()` MUST return `"horizontal"` when `className` equals `"androidx.viewpager2.widget.ViewPager2"` and `isScrollable()` returns `true`.
   - No corresponding scenarios in the ViewPager Scroll Direction requirement.

   This means the spec, as written, only covers the legacy class name. An implementer following the spec alone would not know to add the AndroidX variants.

2. **`MODEL_MENU` exclusion from `resetActions()` is absent**: The PRD (FR09, item 4) says "Add `MODEL_MENU` to the explicit block list in `resetActions()`." The ui-tree spec documents the current block list in the Error section: `MODEL_BACK`, `EVENT_START`, `EVENT_RESTART`, `EVENT_CLEAN_RESTART`, `FUZZ`, `EVENT_ACTIVATE`. `MODEL_MENU` is not in this list. After Phase 2, `resetActions()` must also reject `MODEL_MENU`. The spec needs an `INV-TREE-10` and a scenario for this.

3. **`GUITreeNode.getDomNode()` error is not in invariants**: The error section correctly documents that `getDomNode()` throws `IllegalStateException` before DOM attachment. But there is no corresponding invariant or requirement asserting the temporal ordering rule (DOM must be attached before calling `getDomNode()`). The invariant set has no entry for this.

4. **`GUITreeWidgetDiffer` is underspecified**: The spec mentions the differ in data contracts and has two simple scenarios but provides no detail on the diff algorithm. How does it match nodes between trees — by index, by resource-id, by position? This matters because the naming system's `NamingFactory` relies on the differ to identify discriminating widgets. The naming spec references the differ but neither spec defines the matching algorithm precisely. This is a coordination gap.

5. **No diagram**: The ui-tree spec is the only substantive spec (excluding placeholders) without any diagram. A class diagram showing `GUITree → GUITreeNode`, the `ScrollType` enum, and the `GUITreeBuilder` factory relationship would improve comprehensibility significantly.

---

### 3.7 aperv-tool/spec.md

**Status: PLACEHOLDER — Contains no usable specification content.**

The document acknowledges it is a placeholder for Phase 4. It correctly lists the items that will be specified:
- `ApeRVTool(AbstractTool)` implementation
- Four variants
- `JarResolver` priority chain
- Registration mechanism

However, as of now, this is not a specification — it is an intention statement. The PRD already contains the full requirement detail for Phase 4 (FR14–FR17, Section 4.4). The spec should be written before Phase 4 implementation begins, following the SDD principle that specs precede and guide implementation.

**Missing content that should be here:**
- Data contracts: inputs (task parameters, device ID, APK info), outputs (CommandResult), side effects (ADB push)
- Invariants: JAR resolution priority, variant-to-flag mapping, fallback behavior
- Requirements with WHEN/THEN scenarios for each variant
- Error cases: JarNotFoundError, device not connected, ADB push failure
- Cross-reference to build/spec.md (the JAR this plugin deploys)

---

### 3.8 mop-guidance/spec.md

**Status: PLACEHOLDER — Contains no usable specification content.**

Same situation as `aperv-tool/spec.md`. The document acknowledges it is a placeholder for Phase 3. The PRD (FR11–FR13, Section 5.3) already contains detailed requirements.

**Missing content that should be here:**
- `MopData` JSON schema (the PRD Section FR11 has this, but it belongs in the spec)
- Cross-reference JSON format invariants
- `MopScorer` priority boost table as invariants (not just requirements)
- Graceful degradation scenarios (null `mopDataPath`, malformed JSON, missing handler match)
- The integration point in `StatefulAgent.adjustActionsByGUITree()` as a sequencing invariant

---

## 4. Cross-Artifact Consistency Analysis

This section evaluates consistency **across** the artifact set, treating them as a coherent whole.

### 4.1 Critical Inconsistencies

#### IC-01: Build Spec vs. PRD — Different target systems (CRITICAL)

`build/spec.md` specifies the Ant+dx+Java 1.7+`ape.jar` system. The PRD specifies replacing it with Maven+d8+Java 11+`ape-rv.jar`. These are **mutually exclusive** targets. A reader of the build spec alone would implement the wrong build system. This is the most severe inconsistency in the set.

**Resolution required:** The build spec must be rewritten to describe the Maven target system.

#### IC-02: MODEL_MENU missing from exploration, model, and ui-tree specs (MAJOR)

The PRD defines `MODEL_MENU` (FR09) as a first-class model action in Phase 2. Four specs are affected but none include it:

| Artifact | What's missing |
|----------|---------------|
| `exploration/spec.md` | MODEL_MENU row in ActionType table; INV-EXPL-05 count; SataAgent flowchart MENU step; scenario "State has unvisited MENU action" |
| `model/spec.md` | INV-MODEL-01 (State has one MODEL_BACK AND one MODEL_MENU); INV-MODEL-03 ("eight MODEL_* values" not "seven") |
| `ui-tree/spec.md` | MODEL_MENU added to resetActions() block list; corresponding invariant INV-TREE-XX |
| `build/spec.md` | Not applicable (build-level) |

#### IC-03: AndroidX ViewPager missing from ui-tree spec (MAJOR)

`PRD FR08` requires three class names for horizontal scroll. `ui-tree/spec.md` specifies only one (`android.support.v4.view.ViewPager`). An implementer following only the spec would miss two of the three class names.

#### IC-04: INV-MODEL-03 "seven MODEL_* values" will be incorrect after Phase 2

Currently correct (seven exist). After adding `MODEL_MENU`, this becomes wrong. The spec needs a forward-compatible statement or explicit per-phase versioning.

### 4.2 Minor Inconsistencies

#### IC-05: `doFuzzing` default value

`PRD FR10` says `ape.doFuzzing` default is `true`. `exploration/spec.md` configuration table also says `true`. **Consistent.** However, the CLAUDE.md says fuzzing is "enabled by default at 2% probability... disabled by default" — these two phrases in CLAUDE.md contradict each other. This is a CLAUDE.md issue, not a spec issue.

#### IC-06: PRD FR15 title vs. table

FR15 is titled "Four Supported Variants" but lists 5 rows. If `default` is treated as an alias for `sata`, this is 4 distinct variants plus 1 alias. The spec should clarify this explicitly.

#### IC-07: Phase 1 marked as `done` in PRD Gantt

The Gantt chart marks Phase 1 (Maven build) as `:done`. This is incorrect — no `pom.xml` exists in the repository. Gantt status markers should reflect reality.

#### IC-08: Exploration spec INV-EXPL-03 is missing

The invariant numbering jumps INV-EXPL-02 → INV-EXPL-04. Either an invariant was deleted without renumbering or there is a typo. Should be clarified.

### 4.3 Terminology Consistency

The following terms are used consistently across all specs — no ambiguity found:

| Term | Used consistently in |
|------|---------------------|
| `GUITree` | ui-tree, naming, model, exploration |
| `State` | model, naming, exploration |
| `StateKey` | model, naming |
| `NamingFactory` | naming, model |
| `Naming` | naming, model, exploration |
| `ModelAction` | model, exploration |
| `ActionType` | model, exploration, ui-tree |
| `SataAgent` | exploration, PRD |
| `StatefulAgent` | exploration, PRD, model |

No terminology drift detected.

---

## 5. Correspondence with Codebase

### 5.1 What the Specs Get Right (matches actual code)

| Claim | Verified in |
|-------|-------------|
| `requireTarget()` uses ordinal range `MODEL_CLICK ≤ ord ≤ MODEL_SCROLL_RIGHT_LEFT` | `ActionType.java` lines confirm this |
| `isModelAction()` uses ordinal range `MODEL_BACK ≤ ord ≤ MODEL_SCROLL_RIGHT_LEFT` | `ActionType.java` confirmed |
| `State` constructor creates `backAction = new ModelAction(this, ActionType.MODEL_BACK)` | `State.java` confirmed |
| `getScrollType()` returns `"horizontal"` for `android.support.v4.view.ViewPager` | `GUITreeNode.java` confirmed |
| `getScrollType()` returns `"none"` when `!isScrollable()` | `GUITreeNode.java` confirmed |
| RecyclerView excluded from horizontal scroll detection | `GUITreeNode.java` confirmed — it's not in the horizontal list |
| `MopData` and `MopScorer` are new classes (don't exist yet) | `ape/utils/` directory listing confirmed |
| `MODEL_MENU` does not exist yet | `ActionType.java` confirmed |

### 5.2 What the Specs Get Wrong (contradicts actual code)

| Claim | Actual state |
|-------|-------------|
| `build/spec.md` INV-BUILD-05: MUST use Java 1.7 | Correct for current code, but is the wrong target per PRD |
| `build/spec.md`: references `ape.jar` as output | Correct for current code, but target is `ape-rv.jar` |
| PRD Gantt: Phase 1 is `:done` | `pom.xml` does not exist, Phase 1 is not done |
| PRD Section 1.1: "140 Java source files" | Package table sums to 127 files |

### 5.3 What the Specs Don't Cover (gaps)

- `xpathaction` subpackage (`XPathAction`, `XPathActionController`, `XPathActionSequence`, `XPathlet`): **No spec covers this package.** It is briefly mentioned in PRD Section 4.2 but there is no `openspec/specs/xpathaction/` directory.
- `reducer/` package (delta debugging for crash minimization): Not covered by any spec.
- `ape.events` package (`ApeClickEvent`, `ApeDragEvent`, etc.): No spec covers event generation. The exploration spec mentions events at the level of `ApeEvent` subclasses but provides no invariants or data contracts for them.
- `ApeAPIAdapter.java` (reflection wrappers for `@hide` APIs): Not specced. This is Android-version-specific code that warrants at least a data contract documenting which APIs it wraps.

---

## 6. Quality Dimensions Summary

### 6.1 Narrative Quality

All specs are in English. All use narrative prose appropriate for human readers. The purpose sections in `naming/spec.md`, `model/spec.md`, and `exploration/spec.md` are particularly well written — they explain the *why* before the *what*. `build/spec.md` has the weakest purpose section (only describes mechanics, not motivation).

### 6.2 Diagram Coverage

| Spec | Mermaid diagrams | Assessment |
|------|-----------------|------------|
| PRD | 8 diagrams | Excellent coverage |
| exploration | 1 flowchart (SataAgent) | Adequate; ABA needs a diagram |
| naming | 1 sequence diagram (CEGAR) | Excellent |
| model | 0 | **Missing** — a state/class diagram would help |
| ui-tree | 0 | **Missing** — a class diagram would help |
| build | 0 | Missing; PRD diagram sufficient by reference |
| aperv-tool | 0 | Placeholder |
| mop-guidance | 0 | Placeholder |

### 6.3 Invariant Quality

| Spec | Invariant count | Quality |
|------|----------------|---------|
| naming | 12 | Excellent — verifiable, mathematical |
| model | 10 | Very good — precise, catches edge cases |
| exploration | ~7 active (1 missing number) | Good |
| ui-tree | 7 | Adequate — missing AndroidX, MODEL_MENU |
| build | 6 | Describes wrong target system |

### 6.4 Scenario Quality

All specs use the WHEN/THEN/AND scenario format consistently and correctly. The best scenarios (naming and model specs) have concrete values and address edge cases. The weakest scenarios (fuzzing rate in exploration, GUITreeWidgetDiffer in ui-tree) are too abstract to be mechanically verifiable.

---

## 7. Risks

### R-01 (HIGH): Build spec will mislead Phase 1 implementer

An agent executing Phase 1 using only `build/spec.md` as guidance would implement Ant improvements rather than the Maven migration. **Impact**: Phase 1 implemented incorrectly. **Mitigation**: Rewrite build spec before starting Phase 1.

### R-02 (HIGH): MODEL_MENU gaps will cause incomplete Phase 2

An implementer using `exploration/spec.md` and `model/spec.md` for Phase 2 will not know to:
- Add MODEL_MENU to `State` constructor
- Add MODEL_MENU to the SataAgent unvisited priority chain
- Block MODEL_MENU in `GUITreeNode.resetActions()`
- Update `isModelAction()` invariant counts

**Impact**: MODEL_MENU partially implemented. **Mitigation**: Update all three specs before Phase 2.

### R-03 (MEDIUM): AndroidX ViewPager gap in ui-tree spec

An implementer reading `ui-tree/spec.md` for Phase 2 will only add `android.support.v4.view.ViewPager` (already done!) rather than the two new AndroidX names. **Impact**: Phase 2 UI coverage improvement incomplete. **Mitigation**: Add INV-TREE-08 and INV-TREE-09 to ui-tree spec.

### R-04 (MEDIUM): Phase 1 Gantt marker is misleading

The `:done` marker on Phase 1 in the PRD Gantt may cause a reviewer to believe Phase 1 is complete and skip it during status reviews. **Impact**: Status tracking errors. **Mitigation**: Remove `:done` until the Maven build is verified.

### R-05 (LOW): Placeholder specs for Phases 3 and 4

`aperv-tool/spec.md` and `mop-guidance/spec.md` are empty. While this is acknowledged and acceptable for future phases, if implementation of Phases 3 or 4 begins before these specs are written, the SDD workflow principle (specs precede implementation) will be violated. **Mitigation**: Write these specs before starting implementation, using the PRD as input.

### R-06 (LOW): `GUITreeWidgetDiffer` matching algorithm unspecified

The differ is used by `NamingFactory` to identify discriminating widgets. Its matching logic (by node index? by resource-id? by tree position?) is undocumented. If a different team member implements the differ, they may choose a different matching strategy that breaks the refinement algorithm. **Mitigation**: Document the matching algorithm in ui-tree/spec.md or naming/spec.md.

---

## 8. Prioritized Recommendations

The following recommendations are ordered by impact on implementation quality:

### Priority 1 — Must fix before Phase 1 implementation

1. **Rewrite `build/spec.md`** to describe the Maven+d8+`ape-rv.jar` target. Retain a brief "current state" section labeled as such for context. Add:
   - Invariants for `pom.xml` structure and `d8` invocation
   - `INV-BUILD-07`: Output JAR MUST be named `ape-rv.jar`, not `ape.jar`
   - `INV-BUILD-08`: MUST use `--release 11` (not `source/target 1.7`)
   - `INV-BUILD-09`: `mvn install` MUST copy `target/ape-rv.jar` to `${aperv_tool_dir}`
   - Scenario: `mvn clean package` on Java 11, 17, and 21
   - Add the build pipeline diagram from PRD Section 4.5
   - Deprecation requirement for `build.xml`

2. **Fix PRD Gantt Phase 1 status**: Remove `:done` marker until Phase 1 is actually complete.

### Priority 2 — Must fix before Phase 2 implementation

3. **Add `MODEL_MENU` to `exploration/spec.md`**:
   - Add row in ActionType table
   - Update INV-EXPL-05 to include MODEL_MENU
   - Add MENU step to SataAgent flowchart
   - Add scenario "State has unvisited MENU action"

4. **Add `MODEL_MENU` to `model/spec.md`**:
   - Update INV-MODEL-01 (State has `backAction` AND `menuAction`)
   - Update INV-MODEL-03 ("eight MODEL_* values" or enumerate them)
   - Add State.menuAction to data contracts

5. **Add AndroidX ViewPager to `ui-tree/spec.md`**:
   - Add INV-TREE-08 for `androidx.viewpager.widget.ViewPager`
   - Add INV-TREE-09 for `androidx.viewpager2.widget.ViewPager2`
   - Add two scenarios in the ViewPager requirement section
   - Add MODEL_MENU to the `resetActions()` block list (invariant + scenario)

6. **Fix INV-EXPL-03 numbering gap** in `exploration/spec.md`.

### Priority 3 — Must fix before Phase 3 and 4 implementation

7. **Write `mop-guidance/spec.md`** from the PRD FR11–FR13 requirements. Include `MopData` data contracts, JSON schema invariants, `MopScorer` priority table as invariants, and graceful degradation scenarios.

8. **Write `aperv-tool/spec.md`** from PRD FR14–FR17. Include data contracts for `ApeRVTool`, all four variant configurations, JAR resolution chain as invariants, and `_register_external_tools()` lazy-import scenario.

### Priority 4 — Quality improvements (non-blocking)

9. **Add a class/component diagram to `model/spec.md`**: Show the relationship between `Graph`, `State`, `StateTransition`, `ModelAction`, `ActivityNode`, and `GUITree` with cardinalities.

10. **Add a class diagram to `ui-tree/spec.md`**: Show `GUITree → GUITreeNode (1..*)`, `GUITreeBuilder`, `GUITreeTransition`, and `ScrollType`.

11. **Add ABA navigation diagram to `exploration/spec.md`**: A state-machine diagram showing the A→B→A traversal with buffer semantics.

12. **Formalize `Namelet` definition in `naming/spec.md`**: Add an explicit "A Namelet is a pair `(xpath: String, namer: Namer)`" statement in the Purpose section.

13. **Clarify `GUITreeWidgetDiffer` matching algorithm**: Document whether nodes are matched by tree position, resource-id, or a combination, in either `ui-tree/spec.md` or `naming/spec.md`.

14. **Fix PRD file count discrepancy**: Reconcile "140 Java source files" with the package table total.

15. **Fix PRD FR15 title**: Change "Four Supported Variants" to "Five Variants (Four Distinct)" or restructure the table to make the `default`/`sata` alias relationship explicit.

---

## 9. Summary Scorecard

| Artifact | Correctness | Completeness | Depth | Consistency | Language/Diagrams |
|----------|-------------|-------------|-------|-------------|-------------------|
| PRD.md | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| build/spec.md | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ |
| exploration/spec.md | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| model/spec.md | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| naming/spec.md | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| ui-tree/spec.md | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| aperv-tool/spec.md | — | ⭐ | — | — | — |
| mop-guidance/spec.md | — | ⭐ | — | — | — |

*Scale: ⭐ = poor/placeholder, ⭐⭐ = critical issues, ⭐⭐⭐ = acceptable, ⭐⭐⭐⭐ = good, ⭐⭐⭐⭐⭐ = excellent. Dashes indicate placeholder artifacts not rated.*

---

## 10. Conclusion

The artifact set for APE-RV demonstrates strong foundational work, particularly in the PRD and the naming/model/exploration specs. The research context, experimental design, and technical depth of these documents are suitable for guiding AI-agent implementation under the SDD methodology.

The **critical issue** is that `build/spec.md` documents the wrong target system, which creates an implementation risk if an agent reads specs in isolation. The **major issue** is that MODEL_MENU and AndroidX ViewPager (both Phase 2 features) are absent from the specs that should govern their implementation, requiring four spec updates before Phase 2 begins.

The two placeholder specs (`aperv-tool`, `mop-guidance`) are acceptable for their current phase but must be written before implementation of Phases 3 and 4. Writing them before implementation is not optional under SDD — it is the central workflow principle.

With the corrections identified in Section 8, this artifact set would provide a complete, consistent, and implementation-ready specification for all five phases of APE-RV development.
