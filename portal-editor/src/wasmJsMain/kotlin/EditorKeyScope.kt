/*
 * F1 (feedback round 1): a key that changes the tree — Delete/Backspace, ⌘D,
 * ⌘X, ⌘V — acts on the selection only when the author was last on the tree
 * (the canvas or the outline). A property field that is re-rendered away (a
 * refused write re-reads the document) leaves the keyboard on the page body;
 * a Backspace meant for that field must not then delete the selected node.
 */

/** Where the author last pointed or focused. */
internal enum class KeyScope { Tree, Elsewhere }

/** The scope a pointer press or focus lands in: on the tree unless it is a field, or outside the canvas and outline. */
internal fun keyScopeOf(onCanvasOrOutline: Boolean, isField: Boolean): KeyScope =
  if (onCanvasOrOutline && !isField) KeyScope.Tree else KeyScope.Elsewhere

/** Whether [key] (with ⌘/Ctrl when [mod]) changes the tree rather than moving the selection. */
internal fun changesTheTree(key: String, mod: Boolean): Boolean =
  key == "Delete" || key == "Backspace" || (mod && key.lowercase() in setOf("d", "x", "v"))

/**
 * Whether a tree-changing key may act: only from the tree, and never as a held
 * key's repeat (a Backspace held in a field that went away would delete node
 * after node).
 */
internal fun treeKeyAllowed(scope: KeyScope, repeat: Boolean): Boolean = scope == KeyScope.Tree && !repeat

/**
 * The field to give the keyboard back to after a re-render: the one with the
 * same [label] (its aria-label) when exactly one has it, else the one at the
 * same [index] among the panel's fields when the count is unchanged.
 */
internal fun refocusIndex(label: String?, index: Int, labels: List<String?>, countBefore: Int): Int? {
  if (label != null) labels.withIndex().filter { it.value == label }.singleOrNull()?.let { return it.index }
  return index.takeIf { labels.size == countBefore && it in labels.indices }
}
