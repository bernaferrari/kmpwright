import type { BaseLayoutProps } from "fumadocs-ui/layouts/shared";
import { appName, gitConfig } from "./shared";

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      // JSX supported
      title: (
        <span className="inline-flex items-center gap-2 font-semibold">
          <span
            aria-hidden
            className="inline-grid size-6 place-items-center rounded-lg bg-fd-primary text-xs font-black text-fd-primary-foreground"
          >
            K
          </span>
          {appName}
        </span>
      ),
    },
    links: [
      { text: "Docs", url: "/docs", active: "nested-url" },
      { text: "Agents", url: "/docs/agents", active: "nested-url" },
      { text: "llms.txt", url: "/llms.txt", external: true },
    ],
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
  };
}
