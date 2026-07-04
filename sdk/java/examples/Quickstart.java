import com.adbsocks5.AdbSocks5Client;
import com.adbsocks5.ProxyConfig;

/**
 * Minimal end-to-end example for the adbsocks5 Java SDK.
 *
 * <pre>
 * javac -cp ../out -d ../out Quickstart.java
 * java  -cp ../out Quickstart &lt;serial&gt; &lt;ip&gt; &lt;port&gt; [login] [password]
 * </pre>
 *
 * If the serial is "-" (or empty) and exactly one device is connected, that
 * device is used. Credentials are read from argv here for demonstration only;
 * in real code prefer environment variables.
 */
public class Quickstart {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: Quickstart <serial|-> <ip> <port> [login] [password]");
            System.exit(2);
        }
        String serial = args[0].equals("-") ? null : args[0];
        String ip = args[1];
        int port = Integer.parseInt(args[2]);
        String login = args.length > 3 ? args[3] : "";
        String password = args.length > 4 ? args[4] : "";

        AdbSocks5Client client = new AdbSocks5Client(serial);

        // Downloads + installs the APK automatically if it is missing.
        ProxyConfig proxy = new ProxyConfig(ip, port, login, password);
        client.connect(proxy);

        System.out.println("VPN active on " + client.serial() + ": " + client.isVpnActive());
        System.out.println("Verify with:");
        System.out.println("  adb -s " + client.serial() + " shell curl -4 http://api.ipify.org");
        System.out.println("Stop the VPN with client.disconnect().");
    }
}
