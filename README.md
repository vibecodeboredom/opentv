# opentv

> ⚠️ **Vibecoded:** this project was built by an AI agent (Muse) with a human steering it. It works, but treat it like a hobby project — expect rough edges, read the code before you trust it, and don't be surprised if something breaks.

Open-source Android TV IPTV player. Native Leanback UI, M3U playlists, XMLTV EPG. Free forever — no ads, no accounts, no paid features.

## Features

- **Channel guide** — channels grouped by category, with logos, in a remote-first Leanback UI
- **TV guide** — a full TiviMate-style EPG: a details header on top (preview box with the channel logo, programme title, time range, progress bar with minutes left, favorite star, category), then the grid — channel numbers, logos and names down the left, 30-minute time columns across the top, flat programme cells (the focused one turns white with dark text), and a red now-line. Long-press OK on a programme for the context menu: search, settings, open in external player, record, custom recording (auto-stops after 15/30/60/120 min), add to favorites, block/hide channel, assign EPG, channel options. The guide always shows every channel — ones with no EPG data get "No information" placeholders, just like TiviMate. The show on now is highlighted; pick it to start watching. (Capped at the first 400 channels: the grid inflates every row eagerly.)
- **M3U playlists** — load any M3U URL; ships preloaded with the [iptv-org](https://github.com/iptv-org/iptv) US channels list
- **Playback** — ExoPlayer (HLS / DASH) with the standard Android TV player controls
- **EPG** — XMLTV now/next programme info while watching. The default EPG source is a free US guide; paste more EPG URLs in Settings (comma-separated, they merge). Channels are matched to EPG data by tvg-id first, then by channel name. Note: iptv-org's own hosted guide files (`epg/guides/us.xml`) are gone (404) — the project stopped publishing them — so the guide fills in "No information" for channels with no EPG data instead of showing nothing.
- **Favorites** — press INFO while watching to star a channel; favorites get their own row
- **Recording** — press MENU while watching to record a channel locally; recordings keep going in the background and live on the Recordings screen (play / delete, with storage used)
- **Recording upload** — set an upload URL in Settings and send a finished recording to your own server as an .mp4 (HTTP PUT; basic auth via `https://user:pass@host/path`). The export reads from the local recording cache.
- **Statistics** — a local-only stats screen (watch time, most-watched channels, recordings, favorites, channel counts). Stored on the TV only, never shared; includes a Clear statistics option.
- **Developer options** — a separate app ([DevOptions](https://github.com/vibecodeboredom/devoptions)) that jumps straight to the TV's system developer menu.
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

## Self-hosting

OpenTV is designed to work with your own servers — nothing is locked to a
cloud account.

- **Your own playlist / EPG:** host an `.m3u` playlist and an XMLTV `.xml`
  guide on any static HTTP server (a VPS, a NAS, a Raspberry Pi, or a
  public host like GitHub Pages) and paste the URLs into Settings. The
  preloaded iptv-org list is just a default.
- **Your own recording upload endpoint:** the app uploads a finished
  recording with an HTTP `PUT` to `<uploadUrl>/<channel>_<date>.mp4`.
  Any endpoint that accepts PUT works — a WebDAV share (e.g. Nextcloud),
  an S3-compatible store behind a PUT proxy, or a tiny server of your own.
  A minimal Python endpoint that saves uploads to a folder:

  ```python
  # upload_server.py — run: python3 upload_server.py  (listens on :8000)
  from http.server import BaseHTTPRequestHandler, HTTPServer
  import os

  class H(BaseHTTPRequestHandler):
      def do_PUT(self):
          name = os.path.basename(self.path)
          with open(os.path.join("recordings", name), "wb") as f:
              f.write(self.rfile.read(int(self.headers["Content-Length"])))
          self.send_response(201)
          self.end_headers()

  os.makedirs("recordings", exist_ok=True)
  HTTPServer(("0.0.0.0", 8000), H).serve_forever()
  ```

  Then set the upload URL in the app to `http://<your-server-ip>:8000`.
  Put it behind HTTPS + basic auth (`https://user:pass@host`) before
  exposing it to the internet.

## License

MIT — see [LICENSE](LICENSE).
