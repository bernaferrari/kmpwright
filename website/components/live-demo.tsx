"use client";

import { CornerDownLeft, Hash, LoaderCircle, RotateCcw } from "lucide-react";
import {
  type KeyboardEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/** The subset of the Kotlin/JS export (lib/kmpwright-demo) this component uses. */
type Demo = {
  run(command: string): Promise<{
    ok: boolean;
    output: string;
    durationMs: number;
    changedPage: boolean;
  }>;
  refs(): Promise<
    Array<{
      ref: string;
      role: string;
      name: string;
      x: number;
      y: number;
      width: number;
      height: number;
    }>
  >;
  dispose(): void;
};

type Entry = {
  id: number;
  command: string;
  state: "running" | "ok" | "error";
  output: string;
  ms: number;
};

type Ref = Awaited<ReturnType<Demo["refs"]>>[number];

const PLAYGROUND = "/demo/playground.html";

/** Next steps for the playground, filled in with the refs of the latest snapshot. */
function suggestions(refs: Ref[], ran: Set<string>): string[] {
  const find = (role: string, name?: RegExp) =>
    refs.find((r) => r.role === role && (!name || name.test(r.name)));
  const name = find("textbox", /name/i) ?? find("textbox");
  const terms = find("checkbox");
  const create = find("button", /create/i);
  const plus = find("button", /increment/i);
  const flow = [
    refs.length === 0 ? "snapshot -i" : null,
    name && `fill ${name.ref} "Ada Lovelace"`,
    terms && `check ${terms.ref}`,
    create && `click ${create.ref}`,
    "get text #signup-status",
    plus && `click ${plus.ref}`,
    "console",
    "get title",
  ].filter((command): command is string => !!command && !ran.has(command));
  return [...flow, "help"].slice(0, 4);
}

/**
 * KMPwright's Kotlin engine, compiled to JavaScript, driving a real page in an iframe. Commands go
 * through the same `page.runCommand()` the Android, iOS and JVM backends use.
 */
export function LiveDemo() {
  const frame = useRef<HTMLIFrameElement>(null);
  const log = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const demo = useRef<Demo | null>(null);
  const running = useRef(false);
  const nextId = useRef(0);
  const history = useRef<string[]>([]);
  const historyIndex = useRef(-1);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">(
    "loading",
  );
  const [entries, setEntries] = useState<Entry[]>([]);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState("");
  const [refs, setRefs] = useState<Ref[]>([]);
  const [showRefs, setShowRefs] = useState(true);
  const [scroll, setScroll] = useState({ x: 0, y: 0 });
  const [ran, setRan] = useState<Set<string>>(() => new Set());
  const [host, setHost] = useState("");
  const showRefsRef = useRef(showRefs);
  showRefsRef.current = showRefs;

  const refreshRefs = useCallback(async () => {
    const current = demo.current;
    if (!current || !showRefsRef.current) return;
    try {
      setRefs(Array.from(await current.refs()));
    } catch {
      setRefs([]);
    }
  }, []);

  const run = useCallback(
    async (command: string) => {
      const current = demo.current;
      const line = command.trim();
      if (!current || !line || running.current) return;
      if (line === "clear") {
        setEntries([]);
        return;
      }
      running.current = true;
      const id = nextId.current++;
      setEntries((list) => [
        ...list,
        { id, command: line, state: "running", output: "", ms: 0 },
      ]);
      setRan((set) => new Set(set).add(line));
      setBusy(true);
      try {
        const result = await current.run(line);
        setEntries((list) =>
          list.map((entry) =>
            entry.id === id
              ? {
                  ...entry,
                  state: result.ok ? "ok" : "error",
                  output: result.output,
                  ms: result.durationMs,
                }
              : entry,
          ),
        );
        if (result.changedPage || line.startsWith("snapshot")) {
          await refreshRefs();
        }
      } catch (error) {
        setEntries((list) =>
          list.map((entry) =>
            entry.id === id
              ? {
                  ...entry,
                  state: "error",
                  output:
                    error instanceof Error
                      ? error.message
                      : "Command failed. Try resetting the demo.",
                }
              : entry,
          ),
        );
      } finally {
        running.current = false;
        setBusy(false);
      }
    },
    [refreshRefs],
  );

  // Load the Kotlin/JS bundle, then open the playground the way an agent would.
  useEffect(() => {
    let cancelled = false;
    let created: Demo | null = null;
    import("@/lib/kmpwright-demo/kmpwright-demo.mjs")
      .then(async ({ KmpwrightDemo }) => {
        if (cancelled || !frame.current) return;
        setHost(window.location.host);
        created = new KmpwrightDemo(frame.current) as Demo;
        demo.current = created;
        setStatus("ready");
        await run(`open ${window.location.host}${PLAYGROUND}`);
        if (!cancelled) await run("snapshot -i");
      })
      .catch(() => {
        if (!cancelled) setStatus("failed");
      });
    return () => {
      cancelled = true;
      created?.dispose();
      demo.current = null;
    };
  }, [run]);

  // Follow the frame's scroll so the badges stay on their elements, and re-measure after the
  // visitor uses the page directly.
  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    let detach = () => {};
    let timer: ReturnType<typeof setTimeout> | undefined;
    const attach = () => {
      detach();
      let win: Window | null = null;
      try {
        win = element.contentWindow;
        void win?.document;
      } catch {
        return;
      }
      if (!win) return;
      const target = win;
      let frameRequest = 0;
      const onScroll = () => {
        cancelAnimationFrame(frameRequest);
        frameRequest = requestAnimationFrame(() =>
          setScroll({ x: target.scrollX, y: target.scrollY }),
        );
      };
      const onUse = () => {
        clearTimeout(timer);
        timer = setTimeout(refreshRefs, 350);
      };
      target.addEventListener("scroll", onScroll, { passive: true });
      target.addEventListener("resize", onUse);
      target.document.addEventListener("input", onUse, true);
      target.document.addEventListener("click", onUse, true);
      onScroll();
      detach = () => {
        target.removeEventListener("scroll", onScroll);
        target.removeEventListener("resize", onUse);
        target.document.removeEventListener("input", onUse, true);
        target.document.removeEventListener("click", onUse, true);
      };
    };
    element.addEventListener("load", attach);
    return () => {
      element.removeEventListener("load", attach);
      clearTimeout(timer);
      detach();
    };
  }, [refreshRefs]);

  // Keep the newest output in view, like a terminal.
  useLayoutEffect(() => {
    const element = log.current;
    if (element && entries.length) element.scrollTop = element.scrollHeight;
  }, [entries]);

  function submit() {
    if (busy || status !== "ready" || !draft.trim()) return;
    history.current.push(draft.trim());
    historyIndex.current = -1;
    void run(draft);
    setDraft("");
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") {
      event.preventDefault();
      submit();
      return;
    }
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
    const past = history.current;
    if (!past.length) return;
    event.preventDefault();
    const index =
      event.key === "ArrowUp"
        ? historyIndex.current < 0
          ? past.length - 1
          : Math.max(0, historyIndex.current - 1)
        : historyIndex.current < 0
          ? -1
          : historyIndex.current + 1;
    historyIndex.current = index >= past.length ? -1 : index;
    setDraft(historyIndex.current < 0 ? "" : past[historyIndex.current]);
  }

  /** Puts a ref into the command field: `click @e3`, or appended to a command being typed. */
  function insertRef(ref: string) {
    setDraft((text) =>
      text.trim() ? `${text.trimEnd()} ${ref}` : `click ${ref}`,
    );
    input.current?.focus();
  }

  async function reset() {
    setEntries([]);
    setRefs([]);
    setRan(new Set());
    await run(`open ${window.location.host}${PLAYGROUND}`);
    await run("snapshot -i");
  }

  async function toggleRefs() {
    const next = !showRefs;
    setShowRefs(next);
    showRefsRef.current = next;
    if (next) await refreshRefs();
    else setRefs([]);
  }

  const chips = suggestions(refs, ran);

  return (
    <div className="live-demo overflow-hidden rounded-2xl border bg-card">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 sm:px-5">
        <div className="flex items-center gap-3">
          <span className="relative flex size-2.5" aria-hidden>
            <span
              className={cn(
                "absolute inline-flex size-full rounded-full",
                status === "ready" && "bg-emerald-500/60",
              )}
            />
            <span
              className={cn(
                "relative inline-flex size-2.5 rounded-full",
                status === "ready"
                  ? "bg-emerald-500"
                  : status === "failed"
                    ? "bg-destructive"
                    : "bg-muted-foreground/40",
              )}
            />
          </span>
          <div>
            <p className="text-sm font-medium">
              Try it. This is KMPwright, live.
            </p>
            <p className="text-xs text-muted-foreground">
              The real Kotlin engine, compiled to JavaScript, driving the page
              below.
            </p>
          </div>
        </div>
        <Button
          variant="ghost"
          onClick={reset}
          disabled={busy || status !== "ready"}
          className="h-9 gap-2 px-3"
        >
          <RotateCcw aria-hidden />
          Reset
        </Button>
      </div>

      <div className="grid lg:grid-cols-[1fr_1fr]">
        {/* The page */}
        <div className="min-w-0 border-b lg:border-r lg:border-b-0">
          <div className="flex h-11 items-center justify-between gap-3 border-b px-4 text-xs text-muted-foreground">
            <span className="truncate font-mono">
              {host}
              {PLAYGROUND}
            </span>
            <button
              type="button"
              onClick={toggleRefs}
              aria-pressed={showRefs}
              className={cn(
                "inline-flex h-7 shrink-0 items-center gap-1 rounded-md px-2 font-medium transition-colors focus-visible:outline-2 focus-visible:outline-ring",
                showRefs
                  ? "bg-primary/10 text-primary"
                  : "hover:bg-muted hover:text-foreground",
              )}
            >
              <Hash aria-hidden className="size-3.5" />
              Refs
            </button>
          </div>
          <div className="relative h-[380px] overflow-hidden sm:h-[460px]">
            <iframe
              ref={frame}
              title="KMPwright playground page"
              className="block size-full [color-scheme:light] dark:[color-scheme:dark]"
            />
            <div
              aria-hidden={!showRefs}
              className="pointer-events-none absolute inset-0"
              style={{
                transform: `translate(${-scroll.x}px, ${-scroll.y}px)`,
              }}
            >
              {showRefs &&
                refs.map((ref) => (
                  <div
                    key={ref.ref}
                    className="live-demo-ref absolute rounded-md"
                    style={{
                      left: ref.x,
                      top: ref.y,
                      width: ref.width,
                      height: ref.height,
                    }}
                  >
                    <button
                      type="button"
                      tabIndex={-1}
                      title={`${ref.role} "${ref.name}" · click to use`}
                      onClick={() => insertRef(ref.ref)}
                      className="live-demo-badge pointer-events-auto absolute -top-2 -right-2 rounded-full px-1.5 font-mono text-[10px] leading-4 font-semibold"
                    >
                      {ref.ref}
                    </button>
                  </div>
                ))}
            </div>
          </div>
        </div>

        {/* The terminal */}
        <div className="flex h-[380px] min-w-0 flex-col sm:h-[504px]">
          <div
            ref={log}
            role="log"
            aria-live="polite"
            aria-label="Command output"
            className="min-h-0 flex-1 overflow-y-auto px-4 py-3 font-mono text-[12px] leading-5"
          >
            {status === "loading" && (
              <p className="flex items-center gap-2 text-muted-foreground">
                <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
                Loading KMPwright…
              </p>
            )}
            {status === "failed" && (
              <p className="text-destructive">
                The demo could not load. Try reloading the page.
              </p>
            )}
            {entries.map((entry) => (
              <div key={entry.id} className="mb-3">
                <div className="flex items-baseline gap-2">
                  <span className="text-primary select-none">❯</span>
                  <span className="min-w-0 flex-1 font-semibold break-all text-foreground">
                    {entry.command}
                  </span>
                  <span className="shrink-0 text-[11px] text-muted-foreground">
                    {entry.state === "running" ? (
                      <LoaderCircle
                        aria-label="Running"
                        className="size-3 animate-spin"
                      />
                    ) : (
                      `${entry.ms} ms`
                    )}
                  </span>
                </div>
                {entry.output && (
                  <pre
                    className={cn(
                      "mt-1 overflow-x-auto pl-4 whitespace-pre-wrap break-words",
                      entry.state === "error"
                        ? "text-destructive"
                        : "text-muted-foreground",
                    )}
                  >
                    {linkRefs(entry.output, insertRef)}
                  </pre>
                )}
              </div>
            ))}
          </div>

          <div className="border-t px-3 pt-2.5 pb-3">
            <div className="mb-2 flex gap-1.5 overflow-x-auto pb-0.5">
              {chips.map((chip) => (
                <button
                  key={chip}
                  type="button"
                  disabled={busy || status !== "ready"}
                  onClick={() => void run(chip)}
                  className="h-7 shrink-0 rounded-md border bg-background px-2 font-mono text-[11px] text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring disabled:opacity-50"
                >
                  {chip}
                </button>
              ))}
            </div>
            <div className="flex items-center gap-2 rounded-lg border bg-background px-3 focus-within:border-primary/50 focus-within:ring-3 focus-within:ring-primary/15">
              <span className="font-mono text-sm text-primary select-none">
                ❯
              </span>
              <input
                ref={input}
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                onKeyDown={onKeyDown}
                disabled={status !== "ready"}
                aria-label="Command"
                placeholder={
                  status === "ready"
                    ? "Type a command, or tap a chip or an @e badge"
                    : "Loading…"
                }
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                className="h-10 min-w-0 flex-1 bg-transparent font-mono text-base sm:text-[13px] outline-none placeholder:font-sans placeholder:text-muted-foreground/70"
              />
              <button
                type="button"
                onClick={submit}
                disabled={busy || status !== "ready" || !draft.trim()}
                aria-label="Run command"
                className="-mr-1.5 grid size-8 place-items-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-40"
              >
                <CornerDownLeft aria-hidden className="size-4" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** Makes every `@eN` in command output a button that puts the ref into the command field. */
function linkRefs(text: string, use: (ref: string) => void): ReactNode[] {
  return text.split(/(@e\d+)/g).map((part, index) =>
    /^@e\d+$/.test(part) ? (
      <button
        // biome-ignore lint/suspicious/noArrayIndexKey: parts of one immutable string
        key={index}
        type="button"
        onClick={() => use(part)}
        className="rounded text-primary underline decoration-primary/30 underline-offset-2 hover:decoration-primary"
      >
        {part}
      </button>
    ) : (
      part
    ),
  );
}
