# Gemini Float — native Android Gemini Live assistant

Gemini Float is a lightweight Kotlin/XML Android overlay that starts only when you invoke it. There is no persistent floating orb. Double-press Volume Down (when the accessibility shortcut is enabled) or tap **Open Live assistant** to open a slim control bar and start a Gemini Live audio session.

## Features
- Default model: `gemini-3.8-live`; the model ID is free-form and editable in settings. The chosen model must support Gemini Live.
- Real-time, bidirectional audio through Gemini Live WebSocket (16 kHz PCM microphone input and 24 kHz PCM output). It does not use Android SpeechRecognizer or local TTS for Live mode.
- Compact single bar with **Mute**, **Screen**, **Disconnect**, and an up arrow. The arrow expands the transcript and text input.
- Screenshot capture only after Android's explicit MediaProjection consent; the screenshot is then sent to the current Live session.
- API key stored using Android encrypted preferences.
- Disconnect closes the WebSocket and releases microphone/speaker resources, then removes the overlay. No ongoing audio stream is sent after disconnect.
- GitHub Actions builds a debug APK without Android Studio.

## Build the APK
1. Open the [Actions tab](../../actions).
2. Open the newest successful **Build Gemini Floating Assistant** workflow run.
3. Download the `GeminiFloatingAssistant-debug` artifact, extract it, and install `app-debug.apk` on your Android phone.

Feature branch: `feat/lightweight-floating-assistant`. A draft pull request to `main` is available from the repository's Pull requests tab.

## First-run setup
1. Paste your API key from [Google AI Studio](https://aistudio.google.com/app/apikey).
2. Enter a Live-compatible model ID. The default is `gemini-3.8-live`; custom IDs are accepted and sent to the API as entered.
3. Grant **Display over other apps** and **Microphone** permission.
4. Enable **Gemini Float Shortcut** from Android Accessibility settings to use double-press Volume Down. Some manufacturers/Android builds limit accessibility key interception, so the shortcut is best-effort.
5. Tap **Open Live assistant** or double-press Volume Down.

## Live API and model support
Gemini 3.8 Live is a current stable low-latency Live API model. Gemini Live uses WebSockets and raw PCM audio: microphone input is 16 kHz mono PCM16 and audio output is 24 kHz PCM. The base `gemini-3.8-live` model does not accept a `thinkingLevel` setting; this client omits thinking configuration. See [Live API capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities), [WebSocket API reference](https://ai.google.dev/api/live.md), and [model docs](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live).

## Privacy and resource notes
The user's key is not committed to the repository. This client connects directly to the Gemini Live API using that personal key; direct client-side API keys carry risk if the APK is shared, so keep the app personal and do not publish your key. A production app distributed to others should use an ephemeral-token backend.

The Live session streams microphone audio while connected. **Disconnect** closes the socket and stops/releases the recorder and speaker. **Mute** stops sending microphone audio but keeps the Live session open and keeps Gemini audio audible; a connected session may still incur usage. Tap **Disconnect** when you want to end the session.

Android requires a system prompt to capture another app's screen. Some secure app content may appear black. The screenshot is sent to Google Gemini when approved; avoid capturing sensitive information.

Minimum Android: API 26 (Android 8.0). This project uses native views and no Flutter/React Native runtime. Actual RAM, battery, network use, and API charges depend on device and use; no exact usage limits are guaranteed. Independent personal project, not affiliated with Google.
