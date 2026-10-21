import dev.keliver.portal.render.CompiledComponent

/**
 * The tools bundle's GENERIC editor, served for any app without its own
 * editor: no compiled adapters, because they are looked up by component name
 * and these would draw this repo's sample components in place of an app's
 * same-named opaque ones. Opaque components stay placeholders here; an app's
 * own editor (keliver-new-editor.sh) draws them. See web-spike/build.gradle.
 */
internal val flavorCompiledComponents: Map<String, CompiledComponent> = emptyMap()
