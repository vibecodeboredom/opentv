# OpenTV Juniper — road to v10

Juniper's branch in the v10 build-off: `juniper-v10` (never main).
Nova builds her v10 on `nova-v10`. When both reach v10, the two
branches combine into one combined branch, built as its own separate
package and installed on the TV next to the others to compare.

Rules (from Chuni, 2026-11-17):
- Real ladder: major-major (7.0.0 → 8.0.0 → 9.0.0 → 10.0.0).
  Each major has AT LEAST 3 major-minors, and each major-minor gets
  1 minor-minor patch. The full ladder is:
  7.0.0 → 7.1.0 → 7.1.1 → 7.2.0 → 7.2.1 → 7.3.0 → 7.3.1 →
  8.0.0 → 8.1.0 → 8.1.1 → 8.2.0 → 8.2.1 → 8.3.0 → 8.3.1 →
  9.0.0 → 9.1.0 → 9.1.1 → 9.2.0 → 9.2.1 → 9.3.0 → 9.3.1 →
  10.0.0.
- Every version bundles SEVERAL changes — never one feature per
  version, never one feature then jump to v10.
- Any build installed on the TV is a SEPARATE package
  (`com.opentv.player.juniper`, label "OpenTV Juniper", its own
  launcher icon) — it never overrides the main `com.opentv.player`.
- No parental controls (cancelled). No catch-up promises — his
  playlists have zero catch-up attributes, so catch-up can never
  work for them. Batch fixes into fewer versions.

## 7.x — Guide + playback control

### 7.0.0 ✅ (this release)

- Channel-number jump: type digits on the remote in the guide or
  during playback, the number shows in a corner pill, Enter (or a
  1.5 s pause) jumps straight to that channel — guide focuses its
  row's now programme, playback switches to it.
- Favorites-only guide view: a toggle in the guide menu (and in
  Settings → Guide & playback) that shows only favorited channels.
- Jump to now: a guide-menu action that returns focus to the
  programme on now and scrolls the grid back to the current time.
- EPG freshness + manual refresh: the guide header shows when the
  guide data was last updated ("Guide updated 2h ago"), and the
  guide menu has a "Refresh guide data" action that re-downloads
  the EPG immediately instead of waiting out the 12 h cache.

### 7.1.0 ✅ (this release) — Playback comfort

- Sleep timer: stop playback after 15 / 30 / 60 / 90 minutes,
  from the playback menu (MENU). Counts wall-clock time, paused
  or not; fires once — pause + leave playback. Survives leaving
  for the guide, dies with the session (channel change = fresh).
- Playback speed: 0.5×–2× on live streams, from the playback
  menu. Re-applied when the player is rebuilt (back from the
  guide); resets to 1× on a channel change.
- Seek skip for recordings only: jump forward/back 10 s with
  Left/Right while a recording is playing. INFO opens the info
  overlay during a recording, since Left/Right seek there now.

### 7.1.1 ✅ (this release) — Patch

- Playback speed survives a channel change: the chosen speed is
  carried in the playback intent (and through the guide, for a
  channel picked there) instead of resetting to 1× on a zap.
  A fresh launch still starts at 1×. Catch-up replays do not
  carry it — speed is live-only.
- Recording seek-skip polish: Left/Right now seeks ±10 s when
  the info overlay is up but no strip tile has focus, instead of
  steering focus between transport buttons that do nothing while
  a recording plays. The seek toast shows the new position /
  duration ("2:34 / 45:00") instead of "+10s".
- Sleep-timer edge cases were DROPPED from this patch — Nova
  already covered the timer on her branch (basic timer 7.0.0,
  recording playback 7.1.0, countdown display 7.1.1), so
  reworking ours would just duplicate her work.

### 7.2.0 ✅ (this release) — Guide, day by day

- Day navigation in the guide: jump to tomorrow / a picked day,
  instead of only the 10-hour window around now. Prev/next-day
  rows + a "Go to day…" picker (today .. +6 days) in the guide
  menu; the header shows the viewed day, the now-line is hidden
  off-today, and Jump to now comes home to today.
- "Tonight" quick view: evening prime-time programmes (6–11 PM)
  across favorites, one screen, from the guide menu. Always
  today's 6–11 PM, even after 11 PM (the prime time that just
  aired). No favorites yet shows the empty view.
- Guide window size setting: 6 h / 12 h / 24 h forward, in
  Settings → Guide & playback. Applies on open, or on return
  when the guide is open. Past hours add on top as before.
- Gemini reviewed the diff (caught 1 real issue: Tonight after
  11 PM silently meant tomorrow — fixed to stay today) and wrote
  3 extra unit tests; GLM 429'd and sat this one out. Day math
  is DST-correct (Calendar day add, not fixed 24 h).

### 7.2.1 ✅ (this release) — Patch

- Day-jump focus landing: jumping days (or changing the window size) keeps the channel you were actually viewing instead of snapping back to the playing channel. The pending focus is cleared when the grid goes empty or a search starts, so a stale channel never diverts a later rebuild.
- Tonight empty states: Tonight with no favorites, and favorites with no real programmes in 6–11 PM (all "No information" placeholders), each get their own message instead of the generic search text. Favorites-only empty uses the no-favorites text; search with no matches still says so.
- Window-size layout: the 6/12/24 h header, rows and now-line share one px-per-minute scale, so alignment holds at every size — locked by unit tests (24 h cell math, Tonight fixed at 5 h regardless of window size). A window-size change no longer yanks focus either (same pending-focus fix as day jumps).

### 7.3.0 ✅ (this release) — Audio & picture

- Audio track selection in playback: for multi-language streams, pick the track (language) from the playback menu (Auto + per-track, shown only when the stream carries 2+ audio tracks). Choice survives channel changes within a session and player rebuilds.
- Subtitle / closed-caption toggle: where the stream carries subtitles, pick one (or Off) from the playback menu; PlayerView's built-in subtitle view renders the chosen track. Off survives rebuilds and zaps.
- Channel-logo cache: stale logos refresh in the background (7-day TTL, max 25 per launch, forced network fetch overwriting the disk entry) instead of only on reinstall.
- Gemini lite reviewed the diff (1 finding — wrong on verification: Coil WRITE_ONLY + network DISABLED is the correct forced-refresh combo, and its suggested constant doesn't exist); Gemini also wrote 4 extra unit tests, all verified correct and added.

### 7.3.1 ✅ (this release) — Patch

- Track-selection persistence per channel: the audio language and subtitle choice (or Off) are remembered per channel id, so returning to a channel restores them instead of resetting to Auto / Off; a channel with nothing stored keeps the session-carried choice.
- Subtitle timing: the chosen languages are seeded as preferred languages at player build, so the selector picks them from the first frame — before, the choice only landed after the tracks loaded, so the wrong audio could play and captions could lag or flash the wrong language first. Re-apply on track change still pins the exact track.
- Logo refresh retries: failed refreshes retry on the next guide open (throttled to once per 15 minutes) instead of only on the next app launch.
- Gemini lite reviewed the diff (no findings) and wrote 3 extra unit tests — 1 was new and verified correct and added, 2 duplicated existing tests and were dropped. GLM 429'd on its test-generation call and sat this one out.

## 8.x — Library & data

### 8.0.0 ✅ (this release) — Backup & restore

- Export playlists, favorites, hidden channels and settings to one `opentv-backup.json` file (share via the Android share sheet, plus a copy saved to Downloads) and import it back on another device — a new TV starts with the same setup instead of from scratch. Local ("file:") playlists carry their M3U text in the backup so they are recreated on the other device; EPG sources, upload URL, per-channel EPG overrides and per-channel track picks are included too. Bad files are rejected with a toast; a backup with no usable playlist leaves the current list alone.
- EPG source manager: the EPG URL list is now editable in Settings as a list — add or remove sources one by one (long-press a row to remove) instead of one comma-separated field. Old comma-separated values read back as their sources with no migration; removing the last source is refused.
- Gemini lite reviewed the diff (2 findings — both wrong on verification: malformed JSON numbers already throw and are caught by decode, and the all-playlists-disabled guard is intentional) and wrote 3 extra unit tests — 2 verified correct and added as-is, 1 fixed (wrong playlist entry fields) and added. GLM 429'd on its review call and sat this one out.

### 8.1.0 ✅ (this release) — Channel library

- Custom channel groups: rename and reorder groups, move channels
  between groups (stored as overrides in GroupOverrides +
  PlaylistRepository, playlist untouched). Managed in Settings →
  Channel groups (tap a row for Rename / Move up / Move down /
  Reset); moves happen from the guide long-press menu ("Move to
  group…"). Group overrides also ride along in backup export/import.
- Bulk hide / unhide: guide menu → "Hide channels…" (pick a group,
  tick several channels) and "Show hidden channels…" (tick hidden
  ones to bring them back). Single hide from the long-press menu
  still works as before.
- Channel count per group, shown in the sidebar group list: counts
  were already shown for raw playlist groups — now they count the
  EFFECTIVE groups (renames, moves, custom order applied), so a
  moved channel counts under its new group.

### 8.1.1 ✅ (this release) — Patch

- Group-override migration: a backup now records whether it
  actually carried group overrides, so restoring a pre-8.1.0 backup
  no longer wipes the renames, order and moves on the device; a
  wholesale override set is sanitized first, so a foreign backup
  can never store a line-breaking value (entries for groups that
  no longer exist are kept, not pruned — overrides are global
  across playlists).
- Bulk-hide confirmation: the tick-several hide / unhide dialogs
  now ask first ("Hide 3 channels?" / "Hide 'CNN'?") before
  applying, and the toast still reports what was hidden/shown.
- Sidebar counts: the History count is now the recents still in
  the playlist and not hidden, matching the History list (the old
  raw recents size counted hidden or removed channels too).

### 8.2.0 ✅ (this release) — EPG quality

- EPG source status in Settings: each source row shows whether it
  loaded or failed, when, and how many channels/programmes it
  contributed ("Loaded 2h ago · 148 channels, 12,400 programmes" /
  "Failed 1h ago — HTTP 404"); a "Refresh now" button re-downloads
  + re-parses every source on the spot. Status is recorded on
  every fresh EPG load and survives restarts.
- "Channels without guide data" list in Settings, with one-tap
  EPG assign: tap a channel to pick from ranked suggestions for
  the most common id mismatches (case/punctuation, country/HD
  suffix, display-name spelling, containment) or type the id in
  manually. Assigning sets the per-channel EPG override, so the
  channel drops off the list.
- Per-channel guide coverage indicator in the guide's channel
  options: how many programmes in the next 24 h, whether one is
  on now, and when the next one starts — or "no data for this
  channel".
- Gemini lite reviewed the diff (no real findings — 1 wrong on
  verification, 1 non-issue) and wrote 3 extra unit tests (1
  fixed: wrong EPG key; 2 added as-is). GLM 429'd on its review
  call and sat this one out.

### 8.2.1 ✅ (this release) — Patch

- Source-status refresh timing: a fetch that falls back to the
  stale disk copy is now recorded as failed ("Fetch failed, using
  cached data") instead of claiming a fresh load, and a cached read
  is stamped with the file's real fetch time instead of "Loaded
  just now". "Refresh now" is single-flight, like loadEpg, so two
  taps share one in-flight download instead of racing two of them.
- Assign-dialog suggestions: stacked id suffixes strip ("cnn.us.hd"
  matches "cnn"), suggestions dedupe by normalized id (two sources
  spelling the same channel differently no longer burn two slots),
  and step-4 containment is word-prefix based with the tvg-id as an
  identity — "CNN" suggests "CNN International" and "Discovery"
  suggests "DiscoveryChannel", but never "CNN2" / "ESPN2" / "HBO2"
  and never "BBC News" for "Fox News".
- Coverage counts: programmes carried by two EPG sources are merged
  once (same start + stop + title), so the guide no longer shows
  every programme twice and the coverage count no longer doubles;
  coverageFor dedupes defensively too, for snapshots saved by older
  builds.
- Gemini lite reviewed the diff (3 findings — all wrong on
  verification: stripIdSuffix does assign the stripped value, the
  refresh single-flight is the same correct double-checked locking
  as loadEpg, and the name-key merge loop does not add to the
  programme count) and wrote extra unit tests — 1 adapted and added
  (dedupe order + different slots), 1 extended (numbered variants);
  the rest were wrong or duplicates and were dropped. GLM 429'd on
  its review call and sat this one out.

### 8.3.0 ✅ (this release) — Recordings

- Scheduled recordings from the guide: pick a future programme,
  set it to record when it starts. An AlarmManager alarm fires
  RecordStartReceiver at the start time, which starts the recording
  and schedules its stop at the programme's end (inexact alarms, no
  exact-alarm permission needed — may land a few minutes late).
  Cancel from the guide menu, the recordings screen, or Settings →
  Recordings. A programme that already ended by the time the alarm
  fires is skipped.
- Recordings list: sort (newest / oldest / largest / smallest /
  name) and filter (all / recording now / saved) controls on the
  recordings screen, persisted across opens; per-recording storage
  usage and date on the card, not only in the options dialog.
- Auto-delete old recordings option (Off / 7 / 14 / 30 / 90 days)
  in Settings → Recordings; enforced when the recordings screen
  opens and when a scheduled recording starts. Never deletes
  in-progress recordings or ones with no start time recorded. The
  setting rides in backups (old backups decode to Off).
- Gemini lite reviewed the diff (no findings) and wrote 4 extra
  unit tests (1 fixed: wrong expected spacing in cleanField);
  GLM 429'd and sat this one out.

### 8.3.1 ✅ (this release) — Patch

- Scheduled-record firing while the app is closed: the start
  alarm is now `setAlarmClock` instead of `setAndAllowWhileIdle`
  — an alarm-clock alarm is exact (recording starts on time) and
  grants the Android 12+ exemption to start the foreground
  RecordingService from the background, which the inexact alarm
  did not, so a scheduled recording silently never started when
  the app was closed. A new BOOT_COMPLETED receiver re-arms the
  start alarms after a reboot (alarms don't survive one), and the
  stop receiver no longer crashes if the service isn't running.
- List sorting: recordings with no start time recorded (unknown
  date) sort last in both NEWEST and OLDEST instead of first as
  the "oldest"; name sort uses Locale.ROOT so device locale
  can't reorder names.
- Storage math: the totals on the recordings screen and in Stats
  now use the cache's true on-disk size instead of summing the
  DownloadIndex's claimed bytes, which can overstate after the
  LRU evictor drops old segments. Per-recording card sizes still
  come from the index.
- Gemini lite reviewed the diff (no findings) and wrote 4 extra
  unit tests, all verified correct and added; GLM 429'd and sat
  this one out.

## 9.x — Experience

### 9.0.0 ✅ (this release) — TV home screen

- A real browse-first home on TV: favorites rail, recent channels
  rail, and groups rail — instead of always auto-playing on launch.
  Recent history grew from 3 to 12 so the rail means something
  (the playback strip still caps itself at 3).
- Setting to choose the launch behaviour: home screen (default) or
  auto-play the last channel (Settings → Guide & playback, TV
  only). The setting rides in backups.
- The home screen reuses the mobile home's rails: one shared
  `HomeRails` builder (favorites first, then recents, then the
  effective groups) feeds both, so the two stay in sync. The
  mobile home now uses the effective groups too, instead of the
  raw playlist groups.
- Gemini reviewed the diff (2 real issues — an empty-title rail
  hazard, and the home never retrying after a failed load — both
  fixed) and wrote 8 extra unit tests (7 added as-is, 1 fixed);
  GLM 429'd and sat this one out.

### 9.1.0 ✅ (this release) — Appearance

- Guide row density setting (compact / comfortable), alongside the
  existing channel-list size. Comfortable keeps today's row heights;
  Compact thins them to 0.8x so more channels fit on screen.
- Font size options for guide and sidebar text (small / medium /
  large — 0.9x / 1.0x / 1.15x): programme titles, channel names and
  numbers, header labels, and the sidebar rows.
- High-contrast focus style: focused guide cells and sidebar rows
  get a thick accent outline (3dp on cells, 2dp on rows) and a
  brighter fill, so focus is unmistakable at a glance.
- Reduced-animations toggle: the guide's focus pop, the sidebar
  slide, the playback overlay fades and the guide menu's slide-in
  all jump to their final state instead of animating.
- The four appearance settings ride in backups (old backups decode
  to the defaults). New pure `Appearance.kt` with 14 unit tests.
- Gemini lite reviewed the diff (no findings) and wrote 4 extra
  unit tests, all verified correct and added; GLM 429'd and sat
  this one out.

### 9.1.1 ✅ (this release) — Patch

- Density layout overflow: the guide row-height floor is now
  font-aware (Large titles need 29dp, not the old flat 28dp), so
  a Compact row can never clip the programme title; the guide
  header's date cell and hour labels ellipsize instead of clipping
  past their cells at Large (worst on the 160dp mobile column).
- Font scaling in the details header: the header's title, channel,
  star, time, remaining, category and freshness text now scale
  with the font-size setting (they were fixed sizes before), and
  the header is wrap_content with the platform height as its
  minimum, so the scaled text can never push the bottom
  freshness/category row out — on mobile it clipped even at
  Medium with a two-line title.
- Focus colours: the high-contrast outline now reaches the home
  rails (channel/action/recording cards get a 3dp accent outline
  when focused) and the guide channel cell (outline + brighter
  fill, like the programme cells) — the two focus surfaces that
  had none.
- Gemini lite reviewed the diff (no findings) and wrote 1 extra
  unit test; GLM 429'd on its review call and sat this one out.

### 9.2.0 ✅ (this release) — Performance

- Startup-time pass: the EPG warm-up in preload waits 2 s so the
  playlist load (critical path to the first screen) gets the IO/CPU
  first on a 1 GB box; the stale-logo refresh waits 10 s after
  process start instead of firing 25 network fetches at launch. A
  screen opening the guide still starts the EPG load immediately
  (single-flight), so the delays only reorder background work.
- Memory pass: ExoPlayer max buffer 30 s → 20 s (min/startup
  unchanged, ~7 MB less held at a typical 6 Mbps live stream);
  onTrimMemory releases the in-memory playlist + EPG caches when
  the system is under pressure or the app is backgrounded (they
  rebuild from the SQLite snapshot in milliseconds).
- Logo cache on disk: explicit Coil disk cache (LogoCache, 2% of
  usable space clamped 16–64 MB, dedicated logo_cache directory)
  instead of the implicit default — logos survive a restart without
  re-downloading, and the cache can never grow to hundreds of MB.
- Guide scroll smoothness pass on large playlists: append chunks
  24 → 8 rows with the focus buffer 6 → 12 (GuideScrollTuning), so
  scrolling builds ~70 views per chunk instead of ~200.
- Gemini reviewed the diff (no findings, SHIP) and wrote 6 extra
  unit tests (1 wrong expectation dropped); GLM 429'd and sat this
  one out. First CI run failed on my own test arithmetic (2% of
  1 GiB is 21,474,836 bytes, not 20 MiB) — fixed, re-run green.

### 9.2.1 (planned) — Patch

- Fixes + polish from 9.2.0 testing on the TV: regressions from
  the startup pass, cache eviction, and scroll jank on the oldest
  TV.

### 9.3.0 (planned) — Accessibility & remote

- Spoken channel names: announce the channel on change (TTS),
  toggleable in Settings.
- Larger-text mode for the guide and sidebar.
- Long-press OK action choice: what a held OK does in playback
  (menu, guide, or nothing).

### 9.3.1 (planned) — Patch

- Fixes + polish from 9.3.0 testing on the TV: TTS language and
  timing, large-text overflow, and the long-press choice sticking.

## 10.0.0 (planned) — Juniper v10

- Capstone: the full ladder's features, a final startup-time and
  memory pass over the whole app, and one headline feature picked
  at the time from what tested best on the TV.
- After this, the combine with `nova-v10` happens on a combined
  branch — that is a separate step, not part of this ladder.
