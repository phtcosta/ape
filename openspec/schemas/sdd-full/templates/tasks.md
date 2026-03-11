<!-- Subagent dispatch hints (for changes touching 20+ files):
     - Group N (Name) must complete first — Groups X, Y depend on it.
     - Groups X, Y are independent and can run in parallel after Group N.
     - Group Z integrates everything — must run after all other groups.
     - Critical path: N -> X -> Z.
     - This change touches NN files — use subagent orchestration (M parallel dispatches). -->

## 1. <!-- Foundation Group (e.g., Data Models, Configuration) -->

- [ ] 1.1 <!-- Task description (models, config, constants) -->
- [ ] 1.2 <!-- Task description -->
- [ ] 1.3 Add unit tests for new models/config
- [ ] 1.4 Run `/sdd-test-run <module>`

## 2. <!-- Core Implementation Group -->

- [ ] 2.1 <!-- Task description (core logic) -->
- [ ] 2.2 <!-- Task description -->
- [ ] 2.3 Add unit tests for core implementation
- [ ] 2.4 Run `/sdd-doc-code <new-file-path>` <!-- for new source files/classes -->
- [ ] 2.5 Run `/sdd-test-run <module>`

## 3. <!-- Additional Groups as needed -->

- [ ] 3.1 <!-- Task description -->
- [ ] 3.2 <!-- Task description -->
- [ ] 3.3 Run `/sdd-verify <module>` <!-- intermediate checkpoint after major group -->

## 4. <!-- Integration & Verification -->

- [ ] 4.1 Add integration tests
- [ ] 4.2 Run `/sdd-qa-lint-fix <module>`
- [ ] 4.3 Run `/sdd-verify <module>`
- [ ] 4.4 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 4.5 Run `/sdd-docs-sync <module>` <!-- if CLAUDE.md or architecture docs need updating -->
