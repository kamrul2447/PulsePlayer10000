# Pulse Player WebView interface

This folder is the version-controlled source for the interface loaded by the Android WebView.

## Build

Gradle's `syncWebAssets` task copies this folder into `app/src/main/assets/web` before building. Edit files here; the generated assets directory is ignored by Git.

## Native playback bridge

`js/core/bridge.js` communicates with `BridgeProtocol.kt` using bridge protocol version 1.

- JavaScript to Android: `window.PulseBridge.send(command, payload)`
- Android to JavaScript: `window.PulseBridge.onNativeEvent(jsonString)`

Media3 remains the only playback engine. The UI does not play audio itself or keep an independent library database.

## Online music

Online search uses the app's built-in NewPipe YouTube extractor. The user does not need to configure a YouTube Data API key, Audius key, or custom endpoint. A YouTube playlist URL is loaded into the results list, and **Play playlist** sends its tracks to the native Media3 queue. After an online queue reaches its last item, the player can append the video's related results as suggested next tracks.

Playlist extraction and related-result availability depend on YouTube and NewPipe remaining compatible; they should be tested on a real device with an internet connection.

## Local preview

Opening `index.html` in a desktop browser shows the layout, but library scanning, device permissions, native queue controls, and audio playback require the Android WebView bridge.
