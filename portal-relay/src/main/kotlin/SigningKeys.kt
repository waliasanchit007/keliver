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
import java.nio.file.attribute.UserPrincipal
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
 *   created O_EXCL with mode 0600, EMPTY, inside a staging directory that is
 *   itself 0700; its mode is read back and checked; only then are the key bytes
 *   written and synced, and the file hard-linked into `keys/`. Never
 *   write-then-chmod: in between, the key is readable, and a reader that opened
 *   it in that window keeps what it read. A umask can only remove bits, so no
 *   umask widens this.
 *
 * - A key is created only when protection is MEASURED to hold. On a filesystem
 *   that ignores modes (FAT) the staged file reads back wider than 0600, and a
 *   macOS volume mounted `noowners` makes every local user its owner; either
 *   way the empty staged file is discarded and the start fails saying so.
 *   Nothing is reported as protected on the strength of what was requested
 *   rather than what was read back.
 *
 * - An EXISTING key is never modified, re-created or rotated here, and its mode
 *   is never changed. Tightening an exposed key cannot undo the exposure, so
 *   whether to keep or rotate that identity is the owner's decision; this
 *   reports the measured state and the exact commands that keep the identity.
 *   Refusing to publish with it is the relay's job: see [keyProtection].
 *
 * - Half an identity is never completed by generating the other half (U29).
 *   The old code regenerated BOTH files whenever either was missing, replacing
 *   a private key, or a public key hosts had already embedded. Now nothing is
 *   generated, the relay still starts — editing does not use the key — and
 *   [keyProtection] reports it, which refuses publishing.
 *
 * Nothing here prints, logs or returns key material; messages carry paths,
 * modes and owners only.
 */

internal const val PRIVATE_KEY_FILE = "ed25519.priv"
internal const val PUBLIC_KEY_FILE = "ed25519.pub"

/** Staging directories for a generation in flight. Only this file creates them. */
private const val STAGING_PREFIX = ".keygen-"

private const val NO_KEY_CREATED = "No key was created. Put this app's store on a filesystem that enforces " +
  "permissions (PORTAL_STORE, or \"store\" in keliver.portal.json)."

private val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
private val OWNER_ONLY_DIR: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
private val PUBLIC_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-r--r--")
private val GROUP_OR_OTHER: Set<PosixFilePermission> = setOf(
  PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
  PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
)
private val GROUP_OR_OTHER_WRITE: Set<PosixFilePermission> =
  setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)

/** What was MEASURED about the signing identity's protection. */
internal sealed interface KeyProtection {
  /**
   * Both files present; the private key has no group or other bits; `keys/`, the
   * public key and the key itself cannot be written by other users; the store
   * directory is not world-writable; the key and `keys/` are this user's.
   */
  data object OwnerOnly : KeyProtection

  /**
   * Other users can read the key or replace part of the identity. [detail] says
   * how. [chmodFixes] is false when no chmod can help — the filesystem does not
   * apply modes or ignores ownership, or another user owns the files.
   */
  data class Exposed(val detail: String, val chmodFixes: Boolean = true) : KeyProtection

  /** The filesystem gives nothing to check, so nothing can be said either way. */
  data class Unverified(val detail: String) : KeyProtection

  /** One of the two files is missing (U29). [detail] says which, and what to do. */
  data class Incomplete(val detail: String) : KeyProtection
}

/** A refusal. Everything that existed before the call is as it was. */
internal class SigningKeyException(message: String) : Exception(message)

/**
 * Makes sure [keysDir] holds a signing identity, generating one only when it
 * holds NEITHER file. Returns true when it generated one. Half an identity is
 * left as it is and reported by [keyProtection].
 */
internal fun ensureSigningKeys(keysDir: File): Boolean {
  val keys = keysDir.toPath()
  val createdDir = createKeysDir(keys)
  removeInterruptedGenerations(keys)
  val hasPriv = Files.exists(keys.resolve(PRIVATE_KEY_FILE), LinkOption.NOFOLLOW_LINKS)
  val hasPub = Files.exists(keys.resolve(PUBLIC_KEY_FILE), LinkOption.NOFOLLOW_LINKS)
  if (hasPriv || hasPub) return false
  generate(keys, createdDir)
  return true
}

/**
 * The identity's protection as the filesystem reports it now, or null when
 * [keysDir] holds neither file (a store with no identity; its publish is, as it
 * always was, unsigned). Modes that cannot be read are [KeyProtection.Unverified]
 * — never assumed fine.
 */
internal fun keyProtection(keysDir: File): KeyProtection? {
  val keys = keysDir.toPath()
  val priv = keys.resolve(PRIVATE_KEY_FILE)
  val pub = keys.resolve(PUBLIC_KEY_FILE)
  val hasPriv = Files.exists(priv, LinkOption.NOFOLLOW_LINKS)
  val hasPub = Files.exists(pub, LinkOption.NOFOLLOW_LINKS)
  if (!hasPriv && !hasPub) return null
  if (!hasPriv) return KeyProtection.Incomplete(missingPrivate(keysDir))
  if (!hasPub) return KeyProtection.Incomplete(missingPublic(keysDir))
  return try {
    protectionOf(priv, keys)
  } catch (e: IOException) {
    KeyProtection.Unverified("the modes of $priv could not be read ($e)")
  }
}

/** `rw-------`, or `unknown` where the filesystem has no POSIX modes. For messages only. */
internal fun modeString(path: File): String = runCatching {
  PosixFilePermissions.toString(Files.getPosixFilePermissions(path.toPath()))
}.getOrDefault("unknown")

/**
 * The commands that keep this identity and make it owner-only. Nothing is
 * rotated: they change modes only. Paths are single-quoted for the shell, with
 * any `'` in them closed, escaped and reopened.
 */
internal fun tightenCommands(keysDir: File): String {
  val store = keysDir.absoluteFile.parentFile
  return "chmod o-w ${shq(store.path)} && chmod 700 ${shq(keysDir.path)} && " +
    "chmod 600 ${shq(File(keysDir, PRIVATE_KEY_FILE).path)} && chmod go-w ${shq(File(keysDir, PUBLIC_KEY_FILE).path)}"
}

internal fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * POSIX modes are there to check when the path has a POSIX attribute view.
 * Asked of the path, not of `Files.getFileStore`, which can throw "mount point
 * not found" in some Linux containers and would make a sound store Unverified.
 */
private fun supportsPosix(path: Path): Boolean =
  Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null

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
  val ownership = ownershipIgnored(key)
  if (ownership is Ownership.Unknown) return KeyProtection.Unverified(ownership.detail)
  val keyPerms = Files.getPosixFilePermissions(key)
  val dirPerms = Files.getPosixFilePermissions(dir)
  val me = currentUser()
  // What no chmod can fix: the filesystem, or who owns the files.
  val unfixable = buildList {
    if (ownership is Ownership.Ignored) add(ownership.detail)
    if (requested != null && !requested.containsAll(keyPerms)) {
      add(
        "${key.fileName} reads back ${PosixFilePermissions.toString(keyPerms)} though it was created " +
          "${PosixFilePermissions.toString(requested)}: the filesystem does not apply the modes it is given",
      )
    }
    for (p in listOf(key, dir)) {
      val owner = Files.getOwner(p)
      if (owner != me) add("${p.fileName} is owned by ${owner.name}, not by ${me.name}, who runs this relay")
    }
  }
  val fixable = buildList {
    if (keyPerms.any { it in GROUP_OR_OTHER }) {
      add("${key.fileName} is ${PosixFilePermissions.toString(keyPerms)}: other users can ${access(keyPerms)} it")
    }
    if (dirPerms.any { it in GROUP_OR_OTHER_WRITE }) {
      add("${dir.fileName}/ is ${PosixFilePermissions.toString(dirPerms)}: other users can replace the key")
    }
    val pub = dir.resolve(PUBLIC_KEY_FILE)
    if (Files.exists(pub, LinkOption.NOFOLLOW_LINKS)) {
      val pubPerms = Files.getPosixFilePermissions(pub)
      if (pubPerms.any { it in GROUP_OR_OTHER_WRITE }) {
        add("$PUBLIC_KEY_FILE is ${PosixFilePermissions.toString(pubPerms)}: other users can replace the key hosts embed")
      }
    }
    // The store directory: world-writable (and not sticky) means another user
    // can move keys/ aside and put their own identity there. Group-write is not
    // flagged, deliberately: under a user-private-group umask (002, the default
    // on Debian and Ubuntu) every directory is group-writable by a group that is
    // only this user.
    val store = dir.toAbsolutePath().parent
    if (store != null && worldWritableNotSticky(store)) {
      add("the store directory ${store.fileName}/ is writable by every user: another user can replace keys/")
    }
  }
  val problems = unfixable + fixable
  return if (problems.isEmpty()) {
    KeyProtection.OwnerOnly
  } else {
    KeyProtection.Exposed(problems.joinToString("; "), chmodFixes = unfixable.isEmpty())
  }
}

private fun worldWritableNotSticky(dir: Path): Boolean {
  val mode = runCatching { Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int }.getOrNull()
  if (mode != null) return (mode and 0b010) != 0 && (mode and 0b1000000000) == 0
  return PosixFilePermission.OTHERS_WRITE in Files.getPosixFilePermissions(dir)
}

/** The user this process runs as: the owner of a file it has just created. */
private val currentUserPrincipal: UserPrincipal by lazy {
  val probe = Files.createTempFile("keliver-owner-", ".probe")
  try {
    Files.getOwner(probe)
  } finally {
    Files.deleteIfExists(probe)
  }
}

private fun currentUser(): UserPrincipal = currentUserPrincipal

/**
 * macOS mounts external and disk-image volumes `noowners` (Finder: "Ignore
 * ownership on this volume"): every local user is then treated as the owner of
 * every file, so an owner-only mode protects nothing. MEASURED on a FAT image
 * attached with hdiutil: `ls` showed rwx------ owned by whoever looked. The JVM
 * cannot see mount flags, so this asks `mount`, and a `mount` that cannot be
 * read is not taken as "no". The volume is the one holding the key's REAL
 * path, found by its device where the listing names it.
 */
private fun ownershipIgnored(key: Path): Ownership {
  if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return Ownership.Enforced
  val real = runCatching { key.toRealPath() }
    .getOrElse { return Ownership.Unknown("the real path of $key could not be read ($it)") }
  val device = runCatching { Files.getFileStore(real).name() }.getOrNull()
  val listing = runCatching {
    val p = ProcessBuilder("/sbin/mount").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "exit ${p.exitValue()}" }
    out
  }.getOrElse { return Ownership.Unknown("the mount table could not be read to check for ignored ownership ($it)") }
  return noownersMountFor(listing, device, real.toString())?.let {
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
 * The mount point holding a path, if `mount`'s [listing] marks it `noowners`.
 * Lines read "<device> on <mount point> (<flag>, <flag>, ...)". The mount is the
 * one whose device is [device] when exactly one line names it — that sees
 * through macOS firmlinks, where `/Users/...` lives on `/System/Volumes/Data`
 * but is prefixed by `/` — and otherwise the longest mount point prefixing
 * [realPath].
 */
internal fun noownersMountFor(listing: String, device: String?, realPath: String): String? {
  val line = Regex("^(.+?) on (/.*) \\(([^()]*)\\)$")
  val mounts = listing.lines().mapNotNull { line.find(it) }
    .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
  val byDevice = if (device != null) mounts.filter { it.first == device } else emptyList()
  val holder = byDevice.singleOrNull()
    ?: mounts
      .filter { (_, point, _) -> realPath == point || realPath.startsWith(point.trimEnd('/') + "/") }
      .maxByOrNull { (_, point, _) -> point.length }
    ?: return null
  return holder.second.takeIf { "noowners" in holder.third.split(',').map { f -> f.trim() } }
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
            "reports $dirPerms. This filesystem does not enforce permissions. $NO_KEY_CREATED"
        } else {
          "will not create a signing key in $keys: it is $dirPerms, so other users could replace the key. " +
            "No key was created. Make it owner-only (chmod 700 ${shq(keys.toString())}) and start again."
        },
      )
    }
  }
  val posix = supportsPosix(keys)
  // createTempDirectory is 0700 on POSIX; stated, not assumed.
  val stage = if (posix) {
    Files.createTempDirectory(keys, STAGING_PREFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
  } else {
    Files.createTempDirectory(keys, STAGING_PREFIX)
  }
  try {
    val stagedPriv = stage.resolve(PRIVATE_KEY_FILE)
    // CREATED EMPTY, AND CHECKED BEFORE A KEY BYTE IS WRITTEN. What was
    // requested is not evidence; what the filesystem reports back is. On a
    // refused filesystem the key's bytes never reach the medium.
    val channel = openNew(stagedPriv, if (posix) OWNER_ONLY_FILE else null)
    channel.use { c ->
      when (val p = protectionOf(stagedPriv, stage, requested = OWNER_ONLY_FILE)) {
        KeyProtection.OwnerOnly -> Unit
        is KeyProtection.Exposed -> throw SigningKeyException(
          "will not create a signing key in $keys: ${p.detail}. This filesystem does not enforce permissions. $NO_KEY_CREATED",
        )
        is KeyProtection.Unverified -> throw SigningKeyException(
          "will not create a signing key in $keys: ${p.detail}, so it cannot be made or checked owner-only. " +
            "No key was created. Put this app's store on a filesystem with POSIX permissions " +
            "(PORTAL_STORE, or \"store\" in keliver.portal.json).",
        )
        is KeyProtection.Incomplete -> error("unreachable: a single staged file is not an identity")
      }
      val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      // Zipline wants raw 32-byte Ed25519 keys as hex; the JDK wraps them in
      // PKCS#8/X.509, and the raw key is the last 32 bytes. Same bytes, same
      // format, as before U27: no trailing newline, and readers trim.
      fun raw32(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size)
      val privBytes = hex(raw32(kp.private.encoded)).toByteArray()
      writeAll(c, privBytes)
      val stagedPub = stage.resolve(PUBLIC_KEY_FILE)
      openNew(stagedPub, if (posix) PUBLIC_FILE else null).use { writeAll(it, hex(raw32(kp.public.encoded)).toByteArray()) }

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
    }
  } finally {
    deleteTree(stage)
  }
}

/** O_CREAT|O_EXCL with [perms] as the creation mode. */
private fun openNew(path: Path, perms: Set<PosixFilePermission>?): FileChannel {
  val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
  return if (perms != null) {
    FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(perms))
  } else {
    FileChannel.open(path, options)
  }
}

private fun writeAll(c: FileChannel, bytes: ByteArray) {
  val buffer = ByteBuffer.wrap(bytes)
  while (buffer.hasRemaining()) c.write(buffer)
  c.force(true)
}

/**
 * Into place WITHOUT replacing anything: a hard link is atomic and fails if the
 * name exists, so a concurrent start that got there first wins and this one
 * refuses. There is no fallback. A move without REPLACE_EXISTING is a check and
 * then rename(2), and rename(2) replaces — the one thing this must not do.
 */
private fun place(staged: Path, target: Path) {
  try {
    Files.createLink(target, staged)
  } catch (_: FileAlreadyExistsException) {
    throw SigningKeyException(
      "$target appeared while this start was generating a signing key — another relay for this app is " +
        "starting. Nothing was replaced; start one relay at a time.",
    )
  } catch (e: Exception) {
    if (e !is IOException && e !is UnsupportedOperationException) throw e
    throw SigningKeyException(
      "could not place a new signing key at $target: this filesystem did not create a hard link ($e), and " +
        "without one the key cannot be put in place without risking replacing another. $NO_KEY_CREATED",
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

private fun missingPublic(keysDir: File): String =
  "$keysDir holds $PRIVATE_KEY_FILE but not $PUBLIC_KEY_FILE: half of this app's signing identity. " +
    "Nothing was generated — the relay used to generate a whole new pair here, silently replacing the " +
    "private key (U29). The public key is determined by the private key, so this identity is recoverable: " +
    "restore $PUBLIC_KEY_FILE from a backup of this store. Keliver does not derive it for you."

private fun missingPrivate(keysDir: File): String =
  "$keysDir holds $PUBLIC_KEY_FILE but not $PRIVATE_KEY_FILE: half of this app's signing identity, and " +
    "the half that signs. Nothing was generated — the relay used to generate a whole new pair here, " +
    "silently replacing the public key that hosts embed (U29). A private key cannot be recovered from the " +
    "public one: restore it from a backup, or, to begin a NEW identity on purpose, move $keysDir aside " +
    "(hosts built with the old public key will then refuse bundles signed with the new one)."

private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
