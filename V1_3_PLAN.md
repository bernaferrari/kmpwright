# Kmpwright v1.3.0 Architecture Plan

> **Superseded plan (2026-08-30):** This file records an earlier architecture proposal. Its checklist does not describe the current release state; use [README.md](README.md), the tracked plans in [`plans/`](plans/), and Beads for active work.

## Puppeteer Comparison

| Puppeteer | Kmpwright | Status |
|-----------|-----------|--------|
| Browser | KmpwrightController | ✅ Done |
| BrowserContext | BrowserContext | ✅ Done (needs improvement) |
| Page | Page | ✅ Done |
| Frame | (implicit) | ✅ Done |
| Target | - | ❌ Missing |
| Worker | - | ❌ Missing |
| BrowserContext.overridePermissions | NetworkModule | ✅ Done |
| page.setRequestInterception | RequestInterceptor | ❌ Missing |
| page.setRouteFor interception | MockAPI | ❌ Missing |
| Service Workers | ServiceWorkerRegistry | ❌ Missing |
| Performance API | PerformanceMetrics | ❌ Missing |

## Priority Implementation Order

### Phase 1: Core Infrastructure (High Priority)
1. **Enhanced BrowserContext with Multi-Tab Support**
   - Create `Browser` class (top-level container)
   - Add `browser.pages()` listing
   - Add `browser.createTarget()` for new pages
   - Add `browser.targets()` for all targets (pages, workers, service workers)

2. **Request/Response Interception with Body Capture**
   - Modify `NetworkTracker` in runtime.js to capture bodies
   - Add `Request` and `Response` classes with body support
   - Add `route()` method for interception

### Phase 2: Mock/Intercept API (High Priority)
3. **MockAPI (route interception)**
   - `page.route(urlPattern, handler)`
   - `page.unroute(urlPattern)`
   - Support for mocking responses
   - Support for aborting requests

### Phase 3: Service Worker Support (Medium)
4. **ServiceWorkerRegistry**
   - `page.serviceWorkers()`
   - `page.waitForServiceWorker(urlPattern)`
   - ServiceWorker registration/unregistration
   - Push notification mocking

### Phase 4: WebRTC Handling (Medium)
5. **WebRTC Mocking**
   - `page.mockRTCPeerConnection()`
   - `page.setGeolocation()` already exists in config

### Phase 5: Performance Benchmarks (Medium)
6. **Performance Metrics API**
   - `page.metrics()`
   - `page.performance()`
   - CPU/memory profiling

### Phase 6: Cleanup (Low)
7. **Remove Deprecated**
   - `PerformanceHints.setDatabaseEnabled()` - mark deprecated
   - Any WebSQL references

## New API Surface

```kotlin
// Browser (top level)
val browser = Browser(context, config)
val pages = browser.pages()  // List<Page>
val targets = browser.targets()  // List<Target>
browser.close()

// Page
page.route("**/*", RouteHandler)  // Intercept requests
page.unroute("**/*")
page.waitForRequest(url)
page.waitForResponse(url)
page.metrics()  // Performance metrics
page.serviceWorkers()  // Service workers

// RouteHandler
sealed class RouteHandler {
    data class Continue(val url: String? = null) : RouteHandler()
    data class Fulfill(val status: Int, val body: String, val headers: Map<String, String>) : RouteHandler()
    data object Abort : RouteHandler()
}
```

## Request Body Capture

Current implementation captures:
- url
- method
- headers
- timestamp

Needs to add:
- postData / request body
- postDataJSON (parsed)

## Response Body Capture

Current implementation captures:
- url
- status
- statusText
- headers
- timestamp

Needs to add:
- body (text)
- bodyFromBuffer (base64)

## File Structure

```
kmpwright-core/src/main/java/com/bernaferrari/kmpwright/core/
├── Browser.kt                    # NEW: Top-level browser
├── BrowserContext.kt             # ENHANCED: Multi-page context
├── Target.kt                     # NEW: Target abstraction
├── Page.kt                       # ENHANCED: +route, +metrics
├── PageImpl.kt                   # ENHANCED: Implementation
├── Request.kt                    # NEW: Request with body
├── Response.kt                   # NEW: Response with body
├── Route.kt                      # NEW: Route interception
├── MockAPI.kt                    # NEW: Mock/intercept API
├── ServiceWorker.kt              # NEW: Service worker support
├── ServiceWorkerRegistry.kt      # NEW: Service worker registry
├── PerformanceMetrics.kt         # NEW: Performance benchmarks
├── NetworkModule.kt              # ENHANCED: Cookie/Storage
└── JsBridge.kt                   # ENHANCED: Interception support
```

## kmpwright-runtime.js Changes

1. **NetworkTracker** - Capture request/response bodies
2. **RequestInterceptor** - New module for route interception
3. **ServiceWorkerTracker** - Track service workers
4. **WebRTCMock** - Mock peer connections
