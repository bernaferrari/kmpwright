import { DynamicCodeBlock } from "fumadocs-ui/components/dynamic-codeblock";
import {
  Accessibility,
  ArrowRight,
  Bot,
  Camera,
  CheckCheck,
  Cookie,
  Crosshair,
  Film,
  FlaskConical,
  Globe,
  Layers3,
  ListChecks,
  MousePointerClick,
  Network,
  PanelsTopLeft,
  Server,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Tablet,
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";
import { LiveDemo } from "@/components/live-demo";
import { buttonVariants } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";

const dependency =
  'implementation("io.github.bernaferrari:kmpwright-core:0.0.1")';

const playwrightCode = `// Typed, suspending, auto-waiting.
page.getByLabel("Email").fill("ada@example.com")
page.getByRole("checkbox", name = "I agree").check()
page.getByRole("button", name = "Sign up").click()

expect(page.getByRole("status")).toHaveText("Welcome, Ada")`;

const agentLines: Array<{ kind: "cmd" | "out"; text: string }> = [
  { kind: "cmd", text: "snapshot -i" },
  { kind: "out", text: '@e1 [input type="email"] "Email"' },
  { kind: "out", text: '@e2 [checkbox] "I agree"' },
  { kind: "out", text: '@e3 [button] "Sign up"' },
  { kind: "cmd", text: "fill @e1 ada@example.com" },
  { kind: "cmd", text: "check @e2" },
  { kind: "cmd", text: "click @e3" },
];

const lineage = [
  {
    from: "From Playwright",
    icon: Crosshair,
    items: [
      "goto, locator, getByRole, fill, expect",
      "Actionability checks and auto-waiting",
      "Web-first assertions that retry",
      "page.route to mock and block requests",
    ],
    href: "/docs/coming-from-playwright",
    label: "Playwright → KMPwright",
  },
  {
    from: "From agent-browser",
    icon: Bot,
    items: [
      "snapshot -i with stable @e refs",
      "The same command grammar, in-process",
      "Allowed domains and action policies",
      "Content boundaries for untrusted pages",
    ],
    href: "/docs/coming-from-agent-browser",
    label: "agent-browser → KMPwright",
  },
  {
    from: "New in KMPwright",
    icon: Sparkles,
    items: [
      "Runs inside your Android and iOS app",
      "One API in commonMain, callable from Swift",
      "Compose WebView with a built-in controller",
      "Capabilities reported per backend",
    ],
    href: "/docs/platforms/capabilities",
    label: "Compare backends",
  },
];

const useCases = [
  {
    icon: Bot,
    title: "An AI assistant that uses the web for your users",
    body: "Give Gemini, Claude or a Koog agent one browser tool. It reads a compact snapshot, picks a ref and acts, while the model, the tool and the page all run in your app.",
    href: "/docs/agents/llm-tools",
    label: "Wire up an LLM tool",
  },
  {
    icon: FlaskConical,
    title: "End-to-end checks on the real device",
    body: "Verify web flows in the WebView your users actually get, with your app's cookies, session and network identity. No driver, no daemon, no port forwarding.",
    href: "/docs/quick-start",
    label: "Write a first check",
  },
  {
    icon: MousePointerClick,
    title: "Automate the web parts of a hybrid app",
    body: "Autofill a checkout, skip a login wall or read a result back into native UI. A Compose controller exposes the page next to your own state.",
    href: "/docs/guides/compose",
    label: "Use it from Compose",
  },
  {
    icon: Server,
    title: "The same workflow on the JVM",
    body: "Run the shared code against Chrome over CDP in CI or on a server: launch it locally, attach to an endpoint or connect to a cloud browser provider.",
    href: "/docs/platforms/jvm",
    label: "Run on Chromium",
  },
];

const loop = [
  {
    step: "Observe",
    command: "snapshot -i",
    note: "An accessibility tree with a ref per control. A few lines, not a page of HTML.",
  },
  {
    step: "Pick",
    command: "@e3",
    note: "The model names the exact element it read. No selectors to guess.",
  },
  {
    step: "Act",
    command: "click @e3",
    note: "Auto-waits for the element, then clicks, fills, selects or scrolls.",
  },
  {
    step: "Repeat",
    command: "snapshot -i",
    note: "After the page changes. Stale refs fail with a message that says what to do next.",
  },
];

const guardrails = [
  {
    title: "Allowed domains",
    body: "Limit which hosts the page and its requests may reach.",
  },
  {
    title: "Action policies",
    body: "Allow, deny or require your confirmation for actions, per domain.",
  },
  {
    title: "Content boundaries",
    body: "Mark page text as untrusted inside tool output to blunt prompt injection.",
  },
  {
    title: "Feature switches",
    body: "Turn off eval and DOM extraction for production builds.",
  },
];

const features = [
  {
    icon: Crosshair,
    title: "Locators",
    body: "Roles, labels, text, test ids, filters and frames.",
    href: "/docs/guides/locators",
  },
  {
    icon: CheckCheck,
    title: "Assertions",
    body: "expect(locator) and expect(page) retry until true.",
    href: "/docs/guides/assertions",
  },
  {
    icon: Network,
    title: "Network",
    body: "Route, mock and block requests. Read history and HAR.",
    href: "/docs/guides/network",
  },
  {
    icon: Camera,
    title: "Screenshots",
    body: "Element, clip or full page, with pixel diffs.",
    href: "/docs/guides/screenshots",
  },
  {
    icon: Cookie,
    title: "Session state",
    body: "Cookies and storage, saved encrypted and restored.",
    href: "/docs/guides/storage",
  },
  {
    icon: Accessibility,
    title: "Accessibility audit",
    body: "Bundled axe-core, run against the live page.",
    href: "/docs/guides/accessibility",
  },
  {
    icon: PanelsTopLeft,
    title: "Dialogs & frames",
    body: "Answer alerts and prompts, reach into iframes.",
    href: "/docs/guides/dialogs-and-frames",
  },
  {
    icon: Film,
    title: "Video & traces",
    body: "Recordings, screencasts and CPU profiles on Chromium.",
    href: "/docs/guides/recording",
  },
];

const platforms = [
  {
    target: "Android",
    minimum: "API 26+",
    backend: "android.webkit.WebView",
    icon: Smartphone,
    href: "/docs/platforms/android",
    note: "Attach to the WebView your app already shows, or host one with the Compose wrapper.",
  },
  {
    target: "iOS",
    minimum: "15+",
    backend: "WKWebView",
    icon: Tablet,
    href: "/docs/platforms/ios",
    note: "Call the shared API from Kotlin in iosMain, or from Swift through the framework.",
  },
  {
    target: "JVM",
    minimum: "17+",
    backend: "Chromium · CDP",
    icon: Globe,
    href: "/docs/platforms/jvm",
    note: "Launch Chrome, attach to a running browser, or connect to a cloud provider.",
  },
];

const installs = [
  {
    id: "kmp",
    label: "Multiplatform",
    code: `commonMain.dependencies {
    ${dependency}
}`,
  },
  {
    id: "compose",
    label: "+ Compose",
    code: `// androidMain: a WebView composable with a KMPwright controller
implementation("io.github.bernaferrari:kmpwright-compose:0.0.1")`,
  },
  {
    id: "run",
    label: "First run",
    code: `Browser(AndroidPlatformContext(activity, webView)).use { browser ->
    val page = browser.page()
    page.runCommand("snapshot -i")
}`,
  },
];

function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="landing-eyebrow">{children}</p>;
}

function SectionHeading({
  id,
  eyebrow,
  title,
  children,
  className,
}: {
  id: string;
  eyebrow: string;
  title: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("max-w-2xl", className)}>
      <Eyebrow>{eyebrow}</Eyebrow>
      <h2
        id={id}
        className="mt-3 text-balance text-3xl font-semibold tracking-[-0.03em] sm:text-[2.5rem] sm:leading-[1.1]"
      >
        {title}
      </h2>
      {children && (
        <p className="mt-4 text-pretty text-base leading-7 text-muted-foreground sm:text-lg">
          {children}
        </p>
      )}
    </div>
  );
}

function DeviceShowcase() {
  return (
    <div className="landing-devices relative mx-auto h-[min(118vw,440px)] w-full max-w-[540px] sm:h-[540px] lg:mx-0 lg:ml-auto">
      <div className="landing-device-glow" aria-hidden />
      <figure className="landing-tablet absolute top-0 right-0 w-[74%]">
        <Image
          src="/screens/ipad-recipes.png"
          alt="KMPwright sample app on iPad: a WKWebView with ref badges and a recipe that runs snapshot, fill and press commands"
          width={965}
          height={1360}
          priority
          sizes="(min-width: 1024px) 400px, 70vw"
          className="block h-auto w-full"
        />
      </figure>
      <figure className="landing-phone absolute bottom-0 left-0 w-[40%]">
        <Image
          src="/screens/android-agent.png"
          alt="KMPwright sample app on Android: the agent console running snapshot -i against a WebView with @e badges"
          width={646}
          height={1250}
          priority
          sizes="(min-width: 1024px) 220px, 40vw"
          className="block h-auto w-full"
        />
      </figure>
      <div className="landing-float absolute top-[46%] -left-2 hidden rounded-xl border bg-background/90 px-3.5 py-2.5 font-mono text-[11.5px] leading-5 shadow-lg backdrop-blur sm:block lg:-left-10">
        <p>
          <span className="text-primary">❯</span> fill{" "}
          <span className="text-primary">@e3</span> snapshot
        </p>
        <p>
          <span className="text-primary">❯</span> press Enter{" "}
          <span className="text-primary">@e3</span>
        </p>
        <p className="text-emerald-600 dark:text-emerald-400">
          ✓ Accessibility snapshot
        </p>
      </div>
      <div className="absolute right-3 bottom-3 hidden gap-2 text-[11px] font-medium sm:flex">
        <span className="rounded-full border bg-background/90 px-2.5 py-1 backdrop-blur">
          Android · WebView
        </span>
        <span className="rounded-full border bg-background/90 px-2.5 py-1 backdrop-blur">
          iPadOS · WKWebView
        </span>
      </div>
    </div>
  );
}

function AgentTerminal() {
  return (
    <div className="landing-terminal h-full overflow-hidden rounded-xl border">
      <div className="flex items-center gap-2 border-b px-4 py-2.5 text-xs text-muted-foreground">
        <span className="font-mono">page.runCommand(…)</span>
      </div>
      <div className="px-4 py-3.5 font-mono text-[13px] leading-6">
        {agentLines.map((line, index) =>
          line.kind === "cmd" ? (
            // biome-ignore lint/suspicious/noArrayIndexKey: static list
            <p key={index} className="text-foreground">
              <span className="text-primary select-none">❯ </span>
              {line.text.split(/(@e\d+)/g).map((part, i) =>
                /^@e\d+$/.test(part) ? (
                  // biome-ignore lint/suspicious/noArrayIndexKey: parts of one string
                  <span key={i} className="text-primary">
                    {part}
                  </span>
                ) : (
                  part
                ),
              )}
            </p>
          ) : (
            // biome-ignore lint/suspicious/noArrayIndexKey: static list
            <p key={index} className="pl-4 text-muted-foreground">
              {line.text}
            </p>
          ),
        )}
      </div>
    </div>
  );
}

export default function HomePage() {
  return (
    <main className="landing-page flex flex-1 flex-col">
      {/* Hero */}
      <section
        aria-labelledby="hero-heading"
        className="landing-hero relative overflow-hidden"
      >
        <div className="landing-grid" aria-hidden />
        <div className="relative mx-auto grid w-full max-w-6xl items-center gap-12 px-6 pt-14 pb-16 sm:pt-20 lg:grid-cols-[1.1fr_1fr] lg:gap-10 lg:pt-24 lg:pb-24">
          <div className="min-w-0">
            <Link
              href="/docs/changelog"
              className="inline-flex items-center gap-2 rounded-full border bg-background/70 py-1 pr-3 pl-1 text-xs font-medium backdrop-blur transition-colors hover:border-primary/40"
            >
              <span className="rounded-full bg-primary px-2 py-0.5 text-[11px] text-primary-foreground">
                0.0.1 alpha
              </span>
              Now on Maven Central
              <ArrowRight aria-hidden className="size-3.5" />
            </Link>
            <h1
              id="hero-heading"
              className="mt-6 text-[clamp(2.4rem,4.7vw,3.9rem)] font-semibold leading-[1.04] tracking-[-0.045em] text-balance"
            >
              Playwright and agent&#8209;browser,{" "}
              <span className="landing-gradient-text">
                inside your Android and iOS app.
              </span>
            </h1>
            <p className="mt-6 max-w-xl text-pretty text-lg leading-8 text-muted-foreground">
              KMPwright drives Android WebView, iOS WKWebView and JVM Chromium
              from one Kotlin API. Typed locators and assertions for your
              automation. Snapshots and{" "}
              <code className="landing-inline-code">@refs</code> for your AI
              agent. All in-process, with no driver and no daemon.
            </p>
            <div className="mt-8 flex flex-wrap items-center gap-3">
              <Link
                href="/docs/quick-start"
                className={cn(
                  buttonVariants({ size: "lg" }),
                  "h-11 gap-2 px-5",
                )}
              >
                Get started <ArrowRight aria-hidden data-icon="inline-end" />
              </Link>
              <Link
                href="#demo"
                className={cn(
                  buttonVariants({ variant: "outline", size: "lg" }),
                  "h-11 gap-2 bg-background/60 px-5",
                )}
              >
                Try it live
              </Link>
              <a
                href="https://github.com/bernaferrari/kmpwright"
                className={cn(
                  buttonVariants({ variant: "ghost", size: "lg" }),
                  "h-11 gap-1.5 px-3",
                )}
              >
                GitHub
                <ArrowRight aria-hidden className="size-3.5 -rotate-45" />
              </a>
            </div>
            <div className="mt-8 max-w-xl">
              <DynamicCodeBlock lang="kotlin" code={dependency} />
              <p className="mt-2 text-xs text-muted-foreground">
                One dependency in <code>commonMain</code>. Android, iOS and JVM
                variants are picked by Gradle. MIT licensed.
              </p>
            </div>
          </div>
          <DeviceShowcase />
        </div>
      </section>

      {/* Two APIs, one page */}
      <section aria-labelledby="dual-heading" className="border-y bg-muted/25">
        <div className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24">
          <SectionHeading
            id="dual-heading"
            eyebrow="TWO WAYS TO DRIVE ONE PAGE"
            title={
              <>
                Code for your automation.
                <br className="hidden sm:block" /> Commands for your agent.
              </>
            }
          >
            The same sign-up, written twice. Use the typed API where you know
            the page, hand the command line to a model where you don’t, and mix
            them freely:{" "}
            <code className="landing-inline-code">page.locator("@e3")</code>{" "}
            resolves a ref from the latest snapshot.
          </SectionHeading>

          <div className="mt-10 grid gap-5 lg:grid-cols-2">
            <div className="flex min-w-0 flex-col">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium">
                <Crosshair aria-hidden className="size-4 text-primary" />
                Playwright-style Kotlin
              </div>
              <div className="landing-code-panel min-w-0 flex-1">
                <DynamicCodeBlock lang="kotlin" code={playwrightCode} />
              </div>
            </div>
            <div className="flex min-w-0 flex-col">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium">
                <Bot aria-hidden className="size-4 text-primary" />
                agent-browser commands
              </div>
              <AgentTerminal />
            </div>
          </div>

          <div className="mt-14 grid gap-px overflow-hidden rounded-2xl border bg-border md:grid-cols-3">
            {lineage.map(({ from, icon: Icon, items, href, label }) => (
              <div
                key={from}
                className="flex flex-col bg-background p-6 sm:p-7"
              >
                <div className="flex items-center gap-2.5">
                  <span className="grid size-8 place-items-center rounded-lg bg-primary/10 text-primary">
                    <Icon aria-hidden className="size-4" />
                  </span>
                  <h3 className="font-semibold tracking-tight">{from}</h3>
                </div>
                <ul className="mt-5 flex-1 space-y-2.5 text-sm text-muted-foreground">
                  {items.map((item) => (
                    <li key={item} className="flex gap-2.5">
                      <span
                        aria-hidden
                        className="mt-2 size-1 shrink-0 rounded-full bg-primary"
                      />
                      {item}
                    </li>
                  ))}
                </ul>
                <Link href={href} className="landing-text-link mt-4 text-sm">
                  {label} <ArrowRight aria-hidden className="size-3.5" />
                </Link>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Live demo */}
      <section
        id="demo"
        aria-labelledby="demo-heading"
        className="mx-auto w-full max-w-6xl scroll-mt-20 px-6 py-16 sm:py-24"
      >
        <div className="flex flex-wrap items-end justify-between gap-6">
          <SectionHeading
            id="demo-heading"
            eyebrow="DON’T TAKE OUR WORD FOR IT"
            title="Be the agent."
          >
            This is the real KMPwright engine compiled to JavaScript, driving
            the page on the left. Snapshot it, then click a badge or type a
            command, just like a model would.
          </SectionHeading>
          <Link
            href="/docs/agents/commands"
            className="landing-text-link text-sm"
          >
            All commands <ArrowRight aria-hidden className="size-3.5" />
          </Link>
        </div>
        <div className="mt-10">
          <LiveDemo />
        </div>
      </section>

      {/* Use cases */}
      <section aria-labelledby="uses-heading" className="border-y bg-muted/25">
        <div className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24">
          <SectionHeading
            id="uses-heading"
            eyebrow="WHAT PEOPLE BUILD"
            title="Automation and AI where your users already are."
          >
            Desktop browser tools stop at the laptop. KMPwright puts the same
            ideas on the phone, inside the app, next to your own code.
          </SectionHeading>
          <div className="mt-10 grid gap-5 sm:grid-cols-2">
            {useCases.map(({ icon: Icon, title, body, href, label }) => (
              <Link
                key={title}
                href={href}
                className="landing-card group flex flex-col rounded-2xl border bg-background p-6 sm:p-7"
              >
                <span className="grid size-10 place-items-center rounded-xl bg-primary/10 text-primary">
                  <Icon aria-hidden className="size-5" />
                </span>
                <h3 className="mt-5 text-lg font-semibold tracking-tight">
                  {title}
                </h3>
                <p className="mt-2.5 flex-1 text-pretty text-sm leading-6 text-muted-foreground">
                  {body}
                </p>
                <span className="mt-5 inline-flex items-center gap-1.5 text-sm font-medium">
                  {label}
                  <ArrowRight
                    aria-hidden
                    className="size-3.5 transition-transform group-hover:translate-x-0.5"
                  />
                </span>
              </Link>
            ))}
          </div>
        </div>
      </section>

      {/* Agent loop + guardrails */}
      <section
        aria-labelledby="agents-heading"
        className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24"
      >
        <SectionHeading
          id="agents-heading"
          eyebrow="BUILT FOR THE AGENT LOOP"
          title="One tool. Small snapshots. Errors a model can act on."
        >
          Expose <code className="landing-inline-code">page.runCommand()</code>{" "}
          as a single tool and pass{" "}
          <code className="landing-inline-code">AgentCommands.HELP</code> as its
          description. That is the whole integration.
        </SectionHeading>

        <ol className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {loop.map(({ step, command, note }, index) => (
            <li key={step} className="relative rounded-2xl border p-5">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-muted-foreground">
                  {String(index + 1).padStart(2, "0")} · {step}
                </span>
              </div>
              <p className="mt-3 font-mono text-sm text-primary">{command}</p>
              <p className="mt-3 text-sm leading-6 text-muted-foreground">
                {note}
              </p>
            </li>
          ))}
        </ol>

        <div className="landing-guard mt-6 grid gap-8 rounded-2xl border p-6 sm:p-8 lg:grid-cols-[0.8fr_1.2fr]">
          <div>
            <ShieldCheck aria-hidden className="size-6 text-primary" />
            <h3 className="mt-4 text-xl font-semibold tracking-tight">
              Guardrails, because the page is untrusted.
            </h3>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">
              On a phone the agent acts with your app’s cookies and network
              identity. Decide what it can reach and what it can do before it
              starts.
            </p>
            <Link
              href="/docs/security"
              className="landing-text-link mt-3 text-sm"
            >
              Security model <ArrowRight aria-hidden className="size-3.5" />
            </Link>
          </div>
          <div className="grid gap-x-8 gap-y-5 sm:grid-cols-2">
            {guardrails.map(({ title, body }) => (
              <div key={title} className="flex gap-3">
                <ListChecks
                  aria-hidden
                  className="mt-0.5 size-4 shrink-0 text-primary"
                />
                <div>
                  <p className="text-sm font-medium">{title}</p>
                  <p className="mt-1 text-sm leading-6 text-muted-foreground">
                    {body}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <p className="mt-6 text-sm text-muted-foreground">
          Writing the integration with a coding agent? Point it at{" "}
          <a
            href="/llms.txt"
            className="font-medium text-foreground underline underline-offset-4"
          >
            llms.txt
          </a>{" "}
          or the{" "}
          <Link
            href="/docs/agents/skills"
            className="font-medium text-foreground underline underline-offset-4"
          >
            KMPwright skill
          </Link>
          .
        </p>
      </section>

      {/* Features */}
      <section
        aria-labelledby="features-heading"
        className="border-y bg-muted/25"
      >
        <div className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24">
          <SectionHeading
            id="features-heading"
            eyebrow="BEYOND THE FIRST CLICK"
            title="The rest of the toolbox."
          />
          <div className="mt-10 grid gap-px overflow-hidden rounded-2xl border bg-border sm:grid-cols-2 lg:grid-cols-4">
            {features.map(({ icon: Icon, title, body, href }) => (
              <Link
                key={title}
                href={href}
                className="group bg-background p-5 transition-colors hover:bg-muted/40"
              >
                <Icon aria-hidden className="size-5 text-primary" />
                <h3 className="mt-4 flex items-center gap-1.5 font-semibold tracking-tight">
                  {title}
                  <ArrowRight
                    aria-hidden
                    className="size-3.5 opacity-0 transition-opacity group-hover:opacity-100"
                  />
                </h3>
                <p className="mt-1.5 text-sm leading-6 text-muted-foreground">
                  {body}
                </p>
              </Link>
            ))}
          </div>
        </div>
      </section>

      {/* Platforms */}
      <section
        aria-labelledby="platforms-heading"
        className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24"
      >
        <div className="flex flex-wrap items-end justify-between gap-6">
          <SectionHeading
            id="platforms-heading"
            eyebrow="SHARED WORKFLOW. NATIVE ENGINES."
            title="Write it once in commonMain."
          >
            Each backend uses the platform’s own browser engine and reports what
            it supports, so portable code can check before it relies on a
            feature.
          </SectionHeading>
          <Link
            href="/docs/platforms/capabilities"
            className="landing-text-link text-sm"
          >
            Capability matrix <ArrowRight aria-hidden className="size-3.5" />
          </Link>
        </div>
        <div className="mt-10 grid gap-5 md:grid-cols-3">
          {platforms.map(
            ({ target, minimum, backend, icon: Icon, note, href }) => (
              <Link
                key={target}
                href={href}
                className="landing-card group flex flex-col rounded-2xl border p-6"
              >
                <div className="flex items-center justify-between">
                  <Icon aria-hidden className="size-6 text-primary" />
                  <span className="rounded-md bg-muted px-2 py-0.5 font-mono text-[11px] text-muted-foreground">
                    {minimum}
                  </span>
                </div>
                <h3 className="mt-5 text-xl font-semibold tracking-tight">
                  {target}
                </h3>
                <p className="mt-1 font-mono text-xs text-muted-foreground">
                  {backend}
                </p>
                <p className="mt-4 flex-1 text-sm leading-6 text-muted-foreground">
                  {note}
                </p>
                <span className="mt-5 inline-flex items-center gap-1.5 text-sm font-medium">
                  {target} guide
                  <ArrowRight
                    aria-hidden
                    className="size-3.5 transition-transform group-hover:translate-x-0.5"
                  />
                </span>
              </Link>
            ),
          )}
        </div>
      </section>

      {/* Final CTA */}
      <section aria-labelledby="cta-heading" className="px-6 pb-16 sm:pb-24">
        <div className="landing-cta relative mx-auto grid w-full max-w-6xl gap-10 overflow-hidden rounded-3xl border p-8 sm:p-12 lg:grid-cols-[0.8fr_1.2fr] lg:items-center">
          <div>
            <Layers3 aria-hidden className="size-7 text-primary" />
            <h2
              id="cta-heading"
              className="mt-5 text-balance text-3xl font-semibold tracking-[-0.03em] sm:text-4xl"
            >
              Give your app a browser it can drive.
            </h2>
            <p className="mt-4 max-w-md text-pretty leading-7 text-muted-foreground">
              Add the dependency, attach your WebView and run{" "}
              <code className="landing-inline-code">snapshot -i</code>. The
              quick start walks through all three platforms.
            </p>
            <div className="mt-7 flex flex-wrap gap-3">
              <Link
                href="/docs/quick-start"
                className={cn(
                  buttonVariants({ size: "lg" }),
                  "h-11 gap-2 px-5",
                )}
              >
                Read the quick start{" "}
                <ArrowRight aria-hidden data-icon="inline-end" />
              </Link>
              <Link
                href="/docs/sample-app"
                className={cn(
                  buttonVariants({ variant: "outline", size: "lg" }),
                  "h-11 bg-background/60 px-5",
                )}
              >
                Run the sample app
              </Link>
            </div>
          </div>
          <div className="min-w-0">
            <Tabs defaultValue="kmp">
              <TabsList className="max-w-full">
                {installs.map((tab) => (
                  <TabsTrigger key={tab.id} value={tab.id} className="px-3">
                    {tab.label}
                  </TabsTrigger>
                ))}
              </TabsList>
              {installs.map((tab) => (
                <TabsContent key={tab.id} value={tab.id} className="mt-3">
                  <DynamicCodeBlock lang="kotlin" code={tab.code} />
                </TabsContent>
              ))}
            </Tabs>
          </div>
        </div>
      </section>

      <footer className="border-t">
        <div className="mx-auto grid w-full max-w-6xl gap-8 px-6 py-10 text-sm sm:grid-cols-[1.5fr_1fr_1fr_1fr]">
          <div>
            <p className="font-semibold">KMPwright</p>
            <p className="mt-2 max-w-xs leading-6 text-muted-foreground">
              Browser automation and AI agents for Kotlin Multiplatform. MIT
              licensed.
            </p>
          </div>
          <FooterColumn
            title="Start"
            links={[
              ["Installation", "/docs/installation"],
              ["Quick start", "/docs/quick-start"],
              ["Sample app", "/docs/sample-app"],
            ]}
          />
          <FooterColumn
            title="Agents"
            links={[
              ["Build an agent", "/docs/agents"],
              ["Commands", "/docs/agents/commands"],
              ["Security", "/docs/security"],
            ]}
          />
          <FooterColumn
            title="Project"
            links={[
              ["GitHub", "https://github.com/bernaferrari/kmpwright"],
              ["Changelog", "/docs/changelog"],
              ["agent-browser", "https://agent-browser.dev"],
            ]}
          />
        </div>
        <div className="mx-auto w-full max-w-6xl border-t px-6 py-5 text-xs text-muted-foreground">
          Playwright-inspired API. Agent commands adapted from Vercel’s
          agent-browser. Not affiliated with Microsoft or Vercel.
        </div>
      </footer>
    </main>
  );
}

function FooterColumn({
  title,
  links,
}: {
  title: string;
  links: Array<[string, string]>;
}) {
  return (
    <div>
      <p className="text-xs font-medium tracking-wide text-muted-foreground">
        {title}
      </p>
      <ul className="mt-3 space-y-2">
        {links.map(([label, href]) => (
          <li key={label}>
            {href.startsWith("http") ? (
              <a href={href} className="hover:underline underline-offset-4">
                {label}
              </a>
            ) : (
              <Link href={href} className="hover:underline underline-offset-4">
                {label}
              </Link>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
