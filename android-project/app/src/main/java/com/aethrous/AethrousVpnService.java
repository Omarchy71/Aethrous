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

public class AethrousVpnService extends VpnService {
    private static final String TAG = "AethrousVpn";
    private static final String CHANNEL_ID = "aethrous_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private static final String SOCKS5_ADDRESS = "127.0.0.1";
    private static final int SOCKS5_PORT = 1819;
    private static final String TUN_ADDRESS = "198.18.0.1";
    private static final String TUN_IPV6 = "fc00::1";
    
    private ParcelFileDescriptor vpnInterface;
    private volatile boolean isRunning = false;
    private Process aetherProcess;
    private Thread aetherThread;
    
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
                updateNotification("Connected");
                Log.i(TAG, "VPN started successfully");
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to start VPN", e);
                updateNotification("Error: " + e.getMessage());
                stopVpn();
            }
        }).start();
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
            
            ProcessBuilder pb = new ProcessBuilder(
                aetherPath,
                "--gool",
                "--scan", "balanced",
                "--noize", "balanced"
            );
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
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(SOCKS5_ADDRESS, SOCKS5_PORT), 1000);
                socket.close();
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
        builder.addAddress(TUN_IPV6, 128);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");
        builder.setMtu(8500);
        
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Could not exclude own package", e);
        }
        
        return builder.establish();
    }
    
    private String createTunnelConfig() {
        String config = 
            "tunnel:\n" +
            "  name: tun0\n" +
            "  mtu: 8500\n" +
            "  multi-queue: false\n" +
            "  ipv4: " + TUN_ADDRESS + "\n" +
            "  ipv6: '" + TUN_IPV6 + "'\n" +
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
            FileWriter writer = new FileWriter(configFile);
            writer.write(config);
            writer.close();
            return configFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write config", e);
            return null;
        }
    }
    
    private void stopVpn() {
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
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Disconnect", stopPendingIntent)
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
