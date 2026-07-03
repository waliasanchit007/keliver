package dev.keliver.portalpublished

/**
 * HAND-WRITTEEN side of the round-trip boundary — the portal generates the
 * screen + interface; engineers implement the data/logic here. The portal
 * never touches this file.
 */
object PublishedBindings : PublishedScreenBindings {
  override val text: String = "Compiled + SIGNED Kotlin from the portal"
  override fun buyTapped() {
    println("published screen: buyTapped")
  }
}
