package com.adbsocks5;

/** Base class for every checked error raised by the SDK. */
public class AdbSocks5Exception extends Exception {
    public AdbSocks5Exception(String message) {
        super(message);
    }

    public AdbSocks5Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
