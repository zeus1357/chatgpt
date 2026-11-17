import SwiftUI

@main
struct BtFileWatchApp: App {
    @StateObject private var receiver = BLEReceiver()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(receiver)
        }
    }
}
