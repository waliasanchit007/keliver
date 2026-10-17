import dev.keliver.portal.acceptsModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** K5: the editor's component registry knows each component's parameter types. */
class EditorComponentsTest {
  @Test fun parameterTypesFromTheRelayDecideAcceptsModifier() {
    val reg = parseComponents(
      """
      [
        {"name":"Card","props":[{"name":"title","kind":"Text","label":"Title"}],"events":[],"slots":[],
         "defaults":{},"paramTypes":{"title":"String","modifier":"Modifier"},"transparent":true,
         "dependencies":[],"body":null},
        {"name":"Plain","props":[],"events":[],"slots":[],"defaults":{},"paramTypes":{},
         "transparent":true,"dependencies":[],"body":null}
      ]
      """.trimIndent(),
    )
    assertEquals(setOf("Card", "Plain"), reg.names())
    assertTrue(reg.spec("Card")!!.acceptsModifier)
    assertFalse(reg.spec("Plain")!!.acceptsModifier)
  }

  @Test fun aRelayWithoutParameterTypesStillParses() {
    val reg = parseComponents(
      """[{"name":"Old","props":[],"events":[],"defaults":{},"transparent":false,"dependencies":[],"body":null}]""",
    )
    assertFalse(reg.spec("Old")!!.acceptsModifier)
  }
}
