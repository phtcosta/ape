# Tasks — llm-native-toolcall-repair

TDD discipline throughout: each functional group writes its failing tests first (RED), then the minimal implementation (GREEN). Constraints from `design.md` apply to every group: `[APE-LLM-TEL]` grammar frozen, no prompt changes, defaults bit-identical.

## 1. Config keys (J1b/J1c foundation)

- [x] 1.1 RED: `ConfigTest` — defaults for the four new keys: `llmMaxTokens=1024`, `llmSnapTolerancePx=50`, `llmBoundaryTopPct=0.05`, `llmBoundaryBottomPct=0.94` (no clamping)
- [x] 1.2 RED: `ApePureModeKillSwitchTest` — the four new fields present in `rvExemptReasons` with an LLM sub-param reason (INV-ARCH-06)
- [x] 1.3 GREEN: declare the four `public static final` fields in `Config.java` (next to the existing llm* block) + register the four exempt entries in `rvExemptReasons`
- [x] 1.4 Run `/sdd-test-run ConfigTest,ApePureModeKillSwitchTest`  (ConfigTest 34/0, ApePureModeKillSwitchTest 6/0)

## 2. SglangClient — raw-arguments preservation (INV-LLM-10, part 1)

- [x] 2.1 RED: `SglangClientTest.parseResponse` — malformed arguments string (`{"x": 616, 891}`): map empty AND `getRawArguments()` returns the string verbatim
- [x] 2.2 RED: `SglangClientTest.parseResponse` — well-formed arguments string: map populated AND raw verbatim; arguments as JSON object: map populated AND raw = object serialization; absent arguments → raw null; 2-arg `ToolCall` constructor → raw null
- [x] 2.3 GREEN: add final `rawArguments` field + `getRawArguments()` + 3-arg constructor to `SglangClient.ToolCall` (2-arg delegates with null); capture raw in `parseResponse` for both string and object encodings
- [x] 2.4 Run `/sdd-test-run SglangClientTest`  (SglangClientTest 20/0)

## 3. ToolCallParser — Level 1 unification (INV-LLM-10, part 2 — the J1a core)

- [x] 3.1 RED: native missing-y string (`{"x": 616, 891}`, empty map + raw) → `ParsedAction(click, 616, 891)` with `repair=missing_y` — the dominant degenerate form, must NOT collapse to (0,0)
- [x] 3.2 RED: native quoted-collapsed-XY with a *valid* map (`{"x": "540, 399"}`) → `(540, 399)` `repair=quoted_xy` (not `(0,0)` from `Integer.parseInt` failure)
- [x] 3.3 RED: native array-form object arguments → `(540, 399)` `repair=array_xy`; native unrecoverable click (`{"x": = 265, "y": 687}`) → int-scan `(265, 687)` `repair=int_scan`
- [x] 3.4 RED: fallback preservation — raw unrecoverable + name=`back` → map-based `ParsedAction(back)` `repair=none`, no exception; `getRawArguments()==null` + valid map → identical to pre-change result; clean native call → identical result, `repair=none`
- [x] 3.5 GREEN: rewrite `parse()` Level 1 per design D3 — build `{"name": <JSONObject.quote(name)>, "arguments": <raw>}`, call the shared `parseJsonString`, fall back to `buildParsedAction(name, args, "none")` when raw is null or the shared pipeline returns null
- [x] 3.6 Run `/sdd-test-run ToolCallParserTest`  (ToolCallParserTest 35/0)

## 4. LlmRouter — Config consumption (J1b/J1c wiring)

- [x] 4.1 RED: `LlmRouterTest` — `[APE-LLM-CONFIG]` manifest carries `max_tokens=1024` at default (byte-identical line); boundary reject at `pixelY=50/1850` on 1080x1920 identical to pre-change; euclidean floor 50 behavior identical (INV-RTR-14)
- [x] 4.2 RED: `LlmRouterTest` — native repaired decision flows to TEL: a response whose ToolCall carries a malformed raw that repairs to a matchable coordinate produces `repair=<form>` on the TEL line and increments `repairedCount` (INV-RTR-13 unchanged, now native-fed)  [device-gated selectAction pinned at the two JVM seams: parser feeds non-none form + unchanged emitter site]
- [x] 4.3 GREEN: constructor reads `int maxTokens = Config.llmMaxTokens`; `mapToModelAction` uses `Config.llmBoundaryTopPct` / `Config.llmBoundaryBottomPct` / `Config.llmSnapTolerancePx` at the three existing use sites — no other logic change
- [x] 4.4 Run `/sdd-test-run LlmRouterTest`  (LlmRouterTest 26/0)

## 5. Verification, build and handoff deliverables (§5 of j1_handoff.md)

- [x] 5.1 Run `/sdd-verify ape` (full suite `mvn test` — no regressions vs the 670-test baseline; then `mvn package` → `target/ape-rv.jar`)  (final full suite BUILD SUCCESS: 702 tests, 0 failures, 0 errors, 19 skipped; +36 vs baseline all-additions [26 core TDD + 9 acceptance census + 1 NT-01 pin]; jar sha256 f671db48…)
- [x] 5.2 Update `CLAUDE.md` Central Configuration list with the four new keys (one line each, defaults noted)
- [x] 5.3 Bytecode diff audit: extract old jar (pre-change build) and new jar, `javap -p` diff — confirm changes confined to `ape/llm/SglangClient*`, `ape/llm/ToolCallParser*`, `ape/llm/LlmRouter*`, `ape/utils/Config*`; confirm the TEL emitter sites are unchanged; record jar path + `sha256sum` in the audit memo (`docs/<date>_j1_bytecode_audit.md`)  (jars are Dalvik dex → dexdump -d normalized diff: 10/353 classes differ, all in the 4 families; LlmRouter.selectAction 1845→1845 units byte-identical, ToolCallParser only parse() changed; memo docs/20260723_j1_bytecode_audit.md)
- [x] 5.4 Offline acceptance fixture: unit-level replay of the handoff's native malformation census (missing_y string / quoted / array / int-scan forms) asserting zero `(0,0)` collapses for recoverable forms — the device-free proxy for "degenerate → ~0"; full re-parse acceptance (`decompose_nomatch.py` over new traces) is the rvsec side's Fase-B step  (NativeToolCallRepairAcceptanceTest 9/0, full envelope→parseResponse→parse path)
- [x] 5.5 Run `/sdd-qa-lint-fix ape/llm`  (no-op: checkstyle not installed in this env — no PATH binary, no .m2 jar, no maven-checkstyle-plugin, no project checkstyle.xml; Maven-only build. No files modified; code follows existing package style)
- [x] 5.6 Invoke `/sdd-code-reviewer` via Skill tool  (verdict APPROVE: 0 critical, 0 warning, 3 notes; all 5 hard constraints PASS. NT-01 — the contradictory shape {"x":[a,b],"y":c} diverges (map (a,c) vs raw int_scan (a,b), both non-degenerate) — addressed with a doc comment + characterization test testNativeArrayPlusSeparateY_pinnedNonDegenerate; NT-02/NT-03 out-of-scope/no-change-needed)
- [x] 5.7 Report back for gate G2: jar path, sha256, audit memo path (unblocks rvsec Fase B)  (see final report; jar target/ape-rv.jar sha256 f671db48…, memo docs/20260723_j1_bytecode_audit.md)
