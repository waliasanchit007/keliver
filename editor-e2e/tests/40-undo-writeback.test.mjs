// AUDIT E30/E31 (Undo/Redo state, Cmd+Z in a field) and E19 (an edit and its
// undo give the .kt file back byte for byte: imports and argument order too).
import fs from 'node:fs';
import path from 'node:path';
import { equal, ok } from '../lib/assert.mjs';
import { changedLines, changedSources, expectBytes, snapshot, waitForFile } from '../lib/fixture.mjs';
import { sleep } from '../lib/browser.mjs';
import { COLORS } from './fixture-map.mjs';

const overviewKt = (dir) => path.join(dir, 'src/screens/overview.kt');

/** Waits until Undo/Redo show [want] ({ undo: disabled?, redo: disabled? }). */
async function waitButtons(editor, want, message) {
  const end = Date.now() + 8000;
  let got;
  for (;;) {
    got = await editor.undoRedo();
    if (got.undo && got.redo && got.undo.disabled === want.undo && got.redo.disabled === want.redo) return got;
    if (Date.now() > end) break;
    await sleep(100);
  }
  equal({ undo: got.undo?.disabled, redo: got.redo?.disabled }, want, message);
  return got;
}

export default [
  {
    id: 'E30',
    title: 'Undo/Redo are disabled with nothing to step, count their steps, and step one edit each',
    async run({ page, editor, fixtureDir, log }) {
      // The relay keeps undo stacks per document (and the editor's session), for
      // the life of the relay: the logbook is a screen no earlier test edits.
      await editor.open('logbook', { readyColor: COLORS.entryRow });
      const file = path.join(fixtureDir, 'src/screens/logbook.kt');
      const original = fs.readFileSync(file, 'utf8');

      // A fresh page of an unedited screen: nothing to undo or redo.
      let b = await waitButtons(editor, { undo: true, redo: true }, 'on a fresh page Undo and Redo are disabled');
      log(`fresh: ${JSON.stringify(b)}`);
      ok(/0 steps/.test(b.undo.title) && /0 steps/.test(b.redo.title), `their titles say 0 steps (${b.undo.title} / ${b.redo.title})`);

      // One edit (a key typed into the title's Text field, committed with Enter) enables Undo, with one step.
      await editor.clickOutlineRowOf('StyledText', 'Logbook');
      await editor.appendToField('Text', '!', { commit: true });
      await waitForFile(file, (t) => t.includes('"Logbook!"'), { message: 'the edit was never written' });
      b = await waitButtons(editor, { undo: false, redo: true }, 'after one edit Undo is enabled and Redo is not');
      ok(/\b1 step\b/.test(b.undo.title), `Undo's title counts one step (${b.undo.title})`);

      // A real click on Undo: the file is back byte for byte; Undo is disabled, Redo has one step.
      await page.click(1, 1); // leave the field (its own Cmd+Z is the field's)
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore logbook.kt byte for byte');
      b = await waitButtons(editor, { undo: true, redo: false }, 'after Undo, Undo is disabled and Redo enabled');
      ok(/\b1 step\b/.test(b.redo.title), `Redo's title counts one step (${b.redo.title})`);

      // Redo re-applies it; the platform's undo key (outside a field) undoes it again.
      await editor.clickRedo();
      await waitForFile(file, (t) => t.includes('"Logbook!"'), { message: 'Redo did not re-apply the edit' });
      await waitButtons(editor, { undo: false, redo: true }, 'after Redo, Undo is enabled again');
      await page.undoKey();
      await expectBytes(file, original, 'the undo key did not restore logbook.kt');
      await waitButtons(editor, { undo: true, redo: false }, 'after the undo key, Undo is disabled again');
    },
  },
  {
    id: 'E31',
    title: 'the undo key inside a text field is the field\'s own: focus stays, the document is not undone',
    async run({ page, editor, fixtureDir }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      const before = await editor.appendToField('Text', 'XY');
      const focused = () => page.eval(`(() => { const e = document.activeElement;
        return e && /^(INPUT|TEXTAREA)$/.test(e.tagName) ? e.value : null; })()`);
      equal(await focused(), `${before}XY`, 'the Text field has focus and the typed text');
      // E11: typing is not written; the edit is written once, when committed.
      await sleep(600);
      await expectBytes(file, original, 'typing in the Text field wrote to overview.kt before the edit was committed');

      await page.undoKey();
      await sleep(300);
      const value = await focused();
      ok(value != null, 'after the undo key the Text field still has focus');
      ok(value !== `${before}XY` && `${before}XY`.startsWith(value) && value.startsWith(before),
        `the undo key undid typing in the field (field now "${value}")`);
      // Whatever the field undid is written like any typed edit; leaving the
      // field and pressing Undo until it is disabled restores the file exactly.
      await page.click(1, 1);
      for (let i = 0; i < 6 && !(await editor.undoRedo()).undo.disabled; i++) {
        await editor.clickUndo();
        await sleep(400);
      }
      await expectBytes(file, original, 'undoing every step did not restore overview.kt');
    },
  },
  {
    id: 'E19',
    title: 'an insert (new import) and a new argument, each undone, leave the .kt file byte-identical',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const pristine = snapshot(fixtureDir);
      ok(!/import dev\.keliver\.material\.compose\.Divider\b/.test(original), 'the screen does not import Divider yet');

      // 1. Click-to-insert a Divider into the selected root Column: the file gains its call and its import.
      await editor.clickOutlineRow(0);
      await editor.clickOn('.pane.left input'); // the palette's search box
      await page.type('Divider');
      await editor.clickOn('.pal-row .t', 'Divider', { exact: true });
      const inserted = await waitForFile(file, (t) => /\n\s*Divider\(/.test(t), { message: 'the Divider was never written' });
      ok(/import dev\.keliver\.material\.compose\.Divider\n/.test(inserted), 'the insert added the Divider import');
      log(`insert: ${JSON.stringify(changedSources(pristine, snapshot(fixtureDir)))}`);
      await page.click(1, 1);
      await editor.clickUndo();
      await expectBytes(file, original, 'undoing the insert did not give overview.kt back byte for byte (its import?)');

      // 2. Set a property the call does not pass yet (Max lines): the argument is
      // added; undo must remove it and leave the others in the author's order.
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      const f = await editor.propField('Max lines');
      ok(f, 'the StyledText has a Max lines field');
      await page.click(f.x, f.y);
      await page.press('Backspace');
      await page.press('Backspace');
      await page.type('2');
      await page.press('Enter'); // E11: a number is written once, when committed
      const edited = await waitForFile(file, (t) => t.includes('maxLines = 2'), { message: 'maxLines was never written' });
      log(`maxLines: ${JSON.stringify(changedSources(pristine, snapshot(fixtureDir)))}`);
      ok(edited.includes('maxLines = 2'), 'the new argument is in the file');
      await page.click(1, 1);
      for (let i = 0; i < 6 && !(await editor.undoRedo()).undo.disabled; i++) {
        await editor.clickUndo();
        await sleep(400);
      }
      await expectBytes(file, original, 'undoing the new argument did not give overview.kt back byte for byte');
      equal(changedSources(pristine, snapshot(fixtureDir)), [], 'every source file is byte-identical to the start');
    },
  },
  {
    id: 'F4',
    title: 'deleting a node written in the author\'s own argument order, then Undo, gives the file back byte for byte',
    async run({ page, editor, relay, fixtureDir, log }) {
      const file = path.join(fixtureDir, 'src/screens/logbook.kt');
      const fixture = fs.readFileSync(file, 'utf8');
      // The author's order and layout, not the editor's: one line, size first.
      const authored = fixture.replace(
        'StyledText(\n        text = "Archive",\n        fontSize = 14,\n        colorArgb = -15000801,\n      )',
        'StyledText(fontSize = 14, text = "Archive", colorArgb = -15000801)',
      );
      ok(authored !== fixture, 'the fixture has the Archive text to rewrite');
      const version = async () => JSON.parse(await relay.get('/doc?project=default&screen=logbook')).version;
      const before = await version();
      fs.writeFileSync(file, authored);
      try {
        // The relay reads the change in code (a new document version) before the page opens.
        const end = Date.now() + 15000;
        while (Date.now() < end && (await version()) === before) await sleep(200);
        ok((await version()) !== before, 'the relay read the change made in code');
        await editor.open('logbook', { readyColor: COLORS.entryRow });
        await editor.clickOutlineRowOf('StyledText', 'Archive');
        await page.press('Delete');
        await waitForFile(file, (t) => !t.includes('"Archive"'), { message: 'Delete on the selected row was never written' });
        log(`after delete: ${JSON.stringify(changedLines(authored, fs.readFileSync(file, 'utf8')))}`);
        await editor.clickUndo();
        await expectBytes(file, authored, 'Undo of the delete did not give logbook.kt back byte for byte (argument order?)');
      } finally {
        fs.writeFileSync(file, fixture);
      }
    },
  },
  {
    id: 'H3',
    title: 'Backspace on a selected row deletes that one node; pressing it again deletes nothing more',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const rows = (await editor.outline()).length;
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      for (let i = 0; i < 3; i++) await page.press('Backspace', { settle: 700 });
      await waitForFile(file, (t) => !t.includes('Synced this morning'), { message: 'Backspace never deleted the row' });
      await sleep(1500);
      const removed = changedLines(original, fs.readFileSync(file, 'utf8'));
      log(`removed: ${JSON.stringify(removed)}`);
      equal((await editor.outline()).length, rows - 1, 'three presses deleted one node');
      equal(removed.added, [], 'nothing was added');
      ok(removed.removed.length === 5 && removed.removed.some((l) => l.includes('Synced this morning')), `only the one StyledText call was removed (${removed.removed.length} lines)`);
      equal(await editor.selectedRows(), [], 'nothing is selected after the delete');
      // Review 4 #8: the arrows go on from where the deleted row was (the Row after it), not from the top.
      await page.press('ArrowDown', { settle: 500 });
      const resumed = await editor.selectedRows();
      ok(resumed.length === 1 && /^\W*Row/.test(resumed[0]), `↓ selects the row that took the deleted one's place (${JSON.stringify(resumed)})`);
      await page.click(1, 1);
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not give overview.kt back byte for byte');
    },
  },
];
