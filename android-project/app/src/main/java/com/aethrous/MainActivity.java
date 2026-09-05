package com.aethrous;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private Button btnConnect;
    private TextView txtStatus;
    private boolean isConnected = false;
    
    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = 
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                onVpnPermissionGranted();
            } else {
                if (txtStatus != null) {
                    txtStatus.setText("Status: VPN permission denied");
                }
            }
        });
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);
        
        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                if (isConnected) {
                    stopVpn();
                } else {
                    startVpn();
                }
            });
        }
        
        updateUI();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
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
        updateUI();
    }
    
    private void onVpnPermissionGranted() {
        Intent startIntent = new Intent(this, AethrousVpnService.class);
        startService(startIntent);
        isConnected = true;
        updateUI();
    }
    
    private void updateUI() {
        runOnUiThread(() -> {
            if (btnConnect == null || txtStatus == null) return;
            
            if (isConnected) {
                btnConnect.setText("Disconnect");
                txtStatus.setText("Status: Connected");
                txtStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, getTheme()));
            } else {
                btnConnect.setText("Connect");
                txtStatus.setText("Status: Disconnected");
                txtStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, getTheme()));
            }
        });
    }
}
