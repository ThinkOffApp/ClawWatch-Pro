import SwiftUI

@main
struct ClawWatchAppleApp: App {
    @StateObject private var viewModel = ClawWatchViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: viewModel)
        }
    }
}
