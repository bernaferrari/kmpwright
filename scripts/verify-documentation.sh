#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

required_files=(README.md LICENSE docs/releasing.md example-ios/README.md)
for file in "${required_files[@]}"; do
  [[ -f "$file" ]] || { echo "Missing documentation file: $file" >&2; exit 1; }
done

# Private configuration and generated demo runtime must stay out of Git.
if [[ -n "$(git ls-files -- .claude website/lib/kmpwright-demo)" ]]; then
  echo "Local settings or generated demo output are tracked" >&2
  exit 1
fi

# Public onboarding must not revive known pre-release contradictions.
if rg -n '<repository-url>|composite build|local snapshot|root `LICENSE` file has not' README.md; then
  echo "Stale public documentation claim found" >&2
  exit 1
fi
rg -q 'io\.github\.bernaferrari:kmpwright-core:0\.0\.2' README.md
rg -q 'io\.github\.bernaferrari:kmpwright-compose:0\.0\.2' README.md
rg -q 'Maven Central' README.md
rg -q 'Maven Central' docs/releasing.md
rg -q 'Do Not Embed' docs/releasing.md example-ios/README.md
rg -q 'capabilities\(\)' README.md
rg -q 'PageCapabilities' kmpwright-core/src/commonMain
for selector in 'role=' 'label=' 'text=' 'text:' 'exact_text=' 'placeholder=' 'alt=' 'title=' 'testid=' 'xpath='; do
  rg -q "$selector" website/content/docs/guides/locators.mdx || { echo "Missing documented selector: $selector" >&2; exit 1; }
done
# Every documented selector engine is implemented by the in-page runtime and covered by tests.
for engine in role label text exact_text placeholder alt title testid xpath; do
  rg -q "case '$engine'" kmpwright-core/src/commonMain/kotlin/com/bernaferrari/kmpwright/core/PageHelpers.kt || { echo "Selector engine not implemented: $engine" >&2; exit 1; }
done
rg -q 'role=button\[name=' kmpwright-core/src/jvmTest/kotlin/com/bernaferrari/kmpwright/core/SelectorEngineBrowserTest.kt
rg -q 'getByTestId|getByAltText|getByTitle' kmpwright-core/src/jvmTest/kotlin/com/bernaferrari/kmpwright/core/ConvenienceDslTest.kt
# Playwright-shaped finders, assertions and lifecycle stay covered by real-browser contracts.
rg -q 'getByRole|toHaveURL|waitForURL' kmpwright-core/src/jvmTest/kotlin/com/bernaferrari/kmpwright/core/PlaywrightApiBrowserTest.kt

# Every relative Markdown link in the maintained docs must resolve locally.
for doc in README.md THIRD_PARTY_NOTICES.md docs/*.md docs/compatibility/*.md example-ios/README.md; do
  while IFS= read -r target; do
    case "$target" in
      http://*|https://*|mailto:*|'') continue ;;
    esac
    target="${target%%#*}"
    doc_dir="$(dirname "$doc")"
    [[ -e "$doc_dir/$target" ]] || {
      echo "Broken link in $doc: $target" >&2
      exit 1
    }
  done < <(rg -o '\]\([^)]*\)' "$doc" | sed -E 's/^\]\(([^)]*)\)$/\1/')
done

# Confirm documented release commands and their implementation entry points.
for script in validate-release-tag.sh verify-publication.sh verify-ios-xcframework.sh publish-central-idempotent.sh; do
  [[ -x "scripts/$script" ]] || { echo "Release script is not executable: $script" >&2; exit 1; }
done
rg -q 'validate-release-tag\.sh|verify-publication\.sh|verify-ios-xcframework\.sh' docs/releasing.md
rg -q 'publish-central-idempotent\.sh' .github/workflows/release.yml

echo "Documentation links, release identity, stale-claim, and API references verified."
