// AUDIT E14: open a component instance to edit its insides; the breadcrumb
// leads back. AUDIT E18: a refused edit says so. Edits inside a component go to
// components/<Name>.kt only, surgically, and Undo gives it back byte for byte.
import fs from 'node:fs';
import path from 'node:path';
import { equal, ok } from '../lib/assert.mjs';
import { sleep } from '../lib/browser.mjs';
import { nodes } from '../lib/editor.mjs';
import { changedSources, expectBytes, snapshot, waitForFile } from '../lib/fixture.mjs';
import { diffFraction } from '../lib/png.mjs';
import { COLORS } from './fixture-map.mjs';

/** The breadcrumb's places (["overview", "SummaryCard"]), or null while the screen itself is edited. */
const crumbPlaces = (page) => page.eval(`(() => {
  const c = document.querySelector('.crumbs');
  if (!c || getComputedStyle(c).display === 'none') return null;
  return [...c.querySelectorAll('.crumb')].map(e => e.textContent.trim()).filter(t => t !== 'Done')
    .map(t => t.replace(/^[▢◇]\\s*/, ''));
})()`);

async function waitCrumbs(page, want, message) {
  const end = Date.now() + 8000;
  let got;
  for (;;) {
    got = await crumbPlaces(page);
    if (JSON.stringify(got) === JSON.stringify(want)) break;
    if (Date.now() > end) equal(got, want, message);
    await sleep(100);
  }
  await sleep(400); // the canvas draws the newly opened document
}

/** The outline's node types, in order, for a component body / screen tree from the relay. */
const types = (tree) => nodes(tree).map((n) => n.type);

async function componentTree(relay, name) {
  const d = await relay.get(`/doc?project=default&component=${name}`);
  const conv = (n) => ({ type: n.type, children: (n.children ?? []).map(conv) });
  return conv(JSON.parse(d).root);
}

async function waitHeader(editor, want, message) {
  const end = Date.now() + 5000;
  for (;;) {
    const h = await editor.propsHeader();
    if (h === want) return;
    if (Date.now() > end) equal(h, want, message);
    await sleep(100);
  }
}

export default [
  {
    id: 'E14',
    title: 'double-click opens a component; an internal edit goes to its file only; the crumb returns with the edit shown',
    async run({ page, editor, relay, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const pristine = snapshot(fixtureDir);
      const componentFile = path.join(fixtureDir, 'src/components/SummaryCard.kt');
      const original = fs.readFileSync(componentFile, 'utf8');
      const doc = await relay.doc('overview');
      const summary = doc.children.find((n) => n.type === 'SummaryCard');

      const visitsBefore = await editor.findColor(COLORS.statVisits);
      const tileBefore = await page.capture({ x: visitsBefore.left, y: visitsBefore.top, width: visitsBefore.width, height: visitsBefore.height });

      // Double-click the card (its padding, outside the nested tiles): the component opens.
      const card = await editor.findColor(COLORS.summaryCard);
      await page.dblclick(card.cx, card.top + 8);
      await waitCrumbs(page, ['overview', 'SummaryCard'], 'double-clicking the instance opens SummaryCard (breadcrumb overview › SummaryCard)');
      equal((await editor.outline()).map((r) => r.type), types(await componentTree(relay, 'SummaryCard')),
        "the outline is SummaryCard's body");
      await page.screenshot('E14-opened');

      // Click the Visits tile (a nested StatTile instance) and add "!" to its label.
      const tile = await editor.findColor(COLORS.statVisits);
      await page.click(tile.cx, tile.cy);
      ok(/^StatTile #\d+ · component$/.test(await editor.propsHeader() ?? ''), 'clicking the Visits tile selects that StatTile');
      equal((await editor.propField('Label'))?.value, 'Visits', "the StatTile's Label field shows its argument");
      await editor.appendToField('Label', '!');
      await waitForFile(componentFile, (t) => t !== original, { message: 'the edit was never written to SummaryCard.kt' });
      const changes = changedSources(pristine, snapshot(fixtureDir));
      log(`write-back: ${JSON.stringify(changes)}`);
      equal(changes.map((c) => c.file), ['src/components/SummaryCard.kt'], 'only components/SummaryCard.kt changed');
      equal({ removed: changes[0].lines.removed, added: changes[0].lines.added },
        { removed: ['          label = "Visits",'], added: ['          label = "Visits!",'] }, 'the edit changed only the Visits label argument');

      // The screen's crumb goes back, with the instance selected again, drawing the edit.
      await page.click(1, 1);
      await editor.clickOn('.crumbs .crumb', '▢ overview');
      await waitCrumbs(page, null, 'the "▢ overview" crumb returns to the screen');
      await waitHeader(editor, `SummaryCard #${summary.handle} · component`, 'back on the screen, the SummaryCard instance is selected again');
      const end = Date.now() + 5000;
      let changed = 0;
      // The label gains a "!": a few pixels of an otherwise unchanged tile (it does not move).
      const area = visitsBefore.width * visitsBefore.height;
      while (Date.now() < end && changed * area < 6) {
        const tileAfter = await page.capture({ x: visitsBefore.left, y: visitsBefore.top, width: visitsBefore.width, height: visitsBefore.height });
        changed = diffFraction(tileBefore, tileAfter);
        if (changed * area < 6) await sleep(250);
      }
      log(`Visits tile pixels changed: ${Math.round(changed * area)}`);
      ok(changed * area >= 6, 'the instance on the screen draws the edited label');
      await page.screenshot('E14-back-on-screen');

      // Enter on the selected instance opens it again; Undo there gives the file back byte for byte.
      await page.press('Enter');
      await waitCrumbs(page, ['overview', 'SummaryCard'], 'Enter on the selected instance opens SummaryCard');
      await editor.clickUndo();
      await expectBytes(componentFile, original, 'Undo did not give SummaryCard.kt back byte for byte');
      // With nothing selected, Escape steps out of the component.
      await page.click(1, 1);
      await page.press('Escape');
      await waitCrumbs(page, null, 'Escape with nothing selected returns to the screen');
      equal(changedSources(pristine, snapshot(fixtureDir)), [], 'every source file is byte-identical to the start');
    },
  },
  {
    id: 'E14n',
    title: 'opening a component nested in the opened one extends the breadcrumb; Escape steps out one level at a time',
    async run({ page, editor, relay }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const summary = (await relay.doc('overview')).children.find((n) => n.type === 'SummaryCard');
      const card = await editor.findColor(COLORS.summaryCard);
      await page.dblclick(card.cx, card.top + 8);
      await waitCrumbs(page, ['overview', 'SummaryCard'], 'double-clicking the instance opens SummaryCard');

      const tile = await editor.findColor(COLORS.statVisits);
      await page.dblclick(tile.cx, tile.cy);
      await waitCrumbs(page, ['overview', 'SummaryCard', 'StatTile'], 'double-clicking a nested StatTile opens it: overview › SummaryCard › StatTile');
      equal((await editor.outline()).map((r) => r.type), types(await componentTree(relay, 'StatTile')), "the outline is StatTile's body");
      await page.screenshot('E14n-nested');

      // The middle crumb goes back one level, re-selecting the StatTile it was opened from.
      await editor.clickOn('.crumbs .crumb', '◇ SummaryCard');
      await waitCrumbs(page, ['overview', 'SummaryCard'], 'the SummaryCard crumb goes back to SummaryCard');
      ok(/^StatTile #\d+ · component$/.test(await editor.propsHeader() ?? ''), 'the StatTile it was opened from is selected again');

      // Escape: first deselects, then (nothing selected) steps out to the screen.
      await page.press('Escape');
      equal(await editor.selectedHandle(), null, 'Escape deselects first');
      ok(await crumbPlaces(page), 'deselecting does not leave the component');
      await page.press('Escape');
      await waitCrumbs(page, null, 'Escape with nothing selected steps out to the screen');
      await waitHeader(editor, `SummaryCard #${summary.handle} · component`, 'back on the screen, the SummaryCard instance is selected again');
    },
  },
  {
    id: 'E14r',
    title: 'returning from a component does not claim the re-selected instance is not drawn',
    async run({ page, editor }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const card = await editor.findColor(COLORS.summaryCard);
      await page.dblclick(card.cx, card.top + 8);
      await waitCrumbs(page, ['overview', 'SummaryCard'], 'double-clicking the instance opens SummaryCard');
      const tile = await editor.findColor(COLORS.statVisits);
      await page.dblclick(tile.cx, tile.cy);
      await waitCrumbs(page, ['overview', 'SummaryCard', 'StatTile'], 'the nested StatTile opens');
      const hints = [];
      await page.press('Escape');
      for (let i = 0; i < 10; i++) { hints.push(await editor.canvasHint()); await sleep(100); }
      await page.screenshot('E14r-after-return');
      equal(hints.filter((h) => h && /Not drawn/.test(h)), [], 'no "not drawn" hint about the StatTile drawn on the canvas');
    },
  },
  {
    id: 'E18',
    title: 'an edit the relay refuses leaves the pill at "Change refused" (not "Saved") and the file unchanged',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const pristine = snapshot(fixtureDir);
      // Inside CardTitle, binding Font size (@) would need a new parameter: a
      // change to the component's signature, which every caller passes. Refused.
      await editor.clickOutlineRowOf('CardTitle', 'Recent sites');
      await page.press('Enter');
      await waitCrumbs(page, ['overview', 'CardTitle'], 'Enter on the selected CardTitle opens it');
      await editor.clickOutlineRowOf('StyledText');
      const at = await page.eval(`(() => {
        const row = [...document.querySelectorAll('.pane.right .row')].find(r => r.querySelector('label')?.textContent === 'Font size');
        const b = row && [...row.querySelectorAll('button')].find(b => b.textContent.trim() === '@');
        if (!b) return null; const r = b.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      })()`);
      ok(at, 'the Font size row has a binding (@) button');
      await page.click(at.x, at.y);
      await page.waitFor(`document.querySelector('.topbar .pill')?.textContent === 'Change refused'`,
        { timeout: 5000, message: 'the pill never said "Change refused"' });
      await sleep(3000);
      equal(await editor.pill(), 'Change refused', 'three seconds later the pill still says "Change refused"');
      await page.screenshot('E18-refused');
      log(`toast/console: ${page.console.filter((c) => /refus/i.test(c.text)).map((c) => c.text).join(' | ')}`);
      equal(changedSources(pristine, snapshot(fixtureDir)), [], 'the refused edit changed no source file');
    },
  },
  {
    id: 'E14x',
    title: 'an unknown component is a 404; a component deleted on disk reads as code and Enter stays on the screen',
    async run({ page, editor, relay, fixtureDir }) {
      const r = await fetch(`${relay.base}/doc?project=default&component=Nope`);
      equal(r.status, 404, 'GET /doc?component=Nope is a 404');
      ok(/no such component/.test(await r.text()), 'the 404 says the app has no such component');

      // A component deleted on disk while the editor shows a screen that uses it: the
      // change reaches the editor live, the call reads as code (nothing left to open),
      // and Enter on it keeps the editor on the screen.
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = path.join(fixtureDir, 'src/components/CountBadge.kt');
      const text = fs.readFileSync(file, 'utf8');
      fs.rmSync(file);
      try {
        const end = Date.now() + 8000;
        while (Date.now() < end) {
          const comps = JSON.parse(await relay.get('/components?project=default'));
          if (!comps.some((c) => c.name === 'CountBadge')) break;
          await sleep(200);
        }
        await page.waitFor(`![...document.querySelectorAll('.tree-row .t')].some((t) => t.textContent === 'CountBadge')`,
          { timeout: 8000, message: 'the editor still offers CountBadge as a component after its file was deleted' });
        const rows = await editor.outline();
        const i = rows.findIndex((r) => r.type === 'RawCode' && /CountBadge/.test(r.text + (r.label ?? '')));
        await editor.clickOutlineRow(i >= 0 ? i : rows.findIndex((r) => r.type === 'RawCode'));
        await page.press('Enter');
        await sleep(800);
        await page.screenshot('E14x-code');
        await waitCrumbs(page, null, 'the editor stays on the screen');
        equal((await editor.outline())[0]?.type, 'Column', "the outline is the screen's");
      } finally {
        fs.writeFileSync(file, text);
        await sleep(1500);
      }
    },
  },
];
