# Dependency update — 2026-10-11

The libraries, Android and shared examples, independent publication consumers,
Gradle wrapper, and GitHub Actions use the latest **stable** releases available
from their official repositories on this date. Preview Kotlin, AGP, and Compose
releases are excluded. Agent-browser remains an ignored comparison checkout and
its dependencies are not part of this build.

## Versions

| Dependency | Previous | Updated |
| --- | --- | --- |
| [coreKtx](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml) | 1.15.0 | 1.19.1 |
| [junit](https://repo.maven.apache.org/maven2/junit/junit/maven-metadata.xml) | 4.13.2 | 4.13.2 |
| [junitVersion](https://dl.google.com/dl/android/maven2/androidx/test/ext/junit/maven-metadata.xml) | 1.1.5 | 1.3.0 |
| [espressoCore](https://dl.google.com/dl/android/maven2/androidx/test/espresso/espresso-core/maven-metadata.xml) | 3.5.0 | 3.7.0 |
| [lifecycleRuntimeKtx](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/maven-metadata.xml) | 2.8.7 | 2.11.0 |
| [activityCompose](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml) | 1.9.3 | 1.13.0 |
| [composeBom](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml) | 2024.11.00 | 2026.09.00 |
| [composeMpp](https://repo.maven.apache.org/maven2/org/jetbrains/compose/runtime/runtime/maven-metadata.xml) | 1.11.0-alpha01 | 1.12.1 |
| [composeIcons](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material/material-icons-extended/maven-metadata.xml) | 1.7.3 | 1.7.3 |
| [webkit](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/maven-metadata.xml) | 1.15.0 | 1.17.1 |
| [coroutines](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) | 1.9.0 | 1.11.0 |
| [kotlinxSerializationJson](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml) | 1.7.3 | 1.11.0 |
| [mockk](https://repo.maven.apache.org/maven2/io/mockk/mockk/maven-metadata.xml) | 1.13.13 | 1.14.11 |
| [turbine](https://repo.maven.apache.org/maven2/app/cash/turbine/turbine/maven-metadata.xml) | 1.2.0 | 1.2.1 |
| [kotlinxAtomicFu](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/atomicfu/maven-metadata.xml) | 0.25.0 | 0.33.0 |
| [kotlinxCollectionsImmutable](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-collections-immutable/maven-metadata.xml) | 0.3.8 | 0.5.2 |
| [agp](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) | 9.1.1 | 9.4.1 |
| [kotlin](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml) | 2.4.10 | 2.4.21 |
| [mavenPublish](https://repo.maven.apache.org/maven2/com/vanniktech/gradle-maven-publish-plugin/maven-metadata.xml) | 0.37.0 | 0.37.0 |
| [Ktor](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml) | newly added | 3.6.0 |
| [Ksoup](https://repo.maven.apache.org/maven2/com/fleeksoft/ksoup/ksoup/maven-metadata.xml) | newly added | 0.2.6 |
| [Gradle](https://services.gradle.org/versions/current) | 9.5.0 | 9.8.1 |
| [Compose Material3](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3/maven-metadata.xml) | 1.11.0-alpha01 | 1.9.0 (latest stable) |
| [checkout](https://github.com/actions/checkout/releases/tag/v7.0.1) | v4 | v7.0.1 |
| [setup-java](https://github.com/actions/setup-java/releases/tag/v6.0.1) | v4 | v6.0.1 |
| [setup-android](https://github.com/android-actions/setup-android/releases/tag/v4.0.4) | v3 | v4.0.4 |
| [android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner/releases/tag/v2.38.0) | v2 | v2.38.0 |

Compose Material3 has its own release cadence; its latest stable version is
1.9.0 even though Compose runtime/UI/resources are 1.12.1. Material icons
Multiplatform remains at its latest published 1.7.3. JUnit 4 and Maven Publish
were already current. Compose Android tests now use the BOM rather than an
independent stale UI version. Resources now share the catalog's Compose version.
Removed unused NativeCoroutines entries pointing to nonexistent Maven artifacts,
the duplicate native coroutines version, and the unused Compose compiler version
(the compiler plugin already follows Kotlin). Stable Compose resolves from Maven
Central, so the obsolete JetBrains Space development repository is removed.

## Toolchain compatibility

JDK 17 and Android SDK 37 remain the project baselines. They are supported by
[AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes).
iOS targets are arm64 device and arm64 simulator; Intel simulator support was
removed at the owner’s request, matching current Compose support.
Minimum Android/iOS deployment targets remain API 26/iOS 15; those are runtime
compatibility promises rather than dependency versions.

[Kotlin's published compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
lists Kotlin 2.4.21 through Gradle 9.7.0 and AGP 9.3.1. This project uses the newer
stable Gradle 9.8.1/AGP 9.4.1 combination and verifies it locally; that does not
extend JetBrains' official support range. Gradle delegated source-set declarations
were migrated to getByName/create to address new Gradle 9.6 deprecations. The
wrapper distribution and generated wrapper jar use the official SHA-256 values.

AndroidX Test constraints align the debug test-manifest graph with instrumentation
dependencies, avoiding AGP consistent-resolution conflicts with older transitive
JUnit/Espresso versions. Persistent collections use the new adding/removing APIs.
A real-Chrome regression exposed CDP swallowing an outer timeout; caller
cancellation is now preserved while the transport’s own deadline still produces
a CDP error.

## Validation

The dependency checkpoint passed 802 JVM tests (including the isolated Chrome
fixture), 50 physical Android tests, Compose lint, both Android examples, and
29 iOS simulator tests. The shared Compose iOS framework linked successfully.
The core XCFramework contains arm64 device and simulator slices; archive,
checksums, deployment targets, and an independent Swift consumer passed.
Browser parity additions are verified separately in compatibility/parity-work.md.

Independent Maven consumers also passed: Kotlin Multiplatform compilation and an Android APK build using only the local published artifacts.

## Browser instrumentation

The bundled [web-vitals](https://github.com/GoogleChrome/web-vitals) collector is version 6.2.3, the npm registry's latest stable release on this audit date. The npm tarball's SHA-512 integrity was verified before extracting the IIFE bundle. Bundle SHA-256 and registry integrity are recorded in docs/licenses/web-vitals-provenance.json.

Browser inspectors also pin axe-core 4.14.0 (MPL-2.0), React DevTools Inline 8.0.0 (MIT) and a private React 19.3.0 debug-tools runtime (MIT). `scripts/update-browser-bundles.py --check` verifies npm integrity, embedded code and license provenance. The development-only React fixture pins React/React DOM 19.3.0 and esbuild 0.28.2; its generated bundle and node_modules remain under ignored `.upstream/`.
