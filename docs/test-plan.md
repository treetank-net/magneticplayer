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
- [idea] Downloading to the phone from a title: a whole season, the extras or the whole series from the series'
  screen, without going to "On the phone".
- [idea] "On the phone" with many episodes (e.g. 100 five-minute ones): a compact list view, groups collapsible.
- [idea] Library and servers (stage 2 of docs/plan-multi-server.md, "a shared library from all servers: later"):
  the top switcher gets "All" next to each server; with "All" Library and Downloads merge every reachable server
  (titles marked with their server, e.g. "jmpc · Series (TOSHIBA)"), with one server picked they filter to it.
  "Wake all" for moving files between computers and browsing every library at once.
  Screen-only servers stay out of that (the switcher already shows them greyed out as "screen only").
- [idea] Remote's screen picker: every screen with its state (on / asleep) and "Wake" right in the list. Today Wake
  shows only after picking a screen that does not answer, and only when its MAC is known; the top switcher's Wake
  on a screen-only server also makes it the active file server (check).

- [idea] The catalog's "New on your disks" row: sometimes something else, e.g. a carousel "Because you watched <title>"
  (from the disks) or suggestions that are not on the disks (to download by magnet).
- [idea] A library usable without the computer: the catalog cached on the phone (maybe without covers), with what is
  downloaded marked and playable.
- [idea] Own audio track sheet (original / dubbing / lector) with a remembered preference.
