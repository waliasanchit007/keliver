/*
 * The full Gradle output of every relay publish. The /publish response carries
 * only its last 30 lines, so a failure whose cause was earlier (a Kotlin/JS
 * internal compiler error prints its stack trace first and a summary last) could
 * not be diagnosed afterwards. Each publish now writes its whole output to
 * <store>/publish-logs/ and the response names the file. The newest KEEP logs
 * are kept.
 *
 * A build's output can hold secrets (an app's build script or log level decides
 * what it prints), so the logs get the care keys/ gets: the directory
 * is rwx------ and each file is CREATED rw-------, never written first and
 * narrowed after. Nothing serves them (they are outside bundles/, the only
 * directory the relay serves), and the directory is reserved, never a project.
 */
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Relay-owned storage that sits beside the projects under the store root. Never a project. */
internal val RESERVED_ROOT_DIRS = setOf("bundles", "keys", "kotlin", "reports", PublishLogs.DIR)

internal object PublishLogs {
  const val DIR = "publish-logs"
  const val KEEP = 20
  private val NAME = Regex("""publish-v\d+-(\d+)\.log""")
  private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

  /** Writes [output] for the publish of [sequence] and prunes older logs; returns the file. */
  fun save(storeDir: File, sequence: Int, output: String, nowMillis: Long = System.currentTimeMillis()): File {
    val dir = File(storeDir, DIR).toPath()
    if (posix) {
      val ownerOnly = PosixFilePermissions.fromString("rwx------")
      Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(ownerOnly))
      Files.setPosixFilePermissions(dir, ownerOnly) // it may predate this
    } else {
      Files.createDirectories(dir)
    }
    val file = dir.resolve("publish-v$sequence-$nowMillis.log")
    // CREATE_NEW: a name collision throws rather than overwriting (the relay's caller reports it
    // and publishes anyway); publishes run one at a time and the name carries the sequence.
    val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    val channel = if (posix) {
      FileChannel.open(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
      FileChannel.open(file, options)
    }
    channel.use { c ->
      val buffer = ByteBuffer.wrap(output.encodeToByteArray())
      while (buffer.hasRemaining()) c.write(buffer)
    }
    dir.toFile().listFiles().orEmpty()
      .mapNotNull { f -> NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()?.let { f to it } }
      .sortedByDescending { it.second }
      .drop(KEEP)
      .forEach { it.first.delete() }
    return file.toFile()
  }
}

/**
 * U34: the first relay publish after another Gradle build of the app sometimes failed in the
 * Kotlin/JS compiler ("Internal compiler error"), and the same publish passed when run again. The
 * cause is not known (KNOWN_BUGS U34), so a publish whose build fails THAT way runs once more;
 * every other failure is reported at once. Both outputs are kept in publish-logs/.
 */
internal object PublishRetry {
  private val compilerCrash = Regex("""internal compiler error|backend internal error""", RegexOption.IGNORE_CASE)

  fun isCompilerCrash(exitCode: Int, output: String): Boolean = exitCode != 0 && compilerCrash.containsMatchIn(output)
}
