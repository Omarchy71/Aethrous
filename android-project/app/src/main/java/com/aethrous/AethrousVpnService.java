package com.aethrous;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

public class AethrousVpnService extends VpnService {
    private static final String TAG = "AethrousVpn";
    private static final String CHANNEL_ID = "aethrous_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String SOCKS5_ADDRESS = "127.0.0.1";
    private static final int SOCKS5_PORT = 1819;
    private static final String TUN_ADDRESS = "198.18.0.1";
    private static final String TUN_IPV6 = "fc00::1";
    private static final int MTU = 1280;

    private ParcelFileDescriptor vpnInterface;
    private volatile boolean isRunning = false;
    private Process aetherProcess;
    private Thread aetherThread;
    private Thread monitorThread;

    private String protocol = "gool";
    private String noize = "balanced";
    private String scanMode = "balanced";
    private String ipVersion = "4";
    private boolean quickReconnect = true;
    private int keepalive = 5;
    private boolean useH2 = false;
    private boolean fragment = false;
    private String customPeer = "";
    private String wiwOuter = "";
    private String wiwInner = "";

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load hev-socks5-tunnel native library", e);
        }
    }

    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (isRunning) {
            return START_STICKY;
        }

        if (intent != null) {
            protocol = intent.getStringExtra("protocol");
            if (protocol == null) protocol = "gool";
            noize = intent.getStringExtra("noize");
            if (noize == null) noize = "balanced";
            scanMode = intent.getStringExtra("scan_mode");
            if (scanMode == null) scanMode = "balanced";
            ipVersion = intent.getStringExtra("ip_version");
            if (ipVersion == null) ipVersion = "4";
            quickReconnect = intent.getBooleanExtra("quick_reconnect", true);
            keepalive = intent.getIntExtra("keepalive", 5);
            useH2 = intent.getBooleanExtra("use_h2", false);
            fragment = intent.getBooleanExtra("fragment", false);
            customPeer = intent.getStringExtra("custom_peer");
            if (customPeer == null) customPeer = "";
            wiwOuter = intent.getStringExtra("wiw_outer");
            if (wiwOuter == null) wiwOuter = "";
            wiwInner = intent.getStringExtra("wiw_inner");
            if (wiwInner == null) wiwInner = "";
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
        startVpn();

        return START_STICKY;
    }

    private void startVpn() {
        new Thread(() -> {
            try {
                updateNotification("Starting Aether proxy...");
                if (!startAether()) {
                    throw new Exception("Failed to start Aether");
                }

                updateNotification("Waiting for proxy...");
                if (!waitForSocks5(30)) {
                    throw new Exception("Timeout waiting for SOCKS5 proxy");
                }

                updateNotification("Creating VPN interface...");
                vpnInterface = createVpnInterface();
                if (vpnInterface == null) {
                    throw new Exception("Failed to create VPN interface");
                }

                updateNotification("Starting tunnel...");
                String configPath = createTunnelConfig();
                if (configPath == null) {
                    throw new Exception("Failed to create tunnel config");
                }

                if (!TProxyStartService(configPath, vpnInterface.getFd())) {
                    throw new Exception("Failed to start tunnel");
                }

                isRunning = true;
                updateNotification("Connected (" + protocol.toUpperCase() + ")");
                Log.i(TAG, "VPN started: protocol=" + protocol + " noize=" + noize + " scan=" + scanMode);
                startMonitor();

            } catch (Exception e) {
                Log.e(TAG, "Failed to start VPN", e);
                updateNotification("Error: " + e.getMessage());
                stopVpn();
            }
        }).start();
    }

    private void startMonitor() {
        monitorThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(5000);
                    if (!TProxyIsRunning()) {
                        Log.w(TAG, "Tunnel died, attempting reconnect...");
                        updateNotification("Reconnecting...");
                        stopVpnInternal();
                        Thread.sleep(2000);
                        if (quickReconnect) {
                            startVpn();
                        }
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private boolean startAether() {
        try {
            String aetherPath = getFilesDir() + "/aether";
            File aetherFile = new File(aetherPath);

            if (!aetherFile.exists()) {
                Log.e(TAG, "Aether binary not found: " + aetherPath);
                return false;
            }

            aetherFile.setExecutable(true);

            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(aetherPath);

            cmd.add("--" + protocol);

            cmd.add("--scan");
            cmd.add(scanMode);

            cmd.add("-" + ipVersion);

            cmd.add("--keepalive");
            cmd.add(String.valueOf(keepalive));

            if (quickReconnect) {
                cmd.add("--quick-reconnect");
            } else {
                cmd.add("--no-quick-reconnect");
            }

            if (useH2 && "masque".equals(protocol)) {
                cmd.add("--h2");
                if (fragment) {
                    cmd.add("--fragment");
                }
            }

            if (!"off".equals(noize)) {
                cmd.add("--noize");
                cmd.add(noize);
            }

            if (!customPeer.isEmpty()) {
                cmd.add("--peer");
                cmd.add(customPeer);
            }

            if (!wiwOuter.isEmpty()) {
                cmd.add("--wiw-outer");
                cmd.add(wiwOuter);
            }

            if (!wiwInner.isEmpty()) {
                cmd.add("--wiw-inner");
                cmd.add(wiwInner);
            }

            Log.i(TAG, "Aether command: " + cmd.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());

            aetherProcess = pb.start();

            aetherThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(aetherProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "Aether: " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading Aether output", e);
                }
            });
            aetherThread.setDaemon(true);
            aetherThread.start();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Aether", e);
            return false;
        }
    }

    private boolean waitForSocks5(int maxSeconds) {
        for (int i = 0; i < maxSeconds; i++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(SOCKS5_ADDRESS, SOCKS5_PORT), 1000);
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private ParcelFileDescriptor createVpnInterface() {
        Builder builder = new Builder();
        builder.setSession("Aethrous");
        builder.addAddress(TUN_ADDRESS, 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");
        builder.setMtu(MTU);

        if ("6".equals(ipVersion) || "dual".equals(ipVersion)) {
            builder.addAddress(TUN_IPV6, 128);
        }

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Could not exclude own package", e);
        }

        return builder.establish();
    }

    private String createTunnelConfig() {
        String ipv6Line = ("6".equals(ipVersion) || "dual".equals(ipVersion))
            ? "  ipv6: '" + TUN_IPV6 + "'\n" : "";

        String config =
            "tunnel:\n" +
            "  name: tun0\n" +
            "  mtu: " + MTU + "\n" +
            "  multi-queue: false\n" +
            "  ipv4: " + TUN_ADDRESS + "\n" +
            ipv6Line +
            "  icmp: 'off'\n" +
            "\n" +
            "socks5:\n" +
            "  address: " + SOCKS5_ADDRESS + "\n" +
            "  port: " + SOCKS5_PORT + "\n" +
            "  udp: 'udp'\n" +
            "  mark: 438\n" +
            "\n" +
            "misc:\n" +
            "  log-level: warn\n";

        try {
            File configFile = new File(getFilesDir(), "tunnel.yml");
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(config);
            }
            return configFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write config", e);
            return null;
        }
    }

    private void stopVpnInternal() {
        isRunning = false;

        try {
            TProxyStopService();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping tunnel", e);
        }

        if (aetherProcess != null) {
            aetherProcess.destroy();
            aetherProcess = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close VPN interface", e);
            }
            vpnInterface = null;
        }
    }

    private void stopVpn() {
        stopVpnInternal();
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "VPN stopped");
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Aethrous",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Aethrous VPN Service");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, AethrousVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aethrous")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_vpn_key, "Disconnect", stopPendingIntent)
            .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
        }

        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
