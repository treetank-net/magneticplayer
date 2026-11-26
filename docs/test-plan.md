# Test plan for the next release

What changed since the last tag (`v1.261119.2015`) and has to be checked on real hardware before tagging, plus
wishes noted while testing. Tick an item only after seeing it work (a screenshot on the phone, the TV itself, the
agent's log). Examples use any series with several episodes in one folder and a film outside it.

Legend: `[ ]` to test, `[x]` verified (date, how), `[!]` found broken (what), `[idea]` noted, not built yet.

## Phone player

- [ ] Opening the subtitle sheet (CC) pauses the video; closing it plays on. The same for ExoPlayer's gear menu
      (audio track, speed).
- [ ] Downloading subtitles shows "Downloading…" then "Fitting…" on the row; the file appears only once fitted
      (never an unfitted version on screen first).
- [ ] NapiProjekt subtitles: named after the provider, never a hash; they load (SubRip, not `.mpl`).
- [ ] "Sync while watching" in the sheet: ±0.1/±0.5 s, Reset and Done over the picture; the shift is visible at
      once; 8 s without a tap saves it; reopening the video keeps the shift.
- [ ] A manual shift carries to the next episode's subtitles from the same provider in that folder.
- [ ] The language picked last (e.g. Polish) starts the next video; "Off" is remembered too.
- [ ] Same series, an episode without subtitles in that language: they download on their own with a toast.
- [ ] Another film without them: the bar "No subtitles in <language> — Download / No" for 12 s, playback goes on;
      "Download" fetches and turns them on, "No" hides it.
- [ ] From halfway through an episode the next one's subtitles are fetched (agent log), the same provider first;
      the next episode starts with them on.
- [ ] Embedded tracks: titles like "Spanish (Latin America)", "for the hard of hearing", "forced".

- [x] Audio: the note icon next to CC appears only with two or more audio tracks; the sheet groups tracks by language
      with the kind (lector, dubbing, audio description, commentary, original), title, channels and codec; the video
      pauses while it is open; a pick switches the track at once. (2026-11-19, emulator, v1.261119.2229, a 3-track anime file: EN 5.1, EN commentary, JA). Not yet: a file with PL lector.
- [ ] The next episode (same folder) and other videos start with the same kind of track (e.g. "original" on a file
      with PL lector + EN); a file with one track is left alone.
      Same folder: [x] JA picked in E03, E02 started with JA (2026-11-19, emulator, v1.261119.2229, a 3-track anime file: EN 5.1, EN commentary, JA). Other folders and lector files: not yet.

## Screen, TV, Chromecast

- [ ] Chromecast: the subtitles in the language picked last are on; with "Off" none are on (but can be picked).
- [ ] Samsung (DLNA): the same (CaptionInfo with the right file); with "Off" no subtitles.
- [ ] mpv on jmpc (same server and from another server): `sub_lang` picks the language, "off" turns them off.
- [ ] Missing subtitles before sending: in the same series fetched on their own (progress in the "Opening…"
      dialog); another film asks "No subtitles in <language>" with Download / Without subtitles.
- [ ] Remote: "Skip intro" while the intro plays (series with intro detection) jumps past it.
- [ ] Remote: "Next episode" starts the next one on the same target with the subtitles.
- [ ] From the credits (or the last 10 s): the countdown card on Remote and the notification "Next episode in N s"
      with Play now / Cancel; pausing stops it; seeking back hides it; at 0 the next episode starts.
- [ ] The countdown works with the phone locked (Chromecast via the Cast SDK, Samsung, mpv).
- [ ] Sending from the phone player to a TV mid-series carries the queue.
- [ ] Something else started on the TV (another phone, YouTube): our queue forgets itself, no surprise episode.

## Phone as the source (downloaded copy, computer off)

- [ ] A copy downloaded after this release keeps the fitted subtitles, intro/credits and the episode list
      (check the copy's subtitles after a shift or a new download on the computer: they follow).
- [ ] Computer off: TV fed by the phone shows the subtitles in the chosen language, "Skip intro" and the
      countdown work; the next episode is one that is also downloaded.
- [ ] Phone player on a downloaded copy without the computer: "Skip intro" and the next-episode card use the saved
      intro/credits.

## Without a network (outside, Wi-Fi off)

- [ ] Reported: playing something from "On the phone" outside seemed to fail ("the phone refused"). Reproduce in the
      emulator (download a short video, then `svc wifi disable` / `svc data disable`): the player must start from
      the phone's disk, with the saved subtitles, without errors or long waits.
- [x] Found 2026-11-19 on the emulator (v1.261119.2229): a watching buffer of an MKV did not start offline
      ("Playback stuck buffering and not loading", black, 00:00): the player reads the index (Cues) at the file's end,
      and buffers held only the start. Fixed: the buffer (and a download, before the rest) takes the last 4 MB first.
      [x] 2026-11-19, emulator, v1.261119.2327: an MKV episode watched ~1 min online (the new build took the tail first),
      then airplane mode: it plays from "On the phone" (picture, 24:11 duration). An episode buffered by an older build
      (no tail) still sticks: watch it once online to fix it.
- [ ] No "No subtitles, download?" bar and no auto-fetch attempt when the computer cannot be reached.
- [ ] The app's other tabs without the computer: no error dialogs piling up, a clear "not reachable" state.

## "On the phone": selection

- [x] A long press on a card starts selecting (checkbox, buttons hidden); a tap (de)selects; Back ends it. (2026-11-19, Pixel, v1.261119.2158)
- [ ] The bar: "N selected · size", "Watched" adds the watched ones, select all / none, delete with
      "Delete N videos from the phone? That frees X. The videos stay on the computer."
- [x] Two or more videos of one folder are grouped under the folder's name in episode order; the header's
      checkbox selects the whole group. (2026-11-19, Pixel, v1.261119.2158)
- [ ] Group headers show the size and how many are watched; a tap folds/unfolds, groups over 6 start folded.
- [x] The list/cards icon at the top switches to one line per video (2026-11-19, Pixel, v1.261119.2229); [ ] tap plays,
      long press selects, remembered after a restart.
- [x] (fixed after v1.261119.2229; seen 2026-11-19, Pixel, v1.261119.2306) single videos sit under "Other videos" when there are groups (before, under a
      group they looked like its part); a group of videos lying directly in a library folder carries that folder's
      name (was an empty "(2)"); "Season 1" gets the series' folder before it ("Breaking Bad · Season 1").

## Catalog and Remote

- [~] "Continue watching": a series whose episode was watched to the end stays, labelled "Next: S1 E5" (label seen
      2026-11-19 on the Pixel, v1.261119.2229: "Next: S1 E3"); after its
      last episode it leaves the row; a film watched to the end leaves it.
- [ ] Remote, the screen pill's menu (two or more screens): each screen with "online"/"asleep", "Wake" on an asleep
      one with a known MAC picks it and wakes it.
- [ ] The top switcher's Wake on a screen-only server no longer makes it the file server.
- [ ] Series screen, the download icon by "Episodes": this season / from the next episode to the end / the whole
      series / extras, counts and sizes without what is already on the phone, the deletion rule for the batch
      (default "manually only"), free space on the phone (Download greyed out when it does not fit); the episodes get a
      "downloading" / "on the phone" icon.
      2026-11-19, Pixel, v1.261119.2229: the dialog, its options, counts, rule chips and free space [x] (not started).
      Found: complete watching buffers counted as missing and had no icon; fixed after it (a buffer holding the whole
      video counts as on the phone and is pinned with the batch's rule without downloading).
      [x] 2026-11-19, Pixel, v1.261119.2306: buffered E1/E2 show the "on the phone" icon, the dialog says "4 episodes,
      5.9 GB · 2 already there" for the season (not started).

- [ ] Audio on the computer's screen (agent 1.13.3): a film/episode sent to mpv starts with the audio track picked
      last on the phone (the same folder's choice first: "lector", "original", a language); the Remote's "Audio"
      opens the same sheet as the phone's player (tracks by language, kind, channels, codec) and the pick there is
      remembered too (the next episode on the phone or the screen starts with it).
      [x] 2026-11-20, jmpc, agent 1.13.3 via the API: a dual-audio MKV (English default, Japanese second) opened with
      `audio: "|ja"` plays the Japanese track, with `"|en"` the English one. [x] 2026-11-20, Pixel, v1.261119.2356: the
      Remote's "Audio" on jmpc shows both tracks under English / Japanese (title, 5.1 / stereo, codec: both AAC in
      this file, checked with ffprobe), picking Japanese switches mpv to it. [ ] The phone sending the remembered
      choice when it opens the next video on the screen.

- [x] Catalog, Discover: a row "Because you watched <title>" after "Continue watching", from the title watched last
      (a film finished too), with titles like it on the disks (the same match as "Similar" on a title's screen) minus
      what was watched; hidden with fewer than 3. (Emulator, v1.261119.2356: hidden, its only watched item is an
      unrecognised folder.) 2026-11-20, Pixel, v1.261119.2356: the row after a watched series shows three titles
      with their reasons (shared cast).

## Magnets

- [ ] A magnet without `dn=` (agent 1.13.1): the card says it waits for the name; once the client has the metadata
      (≤ ~20 s after it arrives) the agent recognizes it and, when sure, moves it to its folder (journal: "unnamed
      magnet recognized"); the card shows "Recognized: moving…" or the unsure folder with "Change"; closing the card
      early still gets it placed.
      - 2026-11-22, agent 1.17.6 on jmpc, offline: `place_unnamed` with a stub torrent client and the real IMDb index
        and folders: a public-domain film (high) moved to a "films" folder, a series (medium) and a junk name (low)
        stayed with the guess saved, a torrent still named by its hash kept waiting; `/api/library/guess` for a name
        that is only the hash returns no title, so the card keeps waiting. Not seen with a real client and the app.

## Library screens

- [x] The bottom bar starts with Library, then Downloads, On the phone, Remote, Settings; the app opens on Library (2026-11-19, Pixel, v1.261119.2158);
      [ ] a server without a library (downloads only) opens on Downloads.

- [ ] "Empty trash": the disks and the trash refresh right away (no 501 after it).
- [ ] Finished and cancelled tasks have "Remove from list"; a running one does not.
- [ ] The refresh icon on the disks screen re-reads free space, the trash and the tasks.

- [ ] "Download missing subtitles": in a film's ⋮ menu (library, catalog), "Missing subtitles for the series" on a
      series' episode, the subtitles icon in a folder, "Get subtitles" on the disks screen. Languages to pick, a preview
      of what lacks them (a folder/library) or a snackbar with the count (a film/series); the task in Tasks downloads
      and fits one by one, `note` per video; videos that already have the language (file or track) are left out.
      [x] 2026-11-19 (emulator, v1.261119.2306): "Get subtitles" shows on the disks screen; library preview with EN:
      1614 items with the missing language and folder (not started).
      Agent 1.13.2: a sidecar without a language in its name ("Film.srt") counts by its text's language (common words);
      [x] checked on jmpc's files (en/pl/fr/es/de/nl/cs right, Danish/Finnish left unsure) and by the plan API: a
      film with an untagged Polish .srt is gone from the "pl" list and still on the "en" one.
      Found 2026-11-19: "Get subtitles" missing on the disks screen although the agent had `subs_get_task` — the app
      read capabilities once per process, and screens kept the older copy. Fixed: capabilities are re-read when the
      agent's version (from ping) changes, and `Api.srv` is refreshed by `info()`. Check again after an agent update.

- [ ] Catalog without the server: after one visit the catalog is kept on the phone (`files/catalog/<server>.json`);
      with the server off (or the phone away) Library still opens on Discover/Browse with a bar "Server unreachable ·
      the catalog as synced <when>", a downloaded film plays from it; Folders and the disks say the server is
      unreachable. Back online the bar goes and `?since=` refreshes it (an unchanged catalog: one small answer).
      Episode lists and downloaded marks: the item below. Positions need no sending back: they live only on the
      phone (watched offline = already saved where the catalog reads them).
      [x] 2026-11-20, emulator, v1.261119.2356: airplane mode, app restarted: Library opens on Discover with the bar
      ("synced 0 minutes ago"), drawn covers, "Continue watching" plays the buffered MKV episode offline.

- [ ] Moves are journalled (agent 1.14.0, `GET /api/library/moves?since=`): after "Free up" / load balancing moves
      a series to another disk, the phone (on its next catalog sync) keeps its positions, "watched" marks, the
      folder's audio choice and subtitle shift under the new place: "Continue watching" still shows the series.
      Downloaded copies keep their old key (they still play from "On the phone"). Moves made before the app update
      are applied on the first sync after it.
      [x] 2026-11-20, jmpc, agent 1.14.0: the journal endpoint answers (empty before the first move).
      [x] 2026-11-20, jmpc, agent 1.14.0: after the first folder of a load-balancing task finished, the journal
      lists it (from folder 8 to folder 1). [ ] The phone re-keying its positions with it.
- [ ] A shrink item checks free room first (agent 1.14.0): on a full disk it fails at once with "not enough room on
      the disk: about X MB needed, Y MB free (the trash … may hold old originals)" instead of running ffmpeg into
      ENOSPC (a task with 644 old-format files ran into a full disk: 457 items failed that way, one by one).
- [ ] The app polls the server (ping, access requests, tasks, uploads) only while it is on screen: before, a foreground
      service (the Remote's session, downloads) kept the process alive and the loop pinged every 5 s in the background.
      Back after more than 30 s the header and Library say "Connecting…" until the first answer, not "asleep" /
      "unreachable" from the old state.
- [ ] Task polling (agent 1.14.1): found 2026-11-20 on jmpc, the Pixel on an older build sent ~60 kB/s with the screen
      locked: `/api/tasks` every 15 s for the "task finished" notification, 459 kB each (one finished task with 644
      files). Now the ping carries the latest finish time and the list is read (without items, `?brief=1`) only when
      it changes; the disks screen reads the brief list every 2 s and an opened task card fetches its own items.
      Check: the notification still comes when a task finishes; the items of a task show when opened.
      [x] 2026-11-20: jmpc agent 1.14.1, `?brief=1` 1.9 kB instead of 459 kB; Pixel v1.261120.0804: the disks screen
      shows the item counts ("644 pozycje"), an opened card lists its items with their errors.
- [ ] Following tasks (the user's wish, 2026-11-20): a task started on this phone shows an ongoing progress
      notification ("45% · <item>", the bar), updated once a minute with the app in the background and every 15 s on
      screen; when it ends, the "finished" notification replaces it. Another phone gets nothing unless it taps
      "Monitor" on the task card (then the same notification; "Stop monitoring" ends it). Server asleep: "Server
      unreachable, trying again every minute", gives up after about an hour.
      [x] 2026-11-20, Pixel, v1.261120.0826: "Monitor" on the running move (another device started it) turns into
      "Stop monitoring"; the notification "Zwolnij Filmy – archiwum (TOSHIBA)", "39% · ANIME", bar 39/100, "jmpc";
      in the background the service reads `/api/tasks?brief=1` once a minute (08:49:24, 08:50:25 in the journal).
      Found with it: the screens' own polling loops (disks screen every 2 s, catalog and moves every minute, wish
      list every 30 s, and the Remote, Chromecast, DLNA, torrent files) kept running in the background while a
      service kept the process alive. Fixed after it: they all wait until the app is on screen again.
      [x] 2026-11-20, Pixel, v1.261120.0851: app sent to the background (Home) with a followed task: for 3 minutes
      the only requests from the phone were `GET /api/tasks?brief=1` at 09:06:54, 09:07:54, 09:08:54, 09:09:54.
- [ ] Library offline, continued: a series' episode list opened once online opens offline too (kept per server);
      covers of titles with a complete copy on the phone get a "downloaded" mark (a series: any episode).
      [x] 2026-11-20, Pixel, v1.261120.0826: Loki opened once online, then Wi-Fi off ("Działa 0 z 2 serwerów"): the
      title screen and its episode list (Season 1/2, E1–E5…) open from the phone.
      Found: no mark on Loki / Breaking Bad although whole episodes are on the phone: they are watching buffers, and
      the mark only counted finished downloads. Fixed after it (a buffer holding the whole video counts, as in the
      episode list). [ ] Check the marks again.
      [x] Cold start (force-stop, Wi-Fi off, mobile data on), 2026-11-20, Pixel, v1.261120.0826: the catalog (404/41,
      Continue watching) comes from the sync; the series screen and its episode list open; a whole episode plays
      from "Na telefonie" and from the episode list ("Cały film na telefonie · działa bez sieci", HEVC hardware,
      subtitles; picture and sound confirmed by the user).
      Found: no covers on a cold start (placeholders only): they were in the process memory before, not on disk.
- [~] Folders: a folder's ⋮ menu has "Move to another library folder…" (the other library folders, the same tag
      first; it shows the move preview and starts a task) and "Delete to the trash…" (the trash preview).
      [x] 2026-11-20, Pixel, v1.261120.0804: the menu on a film folder, the dialog lists the other folders with tags,
      the "filmy" ones first (cancelled; the previews not opened).
- [ ] Chromecast and the audio choice (agent 1.14.0, `convert_audio`): "Prepare" for a dual-audio film converts with
      the audio track picked last (the folder's choice first), not always the Polish/first one; the variant then plays
      on the Chromecast with that language.
      [x] 2026-11-20, jmpc, agent 1.14.1 via the API: a synthetic MKV (English AC3 440 Hz default, Japanese AC3
      1760 Hz) converted with `audio: "|ja"` gives a variant with one AAC track tagged jpn at 1760 Hz. [~] From the app
      (2026-11-20, Pixel, v1.261120.1424): "Przygotuj" on an MPEG-4 film with English and Japanese AAC, the folder's
      last choice Japanese: the variant came with Japanese ("Dźwięk w niej: japoński"). Not cast to the TV; removed.

## "All servers" (two or more servers with files)

- [ ] The top switcher has "All servers" at the top; picked, the header says "All servers ▾" with "N of M servers
      on". Picking one server leaves All. "Wake all" when two or more are asleep with a known MAC.
      [x] 2026-11-20, Pixel, v1.261120.0804 (jmpc + the laptop): the row with its subtitle, the header "Wszystkie
      serwery ▾ / Działa 2 z 2 serwerów", Downloads chips (jmpc, Laptop) switch the list and All stays on.
      Found: the merged catalog counted 387 films / 35 series against 404 / 40 on jmpc alone: copies of one title on
      the same server were folded too. Fixed after it (only other servers' copies fold). [ ] Check the counts again.
- [ ] Library in All: Discover/Browse/search over all servers at once; a title on two servers shows once (the better
      copy: resolution, Polish audio), the cover says the server and "+1", the title screen lists "On servers" with
      each copy's quality, playing picks the copy's server. Continue watching and positions stay per server.
- [x] 2026-11-20, Pixel, v1.261120.0826: the counts are right (404 films · 41 series = jmpc + the laptop's one);
      Folders without chips, "Na jmpc" / "Na Laptop" sections, the laptop's folder opens and All stays on.
- [ ] Downloads in All: a row of server chips on top, one server's list at a time. Folders in All: every server's
      library folders in one list, each server under its name; opening another server's folder keeps All on (the
      user, 2026-11-20: chips there made no sense with "All" picked).
- [ ] With the active server asleep and another one on, Library in All doesn't show the asleep pane.

- [ ] Trash on a full disk (agent 1.14.2): a disk under 5 % free sends a shrink's original to another disk's trash
      (`.mp-trash/<date>/<source folder name>/…`) and moves its own older trash days there before an encode.
      [x] 2026-11-20, laptop, unit check with a faked full disk (scratchpad vs ~/.cache): original moved, own trash
      used when there is room, an old trash day moved to make room. [ ] On jmpc with a real run.
- [x] Shrink decode check (agent 1.14.4): only the video decoder's complaints reject a result. Found 2026-11-20 on
      jmpc: the old-format run of one series' S2 extras + S4E08-09 failed 4/4 with "doesn't decode cleanly"; the
      result's picture decoded clean, the only line was "[mp3float] Header missing" from the copied MP3 audio (already
      damaged in the original). The first failing line is now logged. [x] Rerun of the 4 items on jmpc
      (2026-11-20, agent 1.14.4): 4 done, 0 errors, 1033 → 657 MB; a frame at 20:00 matches the original, same length. Also the 2 files of an older run rejected the same way: done, 0 errors, 676 MB saved.
- [x] Shrink: an item that would leave its disk under the 5 % reserve, with no other disk able to take the original,
      isn't started: "dysk zszedłby poniżej rezerwy 5%…" in the task's errors (agent 1.14.3). Unit check on the laptop (fake disk
      usage, 2026-11-20): refused with no other disk, passes when another disk has room. [x] Real disks (2026-11-20,
      laptop, two 1 GB ext4 loop images via udisks, a throwaway agent 1.14.5): with only disk A in the library the item
      was refused with that error; with disk B added it ran, the original went to A's own trash. Not on jmpc's disks.
- [x] "All servers" header: Wake / Sleep name the server they act on ("Obudź jmpc"; several asleep: "Obudź wszystkie";
      several awake: "Uśpij…" opens a list), no longer the hidden active server only. (2026-11-20, Pixel, v1.261120.1000) Seen: "Uśpij jmpc" with the laptop active; offline "Obudź wszystkie". The "Uśpij…" list (two awake servers with power control) (2026-11-20, Pixel, v1.261120.1336): laptop power control switched on for the test, "Uśpij…" opened a list "jmpc / Laptop" (nothing picked; setting restored).
- [x] Cancelling a running task asks first ("Przerwać …?", "Niech działa"); "Monitoruj" moved under the progress,
      with a bell icon. The shrink preview says the space comes back only after the trash days. (2026-11-20, Pixel, v1.261120.1000) Dialog seen, "Niech działa" kept the task running; Monitoruj with a bell under the progress.
- [x] Offline, a video with nothing of it on the phone: a dialog "Nie ma go na telefonie" (not downloaded, the server
      doesn't answer) and back, instead of a player at 00:00 reconnecting for a minute. Also from the Discover hero.
      A buffered video offline no longer waits for the server's subtitle list. (2026-11-20, Pixel, v1.261120.1000) From the Discover hero, Wi-Fi and data off after a force-stop; OK closes the player.
- [x] Player: a spinner from opening until the first picture and while buffering. (2026-11-20, Pixel, v1.261120.1000) Seen in a screen recording while a local x265 episode opened.
- [x] Resume starts at the keyframe before the saved position (then seeks are exact again): picture and sound start
      together. Check on an x265 episode resumed mid-way (before: sound ran while the picture stood still). (2026-11-20, Pixel, v1.261120.1000) Recording of a resumed x265 episode: the picture moves from the first frames.
- [x] Episode list: "Na telefonie" / "Na telefonie do 90%" / "Pobiera się na telefon" under the episode's title, in
      the accent colour (the grey icon at the end read as "watched"). (2026-11-20, Pixel, v1.261120.1000) "Na telefonie" and "Na telefonie do 90%" seen; "Pobiera się" not exercised. (2026-11-20, Pixel, v1.261120.1424) "Pobiera się na telefon" under the six episodes of a season being downloaded (removed afterwards).
- [x] "Move to…": each target folder shows its disk's free space. "On the phone" header shows the phone's free space. (2026-11-20, Pixel, v1.261120.1000) "wolne 706 GB / 321 GB" per target; "wolne na telefonie 45 GB" in the header.
- [x] Task card: errors once per reason with a count ("457 × brak miejsca na dysku"), ffmpeg's reason without temp
      paths (agent 1.14.2 `error_groups`; older tasks are grouped too). (2026-11-20, Pixel, v1.261120.1000) "457 × brak miejsca na dysku", "2 × wynik nie dekoduje się czysto…".
- [x] "All servers" → Folders: server sections keep the order of the server list (no swap after opening a folder);
      inside a folder a line above the path says "jmpc: Seriale (disk)". (2026-11-20, Pixel, v1.261120.1000) Order kept after opening the laptop's folder; "Laptop: Videos" and "jmpc: Filmy (<disk>)" above the path.
- [x] Remote / jmpc screen: a library video shows its recognised name ("Series · S2 E3", "Film (1968)") instead of the
      release's file name (agent 1.14.2). (2026-11-20, Pixel, v1.261120.1000) Remote and player/status: the film's recognised title with its year (agent 1.14.4).
- [x] A title's screen: "Na serwerach" only with two or more copies. (2026-11-20, Pixel, v1.261120.1000) Single-copy title: no row. (2026-11-20, Pixel, v1.261120.1336) Two copies (a 10-minute cut of a public-domain film copied to the laptop's library for the test): both rows shown.
- [ ] Chromecast version and audio (agent 1.14.2): for a file with two or more audio tracks the cast sheet says the
      version's sound ("Dźwięk w niej: angielski") and, when the language picked since differs, offers "Przygotuj
      ponownie: japoński" (removes the old version, prepares one with the new track).
      [~] (2026-11-20, Pixel, v1.261120.1424) "Dźwięk w niej: japoński" shown. "Przygotuj ponownie" not reached: the
      other film of that folder (to pick English) didn't play on the phone (see the UX notes).
- [x] "All servers", cover cards: the card's own line first, the server after it ("Dalej: S1 E3 · jmpc"), so a cut
      line loses the server, not the episode. (2026-11-20, Pixel, v1.261120.1000) "Dalej: S1 E1 · jmpc".
- [x] Audio sheet (player and Remote): a track's title loses the release's codec/channel claims and a bare language
      ("English: 5.1ch (AC3 640Kbps)" shows nothing extra; "Lektor PL (AC3 2.0)" → "Lektor PL"); the line under it,
      read from the file, says channels and codec. (2026-11-20, Pixel, v1.261120.1424) Phone player, an anime episode with "English: 5.1ch (AC3
      640Kbps)" / "Japanese: Stereo (FLAC 24bit)": rows "Ścieżka 1 · 5.1 · AAC", "Ścieżka 2 · stereo · AAC". Remote not checked.
- [ ] Remote's notification: another app's session (Spotify) left paused on a Chromecast ends our notification and
      foreground service after ~2 minutes (before: 10+ minutes); opening the Remote brings it back. Our own paused
      video keeps its notification.
- [x] Discover hero: a carousel of a few slides ("Nowe na twoich dyskach" ×2, "Podobne do „…”", "Najwyżej oceniany",
      "Na chybił trafił"), in a random order each time, turning every 8 s, swipeable, dots below. (2026-11-20, Pixel, v1.261120.1000) Turns by itself, dots, random first slide. UX note below: it can turn right under a tap.
- [x] "All servers", a title on two servers: "Play" on the hero / title screen and the episode list use the copy this
      phone watched last (its server and position), not always the first copy. (2026-11-20, Pixel, v1.261120.1336) Watched the laptop copy to
      1:56, reopened the merged title: "Odtwórz" resumed the laptop copy at ~2:00. The mark under "Na serwerach" stayed
      on the other copy: fixed below. Episode list not exercised (no series on two servers).
- [x] Downloads in "All servers": with one server that has a torrent client (the usual case) its list shows without
      server chips; chips only with two or more such servers. A true merged list is deferred (each client has its own
      actions and settings). (2026-11-20, Pixel, v1.261120.1000) No chips (only jmpc has a torrent client).

## Since v1.261120.1000 (agent 1.14.5 + the next app)

- [x] Shrink: a copied audio track that doesn't decode cleanly (damaged MP3 frames from an old AVI) is re-encoded to
      AAC instead of copied (agent 1.14.5; the phone's MP3 decoder stops on such frames). (2026-11-20, laptop, throwaway
      agent on a test disk) An AVI with bytes overwritten in its MP3 ("Header missing" ×3), profile `good` (copies
      MP3): "audio track(s) [0] don't decode cleanly, re-encoded", the result has AAC, 0 decoder lines, same length.
      [ ] On jmpc with a real run.
- [ ] Recognition: "<Collection 2> - 03 - <Title>" style names give the film's own title, not the collection's
      (agent 1.14.5, `ITEM_V` 4 re-identifies the catalog). Checked on names only; [ ] covers and years in the app.
- [x] Player: a damaged audio track no longer shows "the phone can't show this picture (HEVC)": a dialog "Telefon
      nie odtworzy tego dźwięku" with "Bez dźwięku" (picture only, same position), VLC, Cancel. Reproduce with the
      season 2 extra whose MP3 audio is damaged in the original. (2026-11-20, Pixel, v1.261120.1336) That extra: the dialog "(MP3)"; "Bez dźwięku"
      plays the picture (239 frames / 10 s, clean frame).
- [x] Player title: the recognised name ("Series · S2 E3", "Film (1968)") from the cached catalog, not the file name.
      (2026-11-20, Pixel, v1.261120.1336) A film: "Title (1956)"; a series extra (no episode number): the series' name.
- [x] Settings → "Okładki na telefonie": none (always from the server), tiny (~1 KB each, blurred, like a message
      preview), full; the usage line; switching to none deletes them, to tiny shrinks them. Offline cold start shows
      kept covers. (2026-11-20, Pixel, v1.261120.1336) "Miniatury": 223 covers / 203 KB after ~3 min; Wi-Fi off + cold start: blurred covers
      in every row. (2026-11-20, Pixel, v1.261120.1424) Back to "Nie zapisuj": the usage line goes away.
- [x] Discover hero: a touch holds the carousel for 15 s; a dark scrim under the text; the label is white on a dark chip.
      (2026-11-20, Pixel, v1.261120.1336) Screenshots every 3 s: the slide stood ~21-24 s after a touch, then turned every ~8 s; scrim and chip seen.
- [ ] "Play on jmpc" from a folder switches to the Remote (the launch no longer dies with the folder screen).
- [x] Server picker: "Wszystkie serwery" reads "Biblioteka, foldery i pobierania ze wszystkich". (2026-11-20, Pixel, v1.261120.1336)
- [x] People's names: well-known actors and directors showed as Wikidata ids ("Reżyseria Q25191"): Wikidata keeps
      their names only as `mul` (one label for all languages). Agent 1.14.6 asks for `mul` and retries empty names
      once per start: on jmpc 26 unnamed → 0 (API). (2026-11-20, Pixel, v1.261120.1424) "Reżyseria Christopher Nolan".
- [x] "All servers": the "Podobne do „X”" slide/row no longer offers X itself (another server's copy, same IMDb id).
      (2026-11-20, Pixel, v1.261120.1424) The row shows other titles.
- [x] "Na serwerach": the mark is on the copy "Odtwórz" will use (the one watched last), refreshed after the player.
      (2026-11-20, Pixel, v1.261120.1424) Mark on the laptop copy watched last.
- [ ] Folders search in "All servers" (or with other servers' folders listed) asks every responding server, results
      under "Na <server>"; a folder of another server opens there (path kept). Before: only the active server was asked
      (an anime series on jmpc not found with the laptop active).
- [ ] Phone player title: an unrecognised catalog item (no IMDb, e.g. a folder that holds many series) no longer names
      the video; the file name stays (was "ANIME").

## Since v1.261120.1424 (agent 1.15.0 + the next app)
- [ ] Damaged sound (agent 1.15.0): the background check (5 min after start, then daily; MP3 and small files first) decodes the sound of videos
      with MP3/MP2/AC3 tracks once, `audio-checks.json`; "Stare formaty" then lists them as "Uszkodzony dźwięk MP3 → AAC,
      obraz bez zmian"; running it copies the picture and re-encodes only those tracks; the film then plays on the phone
      with sound to the end. [x] 2026-11-20, laptop throwaway agent: a synthetic H.264 MKV with damaged MP3 frames found
      by the preview and by the background scan, fixed (AAC, no decoder errors, picture copied, original in the trash),
      a second preview empty. [x] On jmpc, the real files (9, see below). [x] The item line in the app (2026-11-20,
      emulator, v1.261120.1625, English): "Damaged sound MP3 → AAC, picture unchanged · 490 MB" among the old formats,
      with the warning about files not checked yet.
- [ ] Catalog groups (agent 1.15.0): a top folder holding several series (`ANIME/<series>/…`) gives one title per
      series, each with `group`; "Show - 01" / "Show.01." numbering counts as episodes in series folders; "Show.1-25"
      folder names lose the range. [x] 2026-11-20, jmpc via the API: 8 series from the anime folder, 7 recognised;
      no other folder of the library taken for a group. [x] 2026-11-20, Pixel, v1.261120.1530: Browse search finds a
      series from the anime folder with its own title, year, cover and "24 episodes". [ ] Player title.
- [ ] Covers: blurred kept thumbnails (and missing ones) load once the server answers again, without restarting the
      app (one ping per server every 5 s at most, retries 5 s → 60 s). [x] 2026-11-20, Pixel, v1.261120.1530: cold
      start with Wi-Fi off shows the kept "Thumbnails" covers, after Wi-Fi returns the same covers, sharp. The blurred →
      sharp swap itself not caught on screen yet.
- [ ] Covers: picking "Thumbnails" / "Full" in Settings starts saving at once for every server with a cached catalog
      (before, only the next catalog refresh in the Library saved them). Compiled only.

## Since v1.261120.1530 (agent 1.15.2 + the next app)
- [ ] A film downloaded to the phone while its torrent was still downloading into the library: the copy waits for the
      missing pieces ("Czeka na komputer") instead of taking zeros (agent 1.15.2 serves `/lib/` of a torrent in
      progress like `/media/`). Found 2026-11-20 on the Pixel: a 720p film copied in 34 s while the torrent finished
      1.5 min later; "Cały film na telefonie", offline it played to ~80% and then stood black with a spinner (online it
      played, the player falls back to the server on a parse error). [x] 2026-11-20, jmpc, agent 1.15.2, API: a
      public-domain test torrent downloading into a hidden test folder of a library folder: `/lib/` HEAD said
      `X-Ready-End` 37 of 129 MB, `X-Downloading: 1`, `Last-Modified`; a read near the end waited ~4 s for the pieces;
      finished: `X-Ready-End` = size, no `X-Downloading`, a newer `Last-Modified`. Torrent and folder removed.
      [ ] The app's download of a torrent in progress.
- [ ] Stale copies on the phone: a downloaded copy keeps the file's `Last-Modified`; when the file changed on the
      server (torrent finished after the copy, re-encode, fixed sound), the next catalog refresh drops the copy's bytes
      and downloads it again (copies from before: when the file is newer than the download). Covers the broken copy
      above. [x] 2026-11-20, emulator, v1.261120.1625 against jmpc's agent 1.15.2 (a 6 MB synthetic test file in the
      phone-uploads folder, removed afterwards): a copy made by v1.261119.2356 (no stamp), file unchanged → one HEAD
      after the catalog refresh, no download; after `touch` on the server → downloaded again ("Whole video on phone");
      `touch` once more (now with a stamp) → downloaded again. [x] The Pixel's broken copy (2026-11-20,
      Pixel, v1.261120.1625): after the install one HEAD, no download; with Wi-Fi off the copy says "Whole film on the
      phone · works offline" and plays at 90% (paused frame looked at). Whether it was re-downloaded by itself or was
      already whole before the fix isn't known.
- [ ] Damaged sound fix on jmpc's real files: 9 MP3 files, 0 errors (2026-11-20, agent 1.15.1, API); the files grew by
      ~386 MB in total (AAC came out larger than the damaged MP3). [x] 2026-11-20, jmpc: all 9 have AAC, the audio
      decodes with 0 errors, lengths as before. [x] Played on the Pixel with sound (2026-11-20, v1.261120.1625): AAC
      stereo through the speaker, picture fine, the user confirmed the sound is clean.
- [ ] Phone copies of files that vanished on the server: test files deleted on jmpc still show under Torrents →
      "Set aside this week" as "left in downloads", and copies of library `.avi` files converted to `.mkv` by an
      old-formats task now get 404 (the path changed). Wish: drop or re-link such entries/copies (seen 2026-11-20,
      Pixel, v1.261120.1625).
- [ ] Damaged sound re-encoded at 128k stereo / 256k multichannel instead of 192k / 384k (agent 1.15.3): the fixed
      files no longer grow (the sources are MP3s of ~96-128k).
- [ ] "Tidy up" no longer proposes moving films to the series folder (agent 1.15.3, catalog items re-identified): a
      single video is never matched to a series of the same name, a folder of films with their own years in the names
      ("01. Film (2001)", "02. Film 2 (2002)") isn't a series either, "2004 - Title" names keep the title and the
      year, a title that is just a number is a film (guessit read it as season 3, episode 0), and a title match with
      the right year wins over a closer-sounding one. Checked against the whole jmpc
      library offline (316 items, only the intended ones changed). [x] The app's Tidy up preview (2026-11-20, emulator,
      v1.261120.1625, agent 1.15.3): no film goes to the series folder any more, nothing is ticked beforehand.
- [ ] Duplicates: a copy that is still in the torrent client has no checkbox and says "In the torrent client: remove it
      there" (agent 1.15.3 + the next app; before, it could be ticked and was only skipped in the delete preview).
      API checked on jmpc (32 groups, none in the client right now); to see in the app with a seeding copy.
- [ ] "Get subtitles" leaves out samples, trailers and extras folders (agent 1.15.3): API on jmpc 1252 → 1115 items,
      no sample/trailer left; to see in the app.
- [ ] "Free up space" with "Up to 15% free" on a disk that already has more: the empty preview says why (agent
      1.15.3). [x] Emulator, English (2026-11-20, v1.261120.1625): "⚠ This disk is already 39% free, more than 15%…";
      Polish not seen.
- [x] "Get subtitles" from Disks / a folder: the language dialog's button says "Preview" (it only opens the preview);
      from a film's menu it still says "Download" (it starts at once). (2026-11-20, Pixel, v1.261120.1856, Polish): "Podgląd" in the dialog.
- [x] A whole "Update old formats" task on synthetic DivX 3 files (a throwaway agent on the laptop, 2026-11-20,
      emulator, v1.261120.1625): preview (2 items, the 20 MB file left out as a sample), the task card with progress,
      current file, Cancel and "Stop monitoring", the progress notification (a few seconds behind the card), the
      originals in the trash (2 files, 198 MB, "Empty trash"), the card "Done · saved 152 MB" and the final
      notification.
- [ ] The final notification of a task says what it did: "Updated N files" when an old-format/sound task made the
      files bigger (was "Shrank N files, saved -386 MB"), "Fitted: X · don't fit: Y" for Subtitles and "Looked for
      subtitles for N videos" for Get subtitles (both said "Moved N items"). In v1.261120.1856, not seen yet (needs a
      real task).
- [ ] A library folder without "name" in a hand-edited config no longer breaks the library, the catalog and the
      wish list (KeyError 'name', 500); the folder's own name is used (agent 1.15.3). Seen on the throwaway agent.
- [x] Cancelling a running task (throwaway agent, 2026-11-20, emulator, v1.261120.1625): the "Stop …?" dialog says
      what happens, ffmpeg stops, the original stays untouched, no leftovers, the progress notification turns into
      "Cancelled". [x] The item of a cancelled task now reads "skipped" instead of "waiting" for ever (agent 1.15.3,
      the same app; its note "not done: the task was stopped" isn't shown by the app).
- [x] The subtitle previews say what their number means ("N videos already have them", "N subtitle files fitted
      before, left out") instead of "Checked N folders", and "Subtitles" no longer shows the size of the subtitle
      files. (2026-11-20, Pixel, v1.261120.1856, Polish): "653 filmy już je mają", "8 plików napisów dopasowanych wcześniej,
      pominiętych", no sizes.
- [x] Previews show what they are looking at under the progress bar (agent 1.15.3 `step` + the next app), and the
      Tidy up bar moves within a library folder too (it stood at 7% for most of the preview). API on jmpc: `step`
      present, Tidy up progress 8 → 33% in 20 s. (2026-11-20, Pixel, v1.261120.1856, Polish): the Duplicates preview shows the file
      being checked under the bar. [ ] The "In the torrent client" label in Duplicates: no seeding duplicate right now.
- [x] "Subtitles" task on a video without speech (same setup): the card shows "1 error" and "no speech found in the
      sound"; the old app's notification said "Moved 0 items, 1 error" (the next app: only "1 error").

## UX tasks (`plan-ux-tasks.md`)

Before building a screen, copy its element list from `plan-ux-tasks.md` here as unticked items; tick each element
only after seeing it on the phone next to its board (EN and PL).

### Stage 1 (agent 1.16.0 + the next app): element by element, next to the boards

**A1. Disks and tidy-up: task list (replaces the actions in `DisksScreen`)**
- [x] header "Disks and tidy-up", back arrow (2026-11-20, emulator against jmpc, v1.261120.2138);
- [ ] scope bar "Scope: whole computer · Narrow…"; Narrow… opens a picker with three combinable filters (folder, category, age group) and a title search; applying it opens E2;
- [x] "Background work: N running, M waiting ›" row → C4 (2026-11-20, emulator against jmpc, v1.261120.2138);
- [x] disk bars (name, bar, "X free of Y") (2026-11-20, emulator against jmpc, v1.261120.2138);
- [ ] headings with a one-line explanation: **Tidy up · move and delete**, **Renovation · changes files, original to the trash**, **Subtitles**, **Catalog · doesn't touch files**;
- [ ] one row per task: name, one-line description, counter or state on the right in the accent colour ("2 to move", "39% / 42% free", "32 groups · 20 GB", "not needed" greyed, "14 files", "5 in 4K", "412 files", "9 files", "1113 items", "578", "11 to check" in the warning colour); a "Trash · originals after changes, 14 days · 12 GB" row;
- [ ] "ADVANCED" label and the Re-encode row at the end of Renovation;
- [x] every row opens its own screen; none expands in place (2026-11-20, emulator against jmpc, v1.261120.2138).

**A4. Duplicates**
- [x] description: removes extra copies; at least one of each group stays (2026-11-20, emulator against jmpc, v1.261120.2138);
- [ ] "WHAT IS A DUPLICATE" chips with hint: same files / same film in other versions (from the catalog);
- [x] "WHICH COPY TO KEEP": in the fitting folder / better quality / on the disk with space / I'll choose (2026-11-20, emulator against jmpc, v1.261120.2138);
- [ ] found summary; "Seeding in the torrent client: N. Shown, but deleted in the client.";
- [ ] "Checked in the background today at HH:MM · check now";
- [ ] "Preview".

**A5. Free up space**
- [ ] description: deletes titles from a disk (to the trash); doesn't re-encode or move;
- [x] "DISK" chips with free percentage (2026-11-20, emulator against jmpc, v1.261120.2138);
- [x] "HOW MUCH" chips 50 / 100 / 250 GB / to 15% free, with the "already X% free: nothing to do" warning (2026-11-20, emulator against jmpc, v1.261120.2138);
- [ ] "WHAT FIRST": watched by everyone, oldest first / largest files; skip "keep" and running series;
- [ ] "INSTEAD OF DELETING" links with amounts to Downscale, Balance and Duplicates (links only), with the note that they are separate tasks;
- [ ] "Preview".

**B1. Fix sound**
- [ ] description: tracks with damaged frames become AAC; the picture stays bit for bit;
- [ ] "Found N files", background check status ("checked X of Y films; the rest tomorrow"), time and size estimate;
- [ ] "FILES": first three with codec and error count, "… and N more";
- [ ] "SETTINGS": AAC quality (128k stereo / 256k 5.1), "Fix new files by itself" switch;
- [ ] "Originals: trash on the same disk, 14 days.";
- [ ] "Preview · N files".

**B2. Modernise old picture**
- [ ] description: formats phones and TVs can't play (or play badly) become H.264; resolution unchanged;
- [ ] "FOUND N FILES" with format and size per file; "Files the phone and Chromecast already play well are skipped.";
- [ ] "ENCODER" chips with hint (graphics card fast / processor smaller but slow and loud) and total time;
- [ ] "SOUND": unchanged if it works / always AAC;
- [ ] "Preview · N files · time".

**B3. Downscale**
- [ ] description: files bigger than the target are reduced to it; smaller ones are never enlarged;
- [ ] "TO" chips 1080p / 720p / 480p / 360p, each with a "how it looks" line;
- [ ] "FOLDERS" list with checkboxes, each with its resolution mix and saving at the chosen target;
- [ ] "Remember as the profile of these folders" switch with "New files in them will be downscaled too.";
- [ ] summary "N files · −X GB · about T h on the graphics card" or "Nothing to do in the chosen folders";
- [ ] "Preview".

**B4. Compression**
- [ ] description: same resolution, smaller file (H.265); downscale is a separate task;
- [ ] "QUALITY" chips: no visible difference / balanced / saving, with hint and summary per choice;
- [ ] "WHICH FILES": only high bitrate (over 3 GB per hour), skip H.265 and AV1, skip seeding torrents, folders picker;
- [ ] "ENCODER": graphics card / processor, daytime;
- [ ] summary and "The disk needs up to X GB free while working (originals in the trash).";
- [ ] "Preview".

**C1. Download missing subtitles**
- [ ] description: searches, downloads and fits them to the film's sound; films unchanged;
- [ ] "LANGUAGES" chips (multi-select);
- [ ] "WHICH FILMS": no subtitles at all / none in this language / only new from today; folders picker;
- [ ] "ORDER": recently added and watched / alphabetical;
- [ ] summary: "N items · about N min of work", the OpenSubtitles daily limit and days needed, "M films already have subtitles in the chosen languages"; for "new only": "New films get subtitles by themselves after they're added";
- [ ] "Preview".

**C2. Fit subtitles to the film**
- [ ] description: compares subtitles already next to the film with its speech and shifts them; downloads nothing;
- [ ] "WHICH SUBTITLES": unchecked / only for what's being watched / all again; folders picker;
- [ ] "ALONG THE WAY": text .sub to .srt; mark the ones that don't fit (don't delete); add the ones that don't fit to "Download missing"; "Original subtitles are set aside.";
- [ ] summary and "N subtitle files fitted earlier, skipped";
- [ ] "Preview".

**E2. Optimise: tasks for a scope**
- [x] header "Optimise" ("Optymalizacja"), back to where it came from (2026-11-20, emulator against jmpc, v1.261120.2138);
- [ ] scope bar "Scope: <label>", "<N files · size · missing M>", "✕ whole computer" → A1;
- [ ] the same headings and rows as A1, counters from F1 for the scope;
- [ ] a task with nothing to do in the scope: greyed, "nothing to do", not hidden; disk-wide tasks (balance, free up, trash) replaced by the one-line note that they are on the whole-computer list;
- [ ] opening a task keeps the scope (F6 scope bar instead of the folder picker); the created task carries the scope label (C4).

**Emulator run (2026-11-20, v1.261120.2138 against jmpc, EN and PL)**: A1, A4, A5, B1, B2, B4, C1, C2, E2 (folder
scope), D3, D4 (add via API, edit kind in the app → saved on the agent with `kind`/`open`, delete), D5 (⋮ menu,
Select bar) open and show live counters. Found and fixed for the next tag:
- [x] B1 examples said "0 frame errors" for checks from before 1.16 (the agent sends null): now only the codec (2026-11-20, emulator against jmpc, v1.261120.2210);
- [x] B1 preview offered an encoder choice and "saves 0 B" (sound fix copies the picture): gone; the summary is "N items · size · h of video" (2026-11-20, emulator, v1.261120.2210). The hours used a decimal point in Polish ("13.6 h"): now the phone's locale, next tag;
- [x] A4 "Seeding in the torrent client: 0" shown at zero: now hidden (2026-11-20, emulator, v1.261120.2210; jmpc has no seeding duplicates now, so the note with a count above zero is unchecked);
- [x] C1 preselected only the first subtitle language: all languages preselected (2026-11-20, emulator, v1.261120.2210).
  The counts still differ by design, as on the boards: A1 counts "no subtitles in that language" (1732), C1 opens on
  "no subtitles at all" (961) and shows 1732 after picking "no subtitles in that language".
Not checked on the emulator: D6 (needs a downloaded episode), starting a task, B3 with files above 1080p (none on
jmpc). Typing fast into text fields loses characters on the emulator (slow typing works): an emulator artefact.

**Where agent 1.16.0 differs from the boards (decide or fix later)**
- "Watched" means streamed from this server since 1.16.0 (`played.json`, empty at first). The phones don't report what
  was watched, so A5 "watched by everyone" has nothing to go on until a phone → agent watched-state sync exists.
- A5 "skip keep and running series": there is no "keep" marker and no knowledge of running series yet; the agent
  skips titles streamed in the last 14 days instead.
- A5 deletes whole titles to the trash (14 days), as the board says; with a scope it answers 400 (disk-wide only).
- B4 skips old formats (they belong to B2); counters on A1: B3 = files above 1080p, B4 = the balanced quality.
- The background scan behind the counters ffprobes every video once (niced, ionice idle, ~5 files/s at most) and
  waits while somebody watches from the same disk. The first pass on a large USB library takes a while; until then
  the counters say how many files are still unchecked.
- [ ] the first background scan on jmpc doesn't disturb a stream (watch something from a library disk during it).
- [ ] `GET /api/catalog/episodes` (stage 5 data, agent side only for now): a series on jmpc gives its seasons with
  the own episodes marked (checked locally with a public series and no files).

### Stage 2 (agent 1.17.0 + the next app): element by element, next to the boards

Checked on a throwaway agent with synthetic files only (layout into Films/Kids with empty folders removed; AVI → MKV
and AC3 5.1 → an AAC stereo default track with `.en.srt` written; pause → encode stopped, survives a restart, resume
re-attaches; cancel leaves no encode). Nothing of it ran on jmpc or the real library yet; the app part wasn't compiled
by the stage's author.

**A1 / E2 rows**
- [ ] "Folder layout" and "Balance between disks" under Tidy up, "For Chromecast" at the end of Renovation; hidden with
  an agent before 1.17 (no `layout`/`balance`/`cast_ready` in `features.jobs`).
- [ ] counters: layout "N to move", balance "39% / 42% free" (grey when nothing would move), For Chromecast "N files".
- [ ] E2 (a scope): layout and For Chromecast counted for the scope; balance replaced by the disk-wide note.
- [ ] Free up space (A5) "Instead": "Balance between disks: move X to the emptier disk" when balance has moves; opens A3.

**A2. Folder layout**
- [ ] description: moves titles into folders by catalog tags wherever they are now;
- [ ] "ARRANGE BY (LEVELS IN ORDER)" chips: kind, age group, category (anime…), genre, film series, decade; a tap adds
  or removes a level, the number on the chip is its place, the order of taps is the order of levels;
- [ ] "IT WILL LOOK LIKE" tree updated from the chosen levels (the counter is read again with `levels`);
- [ ] "AGE GROUPS" chips 0+ and 7+ → Kids, 12+ → Teens, 16+ and 18+ → Films; tapping opens the editor, a change is saved
  on the agent and the tree follows;
- [ ] "DON'T TOUCH": folders I arranged myself (names, detected), titles without a sure match (count), seeding torrents
  (unticked = moved whole through the client; deviation from the board, where it is ticked: decision 13);
- [ ] the hand-arranged folders list comes up before the first preview and is remembered after "Confirm";
- [ ] note: every title stays on its disk; moving between disks is "Balance between disks";
- [ ] primary button "Preview · about N moves"; the preview: unsure titles unticked when "without a sure match" is
  off, a torrent with several titles shown as moving together; the task runs between files of a running encode.
- [ ] on the real library: no title of a hand-arranged folder moves; the catalog still finds the titles after the move
  (no re-recognition of everything moved).

**A3. Balance between disks**
- [ ] description: moves whole titles between disks; the folder layout on each disk stays the same;
- [ ] "GOAL" chips with a hint each: even free space / one kind per disk / fill the newer disk first ("newer" = the
  largest disk);
- [ ] "NOW → AFTER" per disk with percentages and "To move: N titles · X GB · about T" for the chosen goal;
- [ ] "RULES": a series stays whole (always, shown ticked and greyed), don't move what someone watches now, a seeding
  torrent moves whole through the client and keeps seeding, backup folders on both disks;
- [ ] "Preview": the plan shows each disk before → after; a real move between the two USB disks finishes and the
  torrent keeps seeding from the new place.

**B5. For Chromecast**
- [ ] description: today "Prepare" converts at every viewing; this does it once, in the file;
- [ ] "FOUND N FILES" with the reason per title (DTS 5.1 sound, AC3 · subtitles in the file, AVI container), a series as
  "Title, N episodes", "… and N more";
- [ ] "WHAT IT DOES": the three checkboxes; "Picture not re-encoded. Files grow about 2–4%. About T per file.";
- [ ] "Preview · N files"; after the task the file casts to a Chromecast without "Prepare", with sound, and the
  `.srt` shows up as subtitles.

**C4. Background work** (agent 1.17+; an older agent keeps the old task cards)
- [ ] "RUNNING": a card per task with its scope, "running"/"paused", "N of M files · −X GB so far", progress bar,
  "Now: <file> · encoder · about T left", the slow-down reason when someone watches from the same disk;
- [ ] "Pause" / "Resume" (the encode stops and goes on from where it was, also after an agent restart);
- [ ] "Cancel…" with the inline confirmation (finished files stay, the current one returns to the original, M won't be
  done) and "No" / "Stop";
- [ ] "QUEUED": a card per task with its estimate ("Quick: starts in a break between files", the OpenSubtitles limit,
  or about T), ↑ moves it up, ✕ takes it off the queue;
- [ ] "RECENTLY": one line per finished task ("Today · <task>: N files, no errors" / "N files not done"), its errors
  grouped under it; a full disk shows "No space on <disk> (trash X) · Empty the trash and retry", which asks first,
  empties the trash and queues the failed files again;
- [ ] the bell on a running card follows the task with the notification, as before.

### Stage 4 (the same app release): Settings, search sites, On the phone, title page from the phone

**D3. Settings**
- [ ] sections SERVERS, SEARCH ("Search sites ›" with count and kinds, "Subtitle languages"), ON THE PHONE ("Space
  for copies" with "up to X · delete …", "Player"), OTHER ("Updates", "About", plus "Downloads" so older settings
  stay reachable); back returns to the list.
- Differences from the board: "Updates" shows only the app version (server updates stay in the server dialog);
  "Player" lists buffer / screen / pausing (the settings that exist), not decoder / subtitles / next episode.

**D4. Search sites**
- [ ] full screen with intro and "where they appear"; cards (tap = edit, delete);
- [ ] form: name, address with `{q}`, phrase rules, kind (torrents / video / subtitles), "In the app" / "In the
  browser" with hints, "Check", "Save"; the kind and opening mode survive a round trip through the agent (1.16+);
- [ ] reached from Settings, from the server dialog (marked "moved to Settings") and from a missing title
  ("Set up search sites ›"); back returns to where it came from;
- [ ] "In the app": a magnet goes to the server, a video file lands in the phone's Downloads (and reaches the
  server only when the Downloads watcher is on).

**D5. On the phone**
- [ ] tile tap opens D6, ▶ plays, ⋮ quick actions (play from…, on a screen, get subtitles, fit subtitles, keep for
  good, delete from phone, search subtitles on sites, details);
- [ ] "Select"/"Done" and long press; bar "N selected · size", "All", "Watched", "Subtitles XX ▾", "Delete · N";
  both the card and the compact view.
- Simplifications: the subtitle filter looks at the first language only; an item without a catalog title plays.

**D6. Title page from the phone**
- [ ] the Library title page scrolled to the tapped episode with its season open; back returns to D5;
- [ ] seasons badge, "▶ SxxEyy from m:ss", one "On a screen…" chooser, "server · disk · size" line;
- [ ] card "On this phone": count and size, delete rule, "Download the next 3", "Keep for good", "Delete from
  phone…" with confirmation; season line "subtitles: PL 2 · EN 1 · none 1"; ⋮ per episode; works offline.
- Not done: the "← Library" breadcrumb text (arrow only). The season subtitle line costs one `/api/subs/list` per
  episode, also in the normal Library: watch for slowness on long seasons.

**D1. Search: have and don't have** (agent 1.17, `features.catalog_search`; boards Szukaj)
- [ ] the search field under the Discover / Browse / Folders switch on every tab, placeholder "Search a title, actor,
  director"; one query shared by the three tabs, kept after opening a result and coming back;
- [ ] the line under the field while typing: "Searches your disks and the film database (Wikidata/IMDb)";
- [ ] "ON YOUR DISKS · N" (Discover, Browse): cover, title, "year · length · folder" (+ server in "All"), "Play"
  (a film plays on the phone, a series opens its page); a row tap opens the title page;
- [ ] Folders: "ON YOUR DISKS · N" over the file and folder results (as before), the "don't have" group below them;
- [ ] "YOU DON'T HAVE IT · FROM THE FILM DATABASE": titles from the agent's IMDb index (films and series), greyed
  cover, title, "year · kind (genre or "series") · minutes · ★", "want" when on the wishlist; nothing the catalog has;
  a spinner while it loads, "Nothing else in the film database." when empty, a note when the server has no index yet;
- [ ] a "don't have" row opens D2; back returns to the results with the query;
- [ ] "PEOPLE" (Discover, Browse): name · actor/director · "N films on your disks", tap opens the person;
- [ ] an agent before 1.17: no "don't have" group at all, the rest works;
- [ ] Polish: "NA TWOICH DYSKACH · N", "NIE MASZ · Z BAZY FILMÓW", "LUDZIE", "Odtwórz", "chcę".
- Differences from the board: people show no "M others" count (it needs a Wikidata query per person; the person page
  has them); the "don't have" group matches titles that *start* with the query (original or primary IMDb title, no
  Polish titles); the board's "On every Library tab:" prefix of the hint is a design note, not shown.

**D2. A title you don't have** (boards BrakTytulu)
- [ ] header "← You don't have it on your disks"; back returns where it came from (search, person, what next, list);
- [ ] cover beside the title, "year · minutes · ★ rating", "kind · genres · age+", "dir. <name>" (opens the person);
- [ ] "SEARCH": one button per household search site, every kind (subtitle sites too), "Set up search sites ›" in
  the same row (opens D4, back returns here); with no site only the link;
- [ ] the phrase line "Phrase: „Title Year”. Pick a result in the browser; the magnet comes back to the app and lands
  in this title (right folder from tags)." / „Fraza: „Tytuł Rok”. Wybierz wynik w przeglądarce, a magnet wróci…”
  (only with sites); each site opens per its setting ("In the app": in-app browser, "In the browser": the system
  browser), all with the same phrase "Title Year";
- [ ] "Want to watch" (filled; "On your list" once added, tap again removes) and "Paste magnet" side by side;
- [ ] the description: the lead of the Wikipedia article (Polish phone: the Polish article when there is one) with
  "Source: Wikipedia"; absent offline or without an article (agent 1.17+);
- [ ] "SIMILAR · YOU HAVE N": covers marked "on disk" / "you don't have", on-disk ones first; tap opens the title
  page or another D2 (appears after "What next" answers, up to ~10 s the first time);
- [ ] a title that is on the disks after all: header "On your disks" and "Open" instead of the search block.
- [ ] nothing below "Similar": no cast row, no "Why" line (not on the board).
- Differences from the board: the cover is the drawn one (no poster images are fetched); the board's second note
  (how "Set up search sites" behaves) is a design note, not shown.

**Emulator run before the tag (2026-11-20, local debug build of agent 1.17.0 + app, against jmpc, EN)**: seen working
on real data, previews only, nothing started or confirmed:
- [x] A1 rows "Folder layout · 68 to move", "Balance between disks · 39% / 42% free", "For Chromecast" (count equals
  B5's "Found N files");
- [x] A2: level chips with order numbers, live tree per library folder, age groups (three chips), "Don't touch" with
  the hand-arranged count, "Preview" asks for the hand-arranged folders first (list correct; cancelled, not confirmed);
- [x] A3: goals, now → after per disk, "To move: 1 title · 24 GB", rules;
- [x] B5: found files with reasons (series grouped), options, preview button;
- [x] C4 "Recently" with grouped errors and "Empty the trash and retry" (not tapped);
- [x] D1: "On your disks" and "You don't have it · from the film database" groups; D2 page with Wikipedia lead,
  "Similar · you have N", "Set up search engines ›" when there are none;
- [x] E1: seasons from IMDb with missing seasons collapsed ("you don't have it · 12 ep."), "Search the whole season",
  per-episode "Search" sheet with the phrase "Title S02E01";
- found and fixed in the agent: for some anime Wikidata lists the seasons as the "episodes" (4 instead of 89), and
  their titles were laid over IMDb's episodes 1–4: a Wikidata list shorter than the episode files on disk now counts
  as incomplete (IMDb is used) and is matched only by IMDb id.
- [x] A2 question for the owner, answered 2026-11-20: the current folders are a mess, any consistent layout is fine;
  the one exception is the Polish cartoons folder, an exclusive category (cartoons in Polish) that must stay as it is.
- [ ] Agent 1.17.1, folder specificity (the owner's idea, 2026-11-21): several fitting folders on a disk are chosen
  like CSS specificity (`bajki` + `pl` beats `bajki` for a cartoon with Polish sound; unknown language or a tie =
  stays). Checked only on synthetic units in Python. On jmpc the Polish/English cartoon folders still have only
  `bajki`: they need `pl` / `en` added (owner's OK) before the layout preview can show it. Also: MCP `set_title_tags`
  (hand tags, e.g. `pl` for a title whose dub has no language tag) not called yet.
  2026-11-21, jmpc: tags `pl`/`en` added to the two cartoon folders with the owner's OK; layout preview only (not
  run): the Polish folder untouched, English cartoons go to the English folder ("bajki + en").
- [ ] Agent 1.17.1: the Kids group = titles for children, not every low age rating (the owner's point: live-action
  comedies rated 6 went into the cartoon folders). Layout preview on jmpc (2026-11-21): only animated titles go to
  the cartoon folders now, nothing leaves them. Left: a folder holding a live-action fantasy film was matched to its
  1970s animated namesake and still goes to the cartoons; misrecognised titles with a year prefix ("1961 - Title"
  matched to the 1996 remake) — a recognition bug of its own, to fix in the catalog.
- [x] "Continue watching": a film's tile played at once instead of opening its page (the 2026-11-19 wish covered only
  series). Now every tile opens the title page; a film watched part-way shows "▶ Resume from 1:02:10" there. (2026-11-21, emulator,
  debug build of main before the tag: tile → title page with "Resume from 0:59", which resumed at 1:00)
- [ ] not seen yet: scope entry points (stage 3), pause/resume/reorder of a running task, D2 search buttons with
  engines set, PL wording of the new screens.

### Stages 3 and 5 (the next app, agent 1.16+ with `jobs_v2` and `episode_lists`): element by element, next to the boards

**Scope entry points (E2 everywhere)**: each opens "Optimise" with its scope; back returns to where it came from;
the scope bar shows the agent's label; nothing appears on an agent without `jobs_v2` or for a guest.
- [ ] Folders: ⋮ on a folder → "Optimise this folder" (also on a library folder's top level, where ⋮ used to be
  hidden);
- [ ] Library filtered by a category or age group: the `AutoFixHigh` icon in the Results header (genre or tag, and/or
  "who watches" age) and next to the tag chips in Folders → the filter becomes the scope (category and age combined);
  no icon when the filter has other parts (decade, language, person…);
- [ ] film or series page: the icon button next to ▶ (the word "Optimise" too on a wide screen) → scope = the title;
- [ ] season: ⋮ on the season header (E1) → "Optimise this season"; on an older agent the icon in the "Episodes" row
  acts on the season shown;
- [ ] a single file: ⋮ → "Optimise…" in the folder view and in an episode's ⋮ on the title page;
- [ ] selection: "Select" / "Done" on the title page header, checkboxes on episodes on the disk, bottom bar
  "Selected N" and "✨ Optimise · N ›" → scope "<title>, selected"; back while selecting ends it;
- [ ] A1 "Narrow…": FOLDER (checkboxes), CATEGORY (folder tags, then the most common genres; one), AGE GROUP (0+ … 18+,
  several), TITLE (search in the catalog, one title as a chip with ✕), the note that everything must match; Apply
  opens E2 with all of it; the old "come in a later version" note is gone;
- [ ] from "On the phone" (D6) and from another server's copy in "All": the Library of that server opens with E2;
- [ ] E2 with a series as the scope: "N files · size · missing M" (M from the episode lists).

**E1. A series in the library: have and don't have**
- [ ] header: back, breadcrumb "Library › Series" ("Select episodes" while selecting), "Select" / "Done";
- [ ] cover, title, years, seasons and episodes (counts from the catalog list), rating, tags,
  "You have N of M · size · disk";
- [ ] "▶ <episode> from <position>" and the Optimise icon button (→ E2 with the series as scope; text "Optimise" if
  the width allows);
- [ ] one collapsible header per season: "Season N · year", "have X of Y · missing E04" or "you don't have it · N ep.";
  the season of the tapped / continued episode open, the rest collapsed; a season not out yet collapsed with
  "not out yet · N ep.";
- [ ] episodes on disk: number, title (from the catalog), state (watched, position bar, size, subtitles), phone
  mark, ⋮ as before (plus "Optimise…");
- [ ] missing episodes: grey, dashed separator, "Search"; not yet aired: the air date instead;
- [ ] a missing season: collapsed, "Search the whole season" when expanded, then its episodes;
- [ ] the search sheet: phrase (editable, "Title S01E04" or "Title S02"), one button per search site, "Set up search
  engines ›" → D4 (back returns to the title), "Close";
- [ ] a magnet from a result lands in the series' folder (the magnet card says "To: <folder>"), and the episode
  appears on the page after returning to the app;
- [ ] selecting: checkboxes on episodes that exist, bottom bar "Selected N" and "Optimise N ›" → E2;
- [ ] own videos that match no catalog episode under "Other videos in the folder · N"; extras as before;
- [ ] an agent without `episode_lists` (or a series without an IMDb id): the page exactly as before (season chips);
  while the first request waits for Wikidata (up to 30 s) the old list shows, then the full one.
- Not done: the same for film series (missing parts like missing episodes): the agent has no list of a film
  series' parts yet. The magnet → series folder link is kept on the phone for two hours after the sheet opens and is
  used only when the magnet's name (or the agent's recognition) contains the series' title.


### Agent 1.17.2: recognition (2026-11-21)

- [ ] Owner's report: a film in a franchise folder was catalogued as the newest film of that franchise (the folder
  name decided), and a 1961 cartoon as its 1996 remake. Fixed in `mp_ident`/`mp_catalog` (see plan-library 15);
  old vs new over all 730 catalog names on jmpc: 8 better, none worse. `ITEM_V` 8: the catalog redoes its items
  (no ffprobe). Still to see in the app: those titles with the right poster and year after the rescan.

- [ ] Agent 1.17.2, subtitles (owner's report: a film in a collection folder found nothing until its title was typed
  by hand): a last round with the catalog's titles, year and IMDb id. Checked through the API on jmpc (2026-11-21):
  round 1 asked by the collection folder's name, round 2 by the catalog title found 25 matching results including
  Polish ones (nothing downloaded). Not seen in the app yet; the round text is now "another round with the title …".

- [ ] Agent 1.17.3+ + app, subtitle signals: each search result carries `downloads`, `machine` (machine/AI
  translation), `trusted` (trusted uploader), `fps` and `fps_off` (a frame rate other than the video's: usually another
  release, out of sync), `imdb`; the list and the automatic pick sort by language, then not machine-translated, hash
  match, same frame rate, score, trusted, downloads. The app shows "12K downloads · trusted uploader" and, in red,
  "machine translation" / "made for 24 fps, the film has another frame rate". Checked on jmpc in a separate process
  (2026-11-21, a 25 fps film: machine translations last, 23.976/24 fps ones marked); app compiled only. To see in the
  app: the badges and the order on a phone.
- [ ] Agent 1.17.6: agent and app update checks keep to their 6 h on the wall clock (waits of at most 15 min): the laptop
  agent slept from one afternoon to the next evening and then still waited out the rest of its 6 h (monotonic clock
  stops during suspend), so it never fetched a release that came out meanwhile. To see: the laptop picks up the next
  release within ~15 min of waking.
- [ ] Agent 1.17.5, subtitles: among hash matches NapiProjekt (no download quota) goes before OpenSubtitles.
  Measured on jmpc (2026-11-21, 40 random films + 12 series, search only): NapiProjekt has Polish subtitles by hash for
  18/40 films and 9/12 series episodes, OpenSubtitles title matches for 22/40 (PL) and 22/40 (EN); only 2 films had
  Polish only from free sources. BSPlayer failed for 29/40 films (and all series), Podnapisi for everything, Gestdown
  gives English for series only. Timings of free candidates compared pairwise (`mp_subsync.align` + share of lines
  starting within 0.5 s): a NapiProjekt Polish file agreed 0.99 with English hash matches of BSPlayer, and series
  pairs showed the 25↔23.976 drift (ratios 0.959/1.043). Too few files with two independent sources and cached speech
  (8 and 1) to say how well agreement predicts the speech fit: voting stays an idea.
- [ ] Agent 1.17.4, catalog checked by the file's hash (OpenSubtitles search, no downloads): 3+ subtitles for another
  film replace the name's film, agreement marks it sure. On jmpc's data three such fixes are expected (sequels
  named as earlier films, a same-name older film); see the journal "the file's hash says". To see in the app: their
  posters and years after the check. Agent 1.17.6: series as well (by their first episode).
- [ ] Episode numbers (agent 1.17.4): `Show 0205`, `Show 02.05`, `S02.E05` read as S2E05; a lone 3-4 digit number is
  read with the rest of its folder (101..112 + 201..213 = season-coded; 98, 99, 100, 101 = absolute, as in anime);
  dates, `5.1`/`DD2.0` and `H.264` are never episodes. 45 synthetic cases pass; on jmpc's library (202 folders) no
  series changed, 7 films lost a bogus episode number taken from "5.1". The new folder logic is tested only on
  synthetic names (the library has no SSEE or 100+ episode names).

## Battery: nothing runs in the background without a notification (next app after v1.261122.1912)

- [ ] The Remote tab left open, app in the background: no multicast lock (`dumpsys wifi`, "Multicast Locks held"
      without `magneticplayer-announce`) and no Chromecast discovery. Measured before the fix on the Pixel
      (v1.261121.0922): ~0.7 s CPU per minute and the lock held; Library tab in the background: ~0.1 s per minute.
- [ ] Another app's session on a Chromecast (music started from that app) is followed by the Remote's media
      notification only while our app is on screen. Found on v1.261122.1956: with the app in the background the
      notification service kept polling a music app's Chromecast session (~1.8 s CPU per minute, multicast lock held)
      for as long as the music played. After the fix: our notification goes once the app leaves the screen, the
      lock is released; our own casts (mpv, our Chromecast/DLNA playback) keep their notification.
- [x] Root cause of the warm phone, found 2026-11-22 on v1.261122.2131: media3's `SimpleBasePlayer.release()` skips
      `handleRelease()` unless `COMMAND_RELEASE` is available, so every Remote notification that went away left its
      polling loop running (every 1.5 s to the TV or the server, ~80 CPU ticks a minute, no notification, a route
      still shown in the system's output switcher) for as long as the process lived, one more per target played.
      After the fix: Remote tab with music on a Chromecast, then Home; a minute later the process uses almost no CPU
      (per-thread ticks in `/proc/<pid>/task`), no `castnet` threads, no route of ours in
      `dumpsys media_router`. Measured (2026-11-22, Pixel, v1.261122.2223) with music from another app on a
      Chromecast: 13 ticks in the first minute after Home, then 1 tick a minute (before: 80-93), no route, no
      `castnet` threads. The process stays unfrozen while the system keeps our route provider bound (another app
      looking for routes, e.g. the music app casting): memory only, no CPU.
- [ ] Seen 2026-11-22 on the Pixel (v1.261122.2243): Wi-Fi off → "no local network: all away" and the header "not on
      this network" within 12 s; Wi-Fi on → "here" after 8 s; in the background no connections and ~13 ticks a minute.
      But with the app on screen something still opened a connection every few seconds (SYN_SENT over mobile data):
      the next app makes every API call and cover load to a server that isn't on this network fail at once, only
      Home's check, the header's ping (once a minute) and Wake really ask.
      [x] Fixed (2026-11-22, Pixel, v1.261122.2306): Wi-Fi off with the app on screen, 30 samples over ~60 s of the
      phone's TCP table: 2 entries to the server (the header's once-a-minute ping), was 85.
- [x] Away from home (`Home.kt`): on mobile data only the header says "not on this network" at once, no pings to the
      server and no CPU use while the app sits in the background; Wi-Fi back on at home: "here" within a few seconds,
      no restart needed. (2026-11-22, Pixel, v1.261122.2306; the Library copy not looked at in this run)
- [ ] At home with jmpc asleep: "asleep or off" with Wake (the Chromecast next to it is remembered), not "not on this
      network". First run after the update: the neighbours are learned the first time jmpc answers.
- [ ] Another Wi-Fi with the same address range (or a hotspot from the other phone): "not on this network", the token
      isn't sent anywhere; "Check again" checks once; Wake still tries.
      Seen 2026-11-22 on the emulator (v1.261122.2306): its traffic to the server's address redirected (iptables, the
      emulator's cgroup only) to a fake agent answering with another id. Verdict "away" within 8 s of joining; the fake
      got only pings without a token (two at once, then one a minute with the app on screen); back home "here" after
      12 s. [ ] But the header said "a different server is at this address" and Downloading said "the router may have
      given jmpc a new address, pair it again": misleading at somebody else's Wi-Fi. Next app: when the server is
      away, the away message wins (the wrong-server one stays for home, where neighbours prove it's the same place);
      "Check again" not reached because of it.
- [ ] Two servers in two homes: each is "here" only in its own home; "All servers" keeps one library, titles of the
      server that is away stay listed from the copy.
- [ ] A laptop agent that travels: "here" wherever it answers, never "asleep here".
- [ ] A paused player behind another app: no HEAD requests every 3 s once the buffer is full (or the server is
      away); a background download of the buffer still finishes; back in the player the buffer line updates again.
- [ ] Server sheet, cover settings, Discover hero, covers waiting for an offline server, uploads list, subtitle
      search progress: their polling stops while the app is off screen and resumes when it is back.

## Subtitle providers that don't answer (next agent)

- [ ] A provider still connecting after 60 s (BSPlayer's servers stopped accepting connections on 2026-11-22) is
      marked "no answer in 60 s", the search goes on with the others, and for 30 min it shows "didn't answer, tried
      again at HH:MM" instead of being asked (journal: "subs: bsplayer didn't answer"). Background subtitle tasks no
      longer wait minutes per file on it.

## Subtitle fit verdict (agent 1.18.4)

- [x] Read-only on jmpc (2026-11-23): 49 subtitle files next to films with speech already measured: 27 fit before and
      after, 20 that read "unsure" or "bad" now fit (all within ±1 s at ratio 1, one at 25→23.976), 2 stay bad.
- [ ] The subtitle sheet on the phone: badges of files assessed before show "fits" when they lined up as they were.
- [ ] "Download missing subtitles" on a film with a weak-peak candidate keeps the first one (no second and third
      download), and a file that needs another frame rate is rescaled without a shift.
- [ ] "Fit to the film" with "set aside" sets aside only the really unfitting files.

## Subtitle download fails with "Object of type bool_ is not JSON serializable" (next agent)

Reported 2026-11-25 on a film from the library, downloaded while it was being played (the torrent was still
downloading). Two bugs in a row:

1. The first download's fit failed ("no speech found": the video was not complete yet), so the file had no assessment.
   A shift by hand in the player then stored only `{"manual": …}`, and `GET /api/subs/list` read `r["verdict"]`:
   KeyError, 500 for every list of that film's subtitles from then on (the player lost its subtitles). Fixed in
   `mp_api_subs.subs_list`: `fit` carries whichever of `verdict`, `z`, `manual` the assessment has (`manual` was
   documented in `api.md` but never sent; the app already reads it).
2. A later download fitted "weak" (agent 1.18.4: lines up where it is, z 3–6): `align()` compared a numpy offset,
   so `weak` was `numpy.bool_`; `Store._remember` failed in `json.dump` (the job error the app showed), left the bad
   entry in memory, and every later save of `subsync.json` (any fit or shift, on any film) failed until a restart.
   Fixed in `mp_subsync.align` (plain `bool`/`float`), with `mp_core.json_default` as a safety net in
   `Handler.send_json` and `Store._save` (numpy scalars become plain values).

- [x] Offline on jmpc (2026-11-25, the agent's own python and numpy 1.26): the old expression reproduces the exact
      error; the fixed `align()` on that film's subtitles returns `weak: True` as a plain bool and `json.dumps` works.
- [x] Via the API on jmpc after deploy (2026-11-25, agent restarted): `subs/list` of that film is 200 with
      `fit.manual`; a download of English subtitles (first hash-matched result) finished with fit `weak`, verdict
      "good", the previous default (shifted by hand) moved to `Subtitles/` keeping its `manual`; `subsync.json` saved.
- [ ] Phone: the subtitle sheet of a film shifted by hand shows the shift; a download that fits "weak" shows "fits".
- [idea] Owner, 2026-11-25: the first download happened during playback; while it was still downloading/fitting he
      tried to adjust the subtitles in the player and it felt bad; afterwards the subtitles were missing or late.
      The journal shows no race: the download (and its failed fit) ended at 18:17, the shift by hand came at 18:21,
      and the "missing" subtitles were bug 1 above. See "Subtitles UX review" below.

## Subtitles UX review (from the code, 2026-11-25; what the owner approved is built: next section)

Read: `SubsSheet.kt`, the subtitle parts of `PlayerActivity.kt`, `server/web/index.html` (search, download, player),
`mp_api_subs.py`. For the owner to decide; a hands-on pass on the phone comes later.

Errors and silent failures

- [idea] A failed subtitle list is invisible. The player starts with `subsList(...).getOrDefault(emptyList())`, the
  sheet falls back to the previous list or the downloaded copy's files, `applySubFile` keeps the old list, and the web
  player has `catch {}`. With the KeyError above the film simply played without subtitles, with no message. Proposal:
  when the server answers but the list fails, say so ("Couldn't read the subtitle list: …") with "Try again".
- [idea] Errors are shown raw ("Object of type bool_ is not JSON serializable") in one red line at the top of the
  sheet, which can be scrolled out of view on the language page, with no way to retry except tapping the row again.
  Proposal: the error on the row that failed, in plain words ("The server couldn't save the subtitles", details
  folded), with "Try again"; the agent should return a translated message for unexpected errors and log the traceback
  (today the journal had only `error: KeyError('verdict')` and nothing at all for the download job).
- [idea] A fit that could not run (verdict `error`, e.g. "no speech found" because the film was still downloading)
  shows the same toast as a normal "Subtitles on" and no badge. Proposal: "Not fitted: the film isn't complete yet"
  (or the agent's reason), and the agent fits them again once the torrent finishes (today nobody does; only "Fit to
  the film" by hand or the task).

A download while the film plays

- [idea] The download lives in the sheet: closing the sheet (Close, tapping outside, the back arrow on the language
  page, or "Sync while watching", which closes the sheet) cancels the app's polling. The agent finishes the download
  and makes it the default, but the player is never told: the film keeps the old (or no) subtitles and there is no
  message. Proposal: the download continues in the player's scope after the sheet closes, with a small status chip
  over the picture ("Downloading English subtitles… / Fitting… / On, fits"), and the new file is switched on when done.
- [idea] While a download runs, the language page still offers "Sync while watching" and the other versions for the
  file that is about to stop being the default (the new one becomes the default and the old one moves to
  `Subtitles/`, keeping its shift by hand). A shift made then applies to the old file, which then disappears from
  use: that is what "felt bad". Proposal: while downloading, the sync row and the other rows are disabled with
  "Wait for the download" (or the sync row says which file it will move), and after the download the sheet stays on
  the language page showing the new file as "in use" so the next step (sync) is on the right file.
- [idea] Only the "Download: <language>" path (`fetchSubtitles`) shows a cancel; a download picked from the search
  results has no "Cancel", only a spinner on the row and the phase text. Proposal: a cancel on the row, and the other
  results greyed but visible (today they are just not clickable, with no hint why).
- [idea] A manual shift is not carried to a newly downloaded version of the same film (`carryShift` is used only for
  automatic downloads: the next episode and the remembered language, same provider). After "these were 1 s late,
  try another version" the new one starts from the fit. Fine when it fits; when its fit is `error` or `unsure`, offer "Apply the same shift (-1.0 s)?".

State shown

- [idea] Live sync bar: the shift is saved after a pause in tapping (`SYNC_IDLE_MS`); a failed save shows a generic
  "couldn't" toast and the bar is gone, so the change is lost. Proposal: keep the bar with "Not saved, Try again".
- [idea] The sheet shows the fit only as "fits" / "doesn't fit"; `unsure`, `weak` (fitted by frame rate only) and
  `error` show nothing, and a file only shifted by hand shows its shift only on the "Sync while watching" row.
  Proposal: one status line per version: "fits", "fits (timing not checked exactly)", "not checked yet",
  "shifted by hand -1.0 s".
- [idea] Web page: the download is synchronous (no `async`, no `fit: "wait"`), so the toast says "Downloaded" before
  the fit runs, and the fit result never appears; there are no sync controls and no fit badges in the browser.
  Proposal: use the download job (phases), show the fit result, and at least the ±0.5/±0.1 buttons from the sheet.


## Subtitles UX, built (agent 1.18.8 + the next app; compiled, not tried on the phone)

The owner's order of 2026-11-25 (items 1–7 of the review above). Verified so far: py_compile, an offline test of the
agent's refit queue and job errors, the Kotlin compile check, the string checks, the web page's JS syntax.

- [ ] 1. A failed subtitle list says so. Player: a line over the picture "Couldn't read the subtitle list: …" with
      "Retry" (the film plays on; Retry reloads the video at the same position with the files as tracks). Sheet: the
      same message at the top with Retry and "Details". Web page: under the dialog's title and in the browser player,
      with "Try again". Agent: an unexpected error is a 500 with `error` in plain words + `detail`, and the traceback
      in the journal; subtitle jobs the same (a failed download job used to log nothing).
- [ ] 2. Errors on the row that failed: a search result's download (under the result), "Download: <language>" (under
      that row; another language under "Download in another language…"), a shift (under the timing row). Plain words,
      "Details" folds the agent's `detail`, "Retry" repeats it. "Download: <language>" whose every try failed says
      why instead of "No subtitles found".
- [x] 3. (2026-11-25, Pixel, v1.261125.1933: "Checking 1 of 3…", "Downloading…", "Downloaded. Fitting…", then "none of
      the subtitles found fit well… on anyway", in Polish) In the player, a download started in the sheet goes on after the sheet closes: a line over the picture
      with a spinner ("Searching…", "Checking 1 of 3…", "Downloading…", "Fitting…"), then for 6 s how it ended
      ("Subtitles on, they fit your copy", "… they line up with your copy", "… may not fit", "Not fitted yet: the
      film is still downloading…", or "Couldn't download these subtitles: …"); the new file is switched on and its
      language remembered. Opening the sheet again meanwhile shows the progress; when it ends there, the new file is
      "in use". The title card and the Remote keep the old behaviour (the download belongs to the sheet there).
- [ ] 4. While a download runs for a language: its versions, "Timing while watching" (or the ± buttons) and its row on
      the first page are disabled with "Wait for the download to finish"; the other search results can't be tapped.
- [ ] 5. A download (or "Fit") while the film is still downloading: nothing is fitted, the result says "Not fitted
      yet: the film is still downloading…", the version reads "fitted once the film finishes downloading", and within
      ~20 s of the torrent finishing the agent fits it (journal: "subtitles fitted now that the film is complete").
      Survives an agent restart (`subs-refit.json`).
- [ ] 6. Badges per version: "fit your copy", "line up with your copy" (weak), "may not fit your copy" (unsure),
      "don't fit your copy", "fitted once the film finishes downloading", plus "shifted by hand -1.0 s".
- [ ] 7. Web page: a download shows "Downloading…" / "Fitting…" on the result, then "Downloaded: <name> · <fit in
      words>", or the error with "Details" and "Try again".
- [ ] 8. Bug found 2026-11-26 (Pixel, v1.261125.1933): in the player, "Search online" stayed on "Connecting to the
      computer…" for ever. The agent got the search (POST /api/subs/search) but the sheet's progress loop waited for the
      main window to be visible (`MainVisible`, false while the player is in front), so it never asked for the job.
      Now it waits for its own screen (player or main window). Check: in the player, open subtitles, search online:
      progress and results appear; the same from the main window.
- [idea] Not built, for the owner to decide: "Cancel" for a download (a row's cancel and stopping the agent's job);
  carrying a shift by hand to a newly downloaded version of the same film (only "clearly right" when it is the same
  subtitles, which the agent already reuses; for other subtitles the old shift may be wrong, so nothing is carried:
  the old version keeps its shift in `Subtitles/` and its badge shows it); the live sync bar keeping "Not saved,
  Retry" after a failed save.

## Suggestions you don't have (G4, agent 1.18.0)

- [x] Discover: under "Because you watched" a row "Because you watched <title>: not on your disks" (PL "Podobne do
      „…”: nie ma na dyskach") with greyed covers and a reason (director, actor, genres). (2026-11-22, Pixel, v1.261122.1956)
- [ ] A tap opens the missing title's page (D2: search engines, paste a magnet, want to watch); a wishlisted title
      says "want".
- [ ] A title that reaches the disks leaves the row (at the latest after the phone's 10-minute cache).
- [ ] An older agent (no `suggest_missing`): no row, Discover as before.
- [ ] More variety (the owner's wish, 2026-11-23: the row stayed on one title): Discover's "Similar to …" and
      "After watching …" rows each take a title at random from the last ten watched (two different ones), plus
      "Because you have …" after a well rated title on the disks not watched yet; the labels no longer say "not on
      your disks" (the covers do). The picks hold for the day and change after another title is watched (back on
      Discover, or the app reopened). The title page ends with "Similar, not in your collection" (loads when scrolled
      to; the first answer 10-30 s). Also fixed: the missing-titles row kept the previous title's list when its title
      changed (produceState kept its state across keys).
- [ ] A film played from Downloads (a torrent's file) counts as watched in the library: "Continue watching" and the
      suggestion rows show its library title (matched by file name, also after the torrent was moved or removed).
      Found 2026-11-23: such a position was kept under the torrent's key only, so Discover kept an older title.
      Positions saved before this version have no file name and stay invisible.
- [ ] Discover rows (kids, genres, "Because you watched") show one card per title: a series kept per season on one
      disk and whole on another showed up three or four times side by side (seen on the Pixel, v1.261122.1956). The
      copy with most episodes is shown; "Because you watched" also leaves out other copies of the watched title.
- [ ] First load after an agent restart: the row arrives after a few seconds (Wikidata), the screen doesn't stutter.
- [ ] "All servers": the row asks the server that has the watched title.

## Player: episodes, recognised title, audio of the Chromecast version (G2, G3, G5; agent 1.18.0)

- [ ] G2: an "Episodes" button in a series episode's player (from the catalog and from a folder), none for a film with
      extras; the sheet shows the current season, chips for the others, marks now / watched / up to N % / on the
      phone; a tap switches episode, ⏭ and the countdown still work; offline for a downloaded season.
- [ ] G2: the player's title is "Series · S2 E3" / "Film (1968)" from the agent (also for a file outside the phone's
      catalog copy).
- [ ] G3: the Chromecast card says "Audio in it: <language> · <kind>"; after changing the choice (e.g. lector →
      original) "Prepare again: …" appears and the new version carries the right track; a version made before the
      agent update is recognised too.
- [ ] G5: "Play on <server>" from a folder switches to the Remote, also when the server had to be woken.
- [ ] The new texts in both languages.

## Offline sync points (G1, agent 1.18.0)

- [ ] Watch offline on a downloaded copy (a position, and one to the end), reconnect: the agent's `watch.json` has
      them, Continue watching unchanged, Free up counts the title as watched.
- [ ] Clear the app's data and pair again: positions and watched marks come back (a new pairing is a new device id:
      may not; see the open question in `docs/architecture.md`).
- [ ] Pick another subtitle version offline on a downloaded copy: shown at once with the "server gets this choice"
      toast; after reconnecting it is the server's default too.
- [ ] An offline choice older than a change made on the server: the server's subtitles stay and the copy gets them.
- [ ] Shift subtitles on the server: the downloaded copy gets the shifted file at the next sync (≤ 10 min).
- [ ] After the update every downloaded copy re-downloads its subtitles once (old copies have no `mtime`): harmless.
- [ ] Catalog delta on the Pixel: a new title appears and a removed one goes after the next refresh without a full
      download; a folder tag change brings a full catalog.
- [ ] Back home with the app on screen: one sync; no requests while the app is in the background.
- [ ] The web page and older apps against agent 1.18 still get the full catalog with `since=<version>`.

## Re-encode, advanced (stage 6: F3 + B6, agent 1.18.1)

A1/E2:
- [x] an "ADVANCED" label and a "Re-encode" row at the end of Renovation (only with agent 1.18.1+) (2026-11-22, Pixel, v1.261122.1956)
- [ ] the counter "N sets" greyed out; the row opens B6; an E2 scope carries into B6

B6:
- [ ] description: several changes in one pass, the file is encoded once
- [x] "START FROM": Series archive / For Chromecast / Old VHS / Empty (2026-11-22, Pixel, v1.261122.1956); saved sets not checked yet
- [ ] files row: folder picker on the whole computer, the scope bar in a scope
- [ ] six steps: switch + name + one-line summary, only one expanded at a time
- [ ] resolution 720/480/360; codec H.265 saving / H.265 quality / H.264 compatible
- [ ] old picture: deinterlace / denoise / crop black bars; sound: fix damaged / even loudness / leave
- [ ] Chromecast: add AAC stereo / subtitles to .srt; container MKV / MP4
- [x] a step that is off reads "skipped" (2026-11-22, Pixel, v1.261122.1956)
- [ ] summary card: "N files · 1 pass instead of K · about T · −X GB", the order note, warnings
- [ ] "Save set" (a name; the set shows on the other phone) and "Preview" with the steps per file
- [x] Preview on the whole computer with Series archive: 1334 items, size before/after, CPU vs NVENC with hours,
      each file with its steps ("480p · H.265 saving", source codec and height, size → estimate) (2026-11-22, Pixel, v1.261122.1956)
- [ ] While the preview measures (up to 4 min) the line under the bar says "measuring sound and picture (up to N min
      more): <file>" instead of the last file of the walk with the bar stuck at 99 % (agent 1.18.2; seen on the
      Pixel 2026-11-22 with agent 1.18.2 hand-deployed, app v1.261122.1956)
- [x] Open question settled by the owner (2026-11-23): films may be archived too ("480p is enough for what I once
      watched"). The set is now "Archive" with "Only what was watched" on (agent 1.18.5).
- [ ] B6 "Files": "Only what was watched" (last played more than three days ago); on with "Archive", off with the other sets;
      ticking it changes the summary count; a film watched yesterday or never isn't in the preview. EN and PL.
- [ ] "For Chromecast" now makes MKV; the result plays on the Chromecast with Google TV and keeps PGS subtitles.
- [ ] a real task to the end on jmpc (GPU), including an interlaced VHS-like file and one with subtitles; survives an
      agent restart; EN and PL
- Decisions taken without the owner (2026-11-22): deleting a saved set is API-only for now (no place on the board);
  "For Chromecast" used MP4 (MKV since agent 1.18.5); the encoder is picked in the preview, as in the other tasks.

## Library cards: the title as interface text on posters (next app)

The owner (2026-11-24): don't rely on the title printed on the poster (small, decorative, blurred, foreign); show the
name as clear interface text on a dark bar at the bottom of the card.
- [ ] library home rows (`CoverCard`) and the three-column grid (results, genre, person): a card with a real poster
      shows the title in white bold on a dark bar at the bottom, up to 3 lines; long titles end with "…"
- [ ] cards without a picture and with a film frame look as before; search rows, the title page and "Similar" keep a
      clean poster (the title is printed next to or under it there)
- [ ] the "PL audio" badge and the "on this phone" mark stay visible; light and dark theme

## Internet Archive: built-in public-domain search (agent 1.19.0 + the next app)

A built-in search site for legal films (public domain / Creative Commons) from archive.org, added by the item's
`.torrent` (web seeds), never a magnet. Checked so far: the search endpoint on a throwaway agent against the real
archive.org (Night of the Living Dead, Sintel, Big Buck Bunny), the file choice on four real `.torrent` files, the
add sequence against a stub Deluge, Kotlin compile, string checks and the web page's JS syntax. Not yet on a real
Deluge, phone or browser:
- [ ] missing film's page (D2, e.g. Night of the Living Dead 1968 from the library search): "Internet Archive (public
      domain)" is the first button in SEARCH (also with no own search sites; not on a series); it opens the search
      with "Title Year" filled in and results already listed
- [ ] results: title (year), licence line (public domain / Creative Commons BY 3.0 / "no licence stated" in red with
      "Also without a stated licence" on), "whole item X GB · N downloads", "Item page" opens archive.org; "Nothing
      found" with a Polish-only title; Polish UI texts and plural forms of downloads
- [ ] Settings › Search sites: the built-in card (not editable) with "Search" opening an empty search
- [ ] "Download": the usual magnet card on the server that searched (wake, recognised folder, "Change", after
      download); the search stays open behind it
- [ ] on Deluge: the torrent appears as "Night of the Living Dead (1968)", **only one file wanted** from the start
      (Deluge file list: the ISO, OGV, MP3 and thumbnails "Skip"; the network graph doesn't show the ISO downloading
      in the first seconds), download speed from the archive.org web seeds without any peers
- [ ] on disk: `<library folder>/Night of the Living Dead (1968)/Night of the Living Dead (1968).mp4`, no leftover
      `night_of_the_living_dead_dvd/` folder (only Deluge's hidden `.parts` file may appear); the catalog shows the
      film with the year 1968, and the subtitle search finds the 1968 film, not the 1990 / 2006 ones
- [ ] watching while downloading works (sequential download with a web seed)
- [x] real Deluge, through the API (2026-11-26, jmpc, agent 1.19.0 hand-deployed; not yet from the app or the web
      page): Duck and Cover (1951), 23 files in the item, only the chosen one downloaded, renamed to
      `Duck and Cover (1951)/Duck and Cover (1951).mp4`, from the archive.org web seeds in about a minute. Not sure
      of the folder, so it went to the client's default folder. Leftovers: an old-name folder with two empty
      (0-byte) files, and a few small files sharing pieces with the film (a few hundred KB)
- [x] the codec decides, not the extension (2026-11-26, jmpc): the first pick of Duck and Cover was the largest
      `.mp4`, an old MPEG-4 Part 2 upload ("HiRes MPEG4", 320x240) that phones play badly; with the item's file
      formats it now takes the h.264 copy (368x480, Main, 53 MB). Night of the Living Dead and Sintel pick as before
- [ ] "Change" moves it and the folder memory learns the choice; the job history shows the name; adding the same
      item twice returns the same torrent without an error
- [ ] web page, Downloading tab: "Internet Archive (public domain)" dialog, search, "Download" with the sequential
      and after-download choices of the magnet form; the download lands in the recognised folder when the agent is
      sure; "Download again" in the history for an archive item; English and Polish
- [ ] errors in plain words: no torrent client (409), item without a video file (422), archive.org down (502)
- [ ] Transmission backend (if any server uses it): `files-unwanted` and the rename work

## Noted wishes
- [todo] Internet Archive: the owner picks the file (2026-11-26). An item often has no good copy: Duck and Cover's
  h.264 copies are 368x480 without the aspect ratio (shown narrow, should be 4:3), the 4:3 ones are old MPEG-4 Part 2
  or Ogg. So "Download" lists the item's video files (format, codec class, resolution, size; the automatic choice
  preselected; a note on a portrait picture or an old codec) and the owner chooses; a wrong aspect is then fixed by a
  re-encode, which needs a "display as 4:3 / 16:9" option (and re-encoding a seeding torrent's file must leave the
  original for the client).
- [todo] Subtitle timing with cuts, from Whisper (the owner, 2026-11-25/26; tried by hand on Night of the Living Dead
  1968, archive.org's DVD copy). Every subtitle found for it was off by steps, not by a steady drift: the copy is cut
  differently (steps of 1-9 s at about 20, 24, 39, 59, 77 min). The speech-detection fit (`mp_subsync`) can't see
  that, and on an old mono film it confuses music and radio with dialogue. What worked, checked against five lines
  the owner timed by ear (+-1 s): all within 1.4 s except one quiet line (-2.4 s), "ideal, good enough":
  - faster-whisper `small.en` word timestamps; the first words of each subtitle line matched to the heard words
    (within -60..+10 s); a piecewise-constant offset over the matched lines (Viterbi, a penalty per change), a change
    placed in the largest gap between two matched lines, overlaps trimmed (no two lines on screen at once);
  - pitfalls met: faster-whisper's `vad_filter` put words 5-6 s early where speech was sparse (use
    `vad_filter=False`); decoding the whole audio track at once lost ~15 s spread over the film (AAC frames that
    decode to nothing), so times drifted: decode with `-af aresample=async=1:first_pts=0` (or cut short pieces with
    `-ss`); PyAV in faster-whisper 1.2.1 fails (`metadata_errors`): pass a numpy array;
  - speed: laptop CPU (4 threads, int8) ~6x real time; jmpc GTX 970 (float32) 12-18x: the whole film in 7 min, a
    dozen one-minute pieces in under a minute. CTranslate2 4.x no longer runs on Maxwell ("invalid device ordinal");
    3.24 (CUDA 11, faster-whisper 0.10) does;
  - plan: run it when the normal fit is unsure and the film is identified (title + year), on pieces where the
    subtitles have speech; the owner's own sync points (+-1 s) decide where a cut goes when Whisper heard nothing.
  - second film, His Girl Friday 1940 (archive.org copy, two Criterion DVD subtitles, both "very off" for the owner;
    the normal fit found one offset of -18 s and marked it unsure): a 6.4 s cut at 9:37, smaller steps of 0.3-1.1 s
    later, from -12.3 s at the start to -23.3 s at the end; median error 0.14 s over 1006 matched lines, 26 over
    1 s. Overlapping dialogue is common: DVD subtitles split a two-speaker line into two cues with the same times, so
    (a) only the first cue of such a pair is a timing sample (the second speaker talks later: using it pulled every
    offset ~0.4 s late) and (b) overlaps present in the original must stay (trimming them cut the first speaker to
    0.3 s). Whole film on jmpc's GPU: 92 min in 8.3 min. Not yet checked by ear.
  - model and sampling (the owner's idea): film time = sub time x (1 + tempo) + offset per stretch, one tempo for the
    film; listen only to 1-min pieces where the subtitles are dense (10 spread over the film), fit, then bisect
    between two pieces in different stretches (densest unheard piece in the middle third) until the two lines around
    the cut are < 2 min apart. Results against the full-film Whisper, jmpc GPU:
    His Girl Friday: 13 of 92 min heard, 82 s in all (vs 8.3 min), tempo -0.10%, one cut of -6.3 s at 9:56, median
    error 0.29 s (full film 0.14), 90% of lines within 0.85 s;
    Night of the Living Dead: 26 of 95 min heard, 155 s, 4 cuts, median 0.40 s (full 0.29), 90% within 1.4 s, but
    one cut falls in a 4.6-min stretch without dialogue and lands 8 s off (the full film needed the owner's ear there
    too). Next: place such a cut by the audio itself (silence, `silencedetect`) or a scene change (`scdet`), since
    an edit is usually made at a scene change.
  - the owner's rules for the built-in version: (a) a lone line Whisper heard clearly in a long stretch without
    dialogue keeps its own time when it fits neither neighbour (cuts on both sides), else the cut goes on the side
    that keeps it where it was heard; (b) timing target: a line may show up to ~0.5 s before its speech (at the start
    of the speech is ideal), never noticeably after it and never long before; (c) a line may stay on screen longer
    (up to the next one) when lines don't overlap: a setting (config / MCP agent), not fixed; (d) machines without a
    GPU keep working: the first analysis is the speech-detection fit, Whisper only where it is available and fast
    enough (measure CPU-only too).
- [ ] Owner's wish (2026-11-26): picking a subtitle language in the player shows the files already there (they're
  usually downloaded with the film now) and searches for new ones only after a "Search for more" button, not at once.
- [x] By ear (owner, 2026-11-26): The Little Shop of Horrors (English, rhythm) and Bicycle Thieves (Polish fitted to
  the Italian baseline) "both perfect".
- [ ] Agent 1.21.1: archive.org downloads no longer stop at 99.9% (`mp_iafill`): the edge pieces shared with a
  regenerated file are fetched over HTTP and the file checked by archive.org's MD5. Verified on jmpc (2026-11-26,
  through the agent's log and API): Bicycle Thieves (1 MB), Plan 9 from Outer Space (110 KB, its start was zeros and
  the file didn't open) and The Little Shop of Horrors (380 KB, the owner's phone couldn't open it) all matched their
  MD5, were reported complete and paused, and the automatic subtitles then ran by themselves for all three. Film
  language from Wikidata / archive.org: Bicycle Thieves (no language tag) got an Italian baseline (rhythm, good) and
  the Polish file fitted to it. The subtitle queue now resumes after an agent restart. To check: the films open on
  the phone (The Little Shop of Horrors), by ear: its English subtitles and the Italian / Polish ones of Bicycle
  Thieves.
- [ ] Agent 1.21.0: subtitles timed by the rhythm of speech, translations fitted to a baseline, automatic subtitles
  after a download (`mp_rhythm`, `mp_subflow`, `/api/subs/auto`, `/api/config/subtitles`, MCP subtitle settings). The
  owner's rules (2026-11-26): Silero on the whole film when there's no Whisper, for any language; what counts is the
  line appearing at the start of its speech, not its length; subtitles written as sound descriptions are left out of
  timing with an allowance for wrong marks; a translation follows the film's own subtitles (their cut map), not cut
  again when it already fits this copy, only tempo and a shift; the baseline from an attached file, the film's own
  track or a service without a daily limit; the target = the language last picked on purpose; every choice set via
  config/MCP. Verified on jmpc (2026-11-26, offline scripts against full-film Whisper, and once through the API):
  rhythm alone: His Girl Friday 0.14 s median (1.9% over 1 s), Detour 0.21 s (1.7%), Charade 0.20-0.24 s with no line
  over 1 s (also a file for another frame rate), Night of the Living Dead 0.49 s, rightly "unsure". Polish files
  within 0.4 s of the heard English lines: 69-95% (old fit 42-88%), Night 37% (24%); two wrong-film Polish files (a
  remake, a same-named film) left untouched as "unsure". `POST /api/subs/auto` on Charade: 9 s, English by rhythm
  (100% within 0.4 s), Polish by the "copy" hypothesis (74% within 0.4 s, 98% within 1 s). To check: the flow by
  itself after a download finishes (done in 1.21.1, see above); Carnival of Souls checked offline (2026-11-26:
  three English files incl. one for the hard of hearing, all "good", 0.18-0.24 s median against heard words, no line
  over 1 s against the Whisper-built truth); the app reloading a newer subtitle file during
  playback; a Polish-language film; Windows.
- [ ] Owner's wish (2026-11-26): a setting that hides sound descriptions (`[door slams]`, `(laughs)`) from the shown
  subtitles, off by default, in config/MCP.
- [ ] Agent 1.20.0: subtitles re-timed by Whisper cut by cut (`mp_retime`, `POST /api/subs/retime`, automatic after an
  "unsure" fit). Verified on jmpc through the API (2026-11-26), not in the app: a fresh film, Detour 1945 from
  archive.org (68 min), with Criterion Blu-ray subtitles downloaded through `/api/subs/download`: the fit said
  "unsure" (-75 s, pieces from -75 to -84 s) and started the re-timing by itself; the first run recognised 0 lines (the
  lines were looked for ±60 s from their own time, the file was 75 s off: now around the fit's offset), the retry: 6
  cuts, tempo -0.05%, 19.5 min heard in 94-98 s on the GTX 970, CPU only (int8, ~4 cores) 22 min heard in 310 s.
  Against full-film Whisper (653 lines): median 0.29 s (0.15 s of it the deliberate lead), 90% within 0.8 s; the
  full-film re-timing 0.14 s. Same code on the earlier films, with the "third point" check (a heard piece off the line
  → listen around it): Night of the Living Dead median 0.38 s, all five of the owner's lines within 1.5 s ("Don't look
  at it" -0.9 s, the full-film version had -2.4 s), 191 s; His Girl Friday 0.29 s, 111 s. To check: by ear on the
  phone (Detour), a download from the phone that triggers it, the subtitle list/badges after a re-timing (`by:
  whisper`), what the app shows while it runs; a Whisper venv install on another computer (no installer yet).
- [ ] Agent 1.19.1: subtitle fit with Silero VAD and a cut check (the owner: "use silence at the first analysis",
  keep machines without a GPU working). Verified on jmpc through `POST /api/subs/fit` (2026-11-26, API only): His Girl
  Friday re-timed subtitles "good" (z 8.2, offset -0.05), the original DVD subtitles "unsure" with `cuts` (the first
  10 minutes at -12 s, the rest at -18 s) instead of the "good" -18 s for all that Silero alone gave; 18 s for the
  first fit of a 92-min film on the CPU (sound decode + Silero, model fetched), 1.6 s from the cache. Measured before:
  webrtcvad took a film score for speech; decoding without `aresample=async=1` lost ~15 s over Night of the Living
  Dead, so every fit of that copy drifted (fixed for both detectors). To check: a fresh download on the phone shows
  the fit badge; a Windows agent gets `onnxruntime` (or falls back to webrtcvad without errors).
- [todo] Subtitles when none exist anywhere (2026-11-26, Duck and Cover 1951 from archive.org): OpenSubtitles had
  150 results for the words "duck" and "cover" (The Mighty Ducks, Duck Amuck, Cover-Up…), none for this film, so
  "no subtitles" was right. Two sources then: Whisper transcribing the film itself (English films; a 9-min film is
  ~30 s on jmpc's GPU), and for archive.org items their own speech-recognition subtitles in the item
  (`<id>.asr.srt`), which the archive.org add could fetch along with the film.
- [todo] Identify the film from the library catalog before searching subtitles (the owner, 2026-11-25): the search
  mixed several films of the same title (1968, 1990, 2006 3D, a RiffTrax edition) because the year was lost (the
  folder name came from the .torrent). Keep title/year from the add, filter by IMDb id, ask "which film?" when
  unsure ("Recognised as … · Change"), rank commentary/3D/other editions last, and when nothing fits offer another
  search instead of switching on a bad file.
- [todo] Encoder speed as a choice with known effects (the owner, 2026-11-23): a re-encode/archive job lets one pick
  faster/slower (x265 preset, SVT-AV1 preset, VVenC preset) and shows, before starting, what each costs: size vs the
  source, hours of encoding, and quality (VMAF) per source kind, from a measured table shipped with the agent (codec
  benchmark on the server, 2026-11-23: 6 source kinds × NVENC/x265/SVT-AV1/VVenC presets and qualities) plus a short
  trial encode of a few samples of the user's own files. Reason: an archive made in a fast preset that turns out bad
  leaves nothing to go back to, so the quality has to be known (and checked on the result) before the original goes.
  Approved safeguard: every encoded file is checked with VMAF at a few places against its original before the
  original goes to the trash; below the threshold the original stays and the item ends as "skipped: quality". The
  trash (`trash_days`) stays the second line of defence, not the first.
- [todo] Encode on the graphics card to keep the processor free (the owner, 2026-11-23): the point is not speed but
  smooth streaming and playback while a job runs. Agent 1.18.6: NVIDIA encodes (shrink, re-encode, Chromecast version)
  also decode on the card (`-hwaccel cuda`). Checked on the server with the agent's own commands on 30 s clips
  (2026-11-23, GTX 970): H.264 1080p 6.4 → 2.4 s of processor per 30 s, all frames kept; XviD on the card too; 10-bit
  HEVC falls back to the processor (the GTX 970 can't decode it) and still finishes. Not yet seen in a real task.
  Next: NVENC "best" settings for archives (p7, multipass, lookahead; measured in the night benchmark), threads/time
  windows for processor encoders.
- [todo] Load balancing as a job (the user, 2026-11-20): spreading series and films over the disks with the same
  tags so they fill evenly, as a task on the disks screen (a preview like "Free up", optional, maybe suggested when a
  disk passes ~90%). First run done by hand 2026-11-20: 14 folders (~320 GiB) moved from the full disk to the other
  one with "Free up" + an explicit pick (a folder tagged also `anime` and a series being watched were left out).
  Needs: keeping extra tags (a folder with `filmy`+`anime` must not land in a folder that has only `filmy`), and the
  trash: shrink originals stay in the source disk's trash for `trash_days`, so a shrunk disk does not get its room back.
- [idea] Library and servers (stage 2 of docs/plan-multi-server.md, "a shared library from all servers: later"):
  the top switcher gets "All" next to each server; with "All" Library and Downloads merge every reachable server
  (titles marked with their server, e.g. "jmpc · Series (TOSHIBA)"), with one server picked they filter to it.
  "Wake all" for moving files between computers and browsing every library at once.
  Screen-only servers stay out of that (the switcher already shows them greyed out as "screen only").

- [~] The catalog's "New on your disks" row: sometimes something else, e.g. a carousel "Because you watched <title>"
  (from the disks: done, see "Catalog and Remote") or suggestions that are not on the disks (to download by magnet).
- [~] (step 1 done: the catalog on disk, see "Library screens") A library usable without the computer: the catalog cached on the phone (maybe without covers), with what is
  downloaded marked and playable. Moments when the phone reaches a server again are sync points: refresh the cached
  catalog, send positions and watched marks made offline, refresh downloaded copies' subtitles. Incremental, like
  commit hashes: the agent keeps a change counter (or hash) per library and per item, the phone sends the last one it
  saw and gets only what changed since (the whole catalog only the first time or when it is small).
- [todo] Two season 2 extras of one re-encoded series failed: their AVI originals are truncated (the header promises
  more than is there). The check was fixed in agent 1.10.1; the two files only need a new shrink run on that folder.
  2026-11-20: the rerun failed with "no space left" (the disk was full); the series is moved to the other disk by the
  load balancing run, the rerun goes after it.
- [later] The folder browser plays a single file (no next-episode card, no queue); the catalog's series screen is the
  way to watch a series. An episode picker inside the player will cover the rest.
- [~] Audio choice on screens: mpv done (agent 1.13.3, see "Catalog and Remote"); Chromecast still plays the file's
  default track (its receiver switches tracks only in HLS/DASH, a direct file has one audio for it).
  Agreed sheet, for reference: a button next to CC, tracks grouped by language with the file's title
  (lector, dubbing, audio description, commentary), channels (5.1, stereo) and codec; the video pauses while open.
  The choice is remembered as a kind ("original", "Polish lector", "dubbing"), not a track number: per folder in a
  series, otherwise a general preference ("original with subtitles", "lector when there is one"). The kind comes from
  the track title; "original" is the film's language from recognition (TMDB); a track without a title counts only
  by language. mpv and Chromecast get the same choice; DLNA cannot switch tracks (the file's default plays).
- [idea] The folder browser as the place for jobs on its files: shrink, missing subtitles (both there), and more as
  they come. Zip archiving gives videos almost nothing (already compressed), so no.

## UX notes (observed while testing)

Written down as seen, also when unsure whether it is a problem at all. Reviewed with the owner on 2026-11-20
(the approved fixes are numbered in `plan-12-points.md`); each note says where it went. From 2026-11-22 new findings
are fixed as they come and reported, not only noted.

- [replaced: stage 1 job screens] 2026-11-20, "Old formats" preview (emulator, jmpc): ~2 minutes of "Preparing the preview…" with the bar standing at
  the start (the damaged-sound check spends up to 90 s); nothing says what it is doing or how long it takes.
- [replaced: stage 1 job screens (B1-B4)] 2026-11-20, "Old formats" preview: every one of 494 items is ticked, and the button reads "Shrink" (the screen is
  "Update old formats"); one tap starts ~40 h of CPU work. Damaged-sound items sit among the picture conversions
  with nothing grouping them (the job grouping prototype `docs/ux-jobs.html` covers this).
- [replaced: A1 task list] 2026-11-20, "Disks and tidy-up": five tool buttons ("Tidy up", "Duplicates", "Old formats", "Subtitles", "Get
  subtitles"); "Subtitles" next to "Get subtitles" doesn't say the first one fits subtitles already on the disk.
- [fixed 2026-11-22: the disk card offers it under 15 % free] 2026-11-20, "Disks and tidy-up": "Free up space" is offered on a disk with 718 GB of 1.8 TB free.

- [fixed: agent 1.15.0] 2026-11-20, catalog: a library folder's subfolder that holds many series (e.g. `ANIME/<series>/…`) is one unrecognised
  item named after that subfolder; the series inside don't get their own titles in Discover/Browse. (Addressed by agent
  1.15.0; how to show the group, a category or a tag, is in the prototype `docs/ux-jobs.html`.)
- [open: the file-hash check of agent 1.17.4+ may correct it; recheck] 2026-11-20, catalog: an anime series without a year in its names was matched to a 2022 title of the same name (the
  original is from the 1990s).
- [open bug] 2026-11-20, phone player: an MKV with an MPEG-4 ASP picture and two 5.1 AAC tracks played with a black picture and
  "Dźwięk: Brak" (no audio track offered), with no audio-error dialog. An old format ("Stare formaty" fixes the
  picture); why no audio track is offered is not known yet.
- [fixed] 2026-11-20, covers in "Miniatury" mode: after a start without the server, the blurred thumbnails stay when the server
  comes back; sharp covers return only after the app is restarted. (Addressed in the next app.)
- [fixed: a touch holds the turning for 15 s] 2026-11-20, Discover hero carousel: it can turn to the next slide just as "Odtwórz" is tapped, so another title
  starts than the one looked at (seen offline: tapped on one film, the dialog named another).
- [fixed: dark label background and fade] 2026-11-20, Discover hero: the small pink label over a bright cover is hard to read.
- [fixed: new description] 2026-11-20, server picker: "Wszystkie serwery" is described as "pobieranie po jednym serwerze", which no longer
  matches Downloads with a single torrent server (no chips).
- [plan G2] 2026-11-20, phone player: the title is still the release's file name ("… (1080p BluRay x265 …).mkv"), while the
  Remote now shows the recognised name.
- [plan G5] 2026-11-20, playing on jmpc from a folder: the app stays on the folder; the Remote has to be opened by hand.

- [approved fix 17] 2026-11-20, Remote, Audio sheet: a track's own title can contradict the detail line under it ("English: 5.1ch
  (AC3 640Kbps)" over "5.1 · AAC": the file really has AAC; the title is the release's). Which one to trust is unclear.
- [approved fix 11] 2026-11-20, Remote on jmpc: the title shown is the raw file name with release tags
  ("[group].Title.02.Episode [1080p.BRrip.x264.Dual-Audio].mkv"), not the recognised series/episode as in the catalog.
- [approved fix 9] 2026-11-20, tasks: item errors are cut from the front ("ffmpeg failed: e left on device Error writing trailer of
  /media/…/.Film.mp-shrink.part.mkv: No space left on device"): starts mid-word, shows a hidden temp path, and 457
  identical errors are listed one per file instead of one reason.
- [approved fix 3] 2026-11-20, shrink / "Update old formats": the preview promises space saved, but originals go to the trash on the
  same disk for 14 days, so the disk fills up during a big run; nothing on the preview or the task says so.
- [approved fix 19] 2026-11-20, Remote: after leaving the Remote (app in the background, nothing of ours playing) its session service
  stayed in the foreground for 10+ minutes (another app's paused cast session on the TV was selected).
- [approved fix 13; plan G3] A Chromecast variant keeps the audio track it was prepared with: after switching the language on the phone,
  casting the same film again uses the old variant (no "Prepare" offered, it already exists). Unclear whether a
  second variant per language, or a hint "prepared with English audio", is wanted.
- [approved fix 15] "All servers": a title on two servers resumes only on the copy where it was watched (positions are per server);
  the merged cover may open the other copy from zero. Flagged in the prototype as a simplification.
- [approved fix 12] "All servers" in Downloads and Folders is a chip row, not a merged list: it reads like a filter, while Library
  really merges. Could confuse; check with the user.
- [approved fix 16] 2026-11-20, "All servers", Continue watching: the server prefix cuts the next-episode label to "jmpc · Dalej: S1 …"
  (with one server it read "Dalej: S1 E3"); with several servers the prefix may matter less than the episode.
- [approved fix 1] 2026-11-20, "All servers": "Uśpij" (suspend) in the header acts on the active server only, while the header says
  "All servers"; it disappears when the Downloads chip picks the laptop (no power control), which hints at the hidden
  active server.
- [approved fix 8] 2026-11-20, folder ⋮ "Move to…": the target list shows names and tags but not the free space of each disk, so
  picking where a big folder fits needs a trip to "Disks".
- [done 2026-11-23: no `mp-test-*` file is left on the server's disks or downloads] 2026-11-20, Downloads: "Odłożone w tym tygodniu" lists the test files `mp-test-*.avi/.mkv` ("left in downloads");
  test leftovers from an earlier session, to be removed from the server's downloads with the user's yes.
- [approved fix 18] 2026-11-20, Discover: the hero still says "Nowe na twoich dyskach" (new on your disks); item 7 added "Because you
  watched" as a row, the hero kept its old role. Unclear whether the hero should also change.
- [approved fix 10] 2026-11-20, "All servers", Folders: the server whose folder was opened last moves to the top of the list (it
  becomes the active one), so the sections swap places between visits; inside a folder nothing says which server
  it is on (the path shows "/").
- [approved fix 2] 2026-11-20, task card: "Monitor" sits right next to "Cancel" on a running task; a slip cancels a five-hour move
  (cancel has no confirmation).
- [approved fix 14] 2026-11-20, a title's screen: "Na serwerach" (on servers) with one row also shows when the title is on one
  server only; it says nothing new there.
- [approved fix 1] 2026-11-20, "All servers" with every server away: the header button says "Obudź" (wake) and wakes only the active
  server; "Wake all" lives in the switcher sheet.
- 2026-11-20, offline: an episode that is not on the phone opens the player, which sits at 00:00 / 00:00 with a
  spinning pause button and no message (watched for 28 s). The list doesn't say which episodes are on the phone
  apart from the buffer bar; tapping one that isn't should say "not on the phone, server unreachable" instead.
- 2026-11-20, offline: the hero offers "Odtwórz" for a film that is only on the server.
- 2026-11-20, offline: covers are blank on a cold start (not kept on disk).
- 2026-11-20, "Tidy up" (emulator, jmpc): 31 of 33 items are "unsure" moves between the films and cartoons folders
  (Filmy ↔ Bajki, Anime → Bajki), shown unticked because the folder the user chose wins. They fill the list and hide
  the few real suggestions; maybe a collapsed "Unsure" section, or leave Filmy ↔ Bajki out entirely.
- 2026-11-20, "Tidy up" and "Duplicates" previews take ~50 s to 2 min with no hint of what is being checked. Next app:
  the name of the file or folder being checked under the bar.
- 2026-11-20, "Subtitles" (fit) preview: all 578 items ticked, "about 289 min of work", and "38 MB" (the size of the
  subtitle files, which says nothing here). Fitting keeps the originals, so less risky than "Old formats", but one
  tap still queues hours of work.
- 2026-11-20, "Get subtitles": the language dialog's button said "Download" although it only opens a preview (fixed
  in the next app: "Preview"); 1115 items "about 1115 min", which OpenSubtitles' daily limit spreads over
  days. Maybe start with films that have none at all, or let the user pick a folder first.
- 2026-11-20, "Free up space" with "Up to 15% free" on a disk that is 39% free: the preview says only "Nothing to
  do." and "Skipped (4)", not that the disk already has more than 15% free. Fixed: a warning says so (agent 1.15.3).
- 2026-11-20, "Subtitles" preview with the videos right in the library folder (no subfolders): "Checked 0 folders".
  The number isn't folders at all: for "Subtitles" it counts subtitle files fitted before (left out), for "Get
  subtitles" the videos that already have them ("Checked 664 folders" on jmpc). Fixed in the next app: "N subtitle
  files fitted before, left out" / "N videos already have them", and no subtitle-file size in the summary.
