import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: { baseURL: "http://127.0.0.1:3211", trace: "retain-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["Pixel 7"] } },
  ],
  webServer: [
    {
      command: "pnpm start --port 3211",
      url: "http://127.0.0.1:3211",
      timeout: 120_000,
    },
    {
      command: "node tests/backend-server.mjs",
      url: "http://127.0.0.1:3212",
      timeout: 30_000,
    },
  ],
});
