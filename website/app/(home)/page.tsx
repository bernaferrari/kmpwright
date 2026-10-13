import { DynamicCodeBlock } from "fumadocs-ui/components/dynamic-codeblock";
import {
  ArrowRight,
  Bot,
  Camera,
  Globe,
  Network,
  ShieldCheck,
  Smartphone,
} from "lucide-react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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

const features = [
  {
    icon: Bot,
    title: "Built for agents",
    body: "Snapshots with @e refs, compact text output and a single runCommand() you can hand to any LLM as a tool.",
  },
  {
    icon: Smartphone,
    title: "Your app’s own WebView",
    body: "Attach to the Android WebView or iOS WKWebView already on screen, or let KMPwright create one.",
  },
  {
    icon: Globe,
    title: "Playwright-style API",
    body: "Locators, auto-waiting actions, web-first assertions and semantic selectors in shared Kotlin.",
  },
  {
    icon: Network,
    title: "Network control",
    body: "Mock routes, read request history and export HAR, with support reported per platform.",
  },
  {
    icon: Camera,
    title: "See what happened",
    body: "Viewport, element and full-page screenshots and visual diffs; video and traces on Chromium.",
  },
  {
    icon: ShieldCheck,
    title: "Control your workflows",
    body: "Configure allowed origins, action policies and encrypted session files. Check the documented limits before an unattended run.",
  },
];

const platforms = [
  [
    "Android 8+",
    "Android WebView",
    "Your app’s WebView, or one KMPwright creates",
  ],
  ["iOS 15+", "WKWebView", "Shares the same Kotlin API from iosMain or Swift"],
  [
    "JVM 17+",
    "Chromium (CDP)",
    "Launch Chrome, attach to one, or use a cloud provider",
  ],
];

export default function HomePage() {
  return (
    <main className="flex flex-1 flex-col">
      <section className="relative overflow-hidden">
        <div
          aria-hidden
          className="pointer-events-none absolute inset-x-0 -top-40 mx-auto h-96 max-w-4xl rounded-full bg-gradient-to-r from-[#5b4fc4]/25 via-[#8a4fa9]/20 to-[#00696b]/25 blur-3xl"
        />
        <div className="relative mx-auto flex w-full max-w-5xl flex-col items-center px-6 pt-24 pb-14 text-center">
          <Badge variant="secondary" className="mb-6">
            Kotlin Multiplatform · Android · iOS · JVM
          </Badge>
          <h1 className="text-balance text-3xl font-semibold tracking-tight sm:text-6xl">
            Browser automation,
            <br />
            <span className="bg-gradient-to-r from-[#5b4fc4] via-[#8a4fa9] to-[#00696b] bg-clip-text text-transparent dark:from-[#bcafff] dark:via-[#e0aff0] dark:to-[#7ddbd2]">
              in shared Kotlin.
            </span>
          </h1>
          <p className="mt-6 max-w-2xl text-balance text-lg text-muted-foreground">
            Automate Android WebView, iOS WKWebView and JVM Chromium with one
            Kotlin workflow. Use typed locators and assertions, or pass{" "}
            <a
              className="underline underline-offset-4 hover:text-foreground"
              href="https://agent-browser.dev"
            >
              agent-browser
            </a>{" "}
            commands to the same page.
          </p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <Link
              href="/docs/installation"
              className={cn(
                buttonVariants({ size: "lg" }),
                "h-11 rounded-full px-6 text-base",
              )}
            >
              Get started <ArrowRight data-icon="inline-end" />
            </Link>
            <Link
              href="/docs/agents"
              className={cn(
                buttonVariants({ variant: "outline", size: "lg" }),
                "h-11 rounded-full px-6 text-base",
              )}
            >
              Build an agent
            </Link>
          </div>
          <p className="mt-4 text-sm text-muted-foreground">
            Alpha 0.1.0 · Build from source today · Maven artifacts coming with
            the first release
          </p>
        </div>
      </section>

      <section
        aria-labelledby="workflow-heading"
        className="mx-auto w-full max-w-3xl px-6 pb-20"
      >
        <h2
          id="workflow-heading"
          className="mb-2 text-center text-2xl font-semibold tracking-tight"
        >
          A workflow you can run
        </h2>
        <p className="mb-6 text-center text-muted-foreground">
          Fill a form, click, verify the result, and keep a screenshot.
        </p>
        <Tabs defaultValue="workflow">
          <TabsList className="mx-auto min-h-14 max-w-full">
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
            <TabsContent key={example.id} value={example.id} className="mt-3">
              <DynamicCodeBlock lang="kotlin" code={example.code} />
            </TabsContent>
          ))}
        </Tabs>
        <p className="mt-4 text-sm text-muted-foreground">
          This example supplies its own HTML, so it runs without a demo server.{" "}
          <Link
            href="/docs/quick-start"
            className="underline underline-offset-4 hover:text-foreground"
          >
            Connect your browser
          </Link>{" "}
          to get a <code>Page</code>, then call <code>subscribe(page)</code>.
          Screenshot bytes are returned after the assertion passes.
        </p>
      </section>

      <section
        aria-labelledby="features-heading"
        className="mx-auto grid w-full max-w-5xl gap-4 px-6 pb-20 sm:grid-cols-2 lg:grid-cols-3"
      >
        <h2 id="features-heading" className="sr-only">
          Browser workflows and tools
        </h2>
        {features.map(({ icon: Icon, title, body }) => (
          <Card key={title}>
            <CardHeader>
              <Icon aria-hidden className="mb-2 size-5 text-primary" />
              <CardTitle>
                <h3>{title}</h3>
              </CardTitle>
              <CardDescription>{body}</CardDescription>
            </CardHeader>
          </Card>
        ))}
      </section>

      <section className="mx-auto w-full max-w-5xl px-6 pb-24">
        <h2 className="mb-6 text-center text-2xl font-semibold tracking-tight">
          One API, three backends
        </h2>
        <div className="overflow-hidden rounded-2xl border">
          {platforms.map(([target, backend, note]) => (
            <div
              key={target}
              className="grid grid-cols-1 gap-1 border-b p-4 last:border-b-0 sm:grid-cols-[140px_200px_1fr]"
            >
              <span className="font-medium">{target}</span>
              <span className="font-mono text-sm">{backend}</span>
              <span className="text-sm text-muted-foreground">{note}</span>
            </div>
          ))}
        </div>
        <p className="mt-6 text-center text-sm text-muted-foreground">
          Features follow each backend’s abilities.{" "}
          <Link
            href="/docs/platforms/capabilities"
            className="underline underline-offset-4 hover:text-foreground"
          >
            See the capability matrix
          </Link>
          .
        </p>
      </section>
    </main>
  );
}
