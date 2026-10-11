# Kmpwright implementation notes

> **Current implementation note (2026-10-11):** This page records the live
> source layout and backend boundaries. It is not a second installation guide;
> use [README.md](README.md) for onboarding and consumer release status.

Kmpwright is a Kotlin Multiplatform library with one shared API and three
backend families:

- **Android `WebView`** runs the mobile embedded-browser implementation.
- **iOS `WKWebView`** runs the mobile implementation, with the platform's
  narrower network interception surface.
- **JVM CDP** connects to a reachable Chromium DevTools Protocol endpoint,
  including owned local Chromium launch, remote, and configured cloud browsers.

## Live source sets

The core module is `kmpwright-core`:

- `src/commonMain` contains portable browser, page, locator, input, assertion,
  storage, and capability interfaces.
- `src/androidMain` contains the Android `WebView` controller and integrations.
- `src/iosMain` contains the iOS `WKWebView` controller and native bridges.
- `src/jvmMain` contains CDP transport, browser discovery, and JVM providers.

The Android-only `kmpwright-compose` module provides the Compose `WebView`
wrapper. `kmpwright-example-shared` is the shared Compose example UI; its
SwiftUI host and debug framework workflow are documented in
[example-ios/README.md](example-ios/README.md).

## Backend boundaries

Shared APIs do not imply identical browser capabilities. Network interception,
screenshots, tracing, downloads, and browser control can differ by backend.
Use `PageCapabilities` and `page.requireCapabilities(...)` before making an
optional feature part of a workflow. The root README describes the current
public feature boundaries and safe configuration guidance.

## Local verification

Use the repository's CI-equivalent script from an owner-supplied checkout:

```bash
./scripts/ci-build.sh
```

Focused source-set checks include:

```bash
./gradlew :kmpwright-core:jvmTest
./gradlew :kmpwright-core:assembleAndroidMain :kmpwright-compose:lint
./gradlew :kmpwright-core:linkReleaseFrameworkIosSim
```

The iOS task requires macOS and Xcode. The shared Compose host follows
[example-ios/README.md](example-ios/README.md). The core static
`KmpwrightCore.xcframework` is packaged and verified with an independent Swift
consumer; release consumption is documented in [docs/releasing.md](docs/releasing.md).
Only arm64 device and simulator slices are published.
