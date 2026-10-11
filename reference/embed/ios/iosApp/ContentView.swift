import SwiftUI

/// The existing app's home screen: native views, and one Keliver screen among them.
struct ContentView: View {
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Native header").font(.title)
                NavigationLink("Native details") {
                    Text("Native details screen")
                }
                KeliverScreen() // KELIVER EMBED: the guest's screen, below the native views
            }
            .padding()
        }
    }
}
