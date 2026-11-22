# Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
#
# NF2 step 4: the publish job's two checks beside check-release.py (review 2 of PR #289). Run with
# python3 -I:
#
#   publish-checks.py natives <tested repo> <release repo> <group> <version>
#     Every native library in the release (the JNI libraries in zipline-jvm's jar, the AAR's
#     libquickjs.so files, the QuickJS bitcode in the klibs) is byte for byte (sha256) the one in
#     the repository the fork job built, checked and ran the tests with: the same entries, the same
#     digests, and at least the six JNI libraries, the four Android ABIs and one klib's bitcode. Whole
#     archives are not compared (their timestamps differ from build to build). Fails otherwise, so a
#     rebuilt engine that no runner loaded never reaches Maven Central.
#
#   publish-checks.py sha1 <release repo> <group> <version> <manifest out>
#     Writes "<sha1>  <path>" for every file of the release but its checksum files (signatures
#     included), checks each against the .sha1 beside it, and prints the fixed spot-check list (the
#     fork's README, "Publishing the fork") as a Markdown table, for the comparison with the Central
#     Portal before anyone presses Publish.
import hashlib
import os
import re
import sys
import zipfile

NATIVE = re.compile(r"(^jni/.*\.(so|dylib|dll|lib)$)|(native/.*quickjs.*\.bc$)")
CHECKSUMS = (".md5", ".sha1", ".sha256", ".sha512")
# What a person compares with the Portal first: the JVM jar, the Android engine, one klib, the
# Gradle plugin, and the multiplatform module's Gradle metadata (with their signatures).
SPOT_CHECK = ["zipline-jvm/{v}/zipline-jvm-{v}.jar",
              "zipline-android-ndk/{v}/zipline-android-ndk-{v}.aar",
              "zipline-iosarm64/{v}/zipline-iosarm64-{v}.klib",
              "zipline-gradle-plugin/{v}/zipline-gradle-plugin-{v}.jar",
              "zipline/{v}/zipline-{v}.module"]


def natives(repo, group, version):
    """{"<artifact>/<file>!<entry>": sha256} for every native entry of the release's main archives."""
    gdir = os.path.join(repo, *group.split("."))
    found = {}
    if not os.path.isdir(gdir):
        raise SystemExit("publish-checks: nothing under %s in %s" % (group, repo))
    for a in sorted(os.listdir(gdir)):
        d = os.path.join(gdir, a, version)
        for ext in ("jar", "aar", "klib"):
            p = os.path.join(d, "%s-%s.%s" % (a, version, ext))
            if not os.path.isfile(p):
                continue
            with zipfile.ZipFile(p) as z:
                for n in z.namelist():
                    if NATIVE.search(n):
                        found["%s!%s" % (os.path.basename(p), n)] = hashlib.sha256(z.read(n)).hexdigest()
    return found


def cmd_natives(tested_repo, repo, group, version):
    tested, mine = natives(tested_repo, group, version), natives(repo, group, version)
    errors = []
    for k in sorted(set(tested) | set(mine)):
        if k not in mine:
            errors.append("the release lacks %s (in the tested build)" % k)
        elif k not in tested:
            errors.append("the release has %s, not in the tested build" % k)
        elif tested[k] != mine[k]:
            errors.append("%s differs: sha256 %s, the tested build's %s" % (k, mine[k], tested[k]))
    jvm = [k for k in mine if k.startswith("zipline-jvm-") and k.endswith((".so", ".dylib", ".dll"))]
    ndk = [k for k in mine if k.startswith("zipline-android-ndk-") and k.endswith("/libquickjs.so")]
    bitcode = [k for k in mine if k.endswith(".bc")]
    for what, got, need in (("zipline-jvm JNI libraries", jvm, 6), ("zipline-android-ndk libquickjs.so", ndk, 4),
                            ("klib QuickJS bitcode files", bitcode, 1)):
        if len(got) < need:
            errors.append("%d %s, expected at least %d" % (len(got), what, need))
    for e in errors:
        print("publish-checks: FAIL " + e)
    if errors:
        raise SystemExit("publish-checks: %d native difference(s) from the tested build; nothing was uploaded" % len(errors))
    print("publish-checks: %d native entries identical to the tested build (sha256): %d JNI libraries in zipline-jvm, "
          "%d libquickjs.so in zipline-android-ndk, %d bitcode files in %d klibs"
          % (len(mine), len(jvm), len(ndk), len(bitcode), len({k.split("!")[0] for k in bitcode})))


def sha1_of(p):
    h = hashlib.sha1()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cmd_sha1(repo, group, version, out):
    gdir = os.path.join(repo, *group.split("."))
    rows, errors = [], []
    for root, _, names in os.walk(gdir):
        for n in sorted(names):
            if n.endswith(CHECKSUMS):
                continue
            p = os.path.join(root, n)
            rel = os.path.relpath(p, repo).replace(os.sep, "/")
            digest = sha1_of(p)
            side = p + ".sha1"
            if not os.path.isfile(side):
                errors.append("%s has no .sha1" % rel)
            elif open(side, encoding="utf-8").read().split()[0] != digest:
                errors.append("%s: its .sha1 is not the file's sha1 %s" % (rel, digest))
            rows.append((rel, digest))
    rows.sort()
    with open(out, "w", encoding="utf-8") as f:
        for rel, digest in rows:
            f.write("%s  %s\n" % (digest, rel))
    by_path = dict(rows)
    prefix = "/".join(group.split(".")) + "/"
    print("| File | sha1 |")
    print("|---|---|")
    for s in SPOT_CHECK:
        for rel in (prefix + s.format(v=version), prefix + s.format(v=version) + ".asc"):
            if rel in by_path:
                print("| `%s` | `%s` |" % (rel[len(prefix):], by_path[rel]))
            elif not rel.endswith(".asc"):
                errors.append("spot-check file %s is not in the release" % rel)
    print("")
    print("%d files in the manifest (%s)." % (len(rows), os.path.basename(out)))
    for e in errors:
        print("publish-checks: FAIL " + e, file=sys.stderr)
    if errors:
        raise SystemExit("publish-checks: %d sha1 manifest failure(s)" % len(errors))


if __name__ == "__main__":
    if len(sys.argv) == 6 and sys.argv[1] == "natives":
        cmd_natives(*sys.argv[2:6])
    elif len(sys.argv) == 6 and sys.argv[1] == "sha1":
        cmd_sha1(*sys.argv[2:6])
    else:
        raise SystemExit("usage: publish-checks.py natives <tested repo> <release repo> <group> <version>\n"
                         "       publish-checks.py sha1 <release repo> <group> <version> <manifest out>")
