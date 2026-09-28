"""Rico filtered_traces -> Laya rows JSONL (prototype).

Row: {state:{activity, screen_text}, candidates:[{id,text}], gold_id, next_screen_changed, app, trace,
      ui, K_full, gesture, meta:{feats, succ_gap, outcome_valid}}
- candidates: APE-like actionable nodes rendered "tap Button 'OK' (id=ok_btn)" + "press back"; ids are
  neutral (o0..), order shuffled; shortlisted to <= MAXK keeping gold.
- gold: innermost actionable node under the tap point (taps) / scrollable under the swipe start (swipes).
  Steps whose gesture hits no candidate are dropped.
- next_screen_changed: struct signature of the successor UI differs. CAVEAT: in filtered_traces the
  successor is the next *retained unique* UI (median 49 raw UIs later), so this is NOT the immediate
  outcome of the gold action; meta.outcome_valid is False unless the UI numbers are adjacent.

Run: uv run python rico/convert.py --traces 700 --out data/rico/rows_sample.jsonl
"""
import argparse
import json
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ricolib as R
from extract import feat

MAXK = 20


def rows_for_trace(pkg, t, d, rng):
    ids, g = R.load_trace(d)
    out = []
    for i, ui in enumerate(ids):
        v = R.load_vh(d, ui)
        pts = g.get(ui)
        if v is None or not pts:
            continue
        cands = R.candidates(v)
        gt = R.gesture_type(pts)
        x, y = R.to_px(pts[0])
        if gt == "tap":
            gold = R.hit(cands, x, y, kinds=("tap", "longtap", "check", "type"))
        elif gt.startswith("swipe"):
            gold = R.hit(cands, x, y, kinds=("scroll",))
        else:
            gold = None
        if gold is None:
            continue
        K = len(cands)
        others = [c for c in cands if c is not gold]
        rng.shuffle(others)
        keep = [gold] + others[: MAXK - 2]  # room for "press back"
        rng.shuffle(keep)
        opts = [(R.render(c), c) for c in keep] + [("press back", None)]
        rng.shuffle(opts)
        cand_rows, gold_id, feats = [], None, []
        for j, (txt, c) in enumerate(opts):
            cid = f"o{j}"
            cand_rows.append({"id": cid, "text": txt})
            feats.append(feat(c, K) if c is not None else ["back", "Back", "none", 0, 0])
            if c is gold:
                gold_id = cid
        nxt = ids[i + 1] if i + 1 < len(ids) else None
        nv = R.load_vh(d, nxt) if nxt is not None else None
        changed = (R.signature(nv) != R.signature(v)) if nv is not None else None
        act = (v.get("activity_name") or "").split("/")[-1]
        out.append({
            "state": {"activity": act.rsplit(".", 1)[-1], "screen_text": " | ".join(R.screen_text(v))[:600]},
            "candidates": cand_rows, "gold_id": gold_id, "next_screen_changed": changed,
            "app": pkg, "trace": t, "ui": ui, "K_full": K, "gesture": gt,
            "meta": {"feats": feats, "succ_gap": (nxt - ui) if nxt is not None else None,
                     "outcome_valid": nxt is not None and nxt - ui == 1}})
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--traces", type=int, default=700)
    ap.add_argument("--rows", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--out", default="/home/pedro/tmp/laya/data/rico/rows_sample.jsonl")
    a = ap.parse_args()
    rng = random.Random(a.seed)
    tr = list(R.list_traces())
    rng.shuffle(tr)
    n = 0
    with open(a.out, "w") as f:
        for pkg, t, d in tr:
            for r in rows_for_trace(pkg, t, d, rng):
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
                n += 1
            if n >= a.rows:
                break
    print(f"rows={n} -> {a.out}")
