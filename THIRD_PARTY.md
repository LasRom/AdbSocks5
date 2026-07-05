# Third-party components

The Android APK bundles native binaries built from third-party sources. They
run as separate processes (exec'd from the app's native library directory); the
app's own Kotlin/UI code does not link against them.

Sources are built by `scripts/build-native.ps1` from a pinned checkout of
[bndeff/socksdroid](https://github.com/bndeff/socksdroid) (which vendors the
components below with the Android patches they require).

| Component | Packaged as | Upstream | License |
|-----------|-------------|----------|---------|
| BadVPN `tun2socks` | `libtun2socks.so` | [ambrop72/badvpn](https://github.com/ambrop72/badvpn) | BSD-3-Clause |
| `pdnsd` | `libpdnsd.so` | pdnsd (Paul A. Rombouts / Thomas Moestl) | **GPL-2.0** |
| fd-passing JNI helper (`system.cpp` + libancillary) | `libsystem.so` | [bndeff/socksdroid](https://github.com/bndeff/socksdroid) | GPL-2.0 |
| lwIP (used by tun2socks) | (statically linked into `libtun2socks.so`) | lwIP | BSD-3-Clause |

## GPL-2.0 notice

`libpdnsd.so` and `libsystem.so` are derived from GPL-2.0 sources. If you
distribute the APK, you must comply with the GPL-2.0 — in particular, make the
corresponding source available. The source is the pinned SocksDroid checkout
referenced in `scripts/build-native.ps1` (`$SocksDroidCommit`) plus the small
patch that repoints the JNI class name; re-running the script reproduces the
binaries.
