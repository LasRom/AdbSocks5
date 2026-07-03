# Android APK

Headless Android app launched through ADB. This directory owns the Android Gradle project, APK module, `VpnService`, ADB broadcast receiver, and future `tun2socks` integration.

Current entrypoint:

```powershell
adb shell am broadcast -n com.proxy/.AdbCommandReceiver -a com.proxy.START --es config "ip|port|login|password"
```
