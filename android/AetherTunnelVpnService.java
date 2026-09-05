package com.aethertunnel;

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

import java.io.File;
import java.io.IOException;

/**
 * Android VPN Service that combines Aether and hev-socks5-tunnel
 * 
 * Architecture:
 * 1. Starts Aether to get SOCKS5 proxy
 * 2. Creates VPN TUN interface
 * 3. Routes traffic through TUN -> hev-socks5-tunnel -> SOCKS5 -> Aether -> Internet
 */
public class AetherTunnelVpnService extends VpnService {
    private static final String TAG = "AetherTunnelVpn";
    private static final String CHANNEL_ID = "aether_tunnel_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private static final String SOCKS5_ADDRESS = "127.0.0.1";
    private static final int SOCKS5_PORT = 1819;
    private static final String TUN_ADDRESS = "198.18.0.1";
    private static final String TUN_IPV6 = "fc00::1";
    
    private ParcelFileDescriptor vpnInterface;
    private boolean isRunning = false;
    
    // Native JNI methods for hev-socks5-tunnel
    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();
    
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
    
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
                // Step 1: Start Aether (SOCKS5 proxy)
                updateNotification("Starting Aether proxy...");
                if (!startAether()) {
                    throw new Exception("Failed to start Aether");
                }
                
                // Step 2: Wait for SOCKS5 to be ready
                updateNotification("Waiting for proxy...");
                if (!waitForSocks5(30)) {
                    throw new Exception("Timeout waiting for SOCKS5 proxy");
                }
                
                // Step 3: Create VPN interface
                updateNotification("Creating VPN interface...");
                vpnInterface = createVpnInterface();
                if (vpnInterface == null) {
                    throw new Exception("Failed to create VPN interface");
                }
                
                // Step 4: Start hev-socks5-tunnel
                updateNotification("Starting tunnel...");
                String configPath = createTunnelConfig();
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
            String aetherPath = getApplicationInfo().nativeLibraryDir + "/libaether.so";
            File aetherFile = new File(aetherPath);
            if (!aetherFile.exists()) {
                Log.e(TAG, "Aether binary not found: " + aetherPath);
                return false;
            }
            
            // Start Aether process
            ProcessBuilder pb = new ProcessBuilder(
                aetherPath,
                "--masque",
                "--scan", "balanced",
                "--port", String.valueOf(SOCKS5_PORT)
            );
            pb.redirectErrorStream(true);
            pb.start();
            
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
                    return false;
                }
            }
        }
        return false;
    }
    
    private ParcelFileDescriptor createVpnInterface() {
        Builder builder = new Builder();
        builder.setSession("Aether Tunnel");
        builder.addAddress(TUN_ADDRESS, 32);
        builder.addAddress(TUN_IPV6, 128);
        builder.addRoute("0.0.0.0", 0);
        builder.addRoute("::", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");
        builder.addDnsServer("2001:4860:4860::8888");
        builder.setMtu(8500);
        builder.setBlocking(true);
        
        // Exclude our own app from VPN
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
            "  mark: 438\n";
        
        try {
            File configFile = new File(getFilesDir(), "tunnel.yml");
            java.io.FileWriter writer = new java.io.FileWriter(configFile);
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
        
        // Stop tunnel
        TProxyStopService();
        
        // Stop Aether
        // Aether is stopped when its process is killed
        
        // Close VPN interface
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
                "Aether Tunnel",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Aether Tunnel VPN Service");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification buildNotification(String text) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent stopIntent = new Intent(this, AetherTunnelVpnService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether Tunnel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
