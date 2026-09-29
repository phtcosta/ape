"""Fit the choice temperature of a fine-tuned checkpoint on data/val_bal.jsonl and write it into rl_agent_config.json.

The checkpoint is served with temperature 1, so the returned probabilities p give the logits up to a constant
(log p); p_T ∝ p^(1/T). T minimises the soft cross-entropy against the label-F gold distributions. The runtime clamps
temperatures to [0.5, 5.0], so the search stays inside that range.
    uv run python train/calibrate.py /home/pedro/tmp/laya/train/out/v1
"""
import json
import math
import os
import sys

from laya import Router

ck = sys.argv[1]
r = Router(models={"english": ck, "multilingual": ck}, default="english", device="cuda")
L, G = [], []
for line in open("/home/pedro/tmp/laya/train/data/val_bal.jsonl"):
    row = json.loads(line)
    p = r.predict(row["state"], row["questions"], model="english", max_len=1024, head_max_len=640)["answers"]["next"]["probabilities"]
    ks = list(row["gold"]["next"]["probabilities"])
    L.append([math.log(max(p[k], 1e-12)) for k in ks])
    G.append([row["gold"]["next"]["probabilities"][k] for k in ks])


def ce(t):
    tot = 0.0
    for l, g in zip(L, G):
        z = [x / t for x in l]
        m = max(z)
        lse = m + math.log(sum(math.exp(x - m) for x in z))
        tot -= sum(gi * (zi - lse) for gi, zi in zip(g, z))
    return tot / len(L)


ts = [0.5 * (10 ** (i / 100)) for i in range(101)]  # 0.5 .. 5.0 log-spaced
best = min(ts, key=ce)
print(f"n={len(L)}  soft-CE T=1: {ce(1.0):.4f}  T={best:.3f}: {ce(best):.4f}")
cfgp = os.path.join(ck, "rl_agent_config.json")
cfg = json.load(open(cfgp))
cfg["temperature"] = [best, 1.0, 1.0]
cfg["temperature_by_options"] = {}
json.dump(cfg, open(cfgp, "w"), indent=2)
print("wrote temperature", best)
