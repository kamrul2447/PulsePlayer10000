# Pulse Player web interface

This folder is the version-controlled source for the WebView interface used by Pulse Player.

## Build integration

The Android Gradle task named syncWebAssets copies this folder into app/src/main/assets/web before preBuild. Do not edit the generated copy under app/src/main/assets/web; it is ignored by Git and is rebuilt from this folder.

## Native bridge

The file js/core/bridge.js implements the version 1 bridge documented in BridgeProtocol.kt:

- JavaScript to Android: window.PulseBridge.send(command, payload)
- Android to JavaScript: window.PulseBridge.onNativeEvent(jsonString)

The interface uses the existing command and event names. Playback remains owned by the native Media3 PulsePlaybackService; this UI never creates a second player or stores the local song library as its own source of truth.

## Local verification

Open index.html in a browser to inspect the layout. Native media, device scanning, deletion, API calls, and playback require the Android WebView bridge and cannot be fully tested in a desktop browser.
