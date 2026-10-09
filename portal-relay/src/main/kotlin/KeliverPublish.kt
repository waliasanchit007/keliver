import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonPrimitive

/**
 * `keliver-publish`: the relay's publish without the relay, for CI and for a
 * static or CDN bundle server (W3, docs/DELIVERY_PLAN.md).
 *
 *   keliver-publish [app-dir] --out <dir> --public-key-file <file> [--channel stable] [--skip-build] [--init]
 *   keliver-publish [app-dir] --out <dir> --public-key-file <file> --republish <version> [--channel <name>]
 *   keliver-publish [app-dir] --out <dir> --public-key-file <file> --promote <sequence> --channel <name>
 *
 * `<dir>` must hold the LIVE `bundles/index.json` and `bundles/v<N>/` (download
 * them from the bundle server first): the next version and sequence come from
 * them. Without an index it refuses, unless `--init` says this is the first
 * publish ever.
 *
 * Runs the app's `publishTask` (keliver.portal.json), refuses output that is not
 * signed with the app's key or is incomplete, and writes `<out>/bundles/v<N>/`
 * plus `<out>/bundles/index.json`. The tools bundle's `bin/keliver-publish`
 * wrapper starts this; it supplies the public key from the app's store when
 * none is given. Only the PUBLIC key is read here. The compile task signs, with
 * the key its signing block finds (KELIVER_SIGNING_KEY_FILE in CI).
 *
 * `--republish <version>` is a rollback (W4.3): hosts refuse a sequence below
 * the highest they have run, so older code goes out again as a NEW sequence.
 * v<version>'s modules are copied unchanged, the app's `keliverResign` task
 * (from the same signing block, holding the same key) signs the copy for the
 * next sequence, and it is published as a new `v<N>/` with the original's
 * capabilities and channel (or `--channel`). Nothing is compiled.
 *
 * `--promote <sequence> --channel <name>` (W4.4) offers an already published
 * bundle on another channel: a second index entry for the same `v<N>/`. Nothing
 * is built or signed; only the public key is needed.
 *
 * Exit status:
 * - 0 published;
 * - 2 usage;
 * - 3 the build (or, for --republish, the re-sign) failed (nothing published);
 * - 4 refused: nothing written, except that losing the lock race to another
 *   publish may have created `bundles/.publish.lock`;
 * - 5 an I/O error while writing: the index is unchanged or complete, never
 *   half-written, but a `v<N>/` that no entry names may be left. It is never
 *   served as current, and its number is never reused.
 * An unexpected error exits 1 with a stack trace.
 */
object KeliverPublish {
  private const val USAGE =
    "usage: keliver-publish [app-dir] --out <dir> (--public-key-file <file> | KELIVER_PUBLIC_KEY_HEX) " +
      "[--channel stable] [--skip-build] [--init] [--republish <version> | --promote <sequence> --channel <name>]"

  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.getenv("KELIVER_PUBLIC_KEY_HEX")))
  }

  internal fun run(
    args: List<String>,
    publicKeyHexEnv: String?,
    resign: (File, File, Long) -> Int = ::gradleResign,
    build: (File, String, Long) -> Int = ::gradle,
  ): Int {
    var app: String? = null
    var out: String? = null
    var keyFile: String? = null
    var channel: String? = null
    var republish: Int? = null
    var promote: Long? = null
    var skipBuild = false
    var init = false
    val it = args.iterator()
    while (it.hasNext()) {
      when (val a = it.next()) {
        "--out" -> out = it.nextOrNull() ?: return usage("--out needs a directory")
        "--public-key-file" -> keyFile = it.nextOrNull() ?: return usage("--public-key-file needs a file")
        "--channel" -> channel = it.nextOrNull() ?: return usage("--channel needs a name")
        "--republish" -> republish = it.nextOrNull()?.removePrefix("v")?.takeIf { v -> v.matches(Regex("[1-9][0-9]{0,8}")) }?.toInt()
          ?: return usage("--republish needs a published version number (v<N>'s N)")
        "--promote" -> promote = it.nextOrNull()?.takeIf { v -> v.matches(Regex("[1-9][0-9]{0,17}")) }?.toLong()
          ?: return usage("--promote needs a published sequence number")
        "--skip-build" -> skipBuild = true
        "--init" -> init = true
        "-h", "--help" -> { println(USAGE); return 0 }
        else -> if (a.startsWith("-") || app != null) return usage("unexpected argument: $a") else app = a
      }
    }
    val repoDir = File(app ?: ".").absoluteFile.normalize()
    if (out.isNullOrBlank()) return usage("--out is required (a directory; it is never the current one by default)")
    if (keyFile != null && keyFile.isBlank()) return usage("--public-key-file needs a file")
    if (!repoDir.isDirectory) return usage("no such app directory: $repoDir")
    if (republish != null && (init || skipBuild)) {
      return usage("--republish re-signs a bundle already in the live index: it takes neither --init nor --skip-build")
    }
    if (promote != null && (init || skipBuild || republish != null || channel == null)) {
      return usage("--promote <sequence> needs --channel <name>, and takes no --init, --skip-build or --republish")
    }
    val publicKeyHex = when {
      keyFile != null -> runCatching { File(keyFile).readText().trim() }.getOrElse {
        return refuse("could not read the public key file $keyFile: ${it.message}")
      }
      !publicKeyHexEnv.isNullOrBlank() -> publicKeyHexEnv.trim()
      else -> return usage("no public key: pass --public-key-file, or set KELIVER_PUBLIC_KEY_HEX")
    }
    val config = runCatching { loadPortalConfig(repoDir) }.getOrElse {
      return refuse("could not read ${File(repoDir, "keliver.portal.json")}: ${it.message}")
    }

    if (promote != null) return runPromote(File(out), promote, channel!!, publicKeyHex)
    if (republish != null) return runRepublish(repoDir, File(out), republish, publicKeyHex, channel, resign)

    // Checked again under the lock; this only spares a build that could not be published.
    if (!init && !File(File(out, "bundles"), INDEX_FILE).exists()) {
      return refuse(
        "there is no ${File(File(out, "bundles"), INDEX_FILE)}. Download the served bundles/index.json and " +
          "every bundles/v<N>/ into $out first: the next version and sequence come from them, and publishing " +
          "into an empty directory would start again at v1 and overwrite what hosts already load. Only for the " +
          "very first publish, pass --init.",
      )
    }

    // W4: the build signs the sequence this publish will take into the manifest's
    // metadata; publishStatic checks it again under the lock.
    val sequence = try {
      val bundlesDir = File(out, "bundles")
      nextSequence(readIndex(bundlesDir), bundlesDir) // an absent index reads as empty
    } catch (e: PublishRefused) {
      return refuse(e.message.orEmpty())
    }

    if (skipBuild) {
      println("keliver-publish: --skip-build: publishing the existing output of ${config.publishTask} (it must be signed for sequence $sequence)")
    } else {
      println("keliver-publish: building ${config.publishTask} in $repoDir, signed for sequence $sequence")
      val code = build(repoDir, config.publishTask, sequence)
      if (code != 0) {
        System.err.println("keliver-publish: the build failed (gradle exit $code). Nothing was published.")
        return 3
      }
    }

    val capsFile = File(File(repoDir, config.screensDir), "capabilities.txt")
    val caps = if (capsFile.isFile) {
      capsFile.readLines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() && !l.startsWith("#") }
    } else {
      emptyList()
    }
    val result = try {
      publishStatic(File(repoDir, config.publishOutput), File(out), publicKeyHex, channel ?: DEFAULT_CHANNEL, caps, init = init)
    } catch (e: PublishRefused) {
      return refuse(e.message.orEmpty(), e.aboutSigning)
    } catch (e: java.io.IOException) {
      System.err.println(
        "keliver-publish FAILED writing $out: $e. index.json is either unchanged or complete, never half-written; " +
          "a v<N>/ that no index entry names may be left, which is never served as current and never reused.",
      )
      return 5
    }
    println("keliver-publish: the manifest is signed with this app's $PORTAL_SIGNING_KEY_NAME key; every module is present")
    println(
      "keliver-publish: published v${result.version} (sequence ${result.sequence}, channel ${channel ?: DEFAULT_CHANNEL}, " +
        "capabilities ${caps.ifEmpty { listOf("none") }.joinToString(",")}) -> ${result.dir}",
    )
    println("keliver-publish: index ${File(result.dir.parentFile, INDEX_FILE)}")
    return 0
  }

  private fun runRepublish(repoDir: File, out: File, version: Int, publicKeyHex: String, channel: String?, resign: (File, File, Long) -> Int): Int {
    val result = try {
      republishStatic(out, version, publicKeyHex, channel) { dir, sequence ->
        println("keliver-publish: re-signing a copy of v$version for sequence $sequence (keliverResign in $repoDir); nothing is compiled")
        resign(repoDir, dir, sequence)
      }
    } catch (e: BuildFailed) {
      System.err.println(
        "keliver-publish: the re-sign failed (gradle exit ${e.exitCode}). Nothing was published.\n" +
          "  If Gradle says task 'keliverResign' was not found, the app's signing block predates republishing: " +
          "run keliver-new-publish-target.sh to replace it.",
      )
      return 3
    } catch (e: PublishRefused) {
      return refuse(e.message.orEmpty(), e.aboutSigning)
    } catch (e: java.io.IOException) {
      System.err.println(
        "keliver-publish FAILED writing $out: $e. index.json is either unchanged or complete, never half-written; " +
          "a v<N>/ that no index entry names may be left, which is never served as current and never reused.",
      )
      return 5
    }
    val e = result.entry
    println(
      "keliver-publish: republished v$version as v${result.version} (sequence ${result.sequence}, channel " +
        "${(e["channel"] as JsonPrimitive).content}): the same modules, signed again for the new sequence -> ${result.dir}",
    )
    println("keliver-publish: index ${File(result.dir.parentFile, INDEX_FILE)}")
    return 0
  }

  private fun runPromote(out: File, sequence: Long, channel: String, publicKeyHex: String): Int {
    val result = try {
      promoteStatic(out, sequence, channel, publicKeyHex)
    } catch (e: PublishRefused) {
      return refuse(e.message.orEmpty(), e.aboutSigning)
    } catch (e: java.io.IOException) {
      System.err.println("keliver-publish FAILED writing $out: $e. index.json is either unchanged or complete, never half-written.")
      return 5
    }
    println(
      "keliver-publish: promoted sequence ${result.sequence} (v${result.version}) from ${result.from} to $channel: " +
        "a second index entry for the same signed bundle; nothing was built or signed",
    )
    println("keliver-publish: index ${File(File(out, "bundles"), INDEX_FILE)}")
    return 0
  }

  private fun gradleResign(repoDir: File, dir: File, sequence: Long): Int =
    runGradlew(repoDir, "keliverResign", "-Pkeliver.resignDir=${dir.absolutePath}", "-Pkeliver.sequence=$sequence")

  private fun gradle(repoDir: File, task: String, sequence: Long): Int =
    runGradlew(repoDir, task, "-Pkeliver.sequence=$sequence")

  /** The app's ./gradlew; one that cannot start is a failed build (exit 3), not an I/O error writing the site. */
  private fun runGradlew(repoDir: File, vararg args: String): Int = try {
    ProcessBuilder(listOf(File(repoDir, "gradlew").absolutePath) + args + "--console=plain")
      .directory(repoDir)
      .inheritIO()
      .start()
      .waitFor()
  } catch (e: java.io.IOException) {
    System.err.println("keliver-publish: could not run $repoDir/gradlew: ${e.message}")
    127
  }

  private fun usage(why: String): Int {
    System.err.println("keliver-publish: $why\n$USAGE")
    return 2
  }

  private fun refuse(why: String, aboutSigning: Boolean = false): Int {
    System.err.println("keliver-publish REFUSED: $why\n  Nothing was published.")
    if (aboutSigning) {
      System.err.println(
        """
        |  Production hosts accept only bundles signed with this app's key: the compile task signs
        |  them, through the block keliver-new-publish-target.sh writes. In CI, point
        |  KELIVER_SIGNING_KEY_FILE at a file holding the private key (never print it).
        """.trimMargin(),
      )
    }
    return 4
  }

  private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null
}
