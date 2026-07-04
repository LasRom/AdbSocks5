package com.adbsocks5;

import java.util.List;

/**
 * Container for the SDK's specific exception types. Grouping the small
 * subclasses here keeps the public API discoverable without one file each.
 */
final class Exceptions {
    private Exceptions() {}
}

/** The {@code adb} executable could not be located on the host. */
class AdbNotFoundException extends AdbSocks5Exception {
    AdbNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** An {@code adb} command exited with a non-zero status. */
class AdbCommandException extends AdbSocks5Exception {
    final List<String> commandLine;
    final int exitCode;
    final String stdout;
    final String stderr;

    AdbCommandException(List<String> commandLine, int exitCode, String stdout, String stderr) {
        super(buildMessage(commandLine, exitCode, stdout, stderr));
        this.commandLine = commandLine;
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    private static String buildMessage(List<String> cmd, int code, String out, String err) {
        String detail = (err == null || err.isEmpty()) ? out : err;
        String message = "adb command failed (exit " + code + "): " + String.join(" ", cmd);
        if (detail != null && !detail.trim().isEmpty()) {
            message += "\n" + detail.trim();
        }
        return message;
    }
}

/** No usable device is connected over ADB. */
class NoDeviceException extends AdbSocks5Exception {
    NoDeviceException(String message) {
        super(message);
    }
}

/** A specific device serial was requested but is not connected/ready. */
class DeviceNotFoundException extends AdbSocks5Exception {
    DeviceNotFoundException(String message) {
        super(message);
    }
}

/** Multiple devices are connected and no serial was provided. */
class MultipleDevicesException extends AdbSocks5Exception {
    MultipleDevicesException(String message) {
        super(message);
    }
}

/** The APK could not be downloaded or is not a valid package. */
class ApkDownloadException extends AdbSocks5Exception {
    ApkDownloadException(String message) {
        super(message);
    }

    ApkDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** The proxy configuration is invalid. */
class ProxyConfigException extends AdbSocks5Exception {
    ProxyConfigException(String message) {
        super(message);
    }
}

/** Starting or stopping the VPN failed on the device. */
class VpnException extends AdbSocks5Exception {
    VpnException(String message) {
        super(message);
    }
}
