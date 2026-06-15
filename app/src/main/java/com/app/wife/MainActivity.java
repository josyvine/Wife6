package com.wife.app;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;

import com.wife.app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements ConnectionManager.ConnectionStatusListener {

    private ActivityMainBinding binding;
    private WiFiDirectManager wifiDirectManager;
    private WiFiDirectBroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private ConnectionManager connectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        wifiDirectManager = WiFiDirectManager.getInstance(this);
        connectionManager = ConnectionManager.getInstance(this);

        setupIntentFilters();
        setupMenuClickListeners();
        
        // Start foreground service to maintain socket control loop
        Intent serviceIntent = new Intent(this, ConnectionForegroundService.class);
        startService(serviceIntent);
    }

    private void setupIntentFilters() {
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    private void setupMenuClickListeners() {
        // Toggle slide-out menu drawer
        binding.toolbarMain.setNavigationOnClickListener(v -> {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        // Horizontal footer actions
        binding.btnDiscovery.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, DeviceDiscoveryActivity.class));
        });

        binding.btnTextChat.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                startActivity(new Intent(MainActivity.this, ChatActivity.class));
            } else {
                Toast.makeText(this, "Please establish a peer mesh connection first.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnDirectCall.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                Intent callIntent = new Intent(MainActivity.this, VoiceCallActivity.class);
                callIntent.putExtra("IS_INBOUND", false);
                callIntent.putExtra(Constants.EXTRA_PEER_IP, connectionManager.getPeerIpAddress());
                startActivity(callIntent);
            } else {
                Toast.makeText(this, "Connect to a nearby device to place a call.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnVideoCall.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                Intent callIntent = new Intent(MainActivity.this, VideoCallActivity.class);
                callIntent.putExtra("IS_INBOUND", false);
                callIntent.putExtra(Constants.EXTRA_PEER_IP, connectionManager.getPeerIpAddress());
                startActivity(callIntent);
            } else {
                Toast.makeText(this, "Connect to a nearby device to place a video call.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnFileShare.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                startActivity(new Intent(MainActivity.this, FileTransferActivity.class));
            } else {
                Toast.makeText(this, "Please establish a connection to share files.", Toast.LENGTH_SHORT).show();
            }
        });

        // Slider drawer item actions
        binding.btnMenuMeshLogs.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(MainActivity.this, ConnectionStatusActivity.class));
        });

        binding.btnMenuCallHistory.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(MainActivity.this, CallHistoryActivity.class));
        });

        binding.btnMenuSettings.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        receiver = new WiFiDirectBroadcastReceiver(wifiDirectManager.getP2pManager(), wifiDirectManager.getChannel(), wifiDirectManager);
        registerReceiver(receiver, intentFilter);
        connectionManager.registerStatusListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
        connectionManager.unregisterStatusListener(this);
    }

    @Override
    public void onConnectionStateChanged(boolean connected, String peerIp, boolean isHost) {
        runOnUiThread(() -> {
            if (binding == null) return;
            if (connected) {
                binding.vStatusIndicator.setBackgroundResource(android.R.drawable.presence_online);
                binding.tvMainConnectionState.setText("Connected");
                binding.tvMainConnectionState.setTextColor(getResources().getColor(android.R.color.holo_green_dark, getTheme()));
                binding.tvMainPeerDetails.setText("Peer IP: " + peerIp + " | Link Mode: " + (isHost ? "Mesh Host" : "Mesh Client"));
                
                // Start heartbeat keep alive tracking
                HeartbeatManager.getInstance(MainActivity.this).startMonitoring();
            } else {
                binding.vStatusIndicator.setBackgroundResource(android.R.drawable.presence_offline);
                binding.tvMainConnectionState.setText("Disconnected");
                binding.tvMainConnectionState.setTextColor(getResources().getColor(android.R.color.holo_red_dark, getTheme()));
                binding.tvMainPeerDetails.setText("No connected devices nearby.");
                
                HeartbeatManager.getInstance(MainActivity.this).stopMonitoring();
            }
        });
    }
}