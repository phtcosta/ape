<!-- Critical path: Group 1 (Java core: MopData, MopScorer, Config, StatefulAgent) →
     Group 2 (aperv-tool sata_mop wiring in rv-android) →
     Group 3 (Verification) → Group 4 (Close-out).
     Note: no automated test suite (CLAUDE.md). Validation via device run + logcat.
     JSON library: android.util.JsonReader (streaming) — present in framework/classes-full-debug.jar.
                   org.json is NOT on the compile classpath. -->

## 1. Java Core (ape repo)

- [ ] 1.1 Create `src/main/java/com/android/commands/monkey/ape/utils/MopData.java`:
  - Inner class `WidgetMopFlags { boolean directMop; boolean transitiveMop; }`
  - Fields: `Map<String, Map<String, WidgetMopFlags>> widgetData` (activity → shortId → flags); `Set<String> mopActivities` (activities with any MOP-reachable widget)
  - `static MopData load(String path)` — returns null on null/missing/malformed; logs WARNING via `android.util.Log` or APE's Logger
  - `WidgetMopFlags getWidget(String activity, String shortId)` — null if no match
  - `boolean activityHasMop(String activity)` — returns `mopActivities.contains(activity)`
  - Cross-reference logic:
    1. First pass over `reachability[]`: build `Map<String, Boolean[]> bySignature` (signature → [directMop, transitiveMop])
    2. Second pass over `windows[]`: for each widget, iterate `listeners[]`, look up handler in `bySignature`, set flags on `WidgetMopFlags`
  - Widget ID normalisation (helper `extractShortId(String resourceId)`):
    ```java
    static String extractShortId(String resourceId) {
        if (resourceId == null) return "";
        int idx = resourceId.indexOf(":id/");
        return idx < 0 ? "" : resourceId.substring(idx + 4);
    }
    ```
  - **Use `android.util.JsonReader`** (streaming) — available in `framework/classes-full-debug.jar`. Do NOT add `org.json` to pom.xml.

- [ ] 1.2 Create `src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java`:
  - `static int score(String activity, String shortId, MopData data)`:
    - `WidgetMopFlags f = data.getWidget(activity, shortId)`
    - `f != null && f.directMop` → `500`
    - `f != null && f.transitiveMop && !f.directMop` → `300`
    - `f == null && data.activityHasMop(activity)` → `100`
    - else → `0`

- [ ] 1.3 In `src/main/java/com/android/commands/monkey/ape/utils/Config.java`, add after the existing String fields:
  ```java
  // Path to static analysis JSON on device (null = MOP scoring disabled).
  // Loaded once at class init from ape.properties; not updated if file changes at runtime.
  public static final String mopDataPath = Config.get("ape.mopDataPath", null);
  ```

- [ ] 1.4 In `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java`:
  - Add field: `private final MopData _mopData;`
  - In constructor: `_mopData = MopData.load(Config.mopDataPath);`
  - In `adjustActionsByGUITree()` (line ~1059), **append a second loop after the existing loop** that ends at line ~1114:
    ```java
    // MOP guidance pass (Phase 3) — runs only when mopDataPath is set
    if (_mopData != null) {
        String activity = newState.getActivity();
        for (ModelAction action : newState.getActions()) {
            if (!action.requireTarget() || !action.isValid()) continue;
            GUITreeNode node = action.getResolvedNode();
            String shortId = MopData.extractShortId(node.getResourceId());
            int boost = MopScorer.score(activity, shortId, _mopData);
            if (boost > 0) action.setPriority(action.getPriority() + boost);
        }
    }
    ```
  - Note: the loop variable is `newState.getActions()` — same as the existing loop at line 1061. `candidateActions` does NOT exist in this method.
  - `MopData.extractShortId()` is the static helper from task 1.1 — make it package-visible (`static String`, not `private`).

- [ ] 1.5 Run `mvn package` — must succeed; `target/ape-rv.jar` must contain `MopData.class` and `MopScorer.class`:
  ```bash
  mvn package && unzip -l target/ape-rv.jar | grep -E "MopData|MopScorer"
  ```

## 2. aperv-tool sata_mop Wiring (rv-android repo)

- [ ] 2.1 In `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`, update `get_variants()`: change `sata_mop` entry from `"mop_data": None` to `"mop_data": "static_analysis"`.

- [ ] 2.2 Add `_find_static_analysis_file(self, task: Task) -> str | None` to `ApeRVTool` (copy from rvsmart-tool's identical method):
  ```python
  def _find_static_analysis_file(self, task: Task):
      if not hasattr(task, "results_dir") or not task.results_dir:
          return None
      if not hasattr(task, "config") or not task.config:
          return None
      json_path = os.path.join(task.results_dir, f"{task.config.apk_name}.json")
      if os.path.isfile(json_path):
          self.logger.info(f"Found static analysis file: {json_path}")
          return json_path
      return None
  ```

- [ ] 2.3 In `execute_tool_specific_logic()`, between Step 1 (JAR push) and Step 2 (properties push), add:
  ```python
  # Step 1b: Optionally push static analysis JSON for sata_mop variant
  mop_json_pushed = False
  if self._tool_config.get("mop_data") == "static_analysis":
      static_json = self._find_static_analysis_file(task)
      if static_json:
          self._push_file_to_device(
              static_json,
              "/data/local/tmp/static_analysis.json",
              device_serial,
              task.result.trace_file,
          )
          mop_json_pushed = True
      else:
          self.logger.warning(
              "sata_mop: static analysis file not found in results_dir, "
              "running without MOP data"
          )
  ```

- [ ] 2.4 Change `_push_properties(device_serial, trace_file_path)` signature to `_push_properties(device_serial, trace_file_path, mop_json_pushed=False)` and add `ape.mopDataPath` line when flag is True:
  ```python
  def _push_properties(self, device_serial, trace_file_path, mop_json_pushed=False):
      throttle_ms = self._tool_config.get("throttle_ms", 200)
      properties_content = f"ape.defaultGUIThrottle={throttle_ms}\n"
      if mop_json_pushed:
          properties_content += "ape.mopDataPath=/data/local/tmp/static_analysis.json\n"
      # ... rest unchanged (tempfile write + _push_file_to_device)
  ```
  Update the call in `execute_tool_specific_logic()` to pass `mop_json_pushed`.

- [ ] 2.5 Run `mvn install -Drvsec_home=...` to deploy updated `ape-rv.jar` to aperv-tool module.

## 3. Verification

- [ ] 3.1 Device run — `sata` regression: run `aperv:sata` on `cryptoapp.apk`; confirm no MOP-related log lines, identical behaviour to pre-Phase-3:
  ```bash
  uv run rv-platform run --tools aperv:sata --apk test-apks/cryptoapp.apk --timeout 60
  ```

- [ ] 3.2 Device run — `sata_mop` with MOP data: run `aperv:sata_mop` on `cryptoapp.apk` (with `test-apks/cryptoapp.apk.json` present in results_dir); confirm:
  - JSON pushed to `/data/local/tmp/static_analysis.json`
  - `ape.properties` contains `ape.mopDataPath=/data/local/tmp/static_analysis.json`
  - Logcat / trace shows MOP-related activity (MopData loaded, boost applied)
  - Trace file non-empty

- [ ] 3.3 Verify `sata_mop` without JSON (absent file): confirm WARNING logged and run completes as plain `sata`.

- [ ] 3.4 Run `/sdd-qa-lint-fix` on changed Python files in rv-android — fix any ruff issues.

- [ ] 3.5 Run `/sdd-verify` on changed Python files — PASS (lint clean).

## 4. Close-out (ape repo)

- [ ] 4.1 Run `/opsx:sync phase3-mop-awareness` — sync delta specs to main specs.
- [ ] 4.2 Update `docs/plans/20260311_pre_plan.md` — mark Phase 3 done.
- [ ] 4.3 Invoke `/sdd-code-reviewer` via Skill tool.
