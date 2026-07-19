/*
 * web-spike = konduit's OWN portal-editor executable: the reusable shell
 * (:portal-editor, runPortalEditor) + this repo's app (AppLibPreview — Field
 * Notes / settings presenters; AppLibFlows — the Field Notes flow). A consumer
 * app's editor is the same one-liner with its own entries — that substitution
 * is the whole of "per-app preview". See docs/ROADMAP.md item ② and #13 F2.
 *
 * runPortalEditor + the entries are all in the root package (the shell across
 * the module boundary, the app in this module), so none needs an import.
 */
fun main() = runPortalEditor(AppLibPreview, flows = AppLibFlows)
