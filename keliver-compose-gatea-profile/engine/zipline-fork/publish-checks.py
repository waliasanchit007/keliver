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
#   publish-checks.py sha1 <bundle zip> <checked repo> <group> <version> <manifest out>
#     The manifest of what Maven Central received (review 3 of PR #289, M1): <bundle zip> is the
#     bundle vanniktech's plugin wrote and uploaded (apply.sh release --central keeps a copy). Writes
#     "<sha1>  <path>" for every file in it but the checksum files (signatures included), and fails
#     unless each matches the .sha1 beside it in the bundle (a signature has none: Gradle writes no
#     checksums for .asc files), and the bundle and the checked directory
#     (`release` wrote and check-release.py checked it) hold the same files byte for byte. The
#     directory's maven-metadata.xml files are not compared: the plugin leaves them out of the bundle
#     (Central writes its own), and a second Gradle invocation rewrites their timestamp. Prints the
#     fixed spot-check list (the fork's README, "Publishing the fork") as a Markdown table, for the
#     comparison with the Central Portal before anyone presses Publish.
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


def is_metadata(rel):
    # vanniktech 0.36.0 (MavenCentralBuildService) leaves out every file whose name contains this.
    return "maven-metadata" in rel.rsplit("/", 1)[-1]


def cmd_sha1(bundle, repo, group, version, out):
    prefix = "/".join(group.split(".")) + "/"
    errors = []
    # What Central received: the bundle's entries, by path.
    with zipfile.ZipFile(bundle) as z:
        sent = {}
        for i in z.infolist():
            if i.is_dir():
                continue
            name = i.filename.replace("\\", "/")
            if name in sent:
                errors.append("the bundle has %s twice" % name)
            sent[name] = z.read(i)
    # What was checked: the directory, without the metadata the bundle leaves out.
    checked = {}
    for root, _, names in os.walk(repo):
        for n in names:
            p = os.path.join(root, n)
            rel = os.path.relpath(p, repo).replace(os.sep, "/")
            if not is_metadata(rel):
                checked[rel] = p
    for rel in sorted(set(sent) - set(checked)):
        errors.append("the bundle has %s, which the checked directory does not" % rel)
    for rel in sorted(set(checked) - set(sent)):
        errors.append("the checked directory has %s, which the bundle does not" % rel)
    rows = []
    for rel in sorted(sent):
        if not rel.startswith(prefix):
            errors.append("the bundle has %s, outside %s" % (rel, group))
        if is_metadata(rel):
            errors.append("the bundle has %s (expected left out)" % rel)
        if rel in checked and sha1_of(checked[rel]) != hashlib.sha1(sent[rel]).hexdigest():
            errors.append("%s differs from the checked directory's (sha1 %s, the directory's %s)"
                          % (rel, hashlib.sha1(sent[rel]).hexdigest(), sha1_of(checked[rel])))
        if rel.endswith(CHECKSUMS):
            continue
        digest = hashlib.sha1(sent[rel]).hexdigest()
        side = sent.get(rel + ".sha1")
        if rel.endswith(".asc"):
            pass  # Gradle writes no checksum files for signatures (measured: none of 311 has one).
        elif side is None:
            errors.append("%s has no .sha1 in the bundle" % rel)
        elif side.decode("utf-8", "replace").split()[:1] != [digest]:
            errors.append("%s: its .sha1 in the bundle is not the file's sha1 %s" % (rel, digest))
        rows.append((rel, digest))
    with open(out, "w", encoding="utf-8") as f:
        for rel, digest in rows:
            f.write("%s  %s\n" % (digest, rel))
    by_path = dict(rows)
    print("| File | sha1 |")
    print("|---|---|")
    for s in SPOT_CHECK:
        for rel in (prefix + s.format(v=version), prefix + s.format(v=version) + ".asc"):
            if rel in by_path:
                print("| `%s` | `%s` |" % (rel[len(prefix):], by_path[rel]))
            elif not rel.endswith(".asc"):
                errors.append("spot-check file %s is not in the release" % rel)
    print("")
    asc = sum(1 for rel, _ in rows if rel.endswith(".asc"))
    print("%d files in the manifest (%s): %d files and %d .asc signatures; with their "
          "checksum files, %d entries in the uploaded bundle." % (len(rows), os.path.basename(out), len(rows) - asc, asc, len(sent)))
    if not errors:
        print("Each is byte for byte (sha1) the file the checks passed in the signed directory; "
              "maven-metadata.xml is not uploaded (Central writes its own).")
    for e in errors:
        print("publish-checks: FAIL " + e, file=sys.stderr)
    if errors:
        raise SystemExit("publish-checks: %d failure(s): what was uploaded is not what was checked; Drop the deployment"
                         % len(errors))


if __name__ == "__main__":
    if len(sys.argv) == 6 and sys.argv[1] == "natives":
        cmd_natives(*sys.argv[2:6])
    elif len(sys.argv) == 7 and sys.argv[1] == "sha1":
        cmd_sha1(*sys.argv[2:7])
    else:
        raise SystemExit("usage: publish-checks.py natives <tested repo> <release repo> <group> <version>\n"
                         "       publish-checks.py sha1 <bundle zip> <checked repo> <group> <version> <manifest out>")
