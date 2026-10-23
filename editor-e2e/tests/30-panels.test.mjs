// AUDIT E7: the side panels resize by dragging their inner edge, collapse and
// come back on a double-click, and keep their widths across a reload.
import { equal, near, ok } from '../lib/assert.mjs';
import { COLORS } from './fixture-map.mjs';

const textSelection = (page) => page.eval('String(window.getSelection())');

export default [
  {
    id: 'E7',
    title: 'dragging a panel edge resizes it; double-click collapses and restores; widths persist across reload',
    async run({ page, editor, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const start = await editor.paneWidths();
      log(`widths at start: ${JSON.stringify(start)}`);
      ok(start.left > 0 && start.right > 0, 'both panels are open at start');
      const W = page.width, y = 500;

      // Drag each panel's inner edge by 150 px (the left one right, the right one left).
      await page.drag(start.left - 1, y, start.left - 1 + 150, y);
      let w = await editor.paneWidths();
      near(w.left, start.left + 150, 3, 'dragging the left panel edge 150 px to the right widens it by 150 px');
      equal(await textSelection(page), '', 'dragging the left edge selects no text');

      await page.drag(W - start.right + 1, y, W - start.right + 1 - 150, y);
      w = await editor.paneWidths();
      near(w.right, start.right + 150, 3, 'dragging the right panel edge 150 px to the left widens it by 150 px');
      equal(await textSelection(page), '', 'dragging the right edge selects no text');
      await page.screenshot('E7-resized');
      const resized = w;

      // Double-click the left edge: the panel collapses; again: it comes back at its width.
      await page.dblclick(resized.left - 1, y);
      w = await editor.paneWidths();
      equal(w.left, 0, 'double-clicking the left panel edge collapses the panel');
      await page.screenshot('E7-left-collapsed');
      await page.dblclick(1, y);
      w = await editor.paneWidths();
      near(w.left, resized.left, 1, 'double-clicking the collapsed left edge restores the panel at its width');

      // Collapse the right panel, then reload: widths and the collapsed state persist.
      await page.dblclick(W - resized.right + 1, y);
      equal((await editor.paneWidths()).right, 0, 'double-clicking the right panel edge collapses the panel');
      await page.reload();
      await page.waitFor(`document.querySelectorAll('.tree-row').length > 0`, { timeout: 60000 });
      w = await editor.paneWidths();
      log(`widths after reload: ${JSON.stringify(w)}`);
      near(w.left, resized.left, 1, 'after a reload the left panel keeps its dragged width');
      equal(w.right, 0, 'after a reload the right panel is still collapsed');
      // F16: a hidden panel leaves a visible tab at the window's edge; a click on it brings the panel back.
      const tab = await page.eval(`(() => { const t = document.querySelector('.panel-reveal.right');
        if (!t || getComputedStyle(t).display === 'none') return null;
        const b = t.getBoundingClientRect(); return { x: b.x + b.width / 2, y: b.y + b.height / 2, label: t.getAttribute('aria-label') }; })()`);
      ok(tab, 'the collapsed right panel shows a tab at the window edge');
      log(`reveal tab: ${JSON.stringify(tab)}`);
      ok(!(await page.eval(`(() => { const t = document.querySelector('.panel-reveal.left'); return t && getComputedStyle(t).display !== 'none'; })()`)),
        'the open left panel shows no tab');
      await page.click(tab.x, tab.y);
      w = await editor.paneWidths();
      near(w.right, resized.right, 1, 'clicking the tab restores the right panel at its width');
      // Double-clicking the collapsed edge still works too.
      await page.dblclick(W - resized.right + 1, y);
      equal((await editor.paneWidths()).right, 0, 'collapsed again');
      await page.dblclick(W - 2, y);
      w = await editor.paneWidths();
      near(w.right, resized.right, 1, 'double-clicking the collapsed right edge restores the panel at its width');
      await page.screenshot('E7-after-reload');
    },
  },
];
