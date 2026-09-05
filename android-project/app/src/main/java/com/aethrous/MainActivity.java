package com.aethrous;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    
    private Button btnConnect;
    private TextView txtStatus;
    private View statusIndicator;
    private View connectionInfo;
    private TextView txtDuration;
    private TextView txtUpload;
    private TextView txtDownload;
    
    private TextView chipGool;
    private TextView chipMasque;
    private TextView chipWireguard;
    private TextView chipBalanced;
    private TextView chipAggressive;
    
    private boolean isConnected = false;
    private boolean isGool = true;
    private boolean isBalanced = true;
    
    private long connectionStartTime = 0;
    private Handler timerHandler;
    private Runnable timerRunnable;
    
    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = 
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                onVpnPermissionGranted();
            } else {
                if (txtStatus != null) {
                    txtStatus.setText("Permission Denied");
                    statusIndicator.setBackgroundResource(R.drawable.status_indicator_disconnected);
                }
            }
        });
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        setupClickListeners();
        setupTimer();
        updateUI();
    }
    
    private void initViews() {
        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);
        statusIndicator = findViewById(R.id.statusIndicator);
        connectionInfo = findViewById(R.id.connectionInfo);
        txtDuration = findViewById(R.id.txtDuration);
        txtUpload = findViewById(R.id.txtUpload);
        txtDownload = findViewById(R.id.txtDownload);
        
        chipGool = findViewById(R.id.chipGool);
        chipMasque = findViewById(R.id.chipMasque);
        chipWireguard = findViewById(R.id.chipWireguard);
        chipBalanced = findViewById(R.id.chipBalanced);
        chipAggressive = findViewById(R.id.chipAggressive);
    }
    
    private void setupClickListeners() {
        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                if (isConnected) {
                    stopVpn();
                } else {
                    startVpn();
                }
            });
        }
        
        // Protocol chips
        if (chipGool != null) {
            chipGool.setOnClickListener(v -> selectProtocol("gool"));
        }
        if (chipMasque != null) {
            chipMasque.setOnClickListener(v -> selectProtocol("masque"));
        }
        if (chipWireguard != null) {
            chipWireguard.setOnClickListener(v -> selectProtocol("wireguard"));
        }
        
        // Anti-DPI chips
        if (chipBalanced != null) {
            chipBalanced.setOnClickListener(v -> selectNoize("balanced"));
        }
        if (chipAggressive != null) {
            chipAggressive.setOnClickListener(v -> selectNoize("aggressive"));
        }
    }
    
    private void setupTimer() {
        timerHandler = new Handler(Looper.getMainLooper());
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && connectionStartTime > 0) {
                    long elapsed = System.currentTimeMillis() - connectionStartTime;
                    long hours = elapsed / 3600000;
                    long minutes = (elapsed % 3600000) / 60000;
                    long seconds = (elapsed % 60000) / 1000;
                    
                    if (txtDuration != null) {
                        txtDuration.setText(String.format("%02d:%02d:%02d", hours, minutes, seconds));
                    }
                    
                    // Simulate traffic stats
                    if (txtUpload != null) {
                        txtUpload.setText(String.format("%.1f KB/s", Math.random() * 100));
                    }
                    if (txtDownload != null) {
                        txtDownload.setText(String.format("%.1f KB/s", Math.random() * 500));
                    }
                    
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
    }
    
    private void selectProtocol(String protocol) {
        isGool = protocol.equals("gool");
        updateProtocolChips();
    }
    
    private void selectNoize(String noize) {
        isBalanced = noize.equals("balanced");
        updateNoizeChips();
    }
    
    private void updateProtocolChips() {
        if (chipGool != null) {
            chipGool.setBackgroundResource(isGool ? R.drawable.chip_gool_background : R.drawable.chip_background);
        }
        if (chipMasque != null) {
            chipMasque.setBackgroundResource(!isGool ? R.drawable.chip_gool_background : R.drawable.chip_background);
        }
        if (chipWireguard != null) {
            chipWireguard.setBackgroundResource(R.drawable.chip_background);
        }
    }
    
    private void updateNoizeChips() {
        if (chipBalanced != null) {
            chipBalanced.setBackgroundResource(isBalanced ? R.drawable.chip_gool_background : R.drawable.chip_background);
        }
        if (chipAggressive != null) {
            chipAggressive.setBackgroundResource(!isBalanced ? R.drawable.chip_gool_background : R.drawable.chip_background);
        }
    }
    
    private void startVpn() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent);
        } else {
            onVpnPermissionGranted();
        }
    }
    
    private void stopVpn() {
        Intent stopIntent = new Intent(this, AethrousVpnService.class);
        stopIntent.setAction("STOP");
        startService(stopIntent);
        isConnected = false;
        connectionStartTime = 0;
        timerHandler.removeCallbacks(timerRunnable);
        updateUI();
    }
    
    private void onVpnPermissionGranted() {
        Intent startIntent = new Intent(this, AethrousVpnService.class);
        startService(startIntent);
        isConnected = true;
        connectionStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);
        updateUI();
    }
    
    private void updateUI() {
        runOnUiThread(() -> {
            if (btnConnect == null || txtStatus == null || statusIndicator == null) return;
            
            if (isConnected) {
                btnConnect.setText("Disconnect");
                btnConnect.setBackgroundResource(R.drawable.circle_button_disconnect_background);
                txtStatus.setText("Connected");
                txtStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_connected);
                
                if (connectionInfo != null) {
                    connectionInfo.setVisibility(View.VISIBLE);
                }
            } else {
                btnConnect.setText("Connect");
                btnConnect.setBackgroundResource(R.drawable.circle_button_background);
                txtStatus.setText("Disconnected");
                txtStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_disconnected);
                
                if (connectionInfo != null) {
                    connectionInfo.setVisibility(View.GONE);
                }
            }
            
            updateProtocolChips();
            updateNoizeChips();
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
    }
}
