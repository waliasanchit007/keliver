#!/usr/bin/env python3
"""NF4 on iOS: the checks (E1-E4) and the medians of scripts/keliver-nf4-ios-sim.sh.

  keliver-nf4-ios-sim-report.py <evidence-dir> <server-delay-ms> <runs>

Reads <evidence-dir>/launches/<arm>-<n>.console.txt (the harness's "nf4:" line, the host's
"KeliverTiming: <name> <nanos>" marks and its log) and <evidence-dir>/server.log (each launch's
section, "=== <label>", then "<wall ms> GET <path> [#n]"). Prints PASS/FAIL lines and writes
<evidence-dir>/results.md. All times are CACurrentMediaTime's clock; the server's wall-clock
times are placed on it through the nf4 line's (init, wall) pair.
"""
import os
import re
import statistics
import sys

ev, delay, runs = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
MARK = re.compile(r'KeliverTiming: ([a-z0-9-]+) (\d+)')
NF4 = re.compile(r'nf4: arm=(\w) state=(\d+) exec=(-?\d+) init=(\d+) initDone=(\d+) wall=(\d+)')
API = re.compile(r'^(\d+) GET (/api/items\S*) #(\d+)$')

server = {}
label = None
for line in open(os.path.join(ev, 'server.log')):
    line = line.rstrip('\n')
    if line.startswith('=== '):
        label = line[4:]
        server[label] = []
    elif label is not None:
        m = API.match(line)
        if m:
            server[label].append(int(m.group(1)))

launches = []
for name in sorted(os.listdir(os.path.join(ev, 'launches'))):
    m = re.match(r'([abc])-(\d+)\.console\.txt$', name)
    if not m:
        continue
    text = open(os.path.join(ev, 'launches', name), errors='replace').read().replace('\r', '')
    label = name[:-len('.console.txt')]
    l = {'label': label, 'arm': m.group(1), 'marks': {}, 'ui': [], 'held': 0}
    for mm in MARK.finditer(text):
        l['marks'].setdefault(mm.group(1), int(mm.group(2)))
    nf = NF4.search(text)
    if nf:
        l.update(state=int(nf.group(2)), exec=int(nf.group(3)), init=int(nf.group(4)),
                 initDone=int(nf.group(5)), wall=int(nf.group(6)))
    l['ui'] = re.findall(r'KeliverHost: UI start: (.*)', text)
    l['held'] = len(re.findall(r'start: no scene in the foreground yet', text))
    http = [int(k[5:]) for k in l['marks'] if re.fullmatch(r'http-\d+', k)]
    l['http'] = http[0] if http else None
    reqs = server.get(label, [])
    l['requests'] = len(reqs)
    if reqs and 'init' in l:
        l['req'] = l['init'] + (reqs[0] - l['wall']) * 1_000_000
    launches.append(l)

def ms(a, b):
    return None if a is None or b is None else (b - a) / 1e6

ok = True
def check(cond, text):
    global ok
    print(('PASS ' if cond else 'FAIL ') + text)
    ok = ok and cond

for arm in 'abc':
    got = [l for l in launches if l['arm'] == arm]
    check(len(got) == runs, f'arm {arm}: {len(got)} launches of {runs}')

# E4 / E1: every launch drew the answer; no crash before it.
drawn = [l for l in launches if 'content' in l['marks'] and 'exec' in l]
check(len(drawn) == len(launches) and launches,
      f'E4: every launch reached its content mark, the nf4 line first (no crash in init): {len(drawn)} of {len(launches)}')
for arm in 'bc':
    got = [l for l in launches if l['arm'] == arm]
    once = [l for l in got if len(l['ui']) == 1]
    causes = sorted({c for l in got for c in l['ui']})
    held = sum(1 for l in got if l['held'])
    states = sorted({l.get('state') for l in got if 'state' in l})
    check(got and len(once) == len(got),
          f'E1: arm {arm}: one UI start per launch ({len(once)} of {len(got)}): {"; ".join(causes)}; '
          f'UIApplication state read in init(): {states} (0 active, 1 inactive, 2 background); '
          f'start() in init() held until the UI start in {held} of {len(got)}')

# E2: arm c's guest took the prefetched answer.
for l in [l for l in launches if l['arm'] == 'c']:
    code = l['marks'].get('code')
    good = (l['requests'] == 1 and l.get('req') is not None and code is not None and l['req'] < code
            # A request of its own takes at least the delay; under it, with one GET at the server,
            # the guest took the prefetch (in flight when it asked, or answered already).
            and l['http'] is not None and l['http'] < delay - 20)
    check(good, f"E2: {l['label']}: {l['requests']} GET items, sent {ms(l.get('req'), code)} ms before the code loaded, "
                f"the guest's request answered in {l['http']} ms (the server takes {delay})")
# E3: arms a and b went to the network after the code loaded.
for l in [l for l in launches if l['arm'] in 'ab']:
    code = l['marks'].get('code')
    good = (l['requests'] == 1 and l.get('req') is not None and code is not None and l['req'] > code
            and l['http'] is not None and l['http'] >= delay - 5)
    check(good, f"E3: {l['label']}: {l['requests']} GET items, sent {ms(code, l.get('req'))} ms after the code loaded, "
                f"answered in {l['http']} ms")

def med(arm, a, b):
    v = [ms(l.get(a) if a in ('exec', 'init', 'req') else l['marks'].get(a),
            l.get(b) if b in ('exec', 'init', 'req', 'initDone') else l['marks'].get(b))
         for l in launches if l['arm'] == arm]
    v = [x for x in v if x is not None]
    return (statistics.median(v), min(v), max(v), len(v)) if v else None

rows = [
    ('exec → init()', 'exec', 'init'),
    ('init() → init() done (start() inside it)', 'init', 'initDone'),
    ('exec → UI start', 'exec', 'ui-start'),
    ('exec → host start (lookup begins)', 'exec', 'start'),
    ('exec → screen made (open)', 'exec', 'open'),
    ('exec → code loaded', 'exec', 'code'),
    ('exec → first frame', 'exec', 'first-frame'),
    ('exec → content (first guest frame with its data)', 'exec', 'content'),
]
names = {'a': 'a: start at the first screen', 'b': 'b: start() in init()', 'c': 'c: b + prefetch'}
out = ['# NF4 on iOS: the simulator run', '',
       f'Median (min-max) milliseconds over {runs} process-cold launches per arm (terminate, 1 s, launch), '
       f'arms rotated; Debug build; the API answers after {delay} ms. A simulator on a Mac is not a phone: '
       'these compare the arms here and are not phone numbers.', '',
       '| | ' + ' | '.join(names[a] for a in 'abc') + ' |', '|---|---|---|---|']
for title, a, b in rows:
    cells = []
    for arm in 'abc':
        r = med(arm, a, b)
        cells.append('-' if r is None else f'{r[0]:.0f} ({r[1]:.0f}-{r[2]:.0f})')
    out.append(f'| {title} | ' + ' | '.join(cells) + ' |')
http = {arm: [l['http'] for l in launches if l['arm'] == arm and l['http'] is not None] for arm in 'abc'}
out.append("| the guest's GET answered in | " + ' | '.join(
    f'{statistics.median(http[a]):.0f}' if http[a] else '-' for a in 'abc') + ' |')
out += ['', '## Each launch', '', '| launch | exec→init | exec→ui-start | exec→start | exec→code | exec→content | GET answered | GETs | UI start |',
        '|---|---|---|---|---|---|---|---|---|']
for l in sorted(launches, key=lambda l: (l['arm'], int(l['label'].split('-')[1]))):
    f = lambda b: ms(l.get('exec'), l['marks'].get(b))
    fmt = lambda x: '-' if x is None else f'{x:.0f}'
    out.append(f"| {l['label']} | {fmt(ms(l.get('exec'), l.get('init')))} | {fmt(f('ui-start'))} | {fmt(f('start'))} | "
               f"{fmt(f('code'))} | {fmt(f('content'))} | {l['http']} | {l['requests']} | {'; '.join(l['ui'])}"
               f"{' (held)' if l['held'] else ''} |")
open(os.path.join(ev, 'results.md'), 'w').write('\n'.join(out) + '\n')
print('\n'.join(out))
sys.exit(0 if ok else 1)
