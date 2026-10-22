import { equal, ok } from '../lib/assert.mjs';
import { COLORS } from './fixture-map.mjs';

// Requests whose failure is the static file server's, not the editor's.
const IGNORED_ERRORS = [/favicon\.ico/];

export default [
  {
    id: 'smoke',
    title: 'the editor loads the fixture: outline, screen picker, canvas, no exceptions',
    async run({ page, editor, relay, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      log(`outline ready ${editor.outlineReadyMs} ms, canvas ready ${editor.canvasReadyMs} ms`);

      // The outline lists the screen's tree, in document order.
      const doc = await relay.doc('overview');
      const rows = await editor.outline();
      const expected = [];
      const walk = (n) => { expected.push(n.type); n.children.forEach(walk); };
      walk(doc);
      equal(rows.map((r) => r.type), expected, 'the outline lists every node of the screen, in order');

      // The screen picker offers the fixture's screens and shows the open one.
      const picker = await page.eval(`(() => {
        const s = [...document.querySelectorAll('.topbar select')].find(s => [...s.options].some(o => o.value === 'overview'));
        return s ? { value: s.value, options: [...s.options].map(o => o.value) } : null;
      })()`);
      ok(picker, 'a screen picker lists the fixture screens');
      equal(picker.value, 'overview', 'the screen picker shows the open screen');
      equal([...picker.options].sort(), ['logbook', 'overview'], 'the screen picker lists exactly the fixture screens');

      // Nothing threw, and nothing but the static server's favicon 404 was logged as an error.
      equal(page.exceptions, [], 'no uncaught exceptions');
      const errors = page.console.filter((c) => /error/.test(c.type) && !IGNORED_ERRORS.some((re) => re.test(c.text)));
      equal(errors, [], 'no console errors');
      await page.screenshot('loaded');
    },
  },
];
