# SDD Artifact Audit Report (Codex)

Date: 2026-03-11  
Repo: `workspace-rv/ape`  
Git HEAD: `8f51b99cbe85f4075f08c47a8db40ae7d862c716` (working tree contains many untracked files)

## 1. Scope and Method

This report rigorously audits the SDD-related artifacts you listed for:

- Internal consistency (PRD ↔ OpenSpec domain specs ↔ SDD workflow docs)
- Completeness, depth, ambiguity, and “fit-for-implementation” quality
- Consistency with the **current** source code in this repository
- Consistency with **current** referenced external projects (where locally available), specifically `rv-android`
- Diagram format (Mermaid vs non-Mermaid)
- Language and narrative style (English, human-oriented prose)

Artifacts reviewed (primary):

- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/agentes-claude/docs/SDD.md`
- `.sdd/SDD-WORKFLOW.md`
- `docs/PRD.md`
- `openspec/specs/aperv-tool/spec.md`
- `openspec/specs/build/spec.md`
- `openspec/specs/exploration/spec.md`
- `openspec/specs/model/spec.md`
- `openspec/specs/mop-guidance/spec.md`
- `openspec/specs/naming/spec.md`
- `openspec/specs/ui-tree/spec.md`

Code and repo reality checks (local):

- Build system: `build.xml`, presence/contents of `ape.jar`, file counts under `src/`
- CLI options: `src/com/android/commands/monkey/Monkey.java`
- Agents/strategies: `src/com/android/commands/monkey/ape/agent/*`
- Model/actions: `src/com/android/commands/monkey/ape/model/*`
- UI tree: `src/com/android/commands/monkey/ape/tree/GUITreeNode.java`
- Naming/refinement: `src/com/android/commands/monkey/ape/naming/*`

External repo spot-checks (local filesystem, not implemented here):

- `rv-android`: `../../../workspaces-doutorado/workspace-rv/rvsec/rv-android`
  - Verified existence of builtin APE tool at `modules/rv-tools/src/rv_tools/builtin/ape/tool.py`
  - Verified existence of external tool registration in `modules/rv-platform/src/rv_platform/__init__.py`
  - No evidence of an `aperv` module/plugin in current `rv-android` (consistent with “planned” status)

## 2. Executive Summary

The artifact set is **strong in structure and intent**, but **not yet consistent with the current codebase**. The most important issue is that the PRD and several “Current” specs describe **APE-RV as if Phase 1/2 capabilities exist** (Maven+d8 build, additional strategy variants, MODEL_MENU, AndroidX ViewPager detection), while the repository currently contains the **legacy APE implementation** with:

- Ant+dx build producing `ape.jar` (and a prebuilt `ape.jar` already present)
- Only two agent types exposed via CLI `--ape`: `sata` and `random` (plus `replay` via separate flag)
- No `bfs`, no `dfs`, no `ape` strategy option (despite PRD/spec claims)
- No `MODEL_MENU`
- No AndroidX ViewPager/ViewPager2 detection
- No MOP-guided scoring classes
- No `aperv-tool` Python module in this repo (and no `aperv` module found in the checked `rv-android` checkout)

In other words: **the specs and PRD read like a target-state design**, but the “current system” described by the specs largely corresponds to **pre-modernization APE**. This is a normal situation early in SDD (docs ahead of code), but it must be made explicit; otherwise, implementers and reviewers will be misled.

## 3. Strengths (What’s Working Well)

### 3.1 SDD Process Docs Are Clear and Actionable

- `SDD.md` is comprehensive and written for humans; it explains rationale, tradeoffs, and workflow context.
- `.sdd/SDD-WORKFLOW.md` provides a concrete operational workflow and clearly separates tracks (Full / Fast-Forward / Quick Path).

### 3.2 OpenSpec Domain Specs Use a Good Contract Structure

The domain specs consistently follow a contract-like pattern:

- Purpose
- Data Contracts (Input/Output/Side-effects/Error)
- Invariants with stable IDs (`INV-*`)
- Requirements with explicit WHEN/THEN/AND scenarios

Invariant IDs appear unique across the spec set (no duplicates detected).

### 3.3 Several “Current Code” Facts Are Correct and Verified

The artifacts correctly describe important parts of the current implementation, for example:

- **File count**: `find src -name '*.java' | wc -l` returns **140**
- **Package count**: unique Java `package` declarations are **9**:
  - `com.android.commands.monkey`
  - `com.android.commands.monkey.ape`
  - `com.android.commands.monkey.ape.agent`
  - `com.android.commands.monkey.ape.events`
  - `com.android.commands.monkey.ape.model`
  - `com.android.commands.monkey.ape.model.xpathaction`
  - `com.android.commands.monkey.ape.naming`
  - `com.android.commands.monkey.ape.tree`
  - `com.android.commands.monkey.ape.utils`
- **Ant build**: `build.xml` uses `source="1.7"` / `target="1.7"` and invokes `dx`.
- **Dalvik JAR**: `ape.jar` contains `classes.dex` and `file` identifies it as a Dalvik dex file.
- **Naming/refinement**: `NamingFactory.resolveNonDeterminism()` explicitly avoids refining BACK actions and blacklists highly non-deterministic actions after ≥3 divergences (matches the naming spec’s core narrative).
- **Priority hook**: `StatefulAgent.adjustActionsByGUITree()` exists and is called before action selection, which is a good and stable injection point for future guidance (e.g., MOP scoring).

## 4. Major Inconsistencies and Gaps (High Priority)

### 4.1 “Build Modernization” Is a PRD Requirement, Not Current Reality

PRD claims (target state):

- Maven + `d8` build producing `ape-rv.jar`
- `pom.xml` replacing `build.xml` (with Ant retained only as deprecated legacy)

Repo reality (current code):

- Only `build.xml` exists; no `pom.xml` in this repository.
- Build invokes `dx` and compiles for Java 1.7.
- Output artifact in repo is `ape.jar`, not `ape-rv.jar`.

Spec inconsistency:

- `openspec/specs/build/spec.md` is labeled **Current** in `openspec/specs/README.md`, but it specifies **only** Ant+dx producing `ape.jar`.
- The same `openspec/specs/README.md` claims traceability from `build` spec to PRD FR01/FR02/FR03, but FR01/FR02 are Maven-centric and are **not** covered by the build spec.

Risk:

- Implementers using specs as “source of truth” will not know whether to implement Maven+d8 or Ant+dx.

Suggested fix to artifacts (no implementation here):

- Reframe `openspec/specs/build/spec.md` explicitly as **Legacy Build (APE)**, and introduce a separate spec (or delta spec) for the Maven+d8 build (APE-RV target).
- Add an explicit “Status vs Code” banner at the top of PRD and each spec (e.g., `Status: Target (not implemented)` vs `Status: Implemented`), including the validating commit hash.

### 4.2 Strategy Support: PRD/Specs Describe 5 Strategies; Code Exposes 2 (+ replay)

PRD and `openspec/specs/exploration/spec.md` claim five strategies:

- `sata`, `ape`, `bfs`, `dfs`, `random`

Current code reality:

- `Monkey.java` usage string: `--ape [AGENT_TYPE(random,sata)]`
- `Monkey.java` sets `Config.set("ape.agentType", mApeAgent)` only when `--ape` is provided.
- `ApeAgent.createAgent(...)` supports:
  - `sata`
  - `random`
  - `replay` (but configured via `--ape-replay`, not via `--ape replay`)
  - default: `sata`
- There is no code path creating a dedicated BFS/DFS agent, and no “ape” agent type.

Risk:

- The PRD and exploration spec currently function as a *requirements document for planned work*, but are written as if these strategies already exist.

Suggested fix to artifacts:

- Update PRD FR05 and exploration spec to match current CLI behavior, or clearly mark BFS/DFS/APE as **planned variants** with explicit “not implemented” status.

### 4.3 RandomAgent Is Not a Uniform Random Baseline

Artifacts claim (PRD + exploration spec):

- RandomAgent is a “pure random baseline” that ignores the model graph and/or uniformly samples actions.

Current code reality:

- `RandomAgent` extends `StatefulAgent` (and therefore uses the model).
- Action selection is influenced by:
  - `adjustActionsByGUITree()` (base priority, unvisited boost, transition heuristics)
  - `State.randomlyPickAction(...)` which selects using **priority-weighted sampling**, not uniform sampling
  - buffer navigation logic (`selectNewActionFromBuffer()`), which is not “pure random”

Risk:

- Experimental claims (comparisons against “random baseline”) become invalid or at least ambiguous.

Suggested fix to artifacts:

- Either (a) redefine “random” formally as “random choice among model actions with priority weighting,” or (b) plan a true uniform-random agent and explicitly mark it as not implemented yet.

### 4.4 `sataModel.obj` Contents: Model vs Graph

Inconsistency across artifacts:

- `openspec/specs/exploration/spec.md` describes `sataModel.obj` as a serialized **Graph** object.
- `openspec/specs/model/spec.md` describes serialization of the **Model** object.

Current code reality:

- `StatefulAgent.saveGraph()` writes `oos.writeObject(model)` to `sataModel.obj`.

Suggested fix:

- Align all docs to “serialized `Model` (which contains/owns the graph)”.

### 4.5 Phase Labels and “Done” Status Are Misleading

In `docs/PRD.md`, the roadmap Gantt marks Phase 1 (Maven+d8 build) as `done`, but this repository does not yet contain the Maven build system or `ape-rv.jar`.

Risk:

- Reviewers may assume reproducibility/build modernisation is already achieved when it is not.

Suggested fix:

- Replace roadmap “done” markers with objective completion criteria, or link them to concrete evidence (e.g., `pom.xml` present + CI build).

## 5. Medium-Priority Issues (Quality, Ambiguity, Maintainability)

### 5.1 Diagram Format: Not All Diagrams Are Mermaid

Most diagrams are Mermaid, but `docs/PRD.md` contains important architectural diagrams as unlabeled code blocks (ASCII tree layouts), for example:

- Component map under “Java Component (ape-rv.jar)” (ASCII tree)
- `aperv-tool/` directory layout (ASCII tree)

These are human-readable, but they violate the “all diagrams are Mermaid” requirement if interpreted strictly.

Suggested fix:

- Convert these ASCII diagrams to Mermaid (`flowchart`, `classDiagram`, or `mindmap`) or explicitly label them as “directory listing examples” (not diagrams).

### 5.2 Terminology Drift: “APE-RV” vs “APE” vs Artifact Names

Artifacts alternate between:

- `ape.jar` (legacy) vs `ape-rv.jar` (target)
- “APE” (upstream) vs “APE-RV” (fork) while the current repo still matches upstream structure

Suggested fix:

- Add an explicit “Current State vs Target State” section in the PRD and in each spec.
- Adopt a stable naming convention and stick to it across artifacts (e.g., always call the current repo build output `ape.jar` until `ape-rv.jar` exists).

### 5.3 Specification Normative Keywords Are Inconsistent

Across specs, there is a mix of MUST/SHALL/SHOULD. This is not fatal, but it reduces clarity.

Suggested fix:

- Choose one normative convention (commonly RFC-style MUST/SHOULD/MAY) and apply consistently.

### 5.4 “External Projects” References Need Stronger Anchoring

The PRD references:

- `rv-tools/builtin/ape/tool.py` (verified to exist in the checked `rv-android` checkout)
- `rv-platform/__init__.py` `_register_external_tools()` (verified to exist)
- A new module `aperv-tool` and a specific workspace-relative layout (not present yet)

Suggested fix:

- Where possible, reference the actual module paths used by the current `rv-android` repo (e.g., `modules/rv-tools/...`) to reduce ambiguity.
- Add a “Workspace layout assumptions” section with explicit root paths and expected sibling relationships.

## 6. Artifact-by-Artifact Detailed Notes

### 6.1 `SDD.md` (methodology reference)

Strengths:

- Well written, narrative, human-oriented, and broadly complete.

Risks / gaps:

- It contains several quantitative claims (e.g., security flaw rates, RCT outcomes). If this document is treated as a research-grade reference, it should either cite sources or label these as anecdotal/illustrative.

### 6.2 `.sdd/SDD-WORKFLOW.md` (process guide)

Strengths:

- Clear, operational, and pragmatic (“fluid workflow, no phase gates”).

Potential improvement:

- Add an explicit “Artifact verification step” checklist (e.g., “before implementation: check PRD↔spec alignment, check spec↔code alignment for ‘current’ claims”).

### 6.3 `docs/PRD.md` (product and target-state requirements)

Strengths:

- Excellent narrative and a well-structured requirements section with FR/NFR IDs.
- Good Mermaid diagrams supporting architecture and pipelines.
- Useful “out of scope” section.

Key issues:

- Several FRs describe functionality that is not present in current code (Maven build, MODEL_MENU, AndroidX ViewPager2 detection, BFS/DFS strategy variants, MOP loader/scorer, aperv plugin).
- Some “current facts” and “target facts” are blended; Phase roadmap even suggests completion where the repository does not show it.

### 6.4 `openspec/specs/build/spec.md` (build)

Strengths:

- Accurately describes the existing Ant+dx build and its invariants.

Key issues:

- It is labeled “Current” but, in the PRD, Ant+dx is framed as legacy/broken under modern toolchains.
- It does not cover the Maven+d8 target build at all, yet is mapped to Maven-related PRD FRs via `openspec/specs/README.md`.

### 6.5 `openspec/specs/exploration/spec.md` (exploration)

Strengths:

- Good deep narrative of the exploration loop and where to inject priority guidance (`adjustActionsByGUITree`).

Key issues (code mismatch):

- Strategy list and selection semantics do not match `Monkey.java` or `ApeAgent.createAgent`.
- RandomAgent behavior is mischaracterized as “pure random baseline”.
- `sataModel.obj` typed as Graph rather than Model (conflicts with code and model spec).
- Graph stability threshold semantics differ slightly (`>` in code vs “reaches threshold” in spec).

### 6.6 `openspec/specs/model/spec.md` (model)

Strengths:

- Largely matches the code-level contracts (ActionType semantics, back action presence, serialization on teardown).

Watch-outs:

- Ensure the spec consistently distinguishes Graph vs Model (in code, the Model owns the naming manager and action history; serialization persists the Model).

### 6.7 `openspec/specs/ui-tree/spec.md` (UI tree)

Strengths:

- `resetActions()` blocking global action types is accurate and well specified.
- ViewPager legacy class handling matches code.
- The RecyclerView note is correct (and the current code does not special-case RecyclerView by class name).

Gap vs PRD:

- PRD requires AndroidX ViewPager/ViewPager2 detection; current code does not implement it.

### 6.8 `openspec/specs/naming/spec.md` (naming/refinement)

Strengths:

- The description of non-determinism handling aligns with the existing implementation (e.g., skip BACK refinement, NDActionBlacklist behavior).
- The lattice completeness invariant is consistent with `NamerLattice` checks.

Potential improvement:

- Add a short “glossary” for terms like “strong transition”, “non-determinism”, “divergence”, “refinement vs abstraction” to reduce interpretive ambiguity.

### 6.9 `openspec/specs/mop-guidance/spec.md` and `openspec/specs/aperv-tool/spec.md` (planned)

Strengths:

- Correctly labeled “Not yet implemented” and scoped as future phases.

Gaps:

- As planned specs, they are currently outlines rather than full specs; this is fine, but they should not be referenced as implemented behavior elsewhere (PRD currently does a decent job of scoping them as phases).

## 7. Risk Register (Practical Consequences)

### R1: Specs/PRD ahead of code (unmarked)

- Consequence: wasted implementation effort, incorrect reviews, and broken reproducibility claims.

### R2: Experimental validity risk (RandomAgent baseline)

- Consequence: coverage comparisons and published results could be questioned if “random baseline” is not actually random.

### R3: Build reproducibility risk

- Consequence: inability to reproduce builds on modern toolchains, undermining auditability and extension.

### R4: Cross-repo integration ambiguity (rv-android plugin)

- Consequence: plugin integration work may target wrong paths/APIs if repo layouts drift.

## 8. Prioritized Recommendations (Artifact Improvements Only)

1. **Add explicit status banners** to PRD and each spec: `Implemented`, `Partially Implemented`, `Planned`, with last-verified commit hash.
2. **Split build documentation** into:
   - Legacy Ant+dx spec (`ape.jar`)
   - Target Maven+d8 spec (`ape-rv.jar`) as planned/delta
3. **Correct exploration strategy claims** to match the current `Monkey.java` CLI and `ApeAgent.createAgent()`; move BFS/DFS/APE to planned.
4. **Correct RandomAgent characterization** (or plan/define a true uniform baseline).
5. **Unify `sataModel.obj` typing** (Model vs Graph) across all artifacts.
6. **Convert ASCII “diagrams” to Mermaid** (or explicitly categorize them as code listings).
7. **Add a glossary and stronger definitions** for terms reused across specs (strong transition, saturation, stability).

## 9. What’s Missing (If the Goal Is “Implementation-Ready” SDD)

If the next step were implementation (not requested here), the artifacts would benefit from:

- A delta spec explicitly describing *exactly* what changes from “current APE” to “APE-RV Phase 1/2”.
- A cross-artifact traceability table linking each PRD FR to:
  - the spec section implementing it
  - the code files/symbols that satisfy it
  - verification steps/tests (even if manual)

Right now, the PRD contains that mapping conceptually, but the OpenSpec specs do not consistently align with the “Phase 1/2 target” requirements.

---

## Appendix A: Quick Evidence Pointers (Selected)

- Ant build: `build.xml` uses Java 1.7 + `dx`; output `ape.jar` contains `classes.dex`.
- `--ape` accepted values: `src/com/android/commands/monkey/Monkey.java` shows `random,sata` in usage and sets `ape.agentType`.
- Strategy construction: `src/com/android/commands/monkey/ape/agent/ApeAgent.java` supports `sata`, `random`, and `replay`.
- No `MODEL_MENU`: `src/com/android/commands/monkey/ape/model/ActionType.java` contains no `MODEL_MENU`.
- ViewPager detection is legacy-only: `src/com/android/commands/monkey/ape/tree/GUITreeNode.java` checks only `android.support.v4.view.ViewPager`.
- `sataModel.obj` serializes the Model: `src/com/android/commands/monkey/ape/agent/StatefulAgent.java` writes `oos.writeObject(model)`.
- rv-android builtin APE tool exists: `rv-android/modules/rv-tools/src/rv_tools/builtin/ape/tool.py` (local checkout).
- rv-platform external tool registration exists: `rv-android/modules/rv-platform/src/rv_platform/__init__.py` (local checkout).

