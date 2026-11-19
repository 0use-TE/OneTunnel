#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=clang
export GOTOOLCHAIN=local GOMAXPROCS=2 GOMEMLIMIT=1GiB GOGC=60
export GOPATH="$HOME/dsh-android/go" GOCACHE="$HOME/dsh-android/go-cache"
export GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}"
export CGO_CFLAGS="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
export CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
mkdir -p "$root/client/app/src/main/jniLibs/arm64-v8a"
bash "$root/scripts/prepare-core.sh"
cd "$root/native"
go build -mod=mod -buildvcs=false -p 1 -trimpath \
  -tags=with_gvisor,onetunnel,no_easytier,no_zerotier \
  -buildmode=c-shared -ldflags='-s -w' \
  -o "$root/client/app/src/main/jniLibs/arm64-v8a/libonetunnel.so" .
# Termux Clang adds a host-private RUNPATH by default. Remove only that metadata.
go run -p 1 ./tools/elfclean "$root/client/app/src/main/jniLibs/arm64-v8a/libonetunnel.so"
llvm-readelf -h "$root/client/app/src/main/jniLibs/arm64-v8a/libonetunnel.so"
llvm-readelf -d "$root/client/app/src/main/jniLibs/arm64-v8a/libonetunnel.so"
