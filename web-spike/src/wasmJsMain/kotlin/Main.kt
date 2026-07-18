/*
 * web-spike = konduit's OWN portal-editor executable: the reusable shell
 * (:portal-editor, runPortalEditor) + this repo's app (AppLibPreview — Field
 * Notes / settings presenters). A consumer app's editor is the same one-liner
 * with its own AppPreviewEntry — that single substitution is the whole of
 * "per-app preview". See docs/ROADMAP.md item ② (editor-shell separability).
 *
 * runPortalEditor + AppLibPreview are both in the root package (the shell across
 * the module boundary, the app in this module), so neither needs an import.
 */
fun main() = runPortalEditor(AppLibPreview)
