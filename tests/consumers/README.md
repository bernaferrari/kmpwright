# Published-artifact consumers

These small, independent projects test the library as a downstream application.
They resolve Maven artifacts or a packaged XCFramework rather than depending on
the library project directly. Keep their source and configuration in Git; build
outputs, SDK paths and caches are ignored.

- `kmp/`: compiles a Kotlin/JVM workflow against the published multiplatform core.
- `android/`: builds an Android app using the core and Compose Maven artifacts.
- `swift/`: compiles and runs against the packaged iOS XCFramework, including
  Kotlin exceptions crossing the Swift boundary.

Run `scripts/verify-publication.sh` for the Maven consumers and
`scripts/verify-ios-xcframework.sh` for Swift. These checks catch missing artifacts,
incorrect dependency metadata and framework packaging errors before a release.
