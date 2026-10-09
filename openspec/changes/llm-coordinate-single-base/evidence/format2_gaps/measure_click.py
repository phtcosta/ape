"""Exact gap-2 count: per emitted widget, what the jar's id-key lookup gives for a click / long-click.
flag>0 iff (event key in mop ? mop[event]!=none : any flag); distance pairs = dist[event] only.
Also counts what the aggregate-fallback rule (decided 2026-10-09) would give.
Usage: python3 -I -B measure_click.py <derive dir> <docs dir> <out jsonl>"""
import json, multiprocessing as mp, os, sys
NOT_BY_CLICK = {"longclick", "itemlongclick", "implicitcreatecontextmenu", "scroll"}  # final rule, 2026-10-09
def norm(e): return "".join(c.lower() for c in e if c not in "_-")
def work(a):
    mod, path = a
    sys.path.insert(0, mod); import derive_mop_artifact as dm
    with open(path, "rb") as f: raw = f.read()
    doc = json.loads(raw); del raw
    art = dm.derive(doc, os.path.basename(path), ""); del doc
    out = {"file": os.path.basename(path)}
    for ev in ("click", "longclick"):
        flag_nodist = flag_nodist_agg_near = flag_nodist_agg_any = 0
        for acts in art["widgets"].values():
            for w in acts.values():
                mop = {norm(k): v for k, v in (w.get("mop") or {}).items()}
                dist = {norm(k): v for k, v in (w.get("dist") or {}).items()}
                flagged = (mop[ev] != "none") if ev in mop else any(v != "none" for v in mop.values())
                if not flagged or dist.get(ev): continue
                flag_nodist += 1
                if ev in mop: continue  # aggregate rule mirrors the flag: only when the key is absent
                agg = [p for e, l in dist.items() if ev != "click" or e not in NOT_BY_CLICK for p in l]
                if agg: flag_nodist_agg_any += 1
                if any(p[1] <= 3 for p in agg): flag_nodist_agg_near += 1
        out[ev] = [flag_nodist, flag_nodist_agg_any, flag_nodist_agg_near]
    return out
if __name__ == "__main__":
    mod, d, o = sys.argv[1:4]
    files = sorted((os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk.json")), key=os.path.getsize, reverse=True)
    with mp.get_context("spawn").Pool(6, maxtasksperchild=1) as p, open(o, "w") as fo:
        for r in p.imap_unordered(work, [(mod, f) for f in files]): fo.write(json.dumps(r) + "\n"); fo.flush()
