#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source "$HOME/dsh-android/env.sh"
cd "$root/client"
args=(--no-daemon --console=plain --stacktrace
  -Dorg.gradle.native=false
  -Dorg.gradle.vfs.watch=false
  -Dorg.gradle.internal.instrumentation.agent=false
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2")
# Leave the phone's existing VPN and proxy configuration untouched.
# Optional per-build proxy: BUILD_PROXY=127.0.0.1:7890 bash scripts/build-client-termux.sh
if [ -n "${BUILD_PROXY:-}" ]; then
  host="${BUILD_PROXY%:*}"; port="${BUILD_PROXY##*:}"
  args+=(-Dhttp.proxyHost="$host" -Dhttp.proxyPort="$port" -Dhttps.proxyHost="$host" -Dhttps.proxyPort="$port" '-Dhttp.nonProxyHosts=localhost|127.*')
fi
task="${1:-assembleDebug}"
if [ "$task" = assembleDebug ]; then
  test -s app/src/main/jniLibs/arm64-v8a/libonetunnel.so || { echo 'Native core missing: build-native-termux.sh must succeed first.'; exit 1; }
fi
exec gradle "${args[@]}" "$task"
