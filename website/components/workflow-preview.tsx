"use client";

import {
  Check,
  CheckCheck,
  Copy,
  LockKeyhole,
  Play,
  RotateCcw,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const snippet = `page.getByLabel("Email").fill("ada@example.com")
page.getByRole("button", name = "Subscribe").click()
expect(page.locator("#status")).toHaveText("Subscribed")
page.screenshot()`;

const steps = [
  "Fill the email field",
  "Click Subscribe",
  "Assert the result",
  "Capture a screenshot",
];

export function WorkflowPreview() {
  const [step, setStep] = useState(0);
  const [running, setRunning] = useState(false);
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">(
    "idle",
  );
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  function run() {
    timers.current.forEach(clearTimeout);
    setStep(0);
    setRunning(true);
    timers.current = [1, 2, 3, 4].map((next) =>
      setTimeout(() => {
        setStep(next);
        if (next === 4) setRunning(false);
      }, next * 450),
    );
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(snippet);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }

  return (
    <div className="workflow-preview overflow-hidden rounded-2xl border bg-card">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 sm:px-6">
        <div className="flex items-center gap-3">
          <span
            aria-hidden
            className="grid size-8 place-items-center rounded-lg bg-primary/10 font-mono text-sm font-bold text-primary"
          >
            K
          </span>
          <div>
            <p className="text-sm font-medium">
              One workflow. From code to browser.
            </p>
            <p className="text-xs text-muted-foreground">
              Interactive illustration
            </p>
          </div>
        </div>
        <Button onClick={run} disabled={running} className="h-11 gap-2 px-4">
          {step === 4 ? <RotateCcw aria-hidden /> : <Play aria-hidden />}
          {running ? "Running…" : step === 4 ? "Run again" : "Run workflow"}
        </Button>
      </div>

      <div className="grid lg:grid-cols-[1.15fr_1fr]">
        <div className="min-w-0 border-b lg:border-r lg:border-b-0">
          <div className="flex h-12 items-center justify-between border-b px-5 text-xs text-muted-foreground">
            <span className="font-mono">
              Workflow.kt <span className="ml-3 font-sans">commonMain</span>
            </span>
            <button
              type="button"
              onClick={copy}
              aria-label={
                copyState === "copied"
                  ? "Kotlin code copied"
                  : "Copy Kotlin code"
              }
              className="-mr-3 grid size-11 place-items-center rounded-md hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
            >
              {copyState === "copied" ? (
                <Check aria-hidden className="size-4" />
              ) : (
                <Copy aria-hidden className="size-4" />
              )}
            </button>
          </div>
          <div className="overflow-x-auto py-6 font-mono text-[12px] leading-7 sm:text-[13px]">
            <p className="px-5 text-muted-foreground">
              {"// Locate. Interact. Verify. Capture."}
            </p>
            <pre className="mt-4">
              <code>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 1 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">1</span>page.
                  <span className="preview-method">getByLabel</span>(
                  <span className="preview-string">"Email"</span>)
                </span>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 1 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">2</span> .
                  <span className="preview-method">fill</span>(
                  <span className="preview-string">"ada@example.com"</span>)
                </span>
                <span className="preview-code-line">
                  <span className="preview-line-number">3</span>{" "}
                </span>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 2 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">4</span>page.
                  <span className="preview-method">getByRole</span>(
                  <span className="preview-string">"button"</span>, name ={" "}
                  <span className="preview-string">"Subscribe"</span>)
                </span>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 2 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">5</span> .
                  <span className="preview-method">click</span>()
                </span>
                <span className="preview-code-line">
                  <span className="preview-line-number">6</span>{" "}
                </span>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 3 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">7</span>
                  <span className="preview-method">expect</span>(page.
                  <span className="preview-method">locator</span>(
                  <span className="preview-string">"#status"</span>))
                </span>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 3 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">8</span> .
                  <span className="preview-method">toHaveText</span>(
                  <span className="preview-string">"Subscribed"</span>)
                </span>
                <span className="preview-code-line">
                  <span className="preview-line-number">9</span>{" "}
                </span>
                <span
                  className={cn(
                    "preview-code-line",
                    step === 4 && "preview-code-active",
                  )}
                >
                  <span className="preview-line-number">10</span>page.
                  <span className="preview-method">screenshot</span>()
                </span>
              </code>
            </pre>
          </div>
          {copyState === "failed" && (
            <p
              role="status"
              className="px-5 pb-3 text-xs text-muted-foreground"
            >
              Copy unavailable. Select the code to copy it.
            </p>
          )}
        </div>

        <div className="preview-browser-canvas flex flex-col p-5 sm:p-7">
          <div className="overflow-hidden rounded-xl border bg-background shadow-sm">
            <div className="flex h-10 items-center gap-3 border-b bg-muted/40 px-4 text-xs text-muted-foreground">
              <div aria-hidden className="flex gap-1.5">
                <span className="size-2 rounded-full bg-muted-foreground/30" />
                <span className="size-2 rounded-full bg-muted-foreground/30" />
                <span className="size-2 rounded-full bg-muted-foreground/30" />
              </div>
              <span className="flex items-center gap-1.5">
                <LockKeyhole aria-hidden className="size-3" />{" "}
                newsletter.example
              </span>
            </div>
            <div
              role="img"
              aria-label={
                step >= 2
                  ? "Browser preview: ada@example.com subscribed successfully"
                  : "Browser preview: a newsletter subscription form"
              }
              className="select-none p-6 sm:p-8"
            >
              <div aria-hidden>
                <span className="text-xs font-medium uppercase tracking-[0.16em] text-muted-foreground">
                  The Kotlin dispatch
                </span>
                <p className="mt-3 text-2xl font-semibold tracking-tight">
                  A little more Kotlin.
                </p>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">
                  Good reads for people who build things.
                </p>
                <p className="mt-6 text-xs font-medium">Email</p>
                <div
                  className={cn(
                    "mt-2 flex h-11 items-center rounded-lg border px-3 text-sm",
                    step === 1 && "border-primary ring-2 ring-primary/15",
                    step === 0 && "text-muted-foreground",
                  )}
                >
                  {step >= 1 ? "ada@example.com" : "you@example.com"}
                </div>
                <div className="mt-3 flex h-11 items-center justify-center gap-2 rounded-lg bg-foreground text-sm font-medium text-background">
                  {step >= 2 && <Check aria-hidden className="size-4" />}
                  {step >= 2 ? "Subscribed" : "Subscribe"}
                </div>
                <p className="mt-3 h-5 text-center text-xs text-muted-foreground">
                  {step >= 2
                    ? "You’re on the list. See you next issue."
                    : "One email. When there’s something good."}
                </p>
              </div>
            </div>
          </div>
          <div
            className="mt-4 flex min-h-6 items-center gap-2 text-sm"
            role="status"
            aria-live="polite"
            aria-atomic="true"
          >
            {step === 4 ? (
              <CheckCheck aria-hidden className="size-4 text-primary" />
            ) : (
              <span
                aria-hidden
                className={cn(
                  "size-1.5 rounded-full",
                  running ? "bg-primary" : "bg-muted-foreground",
                )}
              />
            )}
            <span className="text-muted-foreground">
              {step === 4
                ? "Assertion passed · screenshot captured"
                : running
                  ? steps[step]
                  : "Run the preview to follow each step"}
            </span>
          </div>
        </div>
      </div>
      <div className="flex items-center justify-between gap-3 border-t px-5 py-3 text-xs text-muted-foreground">
        <span>Shared Kotlin → your platform’s browser</span>
        <span className="shrink-0 font-mono whitespace-nowrap tabular-nums">
          {step} / 4 steps
        </span>
      </div>
    </div>
  );
}
