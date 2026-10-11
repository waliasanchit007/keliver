import SwiftUI
import KeliverHost // KELIVER EMBED

@main
struct ExistingAppMain: App {
    init() {
        Keliver.shared.start() // KELIVER EMBED: look the bundle up while the first screen comes up
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
