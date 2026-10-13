import UIKit
import WebKit
import KmpwrightCore

@main
final class KmpwrightSmokeApp: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Compiling this file against the packaged XCFramework proves the API links from a fresh
        // Swift application; scripts/verify-ios-xcframework.sh launches it with the argument below
        // on a simulator to prove Kotlin exceptions arrive as catchable Swift errors.
        if ProcessInfo.processInfo.arguments.contains("--kmpwright-swift-smoke") {
            Task { @MainActor in
                let passed = await SwiftErrorSmoke.run()
                print(passed ? "KMPWRIGHT_SWIFT_SMOKE PASSED" : "KMPWRIGHT_SWIFT_SMOKE FAILED")
                exit(passed ? 0 : 1)
            }
        }
        return true
    }
}

/// Recent iOS versions require the scene lifecycle; the window only hosts a placeholder.
final class KmpwrightSmokeSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()
        self.window = window
    }
}

/// Calls KMPwright from Swift the way an app does: `async throws` methods, labels from the
/// generated header, every parameter spelled out (Objective-C export has no default arguments).
@MainActor
enum SwiftErrorSmoke {
    static func run() async -> Bool {
        var passed = true
        func check(_ condition: Bool, _ label: String) {
            print("\(condition ? "ok  " : "FAIL") \(label)")
            if !condition { passed = false }
        }

        let config: KmpwrightConfig
        do {
            config = KmpwrightConfig(
                allowedOriginRules: [], installMode: .automatic, inputMode: .hybrid,
                timeouts: try TimeoutConfig(default: 30_000, navigation: 30_000, action: 30_000, waitForSelector: 30_000, handler: 30_000),
                debug: DebugConfig(enabled: false, showHighlight: false, logJsCommands: false, exportTraceOnFailure: true, showDebugOverlay: true),
                productionMode: false, strictSelectors: true, allowEval: true, allowDomExtraction: true,
                enableTraceExport: true, viewport: nil, userAgent: nil, iosSchemeInterception: nil, cdp: nil
            )
        } catch {
            check(false, "TimeoutConfig init threw: \(error)")
            return false
        }
        // A constructor's require() surfaces as a Swift error too (IllegalArgumentException).
        do {
            _ = try TimeoutConfig(default: 30_000, navigation: 30_000, action: 30_000, waitForSelector: 30_000, handler: 0)
            check(false, "TimeoutConfig(handler: 0) should throw")
        } catch {
            let kotlin = (error as NSError).kotlinException
            print("     TimeoutConfig(handler: 0) -> \(String(describing: kotlin))")
            check(kotlin is KotlinIllegalArgumentException, "constructor require() is a catchable IllegalArgumentException")
        }

        let browser = Browser(context: IOSPlatformContext(webView: nil, websiteDataStore: nil), config: config)
        defer { browser.dispose() }
        let page: Page
        do {
            page = try browser.page()
        } catch {
            check(false, "browser.page() threw: \(error)")
            return false
        }

        do {
            try await page.setContent(html: "<p id=msg>hello</p>", waitUntil: .load_, timeoutMs: nil)
            try await ExpectKt.expect(locator: page.locator(selector: "#msg"))
                .toHaveText(text: "hello", timeoutMs: KotlinLong(value: 2_000), useInnerText: false)
            check(true, "setContent and a passing assertion complete without throwing")
        } catch {
            check(false, "unexpected error: \(error)")
        }

        // A locator action that times out throws KmpwrightTimeoutException.
        do {
            try await page.locator(selector: "#missing").click(
                button: .left, clickCount: 1, delayMs: 0, position: nil, force: false, timeoutMs: KotlinLong(value: 200)
            )
            check(false, "click on a missing element should throw")
        } catch {
            let nsError = error as NSError
            let kotlin = nsError.kotlinException as? KotlinThrowable
            print("     click -> domain=\(nsError.domain) description=\(nsError.localizedDescription)")
            print("     kotlinException=\(String(describing: kotlin)) message=\(kotlin?.message ?? "nil")")
            check(kotlin is KmpwrightTimeoutException, "click timeout is KmpwrightTimeoutException")
            check(kotlin is KmpwrightException, "KmpwrightTimeoutException is a KmpwrightException")
            check(nsError.localizedDescription.contains("#missing"), "NSError description carries the Kotlin message")
        }

        // A failed web-first assertion throws KmpwrightAssertionError (an AssertionError, not a KmpwrightException).
        do {
            try await ExpectKt.expect(locator: page.locator(selector: "#msg"))
                .toHaveText(text: "goodbye", timeoutMs: KotlinLong(value: 200), useInnerText: false)
            check(false, "a failing assertion should throw")
        } catch {
            let nsError = error as NSError
            let kotlin = nsError.kotlinException as? KotlinThrowable
            print("     toHaveText -> description=\(nsError.localizedDescription)")
            check(kotlin is KmpwrightAssertionError, "assertion failure is KmpwrightAssertionError")
            check(!(kotlin is KmpwrightException), "KmpwrightAssertionError is not a KmpwrightException")
            check(kotlin?.message?.contains("goodbye") == true, "assertion message names the expected text")
        }

        // The page is still usable after the caught errors.
        do {
            let title = try await page.evaluate(expression: "document.getElementById('msg').textContent")
            check(title.value == "hello", "page still usable after caught errors (evaluate -> \(title.value))")
        } catch {
            check(false, "evaluate after errors threw: \(error)")
        }
        return passed
    }
}
