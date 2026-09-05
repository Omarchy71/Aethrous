package com.aethrous;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button btnConnect;
    private Button btnSettings;
    private Button btnScan;
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
    private TextView chipLight;
    private TextView chipOff;

    private boolean isConnected = false;
    private String selectedProtocol = "gool";
    private String selectedNoize = "balanced";

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

    private final ActivityResultLauncher<String> notificationPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupClickListeners();
        setupTimer();
        requestNotificationPermission();
        updateUI();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void initViews() {
        btnConnect = findViewById(R.id.btnConnect);
        btnSettings = findViewById(R.id.btnSettings);
        btnScan = findViewById(R.id.btnScan);
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
        chipLight = findViewById(R.id.chipLight);
        chipOff = findViewById(R.id.chipOff);
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

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v ->
                Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show());
        }

        if (btnScan != null) {
            btnScan.setOnClickListener(v ->
                Toast.makeText(this, "Scan coming soon", Toast.LENGTH_SHORT).show());
        }

        if (chipGool != null) {
            chipGool.setOnClickListener(v -> selectProtocol("gool"));
        }
        if (chipMasque != null) {
            chipMasque.setOnClickListener(v -> selectProtocol("masque"));
        }
        if (chipWireguard != null) {
            chipWireguard.setOnClickListener(v -> selectProtocol("wireguard"));
        }

        if (chipBalanced != null) {
            chipBalanced.setOnClickListener(v -> selectNoize("balanced"));
        }
        if (chipAggressive != null) {
            chipAggressive.setOnClickListener(v -> selectNoize("aggressive"));
        }
        if (chipLight != null) {
            chipLight.setOnClickListener(v -> selectNoize("light"));
        }
        if (chipOff != null) {
            chipOff.setOnClickListener(v -> selectNoize("off"));
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

                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    private void selectProtocol(String protocol) {
        if (isConnected) return;
        selectedProtocol = protocol;
        updateProtocolChips();
    }

    private void selectNoize(String noize) {
        if (isConnected) return;
        selectedNoize = noize;
        updateNoizeChips();
    }

    private void updateProtocolChips() {
        boolean isGool = "gool".equals(selectedProtocol);
        boolean isMasque = "masque".equals(selectedProtocol);
        boolean isWg = "wireguard".equals(selectedProtocol);

        if (chipGool != null) {
            chipGool.setBackgroundResource(isGool ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipGool.setTextColor(ContextCompat.getColor(this,
                isGool ? android.R.color.white : R.color.text_primary));
        }
        if (chipMasque != null) {
            chipMasque.setBackgroundResource(isMasque ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipMasque.setTextColor(ContextCompat.getColor(this,
                isMasque ? android.R.color.white : R.color.text_primary));
        }
        if (chipWireguard != null) {
            chipWireguard.setBackgroundResource(isWg ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipWireguard.setTextColor(ContextCompat.getColor(this,
                isWg ? android.R.color.white : R.color.text_primary));
        }
    }

    private void updateNoizeChips() {
        boolean isBalanced = "balanced".equals(selectedNoize);
        boolean isAggressive = "aggressive".equals(selectedNoize);
        boolean isLight = "light".equals(selectedNoize);
        boolean isOff = "off".equals(selectedNoize);

        if (chipBalanced != null) {
            chipBalanced.setBackgroundResource(isBalanced ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipBalanced.setTextColor(ContextCompat.getColor(this,
                isBalanced ? android.R.color.white : R.color.text_primary));
        }
        if (chipAggressive != null) {
            chipAggressive.setBackgroundResource(isAggressive ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipAggressive.setTextColor(ContextCompat.getColor(this,
                isAggressive ? android.R.color.white : R.color.text_primary));
        }
        if (chipLight != null) {
            chipLight.setBackgroundResource(isLight ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipLight.setTextColor(ContextCompat.getColor(this,
                isLight ? android.R.color.white : R.color.text_primary));
        }
        if (chipOff != null) {
            chipOff.setBackgroundResource(isOff ? R.drawable.chip_gool_background : R.drawable.chip_background);
            chipOff.setTextColor(ContextCompat.getColor(this,
                isOff ? android.R.color.white : R.color.text_primary));
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
        if (timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        updateUI();
    }

    private void onVpnPermissionGranted() {
        Intent startIntent = new Intent(this, AethrousVpnService.class);
        startIntent.setAction("START");
        startIntent.putExtra("protocol", selectedProtocol);
        startIntent.putExtra("noize", selectedNoize);
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
                if (btnSettings != null) btnSettings.setEnabled(false);
                if (btnScan != null) btnScan.setEnabled(false);
            } else {
                btnConnect.setText("Connect");
                btnConnect.setBackgroundResource(R.drawable.circle_button_background);
                txtStatus.setText("Disconnected");
                txtStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_disconnected);

                if (connectionInfo != null) {
                    connectionInfo.setVisibility(View.GONE);
                }
                if (txtDuration != null) txtDuration.setText("00:00:00");
                if (txtUpload != null) txtUpload.setText("---");
                if (txtDownload != null) txtDownload.setText("---");
                if (btnSettings != null) btnSettings.setEnabled(true);
                if (btnScan != null) btnScan.setEnabled(true);
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
        if (timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }
}
