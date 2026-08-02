# Tasks: rearch-02-runspec

<!-- Subagent dispatch hints:
     - Group 1 (RunSpec/Feature/Presets core) and Group 2 (build stamp) are independent — parallel.
     - Group 3 (bootstrap wiring) depends on 1+2. Group 4 (echo/RunContext) depends on 3.
     - Group 5 (removals) depends on 3 (retired-key validation must exist before keys are retired).
     - Group 6 (compat fixtures) depends on 1; can run parallel to 4-5.
     - Groups 7-8 integrate — sequential, last.
     - Critical path: 1 -> 3 -> 4/5 -> 7.
     - This change touches ~25 files — subagent orchestration reasonable (2-3 parallel dispatches max). -->

Gate: this change may only be applied after `rearch-01-parity-oracle` is implemented and its per-preset goldens are green at HEAD (roadmap stage order). Every task below is jar-side only — **zero Python changes** (design D-4; verified explicitly in task 6.4).

## 1. RunSpec / Feature / Presets core (pure JVM, no wiring yet)

- [ ] 1.1 Create `Feature` enum (`ape.runtime` package): constants per design D-2 with activation rule, owned keys, `EnumSet` dependencies (`MENU_GATEWAY→{MODEL_MENU,MOP}`, MOP family→`MOP`, LLM modes→`LLM`), and neutral values as constructor data
- [ ] 1.2 Create the key-ownership table: every `ape.*` key owned by exactly one of `ExplorationParams` / one `Feature` / the resolver (`ape.preset`, `ape.runId`); include the retired-key constant with per-key reason strings (`ape.apePureMode`, `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.enableXPathAction`, `ape.mopWeightActivity`, file-borne `ape.agentType`/`ape.replayLog`)
- [ ] 1.3 Create `RunSpec` + nested `ExplorationParams`/`MopParams`/`LlmParams`/`TelemetryParams` (final classes, private constructors, hand-rolled immutability; params null iff feature absent) and `RunSpecException(reason, key, detail)`
- [ ] 1.4 Implement `RunSpec.resolve(fileEntries, cli)`: preset merge → type parsing (strict booleans, aborting numerics; existing clamps preserved as clamps) → feature derivation (defaults never activate an impossible feature) → dependency/combination validation → inert-neutral rule → digest + propertiesDigest (SHA-256, design D-6)
- [ ] 1.5 Implement `Presets.resolve("aperv"|"mop"|"llm"|"llm_mop")` with vectors translated from the current `tool.py` arm dicts (design D-3; deployment-specific keys excluded)
- [ ] 1.6 Unit tests: key-ownership totality (every Config-known key owned; test fails on unowned key); feature derivation and dependency matrix; neutral-inert vs non-neutral-abort (incl. `ape.llmPercentageNoSubstrate=-1` on a non-LLM plan); preset+override merge; unknown preset; digest determinism (key order / file split / preset-vs-explicit invariance; seed excluded)
- [ ] 1.7 Unit tests: abort matrix — unknown key, non-`ape.` key, each retired key with its message, bad int, bad boolean (`ture`), `llmOnNewState=true` without `llmUrl`, `activityTriggerEnabled=true` without `mopDataPath`, `mopWeightOpenMenu>0` with `modelMenuEnabled=false`, replay without log
- [ ] 1.8 Run `mvn test` (new tests green, suite unbroken)

## 2. Build provenance stamp (independent of group 1)

- [ ] 2.1 `pom.xml`: add `git-commit-id-maven-plugin` (`failOnNoGitDirectory=false`, sentinel `unknown`, UTC) and `templating-maven-plugin` (`filter-sources`), per the archived gh14 wiring
- [ ] 2.2 Create `src/main/java-templates/com/android/commands/monkey/ape/runtime/BuildInfo.java` with `GIT_SHA` / `JAR_BUILT` constants and private constructor (no `SCHEMA`, no `[APE-BUILD]` banner — design D-8)
- [ ] 2.3 `mvn clean package`: assert generated `BuildInfo` has no `${…}` residue; short sha findable via `unzip -p target/ape-rv.jar classes.dex | strings`; no new jar resources
- [ ] 2.4 Unit test `BuildInfoTest` (constants non-null/non-placeholder)

## 3. Bootstrap wiring + fail-fast

- [ ] 3.1 `Monkey.processOptions`: `--ape` stores the raw value for the resolver (no `Config.set`); **delete** the `--ape-model` branch; `--ape-replay` stores the log path for the resolver
- [ ] 3.2 `Monkey.run` (`mUseApe` branch): call `RunSpec.resolve` before `MonkeySourceApe` construction; on `RunSpecException` print `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<msg>` to stderr and stdout and return nonzero — no agent, no device interaction, no step
- [ ] 3.3 `ApeAgent.createAgent(MonkeySourceApe, RunSpec)`: switch on the validated `agentType` (`sata`/`random`/`replay`); delete the silent `SataAgent` fallback and the replay `System.exit(1)`; always `new Graph()` (no model file)
- [ ] 3.4 Unit tests: `--ape bfs`/`dfs`/`ape`/garbage abort naming `{sata, random, replay}`; absent `--ape` defaults to `sata`; `ape.agentType` in a file aborts (retired key)
- [ ] 3.5 Run `mvn test`

## 4. RunContext + RUN_START echo

- [ ] 4.1 Create `RunContext`: `initialize(spec, seed)` once (second call throws), `current()`, `installForTest(spec)`, `spec()`, `runId()`, seeded RNG identity (performs the single `RandomHelper.seed` call, replacing the call in `Monkey.run`); runId self-generation (`<utc>-<seed>-<digest-prefix>`) with `ape.runId` override
- [ ] 4.2 Create the minimal one-line JSON serializer (strings/numbers/booleans/flat maps/arrays; escapes quote/backslash/control chars; never emits a raw newline) — the stage-4 seed serializer
- [ ] 4.3 Create `RunSpecEcho.emit`: `RUN_START` object per design D-7 (`type,v,run_id,seed,agent,preset,features,params,inert,digest,props_digest,build`); wire into `Monkey.run` immediately after `RunContext.initialize`, before `MonkeySourceApe` construction
- [ ] 4.4 Unit tests: echo content reconstructs each of the four preset plans (report test 9.6); single-line + escaping round-trip on hostile values; `inert` list content; emitted before any agent construction (order test via stream capture); double-initialize throws
- [ ] 4.5 Run `/sdd-doc-code` on the new `ape.runtime` classes (RunSpec, Feature, Presets, RunContext, RunSpecEcho, serializer)
- [ ] 4.6 Run `mvn test`

## 5. Removals (D6 readers, persistence protocol, kill-switch, non-final fields)

- [ ] 5.1 `StringCache`: delete the `/sdcard/ape.strings` static initializer (and its `RuntimeException` path); `maxStringListSize = Config.maxStringListSize`; `nextString()` index drawn from seeded `RandomHelper` (delete the `ThreadLocalRandom` import/use — V23)
- [ ] 5.2 `GUITreeBuilder`: delete the `xPathlets` static block, field, and uses (`/sdcard/ape.xpath`)
- [ ] 5.3 Delete the `ape.model.xpathaction` package (6 files), the `enableXPathAction` branch in `StatefulAgent` (`:480-482`), and `Config.enableXPathAction`
- [ ] 5.4 Delete `StatefulAgent.saveGraph()` + its `safeStep`; delete `Graph.readGraph`; delete `Config.saveObjModel/saveDotGraph/saveVisGraph`; delete now-caller-less `Graph.printDot`/`printVis` (verify no other caller first); teardown chain becomes `llmSummary → superTearDown → coverageDump → saveActionHistory → actionCounters → activityNodes → namingDump → modelCounters`
- [ ] 5.5 Update/replace the teardown-order test: coverage dump strictly before `saveActionHistory` (restated INV-COV-10 boundary)
- [ ] 5.6 `Config`: delete `apePureMode` field, the static-block forcing call, `forceApePureModeInto`, `rvForcedOffValues`, `rvUnsetKeys`, `rvExemptReasons`; update/delete the registry guard tests (superseded by the key-ownership totality test of task 1.6)
- [ ] 5.7 Make the five non-final fields final-or-gone: delete `mopWeightOpenMenu`, `fuzzInputTyped`, `mopStrictPackageMatch`, `activityTriggerEnabled`, `mopFrontierWeight` from `Config`; read sites (MopScorer, ApeFuzzer, MopData.load, SataAgent/StatefulAgent launcher gate, MopFrontierPass) consult `RunContext.current().spec()`; migrate the tests that toggled them to `RunSpec` test-factory + `installForTest`
- [ ] 5.8 Unit tests: `StringCache` seeded determinism (same seed ⇒ same string sequence; empty cache never throws); absence guards (no `ThreadLocalRandom` in `src/main`, no `/sdcard/ape.xpath|ape.xpath.actions|ape.strings` literals outside comments)
- [ ] 5.9 Run `mvn test` (full suite; expect and fix fallout from deleted fields/tests only)

## 6. Python-contract compatibility (fixtures, no Python edits)

- [ ] 6.1 Generate per-arm fixture properties files reproducing `_push_properties` output for the 4 campaign arms (`sata`, `sata_mop_widget`, `sata_llm`, `sata_mop_llm`) and for `ape_pure`; commit under the test tree with a note pinning the source (`tool.py` at rvsec HEAD, date)
- [ ] 6.2 `RunSpecCompatTest`: the 4 campaign fixtures resolve successfully with the expected feature sets/params; the `ape_pure` fixture aborts with `retired_key ape.apePureMode`; a fixture with `ape.mopWeightActivity` aborts with its retired-key message
- [ ] 6.3 `PresetsTest`: `Presets.resolve(name)` + the deployment-specific keys ≡ the corresponding campaign fixture's resolved plan (same digest) — pins design D-3 until stage 5
- [ ] 6.4 Verify zero Python changes needed: `git -C <rvsec>/rv-android status --porcelain` clean w.r.t. this change; document in the task log that `APERV_PROPERTY_MAPPING`, arm dicts, and `_push_properties` are untouched and the 4 campaign arms run against the stage-2 jar

## 7. Gates and checkpoints

- [ ] 7.1 Parity-oracle gate: run the rearch-01 golden suite per preset against the stage-2 jar — green for `aperv`/`mop`/`llm`/`llm_mop`. If any fixture exercises a populated string list, confirm it is in the sanctioned-divergence set (design D-9) and document; byte-parity everywhere else
- [ ] 7.2 `mvn clean package`: `target/ape-rv.jar` builds; vendored jars still excluded; dex contains the build sha
- [ ] 7.3 `mvn test` full suite green
- [ ] 7.4 Run `/sdd-qa-lint-fix` then `/sdd-verify` on the touched modules
- [ ] 7.5 Invoke `/sdd-code-reviewer` via Skill tool over the change diff
- [ ] 7.6 Update `CLAUDE.md`: RunSpec/fail-fast/RUN_START notes; remove `--ape-model`, `ape.apePureMode`, save-flag references; current-state wording only (P4)

## 8. Device smoke (optional, standalone validation only)

- [ ] 8.1 Via `scripts/run_emulator.sh` + adb (standalone path of CLAUDE.md): a valid `sata_mop_widget`-shaped run emits a well-formed `RUN_START` before any `[APE-*]` line; no `sataModel.obj`/`sataGraph.*` in the output dir; coverage dump precedes `action-history` output
- [ ] 8.2 Same session: push a properties file with one unknown key → process exits nonzero with `[APE-RUNSPEC-ABORT]` and injects zero events; `--ape bfs` → abort naming the valid set

## 9. Verification (change hygiene)

- [ ] 9.1 `openspec validate rearch-02-runspec` clean; artifacts coherent with the implemented state (amend deltas if apply-time facts diverged — e.g., the final Feature roster table)
- [ ] 9.2 Confirm the dissolved-invariant bookkeeping: INV-ARCH-06/INV-ARCH-01 removed with substitutes recorded (scoring-pipeline delta); INV-EXPL-03 removed (exploration delta); INV-RUN-01..08 all covered by at least one test
