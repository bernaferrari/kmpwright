import { DynamicCodeBlock } from "fumadocs-ui/components/dynamic-codeblock";
import {
  ArrowRight,
  Bot,
  Camera,
  Code2,
  Globe,
  Layers3,
  Network,
  Smartphone,
} from "lucide-react";
import Link from "next/link";
import { LiveDemo } from "@/components/live-demo";
import { buttonVariants } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";

const examples = [
  {
    id: "workflow",
    label: "Shared workflow",
    code: `// The same suspend function runs on Android, iOS and JVM.
import com.bernaferrari.kmpwright.core.*

suspend fun subscribe(page: Page): ScreenshotResult {
    page.setContent("""
        <label>Email <input id="email" type="email"></label>
        <button onclick="document.querySelector('#status').textContent = 'Subscribed'">
            Subscribe
        </button>
        <p id="status" role="status">Ready</p>
    """.trimIndent())

    page.getByLabel("Email").fill("ada@example.com")
    page.getByRole("button", name = "Subscribe").click()
    expect(page.locator("#status")).toHaveText("Subscribed")
    return page.screenshot()
}`,
  },
  {
    id: "agent",
    label: "Agent loop",
    code: `val page = Browser(AndroidPlatformContext(activity, webView)).page()

page.runCommand("open news.ycombinator.com")
page.runCommand("snapshot -i")
// @e1 [link] "Hacker News"
// @e2 [link] "new"
// @e9 [input type="text"]
page.runCommand("click @e2")
page.runCommand("get title") // New Links | Hacker News`,
  },
  {
    id: "tool",
    label: "LLM tool",
    code: `// One tool is enough: the model reads snapshots and acts on refs.
// Tool is your LLM SDK's function-calling type (Koog, Firebase AI, OpenAI…).
val browserTool = Tool(
    name = "browser",
    description = AgentCommands.HELP,
) { args -> page.runCommand(args.getValue("command")).output }`,
  },
];

const platforms = [
  {
    target: "Android",
    minimum: "8+",
    backend: "WebView",
    icon: Smartphone,
    href: "/docs/platforms/android",
    note: "Automate the WebView in your app, or create one for a workflow.",
  },
  {
    target: "iOS",
    minimum: "15+",
    backend: "WKWebView",
    icon: Layers3,
    href: "/docs/platforms/ios",
    note: "Use the shared API from Kotlin, or call it through Swift.",
  },
  {
    target: "JVM",
    minimum: "17+",
    backend: "Chromium · CDP",
    icon: Globe,
    href: "/docs/platforms/jvm",
    note: "Launch Chrome, attach to a browser, or connect to a cloud provider.",
  },
];

const tools = [
  {
    icon: Code2,
    title: "Find the element you mean",
    body: "Semantic locators, filters and frame scopes. Actions wait for the element; assertions wait for the result.",
    href: "/docs/guides/locators",
    label: "Explore locators",
  },
  {
    icon: Network,
    title: "Shape the network",
    body: "Intercept requests, mock responses and inspect request history. Save a HAR when you need the full picture.",
    href: "/docs/guides/network",
    label: "Control requests",
  },
  {
    icon: Camera,
    title: "Keep the evidence",
    body: "Capture elements or whole pages, compare screenshots, and use Chromium recordings and traces to retrace a run.",
    href: "/docs/guides/screenshots",
    label: "Capture a page",
  },
];

export default function HomePage() {
  return (
    <main className="landing-page flex flex-1 flex-col">
      <section aria-labelledby="hero-heading" className="landing-hero relative">
        <div className="relative mx-auto w-full max-w-6xl px-6 pt-14 pb-12 sm:pt-20 lg:pt-24">
          <div className="mb-6 flex items-center gap-3 text-xs font-medium tracking-wide text-muted-foreground">
            <span className="hidden h-px w-8 bg-primary sm:block" aria-hidden />
            KOTLIN MULTIPLATFORM
            <span className="shrink-0 rounded-md border bg-background/70 px-2 py-1 font-mono text-[11px] tracking-normal whitespace-nowrap">
              0.0.1 alpha
            </span>
          </div>
          <div className="grid items-end gap-7 lg:grid-cols-[1.35fr_1fr] lg:gap-14">
            <h1
              id="hero-heading"
              className="text-[clamp(2.5rem,5.6vw,4.5rem)] font-semibold leading-[1.07] tracking-[-0.045em]"
            >
              Automate browsers.
              <br />
              <span className="text-primary">Keep it Kotlin.</span>
            </h1>
            <div className="max-w-lg">
              <p className="text-pretty text-base leading-7 text-muted-foreground sm:text-lg">
                One shared API for Android WebView, iOS WKWebView and JVM
                Chromium. Build browser workflows inside your app, or give an AI
                agent the controls.
              </p>
              <div className="mt-6 flex flex-wrap items-center gap-3">
                <Link
                  href="/docs/installation"
                  className={cn(
                    buttonVariants({ size: "lg" }),
                    "h-11 gap-2 px-5",
                  )}
                >
                  Start building{" "}
                  <ArrowRight aria-hidden data-icon="inline-end" />
                </Link>
                <a
                  href="https://github.com/bernaferrari/kmpwright"
                  className={cn(
                    buttonVariants({ variant: "ghost", size: "lg" }),
                    "h-11 px-4",
                  )}
                >
                  View source{" "}
                  <ArrowRight aria-hidden className="size-3.5 -rotate-45" />
                </a>
              </div>
            </div>
          </div>
          <div className="mt-10 flex flex-wrap items-center gap-x-6 gap-y-3 text-sm text-muted-foreground">
            {platforms.map(({ target, backend, icon: Icon }) => (
              <span key={target} className="inline-flex items-center gap-2">
                <Icon aria-hidden className="size-4" />
                <span className="font-medium text-foreground">{target}</span>
                <span className="hidden sm:inline">{backend}</span>
              </span>
            ))}
          </div>
          <div className="mt-7">
            <LiveDemo />
          </div>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-xs leading-5 text-muted-foreground">
            <p>
              Open source · Build from source today · Maven publication planned
              for the first release
            </p>
            <Link
              href="/docs/quick-start"
              className="inline-flex items-center gap-1.5 py-2 font-medium text-foreground hover:underline underline-offset-4"
            >
              Run the real Kotlin example{" "}
              <ArrowRight aria-hidden className="size-3.5" />
            </Link>
          </div>
        </div>
      </section>

      <section
        aria-labelledby="paths-heading"
        className="mx-auto w-full max-w-6xl px-6 py-14 sm:py-20"
      >
        <div className="mb-9 max-w-2xl">
          <p className="landing-eyebrow">A PAGE. TWO WAYS TO DRIVE IT.</p>
          <h2
            id="paths-heading"
            className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl"
          >
            For your code. And your agents.
          </h2>
          <p className="mt-4 text-pretty leading-7 text-muted-foreground">
            Choose typed Kotlin calls or compact browser commands. Both work
            with the same page, so you can move between them as your workflow
            needs.
          </p>
        </div>
        <div className="grid gap-6 md:grid-cols-2">
          <div className="rounded-2xl border bg-card p-6 sm:p-8">
            <Code2 aria-hidden className="size-6 text-primary" />
            <h3 className="mt-5 text-xl font-semibold tracking-tight">
              A browser workflow in commonMain
            </h3>
            <p className="mt-3 text-pretty leading-7 text-muted-foreground">
              Automate a form, verify a web flow, or capture a page. Keep the
              workflow in shared Kotlin and connect the browser on each
              platform.
            </p>
            <div className="mt-5 flex flex-wrap gap-2 text-xs text-muted-foreground">
              <span className="rounded-md bg-muted px-2.5 py-1.5">
                Typed locators
              </span>
              <span className="rounded-md bg-muted px-2.5 py-1.5">
                Suspending APIs
              </span>
              <span className="rounded-md bg-muted px-2.5 py-1.5">
                Web-first assertions
              </span>
            </div>
            <Link href="/docs/quick-start" className="landing-text-link mt-6">
              Write your first workflow{" "}
              <ArrowRight aria-hidden className="size-4" />
            </Link>
          </div>
          <div className="landing-agent-card rounded-2xl border p-6 sm:p-8">
            <Bot aria-hidden className="size-6 text-primary" />
            <h3 className="mt-5 text-xl font-semibold tracking-tight">
              Give an agent a browser tool
            </h3>
            <p className="mt-3 text-pretty leading-7 text-muted-foreground">
              Let the model read an interactive snapshot, choose an element
              reference, and act. Expose a single{" "}
              <code className="text-sm text-foreground">runCommand()</code> tool
              to your LLM SDK.
            </p>
            <div className="mt-5 flex flex-wrap gap-2 font-mono text-xs">
              <span className="rounded-md bg-background/70 px-2.5 py-1.5">
                snapshot -i
              </span>
              <span className="rounded-md bg-background/70 px-2.5 py-1.5">
                click @e2
              </span>
              <span className="rounded-md bg-background/70 px-2.5 py-1.5">
                get title
              </span>
            </div>
            <Link href="/docs/agents" className="landing-text-link mt-6">
              Build the agent loop <ArrowRight aria-hidden className="size-4" />
            </Link>
          </div>
        </div>
      </section>

      <section
        aria-labelledby="workflow-heading"
        className="border-y bg-muted/20"
      >
        <div className="mx-auto grid w-full max-w-6xl items-start gap-8 px-6 py-14 sm:py-20 lg:grid-cols-[0.7fr_1.3fr] lg:gap-16">
          <div>
            <p className="landing-eyebrow">SMALL API. USEFUL WORK.</p>
            <h2
              id="workflow-heading"
              className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl"
            >
              Start with a page.
              <br />
              End with a result.
            </h2>
            <p className="mt-4 leading-7 text-muted-foreground">
              The preview’s complete workflow supplies its own HTML. Fill the
              form, click, assert the result, and return the screenshot bytes.
            </p>
            <Link href="/docs/quick-start" className="landing-text-link mt-5">
              Connect your browser <ArrowRight aria-hidden className="size-4" />
            </Link>
            <p className="mt-5 text-sm leading-6 text-muted-foreground">
              Then call <code>subscribe(page)</code> on Android, iOS or JVM.
            </p>
          </div>
          <div className="min-w-0">
            <Tabs defaultValue="workflow">
              <TabsList className="min-h-14 max-w-full">
                {examples.map((example) => (
                  <TabsTrigger
                    key={example.id}
                    value={example.id}
                    className="min-h-11 px-3"
                  >
                    {example.label}
                  </TabsTrigger>
                ))}
              </TabsList>
              {examples.map((example) => (
                <TabsContent
                  key={example.id}
                  value={example.id}
                  className="mt-3"
                >
                  <DynamicCodeBlock lang="kotlin" code={example.code} />
                </TabsContent>
              ))}
            </Tabs>
          </div>
        </div>
      </section>

      <section
        aria-labelledby="tools-heading"
        className="mx-auto w-full max-w-6xl px-6 py-14 sm:py-20"
      >
        <p className="landing-eyebrow">BEYOND THE FIRST CLICK</p>
        <h2
          id="tools-heading"
          className="mt-3 text-balance text-3xl font-semibold tracking-tight sm:text-4xl"
        >
          The pieces a real workflow needs.
        </h2>
        <div className="mt-10 grid gap-8 md:grid-cols-3 md:gap-10">
          {tools.map(({ icon: Icon, title, body, href, label }) => (
            <div key={title} className="border-t pt-5">
              <Icon aria-hidden className="size-5 text-primary" />
              <h3 className="mt-4 text-lg font-semibold tracking-tight">
                {title}
              </h3>
              <p className="mt-3 text-pretty text-sm leading-6 text-muted-foreground">
                {body}
              </p>
              <Link href={href} className="landing-text-link mt-4 text-sm">
                {label}
                <ArrowRight aria-hidden className="size-3.5" />
              </Link>
            </div>
          ))}
        </div>
      </section>

      <section
        aria-labelledby="platforms-heading"
        className="mx-auto w-full max-w-6xl px-6 pb-14 sm:pb-20"
      >
        <div className="rounded-2xl border bg-muted/20 p-6 sm:p-8">
          <div className="flex flex-wrap items-end justify-between gap-5">
            <div>
              <p className="landing-eyebrow">
                SHARED WORKFLOW. NATIVE BACKENDS.
              </p>
              <h2
                id="platforms-heading"
                className="mt-3 text-balance text-2xl font-semibold tracking-tight sm:text-3xl"
              >
                At home on your platform.
              </h2>
            </div>
            <Link
              href="/docs/platforms/capabilities"
              className="landing-text-link text-sm"
            >
              Compare capabilities <ArrowRight aria-hidden className="size-4" />
            </Link>
          </div>
          <div className="mt-8 grid gap-7 md:grid-cols-3 md:gap-10">
            {platforms.map(
              ({ target, minimum, backend, icon: Icon, note, href }) => (
                <div key={target} className="border-t pt-5">
                  <div className="flex items-center gap-2">
                    <Icon aria-hidden className="size-5 text-primary" />
                    <h3 className="font-semibold">
                      {target}{" "}
                      <span className="font-normal text-muted-foreground">
                        {minimum}
                      </span>
                    </h3>
                  </div>
                  <p className="mt-3 font-mono text-xs text-muted-foreground">
                    {backend}
                  </p>
                  <p className="mt-3 text-sm leading-6 text-muted-foreground">
                    {note}
                  </p>
                  <Link href={href} className="landing-text-link mt-3 text-sm">
                    {target} setup{" "}
                    <ArrowRight aria-hidden className="size-3.5" />
                  </Link>
                </div>
              ),
            )}
          </div>
          <p className="mt-8 border-t pt-5 text-sm leading-6 text-muted-foreground">
            KMPwright is in alpha. Features follow each backend’s capabilities;
            check the platform limits before depending on an unattended
            workflow.
          </p>
        </div>
      </section>

      <footer className="border-t">
        <div className="mx-auto flex w-full max-w-6xl flex-col justify-between gap-4 px-6 py-8 text-xs leading-6 text-muted-foreground sm:flex-row">
          <p>
            <span className="font-semibold text-foreground">KMPwright</span> ·
            Browser automation for Kotlin Multiplatform.
          </p>
          <p>
            Playwright-inspired API. Adapted from{" "}
            <a
              href="https://agent-browser.dev"
              className="underline underline-offset-4 hover:text-foreground"
            >
              agent-browser
            </a>
            .{" "}
            <Link
              href="/docs"
              className="ml-2 underline underline-offset-4 hover:text-foreground"
            >
              Read the docs
            </Link>
          </p>
        </div>
      </footer>
    </main>
  );
}
