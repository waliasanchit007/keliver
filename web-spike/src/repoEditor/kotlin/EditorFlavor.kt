import dev.keliver.portal.render.AppFlowEntry
import dev.keliver.portal.render.AppPreviewEntry

/**
 * This repo's own editor (the relay's preview build): its sample app's
 * presenters, personas and flows (its compiled components ride on AppLibPreview). See web-spike/build.gradle.
 */
internal val flavorEntry: AppPreviewEntry = AppLibPreview

internal val flavorFlows: AppFlowEntry? = AppLibFlows
