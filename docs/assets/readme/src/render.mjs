// Renders the README images. Run from website/: node ../docs/assets/readme/src/render.mjs
// ipad.png and ios.png are the example app after its "Fill a form" recipe (iPad Pro 11" landscape, iPhone 18 Pro).
import { createRequire } from "node:module"
import { fileURLToPath } from "node:url"
import path from "node:path"

const here = path.dirname(fileURLToPath(import.meta.url))
const { chromium } = createRequire(path.join(process.cwd(), "package.json"))("@playwright/test")
const names = process.argv.slice(2).length ? process.argv.slice(2) : ["header"]
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 2400, height: 1260 } })
for (const name of names) {
  await page.goto(`file://${path.join(here, `${name}.html`)}`, { waitUntil: "networkidle" })
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: path.join(here, "..", `${name}.jpg`), type: "jpeg", quality: 90 })
}
await browser.close()
