#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
fixture_root="$repo_root/.upstream/react-fixture"
mkdir -p "$fixture_root"
if [[ ! -f "$fixture_root/package.json" ]]; then
  printf '%s\n' '{"name":"kmpwright-react-regression-fixture","private":true,"version":"1.0.0"}' > "$fixture_root/package.json"
fi
npm --prefix "$fixture_root" install --ignore-scripts --no-audit --no-fund --save-exact react@19.3.0 react-dom@19.3.0 esbuild@0.28.2
cp "$repo_root/tests/fixtures/react-app.js" "$fixture_root/app.js"
"$fixture_root/node_modules/.bin/esbuild" "$fixture_root/app.js" --bundle --format=iife --platform=browser --target=chrome120 --define:process.env.NODE_ENV='"development"' --outfile="$fixture_root/app.bundle.js"
printf 'React fixture: %s\n' "$fixture_root/app.bundle.js"
