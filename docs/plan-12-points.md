# The 12-point plan and the approved UX fixes

Scope of the current goal: finish the 12 points below, and the UX fixes the owner approved after reviewing the notes
taken while testing them (`test-plan.md`, "UX notes"). Anything not listed here is out of scope: note it in
`test-plan.md` ("UX notes" or "Noted wishes"), don't build it.

Results and tick marks live in `test-plan.md`; this file says what to do and in which order.

## The 12 points

| # | point | state (2026-11-20) |
|---|---|---|
| 1 | Playback from "On the phone" without Wi-Fi | done, verified (also a cold start) |
| 2 | Library without the computer | episode lists, Continue watching and playback verified offline; "on the phone" marks fixed, to verify |
| 3 | Season download | done |
| 4 | List view | done |
| 5 | "All servers" | verified on the Pixel; fixes below |
| 6 | Screens on the Remote | done |
| 7 | Because you watched | done |
| 8 | Audio panel; mpv and Chromecast get the same choice | mpv done; Chromecast converts with the chosen audio (API) |
| 9 | Magnet re-recognition | done |
| 10 | Folder view menus | menu and move dialog verified |
| 11 | Continue watching | done |
| 12 | Re-encode of one series' season 2 extras (old-format run) | the series is on the bigger disk now; rerun pending |

## Approved fixes, in order

Numbers in brackets are the review numbers (2026-11-20).

### First: errors and risk

1. **"All servers": suspend/wake in the header act on the active server only** [8]. A real bug: in "All" mode the
   header button must act on all servers (or open the server list), never silently on a hidden one.
2. **Cancelling a running task needs a confirmation** [1], and "Monitor" moves away from "Cancel".
3. **Trash on a full disk** [2]: originals replaced by a re-encode go to the trash on the same disk for 14 days, so a
   big run fills the disk (it did: hundreds of "no space left" errors). A guard for this case: when the source disk
   is short of room, put the trash on another library disk with room, and stop starting new items (with a clear
   reason) before a disk goes under its reserve. The preview says when the space comes back.
4. **Offline: an episode or film that isn't on the phone** [4, 6]: don't open a player stuck at 00:00; say clearly
   that it isn't on the phone and the server is out of reach (the hero's "Play" too).
5. **A loader in the player while the file opens** (owner's wish): opening a file from the phone can take a few
   seconds with a black screen; show that it is loading.
6. **Smooth resume** (owner's wish): resuming sometimes plays sound while the picture stands still until they meet.
   Resume from the keyframe before the saved position (or a few seconds before it) so picture and sound start
   together. Find the cause first (seen on files from the phone).
7. **Episode list: mark the episodes that are on the phone** [7] (as the cover mark does).
8. **Free space where it matters** [10]: free space of each disk in "Move to…", too-small disks greyed out; the
   phone's free space where something is saved on the phone (season download, "keep on the phone").

### Then

9. **Error lists of a task grouped by reason** [3], cut at the end, without temp paths.
10. **Which server and disk** [9]: in "All servers" Folders keep a fixed order, and inside a folder show where it is,
    e.g. `jmpc:/<disk>/…` or a server badge.
11. **The Remote shows the recognised title** [11] (series, episode) instead of the file name.
12. **"All servers" in Downloads** [14]: one merged list instead of server chips.
    Done as: with one server that has a torrent client, no chips (its list as is); a true merged list of several
    clients is deferred.
13. **Chromecast variant and audio** [15]: a variant is prepared with one audio track; after picking another language
    the old variant is cast. Say which audio a variant has and offer preparing it again (or one per language).
14. **"On servers" on a title's screen** [17]: with one copy it says nothing; show it only for two or more copies.
    The owner reads the row as "you have it"; where it is belongs to "Available".

### Low priority

15. **Positions per server in "All servers"** [13]: a title on two servers resumes only where it was watched.
16. **Next-episode label cut by the server prefix** [12] (no comment from the owner; the episode first).
17. **Audio sheet: track title vs. detail line** [16] (no comment; trust the detail line).
18. **Discover hero as a rotating carousel** [18]: "Because you watched", "New on your disks", new releases…, the
    first one picked at random each time.
19. **The Remote's session service staying in the foreground** [19] (minor).
20. **Remove the test files from the server's downloads** [20] (at the very end).

### Needs a decision before building

- **Covers and data kept on the phone** [5]: no covers on disk by default (they take room, some people want no data on
  the phone). Maybe an option. In the same spirit: with a short buffer (say 5 minutes) keep about that much of the
  file on disk; larger values to be agreed.

## Not to do in this goal

- Anything not listed above; new findings go to `test-plan.md` as notes.
- Emptying the trash (`.mp-trash`) without the owner's yes.
- Remote access from outside the home network.
