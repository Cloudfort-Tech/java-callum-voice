package ir.cloudfort.callum.voice;

/**
 * Event listener interface for VoiceClient events.
 * Implement this interface to receive voice chat events.
 */
public interface VoiceEventListener {
    /** Connection established. */
    default void onConnected() {}

    /** Connection lost. */
    default void onDisconnected() {}

    /** Reconnection attempt in progress. */
    default void onReconnecting(int attempt) {}

    /** Authentication failed (invalid API key or suspended account). */
    default void onAuthFailed(String reason) {}

    /** Joined a room. */
    default void onRoomJoined(String roomId) {}

    /** Left a room. */
    default void onRoomLeft(String roomId) {}

    /** New peer joined the room. */
    default void onPeerJoined(String peerId) {}

    /** Peer left the room. */
    default void onPeerLeft(String peerId) {}

    /** Peer started speaking. */
    default void onPeerSpeaking(String peerId) {}

    /** Peer stopped speaking. */
    default void onPeerStopped(String peerId) {}

    /** Microphone enabled. */
    default void onMicEnabled() {}

    /** Microphone disabled. */
    default void onMicDisabled() {}

    /** Speaker enabled (Android). */
    default void onSpeakerEnabled() {}

    /** Speaker disabled (Android). */
    default void onSpeakerDisabled() {}

    /** Error occurred. */
    default void onError(Exception error) {}

    /** Connection state changed. */
    default void onStateChanged(VoiceClientState newState) {}
}
