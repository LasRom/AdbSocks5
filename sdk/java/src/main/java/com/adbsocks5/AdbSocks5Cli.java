package com.adbsocks5;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line interface for the Java SDK.
 *
 * <pre>
 * adbsocks5 devices
 * adbsocks5 install    --serial ABCD1234
 * adbsocks5 connect    --serial ABCD1234 --ip 1.2.3.4 --port 1080 --login u --password p
 * adbsocks5 connect    --serial ABCD1234 --proxy '1.2.3.4|1080|u|p'
 * adbsocks5 status     --serial ABCD1234
 * adbsocks5 disconnect --serial ABCD1234
 * </pre>
 */
public final class AdbSocks5Cli {

    private AdbSocks5Cli() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 2;
        }
        String command = args[0];
        Map<String, String> opts = new HashMap<>();
        java.util.Set<String> flags = new java.util.HashSet<>();
        parseOptions(args, opts, flags);

        String adbPath = opts.getOrDefault("adb-path", "adb");
        String serial = opts.get("serial");

        try {
            switch (command) {
                case "devices":
                    return cmdDevices(adbPath);
                case "install":
                    return cmdInstall(newClient(serial, adbPath, opts), opts, flags);
                case "connect":
                    return cmdConnect(newClient(serial, adbPath, opts), opts, flags);
                case "disconnect":
                    return cmdDisconnect(newClient(serial, adbPath, opts));
                case "status":
                    return cmdStatus(newClient(serial, adbPath, opts));
                case "-h":
                case "--help":
                case "help":
                    printUsage();
                    return 0;
                default:
                    System.err.println("error: unknown command '" + command + "'");
                    printUsage();
                    return 2;
            }
        } catch (AdbSocks5Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static AdbSocks5Client newClient(String serial, String adbPath, Map<String, String> opts) {
        AdbSocks5Client client = new AdbSocks5Client(serial, adbPath, 60);
        String apk = opts.get("apk");
        if (apk != null) {
            client.withApkPath(Paths.get(apk));
        }
        return client;
    }

    private static int cmdDevices(String adbPath) throws AdbSocks5Exception {
        List<Device> devices = AdbSocks5Client.listDevices(adbPath);
        if (devices.isEmpty()) {
            System.out.println("No devices connected.");
            return 0;
        }
        for (Device d : devices) {
            System.out.println(d.serial() + "\t" + d.state());
        }
        return 0;
    }

    private static int cmdInstall(AdbSocks5Client client, Map<String, String> opts,
            java.util.Set<String> flags) throws AdbSocks5Exception {
        java.nio.file.Path path =
                client.install(flags.contains("force-download"), flags.contains("latest"));
        System.out.println("Installed " + path.getFileName() + " on " + client.serial());
        return 0;
    }

    private static int cmdConnect(AdbSocks5Client client, Map<String, String> opts,
            java.util.Set<String> flags) throws AdbSocks5Exception {
        ProxyConfig proxy;
        if (opts.get("proxy") != null) {
            proxy = ProxyConfig.parse(opts.get("proxy"));
        } else if (opts.get("ip") != null && opts.get("port") != null) {
            proxy = new ProxyConfig(
                    opts.get("ip"),
                    Integer.parseInt(opts.get("port")),
                    opts.getOrDefault("login", ""),
                    opts.getOrDefault("password", ""));
        } else {
            System.err.println("error: provide --proxy or both --ip and --port");
            return 2;
        }
        client.connect(
                proxy,
                !flags.contains("no-install"),
                !flags.contains("no-grant"),
                flags.contains("latest"));
        System.out.println("Connected " + client.serial() + " via " + proxy.redacted());
        return 0;
    }

    private static int cmdDisconnect(AdbSocks5Client client) throws AdbSocks5Exception {
        client.disconnect();
        System.out.println("Disconnected " + client.serial());
        return 0;
    }

    private static int cmdStatus(AdbSocks5Client client) throws AdbSocks5Exception {
        boolean installed = client.isInstalled();
        boolean active = installed && client.isVpnActive();
        System.out.println("serial:    " + client.serial());
        System.out.println("installed: " + installed);
        System.out.println("vpn_active:" + active);
        return 0;
    }

    /** Parse {@code --key value} options and {@code --flag} booleans from argv. */
    private static void parseOptions(String[] args, Map<String, String> opts,
            java.util.Set<String> flags) {
        java.util.Set<String> booleanFlags = new java.util.HashSet<>(java.util.Arrays.asList(
                "latest", "force-download", "no-install", "no-grant"));
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-s")) {
                if (i + 1 < args.length) {
                    opts.put("serial", args[++i]);
                }
            } else if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (booleanFlags.contains(key)) {
                    flags.add(key);
                } else if (i + 1 < args.length) {
                    opts.put(key, args[++i]);
                }
            }
        }
    }

    private static void printUsage() {
        System.out.println(
                "Usage: adbsocks5 <command> [options]\n\n"
                        + "Commands:\n"
                        + "  devices                       list ADB devices\n"
                        + "  install    [-s serial]        install/reinstall the app\n"
                        + "  connect    [-s serial] ...    connect device to a proxy\n"
                        + "  disconnect [-s serial]        stop the VPN\n"
                        + "  status     [-s serial]        show install/VPN status\n\n"
                        + "Options:\n"
                        + "  -s, --serial <serial>   target device (required if >1 device)\n"
                        + "  --adb-path <path>       path to adb (default: adb)\n"
                        + "  --proxy <ip|port|login|password>\n"
                        + "  --ip <ip> --port <port> [--login <l>] [--password <p>]\n"
                        + "  --apk <path>            local APK (skips download)\n"
                        + "  --latest                use latest GitHub release\n"
                        + "  --force-download        re-download the APK\n"
                        + "  --no-install            fail if app missing (connect)\n"
                        + "  --no-grant              do not grant VPN permission (connect)");
    }
}
