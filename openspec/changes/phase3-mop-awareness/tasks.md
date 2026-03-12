<!-- Critical path: Group 1 (Java core: MopData, MopScorer, Config, StatefulAgent) →
     Group 2 (aperv-tool sata_mop wiring in rv-android) →
     Group 3 (Verification) → Group 4 (Close-out).
     Note: no automated test suite (CLAUDE.md). Validation via device run + logcat. -->

## 1. Java Core (ape repo)

- [ ] 1.1 Create `src/main/java/com/android/commands/monkey/ape/utils/MopData.java`:
  - Inner class `WidgetMopFlags { boolean directMop; boolean transitiveMop; }`
  - `Map<String, Map<String, WidgetMopFlags>> widgetData` (activity → shortId → flags)
  - `Set<String> mopActivities` (activities with any MOP-reachable widget)
  - `static MopData load(String path)` — returns null on null/missing/malformed; logs WARNING
  - `WidgetMopFlags getWidget(String activity, String shortId)` — null if no match
  - `boolean activityHasMop(String activity)`
  - Cross-reference: `windows[i].widgets[j].listeners[k].handler` matches `reachability[m].methods[n].signature` with `directlyReachesMop`/`reachesMop` flags
  - Widget ID normalisation: `"pkg:id/name"` → `"name"` (split on `":id/"`)
  - Use `org.json` or manual JSON parsing — whichever is already on the compile classpath

- [ ] 1.2 Create `src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java`:
  - `static int score(String activity, String shortId, MopData data)`:
    - `data.getWidget(activity, shortId).directMop` → `500`
    - `data.getWidget(activity, shortId).transitiveMop` (not direct) → `300`
    - `getWidget() == null && data.activityHasMop(activity)` → `100`
    - else → `0`

- [ ] 1.3 In `src/main/java/com/android/commands/monkey/ape/utils/Config.java`, add:
  ```java
  public static final String mopDataPath = Config.get("ape.mopDataPath", null);
  ```

- [ ] 1.4 In `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java`:
  - Add field: `private final MopData _mopData;`
  - In constructor: `_mopData = MopData.load(Config.mopDataPath);`
  - In `adjustActionsByGUITree()`, after the base priority loop, add the MOP scoring pass:
    ```java
    if (_mopData != null) {
        String activity = newState.getActivity();
        for (ModelAction action : candidateActions) {
            if (action.requireTarget() && action.isValid()) {
                GUITreeNode node = action.getResolvedNode();
                String shortId = extractShortResourceId(node.getResourceId());
                int boost = MopScorer.score(activity, shortId, _mopData);
                if (boost > 0) action.setPriority(action.getPriority() + boost);
            }
        }
    }
    ```
  - Add private helper `extractShortResourceId(String resourceId)`: returns substring after `":id/"` if present, empty string otherwise. Handle null input.

- [ ] 1.5 Run `mvn package` — must succeed; `target/ape-rv.jar` must contain `MopData.class` and `MopScorer.class`:
  ```bash
  mvn package && unzip -l target/ape-rv.jar | grep -E "MopData|MopScorer"
  ```

## 2. aperv-tool sata_mop Wiring (rv-android repo)

- [ ] 2.1 In `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`, update `get_variants()`: change `sata_mop` entry from `"mop_data": None` to `"mop_data": "static_analysis"`.

- [ ] 2.2 In `execute_tool_specific_logic()`, add step before `_push_properties()`:
  - If `self._tool_config.get("mop_data") == "static_analysis"`:
    - Find `<task.results_dir>/<apk_name>.json` (same logic as rvsmart `_find_static_analysis_file`)
    - If found: push to `/data/local/tmp/static_analysis.json` via `_push_file_to_device()`; store path in local var `mop_json_pushed = True`
    - If not found: log WARNING `"sata_mop: static analysis file not found, running without MOP data"`; `mop_json_pushed = False`
  - Otherwise: `mop_json_pushed = False`

- [ ] 2.3 In `_push_properties()`, add `ape.mopDataPath` to properties content when `mop_json_pushed`:
  - Pass `mop_json_pushed` flag (or refactor to pass extra properties dict) so that `ape.properties` contains `ape.mopDataPath=/data/local/tmp/static_analysis.json` when appropriate.

- [ ] 2.4 Run `mvn install -Drvsec_home=...` to deploy updated `ape-rv.jar` to aperv-tool module.

## 3. Verification

- [ ] 3.1 Device run — `sata` regression: run `aperv:sata` on `cryptoapp.apk`; confirm no MOP-related log lines, no change in behaviour vs pre-Phase-3:
  ```bash
  uv run rv-platform run --tools aperv:sata --apk test-apks/cryptoapp.apk --timeout 60
  ```

- [ ] 3.2 Device run — `sata_mop` with MOP data: run `aperv:sata_mop` on `cryptoapp.apk` using `test-apks/cryptoapp.apk.json` as static analysis JSON; confirm:
  - JSON pushed to `/data/local/tmp/static_analysis.json`
  - `ape.properties` contains `ape.mopDataPath=...`
  - Logcat shows MOP boost log lines (e.g., `MopData: loaded N widgets`)
  - Trace file non-empty

- [ ] 3.3 Verify `sata_mop` without JSON (absent file): confirm WARNING logged and run completes as plain `sata`.

- [ ] 3.4 Run `/sdd-qa-lint-fix` on changed Python files in rv-android — fix any ruff issues.

- [ ] 3.5 Run `/sdd-verify` on changed Python files — PASS (lint clean).

## 4. Close-out (ape repo)

- [ ] 4.1 Run `/opsx:sync phase3-mop-awareness` — sync delta specs to main specs.
- [ ] 4.2 Update `docs/plans/20260311_pre_plan.md` — mark Phase 3 done.
- [ ] 4.3 Invoke `/sdd-code-reviewer` via Skill tool.
