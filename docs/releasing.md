# Releasing Kmpwright

This is the operator and consumer guide for the first public release. It
describes the intended release contract; it does not mean that a release has
already been published.

## Release identity

- Repository: [canonical Kmpwright repository](https://github.com/bernaferrari/kmpwright)
- License: MIT ([`LICENSE`](../LICENSE)), copyright 2026 Bernardo Ferrari
- Versioning: Semantic Versioning; the first release is `0.1.0`
- Maven group: `io.github.bernaferrari`
- Maven artifacts: `kmpwright-core` and `kmpwright-compose`
- iOS minimum: 15.0
- Apple binaries: device `arm64`; simulator `arm64`
- CocoaPods: not supported for the first release

The published Maven coordinates will therefore be
`io.github.bernaferrari:kmpwright-core:0.1.0` and
`io.github.bernaferrari:kmpwright-compose:0.1.0`. Confirm the generated module
metadata and POMs before announcing a release.

## Operator checklist

1. Start from the intended release commit and confirm the version, group, and
   artifact names in the generated publication metadata. Do not publish from
   a dirty checkout.
2. Validate the release tag and run the repository's release gates. The tag
   script writes `RELEASE_VERSION` to `GITHUB_ENV` in CI; locally, export it
   after validating the tag:

   ```sh
   ./scripts/validate-release-tag.sh v0.1.0
   export RELEASE_VERSION=0.1.0
   ./scripts/verify-publication.sh
   ./scripts/verify-ios-xcframework.sh
   ```

   `verify-publication.sh` stages both modules into
   `build/test-repository` and compiles independent JVM/KMP and Android
   consumer fixtures. On macOS, `verify-ios-xcframework.sh` also packages and
   verifies the static iOS artifact, its slices, deployment target, checksum,
   and Swift consumer. These gates are already the release workflow's exact
   commands; do not substitute historical task names.
3. Configure signing and repository access through the CI secret store or
   environment. The workflow maps these GitHub secrets to the corresponding
   Gradle properties: `MAVEN_CENTRAL_USERNAME` and
   `MAVEN_CENTRAL_PASSWORD` become
   `ORG_GRADLE_PROJECT_mavenCentralUsername` and
   `ORG_GRADLE_PROJECT_mavenCentralPassword`; signing uses
   `MAVEN_SIGNING_IN_MEMORY_KEY`, `MAVEN_SIGNING_IN_MEMORY_KEY_ID`, and
   `MAVEN_SIGNING_IN_MEMORY_KEY_PASSWORD`, mapped to
   `ORG_GRADLE_PROJECT_signingInMemoryKey`,
   `ORG_GRADLE_PROJECT_signingInMemoryKeyId`, and
   `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`. Values must never be
   committed, pasted into this guide, or logged.
4. Stage locally before any remote upload. `verify-publication.sh` uses the
   exact local repository at `build/test-repository`; inspect both artifacts,
   POMs, Gradle metadata, sources, and documentation jars there.
5. Verify every staged file before upload. Compare SHA-256 checksums against
   the release bundle, check that signatures verify with the expected public
   key, and inspect the POM/license/source attachments. Reject a staging set
   with missing signatures, unexpected coordinates, or a version other than
   `0.1.0`.
6. Upload only after local verification passes. The tag workflow runs
   `scripts/publish-central-idempotent.sh`, which checks Maven Central first,
   publishes each artifact only when its POM is absent, and waits for the POM
   to become discoverable. This makes retries safe for immutable versions.
   Record the immutable release
   commit, published coordinates, checksums, and signing-key fingerprint in
   the release record. No external release is implied by this document.

## Artifact verification

For a local staging directory, produce a reviewable inventory and checksums:

```sh
find /path/to/staging/io/github/bernaferrari -type f -print | sort
find /path/to/staging/io/github/bernaferrari -type f -print0 \
  | sort -z | xargs -0 shasum -a 256
```

Check that the inventory contains both Maven modules at the same SemVer, the
POM and module metadata, source and documentation artifacts where configured,
and detached signatures for signed files. Verify signatures with the
publisher's trusted public key before copying the checksums into the release
record. A consumer should resolve artifacts only from the intended repository,
then retain the resolved version and checksum in its build provenance.

## iOS XCFramework consumption

The first release is consumed as the versioned static
`KmpwrightCore-0.1.0.xcframework.zip` asset (replace `0.1.0` with the released
version), not through CocoaPods. Verify the adjacent `.sha256` file before
extraction, add the framework to the Xcode target, and set **Do Not Embed** for
this static library. The bundle must contain slices for
`arm64-apple-ios` and simulator `arm64`, and declare iOS 15.0
as its minimum deployment target. Device builds must link the device slice;
simulator builds must link a simulator slice.

Before distributing an app, inspect the bundle with `xcodebuild -showBuildSettings`
and `lipo -info` (or `file`) for each binary, then build a device target and
both simulator architectures. Treat a missing slice, an iOS deployment-target
mismatch, or a signing failure as a release blocker. Consumers should retain
the exact XCFramework archive checksum alongside the Maven dependency
checksums when both integrations are used.

## Rollback and post-release verification

If verification fails, stop promotion and retain the local staging directory
and logs. Do not overwrite a published version: SemVer releases are
immutable. Disable or remove the bad repository staging entry using the
repository provider's controls, then publish a corrected increment (for
example, `0.1.1`) only after the full checklist passes. If a bad version is
already visible, mark it as withdrawn in the release record and communicate
the affected coordinates; consumers should pin a verified version.

After promotion, resolve each coordinate from the intended repository and
compare its POM, module metadata, signatures, and checksums with the reviewed
staging set. Download the iOS archive again and repeat slice and deployment
target checks. Record the verification timestamp and results. Until these
checks have passed, describe the release as staged or pending—not published.
