import XCTest

/// M4's T3 on iOS (ios-timing.sh): Add 1 twice on Espresso beans, so the guest recomposes
/// the open screen in a frame, as drive.py's `frames` scenario does on Android. The host is
/// already running, launched by ios-timing.sh with its console captured: this attaches to it
/// (activate) and never launches it, which would start a process without that console.
/// It finds the widgets through Compose's accessibility tree, which XCTest asks for.
/// TAP_BUNDLE_ID names the host; xcodebuild passes TEST_RUNNER_TAP_BUNDLE_ID through as it.
final class AddOneTwice: XCTestCase {
  override func setUp() {
    continueAfterFailure = false
  }

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

    // On iOS a ListItem's row is labelled with its trailing icon's description
    // ("KeyboardArrowRight", which wins over its texts), so the row is found by its headline,
    // a text Compose keeps in the tree inside the row. Should XCTest not report that text,
    // the first row is Espresso beans (the seed order, no search): tap its arrow. Either way
    // the item screen must then show the name.
    let name = "Espresso beans, dark roast 1kg"
    let headline = labelled("label == %@", name)
    if headline.waitForExistence(timeout: 60) {
      headline.tap()
    } else {
      let arrow = labelled("label == %@", "KeyboardArrowRight")
      XCTAssertTrue(arrow.exists, "F0: no Espresso beans row on screen:\n\(app.debugDescription)")
      print("AddOneTwice: F0 no headline text in the tree; tapping the first row's arrow")
      arrow.tap()
    }
    let add = labelled("label == %@", "Add 1")
    XCTAssertTrue(add.waitForExistence(timeout: 30), "F1: no item opened:\n\(app.debugDescription)")
    XCTAssertTrue(labelled("label == %@", name).exists, "F1: the open item is not Espresso beans:\n\(app.debugDescription)")
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
