import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "bside/src/main/kotlin/com/appathy/sugoroku/human/MainActivity.kt")
ASSETS = os.path.join(ROOT, "bside/src/main/assets")
DRAWABLE = os.path.join(ROOT, "bside/src/main/res/drawable")

errors = []
warns = []

# 1. JSON parse
charas = json.load(open(os.path.join(ASSETS, "charas_human.json"), encoding="utf-8"))
events = json.load(open(os.path.join(ASSETS, "events_human.json"), encoding="utf-8"))

# 2. drawable references
imgs = {os.path.splitext(f)[0] for f in os.listdir(DRAWABLE)}
for c in charas["sets"]["human"]["charas"]:
    if c["img"] not in imgs:
        errors.append("missing drawable: " + c["img"])

# 3. cell index continuity
cells = events["cells"]
for n, c in enumerate(cells):
    if c["i"] != n:
        errors.append("cell index mismatch at %d (i=%s)" % (n, c["i"]))
if cells[0]["type"] != "START":
    errors.append("cell 0 must be START")
if cells[-1]["type"] != "GOAL":
    errors.append("last cell must be GOAL")

# 4. type-specific required fields
VALID = {"START", "GOAL", "NORMAL", "GOOD", "BAD", "WARP", "REST", "CHOICE", "CHALLENGE"}
for c in cells:
    if c["type"] not in VALID:
        errors.append("unknown type at %d: %s" % (c["i"], c["type"]))
    if c["type"] == "CHOICE" and len(c.get("choices", [])) < 2:
        errors.append("CHOICE needs 2 choices at %d" % c["i"])
    if c["type"] == "CHALLENGE":
        for k in ("stat", "need", "ok", "ng"):
            if k not in c:
                errors.append("CHALLENGE missing %s at %d" % (k, c["i"]))
        if c.get("stat") not in ("st", "sp", "pp", "mn"):
            errors.append("CHALLENGE bad stat at %d" % c["i"])
    if c["type"] == "WARP" and c.get("move", 0) == 0:
        errors.append("WARP needs move at %d" % c["i"])
    mv = c.get("move", 0)
    if not (0 <= c["i"] + mv <= len(cells) - 1):
        errors.append("move goes out of board at %d" % c["i"])

# 5. challenge feasibility (rough): accumulated stat before the cell
st = sp = pp = 5
mn = 1000
for c in cells:
    ch = c if c["type"] == "CHALLENGE" else None
    if ch:
        cur = {"st": st, "sp": sp, "pp": pp, "mn": mn}[ch["stat"]]
        if ch["need"] > cur * 1.6:
            warns.append("challenge at %d may be too hard (need=%s, typical=%s)" % (c["i"], ch["need"], cur))
    st += c.get("st", 0)
    sp += c.get("sp", 0)
    pp += c.get("pp", 0)
    mn += c.get("mn", 0)

# 6. Kotlin traps
src = open(KT, encoding="utf-8").read()
if src.count("{") != src.count("}"):
    errors.append("brace imbalance: %d open vs %d close" % (src.count("{"), src.count("}")))
if src.count("(") != src.count(")"):
    errors.append("paren imbalance: %d open vs %d close" % (src.count("("), src.count(")")))
for m in re.finditer(r"\$[A-Za-z_][A-Za-z0-9_]*", src):
    tail = src[m.end():m.end() + 1]
    if tail and ord(tail) > 0x2000:
        errors.append("string template trap: %s followed by non-ascii" % m.group(0))
if re.search(r"\becho\b", src):
    warns.append("echo found in source")

# 7. endings keys
for e in events["endings"]:
    if e["key"] not in ("st", "sp", "pp", "mn"):
        errors.append("bad ending key: " + e["key"])

for w in warns:
    print("WARN " + w)
for e in errors:
    print("ERROR " + e)
print("cells=%d charas=%d drawables=%d" % (len(cells), len(charas["sets"]["human"]["charas"]), len(imgs)))
sys.exit(1 if errors else 0)
