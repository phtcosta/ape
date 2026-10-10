"""Second pass: target visibility and artifact size under alternative cuts (read only).

Usage: python3 -I -B measure_k.py <derive module dir> <documents dir> <out jsonl> [max MB]
For each document: parse once, derive once per cut variant, and record
- the artifact size under each variant;
- per kind (widget/handler/activity), the set of targets present in some list
  at d<=3 before the cut, and present in some list at d<=3 after the K=3 cut.
"""
import json
import multiprocessing as mp
import os
import sys

VARIANTS = {"K3": ("k", 3), "K5": ("k", 5), "K10": ("k", 10), "D3": ("d", 3), "D6": ("d", 6)}


def work(args):
    mod_dir, path = args
    sys.path.insert(0, mod_dir)
    import derive_mop_artifact as dm

    ctx = {"where": None, "rec": None}
    pre = {"widget": set(), "handler": set(), "activity": set()}
    post = {"widget": set(), "handler": set(), "activity": set()}

    def make_cut(kind, bound):
        def cut(minima):
            nearest = sorted((d, i) for i, d in minima.items())
            if kind == "k":
                kept = nearest[:bound]
            else:
                kept = [p for p in nearest if p[0] <= bound]
            if ctx["rec"]:
                w = ctx["where"]
                pre[w].update(i for d, i in nearest if d <= 3)
                post[w].update(i for d, i in kept if d <= 3)
            return [[i, d] for d, i in kept]
        return cut

    for name, where in (("_emit_widgets", "widget"), ("_emit_activity_dist", "activity"),
                        ("_emit_handlers", "handler")):
        real = getattr(dm, name)

        def inner(*a, _real=real, _where=where, **k):
            ctx["where"] = _where
            return _real(*a, **k)

        setattr(dm, name, inner)

    with open(path, "rb") as f:
        raw = f.read()
    digest = dm.digest_of(raw)
    doc = json.loads(raw)
    del raw
    sizes = {}
    for label, (kind, bound) in VARIANTS.items():
        dm._cut_pairs = make_cut(kind, bound)
        ctx["rec"] = label == "K3"
        art = dm.derive(doc, os.path.basename(path), digest)
        sizes[label] = len(dm.serialize_canonical(art))
        if label == "K3":
            targets = art["targets"]
    return {"file": os.path.basename(path), "targets": targets, "sizes": sizes,
            "pre": {k: sorted(v) for k, v in pre.items()},
            "post": {k: sorted(v) for k, v in post.items()}}


def main(mod_dir, docs_dir, out, max_mb="100000"):
    limit = int(max_mb) * 2**20
    files = sorted((os.path.join(docs_dir, f) for f in os.listdir(docs_dir)
                    if f.endswith(".apk.json") and os.path.getsize(os.path.join(docs_dir, f)) <= limit),
                   key=os.path.getsize, reverse=True)
    with mp.get_context("spawn").Pool(6, maxtasksperchild=1) as pool, open(out, "w") as o:
        for r in pool.imap_unordered(work, [(mod_dir, p) for p in files]):
            o.write(json.dumps(r) + "\n")
            o.flush()


if __name__ == "__main__":
    main(*sys.argv[1:])
