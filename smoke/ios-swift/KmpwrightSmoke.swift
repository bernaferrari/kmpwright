import UIKit
import KmpwrightCore

@main
final class KmpwrightSmokeApp: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Importing and constructing a public API proves the packaged
        // XCFramework is linkable from a fresh Swift application.
        _ = KmpwrightConfig.self
        return true
    }
}
