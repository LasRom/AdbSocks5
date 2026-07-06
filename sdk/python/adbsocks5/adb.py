"""Thin wrapper around the ``adb`` command-line tool.

Every call can be scoped to a specific device via its ADB serial, which is how
the SDK lets the caller choose *which* connected device the proxy is applied to.
"""

from __future__ import annotations

import logging
import re
import subprocess
from dataclasses import dataclass
from typing import List, Optional

from .errors import (
    AdbCommandError,
    AdbNotFoundError,
    DeviceNotFoundError,
    MultipleDevicesError,
    NoDeviceError,
)

log = logging.getLogger("adbsocks5.adb")


@dataclass(frozen=True)
class Device:
    """A device reported by ``adb devices``."""

    serial: str
    state: str  # e.g. "device", "unauthorized", "offline"

    @property
    def is_ready(self) -> bool:
        return self.state == "device"


class Adb:
    """Runs ``adb`` commands, optionally pinned to one device serial."""

    def __init__(
        self,
        adb_path: str = "adb",
        serial: Optional[str] = None,
        timeout: float = 60.0,
    ) -> None:
        self.adb_path = adb_path
        self.serial = serial
        self.timeout = timeout

    # -- low level ---------------------------------------------------------

    def _base(self) -> List[str]:
        base = [self.adb_path]
        if self.serial:
            base += ["-s", self.serial]
        return base

    def run(
        self,
        *args: str,
        check: bool = True,
        timeout: Optional[float] = None,
    ) -> subprocess.CompletedProcess:
        """Run ``adb [-s serial] <args...>`` and return the completed process.

        The arguments are passed to ``adb`` directly (no host shell), so shell
        metacharacters in ``args`` are safe on the host side.
        """
        cmd = self._base() + list(args)
        log.debug("running: %s", " ".join(cmd))
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout if timeout is not None else self.timeout,
            )
        except FileNotFoundError as exc:
            raise AdbNotFoundError(
                f"could not find adb executable {self.adb_path!r}; "
                "install Android platform-tools or pass adb_path"
            ) from exc
        if check and proc.returncode != 0:
            raise AdbCommandError(cmd, proc.returncode, proc.stdout, proc.stderr)
        return proc

    def shell(
        self,
        command: str,
        check: bool = True,
        timeout: Optional[float] = None,
    ) -> str:
        """Run a command through the device shell and return stdout.

        ``command`` is sent as a single argument, so device-side quoting (for
        example the single quotes protecting the ``|`` in the config string) is
        preserved verbatim.
        """
        proc = self.run("shell", command, check=check, timeout=timeout)
        return proc.stdout

    # -- device discovery --------------------------------------------------

    @classmethod
    def list_devices(
        cls,
        adb_path: str = "adb",
        timeout: float = 30.0,
    ) -> List[Device]:
        """Return every device known to ``adb devices``."""
        adb = cls(adb_path=adb_path, timeout=timeout)
        out = adb.run("devices").stdout
        devices: List[Device] = []
        for line in out.splitlines()[1:]:  # skip "List of devices attached"
            line = line.strip()
            if not line or "\t" not in line:
                continue
            serial, state = line.split("\t", 1)
            devices.append(Device(serial.strip(), state.strip()))
        return devices

    def resolve_serial(self) -> str:
        """Return the serial this instance targets, validating availability.

        If no serial was given, exactly one ready device must be connected.
        Raises a descriptive error otherwise.
        """
        devices = self.list_devices(self.adb_path, self.timeout)
        ready = [d for d in devices if d.is_ready]

        if self.serial:
            match = next((d for d in devices if d.serial == self.serial), None)
            if match is None:
                raise DeviceNotFoundError(
                    f"device {self.serial!r} is not connected; "
                    f"connected: {[d.serial for d in devices] or 'none'}"
                )
            if not match.is_ready:
                raise DeviceNotFoundError(
                    f"device {self.serial!r} is in state {match.state!r}, "
                    "not 'device' (authorize USB debugging on the phone)"
                )
            return self.serial

        if not ready:
            if devices:
                raise NoDeviceError(
                    "no ready device; connected devices are not in 'device' "
                    f"state: {[(d.serial, d.state) for d in devices]}"
                )
            raise NoDeviceError("no device connected over ADB")
        if len(ready) > 1:
            raise MultipleDevicesError(
                "multiple devices connected; pass a serial to choose one: "
                f"{[d.serial for d in ready]}"
            )
        # Pin the resolved serial so subsequent calls are unambiguous.
        self.serial = ready[0].serial
        return self.serial

    # -- package helpers ---------------------------------------------------

    def is_package_installed(self, package: str) -> bool:
        """Whether ``package`` is installed on the device."""
        out = self.shell(f"pm list packages {package}")
        for line in out.splitlines():
            if line.strip() == f"package:{package}":
                return True
        return False

    def package_version_code(self, package: str) -> Optional[int]:
        """Return the installed ``versionCode`` of ``package``, or ``None`` if
        the package is not installed."""
        if not self.is_package_installed(package):
            return None
        out = self.shell(f"dumpsys package {package}", check=False)
        match = re.search(r"versionCode=(\d+)", out)
        return int(match.group(1)) if match else None

    def install(self, apk_path: str, reinstall: bool = True) -> None:
        """Install (or reinstall) an APK from a host path."""
        args = ["install"]
        if reinstall:
            args.append("-r")
        args.append(str(apk_path))
        # Installs can take a while on slow devices.
        proc = self.run(*args, check=False, timeout=max(self.timeout, 180.0))
        combined = f"{proc.stdout}\n{proc.stderr}"
        if proc.returncode != 0 or "Success" not in combined:
            raise AdbCommandError(
                self._base() + args, proc.returncode, proc.stdout, proc.stderr
            )

    def uninstall(self, package: str) -> None:
        """Uninstall ``package`` from the device (no error if absent)."""
        self.run("uninstall", package, check=False)
