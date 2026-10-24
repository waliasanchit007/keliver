// The properties panel's typed controls (WP-C): a number is written once, on
// commit, and never while invalid (E11); a colour keeps its alpha (E10); an
// Int-coded prop is a dropdown of names (E8); `@` binds a declared field and
// adds nothing to the contract (E12). Every edit is checked on disk (one
// surgical line) and undone byte for byte.
import fs from 'node:fs';
import path from 'node:path';
import { equal, ok } from '../lib/assert.mjs';
import { changedSources, expectBytes, snapshot, waitForFile } from '../lib/fixture.mjs';
import { sleep } from '../lib/browser.mjs';
import { COLORS } from './fixture-map.mjs';

const overviewKt = (dir) => path.join(dir, 'src/screens/overview.kt');

/** The properties row labelled [label]: its control's state, and the centres of its parts. */
function row(page, label) {
  return page.eval(`(() => {
    const r = [...document.querySelectorAll('.pane.right .prop')].find(p => p.querySelector('.prop-label')?.textContent === ${JSON.stringify(label)});
    if (!r) return null;
    const c = (e) => { if (!e) return null; const b = e.getBoundingClientRect(); return { x: b.x + Math.min(b.width / 2, 40), y: b.y + b.height / 2 }; };
    const input = r.querySelector('.prop-ctl input:not([type=color]), .prop-ctl textarea');
    const select = r.querySelector('.prop-ctl select');
    return {
      value: input?.value ?? null, input: c(input), invalid: !!input?.classList.contains('invalid'),
      error: r.querySelector('.field-err')?.textContent ?? '',
      select: c(select), selected: select ? select.options[select.selectedIndex].textContent : null,
      options: select ? [...select.options].map(o => o.textContent) : [],
      alpha: c(r.querySelector('.color-ctl .alpha')), hex: r.querySelector('.color-ctl .hex')?.value ?? null,
      buttons: Object.fromEntries([...r.querySelectorAll('.prop-actions button')].map(b => [b.textContent.trim(), c(b)])),
    };
  })()`);
}

/** Selects the focused field's text with real input. */
async function selectAll(page) {
  const mac = process.platform === 'darwin';
  await page.press('a', { modifiers: [mac ? 'Meta' : 'Control'], commands: ['selectAll'] });
}

/** The undo stack's depth, from the Undo button's title. */
async function undoDepth(editor) {
  return Number(/(\d+) step/.exec((await editor.undoRedo()).undo.title)?.[1] ?? NaN);
}

export default [
  {
    id: 'E11',
    title: 'a number field refuses non-numbers and out-of-range values inline and writes a valid one once, on Enter',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const pristine = snapshot(fixtureDir);
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      const depth = await undoDepth(editor);
      let r = await row(page, 'Font size');
      equal(r?.value, '11', 'the Font size field shows the argument');

      await page.click(r.input.x, r.input.y);
      await selectAll(page);
      await page.type('abc');
      await page.press('Enter');
      await sleep(600);
      r = await row(page, 'Font size');
      ok(r.invalid, '"abc" marks the field invalid');
      equal(r.error, 'Enter a whole number', 'and says why, under the field');
      await expectBytes(file, original, '"abc" was written to overview.kt');

      await page.click(r.input.x, r.input.y);
      await selectAll(page);
      await page.type('-5');
      await page.press('Tab');
      await sleep(600);
      r = await row(page, 'Font size');
      equal(r.error, 'Font size must be at least 1', '-5 is refused with its reason');
      await expectBytes(file, original, '-5 was written to overview.kt');

      // A two-key value typed then committed: one write, one undo step.
      await page.click(r.input.x, r.input.y);
      await selectAll(page);
      await page.type('18');
      await sleep(600);
      await expectBytes(file, original, 'typing wrote to overview.kt before Enter');
      await page.press('Enter');
      await waitForFile(file, (t) => t.includes('fontSize = 18'), { message: 'fontSize = 18 was never written' });
      const changes = changedSources(pristine, snapshot(fixtureDir));
      log(`write-back: ${JSON.stringify(changes)}`);
      equal(changes.map((c) => [c.file, c.lines.removed, c.lines.added]),
        [['src/screens/overview.kt', ['      fontSize = 11,'], ['      fontSize = 18,']]], 'only the fontSize argument changed');
      await sleep(400);
      equal(await undoDepth(editor), depth + 1, 'the committed value is one undo step');
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore overview.kt byte for byte');
    },
  },
  {
    id: 'E10',
    title: 'a colour shows #AARRGGBB; setting its opacity writes the same colour with the new alpha byte',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const pristine = snapshot(fixtureDir);
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      let r = await row(page, 'Color argb');
      const argb = -10525848;
      const hex = (v) => '#' + (v >>> 0).toString(16).toUpperCase().padStart(8, '0');
      equal(r?.hex, hex(argb), 'the colour shows as #AARRGGBB');
      await page.click(r.alpha.x, r.alpha.y);
      await selectAll(page);
      await page.type('50');
      await page.press('Enter');
      const half = ((argb & 0xFFFFFF) | 0x80000000) | 0;
      await waitForFile(file, (t) => t.includes(`colorArgb = ${half},`), { message: `colorArgb = ${half} was never written` });
      const changes = changedSources(pristine, snapshot(fixtureDir));
      log(`write-back: ${JSON.stringify(changes)}`);
      equal(changes.map((c) => [c.lines.removed, c.lines.added]),
        [[[`      colorArgb = ${argb},`], [`      colorArgb = ${half},`]]], 'only the colour argument changed, its RGB kept');
      r = await row(page, 'Color argb');
      equal(r.hex, hex(half), 'the hex shows the new alpha');
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore overview.kt byte for byte');
    },
  },
  {
    id: 'E8',
    title: "an Int-coded prop is a dropdown of the schema's names; choosing one writes its code",
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const pristine = snapshot(fixtureDir);
      // The layout enums, by name.
      await editor.clickOutlineRow(0); // the root Column
      equal((await row(page, 'Horizontal alignment'))?.options, ['Start', 'Center', 'End', 'Stretch'], 'Horizontal alignment lists CrossAxisAlignment by name');
      equal((await row(page, 'Width'))?.options, ['Wrap', 'Fill'], 'Width is Wrap / Fill');
      equal((await row(page, 'Overflow'))?.options, ['Clip', 'Scroll'], 'Overflow is Clip / Scroll');
      // A first argument on `Column { … }` (no argument list) goes in a new list after its name;
      // its undo gives the file back as it was.
      await editor.clickOn('.pane.right .prop-label', 'Width', { exact: true });
      const width = `document.getElementById([...document.querySelectorAll('.pane.right .prop-label')]
        .find(l => l.textContent === 'Width').getAttribute('for'))`;
      await page.press('ArrowDown');
      await sleep(800);
      if (fs.readFileSync(file, 'utf8') === original) {
        log('native select: ArrowDown wrote nothing (macOS headless) — Width set by script + change event');
        await page.eval(`(() => { const s = ${width}; s.value = '1'; s.dispatchEvent(new Event('change')); })()`);
      }
      await waitForFile(file, (t) => /Column\(width = Constraint\.Fill\) \{/.test(t), { message: 'Width = Fill was never written as Column(width = Constraint.Fill) {' });
      log(`first argument: ${JSON.stringify(changedSources(pristine, snapshot(fixtureDir)))}`);
      await page.click(1, 1);
      await editor.clickUndo();
      await waitForFile(file, (t) => t === original, { message: 'undoing Width did not give the file back' });

      // A KDoc-listed code (StyledText.align: 0 start, 1 center, 2 end, 3 justify), chosen.
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      const r = await row(page, 'Align');
      equal(r?.options, ['Start', 'Center', 'End', 'Justify'], 'Align lists its codes by name');
      equal(r.selected, 'Start', 'an unset Align shows its default');
      // A click on the label focuses the dropdown without opening its list; ArrowDown then
      // moves it to the next name (Chrome on Linux). Chrome on macOS opens its native list
      // for ArrowDown instead, which a headless browser cannot drive: there — and only
      // there — the value is set by script and a `change` dispatched, as AUDIT.md's method
      // does for native selects. Both paths go through the editor's own change handler.
      await editor.clickOn('.pane.right .prop-label', 'Align', { exact: true });
      equal(await page.eval(`document.activeElement?.tagName`), 'SELECT', 'clicking the label focuses its dropdown');
      const select = `document.getElementById([...document.querySelectorAll('.pane.right .prop-label')]
        .find(l => l.textContent === 'Align').getAttribute('for'))`;
      await page.press('ArrowDown');
      await sleep(800);
      if (fs.readFileSync(file, 'utf8') === original) {
        log(`native select: ArrowDown wrote nothing (value ${await page.eval(`${select}.value`)}; macOS headless) — set by script + change event`);
        await page.eval(`(() => { const s = ${select}; s.value = '1'; s.dispatchEvent(new Event('change')); })()`);
      }
      await waitForFile(file, (t) => t.includes('align = 1,'), { message: 'choosing Center never wrote align = 1' });
      const changes = changedSources(pristine, snapshot(fixtureDir));
      log(`write-back: ${JSON.stringify(changes)}`);
      equal(changes.map((c) => c.lines.added), [['      align = 1,']], 'only the align argument was added');
      equal((await row(page, 'Align'))?.selected, 'Center', 'the dropdown shows Center');
      await page.click(1, 1);
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore overview.kt byte for byte');
    },
  },
  {
    id: 'E12',
    title: '@ opens a picker of declared fields and writes nothing; picking one binds it without touching the Bindings interface',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const pristine = snapshot(fixtureDir);
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      const r = await row(page, 'Text');
      ok(r?.buttons['@'], 'the Text row has an @ button');
      await page.click(r.buttons['@'].x, r.buttons['@'].y);
      const offered = await page.eval(`[...document.querySelectorAll('.pane.right .bind-list [data-field]')].map(e => e.getAttribute('data-field'))`);
      equal(offered, ['subtitle'], "the picker offers the screen's String fields");
      await sleep(600);
      await expectBytes(file, original, 'opening the picker wrote to overview.kt');
      await editor.clickOn('.pane.right .bind-list [data-field]', 'subtitle');
      await waitForFile(file, (t) => t.includes('text = b.subtitle,') && !t.includes('"Synced this morning"'), { message: 'the binding was never written' });
      const changes = changedSources(pristine, snapshot(fixtureDir));
      log(`write-back: ${JSON.stringify(changes)}`);
      equal(changes.map((c) => [c.lines.removed, c.lines.added]),
        [[['      text = "Synced this morning",'], ['      text = b.subtitle,']]], 'only the argument changed: no field was added to the interface');
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore overview.kt byte for byte');
    },
  },
  {
    id: 'R3',
    title: 'a condition changed twice in its dropdown, without leaving it, writes both changes',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('logbook', { readyColor: COLORS.entryRow });
      const file = path.join(fixtureDir, 'src/screens/logbook.kt');
      const original = fs.readFileSync(file, 'utf8');
      await editor.clickOutlineRowOf('Condition', 'syncPending');
      // Native dropdowns cannot be driven over CDP: focus it as a click does, pick by script, fire `change`.
      const pick = (label) => page.eval(`(() => {
        const s = [...document.querySelectorAll('.pane.right select')].find(s => [...s.options].some(o => o.text === ${JSON.stringify(label)}));
        if (!s) return false; s.focus(); s.value = [...s.options].find(o => o.text === ${JSON.stringify(label)}).value;
        s.dispatchEvent(new Event('change')); return document.activeElement === s; })()`);
      ok(await pick('is false'), 'the test dropdown is focused when "is false" is picked');
      await waitForFile(file, (t) => t.includes('if (!b.syncPending)'), { message: '"is false" was never written' });
      await sleep(800); // its reply has come back; the dropdown still has the keyboard
      ok(await pick('is true'), 'still in the dropdown, "is true" is picked');
      await waitForFile(file, (t) => t === original, { message: 'the second change (back to "is true") was lost' });
      log('both changes written');
    },
  },
  {
    id: 'H1',
    title: 'a text edited in place on the canvas is committed by Enter once; the panel then shows the new text',
    async run({ page, editor, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const file = overviewKt(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      await editor.clickOutlineRowOf('StyledText', 'Synced this morning');
      await page.press('Enter'); // edit the selected text in place
      await page.waitFor(`document.activeElement?.classList.contains('inline-edit')`, { message: 'Enter did not open the in-place editor' });
      await page.caretToEnd();
      await page.type('!');
      await page.press('Enter');
      await waitForFile(file, (t) => t.includes('"Synced this morning!"'), { message: 'the in-place edit was never written' });
      await sleep(800);
      equal(await page.eval(`!!document.querySelector('.inline-edit')`), false, 'the in-place editor is closed, not reopened over the old text');
      equal((await editor.propField('Text'))?.value, 'Synced this morning!', 'the panel shows the text just written');
      await page.click(1, 1);
      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not give overview.kt back byte for byte');
    },
  },
];
