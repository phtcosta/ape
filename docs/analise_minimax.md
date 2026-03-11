# APE-RV SDD Artifacts Analysis Report

**Date:** 2026-03-11  
**Project:** APE-RV (Android Property Explorer - Runtime Verification)  
**Analysis Type:** Rigorous artifact verification following SDD methodology  

---

## Executive Summary

This report presents a comprehensive analysis of the SDD artifacts created for the APE-RV project. The analysis evaluates consistency between artifacts, content quality and depth, missing information, ambiguities, alignment with current source code reality, diagram usage, and language compliance.

**Key Findings:**
- **Critical inconsistency:** PRD describes a Maven/d8 build system that does not exist in the codebase (only Ant/build.xml exists)
- **Specification reality gap:** Several specs describe planned features (MODEL_MENU, ViewPager2, MOP guidance, aperv-tool plugin) that are NOT yet implemented
- **Missing artifacts:** No OpenSpec change artifacts exist under `openspec/changes/`
- **Diagrams:** Most artifacts use Mermaid correctly, but some lack important diagrams
- **Language:** All artifacts are in English with narrative text — compliant with SDD standards

---

## 1. Artifact Inventory

| Artifact | Location | Status | Lines |
|----------|----------|--------|-------|
| PRD.md | `docs/PRD.md` | Active | 663 |
| build/spec.md | `openspec/specs/build/spec.md` | Active | 118 |
| exploration/spec.md | `openspec/specs/exploration/spec.md` | Active | 390 |
| model/spec.md | `openspec/specs/model/spec.md` | Active | 245 |
| naming/spec.md | `openspec/specs/naming/spec.md` | Active | 346 |
| ui-tree/spec.md | `openspec/specs/ui-tree/spec.md` | Active | 113 |
| aperv-tool/spec.md | `openspec/specs/aperv-tool/spec.md` | Not implemented | 18 |
| mop-guidance/spec.md | `openspec/specs/mop-guidance/spec.md` | Not implemented | 17 |

---

## 2. Consistency Analysis

### 2.1 PRD vs. Source Code Reality

| PRD Claim | Source Code Reality | Consistency |
|-----------|---------------------|-------------|
| Maven build with d8 | No `pom.xml` exists; only `build.xml` (Ant) | **INCONSISTENT** |
| AndroidX ViewPager2 detection | Only `android.support.v4.view.ViewPager` in code | **INCONSISTENT** |
| MODEL_MENU action | No `MODEL_MENU` in `ActionType.java` | **INCONSISTENT** |
| MopData/MopScorer classes | No such classes exist in codebase | **INCONSISTENT** |
| Five exploration strategies | Code shows: sata, ape, bfs, dfs, random | CONSISTENT |
| ActionType.MODEL_BACK | Exists in codebase | CONSISTENT |

**Critical Issue:** The PRD describes Phase 1 as "Build modernization: Replace Ant+dx with Maven+d8+Java 11" but the repository still uses Ant exclusively. This creates false expectations about the project's current state.

### 2.2 Cross-Spec Consistency

| Spec Pair | Consistency Issue |
|-----------|-------------------|
| build/spec.md vs. PRD | build/spec.md describes Ant/dx (accurate for current state), PRD describes Maven/d8 (future state) — contradictory |
| exploration/spec.md vs. model/spec.md | Both describe ACTION_BACK correctly; exploration references model invariants correctly |
| naming/spec.md vs. ui-tree/spec.md | Consistent on ViewPager handling; naming references tree structures correctly |
| aperv-tool/spec.md vs. mop-guidance/spec.md | Both marked "Not yet implemented" — consistent placeholder status |

### 2.3 PRD Internal Consistency

The PRD has internal logical flow:
- Phase 1 → Phase 2 → (optional Phase 3) → Phase 4 → Phase 5
- Research questions align with phases
- Roadmap diagrams correctly show dependencies

**Issue:** The roadmap shows Phase 3 as optional but the MOP-guided features are heavily referenced throughout the document as core capabilities.

---

## 3. Content Quality and Depth

### 3.1 PRD Quality Assessment

**Strengths:**
- Comprehensive ecosystem context (RVSEC diagram showing relationships)
- Detailed functional requirements with traceable FR01-FR19
- Clear non-functional requirements (NFR01-NFR06)
- Research questions with hypotheses
- Experimental design parameters
- Package structure with file counts

**Weaknesses:**
- **Stale information:** Describes Maven build as completed/ongoing when no pom.xml exists
- **ActionType reference error:** FR09 mentions "MODEL_MENU" but ActionType.java in current code does not have this value
- **Outdated claims:** "ape.jar" should be "ape-rv.jar" throughout, but terminology is inconsistent

### 3.2 Build Spec Quality Assessment

**Strengths:**
- Clear input/output contracts
- Invariant definitions (INV-BUILD-01 through INV-BUILD-06)
- Error scenarios well documented
- Validates against actual Ant build behavior

**Weaknesses:**
- Does not acknowledge the planned Maven migration described in PRD
- No diagram showing build flow (unlike PRD which has Mermaid)
- Missing test scenarios for edge cases

### 3.3 Exploration Spec Quality Assessment

**Strengths:**
- Excellent use of Mermaid flowchart for SataAgent action selection
- Comprehensive configuration table with types and defaults
- Detailed invariant definitions (INV-EXPL-01 through INV-EXPL-12)
- WHEN/THEN scenario format consistent throughout
- Covers all five strategies with clear requirements

**Weaknesses:**
- **Missing diagram:** No sequence diagram for the exploration loop itself
- INV-EXPL-03 is referenced but never defined in the document
- The spec says "ActionType.MODEL_MENU" is planned (FR09 in PRD) but exploration spec doesn't include MENU in action tables

### 3.4 Model Spec Quality Assessment

**Strengths:**
- Well-structured data contracts
- Strong invariant definitions (INV-MODEL-01 through INV-MODEL-10)
- State creation and transition requirements clearly specified
- WHEN/THEN scenarios are precise

**Weaknesses:**
- No Mermaid diagrams — purely textual
- Some error conditions reference exceptions that may not exist in current code

### 3.5 Naming Spec Quality Assessment

**Strengths:**
- Excellent sequence diagram for CEGAR refinement flow (lines 21-69)
- Complex concepts (NamerLattice, refinement) clearly explained
- Comprehensive invariant definitions
- Good balance of formal specification and narrative explanation

**Weaknesses:**
- Some complexity may be overwhelming for new developers
- No diagram showing the Namer lattice structure visually

### 3.6 UI-Tree Spec Quality Assessment

**Strengths:**
- Clear purpose statement
- Good invariant definitions
- Covers ViewPager and RecyclerView correctly

**Weaknesses:**
- Very brief (113 lines vs. 390 for exploration)
- Missing invariants for AndroidX ViewPager2 (currently only supports legacy)
- No diagrams

### 3.7 Aperv-tool and MOP-Guidance Specs

Both are placeholder documents (18 and 17 lines respectively) stating "Not yet implemented." This is appropriate for planned-but-not-started features, but they provide insufficient detail for implementation guidance when Phase 3/4 begins.

---

## 4. Missing Information

### 4.1 Critical Gaps

| Gap | Impact | Recommendation |
|-----|--------|----------------|
| No Maven/pom.xml in codebase | PRD describes non-existent build | Update PRD to reflect Ant-only current state, OR implement Maven build |
| No MODEL_MENU implementation | FR09 cannot be verified | Implement the feature or mark PRD section as "not started" |
| No ViewPager2 detection | UI coverage improvement incomplete | Implement AndroidX support or document limitation |
| No MopData/MopScorer | Phase 3 cannot proceed | Implement classes or adjust roadmap |
| No aperv-tool plugin | Phase 4 cannot proceed | Create Python module or adjust roadmap |

### 4.2 Documentation Gaps

1. **No test strategy:** No mention of how to test the APE-RV components
2. **No CI/CD:** No mention of build automation
3. **No migration guide:** How to move fromape.jar to ape-rv.jar
4. **No version compatibility matrix:** Java versions vs. Android API levels tested

### 4.3 OpenSpec Workflow Gaps

- No change artifacts in `openspec/changes/` directory
- No delta specs showing what's been implemented vs. planned
- No verification artifacts

---

## 5. Ambiguities

### 5.1 Language Ambiguities

| Location | Ambiguity | Resolution |
|----------|-----------|------------|
| PRD line 8 | "ape-rv.jar" vs "aperv" plugin — inconsistent naming | Standardize on "ape-rv.jar" for JAR, "aperv" for plugin ID |
| PRD FR09 | "MODEL_MENU as systematic model action" — unclear if this is implemented | Verify implementation status |
| PRD line 84 | "mvn install copies to aperv-tool" but aperv-tool doesn't exist | Either create the module or update PRD |
| exploration/spec.md INV-EXPL-03 | Referenced but never defined | Add the missing invariant |

### 5.2 Technical Ambiguities

1. **Refinement algorithm:** naming/spec.md describes CEGAR but doesn't specify convergence criteria
2. **Memory bounds:** No explicit limit on GUITree retention mentioned
3. **Timeout behavior:** What happens when --running-minutes is reached mid-action?

---

## 6. Alignment with Source Code Reality

### 6.1 Verified Code Existence

| Artifact Claim | Verified in Code | Notes |
|----------------|-------------------|-------|
| SataAgent class | YES | `src/.../ape/agent/SataAgent.java` |
| ActionType enum | YES | Contains MODEL_BACK, MODEL_CLICK, scroll types |
| GUITreeNode.getScrollType() | YES | Contains ViewPager detection |
| StatefulAgent | YES | Base class for SataAgent |
| Config class | YES | 100+ configuration flags |
| NamingFactory | YES | CEGAR implementation |
| Model class | YES | Graph management |

### 6.2 Verified NON-Existence

| Artifact Claim | Verified NOT in Code | Notes |
|----------------|----------------------|-------|
| Maven pom.xml | NO | Only Ant build.xml exists |
| MODEL_MENU | NO | Not in ActionType enum |
| ViewPager2 detection | NO | Only legacy ViewPager |
| MopData class | NO | Does not exist |
| MopScorer class | NO | Does not exist |
| aperv-tool Python module | NO | Not in repository |

### 6.3 Conclusion on Reality Gap

**The artifacts describe a FUTURE state of the project that does not yet exist.** This is problematic because:

1. PRD reads as if features are implemented or in-progress
2. No clear way to verify implementation against specs
3. The "spec-anchored" SDD approach requires code to exist for verification

---

## 7. Diagram Analysis

### 7.1 Diagram Usage by Artifact

| Artifact | Mermaid Diagrams | Type | Quality |
|----------|-----------------|------|---------|
| PRD.md | YES | block-beta, graph TD, gantt | Good |
| build/spec.md | NO | — | Missing |
| exploration/spec.md | YES | flowchart TD | Good |
| model/spec.md | NO | — | Missing |
| naming/spec.md | YES | sequenceDiagram | Excellent |
| ui-tree/spec.md | NO | — | Missing |
| aperv-tool/spec.md | NO | — | N/A (placeholder) |
| mop-guidance/spec.md | NO | — | N/A (placeholder) |

### 7.2 Missing Important Diagrams

1. **build/spec.md:** Should have build flow diagram
2. **model/spec.md:** Should show State/StateTransition/Graph relationships
3. **ui-tree/spec.md:** Should show GUITree node hierarchy
4. **exploration/spec.md:** Missing overall exploration loop sequence diagram

---

## 8. Language Compliance

### 8.1 English Language

All artifacts are in English. ✅

### 8.2 Narrative Text

| Artifact | Narrative Style | Compliance |
|----------|-----------------|------------|
| PRD.md | Descriptive paragraphs, bullet points | ✅ |
| All spec.md | WHEN/THEN scenarios, formal tone | ✅ |
| All | Uses RFC 2119 keywords (MUST, SHALL, SHOULD) | ✅ |

---

## 9. Strengths, Weaknesses, Risks, and Recommendations

### 9.1 Strengths

1. **Comprehensive requirements:** PRD has 19 functional requirements with clear traceability
2. **Well-structured specs:** OpenSpec format is consistent across all domain specs
3. **Good invariant coverage:** Most specs define invariants that can be verified
4. **Research integration:** Good connection to academic context (ICSE 2019, PhD thesis)
5. **Ecosystem awareness:** PRD correctly positions APE-RV in RVSEC context

### 9.2 Weaknesses

1. **Reality gap:** Multiple claims about unimplemented features presented as if implemented
2. **Inconsistent terminology:** ape.jar vs. ape-rv.jar, aperv vs. APE-RV
3. **Missing diagrams:** Three specs lack any Mermaid diagrams
4. **Placeholder specs:** aperv-tool and mop-guidance provide no implementation guidance

### 9.3 Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Build mismatch causes confusion | HIGH | Implement Maven build or update PRD |
| Features don't exist blocks verification | HIGH | Implement planned features or adjust specs |
| Naming drift between docs and code | MEDIUM | Create delta spec sync process |
| Placeholder specs delay Phase 3/4 | MEDIUM | Expand specs before implementation begins |

### 9.4 Recommendations

#### Immediate Actions

1. **Fix build description:** Either implement Maven build OR update PRD to reflect Ant-only current state
2. **Add MODEL_MENU to ActionType:** Implement the feature or clearly mark FR09 as not started
3. **Add ViewPager2 support:** Implement AndroidX detection in GUITreeNode.getScrollType()
4. **Create delta specs:** Document what's actually implemented vs. planned in `openspec/changes/`

#### Medium-term Improvements

5. **Expand placeholder specs:** aperv-tool and mop-guidance need full specifications before Phase 3/4
6. **Add missing diagrams:** Add Mermaid to build, model, and ui-tree specs
7. **Define INV-EXPL-03:** Add the missing invariant definition
8. **Create test strategy:** Add testing requirements to PRD

#### Long-term Improvements

9. **Implement MopData/MopScorer:** Core to Phase 3 research questions
10. **Implement aperv-tool:** Required for rv-android integration
11. **Set up CI/CD:** Automate builds and tests

---

## 10. Summary Scores

| Criterion | Score (1-5) | Notes |
|-----------|-------------|-------|
| Consistency with source code | 2 | Many claims not verified in code |
| Internal consistency | 3 | Some cross-spec contradictions |
| Content depth | 4 | Good coverage of existing features |
| Diagram usage | 3 | 50% of specs missing diagrams |
| Language compliance | 5 | English, narrative, RFC 2119 |
| Ambiguity level | 3 | Some unclear claims |
| Practical utility | 2 | Reality gap limits usefulness |

**Overall Assessment:** The artifacts provide a good foundation but require significant updates to align with actual project state. The gap between documented features and implemented code is the primary concern.

---

## Appendix A: Verification Commands Used

```bash
# Verify Java files exist
ls src/com/android/commands/monkey/ape/**/*.java | wc -l
# Result: ~140 files

# Check for Maven
ls pom.xml 2>/dev/null || echo "No pom.xml found"
# Result: No pom.xml found

# Check for MODEL_MENU
grep -r "MODEL_MENU" src/
# Result: Not found

# Check for ViewPager2
grep -r "ViewPager2" src/
# Result: Not found

# Check for MopData
grep -r "MopData" src/
# Result: Not found

# Check for aperv-tool
ls -d */aperv-tool 2>/dev/null || echo "Not found"
# Result: Not found
```

---

*Report generated following SDD verification methodology. Next step: Resolve reality gap by implementing planned features or updating documentation to reflect current state.*
