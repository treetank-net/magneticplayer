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
      Not yet: series episode lists offline, downloaded marks on covers, sending offline positions back.
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
- [ ] Library offline, continued: a series' episode list opened once online opens offline too (kept per server);
      covers of titles with a complete copy on the phone get a "downloaded" mark (a series: any episode).
- [ ] Folders: a folder's ⋮ menu has "Move to another library folder…" (the other library folders, the same tag
      first; it shows the move preview and starts a task) and "Delete to the trash…" (the trash preview).
- [ ] Chromecast and the audio choice (agent 1.14.0, `convert_audio`): "Prepare" for a dual-audio film converts with
      the audio track picked last (the folder's choice first), not always the Polish/first one; the variant then plays
      on the Chromecast with that language.

## "All servers" (two or more servers with files)

- [ ] The top switcher has "All servers" at the top; picked, the header says "All servers ▾" with "N of M servers
      on". Picking one server leaves All. "Wake all" when two or more are asleep with a known MAC.
- [ ] Library in All: Discover/Browse/search over all servers at once; a title on two servers shows once (the better
      copy: resolution, Polish audio), the cover says the server and "+1", the title screen lists "On servers" with
      each copy's quality, playing picks the copy's server. Continue watching and positions stay per server.
- [ ] Downloads and Folders in All: a row of server chips on top, one server's list at a time.
- [ ] With the active server asleep and another one on, Library in All doesn't show the asleep pane.

## Noted wishes
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

## UX notes (observed while testing; to review with the user, not fixed)

Written down as seen, also when unsure whether it is a problem at all.

- 2026-11-20, Remote, Audio sheet: a track's own title can contradict the detail line under it ("English: 5.1ch
  (AC3 640Kbps)" over "5.1 · AAC": the file really has AAC; the title is the release's). Which one to trust is unclear.
- 2026-11-20, Remote on jmpc: the title shown is the raw file name with release tags
  ("[group].Title.02.Episode [1080p.BRrip.x264.Dual-Audio].mkv"), not the recognised series/episode as in the catalog.
- 2026-11-20, tasks: item errors are cut from the front ("ffmpeg failed: e left on device Error writing trailer of
  /media/…/.Film.mp-shrink.part.mkv: No space left on device"): starts mid-word, shows a hidden temp path, and 457
  identical errors are listed one per file instead of one reason.
- 2026-11-20, shrink / "Update old formats": the preview promises space saved, but originals go to the trash on the
  same disk for 14 days, so the disk fills up during a big run; nothing on the preview or the task says so.
- 2026-11-20, Remote: after leaving the Remote (app in the background, nothing of ours playing) its session service
  stayed in the foreground for 10+ minutes (another app's paused cast session on the TV was selected).
- A Chromecast variant keeps the audio track it was prepared with: after switching the language on the phone,
  casting the same film again uses the old variant (no "Prepare" offered, it already exists). Unclear whether a
  second variant per language, or a hint "prepared with English audio", is wanted.
- "All servers": a title on two servers resumes only on the copy where it was watched (positions are per server);
  the merged cover may open the other copy from zero. Flagged in the prototype as a simplification.
- "All servers" in Downloads and Folders is a chip row, not a merged list: it reads like a filter, while Library
  really merges. Could confuse; check with the user.
