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

    func makeUIViewController(context: Context) -> UIViewController {
        Keliver.shared.viewController(safeArea: safeArea)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
