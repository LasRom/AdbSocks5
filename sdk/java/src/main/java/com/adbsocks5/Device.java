package com.adbsocks5;

/** A device reported by {@code adb devices}. */
public final class Device {
    private final String serial;
    private final String state;

    public Device(String serial, String state) {
        this.serial = serial;
        this.state = state;
    }

    public String serial() {
        return serial;
    }

    /** ADB connection state, e.g. {@code device}, {@code unauthorized}, {@code offline}. */
    public String state() {
        return state;
    }

    /** Whether the device is authorized and ready for commands. */
    public boolean isReady() {
        return "device".equals(state);
    }

    @Override
    public String toString() {
        return serial + "\t" + state;
    }
}
