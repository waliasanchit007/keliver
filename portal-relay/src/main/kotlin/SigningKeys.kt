import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator

/*
 * U27: this app's Ed25519 signing identity, `<store>/keys/ed25519.{priv,pub}`.
 *
 * The private key signs every bundle this app's production hosts will accept.
 * It used to be created with `File.writeText`, so its mode was whatever the
 * umask gave — MEASURED `-rw-r--r--` on macOS and on the Linux runner, under a
 * store whose directories are all `drwxr-xr-x`: any local user could read it.
 *
 * The rules:
 *
 * - A NEW private key is owner-only from the moment the file exists. It is
 *   created O_EXCL with mode 0600, inside a staging directory that is itself
 *   0700, written, synced and CHECKED, and only then linked into `keys/`. Never
 *   write-then-chmod: in between, the key is readable, and a reader that opened
 *   it in that window keeps what it read. A umask can only remove bits, so no
 *   umask widens this.
 *
 * - A key is created only when protection is MEASURED to hold. On a filesystem
 *   that ignores modes (FAT, some network mounts) the staged key reads back
 *   wider than 0600; it is then discarded before anything could sign with it,
 *   and the start fails saying so. Nothing is ever reported as protected on the
 *   strength of what was requested rather than what was read back.
 *
 * - An EXISTING key is never modified, re-created or rotated here, and its mode
 *   is never changed. Tightening an exposed key cannot undo the exposure, so
 *   whether to keep or rotate that identity is the owner's decision; this
 *   reports the measured state and the exact commands that keep the identity.
 *   Refusing to sign with it is the relay's job: see [keyProtection].
 *
 * - Half an identity is refused, not repaired. The old code regenerated BOTH
 *   files whenever either was missing — replacing a private key, or a public
 *   key hosts had already embedded. The pair is the identity; one half cannot
 *   be completed without replacing the other.
 *
 * Nothing here prints, logs or returns key material; messages carry paths and
 * modes only.
 */

internal const val PRIVATE_KEY_FILE = "ed25519.priv"
internal const val PUBLIC_KEY_FILE = "ed25519.pub"

/** Staging directories for a generation in flight. Only this file creates them. */
private const val STAGING_PREFIX = ".keygen-"

private const val NOT_CREATED = "Nothing was created. Put this app's store on a filesystem that enforces " +
  "permissions (PORTAL_STORE, or \"store\" in keliver.portal.json)."

private val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
private val OWNER_ONLY_DIR: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
private val GROUP_OR_OTHER: Set<PosixFilePermission> = setOf(
  PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
  PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
)
private val GROUP_OR_OTHER_WRITE: Set<PosixFilePermission> =
  setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)

/** What was MEASURED about the private key's protection. */
internal sealed interface KeyProtection {
  /** No group or other bits on the key, and `keys/` cannot be written by other users. */
  data object OwnerOnly : KeyProtection

  /**
   * Other users can read the key, or replace it. [detail] says how. [chmodFixes]
   * is false when the filesystem itself is the problem — it does not apply modes,
   * or ignores ownership — so no chmod can help and the store has to move.
   */
  data class Exposed(val detail: String, val chmodFixes: Boolean = true) : KeyProtection

  /** The filesystem reports no POSIX permissions, so nothing can be said either way. */
  data class Unverified(val detail: String) : KeyProtection
}

/** A refusal. Everything that existed before the call is as it was. */
internal class SigningKeyException(message: String) : Exception(message)

/**
 * Makes sure [keysDir] holds a signing identity, generating one only when it
 * holds NEITHER file. Returns true when it generated one.
 */
internal fun ensureSigningKeys(keysDir: File): Boolean {
  val keys = keysDir.toPath()
  val createdDir = createKeysDir(keys)
  removeInterruptedGenerations(keys)
  val hasPriv = Files.exists(keys.resolve(PRIVATE_KEY_FILE), LinkOption.NOFOLLOW_LINKS)
  val hasPub = Files.exists(keys.resolve(PUBLIC_KEY_FILE), LinkOption.NOFOLLOW_LINKS)
  if (hasPriv && hasPub) return false
  if (hasPriv || hasPub) throw SigningKeyException(halfIdentity(keysDir, missing = if (hasPriv) PUBLIC_KEY_FILE else PRIVATE_KEY_FILE))
  generate(keys, createdDir)
  return true
}

/**
 * The private key's protection as the filesystem reports it now. Modes that
 * cannot be read are [KeyProtection.Unverified] — never assumed fine.
 */
internal fun keyProtection(keysDir: File): KeyProtection = try {
  protectionOf(keysDir.toPath().resolve(PRIVATE_KEY_FILE), keysDir.toPath())
} catch (e: IOException) {
  KeyProtection.Unverified("the modes of $keysDir/$PRIVATE_KEY_FILE could not be read ($e)")
}

/** `rw-------`, or `unknown` where the filesystem has no POSIX modes. For messages only. */
internal fun modeString(path: File): String =
  if (!supportsPosix(path.toPath())) "unknown" else PosixFilePermissions.toString(Files.getPosixFilePermissions(path.toPath()))

/** The commands that keep this identity and make it owner-only. Nothing is rotated. */
internal fun tightenCommands(keysDir: File): String =
  "chmod 700 '${keysDir.path}' && chmod 600 '${File(keysDir, PRIVATE_KEY_FILE).path}'"

private fun supportsPosix(path: Path): Boolean =
  runCatching { Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java) }
    .getOrDefault(false)

/**
 * [requested] is the mode this process created [key] with, when it did. A mode
 * read back with a bit that was never requested is proof the filesystem did not
 * apply it — a umask can only remove bits — and MEASURED on macOS FAT, which
 * reads back rwx------ for a file created rw-------.
 */
private fun protectionOf(key: Path, dir: Path, requested: Set<PosixFilePermission>? = null): KeyProtection {
  if (!supportsPosix(dir)) {
    return KeyProtection.Unverified("the filesystem holding $dir reports no POSIX permissions")
  }
  val ownership = ownershipIgnored(dir)
  if (ownership is Ownership.Unknown) return KeyProtection.Unverified(ownership.detail)
  val keyPerms = Files.getPosixFilePermissions(key)
  val dirPerms = Files.getPosixFilePermissions(dir)
  val filesystem = buildList {
    if (ownership is Ownership.Ignored) add(ownership.detail)
    if (requested != null && !requested.containsAll(keyPerms)) {
      add(
        "${key.fileName} reads back ${PosixFilePermissions.toString(keyPerms)} though it was created " +
          "${PosixFilePermissions.toString(requested)}: the filesystem does not apply the modes it is given",
      )
    }
  }
  val problems = filesystem + buildList {
    if (keyPerms.any { it in GROUP_OR_OTHER }) {
      add("${key.fileName} is ${PosixFilePermissions.toString(keyPerms)}: other users can ${access(keyPerms)} it")
    }
    if (dirPerms.any { it in GROUP_OR_OTHER_WRITE }) {
      add("${dir.fileName}/ is ${PosixFilePermissions.toString(dirPerms)}: other users can replace the key")
    }
  }
  return if (problems.isEmpty()) {
    KeyProtection.OwnerOnly
  } else {
    KeyProtection.Exposed(problems.joinToString("; "), chmodFixes = filesystem.isEmpty())
  }
}

/**
 * macOS mounts external and disk-image volumes `noowners` (Finder: "Ignore
 * ownership on this volume"): every local user is then treated as the owner of
 * every file, so an owner-only mode protects nothing. MEASURED on a FAT image
 * attached with hdiutil: `ls` showed rwx------ owned by whoever looked. The JVM
 * cannot see mount flags, so this asks `mount`, and a `mount` that cannot be
 * read is not taken as "no".
 */
private fun ownershipIgnored(dir: Path): Ownership {
  if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return Ownership.Enforced
  val real = runCatching { dir.toRealPath().toString() }
    .getOrElse { return Ownership.Unknown("the real path of $dir could not be read ($it)") }
  val listing = runCatching {
    val p = ProcessBuilder("/sbin/mount").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "exit ${p.exitValue()}" }
    out
  }.getOrElse { return Ownership.Unknown("the mount table could not be read to check for ignored ownership ($it)") }
  return noownersMountFor(listing, real)?.let {
    Ownership.Ignored(
      "the volume at $it is mounted with ownership ignored (noowners), so every local user " +
        "counts as the owner of every file on it",
    )
  } ?: Ownership.Enforced
}

private sealed interface Ownership {
  data object Enforced : Ownership
  data class Ignored(val detail: String) : Ownership
  data class Unknown(val detail: String) : Ownership
}

/**
 * The mount point holding [realPath] if `mount`'s [listing] marks it `noowners`.
 * Lines read "<device> on <mount point> (<flag>, <flag>, ...)"; the mount holding
 * a path is the one with the longest mount point that prefixes it.
 */
internal fun noownersMountFor(listing: String, realPath: String): String? {
  val line = Regex("^.+? on (/.*) \\(([^()]*)\\)$")
  val holder = listing.lines().mapNotNull { line.find(it) }
    .map { it.groupValues[1] to it.groupValues[2] }
    .filter { (point, _) -> realPath == point || realPath.startsWith(point.trimEnd('/') + "/") }
    .maxByOrNull { (point, _) -> point.length }
    ?: return null
  return holder.first.takeIf { "noowners" in holder.second.split(',').map { f -> f.trim() } }
}

private fun access(perms: Set<PosixFilePermission>): String {
  val read = PosixFilePermission.GROUP_READ in perms || PosixFilePermission.OTHERS_READ in perms
  val write = PosixFilePermission.GROUP_WRITE in perms || PosixFilePermission.OTHERS_WRITE in perms
  return when {
    read && write -> "read and overwrite"
    read -> "read"
    write -> "overwrite"
    else -> "execute"
  }
}

/**
 * `keys/` is created 0700. An existing one is left as it is — its mode is
 * reported by [keyProtection], never changed. Returns true if this call made it.
 */
private fun createKeysDir(keys: Path): Boolean {
  if (Files.isDirectory(keys)) return false
  Files.createDirectories(keys.parent)
  var created = false
  try {
    if (supportsPosix(keys.parent)) {
      Files.createDirectory(keys, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
    } else {
      Files.createDirectory(keys)
    }
    created = true
  } catch (_: FileAlreadyExistsException) {
    // Another start made it first; checked like any existing one.
  }
  if (!Files.isDirectory(keys)) throw SigningKeyException("$keys exists and is not a directory")
  return created
}

/**
 * A generation interrupted between staging and linking leaves `.keygen-*`
 * behind. Its key never reached `keys/`, so nothing can have signed with it;
 * it is removed rather than left for someone to find.
 */
private fun removeInterruptedGenerations(keys: Path) {
  Files.newDirectoryStream(keys, "$STAGING_PREFIX*").use { stream ->
    stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { deleteTree(it) }
  }
}

private fun generate(keys: Path, createdDir: Boolean) {
  // A fresh key is not put where other users could replace it.
  if (supportsPosix(keys)) {
    val perms = Files.getPosixFilePermissions(keys)
    val dirPerms = PosixFilePermissions.toString(perms)
    if (perms.any { it in GROUP_OR_OTHER_WRITE } || (createdDir && !OWNER_ONLY_DIR.containsAll(perms))) {
      throw SigningKeyException(
        if (createdDir) {
          "will not create a signing key in $keys: the directory was created rwx------, but the filesystem " +
            "reports $dirPerms. This filesystem does not enforce permissions. $NOT_CREATED"
        } else {
          "will not create a signing key in $keys: it is $dirPerms, so other users could replace the key. " +
            "Nothing was created. Make it owner-only (chmod 700 '$keys') and start again."
        },
      )
    }
  }
  val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  // Zipline wants raw 32-byte Ed25519 keys as hex; the JDK wraps them in
  // PKCS#8/X.509, and the raw key is the last 32 bytes. Same bytes, same
  // format, as before U27: no trailing newline, and readers trim.
  fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
  val privBytes = hex(raw32(kp.private.encoded)).toByteArray()
  val pubBytes = hex(raw32(kp.public.encoded)).toByteArray()

  val posix = supportsPosix(keys)
  // createTempDirectory is 0700 on POSIX; stated, not assumed.
  val stage = if (posix) {
    Files.createTempDirectory(keys, STAGING_PREFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
  } else {
    Files.createTempDirectory(keys, STAGING_PREFIX)
  }
  try {
    val stagedPriv = stage.resolve(PRIVATE_KEY_FILE)
    writeNew(stagedPriv, privBytes, if (posix) OWNER_ONLY_FILE else null)
    // CHECKED BEFORE IT IS ANYWHERE A SIGNER LOOKS. What was requested is not
    // evidence; what the filesystem reports back is.
    when (val p = protectionOf(stagedPriv, stage, requested = OWNER_ONLY_FILE)) {
      KeyProtection.OwnerOnly -> Unit
      is KeyProtection.Exposed -> throw SigningKeyException(
        "will not create a signing key in $keys: ${p.detail}. This filesystem does not enforce permissions. $NOT_CREATED",
      )
      is KeyProtection.Unverified -> throw SigningKeyException(
        "will not create a signing key in $keys: ${p.detail}, so it cannot be made or checked owner-only. " +
          "Nothing was created. Put this app's store on a filesystem with POSIX permissions " +
          "(PORTAL_STORE, or \"store\" in keliver.portal.json).",
      )
    }
    val stagedPub = stage.resolve(PUBLIC_KEY_FILE)
    writeNew(stagedPub, pubBytes, null)

    val priv = keys.resolve(PRIVATE_KEY_FILE)
    val pub = keys.resolve(PUBLIC_KEY_FILE)
    place(stagedPriv, priv)
    try {
      place(stagedPub, pub)
    } catch (e: Exception) {
      // Do not leave half an identity: withdraw the private key this call just
      // placed — and only if it is still byte-for-byte the one it placed.
      runCatching { if (Files.readAllBytes(priv).contentEquals(privBytes)) Files.delete(priv) }
      throw e
    }
  } finally {
    deleteTree(stage)
  }
}

/** O_CREAT|O_EXCL with [perms] as the creation mode, written and synced. */
private fun writeNew(path: Path, bytes: ByteArray, perms: Set<PosixFilePermission>?) {
  val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
  val channel = if (perms != null) {
    FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(perms))
  } else {
    FileChannel.open(path, options)
  }
  channel.use { c ->
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) c.write(buffer)
    c.force(true)
  }
}

/**
 * Into place WITHOUT replacing anything. A hard link is atomic and fails if the
 * name exists, so a concurrent start that got there first wins and this one
 * refuses. Where links are unsupported, a move that refuses an existing target.
 */
private fun place(staged: Path, target: Path) {
  try {
    try {
      Files.createLink(target, staged)
    } catch (e: FileAlreadyExistsException) {
      throw e
    } catch (_: UnsupportedOperationException) {
      Files.move(staged, target)
    } catch (_: IOException) {
      Files.move(staged, target)
    }
  } catch (_: FileAlreadyExistsException) {
    throw SigningKeyException(
      "$target appeared while this start was generating a signing key — another relay for this app is " +
        "starting. Nothing was replaced; start one relay at a time.",
    )
  }
}

private fun deleteTree(path: Path) {
  if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
  if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
    Files.newDirectoryStream(path).use { children -> children.forEach { deleteTree(it) } }
  }
  Files.deleteIfExists(path)
}

private fun halfIdentity(keysDir: File, missing: String): String {
  val present = if (missing == PUBLIC_KEY_FILE) PRIVATE_KEY_FILE else PUBLIC_KEY_FILE
  return "$keysDir holds $present but not $missing. That is half of this app's signing identity, and " +
    "the other half cannot be made without replacing this one — the relay used to do exactly that, " +
    "silently. Nothing was changed. Restore $missing from a backup of this store, or, to start a NEW " +
    "identity on purpose, move $keysDir aside (hosts built with the old public key will then refuse " +
    "bundles signed with the new one)."
}

private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
