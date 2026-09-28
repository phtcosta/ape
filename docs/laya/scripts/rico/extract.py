"""Pass 1: walk every Rico trace, emit one compact record per recorded gesture -> data/rico/steps.jsonl.gz.

Run: uv run python rico/extract.py [--limit N_TRACES] [--procs 48]
"""
import argparse
import gzip
import json
import sys
from multiprocessing import Pool
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import ricolib as R

OUT = Path("/home/pedro/tmp/laya/data/rico/steps.jsonl.gz")


def clsb(n):
    a = R.ancestors(n)
    for name, b in (("android.widget.EditText", "EditText"), ("android.widget.CompoundButton", "Toggle"),
                    ("android.widget.ImageButton", "ImageButton"), ("android.widget.Button", "Button"),
                    ("android.widget.ImageView", "ImageView"), ("android.widget.TextView", "TextView"),
                    ("android.widget.AdapterView", "ListLike"), ("android.support.v7.widget.RecyclerView", "ListLike"),
                    ("android.webkit.WebView", "WebView"), ("android.view.ViewGroup", "Container")):
        if name in a:
            return b
    return "View"


def feat(c, K):
    b = c["bounds"]
    yc = (b[1] + b[3]) / 2 / R.SCREEN_H
    af = c["area"] / (R.SCREEN_W * R.SCREEN_H)
    return [c["kind"], clsb(c["node"]), c["src"], min(int(yc * 5), 4),
            0 if af < .01 else 1 if af < .05 else 2 if af < .25 else 3]


def edit_texts(vh):
    return {(n.get("resource-id") or str(o)): (n.get("text") or "") for n, d, o in R.visible_nodes(vh)
            if R.is_a(n, R.EDIT)}


def do_trace(args):
    pkg, t, d = args
    d = Path(d)
    ids, g = R.load_trace(d)
    recs = []
    vhs = {}

    def vh(ui):
        if ui not in vhs:
            vhs[ui] = R.load_vh(d, ui)
        return vhs[ui]
    for i, ui in enumerate(ids):
        v = vh(ui)
        if v is None:
            continue
        pts = g.get(ui)
        nxt = ids[i + 1] if i + 1 < len(ids) else None
        cands = R.candidates(v)
        K = len(cands)
        gt = R.gesture_type(pts)
        rec = {"app": pkg, "trace": t, "ui": ui, "next": nxt, "idx": i, "n_ui": len(ids), "gt": gt,
               "npts": len(pts or []), "K": K, "kb": bool(v.get("is_keyboard_deployed")),
               "act": v.get("activity_name"), "sig": R.h(R.signature(v)),
               "sigt": R.h(R.signature(v, "text")),
               "root": v["activity"]["root"].get("bounds"),
               "cf": [feat(c, K) for c in cands], "ck": [R.action_key(c) for c in cands]}
        gold = None
        if pts:
            x, y = R.to_px(pts[0])
            if gt == "tap":
                gold = R.hit(cands, x, y, kinds=("tap", "longtap", "check", "type"))
                if gold is None:
                    rec["any_node"] = any(R.contains(n["bounds"], x, y) for n, _, _ in R.visible_nodes(v)[1:])
            elif gt.startswith("swipe"):
                gold = R.hit(cands, x, y, kinds=("scroll",))
            rec["y"] = pts[0][1]
        rec["gold"] = cands.index(gold) if gold is not None else None
        if nxt is not None and vh(nxt) is not None:
            nv = vh(nxt)
            rec["nact"] = nv.get("activity_name")
            rec["nsig"] = R.h(R.signature(nv))
            rec["nsigt"] = R.h(R.signature(nv, "text"))
            if gold is not None and gold["kind"] == "type":
                a, b = edit_texts(v), edit_texts(nv)
                rec["typed"] = any(b.get(k, "") != a.get(k, "") for k in a)
        recs.append(rec)
    return recs


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--procs", type=int, default=48)
    a = ap.parse_args()
    tr = [(p, t, str(d)) for p, t, d in R.list_traces()]
    if a.limit:
        tr = tr[: a.limit]
    n = 0
    with gzip.open(OUT, "wt") as f, Pool(a.procs) as pool:
        for recs in pool.imap_unordered(do_trace, tr, chunksize=8):
            for r in recs:
                f.write(json.dumps(r) + "\n")
                n += 1
    print(f"traces={len(tr)} records={n} -> {OUT}")
