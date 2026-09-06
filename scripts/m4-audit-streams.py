#!/usr/bin/env python3
"""Audit captured participant streams to decide whether a reconstructed final
state can be trusted.

    scripts/m4-audit-streams.py <trial-dir> [--eval-input screens/cart.kt]

Reconstruction by replaying Edit calls is only sound if the captured stream
contains EVERY change to the evaluator's input. This checks that claim rather
than assuming it:

  * Edit/Write/NotebookEdit calls, their targets, and whether the tool_result
    came back as an error (a failed edit must not be replayed)
  * every Bash redirect, classified by its TARGET PATH — a redirect into the
    workspace source tree is a change the replay would miss; /dev/null, a
    participant scratchpad, or a grep pattern is not
  * every non-redirect write-capable shell construct (sed -i, cp/mv/rm, git
    mutation, patch, heredoc, python open(...,'w'))

Verdicts:
  REPLAYABLE       — the only workspace writes are successful Edits to the
                     evaluator input; replay reproduces the final state
  CAVEAT           — replayable, but something else in the workspace changed
  NOT-ESTABLISHED  — a write was found that replay cannot account for
"""
import json, sys, glob, os, re

WRITE_CMDS = [
    (r'\bsed\b[^|]*-i', 'sed -i'), (r'\btee\b', 'tee'),
    (r'\b(cp|mv|rm|touch|ln)\b', 'cp/mv/rm/touch/ln'),
    (r'\bgit\s+(checkout|restore|apply|reset|stash|clean|commit)\b', 'git mutation'),
    (r'\bpatch\b', 'patch'), (r'cat\s*<<', 'heredoc'),
    (r'open\([^)]*[\'"]w', 'python write'),
]

def redirect_targets(cmd):
    """Redirect targets, minus the ones that are shell noise or regex text."""
    out = []
    for m in re.finditer(r'>>?\s*("[^"]+"|\S+)', cmd):
        t = m.group(1).strip('"').rstrip(';')
        if t in ('&1', '&2', '/dev/null'):
            continue
        # a '>' inside a quoted grep/sed pattern is not a redirect
        if not (t.startswith('/') or t.startswith('$') or t.startswith('.')):
            continue
        out.append(t)
    return out

def audit_run(stream, ws_root, eval_input):
    uses, results, order = {}, {}, []
    for line in open(stream, encoding='utf-8', errors='replace'):
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        for b in ((e.get('message') or {}).get('content') or []):
            if not isinstance(b, dict):
                continue
            if b.get('type') == 'tool_use':
                uses[b['id']] = b; order.append(b['id'])
            elif b.get('type') == 'tool_result':
                results[b.get('tool_use_id')] = b

    edits, problems, other_ws_writes = [], [], []
    for tid in order:
        u = uses[tid]; r = results.get(tid)
        err = bool(r.get('is_error')) if r else None
        name = u.get('name', '?'); inp = u.get('input') or {}
        if name in ('Edit', 'Write', 'NotebookEdit'):
            path = inp.get('file_path', '')
            edits.append((name, path, err))
            if err:
                problems.append(f'{name} FAILED on {path} — replay must skip it')
            elif not path.endswith(eval_input):
                other_ws_writes.append(f'{name} -> {path}')
        elif name == 'Bash':
            cmd = inp.get('command', '')
            for t in redirect_targets(cmd):
                if ws_root and ws_root in t and '/src/' in t:
                    problems.append(f'Bash redirect into the source tree: {t}')
                elif ws_root and ws_root in t:
                    other_ws_writes.append(f'Bash redirect -> {t}')
            for pat, label in WRITE_CMDS:
                if re.search(pat, cmd):
                    problems.append(f'Bash {label}: {cmd[:160]}')
                    break
    return edits, problems, other_ws_writes, len(order)

def main():
    T = sys.argv[1]
    eval_input = 'screens/cart.kt'
    if '--eval-input' in sys.argv:
        eval_input = sys.argv[sys.argv.index('--eval-input') + 1]
    counts = {'REPLAYABLE': 0, 'CAVEAT': 0, 'NOT-ESTABLISHED': 0}
    for stream in sorted(glob.glob(os.path.join(T, 'reports', '*-r*-stream.jsonl'))):
        tag = re.search(r'/([a-z]+-r\d+)-stream', stream).group(1)
        arm = tag.split('-')[0]
        ws_root = os.path.join(T, f'ws-{arm}')
        edits, problems, other, n = audit_run(stream, ws_root, eval_input)
        good = [e for e in edits if not e[2] and e[1].endswith(eval_input)]
        verdict = ('NOT-ESTABLISHED' if problems
                   else 'CAVEAT' if other or not good
                   else 'REPLAYABLE')
        counts[verdict] += 1
        print(f'=== {tag}  {n} tool calls, {len(edits)} edit(s), '
              f'{len(good)} successful edit(s) to {eval_input}')
        for name, path, err in edits:
            print(f"      {name:6} {'ERROR' if err else 'ok'}  ...{path[-46:]}")
        for p in problems:
            print(f'      PROBLEM: {p}')
        for o in other:
            print(f'      OTHER WORKSPACE WRITE: {o}')
        print(f'      VERDICT: {verdict}')
    print('\n' + '  '.join(f'{k}={v}' for k, v in counts.items()))
    return 0 if counts['NOT-ESTABLISHED'] == 0 else 1

sys.exit(main())
