package com.wife.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class GroupCallManager {
    private static final String TAG = "GroupCallManager";
    private static volatile GroupCallManager instance;

    private final Context context;
    private final Handler mainHandler;

    private DatagramSocket audioSocket;
    private DatagramSocket videoSocket;
    private WifiManager.MulticastLock multicastLock;
    private AudioTrack audioTrack;

    private boolean isCallActive = false;
    private int selfIdHash;
    private int audioSeq = 0;
    private int videoSeq = 0;

    private Thread audioReceiverThread;
    private Thread videoReceiverThread;
    private Thread audioMixerThread;
    private Thread peerPruningThread;

    // Thread-safe map tracking peer unique ID hashes to their respective Audio Jitter Queues
    private final Map<Integer, LinkedBlockingQueue<short[]>> peerAudioQueues = new ConcurrentHashMap<>();
    // Thread-safe map tracking peer unique ID hashes to their last active timestamps
    private final Map<Integer, Long> peerLastSeen = new ConcurrentHashMap<>();

    private GroupCallListener groupCallListener;

    public interface GroupCallListener {
        void onPeerJoined(int peerIdHash);
        void onPeerLeft(int peerIdHash);
        void onVideoFrameReceived(int peerIdHash, Bitmap bitmap);
        void onError(String error);
    }

    public static GroupCallManager getInstance(Context context) {
        if (instance == null) {
            synchronized (GroupCallManager.class) {
                if (instance == null) {
                    instance = new GroupCallManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private GroupCallManager(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Derive a unique 32-bit integer hash from the physical hardware ID
        String selfId = Utils.getDeviceId(context);
        this.selfIdHash = selfId.hashCode();
    }

    public synchronized void startGroupCall(GroupCallListener listener) {
        WifeLogger.log(TAG, "startGroupCall() initiated. Checking active calling state...");
        if (isCallActive) {
            WifeLogger.log(TAG, "startGroupCall() aborted: A group session is already active.");
            return;
        }
        
        this.groupCallListener = listener;
        this.isCallActive = true;
        this.audioSeq = 0;
        this.videoSeq = 0;
        
        peerAudioQueues.clear();
        peerLastSeen.clear();

        try {
            acquireMulticastLock();
            initializeSockets();
            initializeAudioTrack();
            
            startReceiverThreads();
            startAudioMixerThread();
            startPeerPruningThread();
            
            WifeLogger.log(TAG, "Parallel 5-way P2P group call system pipelines successfully loaded.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Fatal failure starting group call pipelines: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError("Failed to start group call: " + e.getMessage());
            }
            teardown();
        }
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("WifeGroupCallLock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
            WifeLogger.log(TAG, "Acquired system MulticastLock to bypass hardware broadcast packet blocks.");
        }
    }

    private void initializeSockets() throws Exception {
        WifeLogger.log(TAG, "Initializing independent parallel UDP calling sockets.");
        
        // Bind audio receiver socket to Port 8903
        audioSocket = new DatagramSocket(Constants.OFF_PORT_GROUP_AUDIO);
        audioSocket.setBroadcast(true);
        audioSocket.setReuseAddress(true);
        
        // Bind video receiver socket to Port 8904
        videoSocket = new DatagramSocket(Constants.OFF_PORT_GROUP_VIDEO);
        videoSocket.setBroadcast(true);
        videoSocket.setReuseAddress(true);
        
        WifeLogger.log(TAG, "UDP sockets successfully bound to ports " + Constants.OFF_PORT_GROUP_AUDIO + " (Audio) and " + Constants.OFF_PORT_GROUP_VIDEO + " (Video).");
    }

    private void initializeAudioTrack() {
        int minBufferSize = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(8000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play();
            WifeLogger.log(TAG, "Physical AudioTrack output stream initialized and playing.");
        } else {
            WifeLogger.log(TAG, "AudioTrack state check failed: STATE_UNINITIALIZED.");
        }
    }

    private void startReceiverThreads() {
        // 1. Spawning Audio Broadcast Receiver Thread
        audioReceiverThread = new Thread(() -> {
            WifeLogger.log(TAG, "UDP Audio receiver thread listening...");
            byte[] packetBuffer = new byte[4096];
            while (isCallActive) {
                try {
                    DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
                    audioSocket.receive(packet);
                    
                    if (!isCallActive) break;
                    processAudioPacket(packet.getData(), packet.getLength());
                } catch (Exception e) {
                    if (isCallActive) {
                        WifeLogger.log(TAG, "Audio packet receive loop exception: " + e.getMessage());
                    }
                }
            }
        });
        audioReceiverThread.start();

        // 2. Spawning Video Broadcast Receiver Thread
        videoReceiverThread = new Thread(() -> {
            WifeLogger.log(TAG, "UDP Video receiver thread listening...");
            byte[] packetBuffer = new byte[65535]; // Maximum safe UDP payload limit
            while (isCallActive) {
                try {
                    DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
                    videoSocket.receive(packet);
                    
                    if (!isCallActive) break;
                    processVideoPacket(packet.getData(), packet.getLength());
                } catch (Exception e) {
                    if (isCallActive) {
                        WifeLogger.log(TAG, "Video packet receive loop exception: " + e.getMessage());
                    }
                }
            }
        });
        videoReceiverThread.start();
    }

    /**
     * Extracts and validates the custom 8-byte binary header from an incoming raw UDP payload.
     * Decodes big-endian structures dynamically.
     */
    private void processAudioPacket(byte[] rawData, int length) {
        if (length < 8) return; // Header size check
        
        ByteBuffer wrap = ByteBuffer.wrap(rawData, 0, 8);
        int senderId = wrap.getInt(); // Bytes 0-3: Sender Hash ID
        byte type = wrap.get();       // Byte 4: Type (1 = Audio, 2 = Video)
        
        if (senderId == selfIdHash || type != 1) {
            return; // Ignore loops back from self or mismatched types
        }

        registerPeerActivity(senderId);

        int audioDataLength = length - 8;
        if (audioDataLength <= 0) return;

        // Convert the remaining payload from byte stream to 16-bit short samples
        int sampleCount = audioDataLength / 2;
        short[] pcmBuffer = new short[sampleCount];
        ByteBuffer.wrap(rawData, 8, audioDataLength).asShortBuffer().get(pcmBuffer);

        LinkedBlockingQueue<short[]> queue = peerAudioQueues.get(senderId);
        if (queue != null) {
            // Offer to jitter buffer. If capacity is exceeded, drop oldest chunk to prioritize real-time
            if (queue.size() > 10) {
                queue.poll();
            }
            queue.offer(pcmBuffer);
        }
    }

    private void processVideoPacket(byte[] rawData, int length) {
        if (length < 8) return;
        
        ByteBuffer wrap = ByteBuffer.wrap(rawData, 0, 8);
        int senderId = wrap.getInt();
        byte type = wrap.get();
        
        if (senderId == selfIdHash || type != 2) {
            return; // Ignore self loops or mismatched types
        }

        registerPeerActivity(senderId);

        int videoDataLength = length - 8;
        if (videoDataLength <= 0) return;

        // Decode raw JPEG payload straight to Bitmap
        try {
            final Bitmap bitmap = BitmapFactory.decodeByteArray(rawData, 8, videoDataLength);
            if (bitmap != null && groupCallListener != null) {
                mainHandler.post(() -> {
                    if (groupCallListener != null) {
                        groupCallListener.onVideoFrameReceived(senderId, bitmap);
                    }
                });
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed decoding group video stream frame: " + e.getMessage());
        }
    }

    private void registerPeerActivity(int senderId) {
        peerLastSeen.put(senderId, System.currentTimeMillis());
        if (!peerAudioQueues.containsKey(senderId)) {
            peerAudioQueues.put(senderId, new LinkedBlockingQueue<>());
            WifeLogger.log(TAG, "New group participant joined call pipeline: [" + senderId + "]");
            mainHandler.post(() -> {
                if (groupCallListener != null) {
                    groupCallListener.onPeerJoined(senderId);
                }
            });
        }
    }

    private void startAudioMixerThread() {
        audioMixerThread = new Thread(() -> {
            WifeLogger.log(TAG, "PCM Software Audio Mixer thread running...");
            final int samplesPerFrame = 160; // 20ms block at 8kHz sample rate
            short[] mixOutputBuffer = new short[samplesPerFrame];
            
            while (isCallActive) {
                long startTime = System.currentTimeMillis();
                
                // Clear output summing buffer
                java.util.Arrays.fill(mixOutputBuffer, (short) 0);
                boolean hasDataToMix = false;

                // Mathematical summing loop across all registered peer queues
                for (Map.Entry<Integer, LinkedBlockingQueue<short[]>> entry : peerAudioQueues.entrySet()) {
                    LinkedBlockingQueue<short[]> queue = entry.getValue();
                    short[] peerChunk = queue.poll();
                    
                    if (peerChunk != null) {
                        hasDataToMix = true;
                        int minLength = Math.min(samplesPerFrame, peerChunk.length);
                        for (int i = 0; i < minLength; i++) {
                            // Raw linear mathematical summing
                            int mixedValue = mixOutputBuffer[i] + peerChunk[i];
                            
                            // Attenuating slightly to reduce clipping chance (Clipping Protection)
                            mixedValue = (int) (mixedValue * 0.8f);
                            
                            // Safe short clipping protection limits
                            if (mixedValue > 32767) mixedValue = 32767;
                            if (mixedValue < -32768) mixedValue = -32768;
                            
                            mixOutputBuffer[i] = (short) mixedValue;
                        }
                    }
                }

                if (hasDataToMix && audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.write(mixOutputBuffer, 0, samplesPerFrame);
                }

                // Sleep precision calculation to maintain a reliable 20ms audio clock loop
                long elapsed = System.currentTimeMillis() - startTime;
                long sleepTime = 20 - elapsed;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        audioMixerThread.start();
    }

    private void startPeerPruningThread() {
        peerPruningThread = new Thread(() -> {
            WifeLogger.log(TAG, "Peer monitoring timeout watchdog thread spawned.");
            while (isCallActive) {
                try {
                    Thread.sleep(3000); // Check peer states every 3 seconds
                    if (!isCallActive) break;

                    long now = System.currentTimeMillis();
                    for (Map.Entry<Integer, Long> entry : peerLastSeen.entrySet()) {
                        int peerId = entry.getKey();
                        long lastSeenTime = entry.getValue();
                        
                        // Prune peer if no packets have been received for over 5 seconds
                        if (now - lastSeenTime > 5000) {
                            WifeLogger.log(TAG, "Watchdog timed out peer session: [" + peerId + "]");
                            peerLastSeen.remove(peerId);
                            peerAudioQueues.remove(peerId);
                            mainHandler.post(() -> {
                                if (groupCallListener != null) {
                                    groupCallListener.onPeerLeft(peerId);
                                }
                            });
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        peerPruningThread.start();
    }

    /**
     * Encapsulates raw audio bytes with our custom 8-byte header and broadcasts over UDP Port 8903.
     */
    public void broadcastAudioFrame(byte[] pcmData, int length) {
        if (!isCallActive || audioSocket == null) return;
        
        try {
            byte[] rawPayload = new byte[length + 8];
            ByteBuffer buffer = ByteBuffer.wrap(rawPayload);
            
            // Build the custom 8-byte header structure
            buffer.putInt(selfIdHash);    // Bytes 0-3: Sender Hash ID
            buffer.put((byte) 1);         // Byte 4: Type indicator (1 = Audio)
            
            // Append 3-byte sequence number
            buffer.put((byte) ((audioSeq >> 16) & 0xFF));
            buffer.put((byte) ((audioSeq >> 8) & 0xFF));
            buffer.put((byte) (audioSeq & 0xFF));
            audioSeq++;

            System.arraycopy(pcmData, 0, rawPayload, 8, length);

            InetAddress broadcastAddr = InetAddress.getByName("192.168.49.255");
            DatagramPacket packet = new DatagramPacket(rawPayload, rawPayload.length, broadcastAddr, Constants.OFF_PORT_GROUP_AUDIO);
            
            // Send asynchronously on calling threads
            new Thread(() -> {
                try {
                    if (audioSocket != null && !audioSocket.isClosed()) {
                        audioSocket.send(packet);
                    }
                } catch (IOException ignored) {}
            }).start();
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed drafting outbound audio packet: " + e.getMessage());
        }
    }

    /**
     * Encapsulates JPEG bytes with our custom 8-byte header and broadcasts over UDP Port 8904.
     */
    public void broadcastVideoFrame(byte[] jpegData) {
        if (!isCallActive || videoSocket == null) return;

        try {
            int length = jpegData.length;
            byte[] rawPayload = new byte[length + 8];
            ByteBuffer buffer = ByteBuffer.wrap(rawPayload);
            
            // Build the custom 8-byte header structure
            buffer.putInt(selfIdHash);    // Bytes 0-3: Sender Hash ID
            buffer.put((byte) 2);         // Byte 4: Type indicator (2 = Video)
            
            // Append 3-byte sequence number
            buffer.put((byte) ((videoSeq >> 16) & 0xFF));
            buffer.put((byte) ((videoSeq >> 8) & 0xFF));
            buffer.put((byte) (videoSeq & 0xFF));
            videoSeq++;

            System.arraycopy(jpegData, 0, rawPayload, 8, length);

            InetAddress broadcastAddr = InetAddress.getByName("192.168.49.255");
            DatagramPacket packet = new DatagramPacket(rawPayload, rawPayload.length, broadcastAddr, Constants.OFF_PORT_GROUP_VIDEO);
            
            // Send asynchronously on calling threads
            new Thread(() -> {
                try {
                    if (videoSocket != null && !videoSocket.isClosed()) {
                        videoSocket.send(packet);
                    }
                } catch (IOException ignored) {}
            }).start();
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed drafting outbound video packet: " + e.getMessage());
        }
    }

    public synchronized void endGroupCall() {
        WifeLogger.log(TAG, "endGroupCall() invoked. Teardown active calling channels.");
        teardown();
    }

    private synchronized void teardown() {
        isCallActive = false;
        
        // Unblock all execution threads
        if (audioReceiverThread != null) audioReceiverThread.interrupt();
        if (videoReceiverThread != null) videoReceiverThread.interrupt();
        if (audioMixerThread != null) audioMixerThread.interrupt();
        if (peerPruningThread != null) peerPruningThread.interrupt();

        if (audioSocket != null) {
            audioSocket.close();
            audioSocket = null;
        }

        if (videoSocket != null) {
            videoSocket.close();
            videoSocket = null;
        }

        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
            WifeLogger.log(TAG, "Released system MulticastLock cleanly.");
        }

        peerAudioQueues.clear();
        peerLastSeen.clear();
        groupCallListener = null;
        
        WifeLogger.log(TAG, "Decoupled 5-way P2P group call engine teardown complete.");
    }
}