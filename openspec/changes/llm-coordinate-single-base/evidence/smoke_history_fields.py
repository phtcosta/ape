"""history_fields_scan.scan on this jar's traces: the list has no ordinal and shares the history's base.

RUN (from this directory)
  python3 -I smoke_history_fields.py . smoke_cryptoapp_20261008.trace.gz > smoke_history_fields.out
"""
import collections, importlib.util, re, sys
spec = importlib.util.spec_from_file_location("hf", sys.argv[1] + "/history_fields_scan.py")
hf = importlib.util.module_from_spec(spec); spec.loader.exec_module(hf)
hf.RE_LIST = re.compile(r'^  (\S+)(?: "(.*?)")? @\((-?\d+),(-?\d+)\)')   # no ordinal (D10)
src = open(sys.argv[1] + "/history_fields_scan.py").read()
# one base for list and history (D1, D5): match on the history's own y, no 1794 -> 1920 rescale
assert "y_root = int(y * 1794 / 1920)" in src
ns = dict(vars(hf)); exec("def scan" + src.split("def scan", 1)[1].split("def main", 1)[0].replace(
    "y_root = int(y * 1794 / 1920)", "y_root = y"), ns)
import gzip, os, tempfile
C = collections.Counter()
trace = sys.argv[2]
if trace.endswith(".gz"):   # history_fields_scan.scan opens plain text
    with gzip.open(trace, "rt") as src, tempfile.NamedTemporaryFile("w", delete=False) as tmp:
        tmp.write(src.read())
    trace = tmp.name
ns["scan"](trace, C)
if trace != sys.argv[2]:
    os.unlink(trace)
for k in sorted(C): print("%-30s %d" % (k, C[k]))
