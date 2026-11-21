import SwiftUI
import KeliverHost

@main
struct iOSApp: App {
    // NF4: the host starts here, at launch, so the bundle lookup and the code load run while the
    // first scene connects (as the Android host's HostApp does). A launch in the background (a
    // silent push, a background refresh) loads nothing: the host waits until the app comes to the
    // foreground or a Keliver screen is made (UiStart.kt). An app whose Keliver screens are rarely
    // opened can delete the start() line: the first screen starts the host.
    //
    // To have your first screen's data on its way while the bundle loads, declare the GET it
    // makes first (HostPrefetch.kt has the rules), before start():
    //   Keliver.shared.prefetch = [KeliverPrefetch(path: "/items", query: ["page": "1"])]
    // It goes out with your requestHeaders as they are when the app comes to the foreground, so
    // restore your session by then (before start() is the simple way to be sure), and call
    // Keliver.shared.discardPrefetched() when the user signs in, signs out or changes. If your
    // first screen establishes the session (a PIN, biometric or login screen), don't declare one.
    init() {
        Keliver.shared.start()
    }

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
