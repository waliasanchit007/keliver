package dev.keliver.portal.ingest

import dev.keliver.portal.document.Contract
import dev.keliver.portal.document.DocNode
import dev.keliver.portal.document.Handle
import dev.keliver.portal.document.UiDocument

/**
 * Maps a freshly-parsed tree (temp negative handles) onto the live document,
 * PRESERVING handles for matched nodes so editor selections, pending ops, and
 * SSE clients survive external file edits. Matching per design §1: explicitId
 * first, then in-order greedy by type, then leftovers = insert/delete.
 */
object Reconciler {
  fun reconcile(old: UiDocument, parsed: Recognized): UiDocument {
    var next = old.nextHandle
    fun alloc(): Handle = Handle(next++)

    fun match(oldNode: DocNode?, newNode: DocNode): DocNode = when (newNode) {
      is DocNode.RawCode -> {
        val handle = (oldNode as? DocNode.RawCode)?.handle ?: alloc()
        newNode.copy(handle = handle)
      }
      is DocNode.Widget -> {
        val oldWidget = oldNode as? DocNode.Widget
        val handle = if (oldWidget != null && oldWidget.type == newNode.type) oldWidget.handle else alloc()
        val oldChildren = (oldWidget?.children ?: emptyList()).toMutableList()

        val matchedChildren = newNode.children.map { newChild ->
          // explicitId match first
          val byId = if (newChild is DocNode.Widget && newChild.explicitId != null) {
            oldChildren.filterIsInstance<DocNode.Widget>().firstOrNull { it.explicitId == newChild.explicitId }
          } else {
            null
          }
          // else: first unconsumed old child of the same shape
          val candidate = byId ?: oldChildren.firstOrNull { oc ->
            when (newChild) {
              is DocNode.Widget -> oc is DocNode.Widget && oc.type == newChild.type
              is DocNode.RawCode -> oc is DocNode.RawCode
            }
          }
          if (candidate != null) oldChildren.remove(candidate)
          match(candidate, newChild)
        }
        newNode.copy(handle = handle, children = matchedChildren)
      }
    }

    val newRoot = match(old.root, parsed.root)
    return UiDocument(
      screen = old.screen,
      root = newRoot,
      contract = parsed.contract,
      version = old.version + 1,
      nextHandle = next,
    )
  }
}
