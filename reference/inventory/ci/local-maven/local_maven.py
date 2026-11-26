"""The local-maven route's file edits (ci/local-maven/route.sh calls it; no Gradle, no network).

  local_maven.py move-app <app-dir> <version> <repo> <fork> <zipline-group> <zipline-plugin-id>
                          <zipline> <kotlin> <compose> <coroutines> <serialization>
      Moves an app made by bootstrap.sh (its overlay is on the 0.4 line) onto <version> and its
      line: keliver.portal.json's appRuntime.keliverVersion, the Kotlin, Compose and Zipline plugins
      in settings.gradle, every dev.keliver library in build.gradle, the Zipline artifact, the
      Compose runtime, coroutines and serialization, and (on the fork's line, <fork> = true) the
      guest's -Xes-long-as-bigint, as keliver-new-device-target.sh writes it. dev.keliver then
      resolves only from <repo> (add-repo). Run twice, it changes nothing the second time.

  local_maven.py add-repo <settings.gradle> <repo>
      Puts an exclusiveContent repository first in the settings' dependencyResolutionManagement
      repositories: dev.keliver (that group exactly, not dev.keliver.zipline) resolves ONLY from the
      local Maven repository at <repo>, so a module the route did not publish fails the build
      instead of coming from Maven Central. Idempotent.

On an edit without its anchor it prints why (one line) and exits 3, before writing anything.
"""
import json
import os
import re
import sys

MARK = "// local-maven route (reference/inventory/ci/local-maven): dev.keliver ONLY from this checkout's libraries"


def fail(msg):
    print(msg)
    raise SystemExit(3)


def sub(text, pattern, repl, fname, exactly=None):
    new, n = re.subn(pattern, repl, text)
    if (exactly is not None and n != exactly) or n < 1:
        fail(f"{fname}: {n} match(es) of {pattern!r}, expected {exactly if exactly is not None else 'at least 1'}")
    return new


def add_repo(text, repo, fname):
    if MARK in text:
        return text
    if "'" in repo or "\\" in repo or not repo.startswith('/'):
        fail(f"{fname}: the local repository must be an absolute path without quotes or backslashes: {repo}")
    drm = re.search(r"dependencyResolutionManagement\s*\{", text)
    if not drm:
        fail(f"{fname}: no dependencyResolutionManagement block to add the local repository to")
    rep = re.compile(r"\n([ \t]*)repositories\s*\{").search(text, drm.end())
    if not rep:
        fail(f"{fname}: no repositories block in dependencyResolutionManagement")
    ind = rep.group(1) + "  "
    block = (f"\n{ind}{MARK}\n"
             f"{ind}exclusiveContent {{\n"
             f"{ind}  forRepository {{ maven {{ name = 'keliverLocalMaven'; url = new File('{repo}').toURI() }} }}\n"
             f"{ind}  filter {{ includeGroup('dev.keliver') }}\n"
             f"{ind}}}\n{ind}")
    return text[:rep.end()] + block + text[rep.end():].lstrip(' ')


def move_app(app, kv, repo, fork, zg, zid, zv, kotlin, compose, coroutines, serialization):
    fork = fork == 'true'
    pj = os.path.join(app, 'keliver.portal.json')
    ps = os.path.join(app, 'settings.gradle')
    pb = os.path.join(app, 'build.gradle')
    c = json.load(open(pj, encoding='utf-8'))
    s = open(ps, encoding='utf-8').read()
    b = open(pb, encoding='utf-8').read()

    c.setdefault('appRuntime', {})['keliverVersion'] = kv

    for plugin in ('multiplatform', 'plugin.compose', 'plugin.serialization'):
        s = sub(s, r"(id 'org\.jetbrains\.kotlin\." + re.escape(plugin) + r"' version ')[^']+(')",
                r"\g<1>" + kotlin + r"\g<2>", 'settings.gradle', exactly=1)
    s = sub(s, r"(id 'org\.jetbrains\.compose' version ')[^']+(')", r"\g<1>" + compose + r"\g<2>", 'settings.gradle', exactly=1)
    s = sub(s, r"id '(app\.cash|dev\.keliver)\.zipline' version '[^']+'", f"id '{zid}' version '{zv}'", 'settings.gradle', exactly=1)
    s = add_repo(s, repo, 'settings.gradle')

    b = sub(b, r"id '(app\.cash|dev\.keliver)\.zipline'(\s*\n)", f"id '{zid}'" + r"\g<2>", 'build.gradle', exactly=1)
    b = sub(b, r"'(app\.cash|dev\.keliver)\.zipline:zipline:[^']+'", f"'{zg}:zipline:{zv}'", 'build.gradle', exactly=1)
    b = sub(b, r"'dev\.keliver:([a-z0-9-]+):[^']+'", r"'dev.keliver:\g<1>:" + kv + "'", 'build.gradle')
    b = sub(b, r"'org\.jetbrains\.compose\.runtime:runtime:[^']+'",
            f"'org.jetbrains.compose.runtime:runtime:{compose}'", 'build.gradle', exactly=1)
    b = sub(b, r"'org\.jetbrains\.kotlinx:kotlinx-coroutines-core:[^']+'",
            f"'org.jetbrains.kotlinx:kotlinx-coroutines-core:{coroutines}'", 'build.gradle', exactly=1)
    b = sub(b, r"'org\.jetbrains\.kotlinx:kotlinx-serialization-json:[^']+'",
            f"'org.jetbrains.kotlinx:kotlinx-serialization-json:{serialization}'", 'build.gradle', exactly=1)
    if fork and '-Xes-long-as-bigint' not in b:
        b = sub(b, r"(\n(\s*)binaries\.executable\(\)[^\n]*\n)",
                lambda m: m.group(1)
                + m.group(2) + "// Long as a JavaScript BigInt: only Keliver's Zipline fork (QuickJS 2026) runs it\n"
                + m.group(2) + "compilerOptions.freeCompilerArgs.add('-Xes-long-as-bigint')\n",
                'build.gradle', exactly=1)

    # Nothing of the old line is left: no other Zipline, no Keliver library at another version.
    other = 'app.cash.zipline' if zid == 'dev.keliver.zipline' else 'dev.keliver.zipline'
    for fname, text in (('settings.gradle', s), ('build.gradle', b)):
        if re.search(r"['\"]" + re.escape(other) + r"['\":]", text):
            fail(f"{fname} still names {other}")
        for m in re.finditer(r"'dev\.keliver:[a-z0-9-]+:([^']+)'", text):
            if m.group(1) != kv:
                fail(f"{fname} still names Keliver {m.group(1)}")

    open(pj, 'w', encoding='utf-8').write(json.dumps(c, indent=2) + '\n')
    open(ps, 'w', encoding='utf-8').write(s)
    open(pb, 'w', encoding='utf-8').write(b)


def main(argv):
    if len(argv) == 13 and argv[1] == 'move-app':
        move_app(*argv[2:13])
    elif len(argv) == 4 and argv[1] == 'add-repo':
        f, repo = argv[2], argv[3]
        text = open(f, encoding='utf-8').read()
        text = add_repo(text, repo, f)
        open(f, 'w', encoding='utf-8').write(text)
    else:
        fail('usage: local_maven.py move-app <app> <version> <repo> <fork> <zipline-group> <zipline-plugin-id> '
             '<zipline> <kotlin> <compose> <coroutines> <serialization> | add-repo <settings.gradle> <repo>')
    print('ok')


if __name__ == '__main__':
    main(sys.argv)
