import XCTest

/// Add 1, TAP_COUNT times, on Espresso beans in the inventory reference app's iOS host, each
/// tap observed ("On hand: N+1", …), as drive.py does on Android:
///   - ios.sh's P3: three taps from TAP_FROM=12 (13, 14, 15), the presenter running from the
///     signed bundle;
///   - ios-timing.sh's T3 (M4): two taps, from whatever it has on hand, so the guest
///     recomposes the open screen in a frame.
/// The host is already running, launched by the script with its console captured: this
/// attaches to it (activate) and never launches it, which would start a process without that
/// console. It finds the widgets through Compose's accessibility tree, which XCTest asks for.
/// xcodebuild passes TEST_RUNNER_TAP_BUNDLE_ID (the host), TEST_RUNNER_TAP_COUNT (default 2)
/// and TEST_RUNNER_TAP_FROM (optional) through without their prefix (tap.sh).
final class AddOne: XCTestCase {
  override func setUp() {
    continueAfterFailure = false
  }

  func testAddOne() throws {
    let env = ProcessInfo.processInfo.environment
    let bundleId = try XCTUnwrap(env["TAP_BUNDLE_ID"], "no TAP_BUNDLE_ID")
    let count = try XCTUnwrap(Int(env["TAP_COUNT"] ?? "2"), "TAP_COUNT is not a number")
    XCTAssertGreaterThan(count, 0, "TAP_COUNT must be at least 1")
    var from: Int?
    if let text = env["TAP_FROM"], !text.isEmpty {
      from = try XCTUnwrap(Int(text), "TAP_FROM is not a number")
    }
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
      print("AddOne: no headline text in the tree; tapping the first row's arrow")
      arrow.tap()
    }
    let add = labelled("label == %@", "Add 1")
    XCTAssertTrue(add.waitForExistence(timeout: 30), "F1: no item opened:\n\(app.debugDescription)")
    XCTAssertTrue(labelled("label == %@", name).exists, "F1: the open item is not Espresso beans:\n\(app.debugDescription)")
    let n = try XCTUnwrap(onHand(), "F1: no 'On hand: N' on the item:\n\(app.debugDescription)")
    if let from {
      XCTAssertEqual(n, from, "F1: Espresso beans opens with On hand: \(n), not \(from)")
    }
    print("AddOne: F1 Espresso beans opens with On hand: \(n)")
    for i in 1...count {
      add.tap()
      XCTAssertTrue(labelled("label == %@", "On hand: \(n + i)").waitForExistence(timeout: 30),
                    "F\(i + 1): Add 1 did not show On hand: \(n + i):\n\(app.debugDescription)")
      print("AddOne: F\(i + 1) Add 1 -> On hand: \(n + i)")
    }
    // The host ends a frame window 1 s after its first sample: let it, in case the test's end
    // touches the app.
    Thread.sleep(forTimeInterval: 3)
  }
}
