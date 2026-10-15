# KMPwright website

The landing page and documentation site use Next.js, Fumadocs MDX and Tailwind.
Library code lives in the parent repository; this app describes the same public
API and platform limitations.

## Develop

Use Node.js 24 and pnpm 11.25.0. From this directory, install the locked
dependencies and start the server:

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Open `http://localhost:3000`. Set `SITE_URL` to the deployment's public URL when
building so metadata uses the correct origin; it uses Vercel's production domain when available, otherwise `https://kmpwright.vercel.app`.

## Check before publishing

```bash
pnpm types:check
pnpm lint
pnpm build
pnpm exec playwright install chromium
pnpm test:e2e
```

The browser suite exercises the published Kotlin runtime and live demo at desktop
and mobile sizes, including failed-form recovery, ref controls and iframe cleanup.
CI retains traces and reports when a contract fails.

`pnpm start` serves the production build. These commands build locally; they do
not deploy the site or publish the library's Maven artifacts.

## Edit content

- `app/(home)/page.tsx`: landing page and runnable shared Kotlin example.
- `components/live-demo.tsx`: the live browser demo, backed by the real Kotlin engine.
- `../kmpwright-web-demo/`: Kotlin source for the demo. Website development, type
  checking and production builds fetch a checksum-pinned GitHub release runtime
  into `lib/kmpwright-demo/`. That directory is ignored and must not be committed.
- `content/docs/`: MDX guides and API reference; `meta.json` controls navigation.
- `lib/layout.shared.tsx`: shared header links and project identity.
- `lib/shared.ts`: GitHub source links and Markdown/image route paths.
- `app/global.css`: shared theme and typography tokens.

Docs pages are generated at build time. Each includes code-copy buttons,
Markdown export and a link to its source. `/llms.txt` indexes the guides and
`/llms-full.txt` provides the combined documentation.

Keep examples aligned with the source, distinguish alpha coordinates from
released artifacts, and describe native WebView limits alongside each feature.

## Vercel

The `kmpwright` Vercel project is connected to `bernaferrari/kmpwright`. Its
Root Directory is `website`, with Node.js 24. Pushes to `main` deploy production;
other branches create previews. `vercel.json` supplies the install and build
commands. The production URL is `https://kmpwright.vercel.app`.
Set `SITE_URL` if the production domain changes.

The Next.js build is Node-only: `demo-runtime.json` pins the compiled Kotlin demo
and its SHA-256 checksums. Generated JavaScript stays in GitHub release assets,
not in Git. Dependency notices are served at `/licenses/kmpwright-demo.txt`.

When changing the Kotlin demo or its shared engine, run `pnpm demo:build` using
Java 17 and the Android SDK. Publish a new `web-demo-runtime-*` prerelease asset
set and update `demo-runtime.json`; `pnpm demo:verify` checks the regenerated code
against that manifest. Use a new asset version instead of replacing a pinned one.
