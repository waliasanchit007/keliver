/**
 * A QR code encoder (ISO/IEC 18004, byte mode, error correction level M,
 * versions 1–10), so the editor can show a QR code for the device proxy
 * without a network service or a library. Enough for any URL of up to 213
 * bytes; a longer text is refused.
 */
internal class QrCode private constructor(val size: Int, private val dark: Array<BooleanArray>) {
  fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y][x]

  /** An SVG of this code with a [quiet]-module light border; one path, no text, no script. */
  fun toSvg(quiet: Int = 4): String {
    val d = StringBuilder()
    for (y in 0 until size) for (x in 0 until size) if (dark[y][x]) d.append("M${x + quiet},${y + quiet}h1v1h-1z")
    val n = size + 2 * quiet
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $n $n\" shape-rendering=\"crispEdges\">" +
      "<rect width=\"$n\" height=\"$n\" fill=\"#fff\"/><path d=\"$d\" fill=\"#000\"/></svg>"
  }

  companion object {
    private const val MAX_VERSION = 10

    // Level M, by version (index 0 unused).
    private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    fun encode(text: String): QrCode {
      val bytes = text.encodeToByteArray()
      val version = (1..MAX_VERSION).firstOrNull { v -> 4 + countBits(v) + bytes.size * 8 <= dataCodewords(v) * 8 }
        ?: throw IllegalArgumentException("too long for a QR code here (${bytes.size} bytes)")
      val data = dataCodewords(version, bytes)
      val all = withErrorCorrection(version, data)
      val size = version * 4 + 17
      val m = Matrix(size)
      m.drawFunctionPatterns(version)
      m.drawCodewords(all)
      var best: Matrix? = null
      var bestPenalty = Int.MAX_VALUE
      for (mask in 0 until 8) {
        val c = m.copy()
        c.applyMask(mask)
        c.drawFormat(mask)
        val p = c.penalty()
        if (p < bestPenalty) { bestPenalty = p; best = c }
      }
      return QrCode(size, best!!.dark)
    }

    private fun countBits(version: Int) = if (version <= 9) 8 else 16

    private fun rawModules(v: Int): Int {
      var r = (16 * v + 128) * v + 64
      if (v >= 2) {
        val n = v / 7 + 2
        r -= (25 * n - 10) * n - 55
        if (v >= 7) r -= 36
      }
      return r
    }

    private fun dataCodewords(v: Int) = rawModules(v) / 8 - ECC_PER_BLOCK[v] * NUM_BLOCKS[v]

    private fun dataCodewords(version: Int, bytes: ByteArray): IntArray {
      val bits = ArrayList<Boolean>()
      fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
      put(0b0100, 4)
      put(bytes.size, countBits(version))
      for (b in bytes) put(b.toInt() and 0xff, 8)
      val capacity = dataCodewords(version) * 8
      put(0, minOf(4, capacity - bits.size))
      put(0, (8 - bits.size % 8) % 8)
      var pad = 0xEC
      while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
      return IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) } }
    }

    private fun withErrorCorrection(v: Int, data: IntArray): IntArray {
      val numBlocks = NUM_BLOCKS[v]
      val eccLen = ECC_PER_BLOCK[v]
      val raw = rawModules(v) / 8
      val numShort = numBlocks - raw % numBlocks
      val shortLen = raw / numBlocks
      val divisor = rsDivisor(eccLen)
      val blocks = ArrayList<IntArray>()
      var k = 0
      for (i in 0 until numBlocks) {
        val len = shortLen - eccLen + if (i < numShort) 0 else 1
        val dat = data.copyOfRange(k, k + len)
        k += len
        val ecc = rsRemainder(dat, divisor)
        // A short block gets a placeholder so the columns line up.
        blocks += if (i < numShort) dat + intArrayOf(0) + ecc else dat + ecc
      }
      val out = ArrayList<Int>()
      for (i in 0..shortLen) for (j in 0 until numBlocks) {
        if (i != shortLen - eccLen || j >= numShort) out += blocks[j][i]
      }
      return out.toIntArray()
    }

    private fun gfMul(x: Int, y: Int): Int {
      var z = 0
      for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((y ushr i) and 1) * x)
      }
      return z
    }

    private fun rsDivisor(degree: Int): IntArray {
      val r = IntArray(degree)
      r[degree - 1] = 1
      var root = 1
      repeat(degree) {
        for (j in 0 until degree) {
          r[j] = gfMul(r[j], root)
          if (j + 1 < degree) r[j] = r[j] xor r[j + 1]
        }
        root = gfMul(root, 0x02)
      }
      return r
    }

    private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
      val r = IntArray(divisor.size)
      for (b in data) {
        val factor = b xor r[0]
        System.arraycopy(r, 1, r, 0, r.size - 1)
        r[r.size - 1] = 0
        for (i in r.indices) r[i] = r[i] xor gfMul(divisor[i], factor)
      }
      return r
    }
  }

  private class Matrix(val size: Int) {
    var dark = Array(size) { BooleanArray(size) }
    var function = Array(size) { BooleanArray(size) }

    fun copy(): Matrix = Matrix(size).also { c ->
      c.dark = Array(size) { dark[it].copyOf() }
      c.function = function
    }

    fun set(x: Int, y: Int, on: Boolean) { dark[y][x] = on; function[y][x] = true }

    fun drawFunctionPatterns(version: Int) {
      for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
      finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
      val pos = alignment(version)
      val n = pos.size
      for (i in 0 until n) for (j in 0 until n) {
        if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
        for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
      }
      drawFormat(0) // reserves the format areas; redrawn per mask
      if (version >= 7) {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
          val on = (bits ushr i) and 1 == 1
          val a = size - 11 + i % 3
          val b = i / 3
          set(a, b, on); set(b, a, on)
        }
      }
    }

    private fun finder(cx: Int, cy: Int) {
      for (dy in -4..4) for (dx in -4..4) {
        val x = cx + dx
        val y = cy + dy
        if (x in 0 until size && y in 0 until size) {
          val dist = maxOf(Math.abs(dx), Math.abs(dy))
          set(x, y, dist != 2 && dist != 4)
        }
      }
    }

    private fun alignment(v: Int): IntArray {
      if (v == 1) return IntArray(0)
      val n = v / 7 + 2
      val step = (v * 4 + n * 2 + 1) / (n * 2 - 2) * 2
      val r = IntArray(n)
      r[0] = 6
      var p = size - 7
      for (i in n - 1 downTo 1) { r[i] = p; p -= step }
      return r
    }

    fun drawFormat(mask: Int) {
      val data = (0 shl 3) or mask // level M's format bits are 00
      var rem = data
      repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
      val bits = ((data shl 10) or rem) xor 0x5412
      fun bit(i: Int) = (bits ushr i) and 1 == 1
      for (i in 0..5) set(8, i, bit(i))
      set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
      for (i in 9 until 15) set(14 - i, 8, bit(i))
      for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
      for (i in 8 until 15) set(8, size - 15 + i, bit(i))
      set(8, size - 8, true)
    }

    fun drawCodewords(data: IntArray) {
      var i = 0
      var right = size - 1
      while (right >= 1) {
        if (right == 6) right = 5
        for (vert in 0 until size) for (j in 0..1) {
          val x = right - j
          val upward = ((right + 1) and 2) == 0
          val y = if (upward) size - 1 - vert else vert
          if (!function[y][x] && i < data.size * 8) {
            dark[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
            i++
          }
        }
        right -= 2
      }
    }

    fun applyMask(mask: Int) {
      for (y in 0 until size) for (x in 0 until size) {
        if (function[y][x]) continue
        val flip = when (mask) {
          0 -> (x + y) % 2 == 0
          1 -> y % 2 == 0
          2 -> x % 3 == 0
          3 -> (x + y) % 3 == 0
          4 -> (x / 3 + y / 2) % 2 == 0
          5 -> x * y % 2 + x * y % 3 == 0
          6 -> (x * y % 2 + x * y % 3) % 2 == 0
          else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }
        if (flip) dark[y][x] = !dark[y][x]
      }
    }

    /** Rules 1, 2 and 4 of the standard's penalty: any mask decodes; this only picks a cleaner one. */
    fun penalty(): Int {
      var p = 0
      for (horizontal in listOf(true, false)) for (a in 0 until size) {
        var run = 1
        for (b in 1 until size) {
          val cur = if (horizontal) dark[a][b] else dark[b][a]
          val prev = if (horizontal) dark[a][b - 1] else dark[b - 1][a]
          if (cur == prev) run++ else { if (run >= 5) p += run - 2; run = 1 }
        }
        if (run >= 5) p += run - 2
      }
      for (y in 0 until size - 1) for (x in 0 until size - 1) {
        val c = dark[y][x]
        if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) p += 3
      }
      val darkCount = dark.sumOf { row -> row.count { it } }
      val total = size * size
      p += (Math.abs(darkCount * 20 - total * 10) + total - 1) / total * 10
      return p
    }
  }
}
