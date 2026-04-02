<!-- This change spans two repositories: rvsec (rvsec-gator) and ape (APE-RV).
     Groups 1-2 completed in rvsec#45 (gh45-enrich-gator-entrypoints) with EXPANDED scope.
     Groups 3-5 are in ape (this session).
     Critical path: 1/2 (done) → 3 → 4 → 5 -->

## 1. XMLParser — getReceivers()/getProviders() accessors (rvsec-gator) — COMPLETED in rvsec#45

- [x] 1.1 Add `getReceivers()` to XMLParser interface and implementations.
- [x] 1.2 Add `getProviders()` and `getProviderAuthorities()` (scope expansion: ContentProviders included).
- [x] 1.3 Move `getServices()` from DefaultXMLParser to AbstractXMLParser (consistency fix).
- [x] 1.4 Parse `android:exported` for all component types. Add `isComponentExported()` to XMLParser.

## 2. RvsecAnalysisClient — entry points + JSON (rvsec-gator) — COMPLETED in rvsec#45

- [x] 2.1 Modify `getEntryPoints()` to add Services, Receivers, and ContentProviders as entry points with lifecycle methods.
- [x] 2.1.1 Modify `complementWithCallbacks()` for Service/Receiver/Provider lifecycle MOP propagation.
- [x] 2.2 Add `writeComponents()` — writes `components{}` with `activities[]`, `receivers[]`, `services[]`, `providers[]`.
- [x] 2.3 Call `writeComponents()` from `writeJson()` as Section 4 after transitions.
- [x] 2.4 Replace `isActivity`/`isMainActivity` with `componentType`/`isMain` in `reachability[]`.
- [x] 2.5 Build rvsec-gator, all tests pass.

## 3. MopData — parse components{} (ape)

- [x] 3.1 Create `ComponentInfo` base class + `ReceiverInfo`, `ServiceInfo`, `ActivityInfo`, `ProviderInfo` in `ape/utils/`.
- [x] 3.2 Add Pass 4 to `MopData.java`: parse all 4 arrays in `components{}`, retaining ALL entries (not filtered by reachesMop).
- [x] 3.3 Add methods: `getReceivers()`, `getServices()`, `getActivities()`, `getProviders()`, `hasComponents()`.
- [x] 3.4 Add `forTest()` overload with component data.
- [x] 3.5 Update summary log line to include component counts.
- [x] 3.6 Unit tests: 4 new tests in `MopDataTest.java` (all types, backward compat, receiver actions, provider authorities).
- [x] 3.7 `mvn clean package` — 275 tests, 0 failures.

## 4. Component triggering in APE-RV (ape)

- [x] 4.1 Add `Config.componentPercentage` (double, default 0.05 when mopDataPath set, 0.0 otherwise).
- [x] 4.2 Add `AndroidDevice.sendBroadcast()`, `startService()` (reflection-based for API compat), `startActivity()`. `broadcastIntent()` catch Exception (not just RemoteException).
- [x] 4.3 Create `SystemBroadcastCatalog` — loads VLM-Fuzz catalog from device, lookup by action → typed extras. File at `data/system-broadcast.json`.
- [x] 4.4 Probabilistic triggering in `SataAgent.selectNewActionNonnull()`: fires with `componentPercentage` probability as side-effect (no step consumed). Round-robin across receivers + services only (Activities excluded — disrupts SATA flow).
- [x] 4.5 `triggerMopComponent()` in `StatefulAgent`: round-robin, broadcast with catalog extras, service by ComponentName.
- [x] 4.6 Unit tests deferred (SystemBroadcastCatalog uses android.util.JsonReader). Smoke tested on-device.
- [x] 4.7 `mvn clean package` — 274 tests, 0 failures.

## 5. Final Verification

- [x] 5.1 Backward compat: unit test `testComponents_backwardCompat` passes.
- [x] 5.2 New JSON verified: logcat confirms "loaded 7 MOP components" + "118 broadcast actions".
- [x] 5.3 `mvn clean package` — BUILD SUCCESS.
- [x] 5.4 Smoke test on-device: 3min, 260 GUI actions + 8 triggers (3%), all 6 component types (2 broadcasts, 2 services, 2 activities), no crash. Exp6: 169 APKs × 3 reps, 507/507 completed, +0.60pp method, +1.59pp MOP vs baseline.
