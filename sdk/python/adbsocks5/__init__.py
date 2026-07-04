"""adbsocks5 - Python SDK for the headless ADB SOCKS5 VPN Android app.

Route an ADB-connected Android device's traffic through a SOCKS5 proxy. Pick a
device by its ADB serial; the APK is downloaded and installed automatically if
it is missing.
"""

from __future__ import annotations

from .adb import Adb, Device
from .apk import (
    DEFAULT_APK_URL,
    PACKAGE_NAME,
    default_cache_dir,
    download_apk,
    latest_release_apk_url,
    resolve_apk,
)
from .client import AdbSocks5Client
from .errors import (
    AdbCommandError,
    AdbNotFoundError,
    AdbSocks5Error,
    ApkDownloadError,
    DeviceNotFoundError,
    MultipleDevicesError,
    NoDeviceError,
    ProxyConfigError,
    VpnError,
)
from .proxy import ProxyConfig

__version__ = "0.1.0"

__all__ = [
    "AdbSocks5Client",
    "ProxyConfig",
    "Adb",
    "Device",
    "PACKAGE_NAME",
    "DEFAULT_APK_URL",
    "default_cache_dir",
    "download_apk",
    "latest_release_apk_url",
    "resolve_apk",
    "AdbSocks5Error",
    "AdbNotFoundError",
    "AdbCommandError",
    "NoDeviceError",
    "DeviceNotFoundError",
    "MultipleDevicesError",
    "ApkDownloadError",
    "ProxyConfigError",
    "VpnError",
    "__version__",
]
