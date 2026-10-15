# Floating bar design customization

The floating bar is built from normal Android XML resources so you can redesign it without touching the Live/audio logic.

## Main design files

- `app/src/main/res/layout/overlay_assistant.xml` — floating bar structure, spacing, control order, header divider, expanded panel, camera preview, and minimized bubble.
- `app/src/main/res/drawable/panel_background.xml` — dark glass panel, corner radius, border, and outer horizontal inset.
- `app/src/main/res/drawable/action_chip.xml` — circular button background and border.
- `app/src/main/res/drawable/floating_orb.xml` — leading waveform orb and minimized bubble.
- `app/src/main/res/drawable/ic_*.xml` — individual vector icons; edit the path or colors in each file.
- `app/src/main/res/values/colors.xml` — accent mint, panel colors, text, outlines, and error color.
- `app/src/main/res/values/styles.xml` — app-wide typography and system bar theme.
- `app/src/main/res/layout/ime_voice_keyboard.xml` — voice keyboard design, separate from the floating bar.

## Quick edits

1. Change panel radius, border, gradient, or margins in `panel_background.xml`.
2. Change button shape and outline in `action_chip.xml`.
3. Change icon artwork in the matching `ic_*.xml` vector drawable.
4. Change bar height, icon size, spacing, and button order in `overlay_assistant.xml`.
5. Change the accent and text palette in `colors.xml`.

The header title/status block is `headerInfo`. Tapping it toggles the expanded transcript and text-input panel. The up-chevron button minimizes the bar to a draggable floating bubble; tapping the bubble restores it. Swipe the controls horizontally to reach camera, file picker, and disconnect actions.

## Voice typing implementation

- `app/src/main/java/com/assistant/gemini/VoiceKeyboardService.kt` owns the keyboard and inserts composing/interim text into the active `InputConnection`.
- `app/src/main/java/com/assistant/gemini/GeminiLiveSession.kt` streams 16 kHz PCM audio and parses Gemini Live transcription events.
- Voice typing uses Google's dedicated `gemini-3.5-transcribe-live` model. Interim transcripts update composing text; final transcripts commit that text in place.

Keep layout-only changes in the XML/drawables above. The IDs used by `AssistantAccessibilityService.kt` are part of the wiring contract, so keep them when rearranging controls.

## Build

Every push to `feat/**` runs the Android debug build and unit tests. The GitHub Actions workflow uploads the debug APK when both steps pass.
