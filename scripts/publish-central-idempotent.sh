#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set by validate-release-tag.sh}"
CENTRAL_BASE_URL="https://repo.maven.apache.org/maven2/io/github/bernaferrari"

central_pom_status() {
  local artifact="$1"
  curl --silent --show-error --location --retry 3 --retry-all-errors \
    --connect-timeout 10 --max-time 30 --output /dev/null \
    --write-out '%{http_code}' \
    "$CENTRAL_BASE_URL/$artifact/$VERSION/$artifact-$VERSION.pom"
}

wait_for_public_pom() {
  local artifact="$1"
  local attempt status
  for attempt in {1..18}; do
    status="$(central_pom_status "$artifact")"
    if [[ "$status" == "200" ]]; then
      echo "$artifact:$VERSION is discoverable on Maven Central"
      return 0
    fi
    if [[ "$status" != "404" ]]; then
      echo "Unexpected Maven Central response while checking $artifact:$VERSION: HTTP $status" >&2
      return 1
    fi
    sleep 10
  done
  echo "Maven Central did not expose $artifact:$VERSION after deployment" >&2
  return 1
}

publish_if_missing() {
  local artifact="$1"
  local status
  status="$(central_pom_status "$artifact")"
  case "$status" in
    200)
      echo "$artifact:$VERSION already exists on Maven Central; skipping immutable deployment"
      ;;
    404)
      echo "Publishing complete $artifact deployment for $VERSION"
      "$ROOT_DIR/gradlew" ":$artifact:publishAndReleaseToMavenCentral" \
        -PreleaseVersion="$VERSION" -PreleaseSigningRequired=true \
        --no-daemon --no-configuration-cache
      # The Gradle plugin is configured with DeploymentValidation.PUBLISHED;
      # this second check makes retry behavior explicit to the workflow.
      wait_for_public_pom "$artifact"
      ;;
    *)
      echo "Refusing to publish $artifact:$VERSION: Maven Central returned HTTP $status" >&2
      return 1
      ;;
  esac
}

# Keep core (all KMP target publications) and Compose as separate sequential
# deployments so a rerun can skip whichever immutable module already landed.
publish_if_missing kmpwright-core
publish_if_missing kmpwright-compose
