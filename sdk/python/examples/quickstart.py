"""Minimal end-to-end example for the adbsocks5 SDK.

Usage:
    python quickstart.py <serial> <ip> <port> [login] [password]

If no serial is given and exactly one device is connected, that device is used.
Proxy credentials are read from argv here for demonstration only; in real code
prefer environment variables so secrets are not stored in shell history.
"""

import sys

from adbsocks5 import AdbSocks5Client, ProxyConfig


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2

    serial = sys.argv[1] or None
    ip = sys.argv[2]
    port = int(sys.argv[3])
    login = sys.argv[4] if len(sys.argv) > 4 else ""
    password = sys.argv[5] if len(sys.argv) > 5 else ""

    client = AdbSocks5Client(serial=serial)

    # Downloads + installs the APK automatically if it is missing.
    proxy = ProxyConfig(ip=ip, port=port, login=login, password=password)
    client.connect(proxy=proxy)

    print(f"VPN active on {client.serial}: {client.is_vpn_active()}")
    print("Run `python quickstart.py ... ` then verify with:")
    print(f"  adb -s {client.serial} shell curl -4 http://api.ipify.org")
    print("Stop the VPN with client.disconnect().")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
