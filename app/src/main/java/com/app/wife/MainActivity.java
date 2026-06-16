package com.wife.app;

import android.content.Intent;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;

import com.wife.app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity implements ConnectionManager.ConnectionStatusListener {

    private static final String TAG = "MainActivity";

    private ActivityMainBinding binding;
    private WiFiDirectManager wifiDirectManager;
    private ConnectionManager connectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Initializing MainActivity.");

        wifiDirectManager = WiFiDirectManager.getInstance(this);
        connectionManager = ConnectionManager.getInstance(this);

        setupMenuClickListeners();
        
        // Start foreground service to maintain socket control loop and global broadcast receiver
        WifeLogger.log(TAG, "Starting ConnectionForegroundService to manage global P2P events and sockets.");
        Intent serviceIntent = new Intent(this, ConnectionForegroundService.class);
        startService(serviceIntent);
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
            WifeLogger.log(TAG, "User launched DeviceDiscoveryActivity.");
            startActivity(new Intent(MainActivity.this, DeviceDiscoveryActivity.class));
        });

        binding.btnTextChat.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                WifeLogger.log(TAG, "User launched ChatActivity.");
                startActivity(new Intent(MainActivity.this, ChatActivity.class));
            } else {
                Toast.makeText(this, "Please establish a peer mesh connection first.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnDirectCall.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                WifeLogger.log(TAG, "User initiated outbound direct voice call from MainActivity.");
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
                WifeLogger.log(TAG, "User initiated outbound direct video call from MainActivity.");
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
                WifeLogger.log(TAG, "User launched FileTransferActivity.");
                startActivity(new Intent(MainActivity.this, FileTransferActivity.class));
            } else {
                Toast.makeText(this, "Please establish a connection to share files.", Toast.LENGTH_SHORT).show();
            }
        });

        // Slider drawer item actions
        binding.btnMenuMeshLogs.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            WifeLogger.log(TAG, "User launched ConnectionStatusActivity from drawer.");
            startActivity(new Intent(MainActivity.this, ConnectionStatusActivity.class));
        });

        binding.btnMenuCallHistory.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            WifeLogger.log(TAG, "User launched CallHistoryActivity from drawer.");
            startActivity(new Intent(MainActivity.this, CallHistoryActivity.class));
        });

        binding.btnMenuSettings.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            WifeLogger.log(TAG, "User launched SettingsActivity from drawer.");
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        WifeLogger.log(TAG, "onResume() called. Registering MainActivity to ConnectionManager status listener.");
        connectionManager.registerStatusListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        WifeLogger.log(TAG, "onPause() called. Unregistering MainActivity from ConnectionManager status listener.");
        connectionManager.unregisterStatusListener(this);
    }

    @Override
    public void onConnectionStateChanged(boolean connected, String peerIp, boolean isHost) {
        runOnUiThread(() -> {
            if (binding == null) return;
            WifeLogger.log(TAG, "onConnectionStateChanged() triggered. Connected: " + connected + " | Peer IP: " + peerIp + " | Is Host: " + isHost);
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