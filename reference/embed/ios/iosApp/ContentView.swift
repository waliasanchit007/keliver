import SwiftUI
import KeliverHost

/// The existing app's home screen: native views, and one Keliver screen among them.
struct ContentView: View {
    /// The host kit: a deep link's route, with only its "tab" parameter. The reference CI passes
    /// the link as a launch argument (`-k4link <uri>`): a simulator URL open keeps no console.
    private let route = KeliverRoute.companion.from(
        uri: UserDefaults.standard.string(forKey: "k4link"), allowedQueryKeys: ["tab"])

    /// The native text that took the Keliver screen's place, once it closed or could not show.
    @State private var replaced: String? = nil

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Native header").font(.title)
                NavigationLink("Native details") {
                    Text("Native details screen")
                }
                if let replaced {
                    Text(replaced).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                } else {
                    // KELIVER EMBED: the guest's screen, below the native views
                    KeliverScreen(
                        onUnavailable: { state in
                            print("ExistingApp: keliver unavailable: \(state.outcome)")
                            replaced = "Native fallback"
                        },
                        route: route,
                        onClose: {
                            print("ExistingApp: keliver closed")
                            replaced = "Keliver closed"
                        }
                    )
                }
            }
            .padding()
        }
    }
}
