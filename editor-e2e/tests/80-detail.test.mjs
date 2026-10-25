// Review 4 and 5 details: folding a subtree leaves the selection alone, a State flag keeps the
// keyboard, and a colour the call does not pass shows as no colour (the widget's own default).
import { equal, ok } from '../lib/assert.mjs';
import { sleep } from '../lib/browser.mjs';
import { COLORS } from './fixture-map.mjs';

/** The centre of the twisty on the outline row whose type is [type] (the first such row). */
const twistyOf = (page, type) => page.eval(`(() => {
  const row = [...document.querySelectorAll('.tree-row')].find(r => r.querySelector('.t')?.textContent === ${JSON.stringify(type)});
  const t = row?.querySelector('.twisty');
  if (!t || !t.textContent.trim()) return null;
  const b = t.getBoundingClientRect();
  return { x: b.x + b.width / 2, y: b.y + b.height / 2, glyph: t.textContent };
})()`);

/** The State panel's checkbox for [field]: its centre and whether it is checked and focused. */
const stateFlag = (page, field) => page.eval(`(() => {
  const row = [...document.querySelectorAll('.row')].find(r => r.querySelector('label')?.textContent === ${JSON.stringify(field)});
  const box = row?.querySelector('input[type=checkbox]');
  if (!box) return null;
  box.scrollIntoView({ block: 'center' });
  const b = box.getBoundingClientRect();
  return { x: b.x + b.width / 2, y: b.y + b.height / 2, checked: box.checked, focused: document.activeElement === box };
})()`);

export default [
  {
    id: 'P1',
    title: 'a publish is said in a small card with its reason — the build log stays one click away, not over the editor',
    async run({ page, editor }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const bar = await page.eval(`[...document.querySelectorAll('.top-action')].map(b => b.innerText.split('\\n').join(' / '))`);
      ok(bar.some((t) => /Publish \/ to devices/.test(t)) && bar.some((t) => /Live \/ run its logic/.test(t)) && bar.some((t) => /Code \/ /.test(t)),
        `each top-bar action says what it does (${JSON.stringify(bar)})`);
      await editor.clickOn('.topbar .top-action', '⬆ Publish');
      await editor.clickOn('.modal-actions button', 'Publish', { exact: true });
      // This fixture app has no publish task: the relay refuses it, and the card says so.
      let card = null;
      for (const end = Date.now() + 30000; !card && Date.now() < end;) {
        card = await page.eval(`(() => { const c = document.querySelector('.publish-card.fail, .publish-card.ok');
          return c ? { state: c.className, title: c.querySelector('.pc-title').textContent, detail: c.querySelector('.pc-detail')?.textContent ?? '' } : null; })()`);
        if (!card) await sleep(250);
      }
      ok(card && /fail/.test(card.state), `a card says it was not published (${JSON.stringify(card)})`);
      equal(card.title, 'Not published');
      ok(card.detail.length > 0 && card.detail.length <= 240, `with its reason in one line (${card.detail})`);
      equal(await page.eval(`(() => { const o = [...document.querySelectorAll('pre')].find(p => p.offsetParent !== null); return !!o; })()`), false,
        'no build log is laid over the editor');
      await editor.clickOn('.publish-card button', 'Log', { exact: true });
      ok(await page.eval(`[...document.querySelectorAll('pre')].some(p => p.offsetParent !== null && p.textContent.length > 0)`), 'Log opens the full output');
    },
  },
  {
    id: 'O1',
    title: 'picking a row far down the outline keeps the outline where it was (it jumped to the top)',
    async run({ page, editor }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      // A short outline, so this screen's rows scroll, scrolled part way down.
      const scrolled = await page.eval(`(() => { const o = document.querySelector('.card.outline');
        o.style.maxHeight = '180px'; o.style.flex = 'none'; o.scrollTop = 120; return o.scrollTop; })()`);
      ok(scrolled > 0, `the outline scrolls (${scrolled})`);
      const row = await page.eval(`(() => { const o = document.querySelector('.card.outline').getBoundingClientRect();
        const r = [...document.querySelectorAll('.tree-row')].find(r => { const b = r.getBoundingClientRect();
          return !r.classList.contains('selected') && b.top > o.top + 20 && b.bottom < o.bottom - 20; });
        const b = r.querySelector('.t').getBoundingClientRect(); return { x: b.x + b.width / 2, y: b.y + b.height / 2, text: r.textContent }; })()`);
      await page.click(row.x, row.y, { settle: 800 });
      equal((await editor.selectedRows()).length, 1, `the row was selected (${row.text})`);
      equal(await page.eval(`document.querySelector('.card.outline').scrollTop`), scrolled, 'the outline did not move');
    },
  },
  {
    id: 'R5a',
    title: 'clicking a twisty folds the subtree and leaves the selection where it was',
    async run({ page, editor }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      const before = await editor.selectedRows();
      equal(before.length, 1, 'one row is selected');
      const twisty = await twistyOf(page, 'Row');
      ok(twisty && twisty.glyph === '▾', 'the Row is open, with a twisty');
      await page.click(twisty.x, twisty.y, { settle: 600 });
      equal((await twistyOf(page, 'Row'))?.glyph, '▸', 'the click folded the Row');
      equal(await editor.selectedRows(), before, 'the selection did not move to the Row');
    },
  },
  {
    id: 'R4f',
    title: 'a State flag toggled with a click keeps the keyboard: Space toggles it back',
    async run({ page, editor }) {
      await editor.open('logbook');
      const flag = await stateFlag(page, 'syncPending');
      ok(flag, 'the State panel lists syncPending');
      await page.click(flag.x, flag.y, { settle: 500 });
      const clicked = await stateFlag(page, 'syncPending');
      equal(clicked.checked, !flag.checked, 'the click toggled it');
      ok(clicked.focused, 'the flag keeps the keyboard after the panel follows the change');
      await page.press(' ', { settle: 500 });
      equal((await stateFlag(page, 'syncPending')).checked, flag.checked, 'Space toggled it back');
    },
  },
  {
    id: 'R4c',
    title: 'a colour the call does not pass shows as no colour, marked default — never as transparent black',
    async run({ page, editor }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      await editor.clickOutlineRowOf('StyledBox');
      await sleep(300);
      const unset = await page.eval(`[...document.querySelectorAll('.pane.right .prop.unset')]
        .filter(p => p.querySelector('.color-ctl'))
        .map(p => ({ label: p.querySelector('.prop-label')?.textContent, none: !!p.querySelector('.swatch.none'),
          hex: p.querySelector('input.hex')?.value, placeholder: p.querySelector('input.hex')?.placeholder }))`);
      ok(unset.length > 0, 'the StyledBox has a colour property it does not set');
      for (const u of unset) {
        ok(u.none, `${u.label}: its swatch shows no colour`);
        equal([u.hex, u.placeholder], ['', 'default'], `${u.label}: the field says the default applies`);
      }
    },
  },
];
