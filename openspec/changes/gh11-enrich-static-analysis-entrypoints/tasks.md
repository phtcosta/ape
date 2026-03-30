<!-- This change spans two repositories: rvsec (rvsec-gator) and ape (APE-RV).
     Groups 1-2 are in rvsec-gator, Group 3 is in ape, Group 4 is verification.
     Groups 1 and 2 are independent and can run in parallel.
     Group 3 depends on Group 2 (JSON format must be defined before MopData parser).
     Group 4 depends on all previous groups.
     Critical path: 1/2 (parallel) → 3 → 4 -->

## 1. XMLParser — getReceivers() accessor (rvsec-gator)

- [ ] 1.1 Add `getReceivers()` method to `XMLParser` interface in `rvsec/rvsec/rvsec-android/rvsec-gator/sootandroid/src/main/java/presto/android/xml/XMLParser.java` (L154, alongside existing `getServices()`). Return type: `Iterator<String>`.
- [ ] 1.2 Add `getReceivers()` implementation in `DefaultXMLParser` (next to `getServices()` at L169): return `receivers.iterator()`.
- [ ] 1.3 Add `getReceivers()` implementation in `AbstractXMLParser` inner class (L46-49 area, alongside `getActivities()`): return `receivers.iterator()`.

## 2. RvsecAnalysisClient — entry points + JSON (rvsec-gator)

- [ ] 2.1 Modify `getEntryPoints()` in `RvsecAnalysisClient.java` (L252-266) to iterate over Services from `XMLParser.Factory.getXMLParser().getServices()` and Receivers from `XMLParser.Factory.getXMLParser().getReceivers()`. Resolve class names to `SootClass` via `Scene.v().getSootClassUnsafe()`. For each resolved class, add lifecycle methods (`onStartCommand`, `onBind`, `onUnbind`, `onCreate`, `onDestroy`, `onHandleIntent` for Services; `onReceive` for Receivers) using `SootClass.getMethodByNameUnsafe()`. Also add public/protected methods following the same pattern as Activities. Skip unresolvable classes with WARNING log.
- [ ] 2.1.1 Modify `complementWithCallbacks()` (L351-393) to also add Service/Receiver lifecycle methods as callbacks. Currently only iterates `output.getActivities()` for lifecycle handlers (L362-364). Add a second loop resolving Service/Receiver classes (same pattern as 2.1) and adding their lifecycle methods to the `callbacks` set so they receive MOP flag propagation via the call graph.
- [ ] 2.2 Add `writeComponents()` method to `RvsecAnalysisClient.java` that writes a `components[]` JSON array. Each entry: `{"className": "...", "type": "SERVICE"|"BROADCAST_RECEIVER"}`. Source data from `XMLParser.Factory.getXMLParser().getServices()` and `getReceivers()`.
- [ ] 2.3 Call `writeComponents()` from `writeJson()` (L749-782) — add as Section 4 after transitions, writing `w.name("components"); writeComponents(w, ...); w.flush();`.
- [ ] 2.4 Add `isService` and `isReceiver` boolean fields to the `reachability[]` entries in `writeReachability()` (L784-821). Resolve which classes are Services/Receivers by checking against the XMLParser lists.
- [ ] 2.5 Build rvsec-gator and run existing integration tests (`RvsecAnalysisClientIT`).

## 3. MopData — parse components[] (ape)

- [ ] 3.1 Add Pass 4 to `MopData.java` (`src/main/java/com/android/commands/monkey/ape/utils/MopData.java`): parse `components[]` section. Build a `Set<String>` of component class names and a `Map<String, Boolean>` of component MOP reachability (cross-reference with the reachability map from Pass 1).
- [ ] 3.2 Add methods `hasComponentMop(String className)` and `getComponentCount()` to `MopData`.
- [ ] 3.3 Update the summary log line (L110) to include component count.
- [ ] 3.4 Add unit tests to `MopDataTest.java`: test JSON with components[], test JSON without components[] (backward compat), test component with MOP reachability.
- [ ] 3.5 `mvn clean package` — verify BUILD SUCCESS and all tests pass.

## 4. Final Verification

- [ ] 4.1 Generate a new static analysis JSON for an APK with known Services/Receivers (e.g., quasseldroid or similar). Verify `components[]` section is present and `reachability[]` includes Service/Receiver methods.
- [ ] 4.2 Load the new JSON in APE-RV's MopData and verify `getComponentCount() > 0` and `hasComponentMop()` returns correct values.
- [ ] 4.3 Verify backward compatibility: load an old JSON (without `components[]`) and confirm MopData behaves identically.
- [ ] 4.4 `mvn clean package` in both repos — BUILD SUCCESS.
