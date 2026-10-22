// AUDIT E3: a screen taller than the frame scrolls with the mouse wheel, and
// a click on what scrolled into view selects it.
import { equal, ok } from '../lib/assert.mjs';
import { nodes } from '../lib/editor.mjs';
import { diffFraction } from '../lib/png.mjs';
import { COLORS } from './fixture-map.mjs';

export default [
  {
    id: 'E3',
    title: 'wheel-scrolling reaches content below the frame; a click after scrolling selects the right node',
    async run({ page, editor, relay, log }) {
      await editor.open('logbook', { readyColor: COLORS.entryRow });
      const doc = await relay.doc('logbook');
      const byColor = (argb) => nodes(doc).find((n) => n.props.colorArgb === argb);
      const footer = byColor(COLORS.footer);
      const archive = byColor(COLORS.archive);
      ok(footer && archive, 'the logbook has its footer and archive boxes');
      const frame = await editor.frameRect();

      // Before: the footer is below the frame, so it is not drawn.
      equal(await editor.findColor(COLORS.footer), null, 'before scrolling, the footer is not visible in the frame');
      const before = await page.capture(frame, 'E3-before');

      // A real wheel over the frame, as a mouse or trackpad sends it.
      await page.wheel(frame.x + frame.width / 2, frame.y + frame.height / 2, 900, { steps: 6, settle: 700 });
      const after = await page.capture(frame, 'E3-after-wheel');
      const moved = diffFraction(before, after);
      log(`frame pixels changed by the wheel: ${(moved * 100).toFixed(1)}%`);
      ok(moved > 0.05, `the wheel scrolled the canvas (only ${(moved * 100).toFixed(2)}% of the frame changed)`);

      const seen = await editor.findColor(COLORS.footer);
      ok(seen, 'after scrolling, the footer (below the frame height) is visible');
      ok(seen.top >= frame.y && seen.bottom <= frame.y + frame.height + 1,
        `the footer is drawn inside the frame (${seen.top}..${seen.bottom}, frame ${frame.y}..${frame.y + frame.height})`);

      // A click on what scrolled into view selects it (hit-testing follows the scroll).
      let overlaysChecked = 0;
      for (const [what, node, color] of [['footer', footer, COLORS.footer], ['archive box', archive, COLORS.archive]]) {
        await editor.clearSelection(doc.handle);
        const b = await editor.findColor(color);
        ok(b, `the ${what} is visible after scrolling`);
        await page.click(b.cx, b.cy);
        equal(await editor.propsHeader(), `StyledBox #${node.handle}`, `clicking the ${what} after scrolling selects it`);
        // Where the whole box is in view, the selection overlay must sit on it.
        if (b.top > frame.y + 1 && b.bottom < frame.y + frame.height - 1) {
          overlaysChecked++;
          const overlay = await editor.selectionOverlay();
          ok(overlay && Math.abs(overlay.y - b.top) <= 3 && Math.abs(overlay.x - b.left) <= 3,
            `the selection overlay sits on the scrolled ${what} (overlay ${JSON.stringify(overlay)}, drawn at ${b.left},${b.top})`);
        }
      }
      ok(overlaysChecked > 0, 'at least one scrolled box was wholly in view to check the selection overlay on');
      await page.screenshot('E3-after-click');
    },
  },
];
