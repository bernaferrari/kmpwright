import { DynamicCodeBlock } from "fumadocs-ui/components/dynamic-codeblock";
import {
  ArrowRight,
  Bot,
  Code2,
  FlaskConical,
  Repeat,
  Sparkles,
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

const automationCode = `with(page) {
    getByPlaceholder("Search").fill("kotlin")
    getByRole("button", name = "Search").click()
    expect(getByRole("listitem")).toHaveCount(10)
}`;

const sharedCode = `suspend fun signUp(page: Page) = with(page) {
    getByLabel("Email").fill("ada@example.com")
    getByRole("button", name = "Sign up").click()
    expect(getByText("Welcome, Ada")).toBeVisible()
}`;

const platformRuns = [
  {
    id: "android",
    label: "Android",
    code: `Browser(AndroidPlatformContext(activity, webView)).use {
    signUp(it.page())
}`,
  },
  {
    id: "ios",
    label: "iOS",
    code: `Browser(IOSPlatformContext(webView = webView)).use {
    signUp(it.page())
}`,
  },
  {
    id: "desktop",
    label: "Desktop",
    code: `launchBrowser(chromePath).use {
    signUp(it.browser.newPage())
}`,
  },
];

const agentLines: Array<{ kind: "cmd" | "out"; text: string }> = [
  { kind: "cmd", text: "snapshot -i" },
  { kind: "out", text: '@e1 [input type="email"] "Email"' },
  { kind: "out", text: '@e2 [button] "Sign up"' },
  { kind: "cmd", text: "fill @e1 ada@example.com" },
  { kind: "cmd", text: "click @e2" },
];

const useCases = [
  {
    icon: Sparkles,
    title: "AI assistants that get things done",
    body: "Let an AI fill a form, find an answer or finish a checkout for your users, right inside your app.",
    href: "/docs/agents",
  },
  {
    icon: FlaskConical,
    title: "Tests on real phones",
    body: "Check that your web screens work on the devices your users actually have.",
    href: "/docs/quick-start",
  },
  {
    icon: Repeat,
    title: "Less busywork",
    body: "Automate the repetitive web steps in your app, like signing in or filling in details.",
    href: "/docs/guides/compose",
  },
];

function SectionHeading({
  id,
  title,
  children,
}: {
  id: string;
  title: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="max-w-2xl">
      <h2
        id={id}
        className="text-balance text-3xl font-semibold tracking-[-0.03em] sm:text-[2.5rem] sm:leading-[1.1]"
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
          alt="KMPwright on an iPad, automating a web page inside an app"
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
          alt="KMPwright on an Android phone, with an AI agent console under a web page"
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
    </div>
  );
}

function AgentTerminal() {
  return (
    <div className="landing-terminal overflow-x-auto rounded-xl border px-4 py-3.5 font-mono text-[13px] leading-6">
      {agentLines.map((line) =>
        line.kind === "cmd" ? (
          <p key={line.text} className="whitespace-nowrap text-foreground">
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
          <p
            key={line.text}
            className="whitespace-nowrap pl-4 text-muted-foreground"
          >
            {line.text}
          </p>
        ),
      )}
    </div>
  );
}

export default function HomePage() {
  return (
    <main className="landing-page flex flex-1 flex-col">
      <section
        aria-labelledby="hero-heading"
        className="landing-hero relative overflow-hidden"
      >
        <div className="landing-grid" aria-hidden />
        <div className="relative mx-auto grid w-full max-w-6xl items-center gap-12 px-6 pt-14 pb-16 sm:pt-20 lg:grid-cols-[1.1fr_1fr] lg:gap-10 lg:pt-24 lg:pb-24">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-2 rounded-full border bg-background/70 px-3 py-1 text-xs font-medium backdrop-blur">
              <span aria-hidden className="size-1.5 rounded-full bg-primary" />
              Open source · Kotlin Multiplatform
            </p>
            <h1
              id="hero-heading"
              className="mt-6 text-[clamp(2.75rem,6vw,4.75rem)] font-semibold leading-[1.02] tracking-[-0.045em] text-balance"
            >
              Let your app{" "}
              <span className="landing-gradient-text">use the web.</span>
            </h1>
            <p className="mt-6 max-w-lg text-pretty text-lg leading-8 text-muted-foreground">
              Automate web pages on Android, iOS and desktop with one Kotlin
              API, or hand the controls to an AI agent.
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
                  "h-11 bg-background/60 px-5",
                )}
              >
                Try it live
              </Link>
            </div>
          </div>
          <DeviceShowcase />
        </div>
      </section>

      <section aria-labelledby="dsl-heading" className="border-y bg-muted/25">
        <div className="mx-auto grid w-full max-w-6xl items-center gap-10 px-6 py-16 sm:py-24 lg:grid-cols-[0.8fr_1.2fr] lg:gap-14">
          <div>
            <SectionHeading
              id="dsl-heading"
              title="Write it once. Run it everywhere."
            >
              Describe the steps in plain Kotlin that reads like what a person
              would do. The same function runs on every device.
            </SectionHeading>
            <ul className="mt-6 space-y-2.5 text-muted-foreground">
              <li className="flex gap-2.5">
                <span aria-hidden className="text-primary">
                  ✓
                </span>
                Find things by their label, role or text
              </li>
              <li className="flex gap-2.5">
                <span aria-hidden className="text-primary">
                  ✓
                </span>
                Waits for the page on its own
              </li>
              <li className="flex gap-2.5">
                <span aria-hidden className="text-primary">
                  ✓
                </span>
                Checks retry until they pass
              </li>
            </ul>
          </div>
          <div className="min-w-0 space-y-4">
            <div className="landing-code-panel min-w-0">
              <p className="mb-2 font-mono text-xs text-muted-foreground">
                commonMain · write once
              </p>
              <DynamicCodeBlock lang="kotlin" code={sharedCode} />
            </div>
            <div className="min-w-0">
              <Tabs defaultValue="android">
                <div className="mb-2 flex items-center justify-between gap-3">
                  <p className="font-mono text-xs text-muted-foreground">
                    run it on
                  </p>
                  <TabsList>
                    {platformRuns.map((run) => (
                      <TabsTrigger key={run.id} value={run.id} className="px-3">
                        {run.label}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </div>
                {platformRuns.map((run) => (
                  <TabsContent key={run.id} value={run.id}>
                    <DynamicCodeBlock lang="kotlin" code={run.code} />
                  </TabsContent>
                ))}
              </Tabs>
            </div>
          </div>
        </div>
      </section>

      <section aria-labelledby="ways-heading" className="border-b">
        <div className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24">
          <SectionHeading
            id="ways-heading"
            title="Write it yourself. Or let AI do it."
          >
            Same page, two ways to drive it. Mix them however you like.
          </SectionHeading>
          <div className="mt-10 grid gap-5 lg:grid-cols-2">
            <div className="flex min-w-0 flex-col rounded-2xl border bg-background p-6 sm:p-7">
              <Code2 aria-hidden className="size-6 text-primary" />
              <h3 className="mt-4 text-xl font-semibold tracking-tight">
                You write the steps
              </h3>
              <p className="mt-2 text-pretty leading-7 text-muted-foreground">
                Use the Kotlin API when you know the page and want the same
                result every time.
              </p>
              <div className="landing-code-panel mt-6 min-w-0">
                <DynamicCodeBlock lang="kotlin" code={automationCode} />
              </div>
            </div>
            <div className="flex min-w-0 flex-col rounded-2xl border bg-background p-6 sm:p-7">
              <Bot aria-hidden className="size-6 text-primary" />
              <h3 className="mt-4 text-xl font-semibold tracking-tight">
                An AI agent takes over
              </h3>
              <p className="mt-2 text-pretty leading-7 text-muted-foreground">
                The agent reads a short list of what’s on the page, then picks
                what to fill and click. One tool is all it needs.
              </p>
              <div className="mt-6 min-w-0">
                <AgentTerminal />
              </div>
            </div>
          </div>
        </div>
      </section>

      <section
        id="demo"
        aria-labelledby="demo-heading"
        className="mx-auto w-full max-w-6xl scroll-mt-20 px-6 py-16 sm:py-24"
      >
        <SectionHeading id="demo-heading" title="Try it right here.">
          You’re the AI agent. Tap a suggestion or a badge on the page and watch
          it act.
        </SectionHeading>
        <div className="mt-10">
          <LiveDemo />
        </div>
      </section>

      <section aria-labelledby="uses-heading" className="border-y bg-muted/25">
        <div className="mx-auto w-full max-w-6xl px-6 py-16 sm:py-24">
          <SectionHeading id="uses-heading" title="What you can build." />
          <div className="mt-10 grid gap-5 md:grid-cols-3">
            {useCases.map(({ icon: Icon, title, body, href }) => (
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
                <p className="mt-2.5 flex-1 text-pretty leading-7 text-muted-foreground">
                  {body}
                </p>
                <span className="mt-5 inline-flex items-center gap-1.5 text-sm font-medium">
                  Learn more
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

      <section aria-labelledby="cta-heading" className="px-6 py-16 sm:py-24">
        <div className="landing-cta mx-auto w-full max-w-3xl rounded-3xl border p-8 text-center sm:p-12">
          <h2
            id="cta-heading"
            className="text-balance text-3xl font-semibold tracking-[-0.03em] sm:text-4xl"
          >
            One line to get started.
          </h2>
          <p className="mx-auto mt-4 max-w-md text-pretty leading-7 text-muted-foreground">
            Works on Android, iOS and desktop. Free and open source.
          </p>
          <div className="mx-auto mt-8 max-w-xl min-w-0 text-left">
            <DynamicCodeBlock lang="kotlin" code={dependency} />
          </div>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <Link
              href="/docs/quick-start"
              className={cn(buttonVariants({ size: "lg" }), "h-11 gap-2 px-5")}
            >
              Read the quick start{" "}
              <ArrowRight aria-hidden data-icon="inline-end" />
            </Link>
            <a
              href="https://github.com/bernaferrari/kmpwright"
              className={cn(
                buttonVariants({ variant: "outline", size: "lg" }),
                "h-11 bg-background/60 px-5",
              )}
            >
              View on GitHub
            </a>
          </div>
        </div>
      </section>

      <footer className="border-t">
        <div className="mx-auto flex w-full max-w-6xl flex-col justify-between gap-3 px-6 py-8 text-sm text-muted-foreground sm:flex-row">
          <p>
            <span className="font-semibold text-foreground">KMPwright</span> ·
            MIT licensed
          </p>
          <div className="flex gap-5">
            <Link href="/docs" className="hover:text-foreground">
              Docs
            </Link>
            <a
              href="https://github.com/bernaferrari/kmpwright"
              className="hover:text-foreground"
            >
              GitHub
            </a>
            <Link href="/docs/changelog" className="hover:text-foreground">
              Changelog
            </Link>
          </div>
        </div>
      </footer>
    </main>
  );
}
