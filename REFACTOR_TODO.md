# Kmpwright Refactor TODO

## Goal

Make `kmpwright` a trustworthy KMP automation library with explicit backend boundaries, fewer misleading APIs, and correct core behavior.

## Phase 1: Correctness First

- Fix shared-layer correctness bugs before adding features.
- Remove lifecycle leaks and silent no-ops where possible.
- Add tests for behavior that previously only looked implemented.

## Phase 2: Capability Model

- Expose backend capabilities in the public API.
- Make partial support explicit for Android WebView, WKWebView, and JVM CDP.
- Prefer throwing clear unsupported-operation errors over pretending features work.

## Phase 3: Backend Semantics

- Replace JS-only input simulation with transport-native input where possible.
- Harden routing/network interception semantics per backend.
- Make `BrowserContext` behavior consistent or explicitly unsupported.

## Phase 4: Parity and Tooling

- Add parity-focused tests for documented features.
- Split “supported”, “partial”, and “unsupported” in docs and examples.
- Add backend-specific smoke tests for Android and iOS instead of relying on JVM-only confidence.

## Immediate Work Queue

- [x] Fix `diffUrls()` so it actually compares two URLs.
- [x] Dispose the underlying controller when `Page.close()` is called.
- [x] Add explicit `PageCapabilities`.
- [x] Stop silent no-op context cleanup on unsupported JVM context operations.
- [x] Refactor CDP input support so JVM can expose real mouse/keyboard capabilities.
- [x] Make CDP endpoint scope explicit so page-scoped and browser-scoped sessions behave predictably.
- [x] Switch Android touch/keyboard input to native WebView event paths where possible.
- [x] Make iOS `BrowserContext` pages share an ephemeral non-persistent data store.
- [x] Replace fake JS tab management with browser-owned page/session tab handling.
- [x] Stop pretending Android `BrowserContext` offers isolated Playwright-style semantics.
- [x] Move extra HTTP headers and HTTP auth into backend-owned request configuration where supported.
- [x] Harden iOS input escaping and make tap/click flows more touch-first.
- [x] De-advertise unsupported mobile download and file chooser flows behind explicit failures.
- [x] Make session state restore navigate back to the saved origin before replaying storage.
- [x] Track observed Android WebView cookie origins and make cookie-store limits explicit.
- [x] Make iOS fetch-route continue semantics actually honor request overrides.
- [x] Make iOS Browser instances own an explicit non-persistent data store by default.
- [x] Add a pooled resource lease layer for browser/device farm orchestration.
- [x] Strengthen iOS mouse semantics so pointer state is tracked per page instead of document-wide clicks.
- [x] Add explicit page/browser reset helpers so pooled workers have a sane default reuse path.
- [x] Add a safe `withLease` path so pooled workers are released or quarantined consistently on exceptions.
- [x] Fix iOS data-store ownership so embedded WKWebView sessions reuse and clean the correct backing store.
- [x] Make WKWebView request/response events reflect real bridged completion and failure states.
- [x] Make iOS keyboard shortcut handling and DOM key metadata stop lying about printable input.
- [ ] Audit every public API for “looks supported but is partial”.
- [ ] Replace remaining JS-event mobile input shims with the strongest platform-native implementation available.
- [ ] Add Android instrumentation tests for routing, cookies, downloads, and screenshots.
- [ ] Add iOS tests for cookies, PDF, routing constraints, and cleanup behavior.
