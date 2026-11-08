# opentv

> ⚠️ **Vibecoded:** this project was built by an AI agent (Muse) with a human steering it. It works, but treat it like a hobby project — expect rough edges, read the code before you trust it, and don't be surprised if something breaks.

Open-source Android TV IPTV player. Native Leanback UI, M3U playlists, XMLTV EPG. Free forever — no ads, no accounts, no paid features.

## Features

- **Channel guide** — channels grouped by category, with logos, in a remote-first Leanback UI
- **TV guide** — a full EPG grid (channels as rows, time as columns, 6-hour window); the show on now is highlighted, pick it to start watching
- **M3U playlists** — load any M3U URL; ships preloaded with the [iptv-org](https://github.com/iptv-org/iptv) US channels list
- **Playback** — ExoPlayer (HLS / DASH) with the standard Android TV player controls
- **EPG** — XMLTV now/next programme info while watching
- **Favorites** — press INFO while watching to star a channel; favorites get their own row
- **Recording** — press MENU while watching to record a channel locally; recordings keep going in the background and live on the Recordings screen (play / delete, with storage used)
- **Settings** — change the playlist or EPG URL from inside the app

OpenTV is a player only. It does not provide, host, or distribute any channels or streams. You add your own playlist (or use the preloaded free iptv-org list).

## Build

Requires JDK 17 and the Android SDK (platform 35, build-tools 35).

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
