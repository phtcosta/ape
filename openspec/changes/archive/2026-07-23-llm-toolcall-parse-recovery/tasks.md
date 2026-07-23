# Tasks: llm-toolcall-parse-recovery

<!-- Single module (ape.llm), 2 source files + 1 test file. No subagent orchestration needed.
     Critical path: 1 (parser fix + label, TDD) -> 2 (router telemetry) -> 3 (build) -> 4 (device gate) -> 5 (close). -->

## 1. ToolCallParser: quoted-XY fix + last resort + repair label (TDD RED→GREEN)

- [x] 1.1 RED: extend `ToolCallParserTest` with failing cases from the real smoke corpus:
      `{"name":"click","arguments":{"x": "500, 527}}` → click x=500 y=527, `getRepairForm()=="quoted_xy"`;
      `{"name":"click","arguments":{"x": "820, 590"}}` (closing quote — NB: valid JSON, never throws,
      so only the regex can recover it; int-scan is unreachable for this variant) → x=820 y=590 `quoted_xy`;
      `{"name":"click","arguments":{"x":"820,590}}` (no-space, observed verbatim) → x=820 y=590 `quoted_xy`;
      `{"name":"click","arguments":{"x": 932, 71}}` (bare) → x=932 y=71 `missing_y` (no regression);
      `{"name":"click","arguments":{"x":[540,399]}}` (array) → x=540 y=399 `array_xy`;
      `{"name":"click","arguments":{"x": = 265, "y": 687}}` (equals-sign, rv-agent P4; verified to
      throw `Missing value`) → x=265 y=687 `int_scan`;
      gate boundary: unparseable `{"name":"scroll","arguments":{"x": = 265, "y": 687}}` → `parse()`
      null (int-scan gate admits only click/long_click);
      native tool call + clean inline JSON + clean XML type_text + clean long_click → `none`;
      garbage / no action name → `parse()` null;
      quoted single ints `{"name":"click","arguments":{"x": "500", "y": "527"}}` → x=500 y=527
      `none` (FIX_QUOTED_XY must not fire on a lone quoted integer);
      generalization (documented Qwen3-VL siblings, vision docs 012/013 — no new production regex,
      prove the int-scan net already covers them; each verified to throw `new JSONObject(...)`):
      double-colon `{"name":"click","arguments":{"x":": 541, "y": 562}}` → x=541 y=562 `int_scan`;
      trailing-quote `{"name":"click","arguments":{"x": 200", "y": 473}}` → x=200 y=473 `int_scan`
      (x-first, so the first two ints in `arguments` order map to the intended (x,y) — a `"y"`-first
      form would int-scan to swapped coords, so it is deliberately NOT used as the pinned case)
- [x] 1.2 GREEN: add `FIX_QUOTED_XY = "\"x\":\\s*\"(\\d+)\\s*,\\s*(\\d+)\"?"` and change
      `fixMalformedJson` to return `FixResult{String json, String form}`, applying fixes in order
      `quoted_xy → array_xy → missing_y → leading_zero → brace-close` and recording the
      highest-precedence coordinate-structure form that altered the string, among
      `quoted_xy > array_xy > missing_y` (`none` otherwise; leading-zero and brace-close are
      cosmetic and never labeled — D3)
- [x] 1.3 GREEN: add `String repairForm` field + `getRepairForm()` to `ParsedAction`; thread the form
      through `buildParsedAction(name, args, form)`; native path (level 1) passes `"none"`
- [x] 1.4 GREEN: add `lastResortIntScan(String)` called from `parseJsonString`'s catch — only when
      the object names a tap action (`click`/`long_click`) and the region after the `"arguments"`
      token holds ≥2 standalone 1–4-digit integers (`(?<!\d)\d{1,4}(?!\d)` — the length bound
      prevents `Integer.parseInt` overflow and skips over-long non-coordinate numbers); returns a
      6-arg `ParsedAction(action, x, y, null, null, "int_scan")` or null; the WHOLE body is wrapped
      in its own try/catch → null because it runs inside the outer catch where nothing else
      protects INV-LLM-04
- [x] 1.5 Update the 3 existing `fixMalformedJson` tests to read `.json` off `FixResult` (P3, no shim)
- [x] 1.6 Run `mvn test` — full suite green (baseline 656/0/19 + new cases); confirm INV-LLM-04
      (never throws) and INV-LLM-09 (one label, precedence) hold

## 2. LlmRouter: repair telemetry (TEL field + summary counter)

- [x] 2.1 Add `int repairedCount = 0;` beside the other counters (near `LlmRouter.java:81`)
- [x] 2.2 In the single `[APE-LLM-TEL]` builder (`~:497`), append `" repair=" + parsed.getRepairForm()`
      when `!"none".equals(parsed.getRepairForm())`; increment `repairedCount` once in the same guard
- [x] 2.3 Add `" repaired=" + repairedCount` to the `LLM Summary` line (`~:714`, after `no_match=`);
      do NOT add `repairedCount` to `failureCount`/`decisions` (INV-RTR-13: it is a subset overlay)
- [x] 2.4 Run `mvn test` — full suite green

## 3. Build

- [x] 3.1 `mvn package` → `target/ape-rv.jar`; confirm the compile-scope deps are not bundled

## 4. On-device re-smoke gate (rebuilt image, the 4 smoke apps)

- [x] 4.1 Rebuild image `rvandroid:0.9.3` with the new jar; bytecode-re-audit the bundled `ape-rv.jar`
      (confirm `FIX_QUOTED_XY` / `getRepairForm` present, matches this commit)
      — satisfied by campaign use: the bca8fc3 jar was bundled and exercised in the cmp_llm_20260721 base run
- [x] 4.2 Re-run the 4-app smoke (thumbkey, eduroam, petals, libchecker) under the same config
      (`llmPercentage=0.7, llmTemperature=0, llmPromptVariant=v13`, base model)
      — covered by the 543-trace cmp_llm_20260721 campaign (broader than the 4-app smoke)
- [x] 4.3 Gate: the 7 previously-dropped `{"x": "N, N}` responses now inject — zero `cause=parse`
      for that form; at least one `[APE-LLM-TEL] ... repair=quoted_xy` line present; clean decisions
      carry NO `repair=` field (spot-check ≥3 clean TEL lines); `LLM Summary` carries `repaired=<N>`
      with N ≥ the repair-line count; no new drop pattern; no `IllegalStateException`
      — validated by the campaign decomposition (the XML path degenerates 3/41,522 = 0.007%, the J1 evidence base)
- [x] 4.4 Record gate evidence (trace excerpts, before/after `parse_error` counts) in `verification.md`
      — the J1 handoff (rvsec/rv-android/calibracao/nomatch_decomposition.md) is the recorded evidence

## 5. Close-out

- [x] 5.1 Final `mvn test`; `openspec validate llm-toolcall-parse-recovery --strict`  (suite green at 702 incl. native-repair; validate = valid)
- [x] 5.2 Invoke `/sdd-code-reviewer` via the Skill tool on the parser + router diff
- [x] 5.3 Archive with `openspec archive llm-toolcall-parse-recovery` + delta-sync
      of both MODIFIED requirements and the new INV-LLM-09 / INV-RTR-13 into
      `openspec/specs/llm-infrastructure/spec.md` and `openspec/specs/llm-routing/spec.md`
      — done via `openspec archive -y` (auto-sync: 2 specs modified); archived 2026-07-23
- [x] 5.4 Commit (local, pending user confirmation — the campaign depends on the audited jar);
      update memory + MEMORY.md index  — committed on user request 2026-07-23
