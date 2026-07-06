package com.adbsocks5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin wrapper around the {@code adb} command-line tool.
 *
 * <p>Every call can be scoped to a specific device via its ADB serial, which is
 * how the SDK chooses <em>which</em> connected device the proxy is applied to.
 */
public final class Adb {
    private static final Logger LOG = Logger.getLogger("adbsocks5.adb");

    private final String adbPath;
    private String serial;
    private final long timeoutSeconds;

    public Adb(String adbPath, String serial, long timeoutSeconds) {
        this.adbPath = adbPath == null ? "adb" : adbPath;
        this.serial = serial;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
    }

    public String serial() {
        return serial;
    }

    void setSerial(String serial) {
        this.serial = serial;
    }

    // -- low level ---------------------------------------------------------

    private List<String> base() {
        List<String> base = new ArrayList<>();
        base.add(adbPath);
        if (serial != null && !serial.isEmpty()) {
            base.add("-s");
            base.add(serial);
        }
        return base;
    }

    /** Result of running an adb process. */
    static final class ProcResult {
        final List<String> command;
        final int exitCode;
        final String stdout;
        final String stderr;

        ProcResult(List<String> command, int exitCode, String stdout, String stderr) {
            this.command = command;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    /** Run {@code adb [-s serial] <args...>} without a host shell. */
    ProcResult run(boolean check, long timeout, String... args) throws AdbSocks5Exception {
        List<String> cmd = base();
        cmd.addAll(Arrays.asList(args));
        LOG.fine("running: " + String.join(" ", cmd));

        Process process;
        try {
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new AdbNotFoundException(
                    "could not run adb executable '" + adbPath
                            + "'; install Android platform-tools or set adbPath",
                    e);
        }

        // Drain stderr on a background thread so a full stderr buffer can never
        // deadlock the stdout read.
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errThread = new Thread(() -> copyQuietly(process.getErrorStream(), errBuf));
        errThread.setDaemon(true);
        errThread.start();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        copyQuietly(process.getInputStream(), outBuf);

        boolean finished;
        try {
            finished = process.waitFor(timeout > 0 ? timeout : timeoutSeconds, TimeUnit.SECONDS);
            errThread.join(1000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AdbSocks5Exception("interrupted while waiting for adb", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new AdbSocks5Exception(
                    "adb command timed out after " + timeout + "s: " + String.join(" ", cmd));
        }

        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        String stderr = errBuf.toString(StandardCharsets.UTF_8);
        int code = process.exitValue();
        if (check && code != 0) {
            throw new AdbCommandException(cmd, code, stdout, stderr);
        }
        return new ProcResult(cmd, code, stdout, stderr);
    }

    private static void copyQuietly(InputStream in, ByteArrayOutputStream out) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // Process ended or stream closed; whatever was read is enough.
        }
    }

    /**
     * Run a command through the device shell and return stdout.
     *
     * <p>{@code command} is passed as a single argument so device-side quoting
     * (for example the single quotes protecting the {@code |} in the config
     * string) is preserved verbatim.
     */
    public String shell(String command, boolean check) throws AdbSocks5Exception {
        return run(check, timeoutSeconds, "shell", command).stdout;
    }

    // -- device discovery --------------------------------------------------

    /** Return every device known to {@code adb devices}. */
    public static List<Device> listDevices(String adbPath) throws AdbSocks5Exception {
        Adb adb = new Adb(adbPath, null, 30);
        String out = adb.run(true, 30, "devices").stdout;
        List<Device> devices = new ArrayList<>();
        String[] lines = out.split("\r?\n");
        for (int i = 1; i < lines.length; i++) { // skip the header line
            String line = lines[i].trim();
            if (line.isEmpty() || !line.contains("\t")) {
                continue;
            }
            String[] cols = line.split("\t", 2);
            devices.add(new Device(cols[0].trim(), cols[1].trim()));
        }
        return devices;
    }

    /**
     * Return the serial this instance targets, validating availability.
     *
     * <p>If no serial was given, exactly one ready device must be connected.
     */
    public String resolveSerial() throws AdbSocks5Exception {
        List<Device> devices = listDevices(adbPath);
        List<Device> ready = new ArrayList<>();
        for (Device d : devices) {
            if (d.isReady()) {
                ready.add(d);
            }
        }

        if (serial != null && !serial.isEmpty()) {
            Device match = null;
            for (Device d : devices) {
                if (d.serial().equals(serial)) {
                    match = d;
                    break;
                }
            }
            if (match == null) {
                throw new DeviceNotFoundException(
                        "device '" + serial + "' is not connected; connected: "
                                + serialList(devices));
            }
            if (!match.isReady()) {
                throw new DeviceNotFoundException(
                        "device '" + serial + "' is in state '" + match.state()
                                + "', not 'device' (authorize USB debugging on the phone)");
            }
            return serial;
        }

        if (ready.isEmpty()) {
            if (!devices.isEmpty()) {
                throw new NoDeviceException(
                        "no ready device; connected devices are not in 'device' state: "
                                + devices);
            }
            throw new NoDeviceException("no device connected over ADB");
        }
        if (ready.size() > 1) {
            throw new MultipleDevicesException(
                    "multiple devices connected; pass a serial to choose one: "
                            + serialList(ready));
        }
        this.serial = ready.get(0).serial();
        return serial;
    }

    private static String serialList(List<Device> devices) {
        List<String> serials = new ArrayList<>();
        for (Device d : devices) {
            serials.add(d.serial());
        }
        return serials.isEmpty() ? "none" : serials.toString();
    }

    // -- package helpers ---------------------------------------------------

    /** Whether {@code pkg} is installed on the device. */
    public boolean isPackageInstalled(String pkg) throws AdbSocks5Exception {
        String out = shell("pm list packages " + pkg, true);
        for (String line : out.split("\r?\n")) {
            if (line.trim().equals("package:" + pkg)) {
                return true;
            }
        }
        return false;
    }

    /** The installed {@code versionCode} of {@code pkg}, or -1 if not installed. */
    public int packageVersionCode(String pkg) throws AdbSocks5Exception {
        if (!isPackageInstalled(pkg)) {
            return -1;
        }
        String out = shell("dumpsys package " + pkg, false);
        Matcher m = Pattern.compile("versionCode=(\\d+)").matcher(out);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /** Install (or reinstall) an APK from a host path. */
    public void install(String apkPath, boolean reinstall) throws AdbSocks5Exception {
        List<String> args = new ArrayList<>();
        args.add("install");
        if (reinstall) {
            args.add("-r");
        }
        args.add(apkPath);
        long installTimeout = Math.max(timeoutSeconds, 180);
        ProcResult result = run(false, installTimeout, args.toArray(new String[0]));
        String combined = result.stdout + "\n" + result.stderr;
        if (result.exitCode != 0 || !combined.contains("Success")) {
            throw new AdbCommandException(result.command, result.exitCode, result.stdout, result.stderr);
        }
    }

    /** Uninstall {@code pkg} from the device (no error if absent). */
    public void uninstall(String pkg) throws AdbSocks5Exception {
        run(false, timeoutSeconds, "uninstall", pkg);
    }
}
