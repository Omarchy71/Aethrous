package com.aethrous;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private SeekBar seekScanMode;
    private TextView txtScanMode;
    private SeekBar seekIpVersion;
    private TextView txtIpVersion;
    private Switch switchQuickReconnect;
    private Switch switchH2;
    private Switch switchFragment;
    private EditText editKeepalive;
    private EditText editCustomPeer;
    private EditText editWiwOuter;
    private EditText editWiwInner;
    private Button btnSave;
    private Button btnBack;

    private final String[] scanModes = {"turbo", "balanced", "thorough", "stealth", "ironclad"};
    private final String[] ipVersions = {"4", "6", "dual"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("aethrous_prefs", MODE_PRIVATE);
        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        seekScanMode = findViewById(R.id.seekScanMode);
        txtScanMode = findViewById(R.id.txtScanMode);
        seekIpVersion = findViewById(R.id.seekIpVersion);
        txtIpVersion = findViewById(R.id.txtIpVersion);
        switchQuickReconnect = findViewById(R.id.switchQuickReconnect);
        switchH2 = findViewById(R.id.switchH2);
        switchFragment = findViewById(R.id.switchFragment);
        editKeepalive = findViewById(R.id.editKeepalive);
        editCustomPeer = findViewById(R.id.editCustomPeer);
        editWiwOuter = findViewById(R.id.editWiwOuter);
        editWiwInner = findViewById(R.id.editWiwInner);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);
    }

    private void loadSettings() {
        int scanIdx = 0;
        String scan = prefs.getString("scan_mode", "balanced");
        for (int i = 0; i < scanModes.length; i++) {
            if (scanModes[i].equals(scan)) { scanIdx = i; break; }
        }
        seekScanMode.setProgress(scanIdx);
        txtScanMode.setText(scanModes[scanIdx]);

        int ipIdx = 0;
        String ip = prefs.getString("ip_version", "4");
        for (int i = 0; i < ipVersions.length; i++) {
            if (ipVersions[i].equals(ip)) { ipIdx = i; break; }
        }
        seekIpVersion.setProgress(ipIdx);
        txtIpVersion.setText(ipVersions[ipIdx]);

        switchQuickReconnect.setChecked(prefs.getBoolean("quick_reconnect", true));
        switchH2.setChecked(prefs.getBoolean("use_h2", false));
        switchFragment.setChecked(prefs.getBoolean("fragment", false));
        editKeepalive.setText(String.valueOf(prefs.getInt("keepalive", 5)));
        editCustomPeer.setText(prefs.getString("custom_peer", ""));
        editWiwOuter.setText(prefs.getString("wiw_outer", ""));
        editWiwInner.setText(prefs.getString("wiw_inner", ""));
    }

    private void setupListeners() {
        seekScanMode.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                txtScanMode.setText(scanModes[progress]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekIpVersion.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                txtIpVersion.setText(ipVersions[progress]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnSave.setOnClickListener(v -> saveSettings());
        btnBack.setOnClickListener(v -> finish());
    }

    private void saveSettings() {
        prefs.edit()
            .putString("scan_mode", scanModes[seekScanMode.getProgress()])
            .putString("ip_version", ipVersions[seekIpVersion.getProgress()])
            .putBoolean("quick_reconnect", switchQuickReconnect.isChecked())
            .putBoolean("use_h2", switchH2.isChecked())
            .putBoolean("fragment", switchFragment.isChecked())
            .putInt("keepalive", parseIntSafe(editKeepalive.getText().toString(), 5))
            .putString("custom_peer", editCustomPeer.getText().toString().trim())
            .putString("wiw_outer", editWiwOuter.getText().toString().trim())
            .putString("wiw_inner", editWiwInner.getText().toString().trim())
            .apply();
        finish();
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
