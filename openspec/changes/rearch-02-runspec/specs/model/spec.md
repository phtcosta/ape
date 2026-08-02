# Delta Specification: model (rearch-02-runspec)

## REMOVED Requirements

### Requirement: Model Serialization on Normal Termination

**Reason**: The persistence protocol is broken by construction and unused: `StatefulAgent.saveGraph` writes a serialized `Model` (`oos.writeObject(model)`, `sataModel.obj`), while the read side `Graph.readGraph` casts the stream to `Graph`, swallows the resulting `ClassCastException` in a blanket `catch (Exception)`, and returns an empty `new Graph()` — a `--ape-model` run would silently start from zero after logging only "Fail to load graph" (verified V14, finding 3.3-6). Owner mandate R1/R3 (clean runs; results are never state; no read-back): the protocol is **removed, not fixed**. `StatefulAgent.saveGraph`, `Graph.readGraph`, the `--ape-model` CLI option, `ape.modelFile`, and the flags `ape.saveObjModel`/`ape.saveDotGraph`/`ape.saveVisGraph` are deleted; `ApeAgent.createAgent` always constructs a fresh `Graph`. No shim, no deprecation path: the retired keys and the removed CLI option abort resolution loudly (`run-spec` capability). Nothing replaces the artifact — offline graph inspection, if ever needed again, is host-side post-processing of the stage-4 trace, and resilience is the Python supervisor's per-task retry.

The teardown ordering property the serialization participated in (coverage dump strictly before the expensive write, INV-COV-10) is preserved and restated against the surviving chain by this change's `exploration` delta ("Output Persistence on Termination"). The tolerant action-history persistence requirement (INV-MODEL-15) is untouched — `action-history.log` remains until stage 4.

## ADDED Requirements

### Requirement: No Model Deserialization and No XPath Action Injection

The model layer SHALL have no deserialization entry point and no external action-injection channel:

1. `Graph.readGraph` SHALL NOT exist; no code path SHALL construct a `Graph` or `Model` from a serialized artifact. Every run starts from an empty graph (R1: no operational state survives a session; R3: no artifact is read back).
2. The XPath action-injection channel SHALL NOT exist: the `ape.model.xpathaction` package (`XPathActionController`, `XPathAction`, `XPathActionSequence`, `XPathActionReader`, and helpers), its static-initializer read of `/sdcard/ape.xpath.actions`, the consuming branch in `StatefulAgent` (`enableXPathAction` gate), and the `ape.enableXPathAction` key are all deleted (owner decision D6: no arm uses the channel and `tool.py` never pushes the file). Action selection is exclusively the agent's decision over the model's own actions.

#### Scenario: no run reads a previous run's model

- **WHEN** a run terminates and a subsequent run starts on the same device
- **THEN** the second run SHALL construct an empty `Graph`
- **AND** no file produced by the first run SHALL be opened by the second run's explorer

#### Scenario: xpath action file has no effect

- **WHEN** a legacy `/sdcard/ape.xpath.actions` file exists on the device
- **THEN** no code SHALL read it and no injected action SHALL enter selection
- **AND** setting `ape.enableXPathAction=true` in `ape.properties` SHALL abort resolution as a retired key
