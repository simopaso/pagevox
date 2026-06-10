# PageVox

**PageVox** is an Android browser that reads web pages aloud. Open a page (or share one to the app), press play, and your device's text-to-speech engine reads the content while you listen — in the foreground or in the background with full lock-screen controls.

![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![Language](https://img.shields.io/badge/language-Kotlin-7f52ff)
![Min SDK](https://img.shields.io/badge/minSdk-26%20(Android%208.0)-blue)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material%203-4285f4)

## Features

- **Read any page aloud** — extracts the readable text from a web page or plain-text document and speaks it with the system TTS engine.
- **Background playback** — a Media3 `MediaSessionService` keeps reading when you leave the app, with play/pause, skip, and a progress bar in the notification and on the lock screen.
- **Tap to start anywhere** — tap a paragraph to begin reading from that sentence.
- **Follow-along** — the sentence being read is highlighted on the page and auto-scrolled into view, karaoke style. Toggle it off for audio-only.
- **Reader mode** — strips navigation, ads, and clutter for distraction-free reading and cleaner narration.
- **Reading-position slider** — a slim vertical scrubber on the screen edge to jump anywhere in the page.
- **Adjustable speed** — 0.8× to 2× presets, applied without interrupting playback.
- **Skip by sentence** — jump to the previous or next sentence.
- **Text size** — zoom the page in/out for comfortable reading.
- **Content-aware voice** — picks a TTS voice that matches the page's declared language while honoring your system default voice, or pick a specific voice in-app.
- **Force dark mode** — render any website or text file with a dark appearance.
- **Bookmarks & history** — save pages, revisit recent ones, and get address-bar autocomplete from your history.
- **Quick navigation** — back/forward, up-one-folder, home, and a full-width address bar.
- **Share & open-with** — send text or links from other apps to PageVox, or set it as a handler for `http`/`https` links.
- **Resumes where you left off** — the last page and reading position are restored on launch.
- **Material You** — dynamic color, edge-to-edge, and light/dark theming.

## Tech stack

- **Kotlin** + **Jetpack Compose** with **Material 3** (dynamic color / Material You)
- **Media3** (`media3-session`, `media3-exoplayer`) for the media session, notification, and progress bar
- Android **TextToSpeech** for narration
- **WebView** (+ `androidx.webkit` for algorithmic dark rendering) for page display and the CSS Custom Highlight API
- **DataStore Preferences** for settings and persisted state

## Architecture

The app runs the UI and a media-session service in the same process, bridged by a small singleton.

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Compose UI and `MainViewModel`: address bar, WebView host, bottom controls, reader/settings/library, text extraction and sentence splitting. |
| `PlaybackService.kt` | A Media3 `MediaSessionService` that owns the `TextToSpeech` engine, speaks sentences, and drives a silent ExoPlayer track so the notification's progress bar reflects reading position. |
| `PlaybackDataRepository.kt` | In-process bridge between UI and service: the sentence list with estimated durations, page language, speech rate, and the selected voice. |

**How playback works:** the activity extracts the page text in the WebView, splits it into sentences, and hands them to `PlaybackDataRepository`. It then sends a custom session command to the service, which speaks each sentence via TTS and seeks a silent audio track to that sentence's estimated start — so the system media UI shows a meaningful, scrubbable progress bar. Sentence-boundary callbacks broadcast the active index back to the UI to drive the highlight and slider.

## Building

Requirements:

- Android Studio (or the Android SDK with command-line tools)
- JDK 17
- Android SDK Platform 36

```bash
git clone <repo-url>
cd PageVox
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Or open the project in Android Studio and run the `app` configuration on a device/emulator running Android 8.0 (API 26) or newer.

> A working text-to-speech engine must be installed on the device. Voices and the default engine are configured in **Android Settings → Accessibility → Text-to-speech output**; PageVox can also override the voice in its own settings.

## Permissions

- `INTERNET` — load web pages.
- `POST_NOTIFICATIONS` — show the playback notification (Android 13+).
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — keep reading in the background.

## Project status

Personal project, actively developed. Current version: **2.1.1**.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify,
and distribute this software under its terms, which include an explicit patent
grant. Bundled third-party libraries remain under their own licenses
(Apache 2.0).
