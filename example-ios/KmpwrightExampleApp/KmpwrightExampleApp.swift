import SwiftUI
import WebKit
import KmpwrightExampleShared

struct KmpwrightComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.createViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // No-op: Compose handles its own state.
    }
}

private struct ReliabilityHost: UIViewRepresentable {
    let baseUrl: String
    @Binding var progress: String

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        DispatchQueue.main.async {
            IOSReliabilityProbe().start(view: view, baseUrl: baseUrl, progress: { progress = $0 }, completion: { report in
                progress = report
                let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("kmpwright-reliability.json")
                do { try report.write(to: file, atomically: true, encoding: .utf8) }
                catch { print("Could not save reliability report: \(error)") }
            })
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) { }
}

@main
struct KmpwrightExampleApp: App {
    @State private var progress = "Starting browser contracts"

    var body: some Scene {
        WindowGroup {
            if let baseUrl = ProcessInfo.processInfo.environment["KMPWRIGHT_RELIABILITY_BASE_URL"] {
                VStack {
                    Text(progress).font(.system(.caption, design: .monospaced)).padding()
                    ReliabilityHost(baseUrl: baseUrl, progress: $progress)
                }
            } else {
                KmpwrightComposeView().ignoresSafeArea()
            }
        }
    }
}
