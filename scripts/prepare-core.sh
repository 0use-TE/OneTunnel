#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/upstream/mihomo/adapter/outbound/onetunnel_tailscale.go"
test -f "$root/upstream/mihomo/adapter/outbound/tailscale.go" || { echo 'Fetch the pinned Mihomo core first'; exit 1; }
all_port_patch="$root/native/overlay/all-port-inbound.patch"
if git -C "$root/upstream/mihomo" apply --unidiff-zero --reverse --check "$all_port_patch" 2>/dev/null; then
  git -C "$root/upstream/mihomo" apply --unidiff-zero --reverse "$all_port_patch"
fi
cp "$root/native/overlay/onetunnel_tailscale.go" "$target"
cp "$root/native/overlay/onetunnel_tailscale_test.go" "${target%.go}_test.go"
rm -f "$root/upstream/mihomo/adapter/outbound/onetunnel_tailscale_forward.go" "$root/upstream/mihomo/adapter/outbound/onetunnel_tailscale_forward_test.go"
for overlay in onetunnel_forward.go onetunnel_forward_test.go onetunnel_forward_all.go onetunnel_forward_all_test.go onetunnel_packet.go onetunnel_wireguard.go onetunnel_wireguard_test.go onetunnel_tailscale_all_test.go onetunnel_inbound_parser_test.go; do
  cp "$root/native/overlay/$overlay" "$root/upstream/mihomo/adapter/outbound/$overlay"
done
for patch_name in android-vpnservice.patch tailscale-inbound.patch wireguard-inbound.patch all-port-inbound.patch; do
patch="$root/native/overlay/$patch_name"
if ! git -C "$root/upstream/mihomo" apply --unidiff-zero --reverse --check "$patch" 2>/dev/null; then
  git -C "$root/upstream/mihomo" apply --unidiff-zero --check "$patch"
  git -C "$root/upstream/mihomo" apply --unidiff-zero "$patch"
fi
done
echo 'Prepared OneTunnel tunnel control and TCP/UDP forwarding overlays'
