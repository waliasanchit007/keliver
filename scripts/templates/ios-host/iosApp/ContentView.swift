import UIKit
import SwiftUI
import KeliverHost

/**
 This app's production Keliver host: a SwiftUI wrapper around the Kotlin
 `MainViewController()` in the KeliverHost framework (host-ios/build.gradle).
 Scaffolded by keliver-new-ios-host.sh; yours to edit from here on, including
 putting the view controller inside your own navigation.
 */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
