/*
 * The guest frame profile (M4; docs/DEVICE_HOST.md, "Timing marks"): spans per frame, gathered
 * into one "frames" line per window by KeliverTiming. Pure Kotlin, the same file in the Android
 * and iOS hosts, and compiled only where KeliverTiming's real marks are (debug and benchmark
 * builds). Not thread-safe: KeliverTiming uses one window from one thread. Scaffolded by
 * keliver-new-production-host.sh and keliver-new-ios-host.sh.
 *
 * A window's line, durations in microseconds as p50/p95/max ("-" when a span has no samples):
 *
 *   n=<frames> empty=<frames that sent no change list> lists=<change lists> changes=<changes>
 *   guest=  the guest's frame: the host's sendFrame call, start to end (composition, applying
 *           to the guest's tree, encoding, and the host taking the change list it sends)
 *   lead=   the frame's start to its change list reaching the host (guest work and encoding,
 *           and the host decoding the list)
 *   sink=   the host's sendChanges: converting the list and queueing it for the main thread
 *   apply=  the host applying the list on the main thread
 *   queue=  the list waiting for the main thread
 *
 * A change list sent outside a frame (a screen's first, from its start) has no lead.
 */
package @@PACKAGE@@

internal class FrameWindow(size: Int = 60) {
  private val guest = Span(size)
  private val lead = Span(size)
  private val sink = Span(size)
  private val apply = Span(size)
  private val queue = Span(size)
  private var frames = 0
  private var empty = 0
  private var lists = 0
  private var changes = 0L

  /** A window ends when any span has [size] samples. */
  val isFull: Boolean get() = guest.isFull || sink.isFull || apply.isFull

  /** A guest frame: its sendFrame call took [guestNanos] and sent [changeLists] change lists. */
  fun frame(guestNanos: Long, changeLists: Int) {
    frames++
    if (changeLists == 0) empty++
    guest.add(guestNanos)
  }

  /** A change list of [count] changes, [leadNanos] after its frame started (null outside a frame). */
  fun changeList(leadNanos: Long?, sinkNanos: Long, count: Int) {
    lists++
    changes += count
    if (leadNanos != null) lead.add(leadNanos)
    sink.add(sinkNanos)
  }

  /** A change list applied on the main thread after waiting [queueNanos] for it. */
  fun applied(queueNanos: Long, applyNanos: Long) {
    queue.add(queueNanos)
    apply.add(applyNanos)
  }

  /** This window's line, starting a new window; null when nothing was recorded. */
  fun take(): String? {
    if (frames == 0 && lists == 0 && apply.count == 0) return null
    val line = "n=$frames empty=$empty lists=$lists changes=$changes " +
      "guest=${guest.take()} lead=${lead.take()} sink=${sink.take()} apply=${apply.take()} queue=${queue.take()}"
    frames = 0
    empty = 0
    lists = 0
    changes = 0L
    return line
  }

  private class Span(size: Int) {
    private val samples = LongArray(size)
    var count = 0
      private set
    val isFull: Boolean get() = count == samples.size

    fun add(nanos: Long) {
      if (count < samples.size) samples[count++] = nanos
    }

    /** "p50/p95/max" in microseconds (nearest rank), or "-"; then empty. */
    fun take(): String {
      if (count == 0) return "-"
      samples.sort(0, count)
      val n = count
      fun rank(percent: Int) = samples[((n * percent + 99) / 100 - 1).coerceIn(0, n - 1)] / 1_000
      count = 0
      return "${rank(50)}/${rank(95)}/${samples[n - 1] / 1_000}"
    }
  }
}
