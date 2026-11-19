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

- [ ] Audio: the note icon next to CC appears only with two or more audio tracks; the sheet groups tracks by language
      with the kind (lector, dubbing, audio description, commentary, original), title, channels and codec; the video
      pauses while it is open; a pick switches the track at once.
- [ ] The next episode (same folder) and other videos start with the same kind of track (e.g. "original" on a file
      with PL lector + EN); a file with one track is left alone.

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
- [ ] No "No subtitles, download?" bar and no auto-fetch attempt when the computer cannot be reached.
- [ ] The app's other tabs without the computer: no error dialogs piling up, a clear "not reachable" state.

## "On the phone": selection

- [ ] A long press on a card starts selecting (checkbox, buttons hidden); a tap (de)selects; Back ends it.
- [ ] The bar: "N selected · size", "Watched" adds the watched ones, select all / none, delete with
      "Delete N videos from the phone? That frees X. The videos stay on the computer."
- [ ] Two or more videos of one folder are grouped under the folder's name in episode order; the header's
      checkbox selects the whole group.
- [ ] Group headers show the size and how many are watched; a tap folds/unfolds, groups over 6 start folded.
- [ ] The list/cards icon at the top switches to one line per video (tap plays, long press selects) and is remembered.

## Catalog and Remote

- [ ] "Continue watching": a series whose episode was watched to the end stays, labelled "Next: S1 E5"; after its
      last episode it leaves the row; a film watched to the end leaves it.
- [ ] Remote, the screen pill's menu (two or more screens): each screen with "online"/"asleep", "Wake" on an asleep
      one with a known MAC picks it and wakes it.
- [ ] The top switcher's Wake on a screen-only server no longer makes it the file server.
- [ ] Series screen, the download icon by "Episodes": this season / from the next episode to the end / the whole
      series / extras, counts and sizes without what is already on the phone, the deletion rule for the batch
      (default "manually only"), free space on the phone (Download greyed out when it does not fit); the episodes get a
      "downloading" / "on the phone" icon.

## Magnets

- [ ] A magnet without `dn=` (agent 1.13.1): the card says it waits for the name; once the client has the metadata
      (≤ ~20 s after it arrives) the agent recognizes it and, when sure, moves it to its folder (journal: "unnamed
      magnet recognized"); the card shows "Recognized: moving…" or the unsure folder with "Change"; closing the card
      early still gets it placed.

## Library screens

- [ ] The bottom bar starts with Library, then Downloads, On the phone, Remote, Settings; the app opens on Library;
      a server without a library (downloads only) opens on Downloads.

- [ ] "Empty trash": the disks and the trash refresh right away (no 501 after it).
- [ ] Finished and cancelled tasks have "Remove from list"; a running one does not.
- [ ] The refresh icon on the disks screen re-reads free space, the trash and the tasks.

- [ ] "Download missing subtitles": in a film's ⋮ menu (library, catalog), "Missing subtitles for the series" on a
      series' episode, the subtitles icon in a folder, "Get subtitles" on the disks screen. Languages to pick, a preview
      of what lacks them (a folder/library) or a snackbar with the count (a film/series); the task in Tasks downloads
      and fits one by one, `note` per video; videos that already have the language (file or track) are left out.

## Noted wishes
- [idea] Library and servers (stage 2 of docs/plan-multi-server.md, "a shared library from all servers: later"):
  the top switcher gets "All" next to each server; with "All" Library and Downloads merge every reachable server
  (titles marked with their server, e.g. "jmpc · Series (TOSHIBA)"), with one server picked they filter to it.
  "Wake all" for moving files between computers and browsing every library at once.
  Screen-only servers stay out of that (the switcher already shows them greyed out as "screen only").

- [idea] The catalog's "New on your disks" row: sometimes something else, e.g. a carousel "Because you watched <title>"
  (from the disks) or suggestions that are not on the disks (to download by magnet).
- [idea] A library usable without the computer: the catalog cached on the phone (maybe without covers), with what is
  downloaded marked and playable. Moments when the phone reaches a server again are sync points: refresh the cached
  catalog, send positions and watched marks made offline, refresh downloaded copies' subtitles. Incremental, like
  commit hashes: the agent keeps a change counter (or hash) per library and per item, the phone sends the last one it
  saw and gets only what changed since (the whole catalog only the first time or when it is small).
- [todo] Two season 2 extras of one re-encoded series failed: their AVI originals are truncated (the header promises
  more than is there). The check was fixed in agent 1.10.1; the two files only need a new shrink run on that folder.
- [later] The folder browser plays a single file (no next-episode card, no queue); the catalog's series screen is the
  way to watch a series. An episode picker inside the player will cover the rest.
- [todo] Audio choice on screens: mpv and Chromecast get the remembered kind of track (the phone part is done).
  Agreed sheet, for reference: a button next to CC, tracks grouped by language with the file's title
  (lector, dubbing, audio description, commentary), channels (5.1, stereo) and codec; the video pauses while open.
  The choice is remembered as a kind ("original", "Polish lector", "dubbing"), not a track number: per folder in a
  series, otherwise a general preference ("original with subtitles", "lector when there is one"). The kind comes from
  the track title; "original" is the film's language from recognition (TMDB); a track without a title counts only
  by language. mpv and Chromecast get the same choice; DLNA cannot switch tracks (the file's default plays).
- [idea] The folder browser as the place for jobs on its files: shrink, missing subtitles (both there), and more as
  they come. Zip archiving gives videos almost nothing (already compressed), so no.
