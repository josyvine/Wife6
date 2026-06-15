package com.wife.app;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class ConnectionManager implements WiFiDirectManager.ConnectionChangeListener {
    private static final String TAG = "ConnectionManager";
    private static volatile ConnectionManager instance;

    private final Context context;
    private final List<ConnectionStatusListener> statusListeners = new ArrayList<>();

    private SocketServer socketServer;
    private SocketClient socketClient;

    private String peerIpAddress = "";
    private boolean isHost = false;
    private boolean isConnected = false;

    public interface ConnectionStatusListener {
        void onConnectionStateChanged(boolean connected, String peerIp, boolean isHost);
    }

    public static ConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ConnectionManager.class) {
                if (instance == null) {
                    instance = new ConnectionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ConnectionManager(Context context) {
        this.context = context;
        WiFiDirectManager.getInstance(context).registerConnectionChangeListener(this);
    }

    public synchronized void registerStatusListener(ConnectionStatusListener listener) {
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
        listener.onConnectionStateChanged(isConnected, peerIpAddress, isHost);
    }

    public synchronized void unregisterStatusListener(ConnectionStatusListener listener) {
        statusListeners.remove(listener);
    }

    public String getPeerIpAddress() {
        return peerIpAddress;
    }

    public boolean isHost() {
        return isHost;
    }

    public boolean isConnected() {
        return isConnected;
    }

    @Override
    public void onConnectionChanged(WifiP2pInfo info) {
        if (info != null && info.groupFormed) {
            isConnected = true;
            isHost = info.isGroupOwner;
            
            // Critical Fix: BOTH Host and Client must start background socket servers
            // to listen for incoming bidirectional message and calling requests.
            startServers();
            
            if (isHost) {
                Log.d(TAG, "Device is Group Owner. Starting SocketServers...");
                WifeLogger.log(TAG, "P2P connection established. This device is the Group Owner (Host). Server sockets started.");
                peerIpAddress = ""; // Will be updated when Client connects to Control Server
            } else {
                Log.d(TAG, "Device is Client. Connecting to Host: " + info.groupOwnerAddress.getHostAddress());
                WifeLogger.log(TAG, "P2P connection established. This device is the Client. Server sockets started. Connecting to Host: " + info.groupOwnerAddress.getHostAddress());
                peerIpAddress = info.groupOwnerAddress.getHostAddress();
                startClient(info.groupOwnerAddress);
            }
        } else {
            Log.d(TAG, "Connection lost, tearing down active sockets.");
            WifeLogger.log(TAG, "P2P Connection lost. Tearing down active sockets.");
            teardown();
        }
        notifyStateChanged();
    }

    private synchronized void startServers() {
        WifeLogger.log(TAG, "startServers() invoked. Initializing SocketServer threads...");
        if (socketServer != null) {
            socketServer.stop();
        }
        socketServer = new SocketServer(context, this);
        socketServer.start();
    }

    private synchronized void startClient(InetAddress hostAddress) {
        WifeLogger.log(TAG, "startClient() invoked. Initializing SocketClient targeting Host: " + hostAddress.getHostAddress());
        if (socketClient != null) {
            socketClient.close();
        }
        socketClient = new SocketClient(context, hostAddress, this);
        socketClient.start();
    }

    public synchronized void updatePeerIpFromAccept(String acceptedIp) {
        WifeLogger.log(TAG, "updatePeerIpFromAccept called with IP: " + acceptedIp + ". Current cached Peer IP: " + peerIpAddress);
        if (peerIpAddress == null || peerIpAddress.isEmpty() || !peerIpAddress.equals(acceptedIp)) {
            Log.d(TAG, "Host recorded Client IP: " + acceptedIp);
            WifeLogger.log(TAG, "Updating Peer IP Address reference to accepted client socket IP: " + acceptedIp);
            this.peerIpAddress = acceptedIp;
            notifyStateChanged();
        }
    }

    public synchronized void teardown() {
        WifeLogger.log(TAG, "teardown() invoked. Cleared connection state variables.");
        isConnected = false;
        peerIpAddress = "";
        isHost = false;
        
        if (socketServer != null) {
            socketServer.stop();
            socketServer = null;
        }
        if (socketClient != null) {
            socketClient.close();
            socketClient = null;
        }
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            List<ConnectionStatusListener> targets;
            synchronized (ConnectionManager.this) {
                targets = new ArrayList<>(statusListeners);
            }
            WifeLogger.log(TAG, "Dispatching connection state change. Connected: " + isConnected + ", Peer IP: " + peerIpAddress + ", Is Host: " + isHost);
            for (ConnectionStatusListener listener : targets) {
                listener.onConnectionStateChanged(isConnected, peerIpAddress, isHost);
            }
        });
    }
}