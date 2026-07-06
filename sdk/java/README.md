# adbsocks5 (Java SDK)

Java SDK for the headless **ADB SOCKS5 VPN** Android app. It routes an
ADB-connected Android device's traffic through a SOCKS5 proxy.

- Pick **which device** the proxy is applied to by its ADB serial.
- If the app is **not installed**, the APK is **downloaded and installed
  automatically** from the project's GitHub releases.
- Pure JDK — **no third-party dependencies** (`ProcessBuilder` + `HttpURLConnection`).

The SDK is a thin, safe wrapper over `adb` and the app's ADB control surface
(`com.proxy.START` / `com.proxy.STOP`, config `ip|port|login|password`).

## Requirements

- JDK 11+
- `adb` (Android platform-tools) on `PATH`, or pass an explicit `adbPath`
- An Android 12+ device with USB debugging enabled and authorized

## Build

```bash
cd sdk/java
gradle build          # library + sources jar
gradle installDist    # runnable CLI under build/install/adbsocks5/bin
```

There is no Gradle wrapper checked in; use a locally installed Gradle, or
compile directly with `javac` (the SDK has no dependencies):

```bash
cd sdk/java
javac -d out src/main/java/com/adbsocks5/*.java
java -cp out com.adbsocks5.AdbSocks5Cli devices
```

## Java usage

```java
import com.adbsocks5.AdbSocks5Client;
import com.adbsocks5.ProxyConfig;

// Target a specific device by its ADB serial (from `adb devices`).
AdbSocks5Client client = new AdbSocks5Client("ABCD1234");

// Installs the APK automatically if it is missing, grants VPN permission,
// then starts the SOCKS5 VPN.
client.connect(new ProxyConfig("1.2.3.4", 1080, "user", "pass"));

System.out.println("VPN active: " + client.isVpnActive());

client.disconnect();
```

No-auth proxy:

```java
client.connect(new ProxyConfig("1.2.3.4", 1080)); // empty login/password
```

Choosing the device:

```java
for (com.adbsocks5.Device d : AdbSocks5Client.listDevices("adb")) {
    System.out.println(d.serial() + " " + d.state());
}

// If exactly one device is connected, serial can be null:
AdbSocks5Client client = new AdbSocks5Client(null);
```

Explicit / offline APK, custom cache, latest release:

```java
AdbSocks5Client client = new AdbSocks5Client("ABCD1234")
        .withApkPath(java.nio.file.Paths.get("path/to/app-release.apk"));
client.ensureInstalled(false);

// or resolve the newest GitHub release instead of the pinned one:
client.connect(proxy, /*autoInstall*/ true, /*grantPermission*/ true, /*useLatest*/ true);
```

## Command-line usage

```bash
adbsocks5 devices
adbsocks5 install    --serial ABCD1234
adbsocks5 connect    --serial ABCD1234 --ip 1.2.3.4 --port 1080 --login user --password pass
adbsocks5 connect    --serial ABCD1234 --proxy '1.2.3.4|1080|user|pass'
adbsocks5 status     --serial ABCD1234
adbsocks5 disconnect --serial ABCD1234
```

If only one device is connected, `--serial` may be omitted.

## How it maps to the app

| SDK call | ADB action |
| --- | --- |
| `ensureInstalled(...)` | download APK -> `adb -s <serial> install -r app-release.apk` |
| `grantVpnPermission()` | `adb -s <serial> shell appops set com.proxy ACTIVATE_VPN allow` |
| `connect(...)` | `am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config 'ip\|port\|login\|password'` |
| `disconnect()` | `... -a com.proxy.STOP` |
| `isVpnActive()` | parses `dumpsys connectivity` for the app's VPN session |

## APK source

By default the SDK downloads the pinned release asset (`app-release.apk` from
GitHub release `v1.0.0`) and caches it under a per-user cache directory
(override with the `ADBSOCKS5_CACHE_DIR` environment variable). Use `useLatest`
(or `--latest`) to resolve the newest GitHub release, or `withApkPath(...)` /
`--apk` to install a local file with no network access.

## Error handling

All SDK errors extend the checked exception `AdbSocks5Exception`; catch it to
handle any failure (missing `adb`, no/multiple devices, bad proxy config,
download failure, device-side VPN error).

## Security notes

- Proxy credentials are never logged; the SDK logs a redacted config string.
- Do not hard-code passwords; prefer environment variables.
- The device-side config value is single-quoted for the device shell, so `|`
  and spaces are passed through safely.

## Status

Alpha. The install/start/stop control path matches the app's current ADB
interface and mirrors the Python SDK. See the repository root `README.md` for
app-level details.
