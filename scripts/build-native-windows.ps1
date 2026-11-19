param(
    [Parameter(Mandatory=$true)][string]$NdkRoot,
    [string]$GoExe = 'go'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolchain = Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64'
if (-not (Test-Path -LiteralPath (Join-Path $toolchain 'bin/clang.exe'))) { $toolchain = $NdkRoot }
$clang = (Resolve-Path -LiteralPath (Join-Path $toolchain 'bin/clang.exe')).Path.Replace('\','/')
$sysroot = (Resolve-Path -LiteralPath (Join-Path $toolchain 'sysroot')).Path.Replace('\','/')
$upstream = Join-Path $projectRoot 'upstream/mihomo'
if (-not (Test-Path -LiteralPath (Join-Path $upstream 'adapter/outbound/tailscale.go'))) { throw 'Fetch the pinned Mihomo source first.' }
Copy-Item -LiteralPath (Join-Path $projectRoot 'native/overlay/onetunnel_tailscale.go') -Destination (Join-Path $upstream 'adapter/outbound/onetunnel_tailscale.go')
Copy-Item -LiteralPath (Join-Path $projectRoot 'native/overlay/onetunnel_tailscale_test.go') -Destination (Join-Path $upstream 'adapter/outbound/onetunnel_tailscale_test.go')
Copy-Item -LiteralPath (Join-Path $projectRoot 'native/overlay/onetunnel_tailscale_forward.go') -Destination (Join-Path $upstream 'adapter/outbound/onetunnel_tailscale_forward.go')
Copy-Item -LiteralPath (Join-Path $projectRoot 'native/overlay/onetunnel_tailscale_forward_test.go') -Destination (Join-Path $upstream 'adapter/outbound/onetunnel_tailscale_forward_test.go')
foreach ($patchName in @('android-vpnservice.patch', 'tailscale-inbound.patch')) {
$patch = Join-Path $projectRoot ('native/overlay/' + $patchName)
& git -C $upstream apply --unidiff-zero --reverse --check $patch 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $upstream apply --unidiff-zero --check $patch
    if ($LASTEXITCODE -ne 0) { throw 'Pinned Android source does not match the OneTunnel patch.' }
    & git -C $upstream apply --unidiff-zero $patch
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply Android VpnService integration patch.' }
}
}
$output = Join-Path $projectRoot 'client/app/src/main/jniLibs/arm64-v8a/libonetunnel.so'
New-Item -ItemType Directory -Force -Path (Split-Path $output) | Out-Null
$variables = @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS','GOMAXPROCS','GOMEMLIMIT','GOTOOLCHAIN')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='1'; $env:GOTOOLCHAIN='local'
    $env:CC='"' + $clang + '" --target=aarch64-linux-android26 "--sysroot=' + $sysroot + '"'
    $env:CGO_CFLAGS=''
    $env:CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    $env:GOMAXPROCS='4'; $env:GOMEMLIMIT='2GiB'
    & $GoExe -C (Join-Path $projectRoot 'native') build -mod=mod -buildvcs=false -p 2 -trimpath '-tags=with_gvisor,onetunnel,no_easytier,no_zerotier' -buildmode=c-shared '-ldflags=-s -w' -o $output .
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed.' }
    Write-Output "Built $output"
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
