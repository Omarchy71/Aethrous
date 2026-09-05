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
    private static final int MTU = 1280;

    private ParcelFileDescriptor vpnInterface;
    private volatile boolean isRunning = false;
    private Process aetherProcess;

    private String protocol = "gool";
    private String noize = "balanced";
    private String scanMode = "balanced";

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
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        new Thread(() -> {
            try {
                updateNotification("Extracting Aether...");
                if (!extractAether()) {
                    throw new Exception("Failed to extract Aether binary");
                }

                updateNotification("Starting Aether proxy...");
                if (!startAether()) {
                    throw new Exception("Failed to start Aether");
                }

                updateNotification("Waiting for SOCKS5 proxy...");
                if (!waitForSocks5(30)) {
                    throw new Exception("Timeout waiting for SOCKS5 proxy");
                }

                updateNotification("Creating VPN interface...");
                vpnInterface = createVpnInterface();
                if (vpnInterface == null) {
                    throw new Exception("Failed to create VPN interface");
                }

                updateNotification("Starting tun2socks...");
                String configPath = createTunConfig();
                if (configPath == null) {
                    throw new Exception("Failed to create tun2socks config");
                }

                int tunFd = vpnInterface.getFd();
                Log.i(TAG, "Starting tun2socks with TUN fd: " + tunFd);

                if (!TProxyService.TProxyStartService(configPath, tunFd)) {
                    throw new Exception("Failed to start tun2socks");
                }

                isRunning = true;
                updateNotification("Connected (" + protocol.toUpperCase() + ")");
                Log.i(TAG, "VPN started successfully");

            } catch (Exception e) {
                Log.e(TAG, "Failed to start VPN", e);
                updateNotification("Error: " + e.getMessage());
                stopVpn();
            }
        }).start();
    }

    private boolean extractAether() {
        try {
            File filesDir = getFilesDir();
            String arch = System.getProperty("os.arch", "");
            String archSuffix;

            if (arch.contains("aarch64") || arch.contains("arm64")) {
                archSuffix = "arm64";
            } else if (arch.contains("arm")) {
                archSuffix = "armv7";
            } else if (arch.contains("x86_64") || arch.contains("amd64")) {
                archSuffix = "x86_64";
            } else {
                archSuffix = "arm64";
            }

            String assetName = "aether-" + archSuffix;
            File dstFile = new File(filesDir, "aether");

            if (!dstFile.exists()) {
                java.io.InputStream in = getAssets().open(assetName);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dstFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            }
            dstFile.setExecutable(true);
            Log.i(TAG, "Extracted Aether for " + archSuffix);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract Aether", e);
            return false;
        }
    }

    private boolean startAether() {
        try {
            String aetherPath = getFilesDir() + "/aether";
            File aetherFile = new File(aetherPath);
            if (!aetherFile.exists()) {
                Log.e(TAG, "Aether binary not found");
                return false;
            }

            ArrayList<String> cmd = new ArrayList<>();
            cmd.add(aetherPath);
            cmd.add("--" + protocol);
            cmd.add("--scan");
            cmd.add(scanMode);
            cmd.add("-4");
            cmd.add("--keepalive");
            cmd.add("5");
            cmd.add("--quick-reconnect");

            if (!"off".equals(noize)) {
                cmd.add("--noize");
                cmd.add(noize);
            }

            Log.i(TAG, "Starting Aether: " + cmd.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());

            aetherProcess = pb.start();

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(aetherProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, "Aether: " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading Aether output", e);
                }
            });
            reader.setDaemon(true);
            reader.start();

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

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Could not exclude own package", e);
        }

        return builder.establish();
    }

    private String createTunConfig() {
        String config =
            "tunnel:\n" +
            "  mtu: " + MTU + "\n" +
            "  ipv4: " + TUN_ADDRESS + "\n" +
            "socks5:\n" +
            "  port: " + SOCKS5_PORT + "\n" +
            "  address: '" + SOCKS5_ADDRESS + "'\n" +
            "  udp: 'udp'\n";

        try {
            File configFile = new File(getFilesDir(), "hev-socks5.conf");
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(config);
            }
            return configFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write tun2socks config", e);
            return null;
        }
    }

    private void stopVpn() {
        isRunning = false;

        try {
            TProxyService.TProxyStopService();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping tun2socks", e);
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
                CHANNEL_ID, "Aethrous", NotificationManager.IMPORTANCE_LOW);
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
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AethrousVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
