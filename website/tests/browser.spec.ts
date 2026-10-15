import { expect, test } from "@playwright/test";
import type { KmpwrightDemo } from "../lib/kmpwright-demo/kmpwright-demo.mjs";

declare global {
  interface Window {
    contractDemo?: KmpwrightDemo;
    hooksRestored?: () => boolean;
  }
}

test("guided form recovers from an early submit and refs can be hidden", async ({
  page,
}) => {
  await page.goto("/#demo");
  const log = page.getByRole("log");
  await expect(log).toContainText('[button] "Create account"');
  const text = (await log.textContent()) ?? "";
  const ref = /(@e\d+) \[button\] "Create account"/.exec(text)?.[1];
  expect(ref).toBeTruthy();
  const submit = page.getByRole("button", {
    name: `click ${ref}`,
    exact: true,
  });
  await submit.click();
  const frame = page.frameLocator("iframe");
  await expect(frame.locator("#signup-status")).toContainText(
    "Please accept the terms",
  );
  await page
    .getByRole("button", { name: /^fill @e\d+ "Ada Lovelace"$/ })
    .click();
  await page.getByRole("button", { name: /^check @e\d+$/ }).click();
  await page.getByRole("button", { name: "Refs", exact: true }).click();
  await expect(submit).toBeVisible();
  await submit.click();
  await expect(frame.locator("#signup-status")).toContainText(
    "Welcome, Ada Lovelace!",
  );
  await page
    .locator(".live-demo")
    .getByRole("button", { name: "Reset", exact: true })
    .click();
  await expect(frame.getByRole("textbox", { name: "Full name" })).toHaveValue(
    "",
  );
  await expect(frame.locator("#signup-status")).toBeEmpty();
});

test("mobile controls meet the touch target and page stays within viewport", async ({
  page,
}) => {
  await page.goto("/#demo");
  await expect(page.getByRole("button", { name: /^fill @e\d+/ })).toBeEnabled();
  for (const control of [
    page.getByRole("button", { name: "Refs", exact: true }),
    page.getByRole("button", { name: /^fill @e\d+/ }),
    page.getByRole("button", { name: "Run command", exact: true }),
  ]) {
    const box = await control.boundingBox();
    expect(box?.height).toBeGreaterThanOrEqual(44);
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("compiled browser backend rejects unobservable network idle and restores borrowed iframe", async ({
  page,
}) => {
  await page.goto("http://127.0.0.1:3212");
  await page.evaluate(async () => {
    const runtimeUrl = new URL("/runtime.mjs", location.href).href;
    const runtime: typeof import("../lib/kmpwright-demo/kmpwright-demo.mjs") =
      await import(runtimeUrl);
    const frame = document.querySelector("iframe");
    if (!frame?.contentWindow) throw new Error("Missing contract frame");
    const win = frame.contentWindow as Window & typeof globalThis;
    const original = [
      win.alert,
      win.confirm,
      win.prompt,
      win.history.pushState,
      win.history.replaceState,
      win.console.log,
      win.Element.prototype.scrollIntoView,
      win.HTMLElement.prototype.focus,
    ];
    window.hooksRestored = () =>
      original.every(
        (value, i) =>
          value ===
          [
            win.alert,
            win.confirm,
            win.prompt,
            win.history.pushState,
            win.history.replaceState,
            win.console.log,
            win.Element.prototype.scrollIntoView,
            win.HTMLElement.prototype.focus,
          ][i],
      );
    window.contractDemo = new runtime.KmpwrightDemo(frame);
  });
  const outcome = await page.evaluate(async () =>
    window.contractDemo?.run("wait --load networkidle"),
  );
  expect(outcome?.ok).toBe(false);
  expect(outcome?.output).toContain("Network-idle waits are unavailable");
  const restored = await page.evaluate(() => {
    window.contractDemo?.dispose();
    return {
      hooks: window.hooksRestored?.(),
      attached: document.querySelector("iframe")?.isConnected,
    };
  });
  expect(restored).toEqual({ hooks: true, attached: true });
});
