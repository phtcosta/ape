<!-- This change spans two repositories: rvsec (rvsec-gator) and ape (APE-RV).
     Groups 1-2 completed in rvsec#45 (gh45-enrich-gator-entrypoints) with EXPANDED scope.
     Groups 3-5 are in ape (this session).
     Group 3 depends on Group 2 (JSON format defined by GATOR).
     Group 4 depends on Group 3 (needs MopData to provide component data).
     Group 5 depends on all previous groups.
     Critical path: 1/2 (done) → 3 → 4 → 5 -->

## 1. XMLParser — getReceivers()/getProviders() accessors (rvsec-gator) — COMPLETED in rvsec#45

- [x] 1.1 Add `getReceivers()` to XMLParser interface and implementations.
- [x] 1.2 Add `getProviders()` and `getProviderAuthorities()` (scope expansion: ContentProviders included).
- [x] 1.3 Move `getServices()` from DefaultXMLParser to AbstractXMLParser (consistency fix).
- [x] 1.4 Parse `android:exported` for all component types. Add `isComponentExported()` to XMLParser.

## 2. RvsecAnalysisClient — entry points + JSON (rvsec-gator) — COMPLETED in rvsec#45

- [x] 2.1 Modify `getEntryPoints()` to add Services, Receivers, and ContentProviders (scope expansion) as entry points with lifecycle methods.
- [x] 2.1.1 Modify `complementWithCallbacks()` for Service/Receiver/Provider lifecycle MOP propagation.
- [x] 2.2 Add `writeComponents()` — writes `components{}` with `activities[]`, `receivers[]`, `services[]`, `providers[]` (scope expansion: all 4 types). Each entry: className, isMain, intentFilters (or authorities for providers), exported, reachesMop, mopMethods.
- [x] 2.3 Call `writeComponents()` from `writeJson()` as Section 4 after transitions.
- [x] 2.4 Replace `isActivity`/`isMainActivity` booleans with `componentType` string (`"activity"|"service"|"receiver"|"provider"|null`) + `isMain` boolean in `reachability[]` entries.
- [x] 2.5 Build rvsec-gator, all tests pass.

## 3. MopData — parse components{} (ape)

- [x] 3.1 Create domain classes in `ape/utils/`: `ComponentInfo` (base with className, actions, reachesMop), `ReceiverInfo`, `ServiceInfo`, `ActivityInfo`, `ProviderInfo` (with authorities String instead of actions).
- [x] 3.2 Add Pass 4 to `MopData.java`: parse `components{}` section. Parse all 4 arrays (`activities[]`, `receivers[]`, `services[]`, `providers[]`), retaining only entries with `reachesMop=true`. Extract intent-filter actions for activities/receivers/services, authorities for providers.
- [x] 3.3 Add methods to `MopData`: `getMopReceivers()`, `getMopServices()`, `getMopActivities()`, `getMopProviders()`, `hasComponents()`.
- [x] 3.4 Add `forTest()` parameter for component data (extend existing factory).
- [x] 3.5 Update the summary log line to include component counts.
- [x] 3.6 Add unit tests to `MopDataTest.java`: components{} with all 4 types, backward compat (no components key), receiver with MOP + intent-filter actions, provider with authorities.
- [x] 3.7 `mvn clean package` — 275 tests, 0 failures, BUILD SUCCESS.

## 4. Component triggering in APE-RV (ape)

- [x] 4.1 Add Config flag: `testComponents` (boolean, default false) in `Config.java`. Single flag controls all component triggering (broadcasts, services, activities, providers).
- [x] 4.2 Add `AndroidDevice.startService(Intent)` — using `IActivityManager.startService()`. Add `AndroidDevice.startActivity(Intent)` — using `IActivityManager.startActivity()`. Add `AndroidDevice.sendBroadcast(Intent)` — public wrapper around existing `broadcastIntent()`. ContentProvider query deferred — providers skipped in triggerMopComponent() for now.
- [x] 4.3 Create `SystemBroadcastCatalog` class in `ape/utils/` — loads `system-broadcast.json` from device (`/data/local/tmp/`), provides lookup by action string → list of typed extras. Parses `--es`/`--ei`/`--ez`/`--el`/`--ef` flags from `adb` field. VLM-Fuzz catalog copied to `data/system-broadcast.json` for push alongside JAR.
- [x] 4.4 Component triggering implemented directly in `StatefulAgent.onGraphStable()` via `triggerMopComponent()` — round-robins across MOP receivers (with broadcast + catalog extras), services (startService), and activities (startActivity). Not via separate MonkeySourceApe event types — the trigger is synchronous within the stagnation handler.
- [x] 4.5 Stagnation escape hatch in `StatefulAgent.onGraphStable()`: when `graphStableCounter > threshold` AND `Config.testComponents` AND `_mopData.hasComponents()`, calls `triggerMopComponent()` BEFORE `requestRestart()`. Resets counter on success.
- [ ] 4.6 Add unit tests: SystemBroadcastCatalog parsing runs on-device (android.util.JsonReader) — defer to smoke test.
- [x] 4.7 `mvn clean package` — 275 tests, 0 failures, BUILD SUCCESS.

## 5. Final Verification

- [x] 5.1 Verify backward compat: unit test `testComponents_backwardCompat` passes — MopData with no components{} returns empty lists, hasComponents()=false.
- [ ] 5.2 Verify new JSON: load a JSON with components{} from rvsec#45 — hasComponents()=true, getMopReceivers()/etc return correct data. PENDING — needs JSON from rvsec#45.
- [x] 5.3 `mvn clean package` — 275 tests, 0 failures, BUILD SUCCESS.
- [ ] 5.4 Smoke test on-device: run APE-RV with `testComponents=true` on APK with MOP receivers. Verify broadcasts sent during stagnation.
