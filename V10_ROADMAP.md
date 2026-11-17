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

### 7.1.0 (planned) — Playback comfort

- Sleep timer: stop playback after 15 / 30 / 60 / 90 minutes,
  from the playback menu.
- Playback speed: 0.5×–2× on live streams, from the playback menu.
- Seek skip for recordings and catch-up: jump forward/back 10 s
  with Left/Right while a recording or catch-up replay is playing.

### 7.1.1 (planned) — Patch

- Fixes + polish from 7.1.0 testing on the TV: sleep-timer edge
  cases, speed resetting when the channel changes, and seek-skip
  focus behaviour.

### 7.2.0 (planned) — Guide, day by day

- Day navigation in the guide: jump to tomorrow / a picked day,
  instead of only the 10-hour window around now.
- "Tonight" quick view: evening prime-time programmes across
  favorites, one screen.
- Guide window size setting: 6 h / 12 h / 24 h window, in
  Settings → Guide & playback.

### 7.2.1 (planned) — Patch

- Fixes + polish from 7.2.0 testing on the TV: day-jump focus
  landing, tonight-view empty states, and window-size layout.

### 7.3.0 (planned) — Audio & picture

- Audio track selection in playback (multi-language streams).
- Subtitle / closed-caption toggle where the stream carries them.
- Channel-logo cache: stale logos refresh in the background
  instead of only on reinstall.

### 7.3.1 (planned) — Patch

- Fixes + polish from 7.3.0 testing on the TV: track-selection
  persistence per channel, subtitle timing, and logo refresh
  retries.

## 8.x — Library & data

### 8.0.0 (planned) — Backup & restore

- Export playlists, favorites, hidden channels and settings to one
  file (share / save it), and import it back on another device —
  so a new TV starts with the same setup instead of from scratch.
- EPG source manager: the EPG URL list becomes editable in
  Settings (add / remove sources), instead of one comma-separated
  field.

### 8.1.0 (planned) — Channel library

- Custom channel groups: rename and reorder groups, move channels
  between groups (stored as overrides, playlist untouched).
- Bulk hide / unhide: select several channels in the guide and hide
  them at once.
- Channel count per group, shown in the sidebar group list.

### 8.1.1 (planned) — Patch

- Fixes + polish from 8.1.0 testing on the TV: group-override
  migration, bulk-hide confirmation, and sidebar counts.

### 8.2.0 (planned) — EPG quality

- EPG source status in Settings: which source loaded, which failed,
  and when each was last fetched.
- "Channels without guide data" list, with one-tap EPG assign for
  the most common id mismatches.
- Per-channel guide coverage indicator in the guide's channel
  options.

### 8.2.1 (planned) — Patch

- Fixes + polish from 8.2.0 testing on the TV: source-status
  refresh timing, assign-dialog suggestions, and coverage counts.

### 8.3.0 (planned) — Recordings

- Scheduled recordings from the guide: pick a future programme,
  set it to record when it starts. Builds on the existing custom
  recording timer.
- Recordings list: sort / filter, and storage usage shown per
  recording.
- Auto-delete old recordings option (keep the last N days).

### 8.3.1 (planned) — Patch

- Fixes + polish from 8.3.0 testing on the TV: scheduled-record
  firing while the app is closed, list sorting, and storage math.

## 9.x — Experience

### 9.0.0 (planned) — TV home screen

- A real browse-first home on TV: favorites rail, recent channels
  rail, and groups rail — instead of always auto-playing on launch.
- Setting to choose the launch behaviour: home screen or auto-play
  the last channel.
- The home screen reuses the mobile home's rails so the two stay
  in sync.

### 9.1.0 (planned) — Appearance

- Guide row density setting (compact / comfortable), alongside the
  existing channel-list size.
- Font size options for guide and sidebar text.
- High-contrast focus style + a reduced-animations toggle.

### 9.1.1 (planned) — Patch

- Fixes + polish from 9.1.0 testing on the TV: density layout
  overflow, font scaling in the details header, and focus colours.

### 9.2.0 (planned) — Performance

- Startup-time pass: faster cold start to first channel.
- Memory pass: lower steady-state use on long viewing sessions.
- Logo cache on disk, so logos survive a restart without
  re-downloading.
- Guide scroll smoothness pass on large playlists.

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
