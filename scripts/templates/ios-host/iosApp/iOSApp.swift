import SwiftUI
import KeliverHost

@main
struct iOSApp: App {
    // NF4: to have your first screen's data on its way while the bundle loads, declare the GET it
    // makes first (HostPrefetch.kt has the rules) and start the host here, in an init():
    //   Keliver.shared.prefetch = [KeliverPrefetch(path: "/items", query: ["page": "1"])]
    //   Keliver.shared.start()
    // It goes out with your requestHeaders as they are when the app comes to the foreground, so
    // restore your session BEFORE Keliver.shared.start(), and call Keliver.shared.discardPrefetched()
    // when the user signs in, signs out or changes. If your first screen establishes the session
    // (a PIN, biometric or login screen), don't declare a prefetch.
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
