# Callum.Voice Android SDK — Real-Time Voice Chat Library

A **Java library** for Android real-time voice communication over a Go server (`phonil-opus`).  
All **microphone capture**, **audio playback**, **WebSocket connection**, **multi-peer audio mixing**, **speaking detection**, and **room management** logic is implemented in this library.

---

## Table of Contents

- [Architecture](#architecture)
- [File Structure](#file-structure)
- [Communication Protocol](#communication-protocol)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Complete Example](#complete-example)
- [API Reference](#api-reference)
- [VoiceConfig](#voiceconfig)
- [VoiceEventListener](#voiceeventlistener)
- [AudioEngine](#audioengine)
- [Permissions](#permissions)
- [Foreground Service](#foreground-service)
- [Important Notes](#important-notes)

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│              Callum.Voice Android SDK                    │
│                                                          │
│  ┌─────────────┐     ┌──────────────┐     ┌──────────┐   │
│  │ VoiceClient │───▶│ AudioEngine  │────▶│ Android  │   │
│  │             │◀───│              │◀────│  Audio   │   │
│  │ • WebSocket │     │ • Capture    │     │  APIs    │   │
│  │ • Room Mgmt │     │ • Playback   │     │          │   │
│  │ • Peer Track│     │ • Volume     │     │ • AudioR.│   │
│  │ • Speaking  │     │ • Mixing     │     │ • AudioT.│   │
│  │ • Reconnect │     │ • Power Mgmt │     │          │   │
│  └─────────────┘     └──────────────┘     └──────────┘   │
│         │                                      ▲         │
│         │ WebSocket (PCM 16-bit mono)          │         │
│         ▼                                      │         │
│  ┌─────────────┐                               │         │
│  │  Go Server   │────── PCM Audio ─────────────┘         │
│  │ (phonil-opus)│                                        │
│  └─────────────┘                                         │
└──────────────────────────────────────────────────────────┘
```

### Layers

| Layer | Responsibility |
|-------|---------------|
| **VoiceClient** | Network logic (WebSocket), room management, peer tracking, speaking detection, auto-reconnect |
| **AudioEngine** | Android-specific audio capture (AudioRecord) and playback (AudioTrack), multi-peer mixing, jitter buffer |
| **VoiceForegroundService** | Android foreground service to keep voice call alive in background |

---

## File Structure

```
callum-voice/
├── build.gradle                          # Android library build config
├── src/main/
│   ├── AndroidManifest.xml               # Permissions and service declaration
│   └── java/ir/cloudfort/callum/voice/
│       ├── VoiceClient.java              # Core logic: WebSocket, room, mixing, speaking detection
│       ├── VoiceConfig.java              # Connection settings (server, API key, sample rate)
│       ├── VoiceClientState.java         # Connection states (DISCONNECTED → CONNECTED → IN_ROOM)
│       ├── VoiceEventListener.java       # Event callback interface
│       ├── AudioEngine.java              # Audio capture/playback with AudioRecord/AudioTrack + Ring Buffer
│       └── VoiceForegroundService.java   # Android foreground service for background calls
└── README.md                             # This file
```

---

## Communication Protocol

### WebSocket Connection

```
ws(s)://{server}/ws?room=__lobby__&peer={peerId}&api_key={apiKey}
```

### Text Message (JSON) — Join Room

```json
{
  "type": "join",
  "room": "room-id",
  "peer": "user-123",
  "sampleRate": 48000
}
```

### Binary Message (Audio) — Packet Format

```
[1B RoomLen][RoomID][1B PeerLen][PeerID][PCM Audio Data]
```

- **PCM Format**: 16-bit signed integer, mono, 48 kHz
- Each packet contains the room ID, sender ID, and raw audio data

---

## Installation

### Option A: Maven Central (Recommended)

Add the dependency in your app's `build.gradle`:

```gradle
// app/build.gradle
dependencies {
    implementation 'ir.cloudfort:callum-voice:0.0.2'
}
```

Available on [Maven Central](https://central.sonatype.com/artifact/ir.cloudfort/callum-voice/overview).

### Option B: Local Module

Copy the `callum-voice` folder to your Android project root, then add it to your `settings.gradle`:

```gradle
// settings.gradle
include ':app', ':callum-voice'
```

Then in your app's `build.gradle`:

```gradle
// app/build.gradle
dependencies {
    implementation project(':callum-voice')
}
```

### Step 3: Request Permissions

In your Activity or Fragment, request the `RECORD_AUDIO` permission at runtime:

```java
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(this,
        new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
}
```

---

## Quick Start

```java
import ir.cloudfort.callum.voice.*;

public class VoiceActivity extends AppCompatActivity {
    private VoiceClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);

        // 1. Create audio engine
        AudioEngine audio = new AudioEngine(48000);

        // 2. Create config
        VoiceConfig config = new VoiceConfig();
        config.server = "callem.cloudfort.ir";
        config.apiKey = "vc_live_YOUR_API_KEY";
        config.peerId = "user-" + UUID.randomUUID().toString();
        config.autoReconnect = true;

        // 3. Create client
        client = new VoiceClient(config, audio);

        // 4. Register event listener
        client.addListener(new VoiceEventListener() {
            @Override
            public void onConnected() {
                Log.d("Voice", "Connected to server");
                client.joinRoom("general");
            }

            @Override
            public void onRoomJoined(String roomId) {
                Log.d("Voice", "Joined room: " + roomId);
                client.enableMic();
            }

            @Override
            public void onPeerJoined(String peerId) {
                Log.d("Voice", "Peer joined: " + peerId);
            }

            @Override
            public void onPeerSpeaking(String peerId) {
                Log.d("Voice", "Peer speaking: " + peerId);
            }

            @Override
            public void onError(Exception error) {
                Log.e("Voice", "Error: " + error.getMessage());
            }
        });

        // 5. Connect
        client.connect();
    }

    @Override
    protected void onDestroy() {
        client.dispose();
        super.onDestroy();
    }
}
```

---

## Complete Example

A full-featured voice chat Activity with mic toggle, speaker toggle, and peer list:

```java
package com.example.voicechat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.UUID;

import ir.cloudfort.callum.voice.*;

public class VoiceActivity extends AppCompatActivity {
    private static final String TAG = "VoiceActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private VoiceClient client;
    private TextView txtStatus;
    private TextView txtPeers;
    private TextView txtSpeaking;
    private Button btnMic;
    private Button btnSpeaker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice);

        txtStatus = findViewById(R.id.txtStatus);
        txtPeers = findViewById(R.id.txtPeers);
        txtSpeaking = findViewById(R.id.txtSpeaking);
        btnMic = findViewById(R.id.btnMic);
        btnSpeaker = findViewById(R.id.btnSpeaker);

        btnMic.setOnClickListener(v -> toggleMic());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());

        // Request microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
        } else {
            initVoiceClient();
        }
    }

    private void initVoiceClient() {
        // Create audio engine
        AudioEngine audio = new AudioEngine(48000);

        // Create config
        VoiceConfig config = new VoiceConfig();
        config.server = "callem.cloudfort.ir";
        config.apiKey = "vc_live_YOUR_API_KEY"; // Replace with your API key
        config.peerId = "android-user-" + UUID.randomUUID().toString();
        config.autoReconnect = true;
        config.maxReconnectAttempts = 5;
        config.reconnectDelayMs = 2000;

        // Create client
        client = new VoiceClient(config, audio);

        // Register event listener
        client.addListener(new VoiceEventListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    txtStatus.setText("Connected");
                    client.joinRoom("general");
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> txtStatus.setText("Disconnected"));
            }

            @Override
            public void onReconnecting(int attempt) {
                runOnUiThread(() -> txtStatus.setText("Reconnecting... attempt " + attempt));
            }

            @Override
            public void onAuthFailed(String reason) {
                runOnUiThread(() -> txtStatus.setText("Auth failed: " + reason));
            }

            @Override
            public void onRoomJoined(String roomId) {
                runOnUiThread(() -> {
                    txtStatus.setText("In room: " + roomId);
                    client.enableMic();
                });
            }

            @Override
            public void onRoomLeft(String roomId) {
                runOnUiThread(() -> txtStatus.setText("Left room: " + roomId));
            }

            @Override
            public void onPeerJoined(String peerId) {
                runOnUiThread(() -> {
                    String peers = String.join(", ", client.getPeers());
                    txtPeers.setText("Peers: " + peers);
                });
            }

            @Override
            public void onPeerLeft(String peerId) {
                runOnUiThread(() -> {
                    String peers = String.join(", ", client.getPeers());
                    txtPeers.setText("Peers: " + (peers.isEmpty() ? "None" : peers));
                });
            }

            @Override
            public void onPeerSpeaking(String peerId) {
                runOnUiThread(() -> txtSpeaking.setText(peerId + " is speaking..."));
            }

            @Override
            public void onPeerStopped(String peerId) {
                runOnUiThread(() -> txtSpeaking.setText(""));
            }

            @Override
            public void onMicEnabled() {
                runOnUiThread(() -> btnMic.setText("🎤 Mute"));
            }

            @Override
            public void onMicDisabled() {
                runOnUiThread(() -> btnMic.setText("🎤 Unmute"));
            }

            @Override
            public void onSpeakerEnabled() {
                runOnUiThread(() -> btnSpeaker.setText("🔊 Speaker ON"));
            }

            @Override
            public void onSpeakerDisabled() {
                runOnUiThread(() -> btnSpeaker.setText("🔊 Speaker OFF"));
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> Log.e(TAG, "Error: " + error.getMessage()));
            }

            @Override
            public void onStateChanged(VoiceClientState newState) {
                Log.d(TAG, "State changed: " + newState);
            }
        });

        // Connect to server
        client.connect();
    }

    private void toggleMic() {
        if (client != null) {
            client.toggleMic();
        }
    }

    private void toggleSpeaker() {
        if (client != null) {
            client.toggleSpeaker();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initVoiceClient();
            } else {
                txtStatus.setText("Microphone permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.dispose();
        }
        super.onDestroy();
    }
}
```

### Layout XML (activity_voice.xml)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/txtStatus"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Disconnected"
        android:textSize="18sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/txtPeers"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Peers: None"
        android:layout_marginTop="8dp" />

    <TextView
        android:id="@+id/txtSpeaking"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp" />

    <Button
        android:id="@+id/btnMic"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="🎤 Unmute"
        android:layout_marginTop="16dp" />

    <Button
        android:id="@+id/btnSpeaker"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="🔊 Speaker OFF" />

</LinearLayout>
```

---

## API Reference

### VoiceClient

| Method | Return Type | Description |
|--------|-------------|-------------|
| `connect()` | `void` | Connect to server |
| `disconnect()` | `void` | Disconnect from server |
| `joinRoom(roomId)` | `void` | Join a room |
| `leaveRoom()` | `void` | Leave current room |
| `enableMic()` | `void` | Enable microphone |
| `disableMic()` | `void` | Disable microphone |
| `toggleMic()` | `boolean` | Toggle microphone state, returns new state |
| `enableSpeaker()` | `void` | Enable speaker (Android) |
| `disableSpeaker()` | `void` | Disable speaker |
| `toggleSpeaker()` | `boolean` | Toggle speaker state, returns new state |
| `isPeerSpeaking(peerId)` | `boolean` | Check if peer is speaking |
| `getPeers()` | `Collection<String>` | Get list of peers in room |
| `addListener(listener)` | `void` | Register event listener |
| `removeListener(listener)` | `void` | Remove event listener |
| `dispose()` | `void` | Release resources |

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `getState()` | `VoiceClientState` | Current connection state |
| `isConnected()` | `boolean` | Whether connected to server |
| `isInRoom()` | `boolean` | Whether in a room |
| `isMicEnabled()` | `boolean` | Whether microphone is enabled |
| `isSpeakerEnabled()` | `boolean` | Whether speaker is enabled |
| `getCurrentRoom()` | `String` | Current room ID |
| `getPeers()` | `Collection<String>` | List of peers in room |

---

## VoiceConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `server` | `String` | — | Server address (e.g., `callem.cloudfort.ir`) |
| `apiKey` | `String` | — | API key (`vc_live_...`) |
| `peerId` | `String` | — | Unique peer identifier |
| `useTls` | `Boolean` | `null` | Use WSS (`null` = auto-detect) |
| `sampleRate` | `int` | `48000` | Audio sample rate (Hz) |
| `autoReconnect` | `boolean` | `true` | Auto reconnect on disconnect |
| `maxReconnectAttempts` | `int` | `5` | Maximum reconnect attempts |
| `reconnectDelayMs` | `long` | `2000` | Delay between attempts (ms) |
| `echoCancellation` | `boolean` | `true` | Echo cancellation |
| `noiseSuppression` | `boolean` | `true` | Noise suppression |
| `autoGainControl` | `boolean` | `true` | Automatic gain control |

---

## VoiceEventListener

All methods have default empty implementations, so you only override what you need:

| Method | Description |
|--------|-------------|
| `onConnected()` | Connection established |
| `onDisconnected()` | Connection lost |
| `onReconnecting(int attempt)` | Reconnection attempt (with attempt number) |
| `onAuthFailed(String reason)` | Authentication failed |
| `onRoomJoined(String roomId)` | Joined a room |
| `onRoomLeft(String roomId)` | Left a room |
| `onPeerJoined(String peerId)` | New peer joined |
| `onPeerLeft(String peerId)` | Peer left |
| `onPeerSpeaking(String peerId)` | Peer started speaking |
| `onPeerStopped(String peerId)` | Peer stopped speaking |
| `onMicEnabled()` | Microphone enabled |
| `onMicDisabled()` | Microphone disabled |
| `onSpeakerEnabled()` | Speaker enabled |
| `onSpeakerDisabled()` | Speaker disabled |
| `onError(Exception error)` | Error occurred |
| `onStateChanged(VoiceClientState newState)` | Connection state changed |

---

## AudioEngine

The `AudioEngine` class handles all audio capture and playback on Android:

### Constructor

```java
AudioEngine audio = new AudioEngine(48000); // 48 kHz sample rate
```

### Key Features

- **AudioRecord** for microphone capture (VOICE_COMMUNICATION source)
- **AudioTrack** for speaker playback (VOICE_COMMUNICATION usage)
- **Ring buffers** for jitter absorption (~2s per peer)
- **Multi-peer mixing** with automatic volume scaling
- **Speaker routing** (earpiece vs loudspeaker)
- **Wake lock** to keep CPU alive during calls

### Methods

| Method | Description |
|--------|-------------|
| `startCapture()` | Start microphone capture |
| `stopCapture()` | Stop microphone capture |
| `startPlayback()` | Start playback engine |
| `stopPlayback()` | Stop playback engine |
| `enqueuePeerAudio(peerId, samples)` | Enqueue audio from a remote peer |
| `removePeer(peerId)` | Remove peer's audio buffers |
| `clearPeers()` | Clear all peer buffers |
| `setPlaybackVolume(volume)` | Set playback volume (0.0 to 2.0) |
| `setSpeakerphoneOn(on)` | Route audio to speaker or earpiece |
| `keepScreenAwake(context)` | Acquire wake lock |
| `releaseScreenAwake()` | Release wake lock |
| `dispose()` | Release all resources |

---

## Permissions

### Required Permissions

The SDK automatically declares these permissions in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### Runtime Permission Request

You **must** request `RECORD_AUDIO` permission at runtime before calling `enableMic()`:

```java
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(this,
        new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
}
```

---

## Foreground Service

The SDK includes `VoiceForegroundService` to keep the voice call alive when the app is in the background.

### How It Works

- Automatically started when you call `audio.keepScreenAwake(context)`
- Shows a persistent notification: "Voice Call - Connected to voice chat"
- Keeps the CPU awake with a partial wake lock
- Declared in `AndroidManifest.xml` with `foregroundServiceType="microphone"`

### Customizing the Notification

To customize the notification, edit `VoiceForegroundService.java`:

```java
private Notification buildNotification() {
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Your App Name")
        .setContentText("In voice call")
        .setSmallIcon(R.drawable.your_icon) // Use your app icon
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setShowWhen(false)
        .build();
}
```

---

## Important Notes

### Audio Format

- **PCM 16-bit signed integer**
- **Mono** (single channel)
- **48,000 Hz** sample rate (default)
- **Bitrate**: ~768 kbps (48000 × 16 × 1)

### Minimum Android Version

- **minSdk 23** (Android 6.0 Marshmallow)
- Tested on Android 6.0 through Android 14

### Dependencies

- **OkHttp 4.12.0** — WebSocket client
- **AndroidX Annotation** — Nullability annotations

### Thread Safety

- All event callbacks are dispatched on the **main (UI) thread**
- You can safely update UI directly in event handlers
- Audio capture and playback run on background threads

### Lifecycle Management

- Call `client.dispose()` in `onDestroy()` to release all resources
- The SDK automatically stops mic, playback, and WebSocket on disconnect
- Foreground service is stopped automatically when you call `audio.releaseScreenAwake()`

### Server

The Go server (`phonil-opus`) is available at `callem.cloudfort.ir`.  
For self-hosting, refer to the server's repository.

### API Key Security

- **Never** expose your API key in public repositories
- Use environment variables or secure storage in production
- If compromised, regenerate the key from the admin dashboard

---

## Comparison with C# SDK

This Java SDK is a **1:1 port** of the C# `Callum.Voice` library:

| Feature | C# SDK | Java SDK |
|---------|--------|----------|
| VoiceClient | ✅ | ✅ |
| VoiceConfig | ✅ | ✅ |
| VoiceClientState | ✅ | ✅ |
| IAudioEngine interface | ✅ | ❌ (AudioEngine is concrete) |
| AudioEngine (Android) | ✅ | ✅ |
| WebSocket | ClientWebSocket | OkHttp |
| Events | C# events | VoiceEventListener interface |
| Foreground Service | ✅ | ✅ |
| Speaking Detection | ✅ | ✅ |
| Auto-Reconnect | ✅ | ✅ |
| Multi-Peer Mixing | ✅ | ✅ |
| Jitter Buffer | ✅ | ✅ |

### Key Differences

1. **No IAudioEngine interface** — Java SDK uses concrete `AudioEngine` class (Android-only)
2. **OkHttp instead of ClientWebSocket** — More reliable on Android
3. **VoiceEventListener interface** — Instead of C#-style events
4. **Handler-based threading** — All callbacks on main thread via `Handler(Looper.getMainLooper())`

---

## License

MIT

---

## Support

For issues, questions, or contributions, please contact the Cloudfort team.
