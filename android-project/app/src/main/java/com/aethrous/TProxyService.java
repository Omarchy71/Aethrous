package com.aethrous;

public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String config_path, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
