package com.aethertunnel;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 100;
    
    private Button btnConnect;
    private TextView txtStatus;
    private boolean isConnected = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);
        
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                stopVpn();
            } else {
                startVpn();
            }
        });
        
        updateUI();
    }
    
    private void startVpn() {
        // Check VPN permission
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            onVpnPermissionGranted();
        }
    }
    
    private void stopVpn() {
        Intent stopIntent = new Intent(this, AetherTunnelVpnService.class);
        stopIntent.setAction("STOP");
        startService(stopIntent);
        isConnected = false;
        updateUI();
    }
    
    private void onVpnPermissionGranted() {
        Intent startIntent = new Intent(this, AetherTunnelVpnService.class);
        startService(startIntent);
        isConnected = true;
        updateUI();
    }
    
    private void updateUI() {
        if (isConnected) {
            btnConnect.setText("Disconnect");
            txtStatus.setText("Status: Connected");
            txtStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            btnConnect.setText("Connect");
            txtStatus.setText("Status: Disconnected");
            txtStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                onVpnPermissionGranted();
            } else {
                txtStatus.setText("Status: VPN permission denied");
            }
        }
    }
}
