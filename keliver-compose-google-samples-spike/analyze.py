#!/usr/bin/env python3
# Research spike (stone U1): classifies AndroidX's Compose samples by how they compile.
#
#   python3 -I analyze.py <gradle log> <spike module dir> <out dir>
#
# Reads the Kotlin diagnostics ("e: file:///…:line:col message") of the two compiles of
# build.gradle (jvm: the original sources on real CMP; js: the rewritten sources on the guest API),
# attributes each to the top-level declaration that contains it, and writes per-sample results
# (samples.tsv), per-name counts (names.tsv) and API usage frequencies (usage.tsv).
import collections
import json
import os
import re
import sys

log_path, module_dir, out_dir = sys.argv[1:4]
native_root = os.path.join(module_dir, 'build/google-samples/native')
guest_root = os.path.join(module_dir, 'build/google-samples/guest')
os.makedirs(out_dir, exist_ok=True)

MODULES = [
    ('material3', 'androidx/compose/material3/samples'),
    ('foundation-layout', 'androidx/compose/foundation/layout/samples'),
    ('foundation', 'androidx/compose/foundation/samples'),
    ('animation-core', 'androidx/compose/animation/core/samples'),
    ('animation', 'androidx/compose/animation/samples'),
    ('ui', 'androidx/compose/ui/samples'),
]


def module_of(rel):
    for name, prefix in MODULES:
        if rel.startswith(prefix + '/'):
            return name
    return '?'


TOP = re.compile(r'^(@|fun |private |internal |public |class |data class|enum class|sealed |object |'
                 r'interface |abstract |open |val |var |const |inline |suspend |typealias |fun\s*<)')


def declarations(path):
    """Top-level declarations: (start line, end line, name, is_sampled, text), 1-based inclusive."""
    lines = open(path, encoding='utf-8').read().split('\n')
    starts = []
    i = 0
    pending = None
    for n, line in enumerate(lines, 1):
        if line.startswith('@file:'):
            continue
        if TOP.match(line):
            if pending is None:
                pending = n
            if not line.startswith('@'):
                starts.append(pending)
                pending = None
        elif line.strip() and not line.startswith((' ', '\t', '}', ')', ']')) and pending is not None:
            # annotation followed by something unusual: close it
            starts.append(pending)
            pending = None
    decls = []
    for k, s in enumerate(starts):
        e = (starts[k + 1] - 1) if k + 1 < len(starts) else len(lines)
        body = '\n'.join(lines[s - 1:e])
        m = re.search(r'\b(?:fun|class|object|val|var|interface|typealias)\s+(?:<[^>]*>\s*)?'
                      r'(?:[A-Za-z0-9_.<>?]+\.)?([A-Za-z_][A-Za-z0-9_]*)', body)
        name = m.group(1) if m else f'decl@{s}'
        sampled = bool(re.search(r'^@Sampled\b', body, re.M))
        decls.append((s, e, name, sampled, body))
    return decls, lines


def imports(lines):
    out = {}
    for line in lines:
        m = re.match(r'import\s+([\w.]+?)\.(\w+)(?:\s+as\s+(\w+))?\s*$', line.strip())
        if m:
            out[m.group(3) or m.group(2)] = m.group(1) + '.' + m.group(2)
    return out


DIAG = re.compile(r'^e: file://(.+?\.kt):(\d+):(\d+) (.*)$')
diags = collections.defaultdict(list)  # (copy, rel) -> [(line, message)]
for raw in open(log_path, encoding='utf-8', errors='replace'):
    m = DIAG.match(raw.rstrip('\n'))
    if not m:
        continue
    path, line, msg = m.group(1), int(m.group(2)), m.group(4)
    for copy, root in (('native', native_root), ('guest', guest_root)):
        if path.startswith(root + '/'):
            diags[(copy, os.path.relpath(path, root))].append((line, msg))


def names_in(msg):
    """What a diagnostic says is missing or mismatched."""
    m = re.match(r"Unresolved reference '([^']+)'", msg)
    if m:
        return ('unresolved', m.group(1))
    m = re.match(r"'([^']+)' is deprecated\. (Keliver: .*)", msg)
    if m:  # one of the prototype's ERROR-level stubs; the message says why
        return ('stub', m.group(1) + ' | ' + m.group(2)[:110])
    m = re.match(r"No parameter with name '([^']+)' found", msg)
    if m:
        return ('no-param', m.group(1))
    m = re.match(r"Unresolved label|Cannot infer type|Cannot infer|Not enough information to infer", msg)
    if m:
        return ('cascade', '')
    if msg.startswith(('None of the following candidates', 'Argument type mismatch', 'No value passed',
                       'Too many arguments', 'Return type mismatch', 'Initializer type mismatch',
                       'Type mismatch', 'Assignment type mismatch')):
        return ('signature', msg[:90])
    return ('other', msg[:120])


results = []
usage = collections.Counter()           # Compose name -> samples using it (all samples)
usage_pkg = {}
for copy_dir in (native_root,):
    for dirpath, _, files in os.walk(copy_dir):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, copy_dir)
            decls, lines = declarations(path)
            imp = imports(lines)
            gdecls, glines = declarations(os.path.join(guest_root, rel))
            # Diagnostics by declaration index (native and guest have the same line numbers).
            def bucket(copy):
                b = collections.defaultdict(list)
                filelevel = []
                for (ln, msg) in diags.get((copy, rel), []):
                    if lines[ln - 1].startswith(('import ', 'package ', '@file:')):
                        filelevel.append((ln, msg))
                        continue
                    hit = [k for k, d in enumerate(decls) if d[0] <= ln <= d[1]]
                    (b[hit[0]] if hit else filelevel).append((ln, msg))
                return b, filelevel
            nb, nfile = bucket('native')
            gb, gfile = bucket('guest')
            # Imports the guest copy cannot resolve: the names missing from the guest API.
            # K2 names the first unresolvable segment ('focus' in …ui.focus.FocusRequester), so the
            # missing name is the import line's own.
            missing = set()
            for ln, m in gfile:
                im = re.match(r'import\s+[\w.]+\.(\w+)(?:\s+as\s+(\w+))?\s*$', lines[ln - 1].strip())
                if im and names_in(m)[0] == 'unresolved':
                    missing.add(im.group(2) or im.group(1))
            helper_names = {d[2]: k for k, d in enumerate(decls) if not d[3]}
            for k, (s, e, name, sampled, body) in enumerate(decls):
                if not sampled:
                    continue
                # A sample also fails when a same-file helper it calls fails.
                used_helpers = [helper_names[h] for h in helper_names
                                if re.search(r'\b' + re.escape(h) + r'\b', body) and helper_names[h] != k]
                ks = [k] + used_helpers
                nerr = [x for j in ks for x in nb.get(j, [])]
                gerr = [x for j in ks for x in gb.get(j, [])]
                code = '\n'.join(l for d in [k] + used_helpers for l in decls[d][4].split('\n')
                                 if not l.lstrip().startswith(('//', '*', '/*')))
                used = set()
                for simple, fq in imp.items():
                    if fq.startswith('androidx.compose.') and not fq.startswith('androidx.compose.runtime.') \
                            and re.search(r'\b' + re.escape(simple) + r'\b', code):
                        used.add(simple)
                        usage_pkg[simple] = fq.rsplit('.', 1)[0]
                for u in used:
                    usage[u] += 1
                optins = sorted(set(re.findall(r'Experimental\w+', body)))
                m2 = sorted(u for u in used if usage_pkg[u] == 'androidx.compose.material')
                results.append({
                    'module': module_of(rel), 'file': rel, 'sample': name, 'line': s,
                    'native': [names_in(m) for _, m in nerr],
                    'guest': [names_in(m) for _, m in gerr],
                    'guest_raw': [m for _, m in gerr][:40],
                    'native_raw': [m for _, m in nerr][:20],
                    'imports': imp, 'used': sorted(used), 'optins': optins, 'm2': m2,
                    'missing': sorted(u for u in used if u in missing),
                })

json.dump(results, open(os.path.join(out_dir, 'samples.json'), 'w'), indent=1, default=list)

with open(os.path.join(out_dir, 'usage.tsv'), 'w') as out:
    out.write('samples\tname\tpackage\n')
    for name, n in usage.most_common():
        out.write(f'{n}\t{name}\t{usage_pkg[name]}\n')

# Names missing in the guest copy, for samples that compile natively.
miss = collections.Counter()
miss_pkg = {}
for r in results:
    if r['native']:
        continue
    seen = set()
    for kind, what in r['guest']:
        if kind in ('unresolved', 'no-param'):
            key = (kind, what)
            if key in seen:
                continue
            seen.add(key)
            miss[key] += 1
            fq = r['imports'].get(what)
            miss_pkg[key] = fq.rsplit('.', 1)[0] if fq else ''
with open(os.path.join(out_dir, 'names.tsv'), 'w') as out:
    out.write('samples\tkind\tname\timported-from\n')
    for (kind, what), n in miss.most_common():
        out.write(f'{n}\t{kind}\t{what}\t{miss_pkg[(kind, what)]}\n')

with open(os.path.join(out_dir, 'samples.tsv'), 'w') as out:
    out.write('module\tsample\tnative-errors\tguest-errors\tguest-unresolved\n')
    for r in results:
        unresolved = sorted({w for k, w in r['guest'] if k == 'unresolved'})
        out.write(f"{r['module']}\t{r['sample']}\t{len(r['native'])}\t{len(r['guest'])}\t{','.join(unresolved)}\n")

by = collections.defaultdict(collections.Counter)
for r in results:
    st = 'compiles' if not r['guest'] else ('fails-native' if r['native'] else 'fails-guest-only')
    by[r['module']][st] += 1
print('module\tsamples\tcompiles\tfails-native(android-only)\tfails-guest-only')
for name, _ in MODULES:
    c = by[name]
    print(f"{name}\t{sum(c.values())}\t{c['compiles']}\t{c['fails-native']}\t{c['fails-guest-only']}")
print('diagnostic files:', len(diags), 'native diags:', sum(len(v) for (c, _), v in diags.items() if c == 'native'),
      'guest diags:', sum(len(v) for (c, _), v in diags.items() if c == 'guest'))
