# adbsocks5 (Python SDK)

Python SDK for the headless **ADB SOCKS5 VPN** Android app. It routes an
ADB-connected Android device's traffic through a SOCKS5 proxy.

- Pick **which device** the proxy is applied to by its ADB serial.
- If the app is **not installed**, the APK is **downloaded and installed
  automatically** from the project's GitHub releases.
- Pure standard library — **no third-party dependencies**.

The SDK is a thin, safe wrapper over `adb` and the app's ADB control surface
(`com.proxy.START` / `com.proxy.STOP`, config `ip|port|login|password`).

## Requirements

- Python 3.8+
- `adb` (Android platform-tools) on `PATH`, or pass `adb_path=...`
- An Android 12+ device with USB debugging enabled and authorized

## Install

```bash
cd sdk/python
pip install -e .
```

This also installs the `adbsocks5` command-line entry point.

## Python usage

```python
from adbsocks5 import AdbSocks5Client, ProxyConfig

# Target a specific device by its ADB serial (from `adb devices`).
client = AdbSocks5Client(serial="ABCD1234")

# Installs the APK automatically if it is missing, grants VPN permission,
# then starts the SOCKS5 VPN.
client.connect(ip="1.2.3.4", port=1080, login="user", password="pass")

print("VPN active:", client.is_vpn_active())

client.disconnect()
```

No-auth proxy:

```python
client.connect(ip="1.2.3.4", port=1080)  # empty login/password
```

Choosing the device:

```python
from adbsocks5 import AdbSocks5Client

for d in AdbSocks5Client.list_devices():
    print(d.serial, d.state)

# If exactly one device is connected, serial can be omitted:
client = AdbSocks5Client()
```

Explicit / offline APK (skip the download):

```python
client = AdbSocks5Client(serial="ABCD1234", apk_path="path/to/app-release.apk")
client.ensure_installed()
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
| `ensure_installed()` | download APK -> `adb -s <serial> install -r app-release.apk` |
| `grant_vpn_permission()` | `adb -s <serial> shell appops set com.proxy ACTIVATE_VPN allow` |
| `connect(...)` | `am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config 'ip\|port\|login\|password'` |
| `disconnect()` | `... -a com.proxy.STOP` |
| `is_vpn_active()` | parses `dumpsys connectivity` for the app's VPN session |

## APK source

By default the SDK downloads the pinned release asset
(`app-release.apk` from GitHub release `v0.1.0`) and caches it under a per-user
cache directory (override with `ADBSOCKS5_CACHE_DIR`). Use `use_latest=True`
(or `--latest`) to resolve the newest GitHub release instead, or pass
`apk_path=` / `--apk` to install a local file with no network access.

## Security notes

- Proxy credentials are never logged; the SDK logs a redacted config string.
- Do not hard-code passwords; prefer environment variables.
- The device-side config value is single-quoted for the device shell, so
  `|` and spaces are passed through safely.

## Status

Alpha. The install/start/stop control path matches the app's current ADB
interface. See the repository root `README.md` for app-level details.
