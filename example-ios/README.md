# iOS Example Host (SwiftUI)

This folder contains the debug SwiftUI/Xcode host for the shared Compose
Multiplatform example. It evaluates source from an owner-supplied checkout;
it is not the public `KmpwrightCore` consumer.

## 1) Build the KMP framework

From the repo root:

```bash
./gradlew :kmpwright-example-shared:linkDebugFrameworkIosSim
```

The framework will be at:

```
kmpwright-example-shared/build/bin/iosSim/debugFramework/KmpwrightExampleShared.framework
```

## 2) Open the Xcode project

Open:

```
example-ios/KmpwrightExampleApp.xcodeproj
```

Then run on an arm64 iOS Simulator (Apple Silicon). Current Compose
Multiplatform releases no longer ship Intel simulator artifacts. The standalone
core XCFramework also supports arm64 devices and simulators.

## Notes

- The SwiftUI host calls `createViewController()` from the KMP module, which returns a Compose-powered UIViewController.
- The project links the debug framework from `kmpwright-example-shared/build/bin/iosSim/debugFramework`.
- For a release build, run:
  ```bash
  ./gradlew :kmpwright-example-shared:linkReleaseFrameworkIosSim
  ```
- The separately distributed `KmpwrightCore.xcframework` is the static core
  library for consumers after external promotion. It is not this example UI
  framework; link that release asset with **Do Not Embed**.
