# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

**PageVox** — an Android browser (Kotlin + Jetpack Compose, Material 3) that reads web pages aloud with the system TTS engine, in the foreground or in the background with lock-screen controls. Package `fi.paso.pagevox`, min SDK 26, target/compile SDK 36. Apache-2.0, repo `git@github.com:simopaso/pagevox.git`.

`README.md` is the user-facing description (features, tech stack, architecture table) and is kept current — read it for the feature list rather than re-deriving it.

## Environment

Windows, at `C:\Users\simo\Coding\PageVox`. Builds in Android Studio as-is; `JAVA_HOME` and `adb` are on the system environment, so `.\gradlew.bat …` and `adb` work with no setup. Toolchain: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, SDK at `C:\Users\simo\AppData\Local\Android\Sdk` (`local.properties`, untracked).

`app/build.gradle.kts` registers a `deleteDesktopIni` task wired into `preBuild`, plus an `ignoreAssetsPattern` for the same reason — a Windows-artifact workaround. Leave both in place.

## Build & verify

| Goal | Command |
| --- | --- |
| Type-check Kotlin | `.\gradlew.bat compileDebugKotlin --console=plain` |
| Unit tests | `.\gradlew.bat testDebugUnitTest --console=plain` |
| Both (normal verification loop) | `.\gradlew.bat compileDebugKotlin testDebugUnitTest --console=plain` |
| Full debug APK | `.\gradlew.bat assembleDebug --console=plain` |
| Lint | `.\gradlew.bat lint` |

Unit tests live in `app/src/test/java/fi/paso/pagevox/` and cover the pure logic — `SentenceSplitterTest`, `PlaybackDataRepositoryTest`. Add tests there when touching sentence splitting, duration estimation, or index math; the rest of the app needs a device.

### Release builds: don't

**The user builds and signs release APKs themselves in Android Studio.** Never hand them a debug APK to install: the debug key mismatches the release key already on their phone, so Android refuses to install over it without uninstalling (wiping history/bookmarks/positions). Compiling `assembleDebug` for verification is fine — just don't present the artifact as something to install.

Workflow for a change: edit → confirm it compiles and unit tests pass → bump the version → tell the user it's ready → they build/install/test on the device → they report back → then commit.

## Version bumping

Every user-visible change gets a version bump in three places:

1. `app/build.gradle.kts` — `versionCode` (integer, +1) and `versionName` (currently `2.17` / code `29`).
2. `README.md` — the "Current version" line under *Project status*.
3. The commit subject usually says "Bump to X.Y" when that's the main content.

Signed release APKs are archived in the repo under `app/release/` (`app-release.apk`, `output-metadata.json`, and a gzipped R8 `mapping-<version>.txt.gz` per shipped release). Keep the mapping file when a release is archived — R8 minification is on for release.

## Architecture, briefly

The UI and the media-session service run **in the same process**, bridged by the `PlaybackDataRepository` singleton.

- `MainActivity.kt` — intents (share / `VIEW` http(s)), `MediaController` connection.
- `MainViewModel.kt` — all UI state and actions.
- `MainScreen.kt`, `AddressBar.kt`, `LibrarySheet.kt`, `SettingsDialog.kt`, `ReadingScrubber.kt` — Compose UI.
- `WebViewContainer.kt` — WebView lifecycle, tap-to-seek, follow-along highlight, state save/restore.
- `PageScripts.kt` — the injected JavaScript: text extraction, tap detection, reader mode, highlighting.
- `PlaybackService.kt` — Media3 `MediaSessionService`; owns `TextToSpeech`.
- `PlaybackDataRepository.kt` — sentences + estimated durations, page language/URL, speech rate, voice, `currentIndex`.
- `SettingsRepository.kt` — DataStore prefs, history, bookmarks. `SentenceSplitter.kt` / `NarrationText.kt` / `UrlUtils.kt` — pure helpers.

Deliberate design choices worth knowing before changing anything:

- **The silent ExoPlayer track is load-bearing.** TTS produces the audio; a silent looping track with per-sentence *estimated* durations keeps the media session in a playing state so the notification/lock-screen controls and a scrubbable progress bar work. Seeking that track maps back to a sentence index.
- **UI ↔ service uses Media3 custom session commands**: `playSentences`, `updateIndex`, `playbackEnded`, `stopPlayback`, `skipNext`, `skipPrevious`.
- **Page text comes from injected JS, not an HTML parser.** Jsoup was dropped in 2.1.2. Highlighting uses the CSS Custom Highlight API rather than rewriting the DOM.
- **Every sentence exists in two forms** (since 2.13): the *verbatim* page text and the narration-*cleaned* text (`NarrationText.kt`). Only the cleaned form is spoken; the verbatim form is what the UI shows, what the highlight JS locates in the DOM by substring match, and what tap-to-seek matches against. Cleaning therefore carries a character map back to the source — a sentence that had "[3]" removed from its middle is no longer a DOM substring, so replacing the verbatim text would silently kill follow-along and tap-to-seek. Keep the two lists index-aligned; `setSentences` drops a spoken list whose size doesn't match.
- **The "Support development" link must stay a link, and must stay optional.** It's a PayPal.me URL in the overflow menu (`SUPPORT_URL` in `MainScreen.kt`). If it ever unlocks, enables or removes anything in the app, it stops being a donation and becomes a digital purchase that Google Play requires to go through Play Billing — so keep it granting nothing. It's opened via `openOutsideThisApp`, which excludes our own activity from the chooser: PageVox handles http(s) VIEW intents, so a plain ACTION_VIEW can land a payment page in our own WebView.
- **Logging in to sites needs WebView plumbing that isn't on by default** (added 2.15, all in `WebViewContainer.kt`): third-party cookies (off by default on a modern target SDK, and the reason SSO used to fail), a `flush()` on ON_PAUSE so a fresh login survives a process kill, `setSupportMultipleWindows` plus an `onCreateWindow` that relays a popup back into the same view, a `shouldOverrideUrlLoading` that hands non-web schemes to the system, and an `onReceivedHttpAuthRequest` dialog. The scheme allow-list must keep `javascript` and `blob` — a `javascript:void(0)` link is most of the web's buttons and must never be launched as an intent. `intent://` URLs are attacker-controlled: strip `component`/`selector` and require `CATEGORY_BROWSABLE` before launching. Google and some other providers block WebView sign-in outright; that is not fixable here.
- **Localisation (2.17): en, fi, sv, de, fr.** No user-visible string goes in Kotlin any more — add it to `res/values/strings.xml` and all four `values-<lang>` files, or the UI silently reverts to English for that one label. `translatable="false"` marks the three that are the same everywhere (product name, the `%1$d / %2$d` counter, the license name). Time-left wording uses the abbreviations "min" and "h" on purpose: they don't inflect in any of these languages, so no `<plurals>` are needed. `res/xml/locales_config.xml` must list every shipped language or the Android 13+ per-app language picker won't offer it. The manual is translated too (`assets/manual-<lang>.html`), picked by `manualUrl()`; menu names quoted in each manual must match that language's `strings.xml`, and a stored home page pointing at any manual is re-resolved to the current language on read.
- **The user manual is a real page, not a screen.** `app/src/main/assets/manual.html` is loaded in the WebView from `MANUAL_URL` (`file:///android_asset/manual.html`) and goes through the same extraction, splitting, tap-to-seek and highlight path as any website — which is the point: it's the default home page *and* a live demo of the reader. Keep its content in semantic `<p>`/`<h2>`/`<li>` elements (a `<div>` is invisible to the extractor), and keep it offline and inline. Assets stay reachable via `file:///android_asset` even with WebView's `allowFileAccess` off, so never enable file access to "fix" it.
- **Sections come from the extraction tag.** `extractTextJs` returns each block's element tag; h1–h6 become section starts, which drive the scrubber ticks, the heading readout, long-press section skip, and the short silence before a heading.
- **Tap-to-seek resolves the exact character** under the finger via `caretRangeFromPoint`, then `MainViewModel.findSentenceIndex` matches progressively shorter probes. An unmatched tap must do nothing — never fall back to sentence 0 (that was the old bug on plain-text/`<pre>` pages).

## The reading-position trap (read this before touching resume logic)

The position lives in **three** places that can drift apart:

1. **Disk** — `SettingsRepository` `LAST_SENTENCE_INDEX` + a per-page `position` on each history entry. Written continuously by the service; only read back in `MainViewModel.init`, i.e. only on a fresh process.
2. **`PlaybackService.currentSentenceIndex`** — the real position, but *instance* state. A paused `MediaSessionService` is destroyed after a few minutes and the recreated instance starts at 0.
3. **`MainViewModel.initialIndex`** — only advanced by `updateHighlight`, which fires only while a `MediaController` is connected (foreground). Goes stale while backgrounded.

**Invariant (since 2.7):** `PlaybackDataRepository.currentIndex` is the single in-process source of truth for resume. The service publishes to it in `persistPosition()` (every sentence start and at pause), so it survives service-instance death. `MainViewModel.getStartIndex()` reads it, falling back to `initialIndex` only before the sentence set is built. `setSentences(startIndex = …)` seeds it from disk on cold start, and `PlaybackService.onCreate` seeds `currentSentenceIndex` from it.

Rules that follow: keep the singleton authoritative in-process; disk is only the cross-process-death backstop; **never route position persistence through the Activity/MediaController** — that connection dies with the foreground, which is exactly how the position used to be lost on a lock-screen pause.

Debugging lesson from that bug: the failing logcat showed the *same PID* throughout, proving the process never died — it was same-process service teardown, not process death or a lost disk write. Check PID/lifecycle before assuming a persistence failure.

## Conventions

- **Commits**: imperative subject (~50 chars), then a wrapped body explaining *why* and what the symptom was, not just what changed. Existing history is a good template. Include the trailers Claude Code adds (`Co-Authored-By:` / `Claude-Session:`).
- **Comments**: this codebase is heavily commented with rationale ("why", not "what"), especially around lifecycle and position handling. Match that density — when you fix a subtle bug, leave the explanation behind.
- **Don't commit until the user has device-tested the change**, unless they say otherwise.
- Keep `README.md` in sync when features or the architecture table change.
