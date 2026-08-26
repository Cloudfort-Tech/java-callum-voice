package ir.cloudfort.callum.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Android audio engine using AudioRecord (capture) and AudioTrack (playback).
 * Handles microphone input, speaker output, and multi-peer audio mixing.
 */
public class AudioEngine {
    private static final String TAG = "AudioEngine";
    private static final int PEER_RING_CAPACITY = 96000; // ~2s at 48 kHz
    private static final int FRAME_SAMPLES = 960;        // 20ms at 48 kHz
    private static final int JITTER_FRAMES = 4;          // 80ms jitter buffer
    private static final int MIX_SAMPLES = FRAME_SAMPLES * JITTER_FRAMES; // 3840

    /**
     * Ring buffer for peer audio data.
     */
    private static class PeerRing {
        private final short[] buffer = new short[PEER_RING_CAPACITY];
        private int writePos = 0;
        private int length = 0;
        private final Object lock = new Object();

        public void enqueue(short[] samples) {
            synchronized (lock) {
                if (length + samples.length > PEER_RING_CAPACITY) {
                    int drop = length + samples.length - PEER_RING_CAPACITY;
                    length -= drop;
                }
                for (int i = 0; i < samples.length; i++) {
                    buffer[(writePos + i) % PEER_RING_CAPACITY] = samples[i];
                }
                writePos = (writePos + samples.length) % PEER_RING_CAPACITY;
                length += samples.length;
            }
        }

        public int read(short[] dest, int maxSamples) {
            synchronized (lock) {
                int count = Math.min(length, maxSamples);
                int start = (writePos - length + PEER_RING_CAPACITY) % PEER_RING_CAPACITY;
                for (int i = 0; i < count; i++) {
                    dest[i] = buffer[(start + i) % PEER_RING_CAPACITY];
                }
                length -= count;
                return count;
            }
        }

        public int available() {
            synchronized (lock) {
                return length;
            }
        }

        public void clear() {
            synchronized (lock) {
                writePos = 0;
                length = 0;
            }
        }
    }

    private final int sampleRate;
    private final ConcurrentHashMap<String, PeerRing> peerQueues = new ConcurrentHashMap<>();

    private AudioRecord recorder;
    private AudioTrack player;
    private Thread captureThread;
    private Thread playbackThread;
    private volatile boolean captureRunning;
    private volatile boolean playbackRunning;

    private PowerManager.WakeLock wakeLock;
    private float volume = 1.0f;

    private AudioCapturedListener audioCapturedListener;
    private ErrorListener errorListener;

    public interface AudioCapturedListener {
        void onAudioCaptured(short[] samples);
    }

    public interface ErrorListener {
        void onError(String message);
    }

    public AudioEngine(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public AudioEngine() {
        this(48000);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setAudioCapturedListener(AudioCapturedListener listener) {
        this.audioCapturedListener = listener;
    }

    public void setErrorListener(ErrorListener listener) {
        this.errorListener = listener;
    }

    /**
     * Start capturing audio from the microphone.
     */
    public void startCapture() {
        if (captureRunning) return;

        int minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuf <= 0) minBuf = 4096;

        recorder = new AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        );

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (errorListener != null) {
                errorListener.onError("AudioRecord failed to initialize");
            }
            return;
        }

        recorder.startRecording();
        captureRunning = true;

        captureThread = new Thread(this::captureLoop, "AudioRecord-Capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void captureLoop() {
        short[] buffer = new short[960]; // 20ms at 48 kHz
        try {
            while (captureRunning) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    short[] samples = new short[read];
                    System.arraycopy(buffer, 0, samples, 0, read);
                    if (audioCapturedListener != null) {
                        audioCapturedListener.onAudioCaptured(samples);
                    }
                } else if (read < 0) {
                    if (errorListener != null) {
                        errorListener.onError("AudioRecord read error: " + read);
                    }
                    Thread.sleep(10);
                }
            }
        } catch (Exception e) {
            if (captureRunning && errorListener != null) {
                errorListener.onError("Capture error: " + e.getMessage());
            }
        }
    }

    /**
     * Stop capturing audio from the microphone.
     */
    public void stopCapture() {
        captureRunning = false;
        if (captureThread != null) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                // Ignore
            }
            recorder.release();
            recorder = null;
        }
    }

    /**
     * Start playback engine (mixer ready to accept peer audio).
     */
    public void startPlayback() {
        if (playbackRunning) return;

        int minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuf <= 0) minBuf = 4096;

        int trackBuf = Math.max(minBuf * 8, MIX_SAMPLES * 4);

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

        AudioFormat fmt = new AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build();

        player = new AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(trackBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        if (player.getState() != AudioTrack.STATE_INITIALIZED) {
            if (errorListener != null) {
                errorListener.onError("AudioTrack failed to initialize");
            }
            return;
        }

        player.play();
        playbackRunning = true;
        player.setVolume(1.0f);

        try {
            Context ctx = VoiceForegroundService.getAppContext();
            if (ctx != null) {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                    int target = (int) (max * 0.8f);
                    if (target > 0) {
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        playbackThread = new Thread(this::playbackLoop, "AudioTrack-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    private void playbackLoop() {
        short[] mixBuffer = new short[MIX_SAMPLES];
        int warmupFrames = JITTER_FRAMES * 4; // ~320ms

        try {
            while (playbackRunning) {
                java.util.Arrays.fill(mixBuffer, (short) 0);
                boolean hasData = false;

                if (warmupFrames > 0) {
                    int totalAvailable = 0;
                    for (PeerRing ring : peerQueues.values()) {
                        totalAvailable += ring.available();
                    }
                    if (totalAvailable < warmupFrames) {
                        player.write(mixBuffer, 0, mixBuffer.length);
                        Thread.sleep(10);
                        continue;
                    }
                    warmupFrames = 0;
                }

                int peerCount = peerQueues.size();
                if (peerCount > 0) {
                    float peerScale = Math.min(1.0f, 0.8f / peerCount);

                    for (PeerRing ring : peerQueues.values()) {
                        int read = ring.read(mixBuffer, MIX_SAMPLES);
                        if (read > 0) hasData = true;
                    }

                    if (peerCount > 1) {
                        for (int i = 0; i < mixBuffer.length; i++) {
                            mixBuffer[i] = (short) (mixBuffer[i] * peerScale);
                        }
                    }
                }

                player.write(mixBuffer, 0, mixBuffer.length);

                if (!hasData) {
                    Thread.sleep(10);
                }
            }
        } catch (Exception e) {
            if (playbackRunning && errorListener != null) {
                errorListener.onError("Playback error: " + e.getMessage());
            }
        }
    }

    /**
     * Stop playback engine.
     */
    public void stopPlayback() {
        playbackRunning = false;
        if (playbackThread != null) {
            try {
                playbackThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playbackThread = null;
        }

        if (player != null) {
            try {
                player.stop();
            } catch (Exception e) {
                // Ignore
            }
            try {
                player.flush();
            } catch (Exception e) {
                // Ignore
            }
            player.release();
            player = null;
        }
    }

    /**
     * Enqueue PCM 16-bit mono samples from a remote peer for playback.
     */
    public void enqueuePeerAudio(String peerId, short[] samples) {
        PeerRing ring = peerQueues.get(peerId);
        if (ring == null) {
            ring = new PeerRing();
            peerQueues.put(peerId, ring);
        }
        ring.enqueue(samples);
    }

    /**
     * Remove a peer's buffers.
     */
    public void removePeer(String peerId) {
        PeerRing ring = peerQueues.remove(peerId);
        if (ring != null) {
            ring.clear();
        }
    }

    /**
     * Clear all peer buffers without stopping playback.
     */
    public void clearPeers() {
        for (PeerRing ring : peerQueues.values()) {
            ring.clear();
        }
        peerQueues.clear();
    }

    /**
     * Set the playback volume (0.0 to 2.0).
     */
    public void setPlaybackVolume(float volume) {
        this.volume = Math.max(0f, Math.min(2f, volume));
        if (player != null) {
            player.setVolume(this.volume);
        }
    }

    /**
     * Switch audio output between earpiece and loudspeaker.
     */
    public void setSpeakerphoneOn(boolean on) {
        try {
            Context ctx = VoiceForegroundService.getAppContext();
            if (ctx == null) return;

            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            am.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && player != null) {
                android.media.AudioDeviceInfo targetDevice = null;
                android.media.AudioDeviceInfo[] outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

                for (android.media.AudioDeviceInfo dev : outputDevices) {
                    if (on && dev.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        targetDevice = dev;
                        break;
                    }
                    if (!on && dev.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        targetDevice = dev;
                        break;
                    }
                }

                if (targetDevice != null) {
                    boolean ok = player.setPreferredDevice(targetDevice);
                    Log.d(TAG, "setPreferredDevice type=" + targetDevice.getType() + " result=" + ok);
                }
            }

            Log.d(TAG, "Speakerphone " + (on ? "ON (loudspeaker)" : "OFF (earpiece)"));
        } catch (Exception e) {
            if (errorListener != null) {
                errorListener.onError("SetSpeakerphone failed: " + e.getMessage());
            }
        }
    }

    /**
     * Prevent the screen from turning off and keep the CPU running.
     */
    public void keepScreenAwake(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Callum:Voice");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            if (errorListener != null) {
                errorListener.onError("KeepScreenAwake failed: " + e.getMessage());
            }
        }
    }

    /**
     * Release the screen-awake / wake-lock.
     */
    public void releaseScreenAwake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Release all resources.
     */
    public void dispose() {
        stopCapture();
        stopPlayback();
        releaseScreenAwake();
        clearPeers();

        try {
            Context ctx = VoiceForegroundService.getAppContext();
            if (ctx != null) {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.setMode(AudioManager.MODE_NORMAL);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}
