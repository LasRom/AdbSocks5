"""Command-line interface for the adbsocks5 SDK.

Examples::

    adbsocks5 devices
    adbsocks5 install --serial ABCD1234
    adbsocks5 connect --serial ABCD1234 --ip 1.2.3.4 --port 1080 \
        --login user --password pass
    adbsocks5 connect --serial ABCD1234 --proxy '1.2.3.4|1080|user|pass'
    adbsocks5 status --serial ABCD1234
    adbsocks5 disconnect --serial ABCD1234
"""

from __future__ import annotations

import argparse
import logging
import sys
from typing import List, Optional

from . import __version__
from .client import AdbSocks5Client
from .errors import AdbSocks5Error
from .proxy import ProxyConfig


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="adbsocks5",
        description="Control the headless ADB SOCKS5 VPN app over ADB.",
    )
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("--adb-path", default="adb", help="path to adb (default: adb)")
    parser.add_argument("-v", "--verbose", action="store_true", help="debug logging")

    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("devices", help="list ADB devices")

    p_install = sub.add_parser("install", help="install/reinstall the app")
    _add_serial(p_install)
    p_install.add_argument("--apk", help="local APK path (skips download)")
    p_install.add_argument("--latest", action="store_true", help="use latest release")
    p_install.add_argument(
        "--force-download", action="store_true", help="re-download the APK"
    )

    p_connect = sub.add_parser("connect", help="connect the device to a proxy")
    _add_serial(p_connect)
    p_connect.add_argument("--proxy", help="ip|port|login|password (or ip:port)")
    p_connect.add_argument("--ip")
    p_connect.add_argument("--port", type=int)
    p_connect.add_argument("--login", default="")
    p_connect.add_argument("--password", default="")
    p_connect.add_argument("--apk", help="local APK path (skips download)")
    p_connect.add_argument("--latest", action="store_true", help="use latest release")
    p_connect.add_argument(
        "--no-install", action="store_true", help="fail if app is missing"
    )
    p_connect.add_argument(
        "--no-grant", action="store_true", help="do not grant VPN permission"
    )

    p_disconnect = sub.add_parser("disconnect", help="stop the VPN")
    _add_serial(p_disconnect)

    p_status = sub.add_parser("status", help="show VPN/install status")
    _add_serial(p_status)

    return parser


def _add_serial(p: argparse.ArgumentParser) -> None:
    p.add_argument(
        "-s", "--serial", help="target device serial (required if >1 device)"
    )


def _make_client(args: argparse.Namespace) -> AdbSocks5Client:
    return AdbSocks5Client(
        serial=getattr(args, "serial", None),
        adb_path=args.adb_path,
        apk_path=getattr(args, "apk", None),
    )


def _cmd_devices(args: argparse.Namespace) -> int:
    devices = AdbSocks5Client.list_devices(adb_path=args.adb_path)
    if not devices:
        print("No devices connected.")
        return 0
    for d in devices:
        print(f"{d.serial}\t{d.state}")
    return 0


def _cmd_install(args: argparse.Namespace) -> int:
    client = _make_client(args)
    path = client.install(
        apk_path=getattr(args, "apk", None),
        force_download=args.force_download,
        use_latest=args.latest,
    )
    print(f"Installed {path.name} on {client.serial}")
    return 0


def _cmd_connect(args: argparse.Namespace) -> int:
    client = _make_client(args)
    if args.proxy:
        proxy = ProxyConfig.parse(args.proxy)
    elif args.ip and args.port:
        proxy = ProxyConfig(args.ip, args.port, args.login, args.password)
    else:
        print("error: provide --proxy or both --ip and --port", file=sys.stderr)
        return 2
    client.connect(
        proxy=proxy,
        auto_install=not args.no_install,
        grant_permission=not args.no_grant,
        use_latest=args.latest,
    )
    print(f"Connected {client.serial} via {proxy.redacted()}")
    return 0


def _cmd_disconnect(args: argparse.Namespace) -> int:
    client = _make_client(args)
    client.disconnect()
    print(f"Disconnected {client.serial}")
    return 0


def _cmd_status(args: argparse.Namespace) -> int:
    client = _make_client(args)
    installed = client.is_installed()
    active = client.is_vpn_active() if installed else False
    print(f"serial:    {client.serial}")
    print(f"installed: {installed}")
    print(f"vpn_active:{active}")
    return 0


_COMMANDS = {
    "devices": _cmd_devices,
    "install": _cmd_install,
    "connect": _cmd_connect,
    "disconnect": _cmd_disconnect,
    "status": _cmd_status,
}


def main(argv: Optional[List[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    try:
        return _COMMANDS[args.command](args)
    except AdbSocks5Error as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
