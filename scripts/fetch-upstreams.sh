#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
commit=0455d0d28c4526937380505153e65a843d8612e8
mkdir -p "$root/upstream"
dir="$root/upstream/mihomo"
if [ ! -d "$dir" ]; then
  git clone --depth 1 --single-branch --branch Meta --no-tags https://github.com/MetaCubeX/mihomo.git "$dir"
fi
# A source-release archive can already contain the pinned tree without .git.
if [ ! -d "$dir/.git" ]; then
  test -f "$dir/go.mod" || { echo 'Missing pinned Mihomo source'; exit 1; }
  echo 'Using source archive tree; verify the archive checksum and UPSTREAMS.md.'
  exit 0
fi
if [ "$(git -C "$dir" rev-parse HEAD)" != "$commit" ]; then
  test -z "$(git -C "$dir" status --porcelain)" || { echo 'Upstream has local edits; refusing checkout.'; exit 1; }
  git -C "$dir" fetch --depth 1 origin "$commit"
  git -C "$dir" switch --detach "$commit"
fi
test "$(git -C "$dir" rev-parse HEAD)" = "$commit"
echo "Pinned Mihomo: $commit"
