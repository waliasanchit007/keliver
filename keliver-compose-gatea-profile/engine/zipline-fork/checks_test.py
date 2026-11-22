# Copyright (C) 2026 Keliver contributors. Licensed under the Apache License, Version 2.0.
#
# Unit tests of the publish job's checks (review 4 of PR #289, carried over): publish-checks.py
# (natives, sha1) and check-release.py's licence-path rule (review 3, L-d), on small synthetic
# Maven repositories. No network, no Gradle. Run with:
#
#   python3 -I keliver-compose-gatea-profile/engine/zipline-fork/checks_test.py
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GROUP = "dev.keliver.zipline"
VERSION = "1.28.0-keliver.1"
GPATH = GROUP.replace(".", "/")

_spec = importlib.util.spec_from_file_location("publish_checks", os.path.join(HERE, "publish-checks.py"))
pc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pc)


def write_zip(path, entries):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)


def artifact_path(repo, artifact, ext, version=VERSION):
    return os.path.join(repo, *GROUP.split("."), artifact, version, "%s-%s.%s" % (artifact, version, ext))


# --- natives ---------------------------------------------------------------------------------------

JNI = ["jni/aarch64/libquickjs.dylib", "jni/amd64/libquickjs.dylib", "jni/aarch64/libquickjs.so",
       "jni/amd64/libquickjs.so", "jni/aarch64/quickjs.dll", "jni/amd64/quickjs.dll"]
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]


def native_repo(repo, jni=None, ndk=None, bitcode=b"bc"):
    jni = dict((n, b"engine " + n.encode()) for n in JNI) if jni is None else jni
    ndk = dict(("jni/%s/libquickjs.so" % a, b"so " + a.encode()) for a in ABIS) if ndk is None else ndk
    write_zip(artifact_path(repo, "zipline-jvm", "jar"), dict(jni, **{"app/cash/zipline/Zipline.class": b"x"}))
    write_zip(artifact_path(repo, "zipline-android-ndk", "aar"), dict(ndk, **{"classes.jar": b"x"}))
    write_zip(artifact_path(repo, "zipline-iosarm64", "klib"),
              {"default/targets/ios_arm64/native/quickjs.bc": bitcode, "default/manifest": b"m"})
    return repo


class NativesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tested = os.path.join(self.tmp.name, "tested")
        self.release = os.path.join(self.tmp.name, "release")

    def tearDown(self):
        self.tmp.cleanup()

    def run_natives(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            try:
                pc.cmd_natives(self.tested, self.release, GROUP, VERSION)
            except SystemExit as e:
                return False, out.getvalue() + str(e)
        return True, out.getvalue()

    def test_identical_passes(self):
        native_repo(self.tested)
        native_repo(self.release)
        ok, out = self.run_natives()
        self.assertTrue(ok, out)
        self.assertIn("11 native entries identical", out)
        self.assertIn("6 JNI libraries", out)

    def test_one_engine_differs(self):
        native_repo(self.tested)
        jni = dict((n, b"engine " + n.encode()) for n in JNI)
        jni["jni/amd64/quickjs.dll"] = b"rebuilt"
        native_repo(self.release, jni=jni)
        ok, out = self.run_natives()
        self.assertFalse(ok)
        self.assertIn("zipline-jvm-%s.jar!jni/amd64/quickjs.dll differs" % VERSION, out)
        self.assertIn("1 native difference(s)", out)

    def test_missing_and_extra_entries(self):
        native_repo(self.tested)
        ndk = dict(("jni/%s/libquickjs.so" % a, b"so " + a.encode()) for a in ABIS)
        del ndk["jni/x86/libquickjs.so"]
        ndk["jni/riscv64/libquickjs.so"] = b"new"
        native_repo(self.release, ndk=ndk)
        ok, out = self.run_natives()
        self.assertFalse(ok)
        self.assertIn("the release lacks zipline-android-ndk-%s.aar!jni/x86/libquickjs.so" % VERSION, out)
        self.assertIn("the release has zipline-android-ndk-%s.aar!jni/riscv64/libquickjs.so, not in the tested build" % VERSION, out)

    def test_too_few_libraries_fails_even_when_identical(self):
        jni = dict((n, b"engine " + n.encode()) for n in JNI[:5])
        native_repo(self.tested, jni=jni)
        native_repo(self.release, jni=jni)
        ok, out = self.run_natives()
        self.assertFalse(ok)
        self.assertIn("5 zipline-jvm JNI libraries, expected at least 6", out)

    def test_no_bitcode_fails(self):
        for repo in (self.tested, self.release):
            native_repo(repo)
            write_zip(artifact_path(repo, "zipline-iosarm64", "klib"), {"default/manifest": b"m"})
        ok, out = self.run_natives()
        self.assertFalse(ok)
        self.assertIn("0 klib QuickJS bitcode files, expected at least 1", out)

    def test_nothing_under_the_group(self):
        os.makedirs(self.tested)
        native_repo(self.release)
        ok, out = self.run_natives()
        self.assertFalse(ok)
        self.assertIn("nothing under %s" % GROUP, out)


# --- sha1 ------------------------------------------------------------------------------------------

SPOT = ["zipline-jvm/{v}/zipline-jvm-{v}.jar", "zipline-android-ndk/{v}/zipline-android-ndk-{v}.aar",
        "zipline-iosarm64/{v}/zipline-iosarm64-{v}.klib", "zipline-gradle-plugin/{v}/zipline-gradle-plugin-{v}.jar",
        "zipline/{v}/zipline-{v}.module"]


def signed_repo(repo, extra=()):
    """A checked directory as `release --central` writes it: each file with .md5 and .sha1, its .asc
    (no checksums), and maven-metadata.xml files (with their checksums) the bundle leaves out."""
    rels = [s.format(v=VERSION) for s in SPOT] + ["zipline/%s/zipline-%s.pom" % (VERSION, VERSION)] + list(extra)
    for rel in rels:
        data = ("content of " + rel).encode()
        p = os.path.join(repo, GPATH, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        for name, body in ((p, data), (p + ".md5", hashlib.md5(data).hexdigest().encode()),
                           (p + ".sha1", hashlib.sha1(data).hexdigest().encode()), (p + ".asc", b"sig " + data)):
            with open(name, "wb") as f:
                f.write(body)
    meta = os.path.join(repo, GPATH, "zipline", "maven-metadata.xml")
    with open(meta, "wb") as f:
        f.write(b"<metadata/>")
    with open(meta + ".sha1", "wb") as f:
        f.write(hashlib.sha1(b"<metadata/>").hexdigest().encode())
    return repo


def bundle_of(repo, path, change=None):
    """The bundle vanniktech 0.36.0 writes from it: every file but maven-metadata*, by relative path.
    `change(entries)` edits the entries first."""
    entries = {}
    for root, _, names in os.walk(repo):
        for n in names:
            p = os.path.join(root, n)
            rel = os.path.relpath(p, repo).replace(os.sep, "/")
            if "maven-metadata" not in n:
                with open(p, "rb") as f:
                    entries[rel] = f.read()
    if change:
        change(entries)
    write_zip(path, entries)
    return path


class Sha1Test(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = signed_repo(os.path.join(self.tmp.name, "repo"))
        self.bundle = os.path.join(self.tmp.name, "bundle.zip")
        self.manifest = os.path.join(self.tmp.name, "SHA1SUMS.txt")

    def tearDown(self):
        self.tmp.cleanup()

    def run_sha1(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                pc.cmd_sha1(self.bundle, self.repo, GROUP, VERSION, self.manifest)
            except SystemExit as e:
                return False, out.getvalue() + err.getvalue() + str(e)
        return True, out.getvalue() + err.getvalue()

    def test_same_files_pass_and_the_manifest_lists_files_and_signatures(self):
        bundle_of(self.repo, self.bundle)
        ok, out = self.run_sha1()
        self.assertTrue(ok, out)
        with open(self.manifest, encoding="utf-8") as f:
            lines = f.read().splitlines()
        # Six files and their six signatures; no checksum files, no maven-metadata.
        self.assertEqual(len(lines), 12, lines)
        self.assertFalse([l for l in lines if l.endswith(pc.CHECKSUMS) or "maven-metadata" in l])
        jar = "%s/zipline-jvm/%s/zipline-jvm-%s.jar" % (GPATH, VERSION, VERSION)
        data = ("content of zipline-jvm/%s/zipline-jvm-%s.jar" % (VERSION, VERSION)).encode()
        self.assertIn("%s  %s" % (hashlib.sha1(data).hexdigest(), jar), lines)
        # The spot-check table: each spot-check file and its signature.
        self.assertIn("| `zipline-jvm/%s/zipline-jvm-%s.jar` | `%s` |" % (VERSION, VERSION, hashlib.sha1(data).hexdigest()), out)
        self.assertIn("zipline/%s/zipline-%s.module.asc" % (VERSION, VERSION), out)
        self.assertIn("12 files in the manifest", out)
        self.assertIn("byte for byte", out)

    def assert_fails_with(self, change, *needles):
        bundle_of(self.repo, self.bundle, change)
        ok, out = self.run_sha1()
        self.assertFalse(ok, out)
        self.assertIn("Drop the deployment", out)
        for n in needles:
            self.assertIn(n, out)

    def test_a_file_that_differs_from_the_checked_one(self):
        jar = "%s/zipline-jvm/%s/zipline-jvm-%s.jar" % (GPATH, VERSION, VERSION)

        def change(e):
            e[jar] = b"rebuilt"
            e[jar + ".sha1"] = hashlib.sha1(b"rebuilt").hexdigest().encode()
        self.assert_fails_with(change, "%s differs from the checked directory's" % jar)

    def test_a_signature_that_differs(self):
        asc = "%s/zipline/%s/zipline-%s.module.asc" % (GPATH, VERSION, VERSION)
        self.assert_fails_with(lambda e: e.__setitem__(asc, b"another signature"), "%s differs" % asc)

    def test_a_file_the_bundle_lacks(self):
        pom = "%s/zipline/%s/zipline-%s.pom" % (GPATH, VERSION, VERSION)
        self.assert_fails_with(lambda e: e.pop(pom), "the checked directory has %s, which the bundle does not" % pom)

    def test_a_file_only_the_bundle_has(self):
        extra = "%s/zipline/%s/zipline-%s-extra.jar" % (GPATH, VERSION, VERSION)
        self.assert_fails_with(lambda e: e.__setitem__(extra, b"x"),
                               "the bundle has %s, which the checked directory does not" % extra)

    def test_a_wrong_sha1_beside_the_file(self):
        pom = "%s/zipline/%s/zipline-%s.pom" % (GPATH, VERSION, VERSION)

        def change(e):
            e[pom + ".sha1"] = b"0" * 40
        # The checked directory has the right .sha1, so the comparison fails too.
        self.assert_fails_with(change, "%s: its .sha1 in the bundle is not the file's sha1" % pom)

    def test_a_file_without_sha1(self):
        pom = "%s/zipline/%s/zipline-%s.pom" % (GPATH, VERSION, VERSION)
        self.assert_fails_with(lambda e: e.pop(pom + ".sha1"), "%s has no .sha1 in the bundle" % pom)

    def test_maven_metadata_in_the_bundle(self):
        meta = "%s/zipline/maven-metadata.xml" % GPATH
        self.assert_fails_with(lambda e: e.__setitem__(meta, b"<metadata/>"), "the bundle has %s (expected left out)" % meta)

    def test_a_file_outside_the_group(self):
        other = "app/cash/zipline/zipline/1.28.0/zipline-1.28.0.pom"

        def change(e):
            e[other] = b"x"
            e[other + ".sha1"] = hashlib.sha1(b"x").hexdigest().encode()
        self.assert_fails_with(change, "the bundle has %s, outside %s" % (other, GROUP))

    def test_a_missing_spot_check_file(self):
        # Neither side has the Gradle plugin's jar: the directories agree, the spot check does not.
        for p in os.listdir(os.path.join(self.repo, GPATH, "zipline-gradle-plugin", VERSION)):
            os.remove(os.path.join(self.repo, GPATH, "zipline-gradle-plugin", VERSION, p))
        self.assert_fails_with(None, "spot-check file %s/zipline-gradle-plugin/%s/zipline-gradle-plugin-%s.jar is not in the release"
                               % (GPATH, VERSION, VERSION))

    def test_a_duplicate_entry(self):
        bundle_of(self.repo, self.bundle)
        pom = "%s/zipline/%s/zipline-%s.pom" % (GPATH, VERSION, VERSION)
        with zipfile.ZipFile(self.bundle, "a") as z, warnings.catch_warnings():
            warnings.simplefilter("ignore")  # zipfile warns about the duplicate name it writes
            with open(os.path.join(self.repo, pom), "rb") as f:
                z.writestr(pom, f.read())
        ok, out = self.run_sha1()
        self.assertFalse(ok, out)
        self.assertIn("the bundle has %s twice" % pom, out)


# --- check-release.py: the licence path (L-d) -------------------------------------------------------

NOTICE = b"Keliver's fork of Zipline, test.\n"
LICENCE = b"Permission is hereby granted, free of charge (test)\n"


class LicencePathTest(unittest.TestCase):
    """check-release.py runs as a script; these build a repository with a root module and its
    platform artifact and look only for the L-d lines in its output (the synthetic release fails
    many other checks, which these tests do not look at)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "repo")
        self.dl = os.path.join(self.tmp.name, "dl")
        os.makedirs(self.dl)
        write_zip(os.path.join(self.dl, "zipline-jvm-1.28.0.jar"), {"META-INF/MANIFEST.MF": b""})
        self.upstream_list = os.path.join(self.dl, "artifacts.txt")

    def tearDown(self):
        self.tmp.cleanup()

    def root_module(self, artifact, platforms):
        p = artifact_path(self.repo, artifact, "module")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        variants = [{"name": "%sApiElements-published" % pl, "available-at": {"group": GROUP, "module": pl, "version": VERSION}}
                    for pl in platforms]
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"formatVersion": "1.1", "variants": variants}, f)

    def platform_jar(self, artifact, licence_dir):
        write_zip(artifact_path(self.repo, artifact, "jar"),
                  {"META-INF/NOTICE": NOTICE, "META-INF/%s/LICENSE-QuickJS.txt" % licence_dir: LICENCE})

    def run_check(self):
        artifacts = sorted(os.listdir(os.path.join(self.repo, *GROUP.split("."))))
        with open(self.upstream_list, "w", encoding="utf-8") as f:
            f.write("\n".join(artifacts) + "\n")
        r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "check-release.py"), self.repo, GROUP, VERSION, "1.28.0",
                            self.upstream_list, os.path.join(self.dl, "zipline-jvm-1.28.0.jar"),
                            os.path.join(self.dl, "none.aar"), os.path.join(self.dl, "none.jar"), "main", "unsigned"],
                           capture_output=True, text=True, timeout=60)
        self.assertNotEqual(r.returncode, 0)  # the synthetic release is incomplete on purpose
        return r.stdout + r.stderr

    def test_platform_artifact_under_its_projects_name(self):
        self.root_module("zipline", ["zipline-jvm"])
        self.platform_jar("zipline-jvm", "zipline")
        out = self.run_check()
        self.assertNotIn("not under its module's name", out)
        self.assertNotIn("platform artifact of both", out)
        self.assertIn("1 platform artifacts mapped", out)

    def test_platform_artifact_under_its_own_artifact_name_fails(self):
        self.root_module("zipline", ["zipline-jvm"])
        self.platform_jar("zipline-jvm", "zipline-jvm")
        out = self.run_check()
        self.assertIn("zipline-jvm-%s.jar: META-INF/zipline-jvm/LICENSE-QuickJS.txt is not under its module's name zipline "
                      "(artifact zipline-jvm)" % VERSION, out)

    def test_a_prefix_of_the_project_name_fails(self):
        # The loose check this replaced accepted any prefix of the artifact name.
        self.root_module("zipline-loader", ["zipline-loader-jvm"])
        self.platform_jar("zipline-loader-jvm", "zipline")
        out = self.run_check()
        self.assertIn("META-INF/zipline/LICENSE-QuickJS.txt is not under its module's name zipline-loader", out)

    def test_a_platform_artifact_claimed_by_two_roots_fails(self):
        self.root_module("zipline", ["zipline-jvm"])
        self.root_module("zipline-loader", ["zipline-jvm"])
        self.platform_jar("zipline-jvm", "zipline")
        out = self.run_check()
        self.assertIn("zipline-jvm is a platform artifact of both zipline and zipline-loader", out)

    def test_the_shared_path_fails(self):
        self.root_module("zipline", ["zipline-jvm"])
        write_zip(artifact_path(self.repo, "zipline-jvm", "jar"),
                  {"META-INF/NOTICE": NOTICE, "META-INF/zipline/LICENSE-QuickJS.txt": LICENCE,
                   "META-INF/LICENSE-QuickJS.txt": LICENCE})
        out = self.run_check()
        self.assertIn("has META-INF/LICENSE-QuickJS.txt, a path every module would share", out)


if __name__ == "__main__":
    unittest.main()
