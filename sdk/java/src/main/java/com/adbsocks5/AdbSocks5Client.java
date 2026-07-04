package com.adbsocks5;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * High-level client for controlling the headless ADB SOCKS5 VPN app over ADB.
 *
 * <pre>{@code
 * AdbSocks5Client client = new AdbSocks5Client("ABCD1234"); // pick a device
 * client.connect(new ProxyConfig("1.2.3.4", 1080, "user", "pass"));
 * ...
 * client.disconnect();
 * }</pre>
 *
 * <p>If the app is not installed on the chosen device, {@link #connect} downloads
 * the APK from GitHub releases and installs it automatically.
 */
public final class AdbSocks5Client {
    private static final Logger LOG = Logger.getLogger("adbsocks5.client");

    public static final String PACKAGE = Apk.PACKAGE_NAME;
    private static final String COMPONENT = PACKAGE + "/.AdbCommandService";
    private static final String ACTION_START = PACKAGE + ".START";
    private static final String ACTION_STOP = PACKAGE + ".STOP";
    private static final String VPN_SESSION_NAME = "ADB SOCKS5 VPN";

    private final Adb adb;
    private Path apkPath;
    private String apkUrl = Apk.DEFAULT_APK_URL;
    private Path cacheDir;

    /**
     * @param serial ADB serial of the target device. If null, exactly one ready
     *               device must be connected (so the proxy is never applied to
     *               the wrong phone).
     */
    public AdbSocks5Client(String serial) {
        this(serial, "adb", 60);
    }

    public AdbSocks5Client(String serial, String adbPath, long timeoutSeconds) {
        this.adb = new Adb(adbPath, serial, timeoutSeconds);
    }

    // -- builder-style configuration --------------------------------------

    /** Use an explicit local APK instead of downloading. */
    public AdbSocks5Client withApkPath(Path apkPath) {
        this.apkPath = apkPath;
        return this;
    }

    /** Override the download URL used when the app must be fetched. */
    public AdbSocks5Client withApkUrl(String apkUrl) {
        this.apkUrl = apkUrl;
        return this;
    }

    /** Override the host directory for cached APK downloads. */
    public AdbSocks5Client withCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir;
        return this;
    }

    // -- device ------------------------------------------------------------

    public String serial() {
        return adb.serial();
    }

    /** List all devices ADB currently sees (ready or not). */
    public static List<Device> listDevices(String adbPath) throws AdbSocks5Exception {
        return Adb.listDevices(adbPath);
    }

    /** Validate and return the target serial, pinning it for later calls. */
    public String resolveDevice() throws AdbSocks5Exception {
        return adb.resolveSerial();
    }

    // -- installation ------------------------------------------------------

    /** Whether the SOCKS5 VPN app is installed on the target device. */
    public boolean isInstalled() throws AdbSocks5Exception {
        resolveDevice();
        return adb.isPackageInstalled(PACKAGE);
    }

    /**
     * Install the app, downloading the APK if no local path is available.
     *
     * @return the host path of the APK that was installed
     */
    public Path install(boolean forceDownload, boolean useLatest) throws AdbSocks5Exception {
        resolveDevice();
        Path path = Apk.resolveApk(apkPath, apkUrl, cacheDir, useLatest, forceDownload);
        LOG.info("installing " + path.getFileName() + " on " + serial());
        adb.install(path.toString(), true);
        return path;
    }

    /**
     * Install the app only if it is missing.
     *
     * @return true if an installation was performed, false if already present
     */
    public boolean ensureInstalled(boolean useLatest) throws AdbSocks5Exception {
        if (isInstalled()) {
            LOG.fine("app already installed on " + serial());
            return false;
        }
        LOG.info("app not installed on " + serial() + ", installing");
        install(false, useLatest);
        return true;
    }

    // -- permission --------------------------------------------------------

    /** Grant the VPN consent via {@code appops} (headless app has no UI). */
    public void grantVpnPermission() throws AdbSocks5Exception {
        resolveDevice();
        adb.shell("appops set " + PACKAGE + " ACTIVATE_VPN allow", true);
    }

    // -- connect / disconnect ---------------------------------------------

    /** Route the device's traffic through {@code proxy} using default options. */
    public ProxyConfig connect(ProxyConfig proxy) throws AdbSocks5Exception {
        return connect(proxy, true, true, false);
    }

    /**
     * Route the device's traffic through the given SOCKS5 proxy.
     *
     * <p>Steps: resolve/validate the device, install the app if missing (when
     * {@code autoInstall}), grant VPN permission (when {@code grantPermission}),
     * then send the START command with the config string.
     *
     * @return the {@link ProxyConfig} that was applied
     */
    public ProxyConfig connect(
            ProxyConfig proxy, boolean autoInstall, boolean grantPermission, boolean useLatest)
            throws AdbSocks5Exception {
        if (proxy == null) {
            throw new VpnException("connect requires a non-null ProxyConfig");
        }
        resolveDevice();
        if (autoInstall) {
            ensureInstalled(useLatest);
        } else if (!isInstalled()) {
            throw new VpnException("app " + PACKAGE + " is not installed and autoInstall=false");
        }
        if (grantPermission) {
            grantVpnPermission();
        }

        String quoted = shellSingleQuote(proxy.toConfigString());
        String command = "am start-foreground-service -n " + COMPONENT
                + " -a " + ACTION_START + " --es config " + quoted;
        LOG.info("connecting " + serial() + " via " + proxy.redacted());
        String out = adb.shell(command, true);
        if (out.contains("Error") || out.contains("Exception")) {
            throw new VpnException("START command failed on device: " + out.trim());
        }
        return proxy;
    }

    /** Stop the VPN and tear down the TUN interface on the device. */
    public void disconnect() throws AdbSocks5Exception {
        resolveDevice();
        String command = "am start-foreground-service -n " + COMPONENT + " -a " + ACTION_STOP;
        LOG.info("disconnecting " + serial());
        String out = adb.shell(command, true);
        if (out.contains("Error") || out.contains("Exception")) {
            throw new VpnException("STOP command failed on device: " + out.trim());
        }
    }

    // -- status ------------------------------------------------------------

    /** Whether this app's VPN is currently up on the device. */
    public boolean isVpnActive() throws AdbSocks5Exception {
        resolveDevice();
        String out = adb.shell("dumpsys connectivity", false);
        return out.contains(VPN_SESSION_NAME) || out.contains("owner=" + PACKAGE);
    }

    /** Quote a value for the device-side shell (POSIX single-quote rules). */
    static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public String toString() {
        return "AdbSocks5Client{serial=" + serial() + "}";
    }
}
