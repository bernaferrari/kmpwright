import SwiftUI
import KmpwrightExampleShared

struct KmpwrightComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.createViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No-op: Compose handles its own state.
    }
}

@main
struct KmpwrightExampleApp: App {
    var body: some Scene {
        WindowGroup {
            KmpwrightComposeView()
                .ignoresSafeArea()
        }
    }
}
