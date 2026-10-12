# Gemini Float — lightweight Android assistant

Native Kotlin + XML floating Gemini assistant designed for lower-memory phones. It uses the Gemini REST API with your own Google AI Studio API key and builds with GitHub Actions; Android Studio is not required on your PC.

## Features
- Draggable translucent floating orb and expandable bottom panel.
- Text prompt, short in-memory conversation, Android speech recognition and text-to-speech.
- Mute voice responses, disconnect/clear conversation state, or close the assistant.
- Select a Gemini model and thinking preset.
- One-shot screenshot after explicitly approving Android's screen-capture consent dialog.
- API key stored using Android encrypted preferences; never committed to the repository.
- Debug APK artifact on push, pull request, or manual workflow run.

## Build APK without Android Studio
1. Open the [Actions tab](../../actions).
2. Choose **Build Gemini Floating Assistant** and run it, or push to the feature branch.
3. Open a successful workflow run and download the `GeminiFloatingAssistant-debug` artifact.
4. Unzip it and install `app-debug.apk` on your Android phone.

Branch: `feat/lightweight-floating-assistant`.

## First-run setup
1. Paste your API key from [Google AI Studio](https://aistudio.google.com/app/apikey). Never add a real key to source files or GitHub Actions logs.
2. Select a model and thinking mode, then save.
3. Grant **Display over other apps**.
4. Optionally enable **Gemini Float Shortcut** in Accessibility settings for double-volume-down. Device/OS restrictions mean this shortcut may not work on every phone; use the orb/manual launcher as a fallback.
5. Tap **Show floating assistant**.

## Screenshots, privacy and permissions
Android requires a system confirmation dialog before an app can capture other apps' screens. The screenshot button opens that dialog; capture is intended to be one-shot, and the screenshot is not submitted until you send a prompt. Some apps show secure content as black. A foreground notification is used during capture. Voice input uses Android's available speech recognizer and may involve its configured recognition provider.

Prompts and an optional screenshot are sent to Google's Gemini API using the key you provide. Conversation history is memory-only and cleared on **Disconnect**. No backend, analytics, or advertising is included.

## Model/thinking compatibility
Model names and API features can change. If a selected model is unavailable to your key or doesn't accept the chosen thinking setting, the app surfaces the API error. Check the [Gemini API model guide](https://ai.google.dev/gemini-api/docs/models) and [thinking guide](https://ai.google.dev/gemini-api/docs/thinking).

## Resource expectations
This uses native Android views and no embedded JavaScript, Flutter runtime, database, or large AI SDK. Actual RAM use depends on Android version, device, overlay, network stack, speech services, and screenshot capture; no fixed RAM limit or APK size is guaranteed.

## Technical notes
- Minimum Android: API 26 (Android 8.0).
- Build JDK: 17; compile SDK 36; target SDK 35.
- Gemini API calls run off the main thread.
- AccessibilityService is only for the optional shortcut.
- Independent personal project, not affiliated with Google.
