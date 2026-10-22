// AUDIT E15: drag a palette item onto the canvas. An insertion line shows
// where it will land, the drop inserts it there, and the new node is selected.
import fs from 'node:fs';
import path from 'node:path';
import { equal, near, ok } from '../lib/assert.mjs';
import { expectBytes, waitForFile } from '../lib/fixture.mjs';
import { COLORS } from './fixture-map.mjs';

export default [
  {
    id: 'E15',
    title: 'dragging a palette item onto the canvas shows a drop line, inserts there and selects the new node',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = path.join(fixtureDir, 'src/screens/overview.kt');
      const original = fs.readFileSync(file, 'utf8');

      // Find the Divider in the palette (typing into its search box), and the
      // "Recent sites" title on the canvas: dropping on its lower edge means "after it".
      await editor.clickOn('.pane.left input');
      await page.type('Divider');
      const source = await editor.centreOf('.pal-row .t', 'Divider', { exact: true });
      ok(source, 'the palette lists Divider');
      const title = await editor.findColor(COLORS.recentSitesTitle);
      ok(title, 'the "Recent sites" title is drawn');

      let marker = null;
      const dragged = await page.dragAndDrop(source.x, source.y, title.cx, title.bottom - 3, {
        during: async () => {
          marker = await page.eval(`(() => { const d = document.querySelector('.drop-line');
            if (!d || getComputedStyle(d).display === 'none') return null;
            const b = d.getBoundingClientRect(); return { cls: d.className, x: b.x, y: b.y, width: b.width, height: b.height }; })()`);
          await page.screenshot('E15-drop-line');
        },
      });
      ok(dragged, 'pressing on the palette row and moving started a drag');
      log(`drop line while over the title: ${JSON.stringify(marker)}; title drawn at ${JSON.stringify(title)}`);
      ok(marker, 'while dragging over the canvas a drop line shows');
      equal(marker.cls, 'drop-line', 'over the lower edge of a leaf the marker is an insertion line (not a container box)');
      near(marker.y + marker.height / 2, title.bottom, 3, 'the insertion line sits on the title\'s bottom edge');
      near(marker.x, title.left, 3, 'the insertion line starts at the title\'s left edge');
      near(marker.width, title.width, 3, 'the insertion line is as wide as the title');

      // The drop inserted a Divider right after the title (and imported it).
      const text = await waitForFile(file, (t) => t !== original, { message: 'the drop was never written to overview.kt' });
      ok(/tintArgb = -5051406,\n {4}\)\n {4}Divider\(\n {4}\)\n/.test(text), 'the Divider call follows the "Recent sites" title in overview.kt');
      ok(/\nimport dev\.keliver\.material\.compose\.Divider\n/.test(text), 'overview.kt imports Divider');

      // ... and selected it.
      await page.waitFor(`/^Divider #/.test(document.querySelector('.pane.right .card')?.firstElementChild?.textContent ?? '')`,
        { timeout: 5000, message: 'the new Divider was not selected after the drop' });
      const rows = await editor.outline();
      const i = rows.findIndex((r) => r.selected);
      equal(rows[i]?.type, 'Divider', 'the selected outline row is the new Divider');
      ok(/Recent sites/.test(rows[i - 1]?.text ?? ''), 'the row above it is the "Recent sites" title');
      await page.screenshot('E15-after-drop');

      await page.click(1, 1);
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not give overview.kt back byte for byte');
    },
  },
];
