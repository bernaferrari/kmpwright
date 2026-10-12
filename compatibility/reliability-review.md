# Reliability repair ledger

Baseline: public revision `9bd5ac745df83f84d21a2d5a1bb64eda61fce060`.
The external review supplied on 2026-10-11 is the repair specification. Earlier
859 JVM / 39 iOS / 55 Android results are historical checkpoints, not evidence
that the contracts below were correct. A checked item needs a regression at the
behavioral boundary. Native host checks must be distinguished from mocks.

## Core contracts

- [ ] Locator identity: filters, indexed descendants, waits, assertions, boxes,
      screenshots, uploads, nested text, frames and strict selection.
      Initial real Chromium reproduction: hasText returned 2 instead of 1.
      Shared query stages now pass LocatorIdentityRegressionTest; wider suite pending.
- [ ] Persistent navigation events and document helper lifetime; link/form/script
      navigation, URL/title tracking, close/cancellation/concurrent deadlines.
- [ ] Default iOS network bridge: normalize default ports, Fetch URL/body
      semantics, terminal errors/timeouts, 204/205/304 null bodies, XHR.
- [ ] Android keyboard typing/insertText through the actual editor connection;
      input, textarea and contenteditable device contracts.
- [ ] Cookies: numeric/session expiry, URL/domain/path identity, targeted Android
      deletion, iOS HttpOnly, validation before destructive state restoration.
- [ ] JVM context pages share target/session initialization and rollback.
- [ ] Live policies on existing/derived locators; eval/DOM extraction entry points
      and diffUrls obey the configured controls.
- [ ] Independent browser-farm leases cannot inherit saved states, init scripts,
      jobs, policies or any origin's storage.
- [ ] Routing replacement/removal, custom headers and OOPIF interception.
- [ ] Callback errors and close/disconnect complete every pending operation.

## Additional contracts

- [ ] Mobile storage getter expressions and restoring empty storage.
- [ ] Continuous 500ms NetworkIdle with a caller-owned deadline.
- [ ] Screenshot format/quality/background/scale forwarding or explicit rejection.
- [ ] Bounded, paginated Android PDFs and accurate iOS option support.
- [ ] Actual metrics or explicitly unavailable values; safely quoted script/style IDs.
- [ ] Compose uses configuration and exposes core automation with correct ownership,
      remember keys, controller replacement and WebView destruction.
- [ ] Cancellation releases held keys/touches; multi-touch configuration and mouse modifiers.
- [ ] InputMode and iOS gesture capability declarations match behavior.
- [ ] Viewport/user agent and data clearing await completion across backends.
- [ ] Tracing errors/EOF/base64 and browser-wide ownership.
- [ ] Connection health, provider reconnection cleanup and context ownership.
- [ ] Download ownership/context/path and protocol failures.
- [ ] HTTPS remote discovery retains scheme/path/query; remote uploads explicit limitation or transfer.
- [ ] Borrowed Android WebViews restore host clients.
- [ ] iOS custom-scheme cancellation and closed connection state.

## CI and presentation

- [ ] Install ripgrep before documentation verification on Ubuntu.
- [ ] Linux Compose publication uses a generated task; independent consumers pass.
- [ ] Android instrumentation green with usable failure reports and logcat.
- [ ] All relevant JVM/native/build/release checks green for the repaired revision.
- [ ] README demo shows a completed workflow; retain alpha and platform limits.

Keep KMPwright as the brand. Freeze feature expansion while repairing these
contracts. Do not mark the entire review solved from mock/string tests alone.
