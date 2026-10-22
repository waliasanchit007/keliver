// What the fixture app (editor-e2e/fixture) draws, so tests can find things on
// the canvas by colour — independently of the editor's own hit-testing, which
// is what they test. Every colour here is unique on its screen; keep it so
// when you change the fixture (a colour used twice makes findColor return the
// union of both).

export const COLORS = {
  // overview
  summaryCard: -13730453, // SummaryCard's card (teal)
  statVisits: -14776091, // the three StatTiles nested in SummaryCard
  statSamples: -1754827,
  statAlerts: -7461718,
  actionCard: -19712, // ActionCard's card (amber)
  startButton: -9614271, // the Button inside ActionCard
  recentSitesTitle: -5051406, // the screen-level CardTitle's tint
  // logbook
  entryRow: -10720320, // the Repeat's EntryRow (every row)
  syncBanner: -36797, // inside `if (b.syncPending)`
  archive: -2634552, // the tall box that pushes the footer below the frame
  footer: -16732991, // "End of log": only reachable by scrolling
};

/** For each screen-level component instance type on overview, the colour its rendered bounds are found by. */
export const OVERVIEW_INSTANCE_COLORS = {
  SummaryCard: COLORS.summaryCard,
  ActionCard: COLORS.actionCard,
  CardTitle: COLORS.recentSitesTitle,
};
