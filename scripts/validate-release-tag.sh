#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-${GITHUB_REF_NAME:-}}"
if [[ ! "$TAG" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Release tags must match vMAJOR.MINOR.PATCH (received: ${TAG:-<empty>})" >&2
  exit 1
fi

VERSION="${TAG#v}"
echo "Release tag $TAG validated; releaseVersion=$VERSION"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'RELEASE_VERSION=%s\n' "$VERSION" >> "$GITHUB_ENV"
fi
