# Kmpwright Open Graph artwork

Generated with the built-in image_gen tool on 2026-10-11.

- Upload image: [kmpwright-opengraph.jpg](kmpwright-opengraph.jpg), 1730 × 909 pixels. JPEG encoding preserves the generated composition and is under 1 MB.
- PNG master: [kmpwright-opengraph.png](kmpwright-opengraph.png).
- For GitHub, upload the JPEG under Settings → Social preview → Edit. Keeping the file in Git alone does not configure the repository's preview. See [GitHub's social-preview guide](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview).

## Generation prompt

```text
Use case: ads-marketing.
Asset type: polished Open Graph / GitHub social preview image for an open-source developer library. Create a landscape 1200 x 630 composition, approximately 1.91:1, opaque background.
Project: Kmpwright, browser automation for Kotlin Multiplatform. Its shared Kotlin APIs control Android WebView, iOS WKWebView, and Chromium through CDP.
Creative direction: a confident, restrained developer-tool identity with precise editorial typography and a single meaningful technical illustration. Very dark charcoal background, softly lit violet-to-coral accent inspired by Kotlin, warm white type, fine sharp lines. Premium flat graphic design with subtly dimensional browser panels; no shiny generic AI art.
Composition: generous 64px safe margins. Left half: large beautiful bold sans-serif wordmark and a clearly readable smaller tagline. Right half: an elegant shared code/browser panel branching into two compact mobile browser frames and one desktop browser frame. Within the three frames, echo the same simple page layout and one small selected element outlined in violet with a tiny cursor, communicating one automation flow across platforms. Use thin routed connectors, neat alignment, modest depth, no visual clutter. Place compact platform labels beneath the illustration.
Text to render exactly and only:
"Kmpwright"
"Browser automation for Kotlin Multiplatform."
"Android · iOS · Chromium"
Wordmark must be perfectly spelled K-m-p-w-r-i-g-h-t, visually dominant and readable at thumbnail size. Tagline should wrap naturally over two lines. Platform labels should be comfortably legible.
Constraints: no fabricated statistics, no badges, no GitHub URL, no logos of other companies, no additional text, no watermark, no photos, no humanoids or robots, no busy circuitry, no ornamental confetti. Keep the illustration secondary to the title, and ensure all content survives small edge crops. Export the finished clean landscape social card.
```

## Final correction prompt

```text
Edit this Open Graph image with one precise text correction. Preserve the composition, wordmark, typography, colors, browser/device illustration, connectors, background, dimensions, and every other detail exactly. In the small code panel at the upper right, replace only the second line page.navigate("https://example.com") with the exact valid Kotlin API line page.goto("https://example.com"). Keep the other two lines exactly: val page = browser.newPage() and page.click(".cta"). Match the existing monospace typography and syntax colors. Do not add any text or visual elements.
```
