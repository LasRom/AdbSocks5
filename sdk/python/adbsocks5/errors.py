"""Exception types raised by the adbsocks5 SDK."""

from __future__ import annotations

from typing import Sequence


class AdbSocks5Error(Exception):
    """Base class for every error raised by this SDK."""


class AdbNotFoundError(AdbSocks5Error):
    """The ``adb`` executable could not be located on the host."""


class AdbCommandError(AdbSocks5Error):
    """An ``adb`` command exited with a non-zero status."""

    def __init__(
        self,
        args: Sequence[str],
        returncode: int,
        stdout: str,
        stderr: str,
    ) -> None:
        self.args = list(args)
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        detail = (stderr or stdout or "").strip()
        message = f"adb command failed (exit {returncode}): {' '.join(self.args)}"
        if detail:
            message = f"{message}\n{detail}"
        super().__init__(message)


class NoDeviceError(AdbSocks5Error):
    """No usable device is connected over ADB."""


class DeviceNotFoundError(AdbSocks5Error):
    """A specific device serial was requested but is not connected."""


class MultipleDevicesError(AdbSocks5Error):
    """Multiple devices are connected and no serial was provided."""


class ApkDownloadError(AdbSocks5Error):
    """The APK could not be downloaded or is not a valid package."""


class ProxyConfigError(AdbSocks5Error):
    """The proxy configuration is invalid."""


class VpnError(AdbSocks5Error):
    """Starting or stopping the VPN failed on the device."""
