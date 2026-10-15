/*
 * The full Gradle output of every relay publish. The /publish response carries
 * only its last 30 lines, so a failure whose cause was earlier (a Kotlin/JS
 * internal compiler error prints its stack trace first and a summary last) could
 * not be diagnosed afterwards. Each publish now writes its whole output to
 * <store>/publish-logs/, readable by the owner only and never under bundles/
 * (which the relay serves), and the response names the file. The newest KEEP
 * logs are kept.
 */
import java.io.File

internal object PublishLogs {
  const val KEEP = 20
  private val NAME = Regex("""publish-v\d+-(\d+)\.log""")

  /** Writes [output] for the publish of [sequence] and prunes older logs; returns the file. */
  fun save(storeDir: File, sequence: Int, output: String, nowMillis: Long = System.currentTimeMillis()): File {
    val dir = File(storeDir, "publish-logs")
    dir.mkdirs()
    val file = File(dir, "publish-v$sequence-$nowMillis.log")
    file.writeText(output)
    file.setReadable(false, false)
    file.setReadable(true, true)
    dir.listFiles().orEmpty()
      .mapNotNull { f -> NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()?.let { f to it } }
      .sortedByDescending { it.second }
      .drop(KEEP)
      .forEach { it.first.delete() }
    return file
  }
}
