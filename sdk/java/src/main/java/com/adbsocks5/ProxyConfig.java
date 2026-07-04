package com.adbsocks5;

/**
 * A SOCKS5 proxy endpoint the device should route traffic through.
 *
 * <p>The Android app expects a single config string in the form
 * {@code ip|port|login|password}. A no-auth proxy uses empty credentials
 * ({@code ip|port||}).
 */
public final class ProxyConfig {
    private final String ip;
    private final int port;
    private final String login;
    private final String password;

    /**
     * @param ip       proxy host/IP reachable from the device's network
     * @param port     proxy TCP port (1-65535)
     * @param login    SOCKS5 username, or empty for a no-auth proxy
     * @param password SOCKS5 password, or empty for a no-auth proxy
     */
    public ProxyConfig(String ip, int port, String login, String password)
            throws ProxyConfigException {
        String cleanIp = ip == null ? "" : ip.trim();
        if (cleanIp.isEmpty()) {
            throw new ProxyConfigException("proxy ip must not be empty");
        }
        if (cleanIp.contains("|") || cleanIp.contains("\n")) {
            throw new ProxyConfigException("proxy ip must not contain '|' or newline");
        }
        if (port < 1 || port > 65535) {
            throw new ProxyConfigException("proxy port must be in 1..65535, got " + port);
        }
        String cleanLogin = login == null ? "" : login;
        String cleanPassword = password == null ? "" : password;
        // The device splits on '|' with limit 4, so only the trailing password
        // may safely contain '|'. Everything else must be delimiter-free.
        if (cleanLogin.contains("|") || cleanLogin.contains("\n")) {
            throw new ProxyConfigException("proxy login must not contain '|' or newline");
        }
        if (cleanPassword.contains("\n")) {
            throw new ProxyConfigException("proxy password must not contain a newline");
        }
        if (cleanLogin.isEmpty() != cleanPassword.isEmpty()) {
            throw new ProxyConfigException(
                    "provide both login and password, or neither for a no-auth proxy");
        }
        this.ip = cleanIp;
        this.port = port;
        this.login = cleanLogin;
        this.password = cleanPassword;
    }

    /** Convenience constructor for a proxy without authentication. */
    public ProxyConfig(String ip, int port) throws ProxyConfigException {
        this(ip, port, "", "");
    }

    /**
     * Parse {@code ip|port|login|password}. The colon forms {@code ip:port} and
     * {@code ip:port:login:password} are also accepted for CLI convenience.
     */
    public static ProxyConfig parse(String value) throws ProxyConfigException {
        if (value == null) {
            throw new ProxyConfigException("proxy string must not be null");
        }
        String raw = value.trim();
        String[] parts = raw.contains("|") ? raw.split("\\|", 4) : raw.split(":", 4);
        if (parts.length == 2) {
            parts = new String[] {parts[0], parts[1], "", ""};
        }
        if (parts.length != 4) {
            throw new ProxyConfigException(
                    "expected ip|port|login|password (or ip:port), got "
                            + parts.length + " field(s)");
        }
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new ProxyConfigException("proxy port must be an integer, got '" + parts[1] + "'");
        }
        return new ProxyConfig(parts[0], parsedPort, parts[2], parts[3]);
    }

    public String ip() {
        return ip;
    }

    public int port() {
        return port;
    }

    public String login() {
        return login;
    }

    public String password() {
        return password;
    }

    /** Whether this proxy uses username/password authentication. */
    public boolean hasAuth() {
        return !login.isEmpty() || !password.isEmpty();
    }

    /** Render the {@code ip|port|login|password} string the app parses. */
    public String toConfigString() {
        return ip + "|" + port + "|" + login + "|" + password;
    }

    /** A log-safe representation that never exposes the password. */
    public String redacted() {
        String secret = password.isEmpty() ? "" : "<redacted:" + password.length() + ">";
        return ip + "|" + port + "|" + login + "|" + secret;
    }

    @Override
    public String toString() {
        return "ProxyConfig{" + redacted() + "}";
    }
}
