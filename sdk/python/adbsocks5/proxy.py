"""SOCKS5 proxy configuration for the ADB SOCKS5 VPN app.

The Android app expects a single config string in the form::

    ip|port|login|password

A proxy without authentication is expressed with empty login/password::

    ip|port||
"""

from __future__ import annotations

from dataclasses import dataclass

from .errors import ProxyConfigError


@dataclass(frozen=True)
class ProxyConfig:
    """A SOCKS5 proxy endpoint the device should route traffic through.

    Args:
        ip: Proxy host/IP reachable *from the Android device's network*.
        port: Proxy TCP port (1-65535).
        login: SOCKS5 username, or empty string for no-auth proxies.
        password: SOCKS5 password, or empty string for no-auth proxies.
    """

    ip: str
    port: int
    login: str = ""
    password: str = ""

    def __post_init__(self) -> None:
        ip = (self.ip or "").strip()
        if not ip:
            raise ProxyConfigError("proxy ip must not be empty")
        if "|" in ip or "\n" in ip:
            raise ProxyConfigError("proxy ip must not contain '|' or newline")
        object.__setattr__(self, "ip", ip)

        try:
            port = int(self.port)
        except (TypeError, ValueError):
            raise ProxyConfigError(f"proxy port must be an integer, got {self.port!r}")
        if not 1 <= port <= 65535:
            raise ProxyConfigError(f"proxy port must be in 1..65535, got {port}")
        object.__setattr__(self, "port", port)

        login = self.login or ""
        password = self.password or ""
        # The device splits the config on '|' with limit 4, so only the
        # trailing password field may safely contain a '|'. Everything else
        # must be free of the delimiter and of newlines.
        if "|" in login or "\n" in login:
            raise ProxyConfigError("proxy login must not contain '|' or newline")
        if "\n" in password:
            raise ProxyConfigError("proxy password must not contain a newline")
        # An auth proxy needs both fields; reject a half-filled pair early so
        # the failure is a clear config error instead of a device-side reset.
        if bool(login) != bool(password):
            raise ProxyConfigError(
                "provide both login and password, or neither for a no-auth proxy"
            )
        object.__setattr__(self, "login", login)
        object.__setattr__(self, "password", password)

    @property
    def has_auth(self) -> bool:
        """Whether this proxy uses username/password authentication."""
        return bool(self.login) or bool(self.password)

    def to_config_string(self) -> str:
        """Render the ``ip|port|login|password`` string the app parses."""
        return f"{self.ip}|{self.port}|{self.login}|{self.password}"

    def redacted(self) -> str:
        """A log-safe representation that never exposes the password."""
        secret = f"<redacted:{len(self.password)}>" if self.password else ""
        return f"{self.ip}|{self.port}|{self.login}|{secret}"

    @classmethod
    def parse(cls, value: str) -> "ProxyConfig":
        """Build a :class:`ProxyConfig` from ``ip|port|login|password``.

        ``ip:port`` and ``ip:port:login:password`` (colon separated) forms are
        also accepted for convenience on the command line.
        """
        if value is None:
            raise ProxyConfigError("proxy string must not be None")
        raw = value.strip()
        if "|" in raw:
            parts = raw.split("|", 3)
        else:
            parts = raw.split(":", 3)
        if len(parts) == 2:
            parts = [parts[0], parts[1], "", ""]
        if len(parts) != 4:
            raise ProxyConfigError(
                "expected ip|port|login|password (or ip:port), "
                f"got {len(parts)} field(s)"
            )
        return cls(parts[0], parts[1], parts[2], parts[3])
