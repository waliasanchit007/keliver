package dev.keliver.portal.document

import kotlinx.serialization.json.Json

/** One wire Json for documents/ops — sealed types carry class discriminators. */
public val DocJson: Json = Json {
  ignoreUnknownKeys = true
  classDiscriminator = "kind"
  encodeDefaults = false
}
