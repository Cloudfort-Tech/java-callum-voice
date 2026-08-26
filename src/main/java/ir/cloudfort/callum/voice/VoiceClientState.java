package ir.cloudfort.callum.voice;

/**
 * Connection state of the VoiceClient.
 */
public enum VoiceClientState {
    /** Not connected, not attempting to connect. */
    DISCONNECTED,

    /** Establishing WebSocket connection. */
    CONNECTING,

    /** Connected to server, not yet in a room. */
    CONNECTED,

    /** Joined a room and ready for audio. */
    IN_ROOM,

    /** Attempting to reconnect after an unexpected disconnect. */
    RECONNECTING
}
