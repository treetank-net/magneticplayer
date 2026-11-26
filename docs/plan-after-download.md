# Plan: after download (steps for every new video)

The owner's idea (2026-11-26): once a server has the agent, a download it didn't start itself (Deluge, Transmission,
any client) gets the same care as a magnet from the app: moved to the right library folder, subtitles, ready for the
phone or Chromecast, shrunk, copied. The steps and their order are the owner's, set from the phone.

The approved prototype is `docs/ux-after-download.html` (local, not in git). Collection folders ("Bond", "Anime"
inside Films) are left out until cataloguing by collection is designed.

## Decisions (from the owner's feedback on the prototype)

- **New downloads only.** What is already on the disks is not touched; only torrents that complete after the feature
  is switched on. A first run marks everything complete as "seen".
- **Every video is its own run** (an episode too), with its own history. A season is 12 runs, shown grouped.
- **Free order of steps.** Nothing is forbidden; the server labels what each step costs (breaks seeding, needs the
  graphics card, needs disk space, subtitles fitted again) and the app shows warnings with one-tap fixes. Templates
  are starting points: "Right away on the phone", "Right away on Chromecast", "Free graphics card", "Archive",
  "Seeder".
- **"End seeding" is a step.** A boundary: when its condition is met (ratio or seeding time, never before a
  minimum) the server pauses the torrent or removes it from the client (files stay), and the steps below it start.
- **A step that changes the file while it still seeds** ends seeding first (pause in the client) and says so in the
  history ("seeding stopped at ratio 0.3"). Steps that change the file have two modes: in place, or beside it as a
  version.
- **Who answers.** Only the person who downloaded (the device that added the magnet) gets questions and the summary.
  Downloads added in the client on the computer have no downloader: the server decides at once, without asking.
  Nobody else's phone is notified.
- **No answer.** After `answer_after_h` (6 h) the marked default is taken and written in the history as "auto".
  Questions and their clocks are on disk and survive a restart.
- **Moves can interrupt seeding.** A move done by the client keeps seeding after a short pause; a move to another
  disk is a longer pause. Both are written in the history.
- **The server guards whatever the order:** subtitles and versions move with the film; a series stays on the disk
  where it already is; step hours win over automatic answers.

## Stage 1: the server (agent 1.23.0)

`server/mp_afterdl.py`, a conductor only. Each step is done by the machinery that already exists:

| step | done by |
|---|---|
| `move` | `Library.targets` (folder tag from the title, the disk with most free space, an existing series folder) and the client's `move` (seeding goes on) |
| `subs` | `mp_subflow` (its queue; `subflow.auto` no longer queues torrents itself while after-download is on) |
| `cast_ready` | a one-item `cast_ready` task (`Tasks._cast_need`) |
| `shrink` | a one-item `compress` task (`Tasks._pick`) |
| `seed_end` | the client's `pause` / `remove` (files kept), from `ratio` and `seeding_time` in the torrent list |
| `backup` | stage 3 (replication exists; a smaller copy with its own steps does not) |

- Settings: `afterdl` in `config.json`, `GET/POST /api/config/afterdl`, MCP `get_settings`/`set_settings`.
  `enabled` (off by default), `sources` (`app`, `client`, `archive`), `recipes` (`film`, `series`: ordered steps with
  options), `answer_after_h`.
- State: `afterdl.json` in the config directory: torrents seen complete, runs (id, torrent, file, source, downloader,
  steps with state, history events). Old finished runs are pruned.
- `GET /api/afterdl` (runs for the Downloads tab, brief), `GET /api/afterdl/<run>` (one run with its history),
  `POST /api/afterdl/<run>` (`skip`, `retry` a step), `GET /api/afterdl/templates`.
- The loop: SleepGuard's 20 s tick. One step per run at a time; a step that waits (seeding, a task in the queue,
  subtitles in subflow's queue) is checked again on the next tick. Steps that use the graphics card or the disk go
  through the task queue, which already pauses while someone watches.
- Questions (`ask`) are stored with their default and deadline; in stage 1 every step decides by itself and the
  history says "auto". Stage 2 adds the question to the downloader's phone.

## Stage 2: the app

- Settings → After download: the screens 1-6 of the prototype (sources, recipes with labels and warnings, a screen
  per step, templates).
- Downloads tab: finished downloads stay while their steps run (dots = steps), a season grouped.
- A file's history (from Downloads and from the film's card in the Library), with "Change…" for a decision.
- The question notification for the downloader only, and the summary when done.

## Stage 3

- `backup`: a copy on another disk, optionally smaller (its own encode, at night), with its own steps.
- Collection folders, once cataloguing by collection is designed.

## Owner's answers (2026-11-26)

- `answer_after_h` per step: each step that asks has its own time (when the answer matters differs by step).
  Stage 1 doesn't ask yet; the global value stays until questions are built.
- The original after shrinking is deleted right away by default (seeding has ended, nothing needs it): the shrink
  step's `original` option, `delete` or `trash`.
