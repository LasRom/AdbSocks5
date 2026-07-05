# Android APK

Headless Android app launched through ADB (with an optional on-device UI). This directory owns the Android Gradle project, APK module, `VpnService`, ADB command bridge, and the native tunnel engine (BadVPN `tun2socks` + `pdnsd`, SocksDroid model).

Minimum supported Android version: Android 12 (`minSdk 31`).

Grant VPN permission for local ADB testing:

```powershell
adb shell appops set com.proxy ACTIVATE_VPN allow
```

Current Android 12+ entrypoint:

```powershell
adb shell "am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config 'ip|port|login|password'"
```

SOCKS5 without username/password:

```powershell
adb shell "am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.START --es config 'ip|port||'"
```

Stop:

```powershell
adb shell am start-foreground-service -n com.proxy/.AdbCommandService -a com.proxy.STOP
```

Native libraries are rebuilt from this `android/` directory:

```powershell
..\scripts\build-native.ps1
```

Build debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Build unsigned release APK:

```powershell
.\gradlew.bat assembleRelease
```

Version metadata is defined in `gradle.properties`:

```properties
app.versionCode=1
app.versionName=0.1.0
```

For signed release builds, copy `signing.properties.example` to `signing.properties` and fill local keystore values. `signing.properties` and keystores are ignored by Git.

Without local signing properties, release output is `app-release-unsigned.apk`.

Current packaged ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
