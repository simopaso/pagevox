# TTS Reader — Android launcher icon

Release-ready icon pack. Drop the `res/` folder into your Android project's
`app/src/main/` directory (it will merge with your existing `res/`).

## What's inside

```
android-icons/
├── playstore-icon.png          512×512 — upload to Play Console
├── master-1024.png             1024×1024 — favicon / web / social use
├── preview-hero.png            512×512 — quick visual reference
├── preview.html                Open in a browser to see every asset
└── res/
    ├── drawable/
    │   ├── ic_launcher_background.xml      Solid indigo, vector
    │   ├── ic_launcher_foreground.xml      Glyph, vector
    │   └── ic_launcher_monochrome.xml      Single-color glyph (Android 13+)
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml                 Adaptive icon manifest (API 26+)
    │   └── ic_launcher_round.xml           Round variant
    └── mipmap-{m,h,x,xx,xxx}hdpi/
        ├── ic_launcher.png                 Squircle fallback (pre-API 26)
        └── ic_launcher_round.png           Circle fallback (pre-API 26)
```

## Design

- **Concept:** lines of text on the left flow into a sound-wave equalizer on
  the right. Text → audio, in one glance.
- **Palette:** Indigo `#3A37B8` background, white text-lines, lavender
  `#C9C7F8` waveform.
- **Canvas:** 108×108 dp, content sits inside the 66 dp safe zone so it
  survives every launcher mask (circle / squircle / teardrop / square).
- **Themed icon:** the monochrome layer is a single tintable fill — Android
  13+ will recolor it to the user's wallpaper palette automatically.

## AndroidManifest reference

If your manifest still points at `@mipmap/ic_launcher`, no changes are needed:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ...>
```

The system picks the adaptive XML on API 26+ and the right-density PNG
fallback on older devices.

## Density / size reference

| Density   | Legacy PNG size | Scale |
|-----------|-----------------|-------|
| mdpi      | 48 × 48 px      | 1.0×  |
| hdpi      | 72 × 72 px      | 1.5×  |
| xhdpi     | 96 × 96 px      | 2.0×  |
| xxhdpi    | 144 × 144 px    | 3.0×  |
| xxxhdpi   | 192 × 192 px    | 4.0×  |
| Play Store| 512 × 512 px    | —     |

## Editing later

The vector drawables in `res/drawable/` are the source of truth. If you ever
tweak the geometry or colors there, regenerate the legacy PNG fallbacks at
the five densities above so old devices stay in sync.
