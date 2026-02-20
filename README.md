# Fluxer Android Client

A native Android WebView client for any [Fluxer](https://fluxer.app) server — sideloadable APK, no Play Store required.

> **All credit for the Fluxer platform goes to the Fluxer team.**
> This Android client is a community wrapper. Official project: [github.com/fluxerapp/fluxer](https://github.com/fluxerapp/fluxer)

---

## Features

- **Any Fluxer server** — enter any `http://` or `https://` server URL on first launch
- **Mic & camera** — voice/video calls work natively via WebView media permissions
- **File downloads** — files save to the system Downloads folder with a notification
- **Notifications** — local Android notifications when the app is in the foreground
- **Offline error page** — friendly retry screen if the server is unreachable
- **Sideloadable** — install directly from the APK, no Play Store needed

---

## Getting the APK

Download the latest APK from the [Releases page](https://github.com/shadowflee3/fluxer-android/releases).

**To install:**
1. On your Android device go to **Settings → Security → Install unknown apps** and allow your browser or file manager
2. Open the downloaded `.apk` and tap **Install**

---

## Building from source

### Requirements
- Android Studio (Hedgehog or newer) **or** JDK 17 + Android SDK command-line tools
- Android SDK with Build Tools 34 and Platform 34

### Steps

```bash
git clone https://github.com/shadowflee3/fluxer-android
cd fluxer-android

# Debug APK (for sideloading / testing)
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

For a release APK you will need to [create a signing keystore](https://developer.android.com/studio/publish/app-signing) and configure it in `app/build.gradle`.

---

## Credits

- **Fluxer** — the platform, web client, and server
  - Website: [fluxer.app](https://fluxer.app)
  - GitHub: [github.com/fluxerapp/fluxer](https://github.com/fluxerapp/fluxer)
  - License: GNU AGPL v3

- **This Android wrapper** is maintained by [shadowflee](https://github.com/shadowflee3) and is not affiliated with or endorsed by the official Fluxer project.

---

## License

Provided as-is for personal use. Respect the Fluxer platform's [AGPL v3 license](https://www.gnu.org/licenses/agpl-3.0.html) when self-hosting or distributing.
