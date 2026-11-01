import XCTest

/// M4's T3 on iOS (ios-timing.sh): Add 1 twice on Espresso beans, so the guest recomposes
/// the open screen in a frame, as drive.py's `frames` scenario does on Android. The host is
/// already running, launched by ios-timing.sh with its console captured: this attaches to it
/// (activate) and never launches it, which would start a process without that console.
/// It finds the widgets through Compose's accessibility tree, which XCTest asks for.
/// TAP_BUNDLE_ID names the host; xcodebuild passes TEST_RUNNER_TAP_BUNDLE_ID through as it.
final class AddOneTwice: XCTestCase {
  func testAddOneTwice() throws {
    let bundleId = try XCTUnwrap(ProcessInfo.processInfo.environment["TAP_BUNDLE_ID"], "no TAP_BUNDLE_ID")
    let app = XCUIApplication(bundleIdentifier: bundleId)
    app.activate()
    let any = app.descendants(matching: .any)
    func labelled(_ format: String, _ value: String) -> XCUIElement {
      any.matching(NSPredicate(format: format, value)).firstMatch
    }
    // "On hand: N" as an element's whole label (StyledText).
    func onHand() -> Int? {
      any.matching(NSPredicate(format: "label BEGINSWITH 'On hand: '")).allElementsBoundByIndex
        .lazy.compactMap { Int($0.label.dropFirst("On hand: ".count)) }.first
    }

    // A ListItem's row may carry its texts as one label, joined with ", ".
    let row = labelled("label BEGINSWITH %@", "Espresso beans, dark roast 1kg")
    XCTAssertTrue(row.waitForExistence(timeout: 60), "F0: no Espresso beans row on screen:\n\(app.debugDescription)")
    row.tap()
    let add = labelled("label == %@", "Add 1")
    XCTAssertTrue(add.waitForExistence(timeout: 30), "F1: Espresso beans did not open:\n\(app.debugDescription)")
    let n = try XCTUnwrap(onHand(), "F1: no 'On hand: N' on the item:\n\(app.debugDescription)")
    print("AddOneTwice: F1 Espresso beans opens with On hand: \(n)")
    for i in 1...2 {
      add.tap()
      XCTAssertTrue(labelled("label == %@", "On hand: \(n + i)").waitForExistence(timeout: 30),
                    "F\(i + 1): Add 1 did not show On hand: \(n + i):\n\(app.debugDescription)")
      print("AddOneTwice: F\(i + 1) Add 1 -> On hand: \(n + i)")
    }
    // The host ends a frame window 1 s after its first sample: let it, in case the test's end
    // touches the app.
    Thread.sleep(forTimeInterval: 3)
  }
}
