package ir.cloudfort.callum.voice;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Cross-platform voice-chat client.
 * Connects to the Go voice service over WebSocket, captures microphone audio
 * via AudioEngine, and plays back remote peer audio.
 */
public class VoiceClient {
    private static final int SPEAKING_THRESHOLD_CHECK_MS = 80;
    private static final double SPEAKING_RMS_THRESHOLD = 0.012;
    private static final int PEER_RING_CAPACITY = 96000; // ~2s at 48 kHz

    private final VoiceConfig config;
    private final AudioEngine audio;
    private final List<VoiceEventListener> listeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private VoiceClientState state = VoiceClientState.DISCONNECTED;
    private String currentRoom;
    private boolean micEnabled;
    private boolean speakerEnabled;
    private int reconnectAttempts;
    private String prevRoom;
    private boolean prevMicEnabled;

    private final ConcurrentHashMap<String, PeerState> peers = new ConcurrentHashMap<>();
    private Timer speakingTimer;

    public VoiceClient(@NonNull VoiceConfig config, @NonNull AudioEngine audio) {
        this.config = config;
        this.audio = audio;

        audio.setAudioCapturedListener(this::onAudioCaptured);
        audio.setErrorListener(msg -> raiseError(new Exception(msg)));
    }

    public void addListener(VoiceEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(VoiceEventListener listener) {
        listeners.remove(listener);
    }

    public VoiceClientState getState() {
        return state;
    }

    public boolean isConnected() {
        return state.ordinal() >= VoiceClientState.CONNECTED.ordinal();
    }

    public boolean isInRoom() {
        return state == VoiceClientState.IN_ROOM;
    }

    public boolean isMicEnabled() {
        return micEnabled;
    }

    public boolean isSpeakerEnabled() {
        return speakerEnabled;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public Collection<String> getPeers() {
        return Collections.unmodifiableCollection(peers.keySet());
    }

    public void connect() {
        if (state.ordinal() >= VoiceClientState.CONNECTED.ordinal()) return;

        setState(VoiceClientState.CONNECTING);

        String scheme = resolveScheme();
        String encodedPeerId;
        String encodedApiKey;
        try {
            encodedPeerId = java.net.URLEncoder.encode(config.peerId, "UTF-8");
            encodedApiKey = java.net.URLEncoder.encode(config.apiKey, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e); // UTF-8 is always supported
        }
        String url = scheme + "://" + config.server + "/ws"
            + "?room=__lobby__"
            + "&peer=" + encodedPeerId
            + "&api_key=" + encodedApiKey;

        httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

        Request request = new Request.Builder()
            .url(url)
            .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                setState(VoiceClientState.CONNECTED);
                reconnectAttempts = 0;
                notifyConnected();

                if (prevRoom != null) {
                    String room = prevRoom;
                    boolean restoreMic = prevMicEnabled;
                    prevRoom = null;
                    prevMicEnabled = false;
                    try {
                        joinRoom(room);
                        if (restoreMic) {
                            enableMic();
                        }
                    } catch (Exception e) {
                        raiseError(e);
                    }
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                handleTextMessage(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull okio.ByteString bytes) {
                handleBinaryMessage(bytes.toByteArray());
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                handleClose(code, reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                handleDisconnect();
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
                raiseError(new Exception("WebSocket failure: " + t.getMessage()));
                handleDisconnect();
            }
        });
    }

    public void disconnect() {
        config.autoReconnect = false;
        cleanup();
        setState(VoiceClientState.DISCONNECTED);
        notifyDisconnected();
    }

    public void joinRoom(String roomId) {
        if (state.ordinal() < VoiceClientState.CONNECTED.ordinal()) {
            throw new IllegalStateException("Not connected");
        }

        if (currentRoom != null) {
            leaveRoom();
        }

        audio.startPlayback();

        try {
            JSONObject joinObj = new JSONObject();
            joinObj.put("type", "join");
            joinObj.put("room", roomId);
            joinObj.put("peer", config.peerId);
            joinObj.put("sampleRate", audio.getSampleRate());
            sendText(joinObj.toString());
        } catch (Exception e) {
            raiseError(e);
        }

        currentRoom = roomId;
        setState(VoiceClientState.IN_ROOM);

        speakingTimer = new Timer();
        speakingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkSpeaking();
            }
        }, 0, SPEAKING_THRESHOLD_CHECK_MS);

        notifyRoomJoined(roomId);
    }

    public void leaveRoom() {
        if (currentRoom == null) return;

        stopMic();
        if (speakingTimer != null) {
            speakingTimer.cancel();
            speakingTimer = null;
        }

        String room = currentRoom;

        List<String> peerIdsCopy = new ArrayList<>(peers.keySet());
        peers.clear();
        for (String peerId : peerIdsCopy) {
            notifyPeerLeft(peerId);
        }

        audio.clearPeers();
        audio.stopPlayback();

        currentRoom = null;
        setState(VoiceClientState.CONNECTED);
        notifyRoomLeft(room);
    }

    public void enableMic() {
        if (micEnabled) return;
        if (state != VoiceClientState.IN_ROOM) {
            throw new IllegalStateException("Must be in a room to enable mic");
        }

        audio.startCapture();
        micEnabled = true;
        notifyMicEnabled();
    }

    public void disableMic() {
        if (!micEnabled) return;
        audio.stopCapture();
        micEnabled = false;
        notifyMicDisabled();
    }

    public boolean toggleMic() {
        if (micEnabled) {
            disableMic();
            return false;
        } else {
            enableMic();
            return true;
        }
    }

    public void enableSpeaker() {
        if (speakerEnabled) return;
        speakerEnabled = true;
        audio.setSpeakerphoneOn(true);
        notifySpeakerEnabled();
    }

    public void disableSpeaker() {
        if (!speakerEnabled) return;
        speakerEnabled = false;
        audio.setSpeakerphoneOn(false);
        notifySpeakerDisabled();
    }

    public boolean toggleSpeaker() {
        if (speakerEnabled) {
            disableSpeaker();
            return false;
        } else {
            enableSpeaker();
            return true;
        }
    }

    public boolean isPeerSpeaking(String peerId) {
        PeerState peer = peers.get(peerId);
        return peer != null && peer.isSpeaking;
    }

    public void dispose() {
        config.autoReconnect = false;
        cleanup();
    }

    private void handleTextMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");
            if ("udp_token".equals(type)) {
                // Handle UDP token if needed
            }
        } catch (Exception e) {
            raiseError(e);
        }
    }

    private void handleBinaryMessage(byte[] data) {
        if (data.length < 4) return;

        int roomLen = data[0] & 0xFF;
        if (data.length < 2 + roomLen) return;

        int peerLen = data[1 + roomLen] & 0xFF;
        if (data.length < 2 + roomLen + peerLen) return;

        String peerId = new String(data, 2 + roomLen, peerLen, StandardCharsets.UTF_8);
        if (peerId.equals(config.peerId)) return;

        int audioOffset = 2 + roomLen + peerLen;
        int audioLen = data.length - audioOffset;
        if (audioLen == 0) return;

        int sampleCount = audioLen / 2;
        short[] samples = new short[sampleCount];
        ByteBuffer.wrap(data, audioOffset, audioLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);

        handlePeerAudio(peerId, samples);
    }

    private void handlePeerAudio(String peerId, short[] samples) {
        if (!peers.containsKey(peerId)) {
            peers.put(peerId, new PeerState(peerId));
            notifyPeerJoined(peerId);
        }

        audio.enqueuePeerAudio(peerId, samples);
        PeerState peer = peers.get(peerId);
        if (peer != null) {
            peer.enqueue(samples);
        }
    }

    private void checkSpeaking() {
        for (Map.Entry<String, PeerState> entry : peers.entrySet()) {
            String peerId = entry.getKey();
            PeerState peer = entry.getValue();

            double rms = peer.computeRms();
            boolean wasSpeaking = peer.isSpeaking;
            boolean isSpeaking = rms > SPEAKING_RMS_THRESHOLD;

            if (isSpeaking != wasSpeaking) {
                peer.isSpeaking = isSpeaking;
                if (isSpeaking) {
                    notifyPeerSpeaking(peerId);
                } else {
                    notifyPeerStopped(peerId);
                }
            }

            peer.rmsLevel = rms;
        }
    }

    private void onAudioCaptured(short[] samples) {
        if (!micEnabled || webSocket == null) return;

        byte[] bytes = new byte[samples.length * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
        sendBinary(bytes);
    }

    private void sendText(String text) {
        if (webSocket != null) {
            webSocket.send(text);
        }
    }

    private void sendBinary(byte[] data) {
        if (webSocket != null) {
            try {
                webSocket.send(okio.ByteString.of(data));
            } catch (Exception e) {
                raiseError(e);
            }
        }
    }

    private void handleClose(int code, String description) {
        if (code == 1008 || code == 4001) {
            notifyAuthFailed(description != null ? description : "Authentication failed");
            config.autoReconnect = false;
        }
    }

    private void handleDisconnect() {
        boolean wasConnected = state.ordinal() >= VoiceClientState.CONNECTED.ordinal();

        prevRoom = currentRoom;
        prevMicEnabled = micEnabled;

        cleanup();

        if (!wasConnected) return;

        setState(VoiceClientState.DISCONNECTED);
        notifyDisconnected();

        if (config.autoReconnect && reconnectAttempts < config.maxReconnectAttempts) {
            reconnectAttempts++;
            setState(VoiceClientState.RECONNECTING);
            notifyReconnecting(reconnectAttempts);

            mainHandler.postDelayed(this::connect, config.reconnectDelayMs);
        }
    }

    private void cleanup() {
        stopMic();
        if (speakingTimer != null) {
            speakingTimer.cancel();
            speakingTimer = null;
        }

        audio.clearPeers();
        audio.stopPlayback();

        List<String> peerIdsCopy = new ArrayList<>(peers.keySet());
        peers.clear();
        for (String peerId : peerIdsCopy) {
            notifyPeerLeft(peerId);
        }

        if (webSocket != null) {
            try {
                webSocket.close(1000, "bye");
            } catch (Exception e) {
                // Ignore
            }
            webSocket = null;
        }

        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            httpClient = null;
        }

        currentRoom = null;
    }

    private void stopMic() {
        if (!micEnabled) return;
        audio.stopCapture();
        micEnabled = false;
    }

    private String resolveScheme() {
        if (config.useTls != null) {
            return config.useTls ? "wss" : "ws";
        }
        return "ws";
    }

    private void setState(VoiceClientState newState) {
        if (state == newState) return;
        state = newState;
        notifyStateChanged(newState);
    }

    private void raiseError(Exception e) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onError(e);
            }
        });
    }

    private void notifyConnected() {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onConnected();
            }
        });
    }

    private void notifyDisconnected() {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onDisconnected();
            }
        });
    }

    private void notifyReconnecting(int attempt) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onReconnecting(attempt);
            }
        });
    }

    private void notifyAuthFailed(String reason) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onAuthFailed(reason);
            }
        });
    }

    private void notifyRoomJoined(String roomId) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onRoomJoined(roomId);
            }
        });
    }

    private void notifyRoomLeft(String roomId) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onRoomLeft(roomId);
            }
        });
    }

    private void notifyPeerJoined(String peerId) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onPeerJoined(peerId);
            }
        });
    }

    private void notifyPeerLeft(String peerId) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onPeerLeft(peerId);
            }
        });
    }

    private void notifyPeerSpeaking(String peerId) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onPeerSpeaking(peerId);
            }
        });
    }

    private void notifyPeerStopped(String peerId) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onPeerStopped(peerId);
            }
        });
    }

    private void notifyMicEnabled() {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onMicEnabled();
            }
        });
    }

    private void notifyMicDisabled() {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onMicDisabled();
            }
        });
    }

    private void notifySpeakerEnabled() {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onSpeakerEnabled();
            }
        });
    }

    private void notifySpeakerDisabled() {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onSpeakerDisabled();
            }
        });
    }

    private void notifyStateChanged(VoiceClientState newState) {
        mainHandler.post(() -> {
            for (VoiceEventListener listener : listeners) {
                listener.onStateChanged(newState);
            }
        });
    }

    private static class PeerState {
        private final short[] ring = new short[PEER_RING_CAPACITY];
        private int writePos = 0;
        private int length = 0;
        private final Object lock = new Object();

        public String peerId;
        public boolean isSpeaking;
        public double rmsLevel;

        public PeerState(String peerId) {
            this.peerId = peerId;
        }

        public void enqueue(short[] samples) {
            synchronized (lock) {
                if (length + samples.length > PEER_RING_CAPACITY) {
                    int drop = length + samples.length - PEER_RING_CAPACITY;
                    length -= drop;
                }
                for (int i = 0; i < samples.length; i++) {
                    ring[(writePos + i) % PEER_RING_CAPACITY] = samples[i];
                }
                writePos = (writePos + samples.length) % PEER_RING_CAPACITY;
                length += samples.length;
            }
        }

        public double computeRms() {
            synchronized (lock) {
                int count = Math.min(length, 480);
                if (count == 0) return 0;

                double sum = 0;
                int start = (writePos - count + PEER_RING_CAPACITY) % PEER_RING_CAPACITY;
                for (int i = 0; i < count; i++) {
                    double v = ring[(start + i) % PEER_RING_CAPACITY] / 32768.0;
                    sum += v * v;
                }
                return Math.sqrt(sum / count);
            }
        }

        public void clear() {
            synchronized (lock) {
                writePos = 0;
                length = 0;
                isSpeaking = false;
                rmsLevel = 0;
            }
        }
    }
}
