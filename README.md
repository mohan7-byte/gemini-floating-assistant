# Gemini Float — native Android Gemini Live assistant

A lightweight Kotlin/XML assistant for Samsung and other Android phones. It does not keep a floating orb on screen; invoke it with the Accessibility volume shortcut or by launching the app.

## Features in this branch
- Gemini Live WebSocket voice conversation using raw 16 kHz PCM input and 24 kHz PCM output.
- Premium compact bar. Swipe left/right over the action strip to switch between core controls (Mute, Screenshot, Disconnect) and extras (Camera, Files, Voice Keyboard). The chevron expands the transcript and text box.
- Animated connection indicator: faster pulse while connecting, slower live pulse after setup.
- Screenshot capture: Android's explicit MediaProjection consent is requested once per Live session. The bar hides before each capture. A scaled temporary VirtualDisplay is created only for the requested frame and immediately released; the approved projection session stays available until Disconnect, avoiding another consent prompt for every screenshot.
- Camera: small CameraX preview; preview targets 320x240, captured JPEG uses 640x480 target and 55% quality, then downsizes to at most 640 px before sending to Gemini.
- File attachments: Android document picker supports .txt, .md, and text-based .pdf; up to 25 PDF pages or 512 KB text is extracted and trimmed before sending for summary/follow-up. Scanned/image-only PDFs have no embedded text and need OCR, which this version does not include.
- Voice Keyboard: a real Android input method records a short PCM utterance, sends it to Gemini for transcription/formatting, then commits formatted text into the focused field. It uses a normal multimodal gemini-3.8-flash request for dictation, separately from the Live model.
- A microphone foreground service keeps the active Live session scheduled across screen-off; a notification gives a second Disconnect control. The app never dismisses the secure keyguard or bypasses your screen lock.
- API key is stored with encrypted local preferences.

## Install from GitHub Actions
1. Open the Actions tab.
2. Download the GeminiFloatingAssistant-debug artifact from the latest successful Build Gemini Floating Assistant run.
3. Extract the ZIP and install app-debug.apk.

## First setup
1. Enter your Google AI Studio API key and a Live-compatible model ID in Settings.
2. Allow display over other apps and microphone access.
3. Enable the Gemini Float Shortcut in Accessibility settings to try the double-press Volume Down shortcut.
4. If you plan to use the camera, grant camera permission when prompted.
5. To set up the keyboard, open Settings → Enable Gemini Float Voice Keyboard, enable it in Android's input-method settings, then select it from the keyboard switcher.
6. Tap Open Live assistant. Double-press Volume Down is best effort: some Samsung/Android builds may intercept the hardware shortcut differently.

## Android screenshot constraints
Android requires user consent to start MediaProjection. The permission is reused within the current connected Live session and released on Disconnect. Capture intentionally hides the assistant bar and waits for the prior app to resume before creating a low-resolution one-frame virtual display. Secure/DRM content can still appear black; no app can force Android to expose secure surfaces.

## Lock screen
The ongoing Live service plus a bounded partial wake lock help an already-started conversation keep working with the screen off. The assistant overlay requests to show over the lock screen, but it does not bypass the PIN/pattern or Android keyguard. Whether double-volume-down can start a new session while locked depends on the Samsung accessibility/key-event policy; test this on-device.

## API cost and privacy
Microphone audio is streamed to Gemini while Live is connected. Mute releases the microphone but leaves the Live session connected; tap Disconnect to close the WebSocket, release microphone/speaker, stop camera and screen capture, release wake lock, and clear in-memory transcript state. Camera frames, screenshots, and extracted file text are sent to Google when you explicitly request those actions. Keep your API key private: this direct-key app is intended for personal use, not public distribution.

## Technical notes
- Minimum Android API 26; compile SDK 36; JDK 17.
- Native Android views; no Flutter or React Native runtime.
- CameraX and PDFBox increase APK size, but support actual camera preview and PDF text extraction. Scanned PDFs aren't OCR'd.
- Actual RAM, thermal, battery and API costs depend on the device and usage.
- Independent personal project, not affiliated with Google.
