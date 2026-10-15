import SwiftUI
import WebKit
import KmpwrightExampleShared

// MARK: - Compose hosting

/// Hosts any Compose-backed view controller produced by the shared module.
struct ComposeScreen: UIViewControllerRepresentable {
    let make: () -> UIViewController

    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// MARK: - iOS 26+: native Liquid Glass navigation

/// A recipe pushed onto the Recipes stack. Identity is per push, not per recipe.
@available(iOS 26.0, *)
struct RecipeRoute: Hashable, Identifiable {
    let id = UUID()
    let recipeId: String
    let title: String
}

/// Navigation state shared between SwiftUI and the Kotlin studio session.
@available(iOS 26.0, *)
@Observable
final class StudioNavigation {
    enum Tab: String, Hashable { case agent = "Agent", recipes = "Recipes", inspect = "Inspect" }

    var selectedTab: Tab = .agent
    var recipePath: [RecipeRoute] = []

    /// One browser session for every tab; Compose screens are created from it.
    @ObservationIgnored private(set) var studio: NativeStudio!

    init() {
        studio = NativeStudio(
            onSelectTab: { [weak self] tab in
                self?.selectedTab = Tab(rawValue: tab) ?? .agent
            },
            onOpenRecipe: { [weak self] id, title in
                self?.recipePath.append(RecipeRoute(recipeId: id, title: title))
            }
        )
    }

    deinit { studio.close() }

    /// A tab root. Compose draws its own header, so the system bar stays hidden;
    /// the title is still set because iOS 26 uses it to label the tab.
    func tabRoot(_ tab: Tab) -> some View {
        ComposeScreen { self.studio.tabViewController(tab: tab.rawValue) }
            .ignoresSafeArea()
            .navigationTitle(tab.rawValue)
            .toolbarVisibility(.hidden, for: .navigationBar)
            .onAppear { self.studio.screenDidAppear(key: tab.rawValue) }
    }
}

@available(iOS 26.0, *)
struct LiquidGlassStudio: View {
    @State private var navigation = StudioNavigation()

    var body: some View {
        TabView(selection: $navigation.selectedTab) {
            Tab("Agent", systemImage: "sparkles", value: StudioNavigation.Tab.agent) {
                NavigationStack { navigation.tabRoot(.agent) }
            }
            Tab("Recipes", systemImage: "book.pages", value: StudioNavigation.Tab.recipes) {
                NavigationStack(path: $navigation.recipePath) {
                    navigation.tabRoot(.recipes)
                        .navigationDestination(for: RecipeRoute.self) { route in
                            ComposeScreen { navigation.studio.recipeViewController(id: route.recipeId) }
                                .ignoresSafeArea()
                                .navigationTitle(route.title)
                                .toolbarTitleDisplayMode(.inline)
                                .onAppear { navigation.studio.screenDidAppear(key: "recipe:\(route.recipeId)") }
                        }
                }
            }
            Tab("Inspect", systemImage: "magnifyingglass", value: StudioNavigation.Tab.inspect) {
                NavigationStack { navigation.tabRoot(.inspect) }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(Color(red: 0.36, green: 0.31, blue: 0.77))
    }
}

// MARK: - iOS 15–25: everything drawn by Compose

struct ComposeStudio: View {
    var body: some View {
        ComposeScreen { MainViewControllerKt.createViewController() }
            .ignoresSafeArea()
    }
}

// MARK: - Browser contracts (test launch mode)

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

    func updateUIView(_ view: WKWebView, context: Context) {}
}

// MARK: - App

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
            } else if #available(iOS 26.0, *) {
                LiquidGlassStudio()
            } else {
                ComposeStudio()
            }
        }
    }
}
