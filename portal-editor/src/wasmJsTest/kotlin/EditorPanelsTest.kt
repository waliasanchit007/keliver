/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import kotlin.test.Test
import kotlin.test.assertEquals

/** Resizable side panels: a drag widens or narrows a panel within its range; stored widths are checked. */
class EditorPanelsTest {
  @Test fun draggingRightWidensTheLeftPanelAndNarrowsTheRightOne() {
    assertEquals(372, draggedWidth(PanelSide.Left, 272, 100.0))
    assertEquals(200, draggedWidth(PanelSide.Right, 300, 100.0))
    assertEquals(400, draggedWidth(PanelSide.Right, 300, -100.0))
  }

  @Test fun aDragStopsAtTheRange() {
    assertEquals(PANEL_MAX_PX, draggedWidth(PanelSide.Left, 272, 5000.0))
    assertEquals(PANEL_MIN_PX, draggedWidth(PanelSide.Left, 272, -5000.0))
  }

  @Test fun aStoredWidthOutsideTheRangeOrNotANumberFallsBack() {
    assertEquals(420, storedWidth(PanelSide.Left, "420"))
    assertEquals(272, storedWidth(PanelSide.Left, "99999"))
    assertEquals(300, storedWidth(PanelSide.Right, "wide"))
    assertEquals(300, storedWidth(PanelSide.Right, null))
  }
}
