# ADB SOCKS5 VPN

Headless Android VPN app controlled over ADB. It installs on an Android device, starts a `VpnService`, opens a TUN interface, and routes device traffic through a SOCKS5 proxy using `tun2socks`.

The app has no Activity and no UI. It is meant to be controlled by scripts, SDKs, or direct ADB commands.

## Current Status

- Android 12+ (`minSdk 31`)
- Headless ADB start/stop flow
- Full-device `VpnService` tunnel
- SOCKS5 with username/password: `ip|port|login|password`
- SOCKS5 without auth: `ip|port||`
- Native `hev-socks5-tunnel` through JNI
- SOCKS5 preflight before VPN startup
- Packaged native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- Python and Java SDKs for ADB control (`sdk/python`, `sdk/java`)

## Requirements

For using an already built APK:

- Android 12+ device
- USB debugging enabled on the device
- `adb` available on your computer

For building from source:

- Android Studio or Android SDK
- JDK 17+
- Gradle wrapper from this repository

For rebuilding native libraries:

- Android NDK `29.0.14206865`
- Git
- PowerShell on Windows

## Build APK

Debug APK is the easiest installable build for local testing because it is signed with the Android debug key.

Windows:

```powershell
cd android
.\gradlew.bat assembleDebug
```

macOS/Linux:

```bash
cd android
./gradlew assembleDebug
```

Debug APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Unsigned release build:

```powershell
cd android
.\gradlew.bat assembleRelease
```

Unsigned release output:

```text
android/app/build/outputs/apk/release/app-release-unsigned.apk
```

An unsigned release APK is not a normal installable release artifact. For a release build that can be distributed, create a local keystore, copy `android/signing.properties.example` to `android/signing.properties`, fill local values, then run `assembleRelease` again. `android/signing.properties` and keystore files are ignored by Git.

## Install APK

Check that the device is visible:

```powershell
adb devices
```

Install the debug APK:

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

The package name is:

```text
com.proxy
```

Uninstall:

```powershell
adb uninstall com.proxy
```

## Grant VPN Permission

Because this app is headless, local ADB testing uses `appops` to grant VPN permission:

```powershell
adb shell appops set com.proxy ACTIVATE_VPN allow
```

If VPN permission is not granted, the START command will fail before creating the TUN interface.

## Start VPN

Authenticated SOCKS5:

```powershell
adb shell "am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config 'IP|PORT|LOGIN|PASSWORD'"
```

Example shape:

```powershell
adb shell "am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config '127.0.0.1|1080|user|pass'"
```

SOCKS5 without username/password:

```powershell
adb shell "am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config 'IP|PORT||'"
```

Before the VPN is created, the app runs a SOCKS5 preflight check. If TCP connect, SOCKS5 auth, or SOCKS5 CONNECT fails, the service stops and does not leave a broken `tun0` VPN behind.

## Stop VPN

```powershell
adb shell am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.STOP
```

## Check That It Works

Check Android connectivity state:

```powershell
adb shell dumpsys connectivity
```

On Windows, filter the output:

```powershell
adb shell dumpsys connectivity | findstr /i "VPN tun0 proxy"
```

If the device has `curl`:

```powershell
adb shell curl -4 http://api.ipify.org
```

The returned IP should be the proxy IP, not the device's normal public IP.

## Logs

Android logs:

```powershell
adb logcat -s AdbCommandService SocksVpnService AdbCommandReceiver
```

For debug builds, native `tun2socks` info logs are written inside app cache:

```powershell
adb shell run-as com.proxy cat cache/tun2socks.log
```

## Native Libraries

The APK packages two native libraries for every supported ABI:

- `libhev-socks5-tunnel.so`
- `libtun2socks_jni.so`

Rebuild all supported ABIs:

```powershell
.\scripts\build-native.ps1
```

Rebuild one ABI:

```powershell
.\scripts\build-native.ps1 -Abi arm64-v8a
```

After rebuilding native libraries, rebuild the APK:

```powershell
cd android
.\gradlew.bat assembleDebug
```

## Troubleshooting

`adb devices` does not show the device:

- enable USB debugging;
- confirm the RSA prompt on the phone;
- reconnect USB and run `adb devices` again.

START does nothing or exits immediately:

- grant VPN permission with `adb shell appops set com.proxy ACTIVATE_VPN allow`;
- check `adb logcat -s AdbCommandService SocksVpnService AdbCommandReceiver`;
- make sure you are using `am start-foreground-service`, not the legacy broadcast command.

`SOCKS5 preflight failed`:

- check proxy IP and port;
- check login/password;
- test the proxy from the same Android device/network;
- try no-auth format only for proxies that really do not require auth: `ip|port||`.

Internet works without VPN but not with VPN:

- the SOCKS5 proxy may reject CONNECT requests or be unstable under full-device traffic;
- check `cache/tun2socks.log` on debug builds;
- stop the VPN and retry with a known working proxy.

## SDKs

Language SDKs wrap the ADB control surface so you can install, start, stop, and
configure the proxy programmatically. Both let you target a specific device by
its ADB serial and download+install the APK automatically when it is missing.

- Python SDK: [`sdk/python`](sdk/python) — package `adbsocks5`, standard library only.

  ```bash
  cd sdk/python && pip install -e .
  adbsocks5 connect --serial ABCD1234 --ip 1.2.3.4 --port 1080 --login user --password pass
  ```

- Java SDK: [`sdk/java`](sdk/java) — package `com.adbsocks5`, pure JDK (no dependencies).

  ```bash
  cd sdk/java && javac -d out src/main/java/com/adbsocks5/*.java
  java -cp out com.adbsocks5.AdbSocks5Cli connect --serial ABCD1234 --ip 1.2.3.4 --port 1080 --login user --password pass
  ```

See each SDK's `README.md` for the full API and CLI reference.

## Repository Layout

```text
.
|-- android/          # Android Gradle project and APK module
|   `-- app/          # Headless Android application
|-- scripts/          # Build helpers, including native library build
|-- sdk/              # Language SDKs for controlling the app over ADB
|   |-- python/       # Python SDK (adbsocks5)
|   `-- java/         # Java SDK (com.adbsocks5)
`-- README.md         # Public install/use documentation
```

Local AI/project memory files are intentionally not part of the public repository.

## Security Notes

- Do not commit proxy credentials, API keys, tokens, keystores, or local environment files.
- Use `.env` or local shell environment variables for secrets.
- The ADB config format is `ip|port|login|password`; avoid logging or committing real proxy passwords.

## Roadmap

- Add CI checks for APK and native ABI matrix builds.
- Package and publish the SDKs (PyPI / Maven).
- Add SDK integration tests against a connected device.

Done:

- Python SDK for ADB install/start/stop/config workflows (`sdk/python`).
- Java SDK with the same control surface (`sdk/java`).

## License

License is not selected yet.
