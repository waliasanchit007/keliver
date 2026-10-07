import java.io.File
import kotlin.system.exitProcess

/**
 * `keliver-publish`: the relay's publish without the relay, for CI and for a
 * static or CDN bundle server (W3, docs/DELIVERY_PLAN.md).
 *
 *   keliver-publish [app-dir] --out <dir> --public-key-file <file> [--channel stable] [--skip-build]
 *
 * Runs the app's `publishTask` (keliver.portal.json), refuses output that is not
 * signed with the app's key or is incomplete, and writes `<out>/bundles/v<N>/`
 * plus `<out>/bundles/index.json`. The tools bundle's `bin/keliver-publish`
 * wrapper starts this; it supplies the public key from the app's store when
 * none is given. Only the PUBLIC key is read here. The compile task signs, with
 * the key its signing block finds (KELIVER_SIGNING_KEY_FILE in CI).
 *
 * Exit status: 0 published, 2 usage, 3 build failed, 4 refused (nothing written),
 * 5 an I/O error while writing (see its message: at worst a `v<N>/` that no
 * index entry names, which is never served as current and whose number is
 * never reused).
 */
object KeliverPublish {
  private const val USAGE =
    "usage: keliver-publish [app-dir] --out <dir> (--public-key-file <file> | KELIVER_PUBLIC_KEY_HEX) " +
      "[--channel stable] [--skip-build]"

  @JvmStatic
  fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.getenv("KELIVER_PUBLIC_KEY_HEX")))
  }

  internal fun run(args: List<String>, publicKeyHexEnv: String?, build: (File, String) -> Int = ::gradle): Int {
    var app: String? = null
    var out: String? = null
    var keyFile: String? = null
    var channel = DEFAULT_CHANNEL
    var skipBuild = false
    val it = args.iterator()
    while (it.hasNext()) {
      when (val a = it.next()) {
        "--out" -> out = it.nextOrNull() ?: return usage("--out needs a directory")
        "--public-key-file" -> keyFile = it.nextOrNull() ?: return usage("--public-key-file needs a file")
        "--channel" -> channel = it.nextOrNull() ?: return usage("--channel needs a name")
        "--skip-build" -> skipBuild = true
        "-h", "--help" -> { println(USAGE); return 0 }
        else -> if (a.startsWith("-") || app != null) return usage("unexpected argument: $a") else app = a
      }
    }
    val repoDir = File(app ?: ".").absoluteFile.normalize()
    if (out.isNullOrBlank()) return usage("--out is required (a directory; it is never the current one by default)")
    if (keyFile != null && keyFile.isBlank()) return usage("--public-key-file needs a file")
    if (!repoDir.isDirectory) return usage("no such app directory: $repoDir")
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

    if (skipBuild) {
      println("keliver-publish: --skip-build: publishing the existing output of ${config.publishTask}")
    } else {
      println("keliver-publish: building ${config.publishTask} in $repoDir")
      val code = build(repoDir, config.publishTask)
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
      publishStatic(File(repoDir, config.publishOutput), File(out), publicKeyHex, channel, caps)
    } catch (e: PublishRefused) {
      return refuse(e.message.orEmpty())
    } catch (e: java.io.IOException) {
      System.err.println("keliver-publish FAILED writing $out: $e. index.json is either unchanged or complete; never half-written.")
      return 5
    }
    println("keliver-publish: the manifest is signed with this app's $PORTAL_SIGNING_KEY_NAME key; every module is present")
    println(
      "keliver-publish: published v${result.version} (sequence ${result.sequence}, channel $channel, " +
        "capabilities ${caps.ifEmpty { listOf("none") }.joinToString(",")}) -> ${result.dir}",
    )
    println("keliver-publish: index ${File(result.dir.parentFile, INDEX_FILE)}")
    return 0
  }

  private fun gradle(repoDir: File, task: String): Int =
    ProcessBuilder(File(repoDir, "gradlew").absolutePath, task, "--console=plain")
      .directory(repoDir)
      .inheritIO()
      .start()
      .waitFor()

  private fun usage(why: String): Int {
    System.err.println("keliver-publish: $why\n$USAGE")
    return 2
  }

  private fun refuse(why: String): Int {
    System.err.println(
      """
      |keliver-publish REFUSED: $why
      |  Nothing was published. Production hosts accept only bundles signed with this app's key:
      |  the compile task signs them, through the block keliver-new-publish-target.sh writes. In CI,
      |  point KELIVER_SIGNING_KEY_FILE at a file holding the private key (never print it).
      """.trimMargin(),
    )
    return 4
  }

  private fun Iterator<String>.nextOrNull(): String? = if (hasNext()) next() else null
}
