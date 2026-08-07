"""Acceptance Sec. 9.11 — regenerate the 2026-07-24 calibration report's quantities
from a new-format trace through `trace_ndjson.TraceReader`.

The corpus is not the report's (that one is frozen and legacy); the claim under test
is *recoverability*: every quantity the report consumed must be reachable from the new
format. So each quantity is recomputed here on the gh97 smoke traces and reported with
the value it takes, and any that cannot be reached is a schema gap to file.
"""

from __future__ import annotations

import glob
import json
import re
import statistics
from collections import Counter, defaultdict
from pathlib import Path

# The report buckets by action *type*; the trace carries the whole APE action
# string. Two shapes occur and the `@` means opposite things in them:
# `g0a0[...]@MODEL_CLICKclass=...` puts the type after it, while
# `EVENT_TRIGGER_ACTIVITY@org.example.SomeActivity` puts the target after it.
# Matching only the first shape is what silently dropped the component steps
# from the report's step counts (§13-C1).
_MODEL = re.compile(r"@(MODEL_[A-Z_]+)")
_EVENT = re.compile(r"^(EVENT_[A-Z_]+)@")


def action_type(action: str) -> str:
    m = _EVENT.match(action) or _MODEL.search(action)
    return m.group(1) if m else "?"

from aperv_tool.analysis.trace_ndjson import TraceReader

ROOT = Path(
    "/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/"
    "rv-android/experimento-rearch-aperv/results_smoke/rearch_aperv_smoke/"
    "rearch_aperv_smoke"
)

out: dict[str, dict] = {}

for trace in sorted(glob.glob(str(ROOT / "*" / "*.trace"))):
    p = Path(trace)
    arm = p.name.split("__")[-1].replace("aperv:", "").replace(".trace", "")
    apk = p.parent.name.replace(".apk", "")
    key = f"{apk}::{arm}"

    reader = TraceReader(p)
    rows = list(reader)
    rs = reader.run_start
    diag = reader.diagnostics

    # --- A. [APE-STEP]-derived -------------------------------------------------
    steps = len(rows)
    t = [r.t_rel_ms for r in rows]
    clock_span_ms = (max(t) - min(t)) if t else None
    actions = Counter(action_type(r.action) for r in rows)
    dec_src = Counter(r.decision_source for r in rows)
    pick_ch = Counter(r.pick_channel for r in rows)
    # §13-C1: the legacy regex dropped EVENT_TRIGGER_ACTIVITY steps
    component_steps = sum(1 for r in rows if r.component is not None)

    # --- B. [APE-OUTCOME]-derived ---------------------------------------------
    with_outcome = [r for r in rows if r.outcome is not None]
    new_state = sum(1 for r in with_outcome if r.outcome.new_state)
    act_changed = sum(1 for r in with_outcome if r.outcome.activity_changed)
    # yield of new state per decision source (report §3.1)
    yield_by_src: dict[str, list[int]] = defaultdict(list)
    for r in with_outcome:
        yield_by_src[r.decision_source].append(int(r.outcome.new_state))
    yield_tbl = {
        k: {"n": len(v), "new_state": sum(v), "yield_pct": round(100 * sum(v) / len(v), 2)}
        for k, v in sorted(yield_by_src.items())
    }

    # --- C. [APE-LLM-TEL]-derived ----------------------------------------------
    calls = [c for r in rows for c in r.llm]
    modes = Counter(c.mode for c in calls)
    results = Counter(c.result for c in calls)
    reasons = Counter(c.reason for c in calls if c.reason)
    tok_in = sum(c.tokens[0] for c in calls if c.tokens)
    tok_out = sum(c.tokens[1] for c in calls if c.tokens)
    ms = [c.ms for c in calls if c.ms is not None]
    llm_ms_total = sum(ms)
    ms_by_mode: dict[str, list[int]] = defaultdict(list)
    for c in calls:
        if c.ms is not None:
            ms_by_mode[c.mode or "?"].append(c.ms)
    latency_tbl = {
        k: {"n": len(v), "mean_ms": round(statistics.mean(v), 1)}
        for k, v in sorted(ms_by_mode.items())
    }
    # §2.3 / §2.1: inter-step clock delta with vs without an LLM call
    d_with, d_without = [], []
    for prev, cur in zip(rows, rows[1:]):
        (d_with if cur.llm else d_without).append(cur.t_rel_ms - prev.t_rel_ms)

    # --- D. [APE-RV] LLM Summary-derived ---------------------------------------
    summary = {
        "calls": len(calls),
        "time_ms": llm_ms_total,
        "matched": results.get("matched", 0),
        "llm_tap": results.get("llm_tap", 0),
        "no_match": results.get("no_match", 0),
        "repaired": sum(1 for c in calls if c.repair),
        "breaker_trips": max([c.trips for c in calls if c.trips is not None], default=0),
        "errors": results.get("error", 0),
    }

    # --- E. [APE-LLM-CONFIG]-derived -------------------------------------------
    # handoff §6.5: RUN_START.params echoes selectively -> assert on `features`
    features = (rs.params or {}).get("features") if rs else None

    # --- F. [APE-LLM-ERROR]-derived --------------------------------------------
    error_causes = Counter(c.cause for c in calls if c.result == "error")

    # --- G. MOP boosts (this stage's own additions) ----------------------------
    boosted = sum(1 for r in rows if r.mop or r.mop_frontier)

    out[key] = {
        "diagnostics": {
            "records_read": diag.records_read,
            "steps_yielded": diag.steps_yielded,
            "malformed": diag.malformed,
            "run_start_present": diag.run_start_present,
            "activities": diag.activities,
            "states": diag.states,
        },
        "A_step": {
            "steps": steps,
            "clock_span_ms": clock_span_ms,
            "actions": dict(actions.most_common()),
            "decision_source": dict(dec_src.most_common()),
            "pick_channel": dict(pick_ch.most_common()),
            "component_dispatch_steps": component_steps,
        },
        "B_outcome": {
            "rows_with_outcome": len(with_outcome),
            "new_state": new_state,
            "activity_changed": act_changed,
            "yield_by_decision_source": yield_tbl,
        },
        "C_llm_tel": {
            "calls": len(calls),
            "modes": dict(modes),
            "results": dict(results),
            "no_match_reasons": dict(reasons),
            "tokens_in": tok_in,
            "tokens_out": tok_out,
            "llm_time_s": round(llm_ms_total / 1000, 1),
            "latency_by_mode": latency_tbl,
            "step_delta_ms_with_llm": round(statistics.mean(d_with), 1) if d_with else None,
            "step_delta_ms_without_llm": round(statistics.mean(d_without), 1) if d_without else None,
        },
        "D_summary": summary,
        "E_config": {"features": features, "params_keys": sorted((rs.params or {}).keys()) if rs else None},
        "F_llm_error": dict(error_causes),
        "G_mop": {"steps_with_mop_boost": boosted},
        "H_dumps": {
            "with_system_prompt": sum(1 for c in calls if c.system_prompt),
            "with_response": sum(1 for c in calls if c.response),
            "with_tool_calls": sum(1 for c in calls if c.tool_calls),
        },
    }

print(json.dumps(out, indent=1, ensure_ascii=False))
