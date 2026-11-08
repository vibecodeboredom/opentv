# opentv

Open-source Android TV IPTV player. Native Leanback UI, M3U playlists, XMLTV EPG. Free forever — no ads, no accounts, no paid features.

## Features

- **Channel guide** — channels grouped by category, with logos, in a remote-first Leanback UI
- **M3U playlists** — load any M3U URL; ships preloaded with the [iptv-org](https://github.com/iptv-org/iptv) US channels list
- **Playback** — ExoPlayer (HLS / DASH) with the standard Android TV player controls
- **EPG** — XMLTV now/next programme info while watching
- **Favorites** — press MENU or INFO while watching to star a channel; favorites get their own row
- **Settings** — change the playlist or EPG URL from inside the app

OpenTV is a player only. It does not provide, host, or distribute any channels or streams. You add your own playlist (or use the preloaded free iptv-org list).

## Build

Requires JDK 17 and the Android SDK (platform 34, build-tools 34).

```sh
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it on an Android TV over ADB:

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

CI builds the debug APK on every push to `main` (see Actions).

## License

MIT — see [LICENSE](LICENSE).
