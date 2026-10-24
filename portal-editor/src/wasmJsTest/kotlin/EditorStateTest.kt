import dev.keliver.portal.PropKind
import dev.keliver.portal.ScreenContract
import dev.keliver.portal.WidgetNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The State panel: every value the canvas reads, with coherent defaults. The fields `if` blocks
 * test were not mockable at all, and an unknown Boolean reads true, so a screen drew its loading
 * skeleton, its content, its empty state and its error banner at once.
 */
class EditorStateTest {
  private fun cond(field: String, test: String? = null, vararg children: WidgetNode) =
    WidgetNode("Condition", buildMap { put("field", field); if (test != null) put("test", test) }, children.toList())

  private val tree = WidgetNode(
    "Column", emptyMap(),
    listOf(
      cond("loading", null, WidgetNode("Shimmer")),
      cond("showContent", null, WidgetNode("StyledText")),
      cond("showEmptyState", null, WidgetNode("Image")),
      cond("errorMessage", "blank", WidgetNode("StyledText")),
      WidgetNode("Repeat", mapOf("items" to "rows"), listOf(cond("item.flag"))),
    ),
  )
  private val contract = ScreenContract(mapOf("headlineRate" to PropKind.Text, "showContent" to PropKind.Bool), listOf("retry"))

  @Test fun conditionFieldsAreListedWithTheirKindsBeforePropsAndLists() {
    val fields = stateFields(tree, contract)
    assertEquals(
      listOf(
        StateField("loading", StateKind.Bool, StateSource.Condition),
        StateField("showContent", StateKind.Bool, StateSource.Condition),
        StateField("showEmptyState", StateKind.Bool, StateSource.Condition),
        StateField("errorMessage", StateKind.Text, StateSource.Condition),
        StateField("headlineRate", StateKind.Text, StateSource.Prop),
        StateField("rows", StateKind.Rows, StateSource.List),
      ),
      fields,
      "an item field (item.flag) is mocked by its rows, and a field listed twice once",
    )
  }

  @Test fun theLoadedScenarioShowsOneCoherentState() {
    val m = effectiveMocks(stateFields(tree, contract), Scenario.Loaded, emptyMap())
    assertEquals("false", m["loading"])
    assertEquals("true", m["showContent"])
    assertEquals("false", m["showEmptyState"])
    assertEquals("", m["errorMessage"], "no error text: the error banner is hidden")
    assertEquals("12.5%", m["headlineRate"], "a realistic sample, not {headlineRate}")
    assertEquals("3", m["rows"])
  }

  @Test fun theLoadingAndErrorScenariosSwitchTheirFlags() {
    val fields = stateFields(tree, contract)
    val loading = effectiveMocks(fields, Scenario.Loading, emptyMap())
    assertEquals("true", loading["loading"])
    assertEquals("false", loading["showContent"])
    assertEquals("0", loading["rows"])
    val error = effectiveMocks(fields, Scenario.Error, emptyMap())
    assertTrue(error.getValue("errorMessage").isNotBlank())
    assertEquals("false", error["loading"])
  }

  @Test fun whatTheUserSetWinsAndExtraItemMocksAreKept() {
    val m = effectiveMocks(stateFields(tree, contract), Scenario.Loaded, mapOf("loading" to "true", "item.title" to "a|b"))
    assertEquals("true", m["loading"])
    assertEquals("a|b", m["item.title"])
  }

  @Test fun onlyScenariosSomeFlagSwitchesAreOffered() {
    assertEquals(Scenario.entries.toList(), scenariosFor(stateFields(tree, contract)))
    val plain = stateFields(WidgetNode("Column", emptyMap(), listOf(cond("visible"))), ScreenContract(emptyMap(), emptyList()))
    assertEquals(listOf(Scenario.Loaded), scenariosFor(plain))
  }

  @Test fun samplesFollowTheFieldName() {
    assertEquals("1,250", sampleText("memberCount"))
    assertEquals("12 Jan 2027", sampleText("maturityDate"))
    assertEquals("AB", sampleText("initials"))
    assertEquals("", sampleText("logoUrl"))
    assertEquals("Broker name", sampleText("brokerName"))
    assertFalse(sampleText("headlineRate").startsWith("{"))
  }

  @Test fun aScreensStateRoundTripsAndBadStorageFallsBack() {
    val s = ScreenState(Scenario.Error, mapOf("loading" to "true", "item.title" to "a|b"))
    assertEquals(s, decodeScreenState(encodeScreenState(s)))
    assertEquals(ScreenState(), decodeScreenState("not json"))
    assertEquals(ScreenState(), decodeScreenState(null))
    assertEquals(Scenario.Loaded, decodeScreenState("""{"scenario":"Gone","values":{}}""").scenario)
  }

  @Test fun aColourFieldShowsAVisibleSampleColour() {
    assertEquals(0xFFEEF2F7.toInt(), sampleColor("noticeBgArgb"))
    assertEquals(0xFF1F2937.toInt(), sampleColor("labelArgb"))
    assertEquals(0xFF4F6BED.toInt(), sampleColor("ringArgb"))
    assertEquals(null, sampleColor("count"))
    assertEquals("${0xFF4F6BED.toInt()}", defaultValue(StateField("noticeIconArgb", StateKind.Int, StateSource.Prop), Scenario.Loaded))
    assertEquals("1", defaultValue(StateField("step", StateKind.Int, StateSource.Prop), Scenario.Loaded))
  }

  // F7: a section whose name reads as content but which holds the loading skeleton.
  private val sectioned = WidgetNode(
    "Column", emptyMap(),
    listOf(
      cond("showListSection", null, cond("isLoading", null, WidgetNode("Shimmer")), WidgetNode("Repeat", mapOf("items" to "rows"))),
      cond("showSummary", null, WidgetNode("StyledText"), WidgetNode("Else", emptyMap(), listOf(cond("hasError", null, WidgetNode("StyledText"))))),
      WidgetNode("Spacer"),
      cond("hasError", null, WidgetNode("StyledText")),
    ),
  )
  private val none = ScreenContract(emptyMap(), emptyList())

  @Test fun aSectionHoldingTheLoadingSkeletonIsShownWhileLoading() {
    val fields = stateFields(sectioned, none)
    assertEquals(setOf(StateRole.Loading), fields.single { it.name == "showListSection" }.carries)
    val loading = effectiveMocks(fields, Scenario.Loading, emptyMap())
    assertEquals("true", loading["showListSection"], "its skeleton is inside it")
    assertEquals("true", loading["isLoading"])
    assertEquals("false", loading["showSummary"], "a role in its else branch does not keep it on")
    assertEquals("true", effectiveMocks(fields, Scenario.Loaded, emptyMap())["showListSection"])
  }

  @Test fun aScenarioScrollsToTheBlockItIsAbout() {
    assertEquals("isLoading", scenarioFocus(sectioned, Scenario.Loading)?.props?.get("field"))
    assertEquals(sectioned.children[3], scenarioFocus(sectioned, Scenario.Error), "the drawn error block, not the one in an else branch")
    assertEquals(null, scenarioFocus(sectioned, Scenario.Loaded))
  }

  @Test fun aHandSetStateThatIsExactlyAPresetShowsAsThatPreset() {
    val fields = stateFields(sectioned, none)
    val scenarios = scenariosFor(fields)
    // Round 5 (L7): what the Loading preset sets, set by hand from Loaded, is Loading — not Custom.
    val asLoading = fields.filter { it.kind == StateKind.Bool || it.source != StateSource.Prop }
      .associate { it.name to defaultValue(it, Scenario.Loading) }
    assertEquals(Scenario.Loading, scenarioShown(fields, scenarios, Scenario.Loaded, asLoading))
    assertEquals(Scenario.Loaded, scenarioShown(fields, scenarios, Scenario.Loaded, emptyMap()))
  }

  @Test fun aFlagSetByHandMakesThePreviewCustom() {
    val fields = stateFields(sectioned, none)
    assertFalse(isCustomState(fields, emptyMap()))
    assertTrue(isCustomState(fields, mapOf("isLoading" to "false")))
    assertFalse(isCustomState(stateFields(tree, contract), mapOf("headlineRate" to "9%")), "a text the user typed is not a flag")
  }

  @Test fun aScenarioFindsItsBlockUnderACompoundCondition() {
    // Review 4 #4: if (!isLoading && !hasError) Content else { if (hasError) ErrorBanner }
    val outer = WidgetNode("Condition", mapOf("field" to "isLoading", "negate" to true, "field.1" to "hasError", "negate.1" to true),
      listOf(WidgetNode("StyledText"), WidgetNode("Else", emptyMap(), listOf(cond("hasError", null, WidgetNode("Banner"))))))
    val t = WidgetNode("Column", emptyMap(), listOf(outer))
    val error = mapOf("isLoading" to "false", "hasError" to "true")
    assertEquals("hasError", scenarioFocus(t, Scenario.Error, error)?.props?.get("field"), "the else branch is drawn: its error block")
    assertEquals(null, scenarioFocus(t, Scenario.Error, mapOf("isLoading" to "false", "hasError" to "false")), "content drawn: no error block shows")
  }

  @Test fun aScenarioRevealsABlockInTheBranchItDraws() {
    // if (hasContent) Text else { if (isLoading) Shimmer }: Loading draws the else branch.
    val t = WidgetNode("Column", emptyMap(), listOf(
      cond("hasContent", null, WidgetNode("StyledText"), WidgetNode("Else", emptyMap(), listOf(cond("isLoading", null, WidgetNode("Shimmer"))))),
    ))
    assertEquals("isLoading", scenarioFocus(t, Scenario.Loading)?.props?.get("field"))
    assertEquals(null, scenarioFocus(t, Scenario.Error))
  }

  @Test fun aSizeLikeIntPreviewsAVisibleSize() {
    assertEquals("16", defaultValue(StateField("size", StateKind.Int, StateSource.Prop), Scenario.Loaded))
    assertEquals("500", defaultValue(StateField("titleWeight", StateKind.Int, StateSource.Prop), Scenario.Loaded))
    assertEquals("1", defaultValue(StateField("level", StateKind.Int, StateSource.Prop), Scenario.Loaded))
    // Review 5: the most specific word wins.
    assertEquals("8", sampleSize("itemSpacing"), "a spacing, not a font size")
    assertEquals("1", sampleSize("borderWidth"), "a border, not a box width")
    assertEquals("1", sampleSize("strokeWidthDp"))
    assertEquals("16", sampleSize("textSizeSp"))
    assertEquals("48", sampleSize("iconWidth"))
    // Review 6
    assertEquals("8", sampleSize("borderRadius"))
    assertEquals("8", sampleSize("dividerPadding"))
    assertEquals("48", sampleSize("outlinedButtonHeight"))
    assertEquals(null, sampleColor("colorCount"), "a number about colours")
    assertEquals(null, sampleColor("tintIndex"))
  }
}
