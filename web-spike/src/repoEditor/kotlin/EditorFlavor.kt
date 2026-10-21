import dev.keliver.portal.render.CompiledComponent

/**
 * This repo's own editor (the relay's preview build): its opaque sample
 * components draw with their compiled code. See web-spike/build.gradle.
 */
internal val flavorCompiledComponents: Map<String, CompiledComponent> = AppLibCompiledComponents
