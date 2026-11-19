#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/upstream/mihomo/adapter/outbound/onetunnel_tailscale.go"
test -f "$root/upstream/mihomo/adapter/outbound/tailscale.go" || { echo 'Fetch the pinned Mihomo core first'; exit 1; }
cp "$root/native/overlay/onetunnel_tailscale.go" "$target"
cp "$root/native/overlay/onetunnel_tailscale_test.go" "${target%.go}_test.go"
cp "$root/native/overlay/onetunnel_tailscale_forward.go" "$root/upstream/mihomo/adapter/outbound/onetunnel_tailscale_forward.go"
cp "$root/native/overlay/onetunnel_tailscale_forward_test.go" "$root/upstream/mihomo/adapter/outbound/onetunnel_tailscale_forward_test.go"
for patch_name in android-vpnservice.patch tailscale-inbound.patch; do
patch="$root/native/overlay/$patch_name"
if ! git -C "$root/upstream/mihomo" apply --unidiff-zero --reverse --check "$patch" 2>/dev/null; then
  git -C "$root/upstream/mihomo" apply --unidiff-zero --check "$patch"
  git -C "$root/upstream/mihomo" apply --unidiff-zero "$patch"
fi
done
echo 'Prepared narrow OneTunnel Tailscale bridge overlay'
