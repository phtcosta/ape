## Context

Phase 2 addresses two UI coverage gaps identified in APE-RV's pre-plan (`docs/plans/20260311_pre_plan.md`), both of which reduce coverage on applications built with AndroidX (the majority of the rv-android experiment corpus):

1. **FR02a — AndroidX ViewPager detection**: `GUITreeNode.getScrollType()` checks class names to determine scroll direction. It includes the legacy `android.support.v4.view.ViewPager` but omits both AndroidX replacements. As a result, apps using tab carousels or paged layouts never receive `MODEL_SCROLL_LEFT_RIGHT` / `MODEL_SCROLL_RIGHT_LEFT` actions; those UI regions are silently skipped.

2. **FR02b — OptionsMenu systematic exploration**: APE has no `MODEL_MENU` action type. The MENU key is only pressed during random fuzzing (2% probability, not tracked). The `OptionsMenu` is therefore never reliably explored, and any functionality reachable only through the overflow menu is systematically missed.

Both fixes are fully self-contained within the existing APE-RV codebase. No new dependencies are introduced. No new abstractions are required. The entire change touches 5 source files and follows established patterns already present in the code.

Build: `mvn package` → `target/ape-rv.jar` (Dalvik JAR via d8). No automated test suite; validation is done on real Android devices.

---

## Architecture

The change affects two layers of the APE-RV stack:

```
┌──────────────────────────────────────────────┐
│ agent/SataAgent                               │  ← add menuAction unvisited check
│ model/ActionType                              │  ← add MODEL_MENU constant
│ model/State                                  │  ← add menuAction field + accessor
├──────────────────────────────────────────────┤
│ MonkeySourceApe                              │  ← handle MODEL_MENU in switch cases
├──────────────────────────────────────────────┤
│ tree/GUITreeNode                             │  ← extend getScrollType() + resetActions()
└──────────────────────────────────────────────┘
```

### Key Components

| Component | Responsibility | Change |
|-----------|---------------|--------|
| `ape/model/ActionType.java` | Enum of all action types; `requireTarget()` / `isModelAction()` predicates | Add `MODEL_MENU` between `MODEL_BACK` and `MODEL_CLICK` |
| `ape/model/State.java` | Per-abstract-state action set; holds `backAction` | Add `menuAction` field; initialise in constructor; expose `getMenuAction()` |
| `MonkeySourceApe.java` | Event generation; action validation | Add `case MODEL_MENU` in two switch statements |
| `ape/tree/GUITreeNode.java` | Scroll-direction inference; widget action assignment | Add 2 class names in `getScrollType()`; add `MODEL_MENU` to `resetActions()` blocklist |
| `ape/agent/SataAgent.java` | Epsilon-greedy action selection; unvisited-action priority | Add `menuAction` unvisited check after `backAction` check |

---

## Mapping: Spec → Implementation → Test

| Requirement / Invariant | Implementation | Validation |
|------------------------|---------------|------------|
| ViewPager AndroidX detection (INV-TREE-02) | `GUITreeNode.getScrollType()` — add 2 class names | ADB: inspect scroll actions on an app with ViewPager2 tabs |
| MODEL_MENU blocklist (INV-TREE-04) | `GUITreeNode.resetActions()` — add `case MODEL_MENU: throw ...` | Code inspection; existing error path pattern |
| `MODEL_MENU` in ActionType (INV-EXPL-13) | `ActionType.java` — insert enum constant | `mvn package` succeeds; `requireTarget()` / `isModelAction()` verified by inspection |
| `State.menuAction` initialised (INV-EXPL-06) | `State` constructor — add `menuAction = new ModelAction(...)` | Code inspection; parallel to `backAction` |
| `MODEL_MENU` event dispatch | `MonkeySourceApe.generateEventsForActionInternal()` | ADB: observe MENU key event in logcat during exploration |
| `MODEL_MENU` validation | `MonkeySourceApe.validateResolvedAction()` | Code inspection; parallel to `MODEL_BACK` |
| `SataAgent` MENU unvisited priority | `SataAgent.selectNewActionEpsilonGreedyRandomly()` | ADB: confirm MENU is pressed exactly once per new state during first-visit exploration |

---

## Goals / Non-Goals

**Goals:**
- Enable horizontal scroll events on AndroidX `ViewPager` and `ViewPager2` containers
- Track OptionsMenu as a first-class exploration action (visited/unvisited per state)
- Guarantee at least one MENU press per new state in `SataAgent`

**Non-Goals:**
- `RecyclerView` horizontal detection (orientation is runtime-set via `LayoutManager`; class name alone is insufficient)
- Handling the result of the MENU press specially (menu items are ordinary clickable widgets in the next `GUITree`)
- Adding `MODEL_MENU` to agents other than `SataAgent` (those agents use the base `StatefulAgent` priority infrastructure, which already includes `menuAction` in `state.getActions()`)

---

## Decisions

**D1 — `MODEL_MENU` inserted between `MODEL_BACK` and `MODEL_CLICK`, not appended at end**

`requireTarget()` and `isModelAction()` use ordinal range checks. `isModelAction()` checks `ord >= MODEL_BACK.ordinal()`, so any constant appended after `MODEL_SCROLL_RIGHT_LEFT` would also pass. `requireTarget()` checks `ord >= MODEL_CLICK.ordinal() && ord <= MODEL_SCROLL_RIGHT_LEFT.ordinal()`. Inserting `MODEL_MENU` before `MODEL_CLICK` keeps it outside the `requireTarget()` range without requiring any change to range boundaries. Appending it at the end would require modifying both predicates. *Chosen: insert after MODEL_BACK.*

**D2 — `menuAction` stored as a named field (parallel to `backAction`), not computed on demand**

`backAction` is a named field in `State` accessed via `getBackAction()`. Following the same pattern for `menuAction` keeps the codebase uniform (P1), avoids any need to scan the actions array to find the MENU action, and makes the intent explicit. *Chosen: named field.*

**D3 — MENU unvisited check placed between BACK check and epsilon-greedy, not before BACK**

`MODEL_BACK` has higher exploration priority than `MODEL_MENU` because BACK navigates out of the current screen, enabling exploration of previously unreachable states. MENU opens a popover that is dismissed with BACK — it makes sense to explore BACK first so that the tool can reach other parts of the app before descending into MENU items. *Chosen: BACK → MENU → widget → epsilon-greedy.*

---

## API Design

### `GUITreeNode.getScrollType()`

Extended to check `"androidx.viewpager.widget.ViewPager"` and `"androidx.viewpager2.widget.ViewPager2"` in the existing horizontal branch, immediately after `"android.support.v4.view.ViewPager"`. No signature change. No new parameters.

### `GUITreeNode.resetActions(ActionType[])`

The existing switch in `resetActions()` has a case for `MODEL_BACK` that throws `IllegalStateException`. A new case for `MODEL_MENU` is added with identical semantics:
```java
case MODEL_MENU:
    throw new IllegalStateException("Cannot set " + at + " to widget.");
```

### `ActionType` enum

New constant inserted between `MODEL_BACK` and `MODEL_CLICK`:
```java
MODEL_MENU,   // MENU key press; no widget target
```

No changes to `requireTarget()`, `isModelAction()`, or `isScroll()` predicate bodies — ordinal arithmetic remains correct.

### `State` constructor

```java
// existing
backAction = new ModelAction(this, ActionType.MODEL_BACK);
c.add(backAction);
// added
menuAction = new ModelAction(this, ActionType.MODEL_MENU);
c.add(menuAction);
```

New field: `private ModelAction menuAction;`
New accessor: `public ModelAction getMenuAction() { return this.menuAction; }`

### `MonkeySourceApe.generateEventsForActionInternal()`

New case added after the `MODEL_BACK` case:
```java
case MODEL_MENU:
    generateKeyMenuEvent();
    break;
```

`generateKeyMenuEvent()` already exists in `MonkeySourceApe` (used by the fuzzing path).

### `MonkeySourceApe.validateResolvedAction()`

New case added after the `MODEL_BACK` case:
```java
case MODEL_MENU:
    return true;
```

### `SataAgent.selectNewActionEpsilonGreedyRandomly()`

After the existing BACK unvisited check:
```java
ModelAction menu = newState.getMenuAction();
if (menu.isValid()) {
    if (menu.isUnvisited()) {
        Logger.iprintln("Select Menu because Menu action is unvisited.");
        return menu;
    }
}
```

---

## Data Flow

```
1. GUITree captured from AccessibilityService
2. GUITreeNode.getScrollType() called per scrollable node
   → "androidx.viewpager.widget.ViewPager" / "androidx.viewpager2.widget.ViewPager2"
     → returns "horizontal"
   → MODEL_SCROLL_LEFT_RIGHT / MODEL_SCROLL_RIGHT_LEFT added to node's actions
3. NamingFactory maps GUITree → abstract State
4. State constructor:
   → backAction = ModelAction(this, MODEL_BACK)
   → menuAction = ModelAction(this, MODEL_MENU)
   → both added to actions array
5. SataAgent.selectNewActionEpsilonGreedyRandomly():
   → if backAction unvisited → return backAction
   → elif menuAction unvisited → return menuAction
   → else epsilon-greedy over widget actions
6. MonkeySourceApe dispatches selected action:
   → MODEL_MENU → generateKeyMenuEvent() → KEYCODE_MENU injected
7. Next GUITree captured; menu items appear as clickable widgets → normal exploration
```

---

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `IllegalStateException` in `resetActions()` | `MODEL_MENU` or `MODEL_BACK` passed as widget action | Bug in caller — fail fast | Fix caller to not include device-level actions in widget action arrays |
| `RuntimeException("Should not reach here")` in switch | Default case hit in `generateEventsForActionInternal()` | Unreachable after `MODEL_MENU` case is added | Compile-time: ensure all enum values are handled |
| KEYCODE_MENU ineffective (device has no MENU button) | Physical device without hardware MENU key | Menu key delivered, ignored by system; `menuAction` marked visited | One wasted step per state; negligible overhead |

---

## Risks / Trade-offs

- **[Ordinal shift]** Inserting `MODEL_MENU` at position N shifts ordinals of `MODEL_CLICK` through `MODEL_SCROLL_RIGHT_LEFT` by 1. Any code using hardcoded ordinal values (rather than enum constant names) would break. APE uses only enum constant comparisons (`ordinal() >= MODEL_CLICK.ordinal()`), not literal ordinal integers, so no code breaks. **Mitigation**: verify all ordinal-dependent predicates after insertion.
- **[Serialised model compatibility]** Java serializes enum constants by name, so the `ActionType` ordinal shift does not corrupt existing `.obj` files. However, `State.menuAction` is a new field: if an old `sataModel.obj` were deserialized with the new code, `menuAction` would be `null`, causing a `NullPointerException` in `SataAgent`. In practice this is not an issue because APE-RV has no "resume from saved graph" feature — `sataModel.obj` is write-only output. No migration code is needed.
- **[KEYCODE_MENU ineffective]** On many modern devices (API 28+) with no hardware MENU button, `KEYCODE_MENU` is either ignored or opens a system context menu. **Mitigation**: accepted trade-off; the action is tried once per state, becomes visited, and incurs no further overhead.

---

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Build | `mvn package` succeeds | `mvn clean package` on host | 1 |
| Binary validation | `target/ape-rv.jar` contains valid `classes.dex` | `unzip -p target/ape-rv.jar classes.dex \| file -` | 1 |
| ViewPager2 coverage | App with ViewPager2 tab layout receives left/right scroll events | ADB run on test APK; observe `MODEL_SCROLL_LEFT_RIGHT` in logcat | 1 app |
| MODEL_MENU exploration | `MODEL_MENU` appears exactly once per new state in SATA logcat output | ADB run with `--ape sata`; grep `ape_log` for `MODEL_MENU` | 1 app |
| OptionsMenu items explored | After MENU press, the resulting overlay widgets appear in the next `GUITree` and are clicked | Visual inspection of screenshots or logcat trace | 1 app |

---

## Open Questions

None. All design decisions are resolved by the pre-plan analysis (`docs/plans/20260311_pre_plan.md`) and confirmed by reading the source.
