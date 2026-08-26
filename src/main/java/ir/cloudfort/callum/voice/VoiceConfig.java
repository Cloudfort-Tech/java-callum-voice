package ir.cloudfort.callum.voice;

/**
 * Configuration for connecting to the voice chat service.
 */
public class VoiceConfig {
    /** Server address (e.g. "callem.cloudfort.ir"). Do NOT include scheme or port. */
    public String server = "";

    /** API key obtained from account registration (vc_live_...). */
    public String apiKey = "";

    /** Unique identifier for this peer / user. */
    public String peerId = "";

    /** Use WSS (TLS) instead of WS. Auto-detected when null. */
    public Boolean useTls = null;

    /** Audio sample rate in Hz (default 48000). */
    public int sampleRate = 48000;

    /** Automatically reconnect on unexpected disconnect (default true). */
    public boolean autoReconnect = true;

    /** Maximum reconnection attempts before giving up (default 5). */
    public int maxReconnectAttempts = 5;

    /** Delay between reconnection attempts in milliseconds (default 2000). */
    public long reconnectDelayMs = 2000;

    /** Enable platform echo cancellation (default true). */
    public boolean echoCancellation = true;

    /** Enable platform noise suppression (default true). */
    public boolean noiseSuppression = true;

    /** Enable platform automatic gain control (default true). */
    public boolean autoGainControl = true;
}
