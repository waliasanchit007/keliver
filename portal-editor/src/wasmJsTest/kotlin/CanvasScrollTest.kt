import dev.keliver.portal.EmptyComponentRegistry
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.serializeTree
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canvas scrolls a screen taller than the frame, as the app's route does on a device, unless
 * the screen scrolls itself (a vertical scroller measured inside another one fails).
 */
class CanvasScrollTest {
  @AfterTest fun reset() {
    setEditorComponents(EmptyComponentRegistry)
  }

  private fun n(type: String, vararg children: WidgetNode, props: Map<String, Any?> = emptyMap()) =
    WidgetNode(type, props, children.toList())

  @Test fun aPlainScreenIsScrolledByTheCanvas() {
    assertFalse(screenScrollsItself(n("StyledBox", n("Column", n("StyledText"), n("Row")))))
  }

  @Test fun aScreenWithItsOwnVerticalScrollerKeepsIt() {
    assertTrue(screenScrollsItself(n("Column", n("ScrollableColumn"))))
    assertTrue(screenScrollsItself(n("Box", n("LazyVerticalGrid"))))
    assertTrue(screenScrollsItself(n("VerticalPager")))
    assertTrue(screenScrollsItself(n("Column", props = mapOf("overflow" to 1))), "a Column with overflow Scroll")
    assertFalse(screenScrollsItself(n("Column", props = mapOf("overflow" to 0))))
    assertFalse(screenScrollsItself(n("Row", n("HorizontalPager"))), "horizontal scrolling does not count")
  }

  @Test fun aComponentWhoseBodyScrollsCounts() {
    val feedBody = serializeTree(n("ScrollableColumn"))
    val loopBody = serializeTree(n("Loop"))
    setEditorComponents(
      parseComponents(
        """
        [{"name":"Feed","props":[],"events":[],"slots":[],"defaults":{},"paramTypes":{},"transparent":true,
          "dependencies":[],"body":$feedBody},
         {"name":"Loop","props":[],"events":[],"slots":[],"defaults":{},"paramTypes":{},"transparent":true,
          "dependencies":["Loop"],"body":$loopBody}]
        """.trimIndent(),
      ),
    )
    assertTrue(screenScrollsItself(n("Column", n("Feed"))))
    assertFalse(screenScrollsItself(n("Column", n("Loop"))), "a self-referencing component terminates")
  }
}
