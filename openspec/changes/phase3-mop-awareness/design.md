# Design: phase3-mop-awareness

## Context

APE-RV's `aperv:sata_mop` variant (Phase 4) is a placeholder: it passes `strategy="sata"` and `mop_data=None` to the Java core. The static analysis JSON produced by the rv-android pipeline (`rv-android-core` static analysis component, same format used by rvsmart) contains two relevant sections: `windows[]` (activity → widget → listener handler) and `reachability[]` (method → MOP reachability flags). Cross-referencing these two sections identifies which widgets, when interacted with, trigger monitored operations.

The injection point in the Java core is already documented in `openspec/specs/exploration/spec.md` (INV-EXPL-11): `StatefulAgent.adjustActionsByGUITree()` is the designated hook. Phase 3 implements the hook.

FR03 from `docs/PRD.md` ("MOP-guided exploration via `sata_mop`") is satisfied by this change.

## Architecture

```
ape.properties (device)                aperv-tool sata_mop variant
  ape.mopDataPath=/data/local/tmp/       pushes static_analysis.json
    static_analysis.json                 writes ape.mopDataPath to ape.properties
         │
         ▼
Config.mopDataPath (Java)
         │
         ▼
MopData.load(path)          ◄── parses windows[] + reachability[]
         │                           cross-references by handler signature
         ▼
StatefulAgent._mopData field
         │
         ▼
adjustActionsByGUITree()    ◄── called once per step after base priority
         │
    for each action:
      if requireTarget() && isValid():
        node = action.getResolvedNode()
        shortId = extractShortResourceId(node.resourceId)
        boost = MopScorer.score(activity, shortId, mopData)
        action.setPriority(existing + boost)
```

### Key Components

| Component | File | Responsibility |
|-----------|------|----------------|
| `MopData` | `src/main/java/.../ape/utils/MopData.java` | Parse JSON, build activity→widget→{directMop,transitiveMop} map |
| `MopScorer` | `src/main/java/.../ape/utils/MopScorer.java` | Map MOP flags to integer priority boost |
| `Config.mopDataPath` | `src/main/java/.../ape/utils/Config.java` | Config flag, null by default |
| `StatefulAgent` | `src/main/java/.../ape/agent/StatefulAgent.java` | Load `MopData` at init; inject scoring in `adjustActionsByGUITree()` |
| `aperv-tool/tool.py` | `rv-android/.../aperv/tool.py` | `sata_mop`: push JSON + write `ape.mopDataPath` to properties |

## Mapping: Spec → Implementation → Test

| Requirement | Implementation | Validation |
|-------------|---------------|------------|
| MopData JSON loading | `MopData.load(String path)` | Device run: verify priority boosts in logcat |
| Widget ID normalisation | `MopData.extractShortId()` | Log: `"com.example:id/btn"` → `"btn"` |
| MopScorer priority scale | `MopScorer.score()` | Log: direct +500, transitive +300, activity +100 |
| StatefulAgent injection | `adjustActionsByGUITree()` MOP pass | Device run: `sata_mop` visits MOP-reachable actions earlier |
| No-op when mopDataPath unset | `if (mopData != null)` guard | Device run: `sata` variant unchanged |
| aperv-tool sata_mop wiring | `_push_properties()` + `_push_mop_data()` | uv smoke test + device run |

## Goals / Non-Goals

**Goals:**
- Wire `sata_mop` end-to-end: JSON on device → `MopData` loaded → priority boosts applied
- No regression on `sata`, `random`, `bfs` variants (guard on `mopData != null`)
- Graceful degradation: if JSON missing or malformed, log warning and proceed as plain `sata`

**Non-Goals:**
- Online MOP monitoring (runtime logcat feedback loop — removed from scope in pre-plan §3c)
- New JSON format — reuse the exact format produced by rv-android static analysis
- Coverage metrics collection inside APE (handled by rv-android Python layer)
- Changing the SATA heuristic itself

## Decisions

### D1 — Reuse rv-android static analysis JSON format unchanged

The JSON format (`windows[]` + `reachability[]`) is already produced and validated by rv-android. Creating a separate format would require a new generator and would diverge from rvsmart's integration. Reusing the format lets the same JSON serve both rvsmart and aperv.

### D2 — Score in `adjustActionsByGUITree()`, not in `selectNewActionNonnull()`

`adjustActionsByGUITree()` is already documented as the designated extension point (INV-EXPL-11). Scoring in `selectNewActionNonnull()` would interleave MOP logic with SATA's epsilon-greedy selection, making both harder to reason about. The priority system already handles tie-breaking correctly.

### D3 — Short resource ID normalisation at score time, not at load time

`GUITreeNode.resourceId` is in `"pkg:id/name"` form at runtime. `MopData` stores short IDs (`"name"`) from the JSON. Normalising at score time in `extractShortResourceId()` avoids a second data structure and keeps `MopData` agnostic to Android naming conventions.

### D4 — +500/+300/+100 scale, same as rvsmart

The scale is empirically chosen in rvsmart to strongly prefer direct MOP paths while keeping the SATA heuristic operative. Diverging without empirical justification introduces unnecessary risk.

### D5 — `MopData.load()` returns `null` on missing/malformed file

Throwing at load time would crash the exploration session if the file is absent. Returning `null` and logging a warning keeps APE operative as plain `sata` — consistent with the graceful degradation principle.

## API Design

### `MopData.load(String path) → MopData`

- **Precondition**: `path` is a file path or `null`
- **Postcondition**: returns populated `MopData` if file is valid JSON; returns `null` if `path` is null, file is missing, or JSON is malformed
- **Side-effect**: logs `WARNING` on parse failure

```java
class MopData {
    // Map: activityClassName → (shortResourceId → WidgetMopFlags)
    static MopData load(String path);
    WidgetMopFlags getWidget(String activity, String shortId);  // null if no match
    boolean activityHasMop(String activity);

    static class WidgetMopFlags {
        boolean directMop;
        boolean transitiveMop;
    }
}
```

### `MopScorer.score(String activity, String shortId, MopData data) → int`

```
directMop    → +500
transitiveMop (but not direct) → +300
activityHasMop (no widget match) → +100
no match     → 0
```

### JSON format (input to `MopData.load`)

```json
{
  "reachability": [
    {
      "className": "com.example.MainActivity",
      "methods": [
        { "name": "onClick", "signature": "<com.example.MainActivity: void onClick(android.view.View)>",
          "directlyReachesMop": true, "reachesMop": true }
      ]
    }
  ],
  "windows": [
    {
      "name": "com.example.MainActivity",
      "widgets": [
        { "idName": "btn_encrypt", "type": "Button",
          "listeners": [{ "handler": "<com.example.MainActivity: void onClick(android.view.View)>" }] }
      ]
    }
  ]
}
```

Cross-reference: `windows[i].widgets[j].listeners[k].handler` matches `reachability[m].methods[n].signature`.

### `StatefulAgent.adjustActionsByGUITree()` injection (pseudo-code)

```java
// Existing base priority loop runs first (unchanged)
// Then, if MOP data is available:
if (_mopData != null) {
    String activity = newState.getActivity();
    for (ModelAction action : candidateActions) {
        if (action.requireTarget() && action.isValid()) {
            GUITreeNode node = action.getResolvedNode();
            String shortId = extractShortResourceId(node.getResourceId());
            int boost = MopScorer.score(activity, shortId, _mopData);
            if (boost > 0) {
                action.setPriority(action.getPriority() + boost);
            }
        }
    }
}
```

### `aperv-tool sata_mop` wiring (Python)

```python
# In execute_tool_specific_logic(), before _build_main_command():
if self._tool_config.get("strategy") == "sata" and self._tool_config.get("mop_data") is not None:
    static_json = self._find_static_analysis_file(task)
    if static_json:
        self._push_file_to_device(static_json, "/data/local/tmp/static_analysis.json", ...)
        # ape.properties already pushed by _push_properties();
        # add ape.mopDataPath=/data/local/tmp/static_analysis.json to properties content
```

The `mop_data` key in `sata_mop` variant config transitions from `None` (placeholder) to a sentinel value (e.g., `"static_analysis"`) that signals the tool to push the JSON. The actual path comes from `task.results_dir/<apk_name>.json` (same as rvsmart).

## Data Flow

```
rv-android static analysis
  → <apk_name>.json in task.results_dir
       │
aperv-tool (Python, sata_mop variant)
  → adb push .json /data/local/tmp/static_analysis.json
  → ape.properties: ape.mopDataPath=/data/local/tmp/static_analysis.json
       │
APE startup (Java)
  → Config.mopDataPath = "/data/local/tmp/static_analysis.json"
  → _mopData = MopData.load(Config.mopDataPath)
       │
Each exploration step
  → StatefulAgent.adjustActionsByGUITree()
  → MopScorer.score(activity, shortId, _mopData)
  → action.setPriority(base + boost)
  → RandomHelper.randomPickWithPriority(actions)
```

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| JSON file missing on device | `MopData.load()` | Return `null`, log WARNING | Proceed as plain `sata` |
| JSON malformed / unexpected format | `MopData.load()` | Return `null`, log WARNING with parse details | Proceed as plain `sata` |
| `getResolvedNode()` returns null | `adjustActionsByGUITree()` | Skip MOP scoring for that action | Action retains base priority |
| `resourceId` is null | `extractShortResourceId()` | Return empty string; `MopData.getWidget()` returns null | Score falls back to activity-level lookup |
| Static analysis file not in `task.results_dir` | `aperv-tool` Python | Log WARNING, skip push | APE runs without MOP data; sata_mop behaves as sata |

## Risks / Trade-offs

- **[Scale is empirical]** → The +500/+300/+100 values are borrowed from rvsmart without APE-specific tuning. Mitigation: run Phase 5 comparison experiment to validate.
- **[Activity name mismatch]** → `MopData` uses `windows[].name` as activity key; `newState.getActivity()` returns the current activity class name from AccessibilityNodeInfo. These may differ (e.g., inner class suffix). Mitigation: use `contains()` fallback if exact match fails.
- **[JSON not available for all APKs]** → Static analysis is only produced for instrumented APKs. Mitigation: graceful null return from `MopData.load()`.

## Testing Strategy

| Layer | What | How |
|-------|------|-----|
| Device smoke test (sata_mop) | `sata_mop` with cryptoapp.apk + its .json; verify MOP-boosted actions in logcat | `mvn install` + `uv run rv-platform run --tools aperv:sata_mop` |
| Device regression (sata) | `sata` variant unchanged behaviour | Same APK, `--tools aperv:sata`; assert no MOP-related log lines |
| Manual JSON inspection | `MopData.load()` on cryptoapp.apk.json; log loaded widget count | Logcat grep for `MopData: loaded N widgets` |

## Open Questions

- None. The JSON format, injection point, scoring scale, and wiring are all confirmed from pre-plan analysis and rvsmart reference.
