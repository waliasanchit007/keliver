package dev.keliver.portal.document

import kotlinx.serialization.Serializable

/** Attribution for audit + undo grouping. */
@Serializable
public data class OpEnvelope(
  val session: String,
  val atMillis: Long,
  val label: String? = null,
)

/**
 * The op vocabulary (design §2). Positions are SIBLING-ANCHORED: `after` is
 * the preceding sibling's handle, null = first child. Never integer indexes.
 */
@Serializable
public sealed interface DocOp {
  @Serializable public data class InsertNode(val parent: Handle, val after: Handle?, val node: DocNode) : DocOp
  @Serializable public data class DeleteNode(val target: Handle) : DocOp
  @Serializable public data class MoveNode(val target: Handle, val newParent: Handle, val after: Handle?) : DocOp
  @Serializable public data class SetProp(val target: Handle, val name: String, val value: PropValue) : DocOp
  @Serializable public data class RemoveProp(val target: Handle, val name: String) : DocOp
  @Serializable public data class SetModifier(val target: Handle, val name: String, val value: PropValue) : DocOp
  @Serializable public data class RemoveModifier(val target: Handle, val name: String) : DocOp
  @Serializable public data class RenameId(val target: Handle, val id: String?) : DocOp
  @Serializable public data class ReplaceRaw(val target: Handle, val text: String) : DocOp
  @Serializable public data class ContractEdit(val contract: Contract) : DocOp
}

/** A transaction: all-or-nothing, one version bump, one undo entry. */
@Serializable
public data class OpBatch(
  val baseVersion: Long,
  val envelope: OpEnvelope,
  val ops: List<DocOp>,
)

@Serializable
public data class OpAck(
  val ok: Boolean,
  val version: Long,
  val error: String? = null,
)
