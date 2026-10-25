import dev.keliver.portal.render.AppFlowEntry
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.ScreenPreview

/**
 * The tools bundle's GENERIC editor, served for any app without its own
 * editor. It carries none of this repo's sample app: compiled adapters are
 * looked up by component name and would draw the sample's components in place
 * of an app's same-named ones, and the sample's presenters, personas and flows
 * would offer (and run, on Live) another app's logic for an app whose screens
 * share their names. Opaque components stay placeholders; an app's own editor
 * (keliver-new-editor.sh) brings its presenters. See web-spike/build.gradle.
 */
internal object GenericPreviewEntry : AppPreviewEntry {
  override val label: String = "app"
  override val screens: Map<String, ScreenPreview> = emptyMap()
}

internal val flavorEntry: AppPreviewEntry = GenericPreviewEntry

internal val flavorFlows: AppFlowEntry? = null
