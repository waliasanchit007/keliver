/*
 * The behaviour of a host feature this line declares, in this line's own test file (MS4-04; the
 * review of NF5 H0, #316: a feature's behavioural check goes beside its line's files, never into
 * the shared HostFeaturesTest.kt, so a later line's feature changes no older line's host). The
 * Android scaffolder copies it into the host's JVM tests; Keliver's own build runs it against this
 * checkout's libraries (keliver-treehouse-host-composeui's jvmTest).
 */
package @@PACKAGE@@

import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.ModifierTag
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.WidgetTag
import dev.keliver.treehouse.SchemaMismatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `keliver.schemaMismatchReports@1`: this host declares it (HostFeatures.kt), and its code-load
 * listener (KeliverLine.kt's KeliverEventListener, which the host's own listener extends) hands
 * each schema mismatch Treehouse names to the host as one report detail. Treehouse names each
 * mismatch once per tag for a load; the host reports what it is handed, with the reason
 * `schema-mismatch`.
 */
class SchemaMismatchReportsTest {
  private class Recording : KeliverEventListener() {
    val details = mutableListOf<String>()

    override fun onSchemaMismatch(detail: String) {
      details += detail
    }
  }

  @Test
  fun thisLineDeclaresSchemaMismatchReports() {
    assertTrue("keliver.schemaMismatchReports@1" in HostFeatures.implemented, "${HostFeatures.implemented}")
  }

  @Test
  fun eachMismatchTreehouseNamesBecomesOneReportDetail() {
    val listener = Recording()

    listener.schemaMismatch(SchemaMismatch.Widget(WidgetTag(41)))
    listener.schemaMismatch(SchemaMismatch.Widget(WidgetTag(42)))
    listener.schemaMismatch(SchemaMismatch.Modifier(ModifierTag(7)))
    listener.schemaMismatch(SchemaMismatch.Property(WidgetTag(12), PropertyTag(3)))
    listener.schemaMismatch(SchemaMismatch.Children(WidgetTag(12), ChildrenTag(2)))
    listener.schemaMismatch(SchemaMismatch.Event(WidgetTag(12), EventTag(4)))

    assertEquals(
      listOf(
        "unknown widget 41",
        "unknown widget 42",
        "unknown modifier 7",
        "unknown property 3 of widget 12",
        "unknown children 2 of widget 12",
        "unknown event 4 of widget 12",
      ),
      listener.details,
    )
  }
}
