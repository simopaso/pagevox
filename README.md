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
- **Reading-position scrubber** — a full-width scrubber above the controls to jump anywhere in the page, with a live preview of the sentence you'll land on, section marks on the track, and the current heading above it.
- **Section navigation** — long-press skip to jump a whole section (heading) at a time; headings get a short pause in front of them so they land as chapter breaks.
- **Natural narration** — citation markers ("[3]"), bare URLs, ASCII rules and unpronounceable abbreviations are cleaned up before they reach the speech engine, while the page text you see and tap stays untouched.
- **Continue listening** — the library opens on the pages you started but didn't finish, showing how far you got and roughly how long is left.
- **Adjustable speed** — 0.8× to 2× presets, applied without interrupting playback.
- **Skip by sentence** — jump to the previous or next sentence.
- **Text size** — zoom the page in/out for comfortable reading.
- **Content-aware voice** — picks a TTS voice that matches the page's declared language while honoring your system default voice, or pick a specific voice in-app.
- **Force dark mode** — render any website or text file with a dark appearance.
- **Bookmarks & history** — save pages, revisit recent ones, and get address-bar autocomplete from your history.
- **Built-in user manual** — ships inside the app, is the default home page until you set your own, and is itself a normal page the app reads aloud. Reachable any time from the overflow menu.
- **Localised** — interface and manual in English, Finnish, Swedish, German and French, following the system language, with a per-app language override on Android 13+. Each manual declares its own language, so it is narrated by a matching voice.
- **Quick navigation** — back/forward, up-one-folder, home, and a full-width address bar.
- **Sites that need a login** — cookies (including third-party, for single sign-on), popup sign-in buttons resolved into the same window, HTTP Basic/Digest credential prompts with optional saving, links to other apps handed to the system, and a "Clear cookies and sign out" action.
- **Share & open-with** — send text or links from other apps to PageVox, or set it as a handler for `http`/`https` links.
- **Resumes where you left off** — the last page and reading position are restored on launch, and every page in your history remembers its own reading position.
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
| `MainActivity.kt` | Activity: intent handling (share/open-with), MediaController connection to the service. |
| `MainViewModel.kt` | UI state and actions: navigation, sentence splitting, reading position, settings. |
| `MainScreen.kt` | Top-level Compose screen: scaffold, bottom controls, dialogs wiring. |
| `AddressBar.kt`, `LibrarySheet.kt`, `SettingsDialog.kt`, `ReadingScrubber.kt` | Individual UI components. |
| `WebViewContainer.kt` | WebView host: page lifecycle, tap-to-seek, follow-along highlight, state save/restore. |
| `PageScripts.kt` | The JavaScript injected into pages: text extraction (with the source element's tag, for sections), tap detection, reader mode, sentence highlighting. |
| `NarrationText.kt` | Turns extracted blocks into the sentence set: cleans the text for speech and maps each cleaned sentence back to the verbatim page text it came from. |
| `SettingsRepository.kt` | DataStore-backed preferences, history, and bookmarks. |
| `UrlUtils.kt` | Address-bar input resolution and URL normalization helpers. |
| `PlaybackService.kt` | A Media3 `MediaSessionService` that owns the `TextToSpeech` engine, speaks sentences, and drives a silent ExoPlayer track so the notification's progress bar reflects reading position. |
| `PlaybackDataRepository.kt` | In-process bridge between UI and service: the sentence list (verbatim and spoken forms) with estimated durations, section boundaries, page language, speech rate, and the selected voice. |

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

Personal project, actively developed. Current version: **2.18**.

## Supporting development

PageVox is free, has no ads, and gates nothing behind a payment. If you find it
useful and would like to support the work, you can do so here:

**[paypal.me/SimoPaso](https://paypal.me/SimoPaso)**

Entirely optional — it unlocks nothing, and every feature stays available to
everyone either way. The same link is in the app under the overflow menu,
*Support development*.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify,
and distribute this software under its terms, which include an explicit patent
grant. Bundled third-party libraries remain under their own licenses
(Apache 2.0).
