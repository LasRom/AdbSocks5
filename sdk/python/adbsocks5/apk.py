"""Locate and download the ADB SOCKS5 VPN APK.

If the app is not installed on the target device, the SDK downloads the APK
from the project's GitHub releases and caches it on the host so repeated runs
do not re-download it.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import urllib.request
from pathlib import Path
from typing import Optional
from urllib.error import URLError

from .errors import ApkDownloadError

log = logging.getLogger("adbsocks5.apk")

PACKAGE_NAME = "com.proxy"
GITHUB_REPO = "LasRom/AdbSocks5"
DEFAULT_VERSION = "v1.0.0"
DEFAULT_APK_URL = (
    f"https://github.com/{GITHUB_REPO}/releases/download/"
    f"{DEFAULT_VERSION}/app-release.apk"
)

# APK files are ZIP archives; every valid one starts with this magic.
_ZIP_MAGIC = b"PK\x03\x04"
_USER_AGENT = "adbsocks5-python-sdk"


def default_cache_dir() -> Path:
    """Return the host cache directory used for downloaded APKs."""
    env = os.environ.get("ADBSOCKS5_CACHE_DIR")
    if env:
        return Path(env)
    local = os.environ.get("LOCALAPPDATA")
    if local:  # Windows
        return Path(local) / "adbsocks5" / "cache"
    xdg = os.environ.get("XDG_CACHE_HOME")
    if xdg:
        return Path(xdg) / "adbsocks5"
    return Path.home() / ".cache" / "adbsocks5"


def latest_release_apk_url(
    repo: str = GITHUB_REPO,
    asset_name: str = "app-release.apk",
    timeout: float = 30.0,
) -> str:
    """Resolve the ``app-release.apk`` download URL of the latest release.

    Raises :class:`ApkDownloadError` if the release or asset cannot be found.
    """
    api = f"https://api.github.com/repos/{repo}/releases/latest"
    req = urllib.request.Request(api, headers={"User-Agent": _USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.load(resp)
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ApkDownloadError(
            f"could not query latest release of {repo}: {exc}"
        ) from exc
    for asset in data.get("assets", []):
        if asset.get("name") == asset_name:
            return asset["browser_download_url"]
    raise ApkDownloadError(
        f"latest release of {repo} has no asset named {asset_name!r}"
    )


def _looks_like_apk(path: Path) -> bool:
    try:
        with open(path, "rb") as fh:
            return fh.read(4) == _ZIP_MAGIC
    except OSError:
        return False


def download_apk(
    url: str = DEFAULT_APK_URL,
    dest: Optional[Path] = None,
    force: bool = False,
    timeout: float = 120.0,
) -> Path:
    """Download the APK from ``url`` into the host cache and return its path.

    A cached, valid APK at ``dest`` is reused unless ``force`` is set.
    """
    if dest is None:
        dest = default_cache_dir() / "app-release.apk"
    dest = Path(dest)

    if dest.exists() and not force and _looks_like_apk(dest):
        log.debug("using cached apk: %s", dest)
        return dest

    dest.parent.mkdir(parents=True, exist_ok=True)
    log.info("downloading apk from %s", url)
    req = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    # Download to a temp file first so a partial download never poses as a
    # valid cached APK.
    tmp_fd, tmp_name = tempfile.mkstemp(dir=str(dest.parent), suffix=".part")
    tmp_path = Path(tmp_name)
    try:
        with os.fdopen(tmp_fd, "wb") as out:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if getattr(resp, "status", 200) not in (200, None):
                    raise ApkDownloadError(
                        f"unexpected HTTP status {resp.status} for {url}"
                    )
                while True:
                    chunk = resp.read(64 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
    except (URLError, TimeoutError) as exc:
        tmp_path.unlink(missing_ok=True)
        raise ApkDownloadError(f"failed to download apk from {url}: {exc}") from exc
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise

    if not _looks_like_apk(tmp_path):
        tmp_path.unlink(missing_ok=True)
        raise ApkDownloadError(
            f"downloaded file from {url} is not a valid APK (bad ZIP header)"
        )

    os.replace(tmp_path, dest)
    log.info("apk saved to %s", dest)
    return dest


def resolve_apk(
    apk_path: Optional[Path] = None,
    apk_url: str = DEFAULT_APK_URL,
    cache_dir: Optional[Path] = None,
    use_latest: bool = False,
    force_download: bool = False,
) -> Path:
    """Return a local APK path, downloading it if necessary.

    Args:
        apk_path: An explicit local APK to use; skips downloading entirely.
        apk_url: Direct download URL (default: pinned release asset).
        cache_dir: Where to store downloaded APKs.
        use_latest: Resolve the latest GitHub release instead of ``apk_url``.
        force_download: Re-download even if a cached copy exists.
    """
    if apk_path is not None:
        path = Path(apk_path)
        if not path.exists():
            raise ApkDownloadError(f"apk_path does not exist: {path}")
        if not _looks_like_apk(path):
            raise ApkDownloadError(f"apk_path is not a valid APK: {path}")
        return path

    if use_latest:
        apk_url = latest_release_apk_url()
    dest = None
    if cache_dir is not None:
        dest = Path(cache_dir) / "app-release.apk"
    return download_apk(apk_url, dest=dest, force=force_download)
