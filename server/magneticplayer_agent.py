#!/usr/bin/env python3
"""MagneticPlayer: the agent running on the computer (Linux Mint, also Windows).

HTTP API for the phone app:
  - adding magnet links to Deluge (deluge-web JSON-RPC) or Transmission (RPC),
  - list of torrents and files, streaming files (HTTP Range) to the player on the phone,
  - remote control of mpv on the computer (JSON IPC): play/pause, seek, fullscreen, volume, subtitles,
  - sleep inhibition while something is downloading or a video is playing; suspend on request.

Python standard library only. Configuration: ~/.config/magneticplayer/config.json
"""
import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mp_media
import mp_platform as platform
from mp_cast import Casts, CastError
import mp_renderers
from mp_announce import Announcer
import mp_upload
import mp_install
import mp_access
import mp_appupdate
import mp_selfupdate
import mp_tasks
import mp_mcp
import mp_routes
import mp_i18n
from mp_i18n import T, bind
import mp_power
from mp_core import (APK_DIR, CONFIG_DIR, CONFIG_PATH, WINDOWS, ApiError, apk_version, json_default, lan_ip, load_config,
                     log, save_config)
from mp_mpv import Mpv
from mp_power import SleepGuard, poweroff, suspend
from mp_torrents import DelugeBackend, NoBackend, TransmissionBackend, ready_end

# DLNA: byte seeking (OP=01), no conversion, streaming + background (FLAGS)
DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
from mp_devices import Devices
from mp_jobs import Jobs
import mp_catalog
import mp_ident
import mp_episodes
import mp_library
from mp_library import Library, LibError, sidecar_subs
from mp_subs import Subtitles, SubsError, readable_srt, strip_descriptions, rank_key as subs_rank_key
import mp_subsync
import mp_retime
import mp_iafill
import mp_osdump
import mp_rhythm
import mp_subflow
import mp_afterdl
import mp_segments
import mp_subfiles
import mp_watch

VERSION = "1.23.0"
API_LEVEL = 2  # /api/info: id, features, power; device tokens, tickets, player/open with a url


# Guest (role "guest", mp_devices.py): watching only. An allow list, not a deny list, so new endpoints
# stay closed to guests until someone deliberately adds them here.
GUEST_ALLOWED = {
    "GET": ("/api/info", "/api/announce", "/api/app/version", "/app/MagneticPlayer.apk", "/api/library",
            "/api/library/browse", "/api/library/episodes", "/api/library/segments", "/api/library/search", "/api/library/cover", "/lib/", "/media/", "/sub", "/api/subs/list", "/api/subs/job", "/api/media/probe", "/api/media/title",
            "/api/media/converts", "/api/jobs", "/api/player/status", "/api/cast/status", "/api/cast/devices",
            "/api/renderers", "/api/torrents"),
    "POST": ("/api/media/ticket", "/api/player/open", "/api/player/cmd", "/api/cast/play", "/api/cast/cmd",
             "/api/subs/search", "/api/subs/download", "/api/subs/job/stop", "/api/system/screen_on", "/api/wake",
             "/api/media/convert"),
}


def guest_allowed(method, p):
    allowed = GUEST_ALLOWED.get("GET" if method == "HEAD" else method, ())
    if p.startswith("/api/torrents/"):  # the torrent's file list is allowed, actions (start, remove, "after download") are not
        return method in ("GET", "HEAD") and p.endswith("/files")
    return any(p == a or (a.endswith("/") and p.startswith(a)) for a in allowed)


# ---------------------------------------------------------------- HTTP

class App:
    def __init__(self, cfg):
        self.cfg = cfg
        mp_power.POWER = bool(cfg.get("power", True))
        mp_power.AUTO_UNLOCK = bool(cfg.get("auto_unlock", True))
        self.backend = {"deluge": DelugeBackend, "transmission": TransmissionBackend}.get(cfg["backend"], NoBackend)(cfg)
        # archive.org torrents stuck on a piece shared with a regenerated file: completed over HTTP (agent 1.21.1+)
        self.iafill = mp_iafill.IAFill(self, CONFIG_DIR, log)
        self.backend = mp_iafill.Wrap(self.backend, self.iafill)
        self.mpv = Mpv(cfg)
        self.guard = SleepGuard(self)
        self.library = Library(cfg, lambda: save_config(cfg), self._default_dirs)
        self.subs = Subtitles(cfg, lambda: save_config(cfg))
        self.subsync = mp_subsync.Store(CONFIG_DIR, log)
        self.subsources = mp_subfiles.Sources(CONFIG_DIR)  # where each subtitle file came from (agent 1.12+)
        # subtitles waiting for their film to finish downloading: {real video path: [names]} (agent 1.18.8+)
        self.refit_path, self.refit_lock, self.refit_busy = os.path.join(CONFIG_DIR, "subs-refit.json"), threading.Lock(), False
        try:
            with open(self.refit_path) as f:
                self.refit = json.load(f)
        except (OSError, ValueError):
            self.refit = {}
        self.segments = mp_segments.Segments(log)
        self.jobs = Jobs(os.path.join(CONFIG_DIR, "jobs.json"), log)
        self.devices = Devices(os.path.join(CONFIG_DIR, "devices.json"), cfg["token"])
        self._subs_ok = None
        self.convert = mp_media.Converter(log, os.path.join(CONFIG_DIR, "encodes"))
        self.renderers = mp_renderers.Renderers(os.path.join(CONFIG_DIR, "renderers.json"), log)
        self.casts = Casts(log, self.renderers)
        # announcing "what is playing" between remotes (LAN multicast): this server's mpv + a list from others for the page
        self.announce = Announcer(log, lambda: {"id": self.cfg["id"], "name": self.name()}, self._announce_mine)
        self.announce.start()
        # uploading videos from the phone, with a history of what landed where (Downloads screen)
        self.uploads = mp_upload.Uploads(cfg, self.library, log, os.path.join(CONFIG_DIR, "uploads.json"))
        # magnet identification (movie/series/kids' show → folder): a small IMDb index refreshed in the background + Wikidata
        self.ident = mp_ident.Service(cfg, os.path.join(CONFIG_DIR, "ident-memory.json"), log)
        self.ident.start()
        # the library as films and series (genres, age, languages, people, topics; Wikidata by IMDb id), shared wishlist
        self.catalog = mp_catalog.Catalog(os.path.join(CONFIG_DIR, "catalog.json"), cfg, self.library, self.ident, log)
        self.catalog.hash_ids = self.subs.hash_ids
        self.catalog.start()
        # episode lists of series (Wikidata, IMDb datasets as fallback): missing seasons and episodes on the title page
        self.episodes = mp_episodes.Episodes(os.path.join(os.path.dirname(mp_ident.cache_dir()), "episodes"), log)
        mp_library.ON_CHANGE.append(self.catalog.refresh)
        self.wishlist = mp_catalog.Wishlist(os.path.join(CONFIG_DIR, "wishlist.json"))
        self.watchstate = mp_watch.WatchState(os.path.join(CONFIG_DIR, "watch.json"), log)  # phones' positions (1.18+)
        self.installs = mp_install.Installs(log)  # installation on another computer (join code)
        self.access = mp_access.AccessRequests(log)  # access requests from the home network (mp_access.py)
        # app for phones from GitHub releases (mp_appupdate.py); the phone sets the mode
        self.app_updates = mp_appupdate.AppUpdates(cfg, lambda: save_config(cfg), APK_DIR, apk_version, log)
        self.app_updates.start()
        # the agent itself from GitHub releases (mp_selfupdate.py): installed when nobody is streaming or converting
        self.streams, self.stream_last, self.stream_lock = 0, 0.0, threading.Lock()
        self.watch = {}  # real path of a video → {active GETs, last request, last GET} (see watched())
        # background file tasks: moving between disks, shrinking by storage profiles (mp_tasks.py); encodes pause while
        # somebody streams from the library
        self.tasks = mp_tasks.Tasks(os.path.join(CONFIG_DIR, "tasks.json"), cfg, lambda: save_config(cfg), self.library,
                                    lambda: self.backend, self.watched,
                                    self.renderers, log)
        self.tasks.subsync = self.subsync
        self.tasks.fetch_subs = self.fetch_best_subs
        self.tasks.subs_ok = lambda: self.subs_available(wait=False)
        self.tasks.catalog = self.catalog  # scopes by title, tag and age group (1.16+)
        self.tasks.subsources = self.subsources
        self.agent_updates = mp_selfupdate.AgentUpdates(cfg, lambda: save_config(cfg), os.path.dirname(os.path.abspath(__file__)),
                                                        VERSION, cfg["port"], self.busy, log)
        self.agent_updates.start()
        # MCP server for an AI assistant in the LAN, approved on the phone (mp_mcp.py)
        self.mcp = mp_mcp.Mcp(self, VERSION, CONFIG_DIR, log)
        # subtitles prepared by themselves after a download: a baseline in the film's language, then the language
        # picked last fitted to it (agent 1.21+, mp_subflow.py)
        self.subflow = mp_subflow.SubFlow(self, CONFIG_DIR, log)
        # after download (agent 1.23+, mp_afterdl.py): the owner's steps for every new video, any client
        self.afterdl = mp_afterdl.AfterDownload(self, CONFIG_DIR, log)
        mp_osdump.setup(cfg, CONFIG_DIR, log)  # the OpenSubtitles.org archive on archive.org (agent 1.22+)
        # discover Chromecasts from startup: the renderer registry knows the model before anyone opens the device picker
        threading.Thread(target=self._cast_warmup, daemon=True).start()
        # which H.264 encoder works on this hardware (NVENC/VAAPI/...): a trial encode takes seconds, so in the background
        threading.Thread(target=mp_media.detect_encoder, daemon=True).start()
        # HEVC encoders for shrinking (processor and graphics card, with a speed trial): after the one above
        threading.Thread(target=mp_media.detect_hevc, daemon=True).start()
        # importing subliminal is slow (~30 s on a fresh Windows): in the background so the first /api/info (pairing) doesn't wait
        threading.Thread(target=self.subs_available, daemon=True).start()

    def name(self):
        return self.cfg.get("name") or socket.gethostname()

    def save_cfg(self):
        save_config(self.cfg)

    def watched(self):
        """Videos in use now, for shrink tasks: [{"path", "kind"}]. A GET in the last 30 s = stream; only HEADs in the
        last 60 s = probe (a phone playing its own copy still asks about the file); mpv on this screen; a conversion."""
        now, out = time.time(), []
        with self.stream_lock:
            for path, w in list(self.watch.items()):
                if w["active"] or now - w["read"] < 30:
                    out.append({"path": path, "kind": "stream"})
                elif now - w["last"] < 60:
                    out.append({"path": path, "kind": "probe"})
                elif now - w["last"] > 3600:
                    del self.watch[path]
        try:
            if self.mpv.running() and (path := self.mpv.prop("path")) and os.path.isfile(path):
                out.append({"path": os.path.realpath(path), "kind": "mpv"})
        except Exception:  # noqa: BLE001 — mpv just ending
            pass
        out += [{"path": j["path"], "kind": "convert"} for j in self.convert.all() if j["state"] in ("queued", "running")]
        return out

    def fit_subs(self, full, name, path=None):
        """Fits a subtitle file next to `full` to the film's speech (mp_subsync). A file of a seeded torrent is only
        assessed. If mpv plays this video, a changed file is loaded again. `path`: a file elsewhere (a fresh download
        in a temporary folder, before it goes next to the video)."""
        elsewhere = path is not None
        path = path or os.path.join(os.path.dirname(full), name)
        locked = not elsewhere and self.tasks.torrent_owned(path)
        if self.downloading_part(full):
            # the film is still downloading: its sound isn't all there (a torrent's missing pieces are zeros), so the
            # fit would fail or lie. Fitted once the torrent is complete (refit_due); a fresh download registers its
            # final name itself (download_subs)
            if not elsewhere:
                self.refit_later(full, name)
            return {"verdict": "pending", "reason": "incomplete", "name": name, "locked": locked,
                    "error": T("the film is still downloading: the subtitles will be fitted when it is complete",
                               "film jeszcze się pobiera: napisy zostaną dopasowane, gdy będzie cały")}
        base = self.subflow.baseline(full)
        lang = mp_retime.language_of(os.path.basename(path))
        if base and lang and lang != base["lang"] and mp_subflow.settings(self.cfg)["translate"] == "baseline" \
                and os.path.basename(path) != base.get("name"):
            # agent 1.21+: another language fits to the film's own subtitles, timed already (mp_subflow)
            try:
                if locked:
                    _, res = mp_rhythm.translate(readable_srt(path, full), base, self.cfg)
                    res.update(changed=False, name=name, locked=True)
                    return res
                res = self.subflow.translate_file(full, path, in_place=True)
                res.update(changed=True, locked=False)
            except (mp_rhythm.RhythmError, OSError, ValueError) as e:
                log("subtitle fitting to the baseline:", name, e)
                return {"verdict": "error", "error": str(e), "name": name}
            if res.get("changed") and not elsewhere:
                try:
                    if self.mpv.running() and self.mpv.prop("path") == full:
                        self.mpv.cmd("sub-reload")
                except ApiError:
                    pass
            return res
        try:
            res = self.subsync.fit(full, path, change=not locked)
        except (mp_subsync.SyncError, OSError, ValueError) as e:
            log("subtitle fitting:", name, e)
            return {"verdict": "error", "error": str(e), "name": name}
        res["locked"] = locked
        if not elsewhere and not locked:
            self.retime_later(full, name, res)
        if res.get("changed") and not elsewhere:
            try:
                if self.mpv.running() and self.mpv.prop("path") == full:
                    new = os.path.join(os.path.dirname(full), res["name"])
                    self.mpv.cmd("sub-add", new, "select")
            except ApiError:
                pass
        return res

    def retime_later(self, full, name, fit):
        """An "unsure" fit (often a copy cut differently: `cuts`) is re-timed by Whisper in the background when this
        computer has it (agent 1.20+, mp_retime; config "whisper": {"auto": false} turns that off)."""
        w = mp_retime.settings(self.cfg)
        if fit.get("verdict") != "unsure" or not w["auto"] or not mp_retime.available(self.cfg):
            return
        fit["retime"] = "pending"
        threading.Thread(target=bind(self.retime_subs), args=(full, name), daemon=True).start()

    def retime_subs(self, full, name, job=None):
        """Re-times a subtitle file next to `full` with Whisper (mp_retime) and rewrites it in place (the original is
        kept). `job`: progress for /api/subs/job ("retime", heard minutes)."""
        path = os.path.join(os.path.dirname(full), name)
        if self.tasks.torrent_owned(path):
            return {"verdict": "error", "error": T("these subtitles belong to a torrent being shared: not changed",
                                                   "te napisy należą do udostępnianego torrenta: bez zmian"), "name": name}
        if self.downloading_part(full):
            return {"verdict": "error", "error": T("the film is still downloading", "film jeszcze się pobiera"), "name": name}
        if not mp_retime.available(self.cfg):
            return {"verdict": "error", "error": T("Whisper is not installed on this computer",
                                                   "na tym komputerze nie ma Whispera"), "name": name}
        progress = job if job is not None else {}
        progress["phase"] = "retime"
        with mp_retime._lock:
            try:
                text = readable_srt(path, full)
                new, res = mp_retime.retime(full, text, mp_retime.settings(self.cfg), mp_retime.language_of(name),
                                            self.subsync.get_any(path), progress)
                self.subsync.replace(path, new, res)
            except (mp_retime.RetimeError, mp_subsync.SyncError, OSError, ValueError) as e:
                log("subtitle re-timing:", name, e)
                return {"verdict": "error", "error": str(e), "name": name}
        res["name"] = name
        try:
            if self.mpv.running() and self.mpv.prop("path") == full:
                self.mpv.cmd("sub-add", path, "select")
        except ApiError:
            pass
        return res

    def download_subs(self, full, b, job=None, prepare=None):
        """POST /api/subs/download: one search result becomes the default subtitles of its language (the current default
        moves to Subtitles/). `job` (async): its "phase" goes "download" → "fit" → done."""
        langs = [l.strip().lower() for l in (b.get("languages") or "en,pl").split(",") if l.strip()]
        # fitted to this copy of the film (agent 1.9+): "fit": "wait" (the app waits, ~30 s the first time for a film)
        # returns the outcome in "fit"; otherwise it happens in the background. Agent 1.12.1+: waiting, the file is
        # fitted in a temporary folder and only then put next to the video, so a player that lists the subtitles
        # meanwhile doesn't load the unfitted file
        wait = (b.get("fit") == "wait" or prepare is not None) and mp_subsync.available()
        own_prepare = prepare

        def prepare(tmp):
            if job is not None:
                job["phase"] = "fit"
            return own_prepare(tmp) if own_prepare is not None else a_fit(tmp)

        def a_fit(tmp):
            return self.fit_subs(full, os.path.basename(tmp), tmp)

        def moved(old, new):  # in place: the fitting assessment and the source follow it (under mp_subs' place lock)
            self.subsync.move(old, new)
            self.subsources.put(new, b["provider"], b["id"], release.get("r", ""))

        release = {}
        res = self.subs.download(full, str(b["id"]), b["provider"], langs, (b.get("title") or "").strip() or None,
                                 b.get("year") or None, place=lambda target, src: (release.update(r=src["release"]), mp_subfiles.place(
                                     full, target, src, self.subsources, self.tasks.torrent_owned, self.subsync.move))[1],
                                 prepare=prepare if wait else None, moved=moved)
        res.pop("path", None)
        if not b.get("auto") and res.get("name"):  # picked by the user: the next films get it too (agent 1.21+)
            self.subflow.chosen(mp_retime.language_of(res["name"]))
        fit = res.get("name") and mp_subsync.available()
        prepared = res.pop("prepared", None)
        if wait and prepared is not None:
            res["fit"] = prepared
        if res.get("name") and self.mpv.running() and self.mpv.prop("path") == full:  # this very video is playing: on
            try:
                self.mpv.cmd("sub-add", os.path.join(os.path.dirname(full), res["name"]), "select")
            except ApiError:
                pass
        if fit and not wait:
            threading.Thread(target=bind(self.fit_subs), args=(full, res["name"]), daemon=True).start()
            res["fit"] = {"verdict": "pending"}
        elif res.get("name") and (res.get("fit") or {}).get("verdict") == "unsure" and own_prepare is None \
                and (res.get("fit") or {}).get("by") not in ("baseline", "rhythm") and not self.tasks.torrent_owned(
                os.path.join(os.path.dirname(full), res["name"])):
            self.retime_later(full, res["name"], res["fit"])  # fitted in a temporary folder: re-timed in place now
        elif (res.get("fit") or {}).get("reason") == "incomplete" and res.get("name"):
            self.refit_later(full, res["name"])  # fitted in a temporary folder: wait under the name it got here
        return res

    # ---- subtitles downloaded while their film was still downloading: fitted once it is complete (agent 1.18.8+)

    def refit_later(self, full, name):
        with self.refit_lock:
            names = self.refit.setdefault(os.path.realpath(full), [])
            if name not in names:
                names.append(name)
                self._refit_save()

    def refit_waiting(self, full):
        """Names of subtitle files next to `full` that wait for the film to finish downloading."""
        with self.refit_lock:
            return set(self.refit.get(os.path.realpath(full), ()))

    def _refit_save(self):
        tmp = self.refit_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.refit, f, ensure_ascii=False)
        os.replace(tmp, self.refit_path)

    def refit_due(self):
        """Every ~20 s (SleepGuard): the waiting subtitles of films that are complete now are fitted, in a thread (the
        first fit of a film reads its whole sound)."""
        if not self.refit or self.refit_busy:
            return
        ready = []
        with self.refit_lock:
            for full in list(self.refit):
                if not os.path.exists(full):
                    self.refit.pop(full)
                elif self.downloading_part(full) is None:
                    ready.append((full, self.refit.pop(full)))
            self._refit_save()
        if not ready:
            return

        def run():
            try:
                for full, names in ready:
                    for name in names:
                        if os.path.exists(os.path.join(os.path.dirname(full), name)):
                            res = self.fit_subs(full, name)
                            log("subtitles fitted now that the film is complete:", name, res.get("verdict"))
            finally:
                self.refit_busy = False
        self.refit_busy = True
        threading.Thread(target=run, daemon=True).start()

    def fetch_best_subs(self, full, lang, prefer=None):
        """As the app's "Download: <language>": search one language, try the best results (no machine translation,
        `prefer`'s provider first, then `mp_subs.rank_key`: hash match, same frame rate, score, trusted, downloads) until one fits this copy (at most 3); results already here are skipped.
        The download result, or None when nothing was found (task "download missing subtitles")."""
        d = os.path.dirname(full)
        have = set()
        for x in sidecar_subs(full):
            src = self.subsources.get(os.path.join(d, x["name"]))
            if src:
                have.add(f"{src['provider']}:{src['id']}")
        try:
            found = self.subs.search(full, [lang], known=self.catalog.known_for(full))
        except Exception as e:  # noqa: BLE001  (providers down, an unrecognised title)
            log("subtitles: search failed for", os.path.basename(full), e)
            return None
        cands = [r for r in found.get("results") or [] if r.get("lang") == lang and f"{r['provider']}:{r['id']}" not in have]
        cands.sort(key=lambda r: (bool(r.get("machine")), r["provider"] != prefer, *subs_rank_key(r)))
        got = None
        for r in cands[:3]:
            try:
                res = self.download_subs(full, {"id": r["id"], "provider": r["provider"], "languages": lang, "fit": "wait"})
            except Exception as e:  # noqa: BLE001  (a provider's limit, an empty file): try the next one
                log("subtitles: download failed", r["provider"], e)
                continue
            got = res
            if (res.get("fit") or {}).get("verdict") != "bad":
                break
        return got

    def shift_subs(self, full, name, by):
        """Moves a subtitle file next to `full` by `by` seconds; mpv playing this video loads it again."""
        path = os.path.join(os.path.dirname(full), name)
        if self.tasks.torrent_owned(path):
            raise ApiError(T("these subtitles belong to a torrent that is still seeding: they can't be changed",
                             "te napisy należą do torrenta, który jeszcze seeduje: nie można ich zmieniać"), 409)
        try:
            total = self.subsync.shift(path, by, full)
        except OSError as e:
            raise ApiError(T(f"can't change the file: {e.strerror}", f"nie da się zmienić pliku: {e.strerror}"), 500)
        try:
            if self.mpv.running() and self.mpv.prop("path") == full:
                self.mpv.cmd("sub-reload")
        except ApiError:
            pass
        return {"name": name, "manual": total}

    def series_episodes(self, imdb):
        """All seasons and episodes of a series, the ones on this server's disks marked with their file. Only the
        copies the asking device may see; without online lookups just the own files (all in `other`)."""
        if not re.fullmatch(r"tt\d+", imdb or ""):
            raise ApiError(T("bad IMDb id", "zły numer IMDb"), 400)
        lang = mp_i18n.lang()
        files = []
        for c in self.catalog.public(lang)["items"]:
            if c.get("imdb") != imdb or not c.get("dir"):
                continue
            try:
                eps = self.library.episodes(c["root"], c["dir"])["episodes"]
            except LibError:
                continue
            files += [(x["season"], x["episode"], f"{c['root']}:{x['path']}") for x in eps]
        listing = None
        if self.ident.online:
            try:
                listing = self.episodes.get(imdb, lang, on_disk=len({(s, e) for s, e, _ in files if e}))
            except Exception as e:  # noqa: BLE001 — a failed lookup still shows the own episodes
                log("episodes:", e)
        out = mp_episodes.Episodes.merge(listing, files)

        def split(v):
            root, rel = v.split(":", 1)
            return {"root": int(root), "path": rel}
        for s in out["seasons"]:
            for e in s["episodes"]:
                if e.get("path"):
                    e.update(split(e["path"]))
        out["other"] = [split(v) for v in out["other"]]
        out["source"] = (listing or {}).get("source")
        return out

    def busy(self):
        """Why a restart (self-update) should wait now, or None: a stream or upload in the last 5 minutes, a queued
        conversion, a copy or move. Running encodes are detached (mp_detached) and survive the restart."""
        with self.stream_lock:
            if self.streams or time.time() - self.stream_last < 300:
                return "stream"
        if any(j["state"] == "queued" for j in self.convert.all()):
            return "conversion"
        if self.tasks.busy():
            return "task"
        return None

    def wake_info(self, net):
        """Assessment from mp_platform + "yes" once wake over the network has worked (on the same NIC)."""
        if self.cfg.get("wake") is False:  # lite mode: someone else's computer, nobody wakes it over the network
            return {"method": None, "mac": None, "confidence": "no", "reason": T("wake disabled (lite install)", "budzenie wyłączone (instalacja lekka)")}
        w = platform.wake_info(net)
        v = self.cfg.get("wake_verified") or {}
        if v.get("at") and w.get("mac") and v.get("mac") in (None, w["mac"]) and not net.get("wireless"):
            day = time.strftime("%d.%m.%Y", time.localtime(v["at"]))
            w = {**w, "confidence": "yes", "reason": T(f"wake over the network worked ({day})", f"budzenie przez sieć zadziałało ({day})"), "verified_at": v["at"]}
        return w

    def is_screen(self):
        """Whether playback is allowed here: config "player" (default yes) + mpv and a graphical session."""
        return bool(self.cfg.get("player", True)) and platform.has_display()

    def subs_available(self, wait=True):
        if self._subs_ok is None and not wait:  # the import is still running: it is enough that the package exists
            import importlib.util
            from mp_subs import add_venv_path
            add_venv_path()
            return importlib.util.find_spec("subliminal") is not None
        if self._subs_ok is None:
            try:
                from mp_subs import _subliminal
                _subliminal()
                self._subs_ok = True
            except Exception:  # noqa: BLE001 — no venv/subliminal
                self._subs_ok = False
        return self._subs_ok

    def info(self, client_ip, device, role="owner"):
        """Who this server is and what it can do (the app hides what is missing; what a guest may not do is removed for them)."""
        out = self._info(client_ip, device)
        out["role"] = role
        if role == "guest":
            out["features"].update(upload=False, install=False, unlock=False, apk=out["features"]["apk"])
            out["power"].update(suspend=False, poweroff=False)
        return out

    def _info(self, client_ip, device):
        net = platform.net_info(client_ip)
        has_torrent = not isinstance(self.backend.raw, NoBackend)
        try:
            library = bool(self.library.roots())
        except LibError:
            library = False
        return {
            "id": self.cfg["id"], "name": self.name(), "hostname": socket.gethostname(), "version": VERSION,
            "api": API_LEVEL, "backend": self.cfg["backend"], "keep_awake": self.guard.reason, "device": device,
            **net,
            "features": {
                "torrent": self.cfg["backend"] if has_torrent else None,
                "library": library,
                "library_tags": True,  # folder tags, visibility and exclusions (agent 1.4+)
                "library_search": True,  # GET /api/library/search (agent 1.5+)
                "library_guess": True,  # magnet identification and downloading straight into a folder (agent 1.5+)
                # disks, background tasks (moving, shrinking), storage profiles, trash (agent 1.6+)
                "library_tasks": True,
                "shrink_encoder": self.cfg.get("shrink_encoder") or "auto",
                "shrink_while_watching": self.cfg.get("shrink_while_watching") or "continue",
                "library_episodes": True, "segments": mp_segments.available(), "subs_versions": True, "subs_shift": True, "subs_download_job": True, "player_sub_lang": True, "player_audio": True, "task_remove": True, "subs_get_task": True,  # intro / credits (agent 1.11+)
                "library_covers": True,  # GET /api/library/cover (agent 1.6+)
                # /api/catalog, /api/wishlist, /api/uploads, /api/config/search (agent 1.8+)
                "catalog": True,
                "tasks_brief": True,  # GET /api/tasks?brief=1, "tasks" in /api/ping (agent 1.14.1+)
                "library_moves": True,  # GET /api/library/moves?since= (agent 1.14+)
                "convert_audio": True,  # POST /api/media/convert takes "audio" (agent 1.14+)
                # GET /api/media/probe?audio= answers audio_want and variant.audio_match; GET /api/media/title (agent 1.18+)
                "convert_audio_match": True, "media_title": True,
                "library_modernize": True,  # POST /api/library/plan {"kind": "modernize"} (agent 1.8.2+)
                # task scopes, GET /api/library/jobs, one plan kind per task, /api/config/jobs (agent 1.16+)
                "jobs_v2": True,
                "episode_lists": True,  # GET /api/catalog/episodes (agent 1.16+)
                # GET /api/catalog/search (titles you don't have) and "about" in /api/catalog/title (agent 1.17+)
                "catalog_search": True,
                # GET /api/catalog/suggest: "because you watched…: not on your disks" from the IMDb index (agent 1.18+)
                "suggest_missing": True,
                # agent 1.18+: GET /api/catalog?since=<cursor> answers with changes only; POST /api/watch/sync takes
                # positions, "watched" marks and subtitle choices made on a phone without the server
                "catalog_delta": True, "watch_sync": True,
                "jobs": ["free", "dupes", "audio_fix", "modernize", "downscale", "compress", "subs_get", "subs", "tags", "trash",
                         "layout", "balance", "cast_ready",  # agent 1.17+: folder layout, balance, ready for Chromecast
                         "reencode"],  # agent 1.18.1+: re-encode (advanced), steps and saved sets
                # pause/resume, queue order and retry of tasks: /api/tasks/<id>/pause|resume|move|retry, /api/tasks/order
                "task_queue": True,  # agent 1.17+
                "subs_fit": mp_subsync.available(),  # subtitles fitted to the film's speech (agent 1.9+)
                "subs_retime": mp_retime.available(self.cfg),  # re-timed by Whisper, cut by cut (agent 1.20+)
                # by the rhythm of speech (any language), translations fitted to a baseline, automatic after a download,
                # /api/subs/auto, /api/config/subtitles (agent 1.21+)
                "subs_auto": mp_subsync.available(),
                "afterdl": True,  # /api/config/afterdl, /api/afterdl (agent 1.23+)
                "subs_want": True,  # torrents take sub_langs, /api/subs/list takes want (agent 1.21.3+)
                "search_engines": len(self.cfg.get("search_engines") or []),
                # the built-in "Internet Archive" site: GET /api/archive/search, POST /api/torrents {"torrent_url"} (agent 1.19+)
                "archive_search": True,
                "library_profiles": list(mp_tasks.PROFILE_NAMES),
                "player": self.is_screen(),
                "subs_search": self.subs_available(wait=False),
                "unlock": mp_power.AUTO_UNLOCK and platform.can_unlock(),
                "apk": apk_version().get("version_code", 0) > 0,
                "app_updates": self.app_updates.mode(),  # "auto" (from GitHub) or "manual" (publish.sh)
                # the agent's own updates (agent 1.6+): mode, or absent where unsupported (Windows); newer version if any
                **({"agent_updates": self.agent_updates.mode(), "agent_available": self.agent_updates.available or ""}
                   if self.agent_updates.supported() else {}),
                "wake_others": True,
                # the "for Chromecast" version: encoder name (hardware or CPU) or None (no ffmpeg)
                "transcode": mp_media.encoder_label(mp_media.encoder_info()),
                "transcode_hw": bool((mp_media.encoder_info() or {}).get("hw")),
                "upload": self.uploads.available(),  # PUT /api/upload/<id> (mp_upload.py)
                "install": self.installs.available(),  # /api/install/* and /i/<code> (mp_install.py)
                "access_requests": True,  # /api/access/* (mp_access.py)
                "mcp": True,  # /mcp for an AI assistant, OAuth approved on the phone (agent 1.6+)
            },
            "power": {"suspend": mp_power.POWER and platform.can_power(), "poweroff": mp_power.POWER and platform.can_power(),
                      "wake": self.wake_info(net)},
        }

    def ref_scope(self, d):
        """Ticket scope for a file reference (the same for a video and its subtitles)."""
        if d.get("torrent_id"):
            return f"t:{d['torrent_id'].lower()}/{int(d['file_index'])}"
        if d.get("root") is not None and d.get("root") != "":
            return f"l:{int(d['root'])}/{d.get('path', '')}"
        return None

    def _default_dirs(self):
        try:
            return [d for d in self.backend.default_dirs() if d]
        except ApiError:
            return []

    def resolve_ref(self, d):
        """A file given by a torrent (torrent_id + file_index) or by the library (root + path)."""
        if d.get("playing") in (True, "1", "true"):  # the file currently playing in mpv
            path = self.mpv.prop("path") if self.mpv.running() else None
            if not path:
                raise ApiError(T("nothing is playing on the computer", "na komputerze nic nie gra"), 409)
            if not os.path.isfile(path):
                raise ApiError(T("the video is playing from another server, search for subtitles there (in Library or at the torrent)",
                                 "film gra z innego serwera, napisy szukaj tam (w Bibliotece albo przy torrencie)"), 409)
            return path
        if d.get("torrent_id"):
            return self.resolve_file(d["torrent_id"], int(d["file_index"]))[0]
        if d.get("root") is not None and d.get("root") != "":
            return self.library.resolve(int(d["root"]), d.get("path", ""))
        raise ApiError(T("no file specified", "brak wskazania pliku"), 400)

    def ref_complete(self, d):
        """Whether the file is entirely on disk (torrent downloaded), since converting an incomplete one would produce garbage."""
        if d.get("torrent_id"):
            _, f = self.resolve_file(d["torrent_id"], int(d["file_index"]))
            return f.get("ready", 0) >= f.get("size", 1) or f.get("progress", 0) >= 100
        return True

    def _announce_mine(self):
        """What is playing on this server's screen (mpv), as a "playing" message for other remotes."""
        if not self.is_screen() or not self.mpv.running():
            return []
        st = self.mpv.status()
        if not st.get("running") or st.get("idle"):
            return []
        return [{"type": "playing", "target": {"kind": "screen", "id": self.cfg["id"], "name": self.name(),
                                                "ctl": {"agent": f"http://{lan_ip()}:{self.cfg['port']}"}},
                 "title": st.get("title") or "", "state": "PAUSED" if st.get("paused") else "PLAYING",
                 "pos": st.get("pos") or 0, "dur": st.get("duration") or 0, "ts": int(time.time())}]

    def _cast_warmup(self):
        try:
            self.casts.devices()
            time.sleep(10)
            self.casts.devices()  # devices that answered later
        except Exception as e:  # noqa: BLE001  (e.g. no pychromecast)
            log("chromecast: discovery failed to start:", e)

    def place_unnamed(self):
        """A magnet added without a name (no `dn=`) and without a folder: once the client knows the name and the files,
        it is recognized like any magnet; when the folder is certain (high confidence, a folder with that tag) the client
        moves it there (seeding goes on), otherwise it stays in the default folder with the guess saved for the app's
        "Change". Given up after an hour without metadata."""
        waiting = [j for j in self.jobs.all() if j.get("place_when_named")]
        if not waiting:
            return
        torrents = {t["id"].lower(): t for t in self.backend.list()}
        for j in waiting:
            tid = j["id"]
            t = torrents.get(tid)
            if t is None or time.time() - j["place_when_named"] > 3600:
                self.jobs.set_meta(tid, place_when_named=None)
                continue
            name = t.get("name") or ""
            if not name or name.lower() == tid:  # the client shows the hash until the metadata arrives
                continue
            files = [(f["path"], f["size"]) for f in self.backend.files(tid)[1]] or None
            if not files:
                continue
            g = self.ident.guess(name, files)
            kind = g.get("kind") if g.get("tag") in ("seriale", "bajki") else None
            tg = self.library.targets(g.get("tag"), g.get("title"), kind)
            pick = tg["pick"]
            meta = {"place_when_named": None, "guess": g, "name": name}
            if pick is not None and g.get("confidence") == "high":
                t2 = tg["targets"][pick]
                dest = self.library.dest(t2["root"], t2["sub"])
                self.backend.move(tid, dest)
                meta["dest"] = dest
                log("unnamed magnet recognized:", tid, name, "→", dest)
            else:
                log("unnamed magnet recognized, folder unsure:", tid, name, g.get("tag"), g.get("confidence"))
            self.jobs.set_meta(tid, **meta)

    def ticket_urls(self, b, base, variant=None):
        """URLs of the video and subtitles with a ticket for another device (screen, Chromecast, DLNA TV)."""
        full = self.resolve_ref(b)
        scope = self.ref_scope(b)
        if not scope:
            raise ApiError(T("tickets only for a file from a torrent or the library", "bilet tylko na plik z torrenta albo biblioteki"), 400)
        ticket, exp = self.devices.ticket(scope)
        tq = "ticket=" + urllib.parse.quote(ticket)
        if b.get("torrent_id"):
            url = f"{base}/media/{b['torrent_id']}/{int(b['file_index'])}/{urllib.parse.quote(os.path.basename(full))}?{tq}"
            rq = f"torrent_id={b['torrent_id']}&file_index={int(b['file_index'])}"
        else:
            path = b.get("path", "")
            url = f"{base}/lib/{int(b['root'])}/{urllib.parse.quote(path)}?{tq}"
            rq = f"root={int(b['root'])}&path={urllib.parse.quote(path)}"
        cast_v = variant == "cast" and mp_media.variant(full)
        if cast_v:
            url += "&v=cast"
        subs = [{"name": x["name"], "lang": x.get("lang"), "default": bool(x.get("default")),
                 "url": f"{base}/sub?{rq}&name={urllib.parse.quote(x['name'])}&{tq}",
                 "vtt": f"{base}/sub?{rq}&name={urllib.parse.quote(x['name'])}&vtt=1&{tq}"} for x in sidecar_subs(full)]
        return {"url": url, "subs": subs, "title": os.path.basename(full), "expires": exp,
                "variant": "cast" if cast_v else None, "content_type": "video/mp4" if cast_v else None}

    def machine_action(self, tid, action):
        if tid:
            self.backend.action(tid, action)
        elif action == "suspend":
            suspend(self.guard)
        elif action == "poweroff":
            poweroff(self.guard)

    def downloading_part(self, full):
        """A library file that belongs to a torrent still downloading: (torrent id, file index), else None. /lib/
        then serves only downloaded pieces, as /media/ does: Deluge keeps undownloaded parts as zeros (sparse files),
        and a phone that copied them kept a "whole" film with a broken end. The list of unfinished torrents is cached
        for a few seconds (phones probe /lib/ every 1.5-3 s)."""
        now = time.time()
        cached = getattr(self, "_dl_parts", None)
        if not cached or now - cached[0] > 5:
            try:
                parts = [(t["id"], os.path.realpath(os.path.join(t["save_path"], t["name"])))
                         for t in self.backend.list() if t.get("progress", 100) < 100 and t.get("save_path")]
            except Exception:
                parts = []  # client not answering: serve as before rather than fail the stream
            cached = self._dl_parts = (now, parts)
        real = os.path.realpath(full)
        for tid, top in cached[1]:
            if real == top or real.startswith(top + os.sep):
                try:
                    save_path, files = self.backend.files(tid)
                except Exception:
                    return None
                base = os.path.realpath(save_path)
                for f in files:
                    if os.path.realpath(os.path.join(base, f["path"])) == real and f.get("ready", 0) < f.get("size", 0):
                        return tid, f["index"]
        return None

    def resolve_file(self, tid, index):
        save_path, files = self.backend.files(tid)
        f = next((f for f in files if f["index"] == index), None)
        if not f:
            raise ApiError(T("no such file", "nie ma takiego pliku"), 404)
        base = os.path.realpath(save_path)
        full = os.path.realpath(os.path.join(base, f["path"]))
        if not full.startswith(base + os.sep):
            raise ApiError(T("path outside the download folder", "ścieżka poza katalogiem pobierania"), 403)
        return full, f


class Handler(BaseHTTPRequestHandler):
    server_version = f"MagneticPlayer/{VERSION}"
    protocol_version = "HTTP/1.1"
    version = VERSION  # /api/ping (mp_api_system)
    app: App = None
    routes = None  # mp_routes.build(app), set in main()

    def log_message(self, fmt, *args):
        if not self.path.startswith(("/api/player/status", "/api/torrents?")):
            log(self.client_address[0], re.sub(r"(token|ticket)=[^&\s\"]+", r"\1=…", fmt % args))

    def send_json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False, default=json_default).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def mcp_route(self, method, p, qd, q):
        a, base = self.app, self.base_url()
        try:
            if p.startswith("/.well-known/oauth-protected-resource") and method == "GET":
                return self.send_json(a.mcp.metadata_resource(base))
            if p.startswith("/.well-known/oauth-authorization-server") and method == "GET":
                return self.send_json(a.mcp.metadata_server(base))
            if p == "/oauth/register" and method == "POST":
                return self.send_json(a.mcp.register(self.body()), 201)
            if p == "/oauth/authorize" and method == "GET":
                return self.send_bytes(a.mcp.authorize_page(qd, self.client_address[0]).encode(), "text/html; charset=utf-8")
            if p == "/oauth/authorize/wait" and method == "GET":
                return self.send_json(a.mcp.authorize_wait(qd.get("session", "")))
            if p == "/oauth/token" and method == "POST":
                n = int(self.headers.get("Content-Length") or 0)
                self.body_read = True
                raw = self.rfile.read(n).decode() if n else ""
                form = json.loads(raw) if raw.lstrip().startswith("{") else dict(urllib.parse.parse_qsl(raw))
                self.extra_headers.append(("Cache-Control", "no-store"))
                return self.send_json(a.mcp.token(form))
            if p == "/mcp":
                self.device = self.authorized(q)
                if not self.device:
                    self.extra_headers.append(("WWW-Authenticate", f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource"'))
                    return self.send_json({"error": "invalid_token"}, 401)
                role = a.devices.role(self.device)
                mp_library.set_viewer(self.device, role)
                if method != "POST":
                    self.extra_headers.append(("Allow", "POST"))
                    return self.send_json({"error": "POST only (no server-initiated stream)"}, 405)
                msg = self.body()
                out = [r for r in (a.mcp.handle(m, role) for m in (msg if isinstance(msg, list) else [msg])) if r]
                if not out:
                    self.send_response(202)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                return self.send_json(out if isinstance(msg, list) else out[0])
            raise ApiError(T("no such endpoint", "nie ma takiego endpointu"), 404)
        except mp_mcp.McpError as e:
            return self.send_json({"error": "invalid_request", "error_description": str(e)}, e.code if 400 <= e.code < 600 else 400)
        except mp_access.AccessError as e:
            return self.send_json({"error": "access_denied", "error_description": str(e)}, e.status)

    def authorized(self, query):
        """Id of the paired device (or "legacy" for the old shared token), or None."""
        h = self.headers.get("Authorization", "")
        given = h[7:] if h.startswith("Bearer ") else (query.get("token") or [""])[0]
        return self.app.devices.check(given)

    def ticket_ok(self, p, q):
        """A ticket (?ticket=) opens only one file and its subtitles: /media, /lib, /sub."""
        t = (q.get("ticket") or [""])[0]
        if not t:
            return False
        m = re.fullmatch(r"/media/([0-9a-fA-F]+)/(\d+)(/.*)?", p)
        if m:
            scope = self.app.ref_scope({"torrent_id": m.group(1), "file_index": m.group(2)})
        elif (m := re.fullmatch(r"/lib/(\d+)/(.+)", p)):
            scope = self.app.ref_scope({"root": m.group(1), "path": urllib.parse.unquote(m.group(2))})
        elif p == "/sub":
            scope = self.app.ref_scope({k: v[0] for k, v in q.items()})
        else:
            return False
        return bool(scope) and self.app.devices.check_ticket(t, scope)

    def base_url(self):
        """This server's address as the client sees it (for URLs for another screen on the same network)."""
        host = self.headers.get("Host") or f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        return "http://" + host

    def device_name(self):
        """Name of the asking device (paired phone), or None."""
        dev = getattr(self, "device", None)
        return next((d["name"] for d in self.app.devices.list() if d["id"] == dev), None) if dev else None

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.body_read = True
        return json.loads(self.rfile.read(n)) if n else {}

    def _drain(self, path):
        """A handler that ignored the request body ("Empty trash" with `{}`) left it in the connection: the next
        request on it was read as "{}GET /api/library/disks" and got 501, so the app's list did not refresh.
        Read it now; a large one (or one an upload handler streamed itself) closes the connection instead."""
        n = int(self.headers.get("Content-Length") or 0)
        if not n or getattr(self, "body_read", False) or path.startswith("/api/upload/"):
            return
        if n > 1 << 20:
            self.close_connection = True
            return
        try:
            self.rfile.read(n)
        except OSError:
            self.close_connection = True

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_PUT(self):
        self.dispatch("PUT")

    def do_OPTIONS(self):
        # preflight from web players (Chromecast) before Range on /media, /lib, /sub
        self.send_response(204)
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Range, Authorization")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def end_headers(self):
        # files (video, subtitles) are also read by the Chromecast receiver from another origin; without CORS it skips subtitles
        if urllib.parse.urlsplit(self.path).path.startswith(("/media/", "/lib/", "/sub")) or self.command == "OPTIONS":
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, X-Ready-End")
        for k, v in getattr(self, "extra_headers", ()):
            self.send_header(k, v)
        self.extra_headers = []
        super().end_headers()

    def do_HEAD(self):
        self.dispatch("HEAD")

    def caption_header(self, q, rq):
        """Samsung (DLNA) asks for subtitles with the getcaptionInfo.sec header; we give the first SRT next to the video (`&cap=`)."""
        cap, t = (q.get("cap") or [""])[0], (q.get("ticket") or [""])[0]
        if cap and t and self.headers.get("getcaptionInfo.sec"):
            self.extra_headers.append(("CaptionInfo.sec", f"{self.base_url()}/sub?{rq}&name={urllib.parse.quote(cap)}"
                                                          f"&ticket={urllib.parse.quote(t)}"))

    def dispatch(self, method):
        # streams and uploads: a self-update and library encodes wait until they have been quiet for a while (App.busy).
        # Only requests that carry data count: the app's HEAD probes (size, X-Ready-End) keep coming every few
        # seconds while it plays from its own buffer or a downloaded copy, and would pause tasks for nothing.
        if method == "HEAD" or not urllib.parse.urlsplit(self.path).path.startswith(("/lib/", "/media/", "/api/upload/")):
            return self._dispatch(method)
        a = self.app
        with a.stream_lock:
            a.streams += 1
        try:
            return self._dispatch(method)
        finally:
            with a.stream_lock:
                a.streams -= 1
                a.stream_last = time.time()

    def _dispatch(self, method):
        self.body_read = False
        try:
            return self._dispatch_inner(method)
        finally:
            self._drain(urllib.parse.urlsplit(self.path).path)

    def _dispatch_inner(self, method):
        self.extra_headers = []
        mp_i18n.set_from_header(self.headers.get("Accept-Language"))  # message language (errors in JSON)
        u = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(u.query)
        p = u.path.rstrip("/")
        ctx = {"method": method, "p": p, "q": q, "qd": {k: v[0] for k, v in q.items()}}
        try:
            a = self.app
            # no token: ping, the web page, pairing, installing on a new computer, access requests, OAuth/MCP
            hit = mp_routes.find(self.routes, method, p, public=True)
            if hit:
                return hit[0](self, a, **ctx, **hit[1])
            mp_library.set_viewer(None)
            self.device = self.authorized(q)
            if not self.device and not (method in ("GET", "HEAD") and self.ticket_ok(p, q)):
                raise ApiError(T("invalid token", "zły token"), 401)
            self.role = a.devices.role(self.device)
            mp_library.set_viewer(self.device, self.role)  # library folder visibility for this device
            if self.role == "guest" and not guest_allowed(method, p):
                raise ApiError(T("guests don't have access to this (ask a household member)", "gość nie ma do tego dostępu (poproś domownika)"), 403)
            hit = mp_routes.find(self.routes, method, p, public=False)
            if hit:
                return hit[0](self, a, **ctx, **hit[1])
            raise ApiError(T("no such endpoint", "nie ma takiego endpointu"), 404)
        except (LibError, SubsError, CastError, mp_install.InstallError, mp_access.AccessError, mp_tasks.TaskError,
                mp_upload.UploadError) as e:
            self.send_json({"error": str(e)}, e.status)
        except ApiError as e:
            self.send_json({"error": str(e)}, e.status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # noqa: BLE001
            log("error:", self.path.split("?")[0], repr(e))
            log(traceback.format_exc().rstrip())
            try:  # plain words for the user, the exception for details (agent 1.18.8+: was the exception alone)
                self.send_json({"error": T("Something went wrong on the computer (an error in the agent).",
                                           "Coś poszło nie tak na komputerze (błąd w agencie)."), "detail": repr(e)}, 500)
            except OSError:
                pass

    def send_bytes(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_static(self, path, ctype, head_only=False, cache="no-cache", headers=None):
        try:
            with open(path, "rb") as f:
                body = f.read()
        except OSError:
            raise ApiError(T("the web page file is missing — run install.sh again", "brak pliku strony — uruchom ponownie install.sh"), 404)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def send_vtt(self, path, video=None):
        text = readable_srt(path, video)
        if mp_subflow.settings(self.app.cfg)["hide_descriptions"]:
            text = strip_descriptions(text)
        if not path.lower().endswith(".vtt"):
            text = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text)
            text = "WEBVTT\n\n" + text
        self.send_text(text, "text/vtt")

    def send_text(self, text, ctype, head_only=False):
        body = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    STALL_WAIT = 20  # seconds of waiting for further pieces, then we drop the connection (the player retries)

    def stream(self, full, head_only, pieces=None, refresh=None, watch=None):
        if watch:  # which video is in use (shrink tasks slow down on its disk and don't swap it): App.watched
            with self.app.stream_lock:
                w = self.app.watch.setdefault(os.path.realpath(watch), {"active": 0, "last": 0.0, "read": 0.0})
                w["active"] += 0 if head_only else 1
            try:
                return self._stream_dbg(full, head_only, pieces, refresh)
            finally:
                with self.app.stream_lock:
                    now = time.time()
                    w["last"] = now
                    if not head_only:
                        w["active"] -= 1
                        w["read"] = now
                if not head_only:
                    self.app.tasks.note_played(watch)  # "watched" for free up and subtitles (1.16+)
        return self._stream_dbg(full, head_only, pieces, refresh)

    def _stream_dbg(self, full, head_only, pieces=None, refresh=None):
        # diagnostics: `touch ~/.config/magneticplayer/debug-stream` logs every request (range, time, bytes sent)
        if not os.path.exists(os.path.join(os.path.dirname(CONFIG_PATH), "debug-stream")):
            return self._stream(full, head_only, pieces, refresh)
        t0, self._sent = time.time(), 0
        try:
            return self._stream(full, head_only, pieces, refresh)
        finally:
            log("stream", self.client_address[0], self.command, os.path.basename(full)[-30:],
                self.headers.get("Range"), f"{self._sent >> 10} KB", f"{time.time() - t0:.1f}s")

    def _stream(self, full, head_only, pieces=None, refresh=None):
        if not os.path.isfile(full):
            raise ApiError(T("the file isn't on disk yet", "pliku jeszcze nie ma na dysku"), 404)
        size = os.path.getsize(full)
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith(".apk"):
            ctype = "application/vnd.android.package-archive"
        dlna = self.headers.get("getcontentFeatures.dlna.org") or "dlna" in (self.headers.get("User-Agent") or "").lower()
        if full.lower().endswith(".mkv"):
            ctype = "video/x-mkv" if dlna else "video/x-matroska"  # Samsung (DLNA) doesn't know x-matroska
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        status = 200
        if rng:
            m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(0, size - int(m.group(2)))
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        # the phone keeps it with a downloaded copy: a later change (torrent finished, file re-encoded) means the copy
        # is stale
        self.send_header("Last-Modified", formatdate(os.path.getmtime(full), usegmt=True))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        # up to which byte we can read from `start` without waiting (the app's on-disk buffer fetches only that much)
        self.send_header("X-Ready-End", str(ready_end(pieces, start, size)))
        if pieces is not None and pieces[2] is not None:  # part of a torrent still downloading: the file will change
            self.send_header("X-Downloading", "1")
        if dlna:  # without this, DLNA TVs can get stuck in TRANSITIONING
            self.send_header("transferMode.dlna.org", "Streaming")
            self.send_header("contentFeatures.dlna.org", DLNA_FEATURES)
        self.end_headers()
        if head_only:
            return
        pos, stop = start, end + 1
        with open(full, "rb") as f:
            f.seek(start)
            while pos < stop:
                avail = ready_end(pieces, pos, size)
                waited = 0
                while avail <= pos and refresh and waited < self.STALL_WAIT:
                    time.sleep(1)
                    waited += 1
                    pieces = refresh()
                    avail = ready_end(pieces, pos, size)
                if avail <= pos:
                    self.close_connection = True  # shorter than Content-Length: the client knows to retry
                    return
                chunk = f.read(min(1 << 20, avail - pos, stop - pos))
                if not chunk:
                    break
                self.wfile.write(chunk)
                pos += len(chunk)
                self._sent = getattr(self, "_sent", 0) + len(chunk)


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True
    # Windows: SO_REUSEADDR lets us take a port someone is already listening on (e.g. on 127.0.0.1), and local
    # requests then go to that listener; there the port must be exclusively ours
    allow_reuse_address = not WINDOWS

    def server_bind(self):
        if WINDOWS:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, TimeoutError)):
            return  # the phone dropped the connection (seeking, closing the player): normal
        super().handle_error(request, client_address)


def cli_pair(cfg, devices):
    """Pairing code for the first phone (or when no paired one is at hand): a QR in the terminal."""
    code, ttl = devices.new_code()
    host = lan_ip()
    name = cfg.get("name") or socket.gethostname()
    link = "magneticplayer://pair?" + urllib.parse.urlencode(
        {"host": host, "port": cfg["port"], "code": code, "id": cfg["id"], "name": name})
    print(T(f"Pair a phone with “{name}”: in the MagneticPlayer app, Settings → “Add server”,",
            f"Sparuj telefon z „{name}”: w apce MagneticPlayer Ustawienia → „Dodaj serwer”,"))
    print(T(f"scan the code with the camera or type it in. The code works once, for {ttl // 60} min.\n",
            f"zeskanuj kod aparatem albo wpisz ręcznie. Kod działa raz, przez {ttl // 60} min.\n"))
    if shutil.which("qrencode"):
        subprocess.run(["qrencode", "-t", "ANSIUTF8", link])
    else:
        try:  # lite mode: the qrcode module from the agent's venv (install.sh calls --pair with the venv's python)
            import qrcode
            q = qrcode.QRCode(border=2)
            q.add_data(link)
            q.print_ascii(invert=True)
        except ImportError:
            print(T("(QR in the terminal after: sudo apt install qrencode)", "(QR w terminalu po: sudo apt install qrencode)"))
    print(T(f"  address: {host}   port: {cfg['port']}   code: {code[:3]}-{code[3:]}",
            f"  adres: {host}   port: {cfg['port']}   kod: {code[:3]}-{code[3:]}"))
    print(f"  link:  {link}")
    print(T(f"  browser: http://{host}:{cfg['port']}/#p={code}", f"  przeglądarka: http://{host}:{cfg['port']}/#p={code}"))


def cli_join(cfg, devices, url):
    """After installing from another server (`JOIN=<server>/i/<code>`): register with a pairing code for the phone."""
    m = re.fullmatch(r"(https?://[^/]+)/i/([A-Za-z0-9-]+)/?", url.strip())
    if not m:
        print(T(f"invalid join address: {url}", f"zły adres dołączenia: {url}"))
        return 1
    pair_code, _ = devices.new_code()
    body = json.dumps({"code": m.group(2), "id": cfg["id"], "name": cfg.get("name") or socket.gethostname(),
                       "port": cfg["port"], "pair_code": pair_code, "version": VERSION}).encode()
    req = urllib.request.Request(m.group(1) + "/api/install/join", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "Accept-Language": mp_i18n.lang()})
    try:
        with urllib.request.urlopen(req, timeout=10):
            pass
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read()).get("error")
        except ValueError:
            err = e.reason
        print(T(f"Couldn't register with {m.group(1)}: {err}", f"Nie udało się zgłosić do {m.group(1)}: {err}"))
        return 1
    except OSError as e:
        print(T(f"Couldn't register with {m.group(1)}: {e}", f"Nie udało się zgłosić do {m.group(1)}: {e}"))
        return 1
    print(T(f"Registered with {m.group(1)}: the phone that started the install will pair by itself (within 10 min).",
            f"Zgłoszono się do {m.group(1)}: telefon, który zaczął instalację, sparuje się sam (do 10 min)."))
    return 0


def main():
    if sys.stderr is None or sys.stdout is None:  # pythonw on Windows (autostart): no console, log to a file
        f = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "agent.log"), "a", encoding="utf-8", buffering=1)
        sys.stderr = sys.stderr or f
        sys.stdout = sys.stdout or f
    cfg = load_config()
    mp_i18n.set_from_env()  # terminal messages (--pair, --join, --devices); HTTP requests have their own language
    if "--print-token" in sys.argv:
        print(cfg["token"])
        return
    devices = Devices(os.path.join(CONFIG_DIR, "devices.json"), cfg["token"])
    if "--pair" in sys.argv:
        return cli_pair(cfg, devices)
    if "--join" in sys.argv:
        i = sys.argv.index("--join")
        sys.exit(cli_join(cfg, devices, sys.argv[i + 1] if i + 1 < len(sys.argv) else ""))
    if "--devices" in sys.argv:
        for d in devices.list():
            seen = time.strftime("%Y-%m-%d %H:%M", time.localtime(d["last_seen"])) if d.get("last_seen") else T("never", "nigdy")
            print(f"{d['id']:14} {d['name']:30} " + T("last seen: ", "ostatnio: ") + seen)
        return
    if "--revoke" in sys.argv:
        i = sys.argv.index("--revoke")
        dev = sys.argv[i + 1] if i + 1 < len(sys.argv) else ""
        print(T("access revoked", "odebrano dostęp") if devices.revoke(dev)
              else T(f"no device {dev!r} (list: --devices)", f"nie ma urządzenia {dev!r} (lista: --devices)"))
        return
    app = App(cfg)
    Handler.app = app
    Handler.routes = mp_routes.build(app)  # fails here, not in a request, if a route points at nothing
    if cfg.get("keep_awake", True):
        app.guard.start()
    srv = QuietServer(("0.0.0.0", int(cfg["port"])), Handler)
    srv.daemon_threads = True
    log(f"MagneticPlayer {VERSION} on port {cfg['port']}, backend {cfg['backend']}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
