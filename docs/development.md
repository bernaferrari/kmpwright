# Development

Contributor setup and verification commands. For library usage, see the [README](../README.md).

## Verify changes

```bash
# Build core/examples, run JVM tests and lint; also link iOS frameworks on macOS.
./scripts/ci-build.sh

# Android instrumentation: reuse each class's activity, fresh WebViews, visible test/count progress.
./scripts/run-android-tests.sh DEVICE_SERIAL

# iOS backend contracts and shared example framework (macOS only).
./gradlew :kmpwright-core:iosSimTest :kmpwright-example-shared:linkReleaseFrameworkIosSim
# Run the complete suite in an app host, including actual WKWebView documents.
./scripts/run-ios-webkit-tests.sh

# Documentation and upstream comparison metadata.
./scripts/verify-documentation.sh
./scripts/verify-agent-browser-compatibility.sh
```

Real Chromium tests require `KMPWRIGHT_TEST_CHROME` to point to the browser executable
and FFmpeg on `PATH`. To include the React fixture:

```bash
./scripts/build-react-fixture.sh
export KMPWRIGHT_TEST_CHROME="/path/to/chrome"
export KMPWRIGHT_TEST_REACT_BUNDLE="$PWD/.upstream/react-fixture/app.bundle.js"
./gradlew :kmpwright-core:jvmTest
```

[Dependency versions](dependencies.md) and [release verification](releasing.md) describe the tested toolchain and
artifact checks.


## Example apps

Android requires JDK 17 and the Android SDK. iOS requires macOS and Xcode.

```bash
./gradlew :example-android:installDebug
./gradlew :kmpwright-example-shared:linkDebugFrameworkIosSim
open example-ios/KmpwrightExampleApp.xcodeproj
```

See the [iOS host guide](../example-ios/README.md) and [website development guide](../website/README.md).
