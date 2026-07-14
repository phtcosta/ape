## MODIFIED Requirements

### Requirement: MopData — Static Analysis JSON Loader

`MopData.load(String path, String expectedPackage, String expectedMainActivity)` SHALL parse the post-gh57+gh60 static analysis JSON file and build a complete typed model:

1. **Top-level scalars**: `getPackageName()`, `getMainActivity()`, `isComplete()`.
2. **Reachability**: `getReachability()` returns immutable `List<ReachabilityClass>` with full per-class (`className`, `componentType`, `isMain`, `methods`) and per-method (`name`, `signature`, `reachable`, `reachesTarget`, `directlyReachesTarget`) fields.
3. **Windows**: `getWindows()` ordered list; `getWindow(int id)` lookup; each `Window` carries `id`, `type`, `name`, `widgets`.
4. **Widgets**: `getWidget(activity, shortId)` returns `Widget` carrying read-from-JSON `id`, `idName`, `type`, `text`, `hint`, `inputType`, `entries`, `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`, `listeners`, plus **derived** `directMop`/`transitiveMop` and per-event-type maps `directMopByEventType`/`transitiveMopByEventType` (INV-MOP-17). No `items` field — gh60 does not emit nested widgets.
5. **Listeners**: each carries `eventType`, `handler`, plus nullable forward-compat `handlerReachesTarget` / `handlerDirectlyReachesTarget` (null on every listener until gh60-C3 lands).
6. **Transitions**: `getTransitions()` carries full per-event fields including `handler` and `widgetId`.
7. **Components**: each `ComponentInfo` carries `className`, `componentType` (derived from JSON parent dict key — `activities`/`receivers`/`services`/`providers`), `isMain`, `exported`, `intentFilters` (structured with `actions` AND `categories` AND a gh60-D15 `data` block — `schemes`/`hosts`/`ports`/`paths`/`pathPrefixes`/`pathPatterns`/`mimeTypes`), `reachesTarget` (read from JSON, not hardcoded), `targetMethods`, and `permission` (gh60-D15, null when no gate). `ProviderInfo` additionally carries `authorities` plus `readPermission`/`writePermission` (gh60-D15). See INV-MOP-18.
8. **Sentinel**: top-level `"complete": true` mandatory (INV-MOP-09).
9. **Precomputed OPTIONSMENU set**: `activityHasMopOptionsMenu(activity)` returns true iff a `Window` with `type="OPTIONSMENU"` and `name="<activity>#OptionsMenu"` exists in `getWindows()` containing at least one widget that **either** has derived `directMop || transitiveMop` **or** has a WTG click-transition to a `hasMop` activity (gateway case; INV-MOP-13). OPTIONSMENU widgets are flat siblings of the Window's `widgets[]` — no nested items.
10. **Sanity check**: when `expectedPackage` / `expectedMainActivity` non-null and diverge from parsed values, emit WARN log. `Config.mopStrictPackageMatch=true` makes mismatch ⇒ `null` return.

Cross-referencing for widgets matches `windows[i].widgets[j].listeners[k].handler` against `reachability[m].methods[n].signature`. Per-event-type maps are populated by grouping listeners by `eventType` during the cross-reference pass; aggregate `directMop`/`transitiveMop` are the OR across all event types (backward compat). When `Listener.handlerReachesTarget` is non-null, the producer value takes precedence over the cross-reference (INV-MOP-12).

The widget map is keyed by base activity name and short widget resource ID. When two or more widgets within the same base activity resolve to the same short id, the map SHALL retain the widget with the strongest MOP flag — `directMop` ranks above `transitiveMop`, which ranks above unflagged — and SHALL NOT overwrite an already-stored flagged widget with an unflagged widget (INV-MOP-19). This replaces the prior last-write-wins behavior, under which an unflagged sibling could overwrite a flagged widget and silently demote its `+mopWeightDirect`/`+mopWeightTransitive` boost.

Widgets whose short id is empty SHALL NOT be stored in the map (INV-MOP-20). Because `extractShortId(GUITreeNode.getResourceID())` returns `""` for any runtime node without a resource id, an empty-string map key IS reachable at runtime — but storing id-less widgets under it would collapse every id-less widget of an activity into one colliding bucket, so the correct behavior is to drop id-less widgets at load rather than store them. The number of MOP-flagged widgets dropped for lacking a resource id SHALL be counted during parsing and logged once per load (a single `[APE-RV] MopData` line). Matching such widgets by class/text/bounds is out of scope.

The single-argument `MopData.load(path)` overload is removed (owned by the "MopData — Package / MainActivity Sanity Check" requirement); the three-argument form is the sole loader entry point.

Unknown JSON keys are ignored for forward compatibility (INV-MOP-11); the parser reads the file once into an `org.json` DOM (design D21).

[The full set of MopData scenarios from the base spec — real cryptoapp fixture typed-field load, gh60-D15 component trigger-surface fields, target-key regression, widget metadata, package/mainActivity sanity check (warn-only and strict), OPTIONSMENU gateway cases, per-event-type maps, multiple-listener idempotence, complete-but-empty JSON, file missing, null path, malformed JSON, unknown future fields — is preserved unchanged; the widget-collision and empty-id scenarios below are appended by this change.]

#### Scenario: Duplicate short id — strongest MOP flag retained
- **WHEN** one base activity contains two widgets with short id `"submit"`, the first with `directMop=true` and the second (a later window/fragment) unflagged
- **THEN** `getWidget(activity, "submit")` SHALL return the `directMop=true` widget
- **AND** the unflagged widget SHALL NOT overwrite it

#### Scenario: Duplicate short id — unflagged does not displace flagged regardless of order
- **WHEN** the unflagged widget with short id `"submit"` is parsed first and the `directMop=true` widget second
- **THEN** `getWidget(activity, "submit")` SHALL return the `directMop=true` widget (stronger flag wins on order-independent comparison)

#### Scenario: Empty short id not bucketed
- **WHEN** a base activity contains a widget whose `idName` is the empty string and which is MOP-flagged
- **THEN** the widget map for that activity SHALL NOT contain an entry under the empty-string key
- **AND** the per-load flagged-no-id drop count SHALL be incremented by one
- **AND** `activityHasMop(activity)` SHALL still return `true` (the activity-level association is unaffected)

## ADDED Requirements

### Requirement: MopData — DIALOG Window Re-Keying to Host Activity

`MopData.load` SHALL re-key DIALOG-type windows to their host activity after transitions are parsed and before the OPTIONSMENU-gateway precompute: for each window with `type=="DIALOG"`, find an incoming transition whose target is that window, take `baseActivity(source.name)` as the host, and merge the dialog's already-parsed widget entries into `widgetData[host]` using the same strongest-flag-wins collision policy (`mopRank`) as Pass 2. The dialog-class key entry SHALL be removed after a successful merge (the widgets move, they are not copied), so widget counts are not inflated. When a merged widget is MOP-flagged, `host` SHALL be added to `mopActivities` so that `activityHasMop(host)` stays consistent with the merged widget map (INV-MOP-25). DIALOG windows with no incoming transition remain keyed as-is (unreachable); their count SHALL be reported on a dedicated `[APE-RV]` diagnostic line, separate from the `[APE-MOP-DATA]` load status line (whose field set is fixed by the "MopData — Load Status Line and Fail-Fast" requirement).

Verified motivation: a DIALOG window's `name` is the dialog class (e.g. `android.app.AlertDialog`), which `baseActivity` leaves untouched and which never equals `newState.getActivity()` at runtime — so every widget-level MOP flag on a dialog widget was structurally unreachable for scoring (corpus estimate: ~86 flagged widgets across 5 of 169 apps). The WTG `transitions` already present in the same JSON carry the activity→dialog edges needed to recover the host, so the fix is consumer-side with no producer change.

#### Scenario: dialog widgets resolvable via host activity
- **WHEN** the JSON has window `{name: "android.app.AlertDialog", type: "DIALOG"}` with a flagged widget `btn_confirm`, and a transition whose source is `"com.example.MainActivity"` and target is that dialog window
- **THEN** `getWidget("com.example.MainActivity", "btn_confirm")` SHALL return the flagged widget

#### Scenario: collision on re-key keeps the strongest flag
- **WHEN** the host activity already holds a widget with the same `idName` and a weaker MOP rank than the dialog's widget
- **THEN** the dialog's widget SHALL win (same `mopRank` policy as Pass 2, INV-MOP-19)

#### Scenario: dialog-only host promoted to MOP activity
- **WHEN** an activity has no flagged widget of its own but a reachable DIALOG merges a flagged widget into it
- **THEN** `activityHasMop(host)` SHALL return `true` after load
- **AND** `getWidget(dialogClass, ...)` SHALL return `null` — the widgets moved to the host, and the dialog class is not a runtime activity key for the widget map

#### Scenario: re-keyed widgets moved, not copied
- **WHEN** a DIALOG window is re-keyed to its host activity
- **THEN** the dialog-class key SHALL be absent from the **widget map** after load (the widgets are moved, not duplicated)

#### Scenario: dialog-class MOP-activity entry retained for gateway detection
- **WHEN** a DIALOG window's own base activity (the dialog class) was added to `mopActivities` in Pass 2 (`:330`) and a WTG click edge targets that dialog window
- **THEN** the dialog class SHALL remain in `mopActivities` after the re-key, so the OPTIONSMENU-gateway precompute (condition 2, which tests `mopActivities.contains(targetActivity)`) still recognizes the source activity's menu as a gateway
- **AND** the move-not-copy removal SHALL apply to the widget map only, never to `mopActivities`

#### Scenario: orphan dialog left as-is
- **WHEN** a DIALOG window has no incoming transition in the JSON
- **THEN** its widgets SHALL remain under the dialog-class key (unreachable)
- **AND** the orphan count SHALL be reported on a dedicated `[APE-RV]` line, not on the `[APE-MOP-DATA]` load status line

## Invariants (added)

- **INV-MOP-19**: On a `shortId` collision within a base activity, the widget map SHALL retain the strongest MOP flag (direct > transitive > unflagged); an unflagged widget SHALL never overwrite a flagged one; the outcome is order-independent.
- **INV-MOP-20**: Widgets with an empty `idName` SHALL NOT be stored; the count of MOP-flagged widgets dropped for lacking a resource id SHALL be logged once per load.
- **INV-MOP-25**: After load, every DIALOG window reachable via a WTG transition SHALL have its widgets queryable under the host activity's key (subject to the standard collision policy) and removed from the dialog-class **widget-map** key; when a merged widget is flagged, `activityHasMop(host)` SHALL reflect it. The move-not-copy removal applies to the widget map only — the dialog class's Pass-2 `mopActivities` entry SHALL be retained so the OPTIONSMENU-gateway precompute (condition 2) still fires for activities that navigate into a MOP-bearing dialog. Orphan (unreachable) DIALOG windows are counted on a dedicated `[APE-RV]` diagnostic line, never on the `[APE-MOP-DATA]` status line.
