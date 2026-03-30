<!-- This change spans two repositories: rvsec (rvsec-gator) and ape (APE-RV).
     Groups 1-2 are in rvsec-gator (rv-android session).
     Groups 3-5 are in ape (this session).
     Groups 1 and 2 are independent and can run in parallel.
     Group 3 depends on Group 2 (JSON format must be defined before MopData parser).
     Group 4 depends on Group 3 (needs MopData to provide component data).
     Group 5 depends on all previous groups.
     Critical path: 1/2 (parallel) → 3 → 4 → 5 -->

## 1. XMLParser — getReceivers() accessor (rvsec-gator)

- [ ] 1.1 Add `getReceivers()` method to `XMLParser` interface (`presto/android/xml/XMLParser.java` L154, alongside `getServices()`). Return type: `Iterator<String>`.
- [ ] 1.2 Add `getReceivers()` implementation in `DefaultXMLParser` (next to `getServices()` at L169): return `receivers.iterator()`.
- [ ] 1.3 Add `getReceivers()` implementation in `AbstractXMLParser` inner class (alongside `getActivities()`): return `receivers.iterator()`.

## 2. RvsecAnalysisClient — entry points + JSON (rvsec-gator)

- [ ] 2.1 Modify `getEntryPoints()` (L252-266) to iterate over Services from `XMLParser.Factory.getXMLParser().getServices()` and Receivers from `getReceivers()`. Resolve class names to `SootClass` via `Scene.v().getSootClassUnsafe()`. Add lifecycle methods (`onCreate`, `onStartCommand`, `onBind`, `onUnbind`, `onDestroy`, `onHandleIntent` for Services; `onReceive` for Receivers) + public/protected methods. Skip unresolvable classes with WARNING log.
- [ ] 2.1.1 Modify `complementWithCallbacks()` (L351-393) to add Service/Receiver lifecycle methods to the callbacks set for MOP flag propagation.
- [ ] 2.2 Add `writeComponents()` method — writes `"components": {"receivers": [...], "services": [...]}`. Each entry: `className`, `intentFilters` (from IntentFilterManager), `exported` (from manifest), `reachesMop`, `mopMethods` (signatures of lifecycle methods with MOP reachability).
- [ ] 2.3 Call `writeComponents()` from `writeJson()` (L749-782) as Section 4 after transitions, with `w.flush()`.
- [ ] 2.4 Add `isService` and `isReceiver` boolean fields to `reachability[]` entries in `writeReachability()` (L784-821).
- [ ] 2.5 Build rvsec-gator and run existing integration tests (`RvsecAnalysisClientIT`).

## 3. MopData — parse components{} (ape)

- [ ] 3.1 Create `ReceiverInfo` and `ServiceInfo` domain classes in `ape/utils/` — each holds `className` (String) and `actions` (List\<String\>).
- [ ] 3.2 Add Pass 4 to `MopData.java`: parse `components{}` section. Parse `receivers[]` and `services[]`, retaining only entries with `reachesMop=true`. Extract intent-filter actions.
- [ ] 3.3 Add methods `getMopReceivers()`, `getMopServices()`, `hasComponents()` to `MopData`.
- [ ] 3.4 Update the summary log line to include component count.
- [ ] 3.5 Add unit tests to `MopDataTest.java`: JSON with components{}, JSON without components{} (backward compat), receiver/service with MOP reachability and intent-filter actions.
- [ ] 3.6 `mvn clean package` — verify BUILD SUCCESS and all tests pass.

## 4. Component triggering in APE-RV (ape)

- [ ] 4.1 Add Config flags: `testBroadcasts` (boolean, default false), `testServices` (boolean, default false) in `Config.java`.
- [ ] 4.2 Add `AndroidDevice.startService(Intent)` method — symmetric to `broadcastIntent()`, using `IActivityManager.startService()` via reflection.
- [ ] 4.3 Embed VLM-Fuzz system broadcast catalog (`/tmp/VLM-Fuzz/system-broadcast.json`, 187 entries, 120 with typed extras) as a resource in APE-RV. Create `SystemBroadcastCatalog` class in `ape/utils/` that loads the JSON and provides lookup by action string → list of extras (key, type, value). Parse `--es` (String), `--ei` (int), `--ez` (boolean), `--el` (long), `--ef` (float) flags from the `adb` field.
- [ ] 4.4 Add `EVENT_BROADCAST` and `EVENT_START_SERVICE` to action type handling in `MonkeySourceApe`. For EVENT_BROADCAST: construct Intent with action from ReceiverInfo + ComponentName; if `SystemBroadcastCatalog` has a match for the action, add the typed extras to the Intent. Call `AndroidDevice.broadcastIntent()`. For EVENT_START_SERVICE: construct Intent with ComponentName (+ action if available), call `AndroidDevice.startService()`.
- [ ] 4.5 Add stagnation escape hatch in `SataAgent.selectNewActionNonnull()`: when `graphStableCounter > threshold` AND `Config.testBroadcasts/testServices` AND `_mopData.hasComponents()`, select broadcast/service action (round-robin across MOP components) BEFORE falling through to restart. Reset `graphStableCounter` after trigger.
- [ ] 4.6 Add unit tests: Config defaults (testBroadcasts=false, testServices=false), SystemBroadcastCatalog lookup (match with extras, no match returns empty).
- [ ] 4.7 `mvn clean package` — verify BUILD SUCCESS and all tests pass.

## 5. Final Verification

- [ ] 5.1 Generate a new static analysis JSON for an APK with known Services/Receivers. Verify `components{}` section has intentFilters, exported, reachesMop, mopMethods.
- [ ] 5.2 Load new JSON in MopData, verify `hasComponents()=true`, `getMopReceivers()`/`getMopServices()` return correct data.
- [ ] 5.3 Verify backward compatibility: old JSON (without components{}) works unchanged.
- [ ] 5.4 Smoke test: run APE-RV with `testBroadcasts=true` on an APK with MOP receivers. Verify broadcasts are sent during stagnation.
- [ ] 5.5 `mvn clean package` in both repos — BUILD SUCCESS.
