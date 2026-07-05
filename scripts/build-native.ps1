param(
    [string[]] $Abi = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
    [string] $AndroidApi = "31",
    [string] $NdkVersion = "29.0.14206865",
    [string] $HevRepoUrl = "https://github.com/heiher/hev-socks5-tunnel.git",
    [string] $HevCommit = "67dfba56c0a9254f401447e0751432bd36617d70",
    [switch] $Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$androidRoot = Join-Path $repoRoot "android"
$appMain = Join-Path $androidRoot "app\src\main"
$sourceRoot = Join-Path $repoRoot "build\native\hev-socks5-tunnel"
$jniSource = Join-Path $appMain "cpp\tun2socks_jni.c"
$jniLibsRoot = Join-Path $appMain "jniLibs"
$supportedAbis = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

function Write-Step([string] $Message) {
    Write-Host "[native] $Message"
}

function Get-AndroidSdkPath {
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) {
        return (Resolve-Path -LiteralPath $env:ANDROID_HOME).Path
    }

    if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) {
        return (Resolve-Path -LiteralPath $env:ANDROID_SDK_ROOT).Path
    }

    $localProperties = Join-Path $androidRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        foreach ($line in Get-Content -LiteralPath $localProperties) {
            if ($line -match "^sdk\.dir=(.+)$") {
                $sdkDir = $Matches[1].Replace("\\", "\")
                if (Test-Path -LiteralPath $sdkDir) {
                    return (Resolve-Path -LiteralPath $sdkDir).Path
                }
            }
        }
    }

    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path -LiteralPath $defaultSdk) {
        return (Resolve-Path -LiteralPath $defaultSdk).Path
    }

    throw "Android SDK not found. Set ANDROID_HOME, ANDROID_SDK_ROOT, or android/local.properties sdk.dir."
}

function Get-ShortPath([string] $Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $short = & cmd.exe /c "for %I in (`"$resolved`") do @echo %~sI"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($short)) {
        return $resolved
    }
    return $short.Trim()
}

function Invoke-Git([string[]] $Arguments) {
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Invoke-CmdChecked([string] $CommandLine) {
    & cmd.exe /c $CommandLine
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $CommandLine"
    }
}

function Get-ClangName([string] $TargetAbi) {
    switch ($TargetAbi) {
        "arm64-v8a" { return "aarch64-linux-android$AndroidApi-clang.cmd" }
        "armeabi-v7a" { return "armv7a-linux-androideabi$AndroidApi-clang.cmd" }
        "x86" { return "i686-linux-android$AndroidApi-clang.cmd" }
        "x86_64" { return "x86_64-linux-android$AndroidApi-clang.cmd" }
        default { throw "Unsupported ABI: $TargetAbi" }
    }
}

function Ensure-HevSource {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sourceRoot) | Out-Null

    if (-not (Test-Path -LiteralPath $sourceRoot)) {
        Write-Step "cloning hev-socks5-tunnel $HevCommit"
        Invoke-Git @("clone", "--recursive", $HevRepoUrl, $sourceRoot)
    }

    Write-Step "checking out hev-socks5-tunnel $HevCommit"
    Invoke-Git @("-c", "safe.directory=$sourceRoot", "-C", $sourceRoot, "fetch", "origin")
    Invoke-Git @("-c", "safe.directory=$sourceRoot", "-C", $sourceRoot, "checkout", "--force", $HevCommit)
    Invoke-Git @("-c", "safe.directory=$sourceRoot", "-C", $sourceRoot, "submodule", "update", "--init", "--recursive", "--force")

    Set-Content -LiteralPath (Join-Path $sourceRoot ".rev-id") -Value $HevCommit.Substring(0, 12) -NoNewline
}

function Patch-HevSourceForLibraryBuild {
    $buildMk = Join-Path $sourceRoot "build.mk"
    $lines = Get-Content -LiteralPath $buildMk
    $patched = foreach ($line in $lines) {
        if ($line -match "^SRCFILES=.*rwildcard.*\*\.c \*\.S") {
            'SRCFILES=$(filter-out $(SRCDIR)/hev-jni.c,$(call rwildcard,$(SRCDIR)/,*.c *.S))'
        } else {
            $line
        }
    }
    Set-Content -LiteralPath $buildMk -Value $patched

    Get-ChildItem -LiteralPath $sourceRoot -Recurse -File |
        Where-Object { $_.Length -gt 0 -and $_.Length -lt 256 } |
        ForEach-Object {
            $content = (Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue).Trim()
            if ($content -match "^[A-Za-z0-9_./-]+$") {
                $target = Join-Path $_.DirectoryName $content
                if (Test-Path -LiteralPath $target -PathType Leaf) {
                    Copy-Item -LiteralPath $target -Destination $_.FullName -Force
                }
            }
        }

    $miscSource = Join-Path $sourceRoot "src\core\src\hev-socks5-misc.c"
    $misc = Get-Content -LiteralPath $miscSource -Raw
    if ($misc -notmatch "hev_socks5_set_protect_socket") {
        $misc = $misc.Replace(
            '#include "hev-socks5-misc-priv.h"',
            @'
#include "hev-socks5-misc-priv.h"

static int (*protect_socket_callback) (int fd);

void
hev_socks5_set_protect_socket (int (*callback) (int fd))
{
    protect_socket_callback = callback;
}
'@
        )
    }

    if ($misc -notmatch "protect_socket_callback \(fd\)") {
        $pattern = "(    if \(fd < 0\)\r?\n        return -1;\r?\n\r?\n)(    res = setsockopt \(fd, IPPROTO_IPV6, IPV6_V6ONLY, &zero, sizeof \(zero\)\);)"
        $replacement = "`$1    if (protect_socket_callback) {`r`n        res = protect_socket_callback (fd);`r`n        if (res < 0) {`r`n            close (fd);`r`n            return -1;`r`n        }`r`n    }`r`n`r`n`$2"
        $misc = [regex]::Replace($misc, $pattern, $replacement, 1)
    }
    Set-Content -LiteralPath $miscSource -Value $misc -NoNewline

    $tunnelSource = Join-Path $sourceRoot "src\hev-socks5-tunnel.c"
    $tunnel = Get-Content -LiteralPath $tunnelSource -Raw
    if ($tunnel -notmatch "ADB SOCKS5: drop non-DNS UDP") {
        $pattern = "(    dns = hev_mapped_dns_get \(\);\r?\n    if \(dns && addr->type == IPADDR_TYPE_V4\) \{\r?\n        int faddr = hev_config_get_mapdns_address \(\);\r?\n        int fport = hev_config_get_mapdns_port \(\);\r?\n        if \(fport == port && faddr == ip_2_ip4 \(addr\)->addr\) \{\r?\n            udp_recv \(pcb, dns_recv_handler, dns\);\r?\n            return;\r?\n        \}\r?\n    \}\r?\n\r?\n)(    udp = hev_socks5_session_udp_new \(pcb, &mutex\);)"
        $replacement = "`$1    /* ADB SOCKS5: SocksDroid-style default. Keep DNS on mapdns, but`r`n     * do not send arbitrary full-device UDP through commercial SOCKS5.`r`n     * It otherwise opens many UDP ASSOCIATE sessions and starves TCP.`r`n     *`r`n     * Do NOT pbuf_free (p) here: like the !run branch above, udp_input`r`n     * owns and frees p after this callback returns. Freeing it here is a`r`n     * double-free that aborts under real full-device UDP load.`r`n     */`r`n    udp_remove (pcb);`r`n    return;`r`n`r`n`$2"
        $patchedTunnel = [regex]::Replace($tunnel, $pattern, $replacement, 1)
        if ($patchedTunnel -eq $tunnel) {
            throw "Failed to patch hev-socks5-tunnel UDP handler"
        }
        $tunnel = $patchedTunnel
    }
    Set-Content -LiteralPath $tunnelSource -Value $tunnel -NoNewline
}

function Build-HevLibrary([string] $NdkBuild, [string] $TargetAbi) {
    $sourceShort = Get-ShortPath $sourceRoot
    $ndkBuildShort = Get-ShortPath $NdkBuild
    $revId = $HevCommit.Substring(0, 12)
    $ndkArgs = "NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk APP_ABI=$TargetAbi REV_ID=$revId"

    if ($Clean) {
        Write-Step "cleaning hev-socks5-tunnel for $TargetAbi"
        Invoke-CmdChecked "cd /d `"$sourceShort`" && `"$ndkBuildShort`" $ndkArgs clean"
    }

    Write-Step "building hev-socks5-tunnel for $TargetAbi"
    Invoke-CmdChecked "cd /d `"$sourceShort`" && `"$ndkBuildShort`" $ndkArgs"

    $builtLibrary = Join-Path $sourceRoot "libs\$TargetAbi\libhev-socks5-tunnel.so"
    if (-not (Test-Path -LiteralPath $builtLibrary)) {
        throw "Built library not found: $builtLibrary"
    }

    $abiOutput = Join-Path $jniLibsRoot $TargetAbi
    New-Item -ItemType Directory -Force -Path $abiOutput | Out-Null
    Copy-Item -LiteralPath $builtLibrary -Destination (Join-Path $abiOutput "libhev-socks5-tunnel.so") -Force
}

function Build-JniBridge([string] $ToolchainBin, [string] $TargetAbi) {
    $clang = Join-Path $ToolchainBin (Get-ClangName $TargetAbi)
    if (-not (Test-Path -LiteralPath $clang)) {
        throw "clang not found: $clang"
    }

    $abiOutput = Join-Path $jniLibsRoot $TargetAbi
    $jniOutput = Join-Path $abiOutput "libtun2socks_jni.so"
    $hevOutput = Join-Path $abiOutput "libhev-socks5-tunnel.so"
    if (-not (Test-Path -LiteralPath $hevOutput)) {
        throw "libhev-socks5-tunnel.so must be built before JNI bridge for $TargetAbi"
    }

    Write-Step "building JNI bridge for $TargetAbi"
    & $clang -shared -fPIC -O2 -Wall -Wextra `
        "-Wl,-z,max-page-size=16384" `
        "-Wl,-z,common-page-size=16384" `
        "-o" $jniOutput `
        $jniSource `
        "-L$abiOutput" `
        "-lhev-socks5-tunnel"
    if ($LASTEXITCODE -ne 0) {
        throw "JNI bridge build failed for $TargetAbi with exit code $LASTEXITCODE"
    }
}

$sdkPath = Get-AndroidSdkPath
$ndkPath = Join-Path $sdkPath "ndk\$NdkVersion"
$ndkBuild = Join-Path $ndkPath "ndk-build.cmd"
$toolchainBin = Join-Path $ndkPath "toolchains\llvm\prebuilt\windows-x86_64\bin"

if (-not (Test-Path -LiteralPath $ndkBuild)) {
    throw "NDK $NdkVersion not found at $ndkPath. Install it from Android Studio SDK Manager."
}
if (-not (Test-Path -LiteralPath $jniSource)) {
    throw "JNI source not found: $jniSource"
}

Write-Step "repo: $repoRoot"
Write-Step "sdk:  $sdkPath"
Write-Step "ndk:  $ndkPath"
Write-Step "abi:  $($Abi -join ', ')"

foreach ($targetAbi in $Abi) {
    if ($targetAbi -notin $supportedAbis) {
        throw "Unsupported ABI: $targetAbi. Supported ABIs: $($supportedAbis -join ', ')"
    }
}

Ensure-HevSource
Patch-HevSourceForLibraryBuild

foreach ($targetAbi in $Abi) {
    Build-HevLibrary -NdkBuild $ndkBuild -TargetAbi $targetAbi
    Build-JniBridge -ToolchainBin $toolchainBin -TargetAbi $targetAbi
}

Write-Step "native libraries copied to $jniLibsRoot"
