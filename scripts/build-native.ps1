param(
    [string[]] $Abi = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
    [string] $NdkVersion = "29.0.14206865",
    [string] $SocksDroidUrl = "https://github.com/bndeff/socksdroid.git",
    [string] $SocksDroidCommit = "cf4d7babc40701c7c19d080198a2e7522fa8e617",
    [switch] $Clean
)

# Builds the native tunnel engine (SocksDroid model): BadVPN tun2socks + pdnsd
# + a tiny fd-passing JNI helper (libsystem.so). Sources come from the pinned
# SocksDroid checkout (BadVPN is Android-patched there: --tunfd/--sock/--dnsgw/
# daemonize). Outputs are executables renamed to lib*.so so they package into
# the APK and can be exec'd at runtime from nativeLibraryDir.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$androidRoot = Join-Path $repoRoot "android"
$appMain = Join-Path $androidRoot "app\src\main"
$sourceRoot = Join-Path $repoRoot "build\native\socksdroid"
$jniRootRel = "app\src\main\jni"
$jniLibsRoot = Join-Path $appMain "jniLibs"
$supportedAbis = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

# JNI class the fd-passing helper registers against (SocksDroid ships
# net/typeblog/socks/System; we repoint it to our package).
$jniClassOld = 'net/typeblog/socks/System'
$jniClassNew = 'com/proxy/Native'

$fso = New-Object -ComObject Scripting.FileSystemObject

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
    if (Test-Path -LiteralPath $resolved -PathType Container) {
        return $fso.GetFolder($resolved).ShortPath
    }
    return $fso.GetFile($resolved).ShortPath
}

function Invoke-Git([string[]] $Arguments) {
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Ensure-SocksDroidSource {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sourceRoot) | Out-Null
    if (-not (Test-Path -LiteralPath $sourceRoot)) {
        Write-Step "cloning socksdroid $SocksDroidCommit"
        Invoke-Git @("clone", $SocksDroidUrl, $sourceRoot)
    }
    Write-Step "checking out socksdroid $SocksDroidCommit"
    Invoke-Git @("-c", "safe.directory=$sourceRoot", "-C", $sourceRoot, "fetch", "origin")
    Invoke-Git @("-c", "safe.directory=$sourceRoot", "-C", $sourceRoot, "checkout", "--force", $SocksDroidCommit)
}

function Patch-JniClassName {
    $systemCpp = Join-Path $sourceRoot "$jniRootRel\system.cpp"
    $content = Get-Content -LiteralPath $systemCpp -Raw
    if ($content -match [regex]::Escape($jniClassNew)) {
        Write-Step "system.cpp already repointed to $jniClassNew"
        return
    }
    if ($content -notmatch [regex]::Escape($jniClassOld)) {
        throw "system.cpp does not contain expected class path '$jniClassOld'"
    }
    $content = $content.Replace($jniClassOld, $jniClassNew)
    Set-Content -LiteralPath $systemCpp -Value $content -NoNewline
    Write-Step "repointed system.cpp JNI class to $jniClassNew"
}

function Build-Abi([string] $NdkBuild, [string] $TargetAbi) {
    $projDir = Join-Path $sourceRoot "app\src\main"
    $projShort = Get-ShortPath $projDir
    $ndkShort = Get-ShortPath $NdkBuild

    if ($Clean) {
        Write-Step "cleaning $TargetAbi"
        & cmd /c "cd /d `"$projShort`" && `"$ndkShort`" NDK_PROJECT_PATH=. APP_ABI=$TargetAbi clean" | Out-Null
    }

    Write-Step "building tun2socks + pdnsd + system for $TargetAbi"
    & cmd /c "cd /d `"$projShort`" && `"$ndkShort`" NDK_PROJECT_PATH=. APP_ABI=$TargetAbi -j4"
    if ($LASTEXITCODE -ne 0) {
        throw "ndk-build failed for $TargetAbi with exit code $LASTEXITCODE"
    }

    $builtDir = Join-Path $projDir "libs\$TargetAbi"
    $outDir = Join-Path $jniLibsRoot $TargetAbi
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    # Executables must be named lib*.so to be packaged + extractable + exec'd.
    $map = @{
        "tun2socks"   = "libtun2socks.so"
        "pdnsd"       = "libpdnsd.so"
        "libsystem.so" = "libsystem.so"
    }
    foreach ($src in $map.Keys) {
        $srcPath = Join-Path $builtDir $src
        if (-not (Test-Path -LiteralPath $srcPath)) {
            throw "expected build output not found: $srcPath"
        }
        Copy-Item -LiteralPath $srcPath -Destination (Join-Path $outDir $map[$src]) -Force
    }
}

function Remove-LegacyHevLibs {
    foreach ($targetAbi in $supportedAbis) {
        $outDir = Join-Path $jniLibsRoot $targetAbi
        foreach ($legacy in @("libhev-socks5-tunnel.so", "libtun2socks_jni.so")) {
            $p = Join-Path $outDir $legacy
            if (Test-Path -LiteralPath $p) {
                Remove-Item -LiteralPath $p -Force
                Write-Step "removed legacy $targetAbi/$legacy"
            }
        }
    }
}

$sdkPath = Get-AndroidSdkPath
$ndkPath = Join-Path $sdkPath "ndk\$NdkVersion"
$ndkBuild = Join-Path $ndkPath "ndk-build.cmd"
if (-not (Test-Path -LiteralPath $ndkBuild)) {
    throw "NDK $NdkVersion not found at $ndkPath. Install it from Android Studio SDK Manager."
}

foreach ($targetAbi in $Abi) {
    if ($targetAbi -notin $supportedAbis) {
        throw "Unsupported ABI: $targetAbi. Supported ABIs: $($supportedAbis -join ', ')"
    }
}

Write-Step "repo: $repoRoot"
Write-Step "sdk:  $sdkPath"
Write-Step "ndk:  $ndkPath"
Write-Step "abi:  $($Abi -join ', ')"

Ensure-SocksDroidSource
Patch-JniClassName

foreach ($targetAbi in $Abi) {
    Build-Abi -NdkBuild $ndkBuild -TargetAbi $targetAbi
}

Remove-LegacyHevLibs

Write-Step "native binaries (libtun2socks.so, libpdnsd.so, libsystem.so) copied to $jniLibsRoot"
