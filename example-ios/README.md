# iOS example host

The SwiftUI host for the shared Compose Multiplatform sample in
[`kmpwright-example-shared`](../kmpwright-example-shared).

| iOS | Navigation |
| --- | --- |
| 26 and later | Native SwiftUI `TabView` and `NavigationStack`, so the system draws the Liquid Glass tab bar and navigation bar. Compose renders each screen. |
| 15–25 | The whole sample, navigation included, is drawn by Compose. |

## Run it

From the repository root, build the shared framework, then open the project:

```bash
./gradlew :kmpwright-example-shared:linkDebugFrameworkIosSim
open example-ios/KmpwrightExampleApp.xcodeproj
```

Run the `KmpwrightExampleApp` scheme on an arm64 simulator. The project's build
phase relinks the framework on each build. For an optimized framework, run
`./gradlew :kmpwright-example-shared:linkReleaseFrameworkIosSim`.

## How the native shell works

- `NativeStudio` (Kotlin, `iosMain`) owns one browser session: a single
  `WKWebView`, `Browser` and page shared by every tab.
- `tabViewController(tab:)` returns a Compose controller for each tab root.
  Recipes open inside the Recipes panel, so no screen is pushed and the page
  never moves.
- Each screen reports `screenDidAppear(key:)`; the visible screen adopts the
  shared `WKWebView`, so the page you are driving follows you between tabs.
- Compose sends tab changes back to Swift through the `onSelectTab` callback.
  See the
  [Liquid Glass guide](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html)
  for the pattern.

## Browser contract mode

When `KMPWRIGHT_RELIABILITY_BASE_URL` is set, the app skips the UI and runs the
iOS reliability probe against that fixture server instead. The
[`run-ios-browser-contracts.sh`](../scripts/run-ios-browser-contracts.sh) script
sets it up.
