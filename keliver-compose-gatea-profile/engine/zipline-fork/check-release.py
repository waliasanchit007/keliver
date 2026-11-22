# Copyright 2026 The Keliver Authors. Licensed under the Apache License, Version 2.0.
#
# NF2 step 4: checks what `apply.sh release` published to a Maven repository directory before
# anything may go to Maven Central, and lists it with sizes. Run with python3 -I:
#
#   check-release.py <repo> <group> <version> <upstream version> \
#                    <upstream artifact list> <upstream zipline-jvm jar> <upstream zipline-android-ndk aar>
#
# apply.sh fetches the last three from Maven Central with curl (so the machine's trust store
# applies): the names upstream publishes at <upstream version>, one per line, and its zipline-jvm
# jar and zipline-android-ndk AAR, each checked against its .sha1.
#
# An engine library is told apart by the atom "BigInt64Array": QuickJS 2026 always has BigInt, and
# Zipline built QuickJS 2021 without CONFIG_BIGNUM, which is the only way 2021 has it. Upstream's own
# libraries are the control: the check fails if one of them has the atom, as it then proves nothing.
# (QuickJS's version string is no use here: only JS_DumpMemoryUsage prints it, and a build with
# hidden visibility, such as Android's, drops that function.) Fails unless:
#   - every file is under <group>, at <version>, and nothing names app.cash.zipline as a Maven group
#     (POM dependencies, Gradle module metadata);
#   - the artifacts are upstream's names, all of them, the Gradle plugin's marker being
#     `<group>.gradle.plugin` for upstream's `app.cash.zipline.gradle.plugin`;
#   - every POM names Apache-2.0 (Zipline) and MIT (QuickJS), Keliver's developer and SCM URL;
#   - zipline-jvm carries the same JNI libraries as upstream's jar, each the new engine;
#   - zipline-android-ndk's AAR has libquickjs.so for its four ABIs, each the new engine;
#   - each Kotlin/Native klib of `zipline` carries the engine's bitcode (cklib);
#   - the Gradle plugin's jar declares both plugin ids and names no app.cash.zipline coordinate.
import os
import re
import sys
import zipfile

repo, group, version, upstream_version, upstream_list, upstream_jar, upstream_aar = sys.argv[1:8]
UPSTREAM_GROUP = "app.cash.zipline"
MARKER = group + ".gradle.plugin"
UPSTREAM_MARKER = UPSTREAM_GROUP + ".gradle.plugin"
ANDROID_ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
NEW_ENGINE_ATOM = b"BigInt64Array"
errors = []


def fail(msg):
    errors.append(msg)
    print("check-release: FAIL " + msg)


def human(n):
    if n < 1024:
        return "%d B" % n
    if n < 1024 * 1024:
        return "%.1f KB" % (n / 1024.0)
    return "%.1f MB" % (n / 1024.0 / 1024.0)


def check_engines(what, mine, theirs, names):
    """Each named library is the new engine in `mine` and, as the control, not in `theirs`."""
    for n in names:
        if n not in mine.namelist():
            fail("%s has no %s" % (what, n))
            continue
        data = mine.read(n)
        if NEW_ENGINE_ATOM not in data:
            fail("%s %s is not QuickJS 2026 (no %s)" % (what, n, NEW_ENGINE_ATOM.decode()))
            continue
        if n not in theirs.namelist():
            fail("%s: upstream's has no %s to compare" % (what, n))
            continue
        if NEW_ENGINE_ATOM in theirs.read(n):
            fail("%s: upstream's %s has %s too; the engine check proves nothing" % (what, n, NEW_ENGINE_ATOM.decode()))
            continue
        print("check-release: %s %s %s, QuickJS 2026 (upstream's: 2021)" % (what, n, human(len(data))))


def artifact_file(a, ext, classifier=""):
    return os.path.join(gdir, a, version, "%s-%s%s.%s" % (a, version, classifier, ext))


# 1. Everything under the group, at the version.
gdir = os.path.join(repo, *group.split("."))
files = []
for root, _, names in os.walk(repo):
    for n in names:
        p = os.path.join(root, n)
        if not p.startswith(gdir + os.sep):
            fail("%s is outside %s" % (os.path.relpath(p, repo), group))
            continue
        files.append(p)
artifacts = sorted(d for d in os.listdir(gdir) if os.path.isdir(os.path.join(gdir, d))) if os.path.isdir(gdir) else []
if not artifacts:
    raise SystemExit("check-release: nothing published under %s in %s" % (group, repo))
for a in artifacts:
    versions = sorted(v for v in os.listdir(os.path.join(gdir, a)) if os.path.isdir(os.path.join(gdir, a, v)))
    if versions != [version]:
        fail("%s: versions %s, expected only %s" % (a, versions, version))

# 2. Upstream's names, all of them.
upstream = sorted(MARKER if a == UPSTREAM_MARKER else a
                  for a in open(upstream_list, encoding="utf-8").read().split())
if not upstream:
    raise SystemExit("check-release: %s lists no upstream artifacts" % upstream_list)
missing = sorted(set(upstream) - set(artifacts))
extra = sorted(set(artifacts) - set(upstream))
if missing:
    fail("upstream %s publishes %d artifacts this release lacks: %s" % (upstream_version, len(missing), ", ".join(missing)))
if extra:
    fail("not upstream %s artifact names: %s" % (upstream_version, ", ".join(extra)))
print("check-release: %d artifacts; upstream %s has %d, the plugin marker counted as %s"
      % (len(artifacts), upstream_version, len(upstream), MARKER))

# 3. POMs and module metadata. Maven Central wants the licences, developers and SCM in every POM,
# the plugin marker's too.
for p in files:
    name = os.path.basename(p)
    if p.endswith(".pom"):
        text = open(p, encoding="utf-8").read()
        if "<groupId>%s</groupId>" % UPSTREAM_GROUP in text:
            fail("%s names %s as a group" % (name, UPSTREAM_GROUP))
        if "<groupId>%s</groupId>" % group not in text:
            fail("%s: groupId is not %s" % (name, group))
        for need, what in [("licenses/LICENSE-2.0", "Zipline's Apache-2.0 licence"),
                           ("<name>MIT</name>", "QuickJS's MIT licence"),
                           ("<id>waliasanchit007</id>", "Keliver's developer"),
                           ("<url>https://github.com/waliasanchit007/keliver</url>", "the fork's SCM URL")]:
            if need not in text:
                fail("%s lacks %s" % (name, what))
    elif p.endswith(".module"):
        text = open(p, encoding="utf-8").read()
        if re.search(r'"group":\s*"%s"' % re.escape(UPSTREAM_GROUP), text):
            fail("%s names %s as a group" % (name, UPSTREAM_GROUP))

# 4. zipline-jvm's JNI libraries: the same entries as upstream's jar, built from the new engine.
jar = artifact_file("zipline-jvm", "jar")
if os.path.exists(jar):
    with zipfile.ZipFile(jar) as z, zipfile.ZipFile(upstream_jar) as u:
        mine = sorted(n for n in z.namelist() if n.startswith("jni/") and not n.endswith("/"))
        theirs = sorted(n for n in u.namelist() if n.startswith("jni/") and not n.endswith("/"))
        if mine != theirs:
            fail("zipline-jvm JNI libraries %s, upstream's %s" % (mine, theirs))
        # The libraries; jni/lib/quickjs.lib is Windows' import library (names only), as upstream's.
        check_engines("zipline-jvm", z, u, [n for n in mine if n.endswith((".so", ".dylib", ".dll"))])
else:
    fail("no zipline-jvm jar")

# 5. The Android engine.
aar = artifact_file("zipline-android-ndk", "aar")
if os.path.exists(aar):
    with zipfile.ZipFile(aar) as z, zipfile.ZipFile(upstream_aar) as u:
        check_engines("zipline-android-ndk", z, u, ["jni/%s/libquickjs.so" % abi for abi in ANDROID_ABIS])
else:
    fail("no zipline-android-ndk AAR")

# 6. The native engines: every klib of the `zipline` module carries cklib's QuickJS bitcode.
klibs = 0
for a in artifacts:
    if not re.fullmatch(r"zipline-(ios|macos|tvos|linux)[a-z0-9]+", a):
        continue
    k = artifact_file(a, "klib")
    if not os.path.exists(k):
        fail("%s has no klib" % a)
        continue
    with zipfile.ZipFile(k) as z:
        if not [n for n in z.namelist() if re.search(r"native/.*quickjs.*\.bc$", n)]:
            fail("%s's klib has no QuickJS bitcode" % a)
    klibs += 1
if klibs == 0:
    fail("no native klib of zipline")
print("check-release: %d native klibs of zipline checked for the engine's bitcode" % klibs)

# 7. The Gradle plugin: both ids, and its coordinates in the fork's group.
pjar = artifact_file("zipline-gradle-plugin", "jar")
if os.path.exists(pjar):
    with zipfile.ZipFile(pjar) as z:
        names = z.namelist()
        for pid in (group, UPSTREAM_GROUP):
            if "META-INF/gradle-plugins/%s.properties" % pid not in names:
                fail("zipline-gradle-plugin does not declare the plugin id %s" % pid)
        classes = b"".join(z.read(n) for n in names if n.endswith(".class"))
        if (UPSTREAM_GROUP + ":").encode() in classes:
            fail("zipline-gradle-plugin names an %s coordinate" % UPSTREAM_GROUP)
        if group.encode() not in classes:
            fail("zipline-gradle-plugin never names %s" % group)
else:
    fail("no zipline-gradle-plugin jar")

# 8. The listing.
total = 0
print("check-release: published as %s:<artifact>:%s" % (group, version))
for a in artifacts:
    d = os.path.join(gdir, a, version)
    parts = []
    for n in sorted(os.listdir(d)):
        if n.endswith((".md5", ".sha1", ".sha256", ".sha512", ".asc")):
            continue
        size = os.path.getsize(os.path.join(d, n))
        total += size
        parts.append("%s %s" % (n[len("%s-%s" % (a, version)):] or n, human(size)))
    print("  %-34s %s" % (a, ", ".join(parts)))
print("check-release: %d files, %s in all (without checksums and signatures)" % (len(files), human(total)))
if errors:
    raise SystemExit("check-release: %d failure(s)" % len(errors))
print("check-release: OK")
