package com.aethrous;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private TextView txtStatus;
    private boolean isConnected = false;
    private boolean autoStarted = false;
    private SharedPreferences prefs;
    private Handler handler;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                startVpnService();
            } else {
                Toast.makeText(this, "VPN permission required", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("aethrous_prefs", MODE_PRIVATE);

        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);

        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                stopVpnService();
            } else {
                requestVpnPermission();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (!autoStarted) {
            autoStarted = true;
            handler.postDelayed(() -> {
                if (!isConnected) {
                    requestVpnPermission();
                }
            }, 500);
        }
    }

    private void requestVpnPermission() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, AethrousVpnService.class);
        intent.setAction("START");
        intent.putExtra("protocol", prefs.getString("protocol", "gool"));
        intent.putExtra("noize", prefs.getString("noize", "balanced"));
        intent.putExtra("scan_mode", prefs.getString("scan_mode", "balanced"));
        startService(intent);
        isConnected = true;
        updateStatus();
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, AethrousVpnService.class);
        intent.setAction("STOP");
        startService(intent);
        isConnected = false;
        updateStatus();
    }

    private void updateStatus() {
        if (txtStatus == null || btnConnect == null) return;
        txtStatus.setText(isConnected ? "Connected" : "Disconnected");
        txtStatus.setTextColor(ContextCompat.getColor(this,
            isConnected ? R.color.status_connected : R.color.status_disconnected));
        btnConnect.setText(isConnected ? "Disconnect" : "Connect");
        btnConnect.setBackgroundResource(isConnected
            ? R.drawable.circle_button_disconnect_background
            : R.drawable.circle_button_background);
    }
}
