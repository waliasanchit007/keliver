import SwiftUI
import KeliverHost

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
#if KELIVER_DEBUG_LINK
                // ED3, the Debug configuration only: the editor's "On a device" QR code, a
                // <keliver-scheme>://keliver-debug/bundles?server=… link (Info-Debug.plist).
                .onOpenURL { url in _ = KeliverDebugLinkKt.openKeliverDebugLink(url: url.absoluteString) }
#endif
        }
    }
}
