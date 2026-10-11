# Kmpwright KMP (Kotlin Multiplatform) Feasibility Analysis

> **Historical analysis (2026-08-30):** This document preserves early feasibility reasoning and estimates. It is not a current implementation or installation guide; use [README.md](README.md) for the live project.

## Status: KMP Active ✅ (Android + iOS, Mobile WebView Subset)

Kmpwright ships a **Kotlin Multiplatform core** with Android WebView and WKWebView support. The iOS implementation includes navigation, selectors, input, screenshots, cookies, PDF generation, and partial fetch/XHR or custom-scheme interception. It does not provide full browser-network interception parity.

---

## Executive Summary (Original)

**Difficulty: Hard** (6-12 months for a team, solo would take 12-18+ months)

**Key Challenge**: Android's `android.webkit.WebView` and iOS's `WKWebView` have fundamentally different APIs.

## Component-by-Component Analysis

### What Can Be Shared (Easy - 20% of code)

```
commonMain/
├── Page.kt              # Interface definitions
├── Locator.kt           # Locator logic
├── Browser.kt           # Browser interface
├── RequestResponse.kt   # Data classes
├── Route.kt             # Route interception
├── Exceptions.kt        # KmpwrightException
└── Config.kt            # Configuration
```

### What Needs Platform Abstraction (Hard - 80% of code)

| Component | Android | iOS | Difficulty |
|-----------|---------|-----|------------|
| **WebView** | `WebView` | `WKWebView` | 🔴 Hard |
| **JS Bridge** | `evaluateJavascript` | `evaluateJavaScript` | 🔴 Hard |
| **Screenshot** | `view.draw()` | `UIGraphicsImageRenderer` | 🟡 Medium |
| **Dialogs** | `WebChromeClient` | `WKUIDelegate` | 🟡 Medium |
| **Network** | Interception via JS | Interception via URLProtocol | 🔴 Hard |
| **Cookies** | `CookieManager` | `WKHTTPCookieStore` | 🟡 Medium |
| **Events** | `WebViewClient` | `WKNavigationDelegate` | 🟡 Medium |

## Architecture Proposal

```
kmpwright/
├── build.gradle.kts           # KMP setup
├── settings.gradle.kts
├── gradle.properties
│
├── commonMain/
│   ├── kotlin/
│   │   └── com/bernaferrari/kmpwright/
│   │       ├── Page.kt (interface)
│   │       ├── Browser.kt (interface)
│   │       ├── Locator.kt (interface)
│   │       ├── RequestResponse.kt
│   │       ├── Route.kt
│   │       ├── Config.kt
│   │       └── Exceptions.kt
│   └── resources/
│       └── kmpwright-runtime.js  # Shared JS runtime
│
├── androidMain/
│   ├── kotlin/
│   │   └── com/bernaferrari/kmpwright/
│   │       ├── AndroidWebViewController.kt
│   │       ├── AndroidJsBridge.kt
│   │       ├── AndroidPage.kt
│   │       ├── AndroidBrowser.kt
│   │       └── AndroidScreenshot.kt
│   └── AndroidManifest.xml
│
├── iosMain/
│   ├── kotlin/
│   │   └── com/bernaferrari/kmpwright/
│   │       ├── IOSWebViewController.kt
│   │       ├── IOSJsBridge.kt
│   │       ├── IOSPage.kt
│   │       ├── IOSBrowser.kt
│   │       └── IOSScreenshot.kt
│   └── Info.plist
│
└── composeMain/      # Shared Compose UI
    └── kotlin/
        └── com/bernaferrari/kmpwright/compose/
            └── KmpwrightWebView.kt
```

## Key Challenges

### 1. WebView API Differences

```kotlin
// Android - Easy
webView.evaluateJavascript(script) { result ->
    callback(result)
}

// iOS - Different
wkWebView.evaluateJavaScript(script) { result, error in
    if let error = error { callback(null) }
    else { callback(result) }
}
```

### 2. JavaScript Bridge

**Android**: Uses `@JavascriptInterface` annotated methods
**iOS**: Uses `WKScriptMessageHandler` with message passing

**Solution**: Common JS runtime that sends messages via `postMessage` and receives via callback

```javascript
// kmpwright-runtime.js (common)
window.__kmpwright = {
    postMessage(data) {
        if (window.webkit && window.webkit.messageHandlers.kmpwright) {
            window.webkit.messageHandlers.kmpwright.postMessage(JSON.stringify(data));
        }
        if (window.kmpwrightNative) {
            window.kmpwrightNative.postMessage(JSON.stringify(data));
        }
    },
    onmessage(callback) {
        window.__kmpwrightMessageCallback = callback;
    }
};
```

### 3. Dialog Handling

**Android**: Override `onJsAlert/confirm/prompt`
**iOS**: Implement `WKUIDelegate` methods

### 4. Network Interception

**Android**: Can intercept via `shouldInterceptRequest`
**iOS**: Need custom `URLProtocol` or `WKURLSchemeHandler`

### 5. Touch Events

**Android**: `webView.dispatchTouchEvent()`
**iOS**: Need `UIGestureRecognizer` or JavaScript click dispatch

## Estimated Timeline

| Phase | Task | Duration | Difficulty |
|-------|------|----------|------------|
| 1 | KMP project setup | 2 weeks | Easy |
| 2 | Extract common interfaces | 3 weeks | Medium |
| 3 | Android implementation | 4 weeks | Medium |
| 4 | iOS WebView wrapper | 6 weeks | Hard |
| 5 | iOS JS Bridge | 4 weeks | Hard |
| 6 | iOS dialogs & events | 3 weeks | Medium |
| 7 | iOS screenshots | 2 weeks | Medium |
| 8 | Testing & polish | 4 weeks | Medium |
| | **Total** | **~7 months** | |

## Questions to Answer First

1. **Target iOS WebView?**
   - Pure `WKWebView` (harder)
   - **Chrome Custom Tabs** (easier, similar to Android)

2. **Compose Multiplatform UI?**
   - Share `KmpwrightWebView` composable
   - Needs platform-specific WebView implementations

3. **Feature parity?**
   - Some features may not work on iOS
   - Service Workers support varies

## Recommendation

**If you want iOS support, consider alternatives:**

1. **Use Chrome Custom Tabs** - More consistent API across platforms
2. **Partner with iOS developers** - KMP requires iOS expertise
3. **Focus on Android first** - Ship, then expand

## Code Examples

### Platform Abstraction Pattern

```kotlin
// commonMain
expect class KmpwrightViewController {
    fun loadUrl(url: String)
    fun evaluateJavaScript(script: String, callback: (String?) -> Unit)
    fun takeScreenshot(): ByteArray?
    fun setDialogHandler(handler: DialogHandler)
    fun goBack()
    fun goForward()
    fun reload()
    fun dispose()
}

// androidMain
actual class KmpwrightViewController(
    private val webView: WebView
) {
    actual fun loadUrl(url: String) = webView.loadUrl(url)
    
    actual fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        webView.evaluateJavascript(script) { result ->
            callback(result)
        }
    }
    
    // ... other implementations
}

// iosMain
actual class KmpwrightViewController(
    private val wkWebView: WKWebView
) {
    actual fun loadUrl(url: String) = wkWebView.load(NSURLRequest.requestWithURL(NSURL(string = url)))
    
    actual fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        wkWebView.evaluateJavaScript(script) { result, error in
            if (error != null) callback(null)
            else callback(result as? String)
        }
    }
    
    // ... other implementations
}
```

## Conclusion

**Making Kmpwright truly KMP-ready is a major undertaking.**

The JS runtime, data classes, and business logic can be shared. But the WebView control layer needs complete reimplementation for iOS.

**Realistic path:**
1. ✅ Ship Android version first (what we have)
2. Consider Chrome Custom Tabs for iOS (simpler than WKWebView)
3. Partner with iOS developers if expanding
4. Or use existing KMP WebView libraries

Would you like to explore a specific path forward?

---

## KMP Rollback Summary (Jan 12, 2026)

### What Was Done
- Created common interfaces in `commonMain/`
- Created Android implementations in `androidMain/`
- Created iOS stubs in `iosMain/`
- Configured KMP build in `kmpwright-core/build.gradle.kts`

### Why It Was Rolled Back
1. Build conflicts between `kotlin.multiplatform` and `kotlin.android` plugins
2. No macOS + Xcode environment for iOS testing
3. Low immediate priority for iOS support
4. Complexity of WKWebView bridging

### What Was Removed
- `commonMain/` source set (moved interfaces back to `main/`)
- `iosMain/` source set (iOS stubs)
- `androidMain/` source set (KMP Android impl)
- KMP plugin from build configuration

### Future KMP Resume Steps
1. Restore KMP plugin to `kmpwright-core/build.gradle.kts`
2. Move interfaces to `commonMain/`
3. Create proper Android/iOS implementations
4. Set up macOS + Xcode for iOS testing
5. Add `@ObjCExporter` annotations
