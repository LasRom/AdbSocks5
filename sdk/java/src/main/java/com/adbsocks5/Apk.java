package com.adbsocks5;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locate and download the ADB SOCKS5 VPN APK.
 *
 * <p>If the app is not installed on the target device, the SDK downloads the
 * APK from the project's GitHub releases and caches it on the host so repeated
 * runs do not re-download it.
 */
public final class Apk {
    private static final Logger LOG = Logger.getLogger("adbsocks5.apk");

    public static final String PACKAGE_NAME = "com.proxy";
    public static final String GITHUB_REPO = "LasRom/AdbSocks5";
    public static final String DEFAULT_VERSION = "v0.1.0";
    public static final String DEFAULT_APK_URL =
            "https://github.com/" + GITHUB_REPO + "/releases/download/"
                    + DEFAULT_VERSION + "/app-release.apk";
    private static final String ASSET_NAME = "app-release.apk";
    private static final String USER_AGENT = "adbsocks5-java-sdk";
    private static final byte[] ZIP_MAGIC = {0x50, 0x4b, 0x03, 0x04}; // "PK\x03\x04"

    private Apk() {}

    /** Host cache directory used for downloaded APKs. */
    public static Path defaultCacheDir() {
        String override = System.getenv("ADBSOCKS5_CACHE_DIR");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        String local = System.getenv("LOCALAPPDATA"); // Windows
        if (local != null && !local.isEmpty()) {
            return Paths.get(local, "adbsocks5", "cache");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, "adbsocks5");
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "adbsocks5");
    }

    /** Resolve the {@code app-release.apk} download URL of the latest release. */
    public static String latestReleaseApkUrl() throws AdbSocks5Exception {
        String api = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
        String json;
        try {
            json = httpGetString(api, 30);
        } catch (IOException e) {
            throw new ApkDownloadException("could not query latest release of " + GITHUB_REPO, e);
        }
        // Minimal parse: find a browser_download_url that ends with the asset name.
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String url = m.group(1);
            if (url.endsWith(ASSET_NAME)) {
                return url;
            }
        }
        throw new ApkDownloadException(
                "latest release of " + GITHUB_REPO + " has no asset named " + ASSET_NAME);
    }

    /**
     * Return a local APK path, downloading it if necessary.
     *
     * @param apkPath       explicit local APK to use; skips downloading if non-null
     * @param apkUrl        direct download URL (default: pinned release asset)
     * @param cacheDir      where to store downloaded APKs (null for default)
     * @param useLatest     resolve the latest GitHub release instead of apkUrl
     * @param forceDownload re-download even if a cached copy exists
     */
    public static Path resolveApk(
            Path apkPath, String apkUrl, Path cacheDir, boolean useLatest, boolean forceDownload)
            throws AdbSocks5Exception {
        if (apkPath != null) {
            if (!Files.exists(apkPath)) {
                throw new ApkDownloadException("apkPath does not exist: " + apkPath);
            }
            if (!looksLikeApk(apkPath)) {
                throw new ApkDownloadException("apkPath is not a valid APK: " + apkPath);
            }
            return apkPath;
        }
        String url = useLatest ? latestReleaseApkUrl() : apkUrl;
        Path dest = (cacheDir != null ? cacheDir : defaultCacheDir()).resolve("app-release.apk");
        return downloadApk(url, dest, forceDownload);
    }

    /** Download the APK from {@code url} into the cache and return its path. */
    public static Path downloadApk(String url, Path dest, boolean force)
            throws AdbSocks5Exception {
        if (dest == null) {
            dest = defaultCacheDir().resolve("app-release.apk");
        }
        if (Files.exists(dest) && !force && looksLikeApk(dest)) {
            LOG.fine("using cached apk: " + dest);
            return dest;
        }

        Path tmp = null;
        try {
            Files.createDirectories(dest.getParent());
            LOG.info("downloading apk from " + url);
            // Download to a temp file first so a partial download never poses as
            // a valid cached APK.
            tmp = Files.createTempFile(dest.getParent(), "app-release", ".part");
            downloadTo(url, tmp);
            if (!looksLikeApk(tmp)) {
                throw new ApkDownloadException(
                        "downloaded file from " + url + " is not a valid APK (bad ZIP header)");
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("apk saved to " + dest);
            return dest;
        } catch (IOException e) {
            throw new ApkDownloadException("failed to download apk from " + url, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp file already moved or gone
                }
            }
        }
    }

    private static void downloadTo(String url, Path dest) throws IOException, AdbSocks5Exception {
        HttpURLConnection conn = openWithRedirects(url, 120);
        int status = conn.getResponseCode();
        if (status != 200) {
            conn.disconnect();
            throw new ApkDownloadException("unexpected HTTP status " + status + " for " + url);
        }
        try (InputStream in = conn.getInputStream();
                OutputStream out = Files.newOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Open a connection, manually following redirects (including the
     * https->https cross-host redirect GitHub uses for release assets, which
     * {@link HttpURLConnection} does not follow automatically).
     */
    private static HttpURLConnection openWithRedirects(String url, int timeoutSeconds)
            throws IOException {
        int timeoutMs = timeoutSeconds * 1000;
        String current = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    throw new IOException("redirect without Location header from " + current);
                }
                current = location;
                continue;
            }
            return conn;
        }
        throw new IOException("too many redirects downloading " + url);
    }

    private static String httpGetString(String url, int timeoutSeconds) throws IOException {
        HttpURLConnection conn;
        try {
            conn = openWithRedirects(url, timeoutSeconds);
        } catch (IOException e) {
            throw e;
        }
        try (InputStream in = conn.getInputStream()) {
            byte[] bytes = readAll(in);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean looksLikeApk(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] head = new byte[4];
            int read = in.read(head);
            if (read != 4) {
                return false;
            }
            for (int i = 0; i < 4; i++) {
                if (head[i] != ZIP_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
