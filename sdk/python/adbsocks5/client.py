"""High-level client for controlling the ADB SOCKS5 VPN app over ADB.

Typical use::

    from adbsocks5 import AdbSocks5Client

    client = AdbSocks5Client(serial="ABCD1234")   # pick a device
    client.connect(ip="1.2.3.4", port=1080, login="user", password="pass")
    ...
    client.disconnect()

If the app is not installed on the chosen device, :meth:`connect` downloads the
APK from GitHub releases and installs it automatically.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional

from .adb import Adb, Device
from .apk import DEFAULT_APK_URL, PACKAGE_NAME, TARGET_VERSION_CODE, resolve_apk
from .errors import VpnError
from .proxy import ProxyConfig

log = logging.getLogger("adbsocks5.client")

_COMPONENT = f"{PACKAGE_NAME}/.AdbCommandService"
_ACTION_START = f"{PACKAGE_NAME}.START"
_ACTION_STOP = f"{PACKAGE_NAME}.STOP"
_VPN_SESSION_NAME = "ADB SOCKS5 VPN"


def _shell_single_quote(value: str) -> str:
    """Quote ``value`` for the device-side shell (POSIX single-quote rules)."""
    return "'" + value.replace("'", "'\\''") + "'"


class AdbSocks5Client:
    """Controls the headless SOCKS5 VPN app on one ADB-connected device.

    Args:
        serial: ADB serial of the target device. If omitted, exactly one ready
            device must be connected (otherwise an error is raised so the proxy
            is never applied to the wrong phone).
        adb_path: Path to the ``adb`` executable (default: ``"adb"`` on PATH).
        apk_path: Optional explicit local APK; skips downloading.
        apk_url: Download URL used when the app must be fetched.
        cache_dir: Where to cache downloaded APKs.
        timeout: Default per-command timeout in seconds.
    """

    PACKAGE = PACKAGE_NAME

    def __init__(
        self,
        serial: Optional[str] = None,
        adb_path: str = "adb",
        apk_path: Optional[Path] = None,
        apk_url: str = DEFAULT_APK_URL,
        cache_dir: Optional[Path] = None,
        timeout: float = 60.0,
    ) -> None:
        self.adb = Adb(adb_path=adb_path, serial=serial, timeout=timeout)
        self.apk_path = Path(apk_path) if apk_path else None
        self.apk_url = apk_url
        self.cache_dir = Path(cache_dir) if cache_dir else None

    # -- device ------------------------------------------------------------

    @property
    def serial(self) -> Optional[str]:
        return self.adb.serial

    @staticmethod
    def list_devices(adb_path: str = "adb") -> list[Device]:
        """List all devices ADB currently sees (ready or not)."""
        return Adb.list_devices(adb_path=adb_path)

    def resolve_device(self) -> str:
        """Validate and return the target serial, pinning it for later calls."""
        return self.adb.resolve_serial()

    # -- installation ------------------------------------------------------

    def is_installed(self) -> bool:
        """Whether the SOCKS5 VPN app is installed on the target device."""
        self.resolve_device()
        return self.adb.is_package_installed(self.PACKAGE)

    def install(
        self,
        apk_path: Optional[Path] = None,
        force_download: bool = False,
        use_latest: bool = False,
    ) -> Path:
        """Install the app, downloading the APK if no local path is available.

        Returns the host path of the APK that was installed.
        """
        self.resolve_device()
        path = resolve_apk(
            apk_path=apk_path or self.apk_path,
            apk_url=self.apk_url,
            cache_dir=self.cache_dir,
            use_latest=use_latest,
            force_download=force_download,
        )
        log.info("installing %s on %s", path.name, self.serial)
        self.adb.install(str(path), reinstall=True)
        return path

    def ensure_installed(self, use_latest: bool = False) -> bool:
        """Install the app if missing, or upgrade it if the installed build is
        older than the SDK's target (:data:`~adbsocks5.apk.TARGET_VERSION_CODE`).

        Returns ``True`` if an install or upgrade was performed, ``False`` if the
        app was already present and up to date.
        """
        self.resolve_device()
        installed = self.adb.package_version_code(self.PACKAGE)
        if installed is None:
            log.info("app not installed on %s, installing", self.serial)
            self.install(use_latest=use_latest)
            return True
        if installed < TARGET_VERSION_CODE:
            log.info(
                "app on %s is versionCode %d (< %d), upgrading",
                self.serial,
                installed,
                TARGET_VERSION_CODE,
            )
            self.install(use_latest=use_latest)
            return True
        log.debug(
            "app up to date on %s (versionCode %d)", self.serial, installed
        )
        return False

    # -- permission --------------------------------------------------------

    def grant_vpn_permission(self) -> None:
        """Grant the VPN consent via ``appops`` (headless app has no UI)."""
        self.resolve_device()
        self.adb.shell(f"appops set {self.PACKAGE} ACTIVATE_VPN allow")

    # -- connect / disconnect ---------------------------------------------

    def connect(
        self,
        ip: Optional[str] = None,
        port: Optional[int] = None,
        login: str = "",
        password: str = "",
        *,
        proxy: Optional[ProxyConfig] = None,
        auto_install: bool = True,
        grant_permission: bool = True,
        use_latest: bool = False,
    ) -> ProxyConfig:
        """Route the device's traffic through the given SOCKS5 proxy.

        Provide either a :class:`ProxyConfig` via ``proxy`` or ``ip``/``port``
        (plus optional ``login``/``password``).

        Steps performed:
            1. resolve/validate the target device,
            2. install the app if missing (when ``auto_install``),
            3. grant VPN permission (when ``grant_permission``),
            4. send the START command with the config string.

        Returns the :class:`ProxyConfig` that was applied.
        """
        if proxy is None:
            if ip is None or port is None:
                raise VpnError("connect requires either proxy=... or ip and port")
            proxy = ProxyConfig(ip=ip, port=port, login=login, password=password)

        self.resolve_device()
        if auto_install:
            self.ensure_installed(use_latest=use_latest)
        elif not self.is_installed():
            raise VpnError(
                f"app {self.PACKAGE} is not installed and auto_install=False"
            )
        if grant_permission:
            self.grant_vpn_permission()

        quoted = _shell_single_quote(proxy.to_config_string())
        command = (
            f"am start-foreground-service -n {_COMPONENT} "
            f"-a {_ACTION_START} --es config {quoted}"
        )
        log.info("connecting %s via %s", self.serial, proxy.redacted())
        out = self.adb.shell(command)
        if "Error" in out or "Exception" in out:
            raise VpnError(f"START command failed on device: {out.strip()}")
        return proxy

    def disconnect(self) -> None:
        """Stop the VPN and tear down the TUN interface on the device."""
        self.resolve_device()
        command = (
            f"am start-foreground-service -n {_COMPONENT} -a {_ACTION_STOP}"
        )
        log.info("disconnecting %s", self.serial)
        out = self.adb.shell(command)
        if "Error" in out or "Exception" in out:
            raise VpnError(f"STOP command failed on device: {out.strip()}")

    # -- status ------------------------------------------------------------

    def is_vpn_active(self) -> bool:
        """Whether this app's VPN is currently up on the device.

        Inspects ``dumpsys connectivity`` for the app's VPN session/owner.
        """
        self.resolve_device()
        out = self.adb.shell("dumpsys connectivity", check=False)
        return _VPN_SESSION_NAME in out or f"owner={self.PACKAGE}" in out

    def __repr__(self) -> str:  # pragma: no cover - debug helper
        return f"AdbSocks5Client(serial={self.serial!r})"
