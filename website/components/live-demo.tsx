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
function suggestions(refs: Ref[]): string[] {
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
  ].filter((command): command is string => !!command);
  return [...flow, "help"];
}

/**
 * KMPwright's Kotlin engine, compiled to JavaScript, driving a real page in an iframe. Commands go
 * through the same `page.runCommand()` the Android, iOS and JVM backends use.
 */
export function LiveDemo() {
  const frame = useRef<HTMLIFrameElement>(null);
  const log = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const layer = useRef<RefLayer | null>(null);
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
  const [host, setHost] = useState("");
  const showRefsRef = useRef(showRefs);
  showRefsRef.current = showRefs;
  const refsRef = useRef(refs);
  refsRef.current = refs;
  const pickRef = useRef<(ref: string) => void>(() => {});

  const refreshRefs = useCallback(async () => {
    const current = demo.current;
    if (!current) return;
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
      setEntries((list) =>
        [
          ...list,
          { id, command: line, state: "running" as const, output: "", ms: 0 },
        ].slice(-100),
      );
      setBusy(true);
      try {
        const result = await current.run(line);
        setEntries((list) =>
          list.map((entry) =>
            entry.id === id
              ? {
                  ...entry,
                  state: result.ok ? "ok" : "error",
                  output: result.output.slice(0, 100_000),
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

  // Draw the ref badges inside the page itself, so they scroll with it on the compositor, with no
  // lag on touch, wheel or trackpad. Re-measure after the visitor uses the page directly.
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
      const refLayer = mountRefLayer(target.document, (ref) =>
        pickRef.current(ref),
      );
      layer.current = refLayer;
      refLayer.paint(showRefsRef.current ? refsRef.current : []);
      const onUse = () => {
        clearTimeout(timer);
        timer = setTimeout(refreshRefs, 350);
      };
      target.addEventListener("resize", onUse);
      target.document.addEventListener("input", onUse, true);
      target.document.addEventListener("click", onUse, true);
      detach = () => {
        refLayer.dispose();
        if (layer.current === refLayer) layer.current = null;
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

  useEffect(() => {
    layer.current?.paint(showRefs ? refs : []);
  }, [refs, showRefs]);

  // Keep the newest output in view, like a terminal.
  useLayoutEffect(() => {
    const element = log.current;
    if (element && entries.length) element.scrollTop = element.scrollHeight;
  }, [entries]);

  function submit() {
    if (busy || status !== "ready" || !draft.trim()) return;
    history.current.push(draft.trim());
    if (history.current.length > 100) history.current.shift();
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

  pickRef.current = insertRef;

  async function reset() {
    setEntries([]);
    setRefs([]);
    await run(`open ${window.location.host}${PLAYGROUND}`);
    await run("snapshot -i");
  }

  async function toggleRefs() {
    const next = !showRefs;
    setShowRefs(next);
    showRefsRef.current = next;
    if (next) await refreshRefs();
  }

  const chips = suggestions(refs);

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
          className="h-11 gap-2 px-3"
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
                "inline-flex h-11 shrink-0 items-center gap-1 rounded-md px-2 font-medium transition-colors focus-visible:outline-2 focus-visible:outline-ring",
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
                  className="h-11 shrink-0 rounded-md border bg-background px-2 font-mono text-[11px] text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring disabled:opacity-50"
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
                className="h-11 min-w-0 flex-1 bg-transparent font-mono text-base sm:text-[13px] outline-none placeholder:font-sans placeholder:text-muted-foreground/70"
              />
              <button
                type="button"
                onClick={submit}
                disabled={busy || status !== "ready" || !draft.trim()}
                aria-label="Run command"
                className="-mr-1.5 grid size-11 place-items-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-40"
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

type RefLayer = { paint(refs: Ref[]): void; dispose(): void };

const REF_LAYER_CSS = `
  :host { all: initial; }
  .box {
    position: absolute;
    box-sizing: border-box;
    border-radius: 6px;
    outline: 1.5px solid color-mix(in oklch, var(--kw-primary) 55%, transparent);
    outline-offset: 1px;
    background: color-mix(in oklch, var(--kw-primary) 6%, transparent);
    transition: background-color 120ms ease;
  }
  .box.hover { background: color-mix(in oklch, var(--kw-primary) 18%, transparent); }
  .badge {
    position: absolute;
    top: -8px;
    right: -8px;
    padding: 0 6px;
    border-radius: 999px;
    background: var(--kw-primary);
    color: var(--kw-primary-foreground);
    box-shadow: 0 1px 3px rgb(0 0 0 / 25%);
    font: 600 10px/16px ui-monospace, SFMono-Regular, Menlo, monospace;
    white-space: nowrap;
  }
`;

/**
 * Draws ref badges inside the automated page, in a closed shadow root under an aria-hidden host with
 * pointer-events: none. Living in the page, the badges scroll with it natively. The engine cannot see
 * them: snapshots skip aria-hidden hosts and cannot enter closed shadow roots, and hit-testing skips
 * pointer-events: none, so automated clicks still land on the page. Real clicks on a badge are caught
 * by position and turned into `onPick(ref)`.
 */
function mountRefLayer(doc: Document, onPick: (ref: string) => void): RefLayer {
  const host = doc.createElement("kmpwright-refs");
  host.setAttribute("aria-hidden", "true");
  host.style.cssText =
    "position:absolute;top:0;left:0;width:0;height:0;overflow:visible;pointer-events:none;z-index:2147483647;display:block;contain:layout style;";
  const root = host.attachShadow({ mode: "closed" });
  const style = doc.createElement("style");
  style.textContent = REF_LAYER_CSS;
  const list = doc.createElement("div");
  root.append(style, list);
  doc.documentElement.appendChild(host);

  let badges: Array<{ ref: string; badge: HTMLElement; box: HTMLElement }> = [];
  let hovered: HTMLElement | null = null;

  const hit = (event: MouseEvent) => {
    if (!event.isTrusted) return null;
    return (
      badges.find(({ badge }) => {
        const r = badge.getBoundingClientRect();
        return (
          event.clientX >= r.left - 2 &&
          event.clientX <= r.right + 2 &&
          event.clientY >= r.top - 2 &&
          event.clientY <= r.bottom + 2
        );
      }) ?? null
    );
  };
  const swallow = (event: Event) => {
    event.preventDefault();
    event.stopImmediatePropagation();
  };
  const onDown = (event: PointerEvent) => {
    if (hit(event)) swallow(event);
  };
  const onMouseDown = (event: MouseEvent) => {
    if (hit(event)) swallow(event);
  };
  const onClick = (event: MouseEvent) => {
    const target = hit(event);
    if (!target) return;
    swallow(event);
    onPick(target.ref);
  };
  const onMove = (event: PointerEvent) => {
    const box = hit(event)?.box ?? null;
    if (box === hovered) return;
    hovered?.classList.remove("hover");
    box?.classList.add("hover");
    hovered = box;
  };
  doc.addEventListener("pointerdown", onDown, true);
  doc.addEventListener("mousedown", onMouseDown, true);
  doc.addEventListener("click", onClick, true);
  doc.addEventListener("pointermove", onMove, { capture: true, passive: true });

  return {
    paint(refs) {
      const theme = getComputedStyle(document.documentElement);
      host.style.setProperty(
        "--kw-primary",
        theme.getPropertyValue("--primary").trim() || "#5b4fc4",
      );
      host.style.setProperty(
        "--kw-primary-foreground",
        theme.getPropertyValue("--primary-foreground").trim() || "#fff",
      );
      hovered = null;
      badges = refs.map((ref) => {
        const box = doc.createElement("div");
        box.className = "box";
        box.style.cssText = `left:${ref.x}px;top:${ref.y}px;width:${ref.width}px;height:${ref.height}px;`;
        const badge = doc.createElement("span");
        badge.className = "badge";
        badge.textContent = ref.ref;
        box.append(badge);
        return { ref: ref.ref, badge, box };
      });
      list.replaceChildren(...badges.map(({ box }) => box));
    },
    dispose() {
      doc.removeEventListener("pointerdown", onDown, true);
      doc.removeEventListener("mousedown", onMouseDown, true);
      doc.removeEventListener("click", onClick, true);
      doc.removeEventListener("pointermove", onMove, true);
      host.remove();
    },
  };
}
