import SwiftUI
import UIKit
import KeliverHost

/**
 This app's Keliver screen, for a SwiftUI layout: the guest's screen from the
 one Keliver host in the process (`Keliver.shared`, from the KeliverHost
 framework that keliver-host-ios/ builds). Any number of these share one
 lookup and one load. Scaffolded by keliver-new-ios-host.sh --embed.
 */
struct KeliverScreen: UIViewControllerRepresentable {
    /// Pad to the safe area; leave it off when your layout already does.
    var safeArea: Bool = false

    /// Called on the main thread when this screen cannot be shown (`outcome`: no-bundle, refused,
    /// not-loaded, or screen-failed when its code threw): replace it with what it replaced, with
    /// the route you were showing. The
    /// screen holds this closure: capture your controller weakly (`[weak self]`).
    var onUnavailable: ((KeliverHostStateUnavailable) -> Void)? = nil

    /// The guest's screen for this route (`KeliverRoute.companion.from(uri:allowedQueryKeys:)` keeps
    /// only the query keys you allow). A new route needs a new screen: give this view a new `.id`.
    var route: KeliverRoute? = nil

    /// Called on the main thread when the screen asks to close. nil pops the navigation stack
    /// back to the screen below it, or dismisses it: in a sheet or a NavigationStack, pass
    /// `dismiss()` (or drop the path element) so SwiftUI's state agrees.
    var onClose: (() -> Void)? = nil

    func makeUIViewController(context: Context) -> UIViewController {
        Keliver.shared.viewController(safeArea: safeArea, onUnavailable: onUnavailable, route: route, onClose: onClose)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
