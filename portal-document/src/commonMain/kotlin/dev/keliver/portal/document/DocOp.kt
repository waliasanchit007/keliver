package dev.keliver.portal.document

import kotlinx.serialization.Serializable

/** Attribution for audit + undo grouping. */
@Serializable
data class OpEnvelope(
  val session: String,
  val atMillis: Long,
  val label: String? = null,
)

/**
 * The op vocabulary (design §2). Positions are SIBLING-ANCHORED: `after` is
 * the preceding sibling's handle, null = first child. Never integer indexes.
 */
@Serializable
sealed interface DocOp {
  @Serializable data class InsertNode(val parent: Handle, val after: Handle?, val node: DocNode) : DocOp
  @Serializable data class DeleteNode(val target: Handle) : DocOp
  @Serializable data class MoveNode(val target: Handle, val newParent: Handle, val after: Handle?) : DocOp
  @Serializable data class SetProp(val target: Handle, val name: String, val value: PropValue) : DocOp
  @Serializable data class RemoveProp(val target: Handle, val name: String) : DocOp
  @Serializable data class SetModifier(val target: Handle, val name: String, val value: PropValue) : DocOp
  @Serializable data class RemoveModifier(val target: Handle, val name: String) : DocOp
  @Serializable data class RenameId(val target: Handle, val id: String?) : DocOp
  @Serializable data class ReplaceRaw(val target: Handle, val text: String) : DocOp
  @Serializable data class ContractEdit(val contract: Contract) : DocOp
}

/** A transaction: all-or-nothing, one version bump, one undo entry. */
@Serializable
data class OpBatch(
  val baseVersion: Long,
  val envelope: OpEnvelope,
  val ops: List<DocOp>,
)

@Serializable
data class OpAck(
  val ok: Boolean,
  val version: Long,
  val error: String? = null,
)
