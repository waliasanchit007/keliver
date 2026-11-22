# Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
#
# NF2 step 4: checks what `apply.sh release` published to a Maven repository directory before
# anything may go to Maven Central, and lists it with sizes. Run with python3 -I:
#
#   check-release.py <repo> <group> <version> <upstream version> \
#                    <upstream artifact list> <upstream zipline-jvm jar> <upstream zipline-android-ndk aar> \
#                    <upstream zipline-gradle-plugin jar> <source ref> <signed|unsigned>
#
# apply.sh fetches the upstream files from Maven Central with curl (so the machine's trust store
# applies): the names upstream publishes at <upstream version>, one per line, and its zipline-jvm
# jar, zipline-android-ndk AAR and zipline-gradle-plugin jar, each checked against its .sha1.
#
# An engine library is told apart by the atom "BigInt64Array": QuickJS 2026 always has BigInt, and
# Zipline built QuickJS 2021 without CONFIG_BIGNUM, which is the only way 2021 has it. Upstream's own
# libraries are the control: the check fails if one of them has the atom, as it then proves nothing.
# (QuickJS's version string is no use here: only JS_DumpMemoryUsage prints it, and a build with
# hidden visibility, such as Android's, drops that function.) Fails unless:
#   - every file is under <group>, at <version>, and nothing names app.cash.zipline as a Maven group
#     (POM dependencies, Gradle module metadata) except as a capability;
#   - every variant with files in the Gradle module metadata declares its own capability and
#     upstream's, app.cash.zipline:<name> (so Gradle refuses a classpath with both groups);
#   - the artifacts are upstream's names, all of them, the Gradle plugin's marker being
#     `<group>.gradle.plugin` for upstream's `app.cash.zipline.gradle.plugin`;
#   - every POM has a name and a description, names Apache-2.0 (Zipline) and MIT (QuickJS),
#     Keliver's developer and SCM URL, and points at <source ref> (url and scm tag);
#   - Maven Central's file rules: an artifact whose POM packaging is not `pom` has its main file,
#     a -sources.jar and a -javadoc.jar; every file has .md5 and .sha1; with `signed`, every file
#     has its .asc signature;
#   - every jar, AAR and klib carries META-INF/<module>/LICENSE-QuickJS.txt (QuickJS's MIT notice;
#     <module> is the Gradle project, the artifact name without its platform suffix) and the fork's
#     META-INF/NOTICE, one copy of each;
#   - zipline-jvm carries the same JNI libraries as upstream's jar, each the new engine;
#   - zipline-android-ndk's AAR has libquickjs.so for its four ABIs, each the new engine;
#   - each Kotlin/Native klib of `zipline` carries the engine's bitcode (cklib);
#   - every main jar's classes (zipline-jvm, the Gradle and Kotlin compiler plugins, the loader, the
#     CLI, ...) are no newer a class-file version than upstream's jar of the same name (the release
#     is built on JDK 21, .java-version, and Gradle builds may run on 17: a module without a pinned
#     bytecode target would otherwise need the newer JDK); apply.sh downloads upstream's jars;
#   - the Gradle plugin's jar declares both plugin ids, names no app.cash.zipline coordinate, and its
#     BuildConfig's constants name the fork: KOTLIN_PLUGIN_GROUP and ziplineGroup are <group>,
#     KOTLIN_PLUGIN_VERSION and ziplineVersion are <version> (the compiler plugin and the CLI that
#     every build applying the plugin resolves).
import json
import os
import re
import struct
import sys
import zipfile

(repo, group, version, upstream_version, upstream_list, upstream_jar, upstream_aar,
 upstream_plugin_jar, source_ref, signed) = sys.argv[1:11]
if signed not in ("signed", "unsigned"):
    raise SystemExit("check-release: the last argument is signed or unsigned, not %r" % signed)
UPSTREAM_GROUP = "app.cash.zipline"
MARKER = group + ".gradle.plugin"
UPSTREAM_MARKER = UPSTREAM_GROUP + ".gradle.plugin"
ANDROID_ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
NEW_ENGINE_ATOM = b"BigInt64Array"
LICENSE_ENTRY = re.compile(r"META-INF/([^/]+)/LICENSE-QuickJS\.txt$")
LICENSE_NEEDLE = b"Permission is hereby granted"
NOTICE_ENTRY = "META-INF/NOTICE"
NOTICE_NEEDLE = b"Keliver's fork of Zipline"
FORK_DIR = "https://github.com/waliasanchit007/keliver/tree/%s/keliver-compose-gatea-profile/engine/zipline-fork" % source_ref
CHECKSUMS = (".md5", ".sha1", ".sha256", ".sha512")
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


def class_constants(data):
    """The String constants (ConstantValue) of a class file's static final fields, by field name."""
    pos = 8  # magic, minor, major
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    pool = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            (n,) = struct.unpack_from(">H", data, pos)
            pool[i] = ("utf8", data[pos + 2:pos + 2 + n].decode("utf-8", "replace"))
            pos += 2 + n
        elif tag == 8:  # String
            pool[i] = ("string", struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (7, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):  # Long, Double: two slots
            pos += 8
            i += 1
        else:
            raise ValueError("constant pool tag %d" % tag)
        i += 1
    pos += 6  # access flags, this class, super class
    (interfaces,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * interfaces
    (fields,) = struct.unpack_from(">H", data, pos)
    pos += 2
    result = {}
    for _ in range(fields):
        _, name_index, _, attributes = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        for _ in range(attributes):
            attr_name, length = struct.unpack_from(">HI", data, pos)
            if pool[attr_name] == ("utf8", "ConstantValue"):
                (value_index,) = struct.unpack_from(">H", data, pos + 6)
                value = pool[value_index]
                if value and value[0] == "string":
                    result[pool[name_index][1]] = pool[value[1]][1]
            pos += 6 + length
    return result


def upstream_outside_capabilities(node, path=""):
    """Paths in Gradle module metadata where app.cash.zipline is a group, other than a capability."""
    found = []
    if isinstance(node, dict):
        if node.get("group") == UPSTREAM_GROUP and not re.search(r"\.capabilities\[\d+\]$", path):
            found.append(path or "/")
        for k, v in node.items():
            found += upstream_outside_capabilities(v, "%s.%s" % (path, k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            found += upstream_outside_capabilities(v, "%s[%d]" % (path, i))
    return found


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

# 3. POMs and module metadata. Maven Central wants a name, a description, the licences, developers
# and SCM in every POM, the plugin marker's too.
modules = variants_checked = 0
for p in files:
    name = os.path.basename(p)
    if p.endswith(".pom"):
        text = open(p, encoding="utf-8").read()
        if "<groupId>%s</groupId>" % UPSTREAM_GROUP in text:
            fail("%s names %s as a group" % (name, UPSTREAM_GROUP))
        if "<groupId>%s</groupId>" % group not in text:
            fail("%s: groupId is not %s" % (name, group))
        for need, what in [(re.compile(r"<project\b[^>]*>.*?\n  <name>[^<]+</name>", re.S), "a name"),
                           (re.compile(r"\n  <description>[^<]+</description>"), "a description"),
                           ("licenses/LICENSE-2.0", "Zipline's Apache-2.0 licence"),
                           ("<name>MIT</name>", "QuickJS's MIT licence"),
                           ("<id>waliasanchit007</id>", "Keliver's developer"),
                           ("<url>https://github.com/waliasanchit007/keliver</url>", "the fork's SCM URL"),
                           ("\n  <url>%s</url>" % FORK_DIR, "the url at %s" % source_ref),
                           ("<tag>%s</tag>" % source_ref, "the scm tag %s" % source_ref)]:
            if not (need.search(text) if hasattr(need, "search") else need in text):
                fail("%s lacks %s" % (name, what))
    elif p.endswith(".module"):
        modules += 1
        metadata = json.load(open(p, encoding="utf-8"))
        for where in upstream_outside_capabilities(metadata):
            fail("%s names %s as a group at %s (only a capability may)" % (name, UPSTREAM_GROUP, where))
        # The module's own coordinates, from its directory (a platform module's `component` is the
        # multiplatform root module it belongs to).
        own = (group, os.path.basename(os.path.dirname(os.path.dirname(p))))
        for v in metadata.get("variants", []):
            if "available-at" in v:
                continue  # Points at another module, which declares its own.
            caps = {(c.get("group"), c.get("name")) for c in v.get("capabilities", [])}
            for need in (own, (UPSTREAM_GROUP, own[1])):
                if need not in caps:
                    fail("%s: variant %s lacks the capability %s:%s" % (name, v.get("name"), need[0], need[1]))
            variants_checked += 1
print("check-release: %d Gradle module files, %d variants with files, each with its own and %s's capability"
      % (modules, variants_checked, UPSTREAM_GROUP))

# 3b. Maven Central's file rules (it refuses a deployment that breaks them, so a miss here costs a
# rerun, not a release): main file, sources and javadoc jars, checksums, and signatures if signed.
for a in artifacts:
    d = os.path.join(gdir, a, version)
    names = set(os.listdir(d))
    pom = "%s-%s.pom" % (a, version)
    if pom not in names:
        fail("%s has no POM" % a)
        continue
    m = re.search(r"<packaging>([^<]+)</packaging>", open(os.path.join(d, pom), encoding="utf-8").read())
    packaging = m.group(1) if m else "jar"
    if packaging != "pom":
        for f in ["%s-%s.%s" % (a, version, packaging), "%s-%s-sources.jar" % (a, version),
                  "%s-%s-javadoc.jar" % (a, version)]:
            if f not in names:
                fail("%s (packaging %s) has no %s" % (a, packaging, f))
    for n in names:
        if n.endswith(CHECKSUMS) or n.endswith(".asc"):
            continue
        for ext in (".md5", ".sha1") + ((".asc",) if signed == "signed" else ()):
            if n + ext not in names:
                fail("%s has no %s" % (n, ext))

# 3c. The licence notices in every archive.
# The Gradle project each artifact belongs to, exactly (review 3 of PR #289, L-d): a Kotlin
# Multiplatform module's platform artifacts (zipline-jvm, zipline-iosarm64, ...) are the
# `available-at` modules of its root artifact's Gradle module metadata, and the root artifact is the
# project's name; every other artifact is its own project's name.
module_of = {}
for a in artifacts:
    mod = artifact_file(a, "module")
    if not os.path.exists(mod):
        continue
    for v in json.load(open(mod, encoding="utf-8")).get("variants", []):
        at = v.get("available-at")
        if at and at.get("module"):
            if module_of.get(at["module"], a) != a:
                fail("%s is a platform artifact of both %s and %s" % (at["module"], module_of[at["module"]], a))
            module_of[at["module"]] = a
archives = 0
for p in files:
    if not p.endswith((".jar", ".aar", ".klib")):
        continue
    archives += 1
    name = os.path.basename(p)
    artifact = os.path.basename(os.path.dirname(os.path.dirname(p)))
    with zipfile.ZipFile(p) as z:
        infos = z.infolist()
        notices = [i for i in infos if i.filename == NOTICE_ENTRY]
        licences = [i for i in infos if LICENSE_ENTRY.match(i.filename)]
        for what, copies, needle in (("META-INF/NOTICE", notices, NOTICE_NEEDLE),
                                     ("META-INF/<module>/LICENSE-QuickJS.txt", licences, LICENSE_NEEDLE)):
            if not copies:
                fail("%s has no %s" % (name, what))
            elif len(copies) > 1:
                fail("%s has %d copies of %s: %s" % (name, len(copies), what, ", ".join(i.filename for i in copies)))
            elif needle not in z.open(copies[0]).read():
                fail("%s: %s is not the fork's" % (name, copies[0].filename))
        # Under its own module's name, exactly, so two of the fork's archives never carry the same
        # path; the old shared path is gone.
        for i in licences:
            module = LICENSE_ENTRY.match(i.filename).group(1)
            if module != module_of.get(artifact, artifact):
                fail("%s: %s is not under its module's name %s (artifact %s)"
                     % (name, i.filename, module_of.get(artifact, artifact), artifact))
        if "META-INF/LICENSE-QuickJS.txt" in z.namelist():
            fail("%s has META-INF/LICENSE-QuickJS.txt, a path every module would share" % name)
print("check-release: %d jars, AARs and klibs, each with META-INF/NOTICE and META-INF/<module>/LICENSE-QuickJS.txt, "
      "<module> exactly the artifact's Gradle project (%d platform artifacts mapped from the root modules' metadata)"
      % (archives, len(module_of)))


def class_major(z):
    """The highest class-file major version in a jar (Java 8 is 52, 17 is 61, 21 is 65), ignoring
    multi-release entries under META-INF/versions/."""
    majors = [struct.unpack_from(">H", z.read(n), 6)[0] for n in z.namelist()
              if n.endswith(".class") and not n.startswith("META-INF/")]
    return max(majors) if majors else None


# 3d. Class-file versions no newer than upstream's (review 2 of PR #289, L3), for every main jar of
# the release with a counterpart upstream (review 3, L-b: the Kotlin compiler plugin, the loader,
# the CLI and the bytecode tools load in consumers' builds too). apply.sh downloads upstream's
# <artifact>-<upstream version>.jar for each into the directory with the other upstream files.
upstream_dir = os.path.dirname(upstream_jar)
jars_compared = 0
for a in artifacts:
    mine_path = artifact_file(a, "jar")
    if not os.path.exists(mine_path) or a not in upstream:
        continue
    theirs_path = os.path.join(upstream_dir, "%s-%s.jar" % (a, upstream_version))
    if not os.path.exists(theirs_path):
        fail("%s: upstream's %s was not downloaded to compare class-file versions" % (a, os.path.basename(theirs_path)))
        continue
    jars_compared += 1
    with zipfile.ZipFile(mine_path) as z, zipfile.ZipFile(theirs_path) as u:
        mine_major, theirs_major = class_major(z), class_major(u)
    if mine_major is None and theirs_major is None:
        print("check-release: %s has no classes, nor has upstream's (a metadata jar)" % a)
    elif mine_major is None or theirs_major is None:
        fail("%s: no classes to compare (fork's %s, upstream's %s)" % (a, mine_major, theirs_major))
    elif mine_major > theirs_major:
        fail("%s: class-file major version %d (Java %d), newer than upstream's %d (Java %d)"
             % (a, mine_major, mine_major - 44, theirs_major, theirs_major - 44))
    else:
        print("check-release: %s classes at class-file major %d (Java %d); upstream's %d (Java %d)"
              % (a, mine_major, mine_major - 44, theirs_major, theirs_major - 44))
for a in ("zipline-jvm", "zipline-gradle-plugin", "zipline-kotlin-plugin"):
    if os.path.exists(artifact_file(a, "jar")) and a not in upstream:
        fail("%s: no upstream counterpart to compare class-file versions with" % a)
print("check-release: class-file versions of %d jars compared with upstream's" % jars_compared)

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
        # The constants the plugin resolves the compiler plugin and the CLI with.
        build_config = "app/cash/zipline/gradle/BuildConfig.class"
        if build_config not in names:
            fail("zipline-gradle-plugin has no %s" % build_config)
        else:
            constants = class_constants(z.read(build_config))
            for field, want in [("KOTLIN_PLUGIN_GROUP", group), ("ziplineGroup", group),
                                ("KOTLIN_PLUGIN_NAME", "zipline-kotlin-plugin"),
                                ("KOTLIN_PLUGIN_VERSION", version), ("ziplineVersion", version)]:
                if constants.get(field) != want:
                    fail("zipline-gradle-plugin BuildConfig.%s is %r, not %r" % (field, constants.get(field), want))
            print("check-release: zipline-gradle-plugin BuildConfig: %s" % ", ".join(
                "%s=%s" % (k, constants.get(k)) for k in ("KOTLIN_PLUGIN_GROUP", "KOTLIN_PLUGIN_NAME",
                                                          "KOTLIN_PLUGIN_VERSION", "ziplineGroup", "ziplineVersion")))
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
