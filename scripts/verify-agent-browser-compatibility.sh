#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$project_dir/docs/compatibility/agent-browser-compatibility.json"

if [[ ! -f "$manifest" ]]; then
  echo "missing compatibility manifest: $manifest" >&2
  exit 1
fi

# No upstream checkout is required to build or verify Kmpwright.
if [[ -n "$(git -C "$project_dir" ls-files -- agent-browser .upstream)" ]]; then
  echo "upstream source must remain untracked" >&2
  exit 1
fi

python3 - "$manifest" "$project_dir" <<'PY'
import json
import re
import sys

manifest_path = sys.argv[1]
with open(manifest_path, encoding="utf-8") as stream:
    manifest = json.load(stream)

assert manifest.get("manifestVersion") == 1, "manifestVersion must be 1"
upstream = manifest.get("upstream", {})
assert upstream.get("repository") == "https://github.com/vercel-labs/agent-browser"
for field in ("baselineCommit", "comparedCommit"):
    assert re.fullmatch(r"[0-9a-f]{40}", upstream.get(field, "")), f"{field} must be a full SHA-1"
assert upstream.get("workingTreeIsIgnored") is True
assert upstream.get("publicSources") == ["README.md", "agent-browser.schema.json"]

from pathlib import Path
project = Path(sys.argv[2]).resolve()
inventory_path = project / upstream["changeInventory"]
inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
assert inventory["baselineCommit"] == upstream["baselineCommit"]
assert inventory["comparedCommit"] == upstream["comparedCommit"]
changes = inventory["changes"]
assert len(changes) == inventory["commitCount"] and len(changes) > 0
assert len({item["commit"] for item in changes}) == len(changes), "duplicate inventory commits"
for item in changes:
    assert re.fullmatch(r"[0-9a-f]{40}", item["commit"])
    assert item["disposition"] and item["notes"] and item["subject"]
review_path = project / upstream["review"]
assert review_path.is_file(), "missing upstream review"
review = review_path.read_text(encoding="utf-8")
for item in changes:
    sha = item["commit"]
    expected = "| [{}](https://github.com/vercel-labs/agent-browser/commit/{}) | {} | {} | {} |".format(
        sha[:7], sha, *(item[field].replace("|", "\\|").replace("\n", " ") for field in ("subject", "disposition", "notes")))
    assert review.count(expected) == 1, f"review row must match inventory exactly: {sha}"

valid_statuses = {"supported", "partial", "unsupported"}
for collection_name in ("commands", "globalOptions"):
    collection = manifest.get(collection_name)
    assert isinstance(collection, list) and collection, f"{collection_name} must be non-empty"
    names = set()
    for entry in collection:
        key = entry.get("command", entry.get("option"))
        assert isinstance(key, str) and key.strip(), f"{collection_name} entry needs a name"
        assert key not in names, f"duplicate {collection_name} entry: {key}"
        names.add(key)
        assert entry.get("status") in valid_statuses, f"invalid status for {key}"
        if entry["status"] != "unsupported":
            assert entry.get("apiRefs"), f"supported/partial entry has no API refs: {key}"
            for ref in entry["apiRefs"]:
                assert isinstance(ref.get("owner"), str) and isinstance(ref.get("member"), str), f"bad API ref in {key}"
        for option in entry.get("options", []):
            assert isinstance(option.get("name"), str) and option["name"].strip(), f"bad option in {key}"
            assert option.get("status") in valid_statuses, f"invalid option status in {key}"
            if option["status"] != "unsupported":
                assert option.get("apiRefs"), f"supported/partial option has no API refs: {key}/{option['name']}"
                for ref in option["apiRefs"]:
                    assert isinstance(ref.get("owner"), str) and isinstance(ref.get("member"), str), f"bad option API ref in {key}"

verification = manifest.get("verification", {})
assert verification.get("boundaryOnly") is True
assert verification.get("upstreamInternalsRequired") is False
print(f"agent-browser compatibility manifest OK (compared {upstream['comparedCommit']})")
PY
