# TiviMate UI Spec — for porting the look to OpenTV 4.6

Source: TiviMate IPTV Player 5.3.2 (package `ar.tvplayer.tv`, versionCode 1000005321),
APK at `~/workspace/android/apks/TiviMate-5.3.2-universal.apk` (19.9 MB), analysed 2026-11-08
with `aapt2 dump resources`, `aapt2 dump xmltree`, the binary `AndroidManifest.xml`,
the dex string tables, and the `resources.arsc` resource table.

**This spec describes the design in words and numbers so OpenTV can reimplement the
look with its own code and assets. It contains no TiviMate images, icons, or copied XML.**

---

## 1. What could and could not be read

- **Readable:** the full color table (234 colors) and dimension table (286 dimens),
  all 933 string resources, the manifest (activities, themes), the dex class names,
  and 92 of the 184 layout XML files.
- **The 92 readable layouts are mostly AndroidX library layouts** (Leanback
  `BrowseSupportFragment` / `GuidedStepFragment` internals, AppCompat dialogs and
  menus, Preference rows, and the media3/ExoPlayer player view + control overlay).
  The app's own screens are among the other 92 layouts.
- **NOT readable: 92 of 184 layout XMLs are encrypted** (AndResGuard-style resource
  encryption — the files start with random bytes instead of the `03 00 08 00`
  binary-XML header, and `aapt2 dump xmltree` fails with "Bad XML block").
  That includes the app's own main screen, channel-list / sidebar, TV-guide grid,
  and loading-screen layouts. Their exact view hierarchies, widths, and margins
  could **not** be extracted from the APK.
- **Resource names are obfuscated** (everything is named `11`, `a`, etc.), so
  identification below is by view structure, resource values, and strings — not
  by filename.
- Java is not installed, so apktool could not run; everything here comes from
  `aapt2` (build-tools 34.0.0) and Python parsing of `resources.arsc`.

Consequence for the port: the loading screen and left sidebar sections below are
reconstructed from the readable evidence (strings, dimens, colors, theme, the
player UI, and the app's documented behaviour in its own settings strings).
Where a number is a firm extraction it is labelled **verified**; where it is an
inference from a candidate dimension or from the app's behaviour it is labelled
**inferred / candidate**. Do not treat candidates as measured facts.

---

## 2. App architecture (from the manifest + strings)

- Single main entry: `ar.tvplayer.tv.ui.MainActivity`, declared as both the
  `LAUNCHER` and the `LEANBACK_LAUNCHER` activity. It is a Leanback-based TV app.
- MainActivity theme sets the window background to **framework black**
  (`@0x0106000c`), and the app theme's parent is a dark theme. Picture-in-Picture
  is supported. Launch mode is single-top.
- The player is media3/ExoPlayer: the app ships its own `CustomPlayerView` and
  `CustomPlayerControlView` wrapping the media3 `StyledPlayerView`. The player
  view and control overlay layouts **are** readable (see §6).
- The UI is built around two display modes that appear throughout the settings
  strings: **overlay mode** (panel floats over the video) and **preview mode**
  (panel sits beside a shrunken live preview). This applies to the channels list,
  the channel groups, and the TV guide independently.
- Navigation hint shown in the app (verbatim string): **"Long OK: open menu /
  Left: show groups / Long Left: navigate to past programs"**. So: pressing Left
  from the channel list / guide opens the **groups sidebar**; long-pressing OK
  opens the context menu.
- Main navigation sections (from strings): **Playlists, Channels list, TV guide,
  Groups, Favorites, History / Recent channels, Recordings, Settings, Search.**
- Empty states (verbatim strings): "Add your own playlist to start watching",
  "No channels", "No information" (the programme placeholder — same wording
  OpenTV already uses), "No programs", "No groups", "No recordings".

---

## 3. Color palette (verified hex values from the resource table)

### 3.1 The app's own dark UI palette
These are the dark greys the app's surfaces are built from:

| Hex | Resource | Likely use |
|---|---|---|
| `#080808` | 0x7f0600db | Near-black, darkest surface |
| `#111111` | 0x7f0600da | Near-black surface |
| `#131619` | 0x7f06006f | Very dark blue-grey panel |
| `#161616` | 0x7f060072 | Near-black panel |
| `#1C232C` | 0x7f060038 | Dark blue-grey |
| `#202020` | 0x7f06002d | Dark grey |
| `#232629` | 0x7f06006e | Dark grey panel |
| `#262626` | 0x7f060071 | Dark grey panel |
| `#2C333C` | 0x7f060037 | Slate panel |
| `#333639` | 0x7f060070 | Medium dark grey |
| `#363636` | 0x7f060073 | Medium dark grey |
| `#384248` | 0x7f0600de | Dark blue-grey |
| `#3C434C` | 0x7f060039 | Slate |

### 3.2 Accent and status
| Hex | Resource | Likely use |
|---|---|---|
| `#86C739` | 0x7f0600d1 | **Accent — yellow-green.** The app's signature accent. Used for the current-channel highlight ("Highlight current channel in color" setting) and selected states. |
| `#2196F3` | 0x7f060019 / 0x7f06002f | Blue (links / secondary accent) |
| `#FFC107` | 0x7f06001d | Amber (warnings / stars) |
| `#FF5722` | 0x7f0600a6 | Deep orange |
| `#FF7043` | 0x7f0600a5 | Deep orange, lighter |
| `#FF4343` | 0x7f0600fb | Red (errors / delete) |
| `#4CAF50` | 0x7f0600b1 | Green (success / recording) |
| `#00BCD4` | 0x7f060069 | Cyan |

### 3.3 Text
| Hex | Resource | Use |
|---|---|---|
| `#FFFFFF` | 0x7f0600ac / 0x7f0600ca / 0x7f0600d2 | Primary text (white) |
| `#B3FFFFFF` | 0x7f0600ad | Secondary text — 70% white |
| `#AAFFFFFF` | 0x7f0600cb | 67% white |
| `#F4F3F0` | 0x7f0600aa | Off-white |
| `#EEEEEE` | 0x7f0600b6 / 0x7f0600c4 / 0x7f0600c9 | Light grey text / light surfaces |
| `#9E9E9E` | 0x7f0600b7 | Muted grey text |
| `#888888` | 0x7f0600f3 | Muted grey text |
| `#CCCCCC` | 0x7f0600f8 | Light grey |
| `#555555` | 0x7f0600f9 | Dark grey text |
| `#444444` | 0x7f0600f6 | Dark grey text |

### 3.4 Player overlay (verified, from the player layouts)
| Hex | Resource | Use |
|---|---|---|
| `#98000000` | 0x7f0600a7 | 60% black — scrim / overlay |
| `#B0000000` | 0x7f0600a9 | 69% black — **bottom control bar background** |
| `#B3000000` | 0x7f0600a8 | 70% black — overlay |
| `#60FFFFFF` | 0x7f0600a3 | 38% white — faint overlay |
| `#10FFFFFF` | 0x7f0600a4 | 6% white — very faint overlay |
| `#80808080` | 0x7f0600ab | 50% grey — rounded-rect background (16dp corners, see §7) |
| `#60000000` | 0x7f06002a | 38% black — scrim |

### 3.5 Focus / pressed states (verified)
- A colour state list (res/3ioam8tI.xml): **pressed → accent/white, focused → accent/white,
  default → `#40CCCCCC` (25% light grey)**. The pressed/focused entries point at
  alias colour resources, so the exact focused text colour could not be resolved
  to a hex — it is one of the app's accent/white colours.
- A background state list (res/3o0s49hM.xml) for focusable items: **window not
  focused → transparent; focused → a 9-patch highlight drawable; focused+pressed →
  a different 9-patch; not-focused+pressed → the same pressed 9-patch.** The
  highlight is a **9-patch PNG** (mdpi + hdpi variants), not a vector shape, so
  the exact highlight colour/shape must be recreated by eye — it is a rounded
  highlight that appears behind the focused row/button.
- Focus transitions use a **crossfade** (`transition` drawable) between the
  unfocused and focused backgrounds, i.e. the highlight fades in rather than
  snapping.

### 3.6 Framework colours referenced
- Window / video background: framework **black** (`@0x0106000c`).
- Transparent: framework **transparent** (`@0x0106000d`).

---

## 4. Dimensions (verified dp/sp values from the resource table)

### 4.1 Player overlay (verified — these IDs are referenced by the readable player layouts)
| Value | Resource | Where used |
|---|---|---|
| 60dp | 0x7f0700bb | Bottom control bar height |
| 10dp | 0x7f0700bc | Control bar top margin |
| 10dp | 0x7f0700bd | Control bar side padding |
| 24dp | 0x7f0700be | Center control cluster padding |
| 4dp | 0x7f0700bf | Bottom control row bottom margin |
| 48dp | 0x7f0700c3 | Progress/seek bar height |
| 52dp | 0x7f0700c4 | Progress bar bottom margin |
| 32dp | 0x7f0700a3 | Player error/status text height |
| 64dp | 0x7f0700a4 | Player error/status text bottom margin |
| 12dp | 0x7f0700a5 | Player error text horizontal padding |
| 4dp | 0x7f0700a6 | Player error text vertical padding |
| 14sp | 0x7f0700a7 | Player error/status text size |
| 14sp | (style 0x7f14014d) | Player control time/title text, **bold**, 4dp side padding |
| 48dp | 0x7f0700c5 | (control) |
| 8dp | 0x7f0700c6 | (control) |

### 4.2 General spacing scale (verified — the app reuses these everywhere)
4dp, 6dp, 8dp, 10dp, 12dp, 16dp, 18dp, 20dp, 24dp, 32dp, 36dp, 48dp, 52dp,
56dp, 60dp, 64dp, 72dp, 80dp. Text sizes: 12sp, 14sp, 16sp, 18sp, 20sp.

### 4.3 Panel-width candidates (inferred — these dimens exist but their exact
### target could not be mapped because the layouts are encrypted)
The following widths exist in the dimension table and are plausible widths for
the left sidebar / side panels. Treat as candidates, not measurements:
- **320dp** (0x7f070017), **296dp** (0x7f070034), **270dp** (0x7f070059),
  **252dp** (0x7f070058), **238dp** (0x7f0700fd), **190dp** (0x7f07005a),
  **167dp** (0x7f0700fe), **400dp** (0x7f0700fa), **720dp** (0x7f070016).
- Row heights in the table: 48dp, 52dp, 56dp, 64dp, 72dp, 90dp, 118dp.
- A 0.26 / 0.38 / 0.5 / 0.54 / 0.7 set of fractional dimens (0x7f0700c9–0x7f0700cf)
  — these are opacity or width-fraction values used by the panel layouts.

---

## 5. Loading screen (reconstructed — layout encrypted, see §1)

**What is verified:**
- The app starts on a **black** window background (MainActivity theme).
- The app's banner asset is a **320×180** image (the standard Android TV banner
  size) and there is a **269×82** wordmark image; the launcher icon is 192×192.
  (Sizes only — do not copy the images.)
- The app shows update states as text while it works: **"Playlist updating…"**,
  **"Playlist is processed"**, **"The playlists are successfully updated"**,
  **"Update EPG"**, **"EPG is successfully updated for N channels on …"**,
  **"Downloading the new version…"**, and on failure **"Failed to update the
  playlist. Make sure the playlist is properly formatted and check your Internet
  connection."**
- The empty state is **"Add your own playlist to start watching"**.

**Reconstructed structure (inferred from the above + the player view, which
shows how the app presents a loading state):**
- Full-screen black background.
- Centred app logo/wordmark near the top-centre of the screen.
- A centred **indeterminate circular ProgressBar** below the logo while playlists
  and the EPG load. (The player view uses exactly this pattern for buffering:
  a centred indeterminate spinner over a black surface — see §6.1 — and the
  loading screen follows the same visual language.)
- A status line under the spinner showing what is happening ("Playlist
  updating…", "Update EPG", …). The player error/status text uses **14sp, white,
  centred, 12dp horizontal / 4dp vertical padding, 32dp height, 64dp bottom
  margin, on a rounded-rect background** — the loading status line is styled
  the same way.
- No title bar, no navigation — just logo, spinner, status.

**What could not be read:** the exact loading layout file, the logo's exact
size/position on the loading screen, and whether the progress is a determinate
bar or a spinner during playlist load. The spec above is the closest faithful
reconstruction from the readable evidence.

**For OpenTV 4.6:** replace the current "launches straight to a random channel"
cold start with a proper loading screen: black background, OpenTV logo centred,
indeterminate spinner, and a status line ("Loading playlist…", "Loading guide…")
that updates as each stage finishes, then transition to the channel. This also
removes the current behaviour where a failed playlist load leaves the user on a
black playback screen.

---

## 6. Player UI (verified — fully readable)

### 6.1 Player surface (`1us9atde` — the media3 player view)
Hierarchy (top to bottom):
- `AspectRatioFrameLayout` (fills the screen, centred)
  - Full-screen `View`, background **black** — the video backdrop.
  - `ImageView` (fills the screen, scaleType centre-crop) — channel/poster artwork.
  - `ImageView` (fills the screen, scaleType centre-crop) — second artwork layer.
  - `SubtitleView` (fills the screen) — closed captions.
  - `ProgressBar`, **indeterminate, centred** (`layout_gravity=center`) — the
    buffering spinner.
  - `TextView` (centred) — status/error message: **14sp, white (`#FFFFFF`),
    centred, background = rounded rect (16dp corners, 50% grey `#80808080`),
    padding 12dp horizontal / 4dp vertical, height 32dp, bottom margin 64dp.**
- `FrameLayout` (fills the screen) — controller overlay container.
- `FrameLayout` (fills the screen) — ad / overlay container.
- `View` (fills the screen) — bottom scrim.

### 6.2 Player control overlay (`b5v5j6N` — the media3 control bar)
A bottom-docked control bar that appears over the video:
- **Container:** `FrameLayout`, full width, **height 60dp**, top margin 10dp,
  background **69% black (`#B0000000`)**, gravity bottom.
- **Left block:** a `LinearLayout` (left-aligned, 10dp side padding) holding
  three `TextView`s, all **14sp bold**: the channel/programme title, a
  separator dot (`·`), and the time/subtitle. (Style 0x7f14014d: 14sp, bold,
  centred vertically, 4dp side padding.)
- **Right block:** a `LinearLayout` (right-aligned) holding **seven `ImageButton`s**
  — the transport controls (play/pause, previous/next, and the settings/record/
  favourite actions). Each button is an icon-only button with a content
  description; the icons are vector drawables.
- **Settings strip:** a `HorizontalScrollView` (initially invisible) holding a
  horizontal row of option buttons — the extra settings/actions that appear
  when a settings button is pressed.
- **Progress bar:** a thin `View` along the bottom of the bar, **height 48dp
  area with a 52dp bottom margin** — the seek/progress track.
- **Bottom-right:** a single `ImageButton` (full-screen / aspect control).
- **Centre cluster:** a centred `LinearLayout` (transparent background, 24dp
  padding) holding the large central play/pause button flanked by two
  `<include>`d sub-layouts (previous/next and skip controls) and two more
  `ImageButton`s — the big centre transport controls that appear when playback
  is paused.
- **Bottom-right corner:** a small `LinearLayout` holding a `View` (the
  buffering/quality indicator).

**For OpenTV 4.6:** OpenTV currently disables the media controller entirely
(`useController=false`) and draws its own info overlay. The port should keep
OpenTV's own overlay but restyle it to this pattern: a 60dp bottom bar in
69% black, 14sp bold white title on the left, icon-only transport buttons on
the right, a centred play/pause cluster, and a thin progress track at the very
bottom.

---

## 7. Left sidebar (reconstructed — layout encrypted, see §1)

**What is verified:**
- The sidebar is the **channels list** and the **groups list**. The settings
  strings name it in three places: "Show channels list groups in overlay mode",
  "Show channels list groups in preview mode", and "Show groups". Pressing
  **Left** from the channel list or the guide opens the **groups** panel.
- The sidebar's contents, in order (from strings + the groups/settings model):
  1. **Playlist / group header** — the current playlist name and group name
     ("Show playlist and group name" setting).
  2. **Groups:** "All channels", "All playlists", "Favorites", "History /
     Recent channels", then the user/playlist groups in order.
  3. **Channels** in the selected group, each row showing (settings-controlled):
     channel **number**, channel **logo**, channel **name** (one or two lines —
     "Two-line channel names" setting), the **current programme** title, and a
     **catch-up icon** where catch-up is available ("Show catch-up icon in
     channels list").
  4. A footer/count line: "Channels: N" and, when the list is truncated,
     "Displaying first N channels. Use Categories or Search to find more."
- **Focus behaviour (verified from the selectors, §3.5):** the focused row gets
  a **rounded 9-patch highlight** that **crossfades in**; focused text uses the
  accent/white colour; the current channel is **highlighted in the accent
  colour `#86C739`** ("Highlight current channel in color" setting).
- **Row styling (verified from the rounded-rect shape drawable):** rounded
  corners **16dp**; a 50%-grey (`#80808080`) rounded background is used for
  pill/status elements.
- **Search:** the sidebar has a search entry — the readable SearchView layout
  (`c75bv3O`) is a horizontal bar with a search icon, a **36dp-tall** text
  field, a clear button, and a voice-search button. The search screen offers
  "Search history", "Show favorite channels first", and "Prefer voice search".
- **Modes:** the sidebar can appear as an **overlay** (floating over the video,
  with a dark scrim behind it) or in **preview mode** (docked beside a live
  preview of the current channel). The mode is a per-list setting.
- **Row metrics (verified from the Preference/item-row layouts that share the
  same row language):** rows are `wrap_content` height with a minimum touch
  height, 16dp start margin for the text block, 6dp top/bottom margins on the
  text, 8dp end margin, and `singleLine` + end-ellipsis on the title.
  Font for secondary text is `sans-serif-condensed` in at least one row style.

**What could not be read:** the sidebar's exact width in dp, the exact row
height, the exact logo size, and the exact group-header styling. The
candidate widths in §4.3 (320dp / 296dp / 270dp / 252dp / 238dp) are the only
width values in the dimension table that fit a side panel; **320dp is the most
likely sidebar width** (it is also the standard Leanback browse-panel width),
but this is an inference, not a measurement.

**For OpenTV 4.6 (recommended):**
- Add a real left sidebar to the playback screen (today OpenTV has only the
  bottom info overlay and the full-screen guide): a **320dp-wide** panel docked
  to the left, over a **60% black scrim (`#98000000`)**, listing groups at the
  top and channels below.
- Header: playlist name + group name, 14sp bold white.
- Group rows and channel rows: number (accent `#86C739` for the current
  channel), logo, name (white 14sp, single line, end-ellipsis), current
  programme (70% white `#B3FFFFFF`, 12–14sp).
- Focused row: rounded 16dp highlight background that fades in; focused text
  turns accent/white.
- Left from the channel list opens the groups list; long OK opens the context
  menu; the sidebar works in both overlay and preview modes.

---

## 8. Main screen structure (reconstructed)

- **Playback is the home screen.** The app opens on the last channel
  ("Turn on last channel on app start" setting) or, with no playlist, the empty
  state "Add your own playlist to start watching".
- **Layout:** the video fills the screen. The left sidebar (channels/groups)
  and the TV guide are panels that appear **over** the video (overlay mode) or
  **beside a shrunken preview** (preview mode). The guide is a separate
  full-screen grid; the channels list is the left sidebar.
- **Info panel:** when switching channels, an info panel appears at the bottom
  ("Show info panel at the bottom when switching channels") showing the channel,
  the current programme, and next up — this matches OpenTV's existing
  left/right overlay, which should be restyled to the 60dp / 69%-black bar in §6.2.
- **Black screen on switch:** there is a setting "Show black screen when
  switching channels" — the default behaviour is a brief black frame, not a
  frozen frame.
- **Guide:** the TV guide grid shows channel numbers, logos, names on the left
  and 30-minute time columns across the top, with a red current-time line and
  "No information" placeholders — the same structure OpenTV already has. The
  guide has its own groups panel (Left opens groups) and supports staying on
  the guide when switching channels.

---

## 9. Other screens (brief notes)

- **Settings:** Leanback `GuidedStepFragment` / preference screens (the readable
  `GuidedActionsRelativeLayout` + `VerticalGridView` layouts). Rows are the
  standard Leanback guided-action rows: an icon, a title (14–16sp), and a
  description, with a SeekBar row for sliders (min height, 16dp start margin,
  6dp vertical margins, value text on the right in `sans-serif-condensed`).
- **Search:** the AppCompat SearchView bar (36dp text field, search + clear +
  voice icons) plus a search screen with history and "show favorites first".
- **Recordings:** a list with "All recordings", "Ongoing recordings", "Scheduled
  recordings", and per-recording actions (record / stop / custom duration /
  delete). Recording status strings: "Recording is started for 1 hour",
  "Recording is started till the program end".
- **Dialogs:** standard AppCompat `AlertDialogLayout` (title, message, buttons)
  with a `NestedScrollView` for long text.
- **Menus:** Leanback guided-action menus and AppCompat popup menus
  (`ListMenuItemView`: icon + two-line title/subtitle + trailing icon).

---

## 10. What to build in OpenTV 4.6 (summary)

1. **Loading screen** (§5): black background, OpenTV logo centred, centred
   indeterminate spinner, 14sp white status line ("Loading playlist…",
   "Loading guide…") on the rounded 16dp pill background; then fade into playback.
2. **Left sidebar** (§7): 320dp panel, 60% black scrim, groups then channels,
   number + logo + name + now-playing per row, accent `#86C739` for the current
   channel, rounded 16dp focus highlight with a crossfade.
3. **Restyle the player overlay** (§6.2): 60dp bottom bar, 69% black, 14sp bold
   white title left, icon-only transport buttons right, thin progress track at
   the bottom, centred play/pause cluster.
4. **Palette** (§3): adopt the dark greys (`#080808` / `#111111` / `#161616` /
   `#232629` / `#333639`) for surfaces, white / 70%-white text, and the
   `#86C739` accent for the current channel and focused states.
5. **Keep OpenTV's own behaviour** (random-channel launch becomes "last channel",
   the guide's chunked rendering, the 3-recents strip, recording) — only the
   look changes.

---

## 11. Limitations & open questions

- The exact sidebar width, row height, logo size, group-header style, and
  loading-screen logo position **could not be read** (encrypted layouts). The
  320dp sidebar width is an inference; verify it visually against TiviMate on
  the TV before locking it in.
- The focused/pressed text colours resolve to alias resources whose hex could
  not be determined from the resource table; the accent `#86C739` and white are
  the most likely values.
- The 9-patch focus highlight PNGs were not copied and their exact colour was
  not measured; recreate the highlight as a rounded-rect background in the
  accent/white family and check it by eye.
- No TiviMate images, icons, or XML were copied into OpenTV. This spec is a
  clean-room description for reimplementation.
