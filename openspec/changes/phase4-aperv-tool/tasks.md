<!-- Critical path: Group 1 (pom.xml) → Group 2 (rv-android change).
     This ape change only closes after the rv-android change (phase4-aperv-tool) is complete.
     Note: no automated test suite (CLAUDE.md). -->

## 1. ape/pom.xml — Install JAR Copy

- [ ] 1.1 In `pom.xml`, add a `maven-resources-plugin` execution bound to the `install` phase that copies `target/ape-rv.jar` to `${rvsec_home}/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`. Define `rvsec_home` property defaulting to `${user.home}/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec`. Use the `copy-resources` goal.
- [ ] 1.2 Run `mvn install -Drvsec_home=/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec` — must succeed and produce `ape-rv.jar` at `modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar` in the rv-android repo.

## 2. rv-android change — aperv-tool module + e2e

- [ ] 2.1 Create GitHub issue in rv-android repo for the aperv-tool Python module (references ape issue #3). Title: "Phase 4: Add aperv-tool Python module and rv-platform registration".
- [ ] 2.2 Run `/opsx:ff phase4-aperv-tool` in the rv-android repo context to generate all artifacts for the aperv-tool change (module structure, ApeRVTool implementation, rv-platform registration, e2e verification). Use design.md from this change as primary reference.
- [ ] 2.3 Run `/opsx:apply phase4-aperv-tool` in rv-android — implement all tasks: aperv-tool module files (pyproject.toml, __init__.py files, .gitignore, tool.py), rv-platform registration, uv sync, smoke test, ADB e2e run, lint, verify, code-review.
- [ ] 2.4 Confirm rv-android change is archived (`/opsx:archive phase4-aperv-tool` in rv-android).

## 3. Close-out (ape repo)

- [ ] 3.1 Run `/opsx:sync phase4-aperv-tool` — sync delta specs to main specs (pom.xml install hook).
- [ ] 3.2 Invoke `/sdd-code-reviewer` via Skill tool.
