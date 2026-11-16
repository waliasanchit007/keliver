"""Every QuickJS name Zipline 1.28.0's native layer uses, and how its declaration differs between
QuickJS 2021-03-27 and 2026-06-04 headers. Full declarations (multi-line, inline bodies cut at '{')."""
import re, sys, pathlib
native, h21, h26, out = map(pathlib.Path, sys.argv[1:5])
src = "\n".join(p.read_text(errors="replace") for p in native.rglob("*") if p.is_file())
ours = set(re.findall(r"\b(?:JS_[A-Z][A-Za-z0-9_]*|js_[a-z][a-z0-9_]*)\b", src))
own = set(re.findall(r"\b(JS_[A-Za-z0-9_]+)\s*\([^;{)]*\)\s*\{", src))  # defined in Zipline's own files
def decls(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S); text = re.sub(r"//[^\n]*", " ", text)
    found = {}
    for m in re.finditer(r"#define\s+([A-Za-z_][A-Za-z0-9_]*)([^\n]*(?:\\\n[^\n]*)*)", text):
        found.setdefault(m.group(1), "#define " + m.group(1) + re.sub(r"\s+", " ", m.group(2)).strip()[:160])
    for m in re.finditer(r"(^|[;}\n])\s*((?:static\s+inline\s+|static\s+|inline\s+)?[A-Za-z_][\w\s\*]*?\b([A-Za-z_]\w*)\s*\(([^;{]*?)\))\s*[;{]", text, flags=re.S):
        name = m.group(3)
        if name in ("if", "while", "for", "switch", "return", "sizeof"): continue
        found.setdefault(name, re.sub(r"\s+", " ", m.group(2)).strip())
    for m in re.finditer(r"\b(JS_[A-Z0-9_]+)\s*=\s*([^,\n}]*)", text):  # enum constants
        found.setdefault(m.group(1), m.group(1) + " = " + m.group(2).strip())
    for m in re.finditer(r"typedef[^;]*?\b(JS[A-Za-z0-9_]*)\s*;", text):
        found.setdefault(m.group(1), "typedef ... " + m.group(1))
    for m in re.finditer(r"typedef\s+struct\s+(JS[A-Za-z0-9_]*)", text):
        found.setdefault(m.group(1), "typedef struct " + m.group(1))
    return found
d21, d26 = decls(h21.read_text()), decls(h26.read_text())
names = sorted(n for n in ours if n not in own and (n in d21 or n in d26))
unknown = sorted(n for n in ours if n not in own and n not in d21 and n not in d26 and n.startswith("JS_"))
lines = [f"{len(names)} QuickJS names used by Zipline 1.28.0's native layer (zipline/native, excluding names it defines itself: {', '.join(sorted(own)) or 'none'})"]
same = changed = 0
for n in names:
    a, b = d21.get(n), d26.get(n)
    if a == b: same += 1; continue
    changed += 1
    lines.append(f"\n{n}\n  2021: {a or '<not in header>'}\n  2026: {b or '<not in header>'}")
lines.insert(1, f"identical declaration: {same}; changed or missing: {changed}")
if unknown: lines.append("\nused but not found in either header (macros of other names, or not QuickJS): " + ", ".join(unknown))
pathlib.Path(out).write_text("\n".join(lines) + "\n"); print("\n".join(lines[:2]))
