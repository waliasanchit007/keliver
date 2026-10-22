// AUDIT E1/E2: clicking a component instance on the canvas selects that
// instance, and a click anywhere inside it never selects a node outside it.
import path from 'node:path';
import fs from 'node:fs';
import { equal, ok, SoftAsserts } from '../lib/assert.mjs';
import { nodes } from '../lib/editor.mjs';
import { changedSources, expectBytes, snapshot, waitForFile } from '../lib/fixture.mjs';
import { COLORS, OVERVIEW_INSTANCE_COLORS } from './fixture-map.mjs';

/** What is selected now: the handle, the properties header and the selected outline rows. */
async function selection(editor) {
  return { handle: await editor.selectedHandle(), header: await editor.propsHeader(), rows: await editor.selectedRows() };
}

const describe = (byHandle, h) => (h == null ? 'nothing' : byHandle.has(h) ? `${byHandle.get(h).type} #${h}` : `#${h} (not in the screen)`);

export default [
  {
    id: 'E1',
    title: 'clicking the centre of each component instance selects that instance',
    async run({ page, editor, relay, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const doc = await relay.doc('overview');
      const componentNames = new Set((await relay.components()).map((c) => c.name));
      const order = nodes(doc).map((n) => n.handle);
      // The instances drawn with a colour of their own (an opaque one previews as a placeholder).
      const instances = doc.children.filter((n) => componentNames.has(n.type) && OVERVIEW_INSTANCE_COLORS[n.type] != null);
      ok(instances.length >= 3, `the overview's root Column holds component instances (found ${instances.length})`);

      // Where each instance is drawn, measured before anything is selected (no overlay on top).
      const drawn = new Map();
      for (const inst of instances) {
        const bounds = await editor.findColor(OVERVIEW_INSTANCE_COLORS[inst.type]);
        ok(bounds, `${inst.type} #${inst.handle} is drawn on the canvas`);
        drawn.set(inst.handle, bounds);
      }

      const soft = new SoftAsserts();
      for (const inst of instances) {
        const bounds = drawn.get(inst.handle);
        await editor.clearSelection(doc.handle);
        await page.click(bounds.cx, bounds.cy, { settle: 150 });
        const sel = await selection(editor);
        log(`${inst.type} #${inst.handle}: clicked (${bounds.cx}, ${bounds.cy}) -> ${sel.header}`);
        soft.equal(sel.header, `${inst.type} #${inst.handle} · component`, `centre of ${inst.type} #${inst.handle}: properties header`);
        const rows = await editor.outline();
        const selectedIdx = rows.map((r, i) => (r.selected ? i : -1)).filter((i) => i >= 0);
        soft.equal(selectedIdx, [order.indexOf(inst.handle)], `centre of ${inst.type} #${inst.handle}: the selected outline row`);
        // The selection overlay outlines the whole instance, not one widget inside it.
        const overlay = await editor.selectionOverlay();
        soft.ok(overlay && Math.abs(overlay.x - bounds.left) <= 2 && Math.abs(overlay.y - bounds.top) <= 2 &&
          Math.abs(overlay.width - bounds.width) <= 2 && Math.abs(overlay.height - bounds.height) <= 2,
        `${inst.type} #${inst.handle}: the selection overlay ${JSON.stringify(overlay)} matches its drawn bounds ` +
          `${JSON.stringify({ x: bounds.left, y: bounds.top, width: bounds.width, height: bounds.height })}`);
        await page.screenshot(`E1-${inst.type}`);
      }
      soft.check('component instances selected by a canvas click');
    },
  },
  {
    id: 'E2',
    title: 'a click inside a component (nested components, its button) never selects a node outside it',
    async run({ page, editor, relay, fixtureDir, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const doc = await relay.doc('overview');
      const specs = await relay.components();
      const byName = new Map(specs.map((s) => [s.name, s]));
      const all = nodes(doc);
      const byHandle = new Map(all.map((n) => [n.handle, n]));

      // Precondition: the collision is real. A component call nested in a
      // definition carries a definition-local id; at least one of them must
      // equal the handle of a screen node outside the instance, or this test
      // no longer exercises what broke (adjust the fixture if it fails).
      const targets = doc.children.filter((n) => byName.get(n.type)?.body && nodes(byName.get(n.type).body).some((b) => byName.has(b.type)));
      ok(targets.length >= 2, 'the overview holds instances whose definitions nest other components');
      const collisions = [];
      for (const inst of targets) {
        const subtree = new Set(nodes(inst).map((n) => n.handle));
        for (const b of nodes(byName.get(inst.type).body)) {
          if (byName.has(b.type) && byHandle.has(b.id) && !subtree.has(b.id)) {
            collisions.push(`${inst.type}'s nested ${b.type} has id ${b.id} = screen ${describe(byHandle, b.id)}`);
          }
        }
      }
      log(`definition-local ids that collide with screen handles:\n  ${collisions.join('\n  ')}`);
      ok(collisions.length >= 2, `the fixture produces id collisions for nested components (found: ${collisions.join('; ') || 'none'})`);

      // Points to click: the nested parts (found by colour), then a grid over each card.
      const soft = new SoftAsserts();
      const clicks = [];
      const summary = doc.children.find((n) => n.type === 'SummaryCard');
      const action = doc.children.find((n) => n.type === 'ActionCard');
      for (const [what, color, inst] of [
        ['StatTile "Visits" in SummaryCard', COLORS.statVisits, summary],
        ['StatTile "Samples" in SummaryCard', COLORS.statSamples, summary],
        ['StatTile "Alerts" in SummaryCard', COLORS.statAlerts, summary],
        ['the Start button in ActionCard', COLORS.startButton, action],
      ]) {
        const b = await editor.findColor(color);
        ok(b, `${what} is drawn on the canvas`);
        clicks.push({ what, x: b.cx, y: b.cy, inst });
      }
      for (const [inst, color] of [[summary, COLORS.summaryCard], [action, COLORS.actionCard]]) {
        const b = await editor.findColor(color);
        for (let y = b.top + 8; y <= b.bottom - 8; y += 36) {
          for (let x = b.left + 8; x <= b.right - 8; x += 40) clicks.push({ what: `${inst.type} grid (${x}, ${y})`, x, y, inst });
        }
      }
      log(`${clicks.length} clicks inside ${targets.map((t) => `${t.type} #${t.handle}`).join(' and ')}`);

      for (const c of clicks) {
        await editor.clearSelection(doc.handle);
        await page.click(c.x, c.y, { settle: 60 });
        const h = await editor.selectedHandle();
        const subtree = new Set(nodes(c.inst).map((n) => n.handle));
        soft.ok(subtree.has(h), `${c.what} selected ${describe(byHandle, h)}, outside ${c.inst.type} #${c.inst.handle}`);
      }
      await page.screenshot('E2-after-grid');
      soft.check('clicks inside component instances');

      // The selection is the instance, so an edit lands on it — surgically.
      // Click the button inside ActionCard, append one character to its label
      // in the properties panel, and check the screen file changed in that
      // argument only; Undo restores it byte for byte.
      const file = path.join(fixtureDir, 'src/screens/overview.kt');
      const before = snapshot(fixtureDir);
      const original = fs.readFileSync(file, 'utf8');
      const button = await editor.findColor(COLORS.startButton);
      await editor.clearSelection(doc.handle);
      await page.click(button.cx, button.cy);
      equal(await editor.propsHeader(), `ActionCard #${action.handle} · component`, 'clicking the Start button selects ActionCard');
      const field = await editor.propField('Label');
      ok(field, 'the properties panel has a Label field for ActionCard');
      equal(field.value, 'Restock the kit', "the Label field shows the instance's argument");
      await editor.appendToField('Label', '!', { commit: true });
      const edited = await waitForFile(file, (t) => t !== original, { message: 'the edit was never written to overview.kt' });
      const changes = changedSources(before, snapshot(fixtureDir));
      equal(changes, [{ file: 'src/screens/overview.kt', lines: { at: changes[0]?.lines.at,
        removed: ['      label = "Restock the kit",'], added: ['      label = "Restock the kit!",'] } }],
      'the edit changed only the ActionCard label argument in overview.kt');
      log(`write-back: ${JSON.stringify(changes)}`);
      ok(edited.includes('label = "Restock the kit!"'), 'overview.kt has the new label');

      await editor.clickUndo();
      await expectBytes(file, original, 'Undo did not restore overview.kt byte for byte');
      equal(changedSources(before, snapshot(fixtureDir)), [], 'after Undo every source file is byte-identical');
    },
  },
];
