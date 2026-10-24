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
    id: 'K1',
    title: 'a relay that cannot open components is never shown as one: the editor says so and stays on the screen',
    async run({ page, editor, relay }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const screenTypes = (await editor.outline()).map((r) => r.type);
      // An older relay ignores `component=` and sends its screen: answer the component's documents that way.
      const asOlderRelay = (d) => {
        if (d.method !== 'Fetch.requestPaused') return;
        const url = d.params.request.url.replace(/component=[^&]+/, 'screen=overview');
        page.send('Fetch.continueRequest', { requestId: d.params.requestId, url }).catch(() => {});
      };
      page.listeners.push(asOlderRelay);
      await page.send('Fetch.enable', { patterns: [{ urlPattern: '*component=*', requestStage: 'Request' }] });
      try {
        const card = await editor.findColor(COLORS.summaryCard);
        await page.dblclick(card.cx, card.top + 8);
        let hint = null;
        for (const end = Date.now() + 5000; !hint && Date.now() < end;) { hint = await editor.canvasHint(); if (!hint) await sleep(100); }
        ok(/older than the editor/.test(hint ?? ''), `the editor says the relay cannot open SummaryCard (hint: ${hint})`);
        await waitCrumbs(page, null, 'the breadcrumb goes back to the screen');
        equal((await editor.outline()).map((r) => r.type), screenTypes, "the outline is still the screen's");
        ok(await editor.findColor(COLORS.summaryCard), 'the screen is still drawn');
      } finally {
        await page.send('Fetch.disable');
        page.listeners.splice(page.listeners.indexOf(asOlderRelay), 1);
      }
    },
  },
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
      // F13: no dashed hover box is left where the instance was drawn on the screen.
      equal(await page.eval(`(() => { const host = document.getElementById('PreviewHost');
        const h = [...host.parentElement.children].find(e => e !== host && e.style.borderTopStyle === 'dashed');
        return h ? getComputedStyle(h).display : 'none'; })()`), 'none', 'the hover box of the screen\'s instance is gone');
      equal((await editor.outline()).map((r) => r.type), types(await componentTree(relay, 'SummaryCard')),
        "the outline is SummaryCard's body");
      // K2: the opened component sits inset, not flush with the frame's top edge.
      const frame = await editor.frameRect();
      const opened = await editor.waitForColor(COLORS.summaryCard, { message: 'the opened SummaryCard is drawn' });
      ok(opened.top - frame.y >= 16, `K2: the opened component starts below the frame's top edge (${Math.round(opened.top - frame.y)} px down)`);
      await page.screenshot('E14-opened');

      // Click the Visits tile (a nested StatTile instance) and add "!" to its label.
      const tile = await editor.findColor(COLORS.statVisits);
      await page.click(tile.cx, tile.cy);
      ok(/^StatTile #\d+ · component$/.test(await editor.propsHeader() ?? ''), 'clicking the Visits tile selects that StatTile');
      equal((await editor.propField('Label'))?.value, 'Visits', "the StatTile's Label field shows its argument");
      await editor.appendToField('Label', '!', { commit: true });
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
      // F14: back on the screen, Undo there is the screen's: the editor says where the component's edits are undone.
      ok(await page.eval(`[...document.querySelectorAll('div')].some(d => d.style.position === 'fixed' && /Undo here still undoes them/.test(d.textContent))`),
        'a message says how to undo the edits made inside SummaryCard');
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
      // Inside CardTitle, the StyledText's Text is bound to the `text` parameter;
      // unbinding it would leave the required `text` argument out. Refused.
      await editor.clickOutlineRowOf('CardTitle', 'Recent sites');
      await page.press('Enter');
      await waitCrumbs(page, ['overview', 'CardTitle'], 'Enter on the selected CardTitle opens it');
      await editor.clickOutlineRowOf('StyledText');
      const at = await page.eval(`(() => {
        const row = [...document.querySelectorAll('.pane.right .prop')].find(r => r.querySelector('.prop-label')?.textContent === 'Text');
        const b = row && [...row.querySelectorAll('button')].find(b => b.textContent.trim() === 'unbind');
        if (!b) return null; const r = b.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      })()`);
      ok(at, 'the bound Text row has an unbind button');
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
    id: 'F1',
    title: 'after a refusal re-renders the panel, Backspace and Delete delete nothing; from the outline Delete deletes and Undo restores',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const pristine = snapshot(fixtureDir);
      const file = path.join(fixtureDir, 'src/components/CardTitle.kt');
      const original = fs.readFileSync(file, 'utf8');
      await editor.clickOutlineRowOf('CardTitle', 'Recent sites');
      await page.press('Enter');
      await waitCrumbs(page, ['overview', 'CardTitle'], 'Enter on the selected CardTitle opens it');
      await editor.clickOutlineRowOf('StyledText');
      const rows = (await editor.outline()).length;
      // The refused unbind (as in E18) re-reads the document and re-renders the panel it was clicked in.
      const at = await page.eval(`(() => {
        const row = [...document.querySelectorAll('.pane.right .prop')].find(r => r.querySelector('.prop-label')?.textContent === 'Text');
        const b = row && [...row.querySelectorAll('button')].find(b => b.textContent.trim() === 'unbind');
        if (!b) return null; const r = b.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      })()`);
      ok(at, 'the bound Text row has an unbind button');
      await page.click(at.x, at.y);
      await page.waitFor(`document.querySelector('.topbar .pill')?.textContent === 'Change refused'`,
        { timeout: 5000, message: 'the pill never said "Change refused"' });
      await sleep(500);
      log(`focus after the refusal: ${await page.eval('document.activeElement?.tagName')}`);
      for (const key of ['Backspace', 'Backspace', 'Delete']) await page.press(key, { settle: 400 });
      await sleep(1500);
      await page.screenshot('F1-keys-after-refusal');
      equal((await editor.outline()).length, rows, 'Backspace/Delete after the refusal deleted no node');
      equal(changedSources(pristine, snapshot(fixtureDir)), [], 'no source file changed');

      // Pointing at the tree again, Delete deletes the selection; Undo gives the file back byte for byte.
      await editor.clickOutlineRowOf('StyledText');
      await page.press('Delete');
      await waitForFile(file, (t) => !t.includes('StyledText('), { message: 'Delete on the selected outline row did not delete it' });
      equal((await editor.outline()).length, rows - 1, 'the outline lost the deleted row');
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore CardTitle.kt byte for byte');
    },
  },
  {
    id: 'G1',
    title: 'back on the screen after a component edit, Undo undoes that edit first, then the older screen edit',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const screenFile = path.join(fixtureDir, 'src/screens/overview.kt');
      const componentFile = path.join(fixtureDir, 'src/components/SummaryCard.kt');
      const screenOriginal = fs.readFileSync(screenFile, 'utf8');
      const componentOriginal = fs.readFileSync(componentFile, 'utf8');
      // 1. A screen edit.
      await editor.clickOutlineRowOf('StyledText', 'Field station');
      await editor.appendToField('Text', '!', { commit: true });
      await waitForFile(screenFile, (t) => t.includes('"Field station!"'), { message: 'the screen edit was never written' });
      // 2. An edit inside SummaryCard.
      await page.click(1, 1);
      const card = await editor.findColor(COLORS.summaryCard);
      await page.dblclick(card.cx, card.top + 8);
      await waitCrumbs(page, ['overview', 'SummaryCard'], 'double-clicking the instance opens SummaryCard');
      const tile = await editor.findColor(COLORS.statVisits);
      await page.click(tile.cx, tile.cy);
      await editor.appendToField('Label', '?', { commit: true });
      await waitForFile(componentFile, (t) => t.includes('"Visits?"'), { message: 'the component edit was never written' });
      // 3. Back on the screen, the platform's undo key: the component edit goes first.
      await page.click(1, 1);
      await page.press('Escape'); // deselects the tile
      await page.press('Escape'); // steps out of SummaryCard
      await waitCrumbs(page, null, 'Escape returns to the screen');
      // H2: the order survives a reload, as the relay's stacks do.
      await page.reload();
      await page.waitFor(`document.querySelectorAll('.tree-row').length > 0`, { timeout: 60000 });
      await sleep(1000);
      const b = await editor.undoRedo();
      log(`Undo on the screen, after a reload: ${JSON.stringify(b.undo)}`);
      ok(/SummaryCard/.test(b.undo?.title ?? ''), `Undo's tooltip names SummaryCard (${b.undo?.title})`);
      await page.undoKey();
      await expectBytes(componentFile, componentOriginal, 'the first undo did not give SummaryCard.kt back');
      ok(fs.readFileSync(screenFile, 'utf8').includes('"Field station!"'), 'the older screen edit is still there');
      await sleep(600);
      await page.undoKey();
      await expectBytes(screenFile, screenOriginal, 'the second undo did not give overview.kt back');
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
