"""After download (agent 1.23+, docs/plan-after-download.md): the owner's steps for every new video, whichever way it
was downloaded (the app, archive.org, or straight in the torrent client on the computer).

A conductor only: every step is done by what already exists.
- `move`: the folder from the title (`Library.targets`: the folder tag, the disk with most free space, a series' own
  folder), done as a one-item move task (by the torrent client while it seeds; journalled for the phones);
- `subs`: mp_subflow's queue (baseline in the film's language, the wanted languages fitted to it);
- `cast_ready`: a one-item "ready for Chromecast" task (AAC sound, text subtitles as .srt, AVI → MKV);
- `shrink`: a one-item compression task, only above the step's size or bitrate;
- `seed_end`: a boundary: when the torrent reached the ratio or the seeding time (never before the minimum), it is
  paused or removed from the client (files stay), and the steps below start.
The order is free. A step that changes the file (`shrink`, `cast_ready` in place) while the torrent still seeds ends
seeding first and says so in the history; `effects()` labels every step with what it costs, for the app's warnings.

Every video is its own run (an episode too), with its own history. Runs are persisted in `afterdl.json` and go on
after a restart; a step that waits (seeding, a queued task, subtitles in their queue) is checked every tick (~20 s).
Torrents complete when the feature first runs are the existing library: they are marked seen and left alone.

Decisions are made by the server ("auto" in the history). Questions to the downloader's phone are stage 2.
"""
import copy
import json
import os
import secrets
import threading
import time

import mp_i18n
import mp_ident
from mp_i18n import T
from mp_library import VIDEO_EXT

MIN_SIZE = 50 * 1024 * 1024  # samples and extras are left alone
KEEP_RUNS = 2000
MOVE_TIMEOUT = 12 * 3600
FAILS = 30  # a step that keeps raising (the client restarting, a disk spinning up) fails after ~10 minutes of ticks

KINDS = ("move", "subs", "cast_ready", "shrink", "seed_end")
SOURCES = ("app", "client", "archive")

# every step's options and their defaults (a recipe's step is {"kind": …, **options})
STEP_DEFAULTS = {
    "move": {},
    "subs": {"langs": [], "downloader": True},  # always these + the downloader's languages
    "cast_ready": {"mode": "keep_seeding", "add_aac": True, "subs_to_srt": True, "avi_to_mkv": True},
    "shrink": {"min_gb": 8.0, "min_mbps": 6.0, "quality": "balanced", "encoder": "auto", "skip_hevc_av1": True},
    "seed_end": {"ratio": 2.0, "days": 7.0, "min_days": 1.0, "then": "remove"},
}
CHOICES = {("cast_ready", "mode"): ("keep_seeding", "in_place"),
           ("shrink", "quality"): ("transparent", "balanced", "saving"),
           ("shrink", "encoder"): ("auto", "cpu", "gpu"),
           ("seed_end", "then"): ("pause", "remove")}


def _recipe(*steps):
    return [dict(STEP_DEFAULTS[k], kind=k, **o) for k, o in steps]


# starting points (the prototype's templates); "versions beside the file" are stage 3, so the phone and Chromecast
# templates are one for now
TEMPLATES = {
    "right_away": _recipe(("subs", {}), ("cast_ready", {}), ("move", {}), ("seed_end", {}), ("shrink", {})),
    "free_gpu": _recipe(("move", {}), ("seed_end", {}), ("shrink", {}), ("subs", {}), ("cast_ready", {})),
    "archive": _recipe(("shrink", {"min_gb": 2.0, "min_mbps": 3.0}), ("subs", {}), ("move", {})),
    "seeder": _recipe(("move", {}), ("subs", {}), ("cast_ready", {}), ("seed_end", {"ratio": 3.0, "days": 30.0}),
                      ("shrink", {})),
}
TEMPLATE_TITLES = {"right_away": ("Right away on the phone or Chromecast", "Od razu na telefon lub Chromecast"),
                   "free_gpu": ("Free graphics card", "Wolna karta graficzna"),
                   "archive": ("Archive: free space quickly", "Archiwum: szybko zwolnić miejsce"),
                   "seeder": ("Seeder", "Seeder")}

DEFAULTS = {"enabled": False, "sources": list(SOURCES),
            "recipes": {"film": TEMPLATES["right_away"], "series": TEMPLATES["right_away"]},
            "answer_after_h": 6.0}


def _tx(en, pl):
    """A text stored in both languages (history, notes); `_say` picks the reader's."""
    return {"en": en, "pl": pl}


def _say(x):
    return (x or {}).get(mp_i18n.lang()) or (x or {}).get("en") if x else None


def _opts(step):
    """A run's step options with today's defaults for what it lacks (runs outlive versions)."""
    return dict(STEP_DEFAULTS[step["kind"]], **step.get("opts") or {})


def _num(x):
    return f"{x:g}".replace(".", ",") if mp_i18n.lang() == "pl" else f"{x:g}"


# ---- settings

def settings(cfg):
    s = copy.deepcopy(DEFAULTS)
    mine = cfg.get("afterdl") or {}
    s.update({k: copy.deepcopy(v) for k, v in mine.items() if k in DEFAULTS and k != "recipes"})
    for kind, steps in (mine.get("recipes") or {}).items():  # a kind not set keeps the default recipe
        if kind in s["recipes"] and isinstance(steps, list):  # a hand-edited step gets the options it lacks
            s["recipes"][kind] = [dict(STEP_DEFAULTS[x["kind"]], **x) for x in steps
                                  if isinstance(x, dict) and x.get("kind") in KINDS]
    return s


def _check_step(raw):
    if not isinstance(raw, dict) or raw.get("kind") not in KINDS:
        raise ValueError("step.kind: one of " + ", ".join(KINDS))
    kind, out = raw["kind"], dict(STEP_DEFAULTS[raw["kind"]], kind=raw["kind"])
    for k, v in raw.items():
        if k == "kind":
            continue
        if k not in STEP_DEFAULTS[kind]:
            raise ValueError(f"unknown option: {kind}.{k}")
        want = type(STEP_DEFAULTS[kind][k])
        if want is float and isinstance(v, int) and not isinstance(v, bool):
            v = float(v)
        if not isinstance(v, want) or (want is list and not all(isinstance(x, str) and 2 <= len(x) <= 3 for x in v)):
            raise ValueError(f"{kind}.{k}: expected {want.__name__}")
        if want is float and not 0 <= v <= 10000:
            raise ValueError(f"{kind}.{k}: out of range")
        if (kind, k) in CHOICES and v not in CHOICES[(kind, k)]:
            raise ValueError(f"{kind}.{k}: one of {', '.join(CHOICES[(kind, k)])}")
        out[k] = [x.lower() for x in v][:3] if want is list else v
    return out


def set_settings(app, changes):
    """Validated changes (API, MCP); `recipes.<kind>` is a list of steps or a template's name. → the settings."""
    cur = dict(app.cfg.get("afterdl") or {})
    for k, v in (changes or {}).items():
        if k not in DEFAULTS:
            raise ValueError(f"unknown setting: afterdl.{k}")
        if v is None:
            cur.pop(k, None)
        elif k == "enabled":
            if not isinstance(v, bool):
                raise ValueError("afterdl.enabled: expected bool")
            cur[k] = v
        elif k == "sources":
            if not isinstance(v, list) or any(x not in SOURCES for x in v):
                raise ValueError("afterdl.sources: a list of " + ", ".join(SOURCES))
            cur[k] = list(dict.fromkeys(v))
        elif k == "answer_after_h":
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0 < v <= 168:
                raise ValueError("afterdl.answer_after_h: hours, 0-168")
            cur[k] = float(v)
        elif k == "recipes":
            if not isinstance(v, dict) or any(x not in ("film", "series") for x in v):
                raise ValueError("afterdl.recipes: {film, series}")
            rec = dict(cur.get("recipes") or DEFAULTS["recipes"])
            for kind, steps in v.items():
                if isinstance(steps, str):
                    if steps not in TEMPLATES:
                        raise ValueError("template: one of " + ", ".join(TEMPLATES))
                    steps = TEMPLATES[steps]
                if not isinstance(steps, list) or len(steps) > 10:
                    raise ValueError(f"afterdl.recipes.{kind}: a list of at most 10 steps")
                steps = [_check_step(x) for x in steps]
                if sum(x["kind"] == "seed_end" for x in steps) > 1:
                    raise ValueError(f"afterdl.recipes.{kind}: \"seed_end\" at most once")
                rec[kind] = steps
            cur[k] = rec
    app.cfg["afterdl"] = cur
    app.save_cfg()
    return public_settings(app.cfg)


def public_settings(cfg):
    s = settings(cfg)
    return dict(s, effects={k: effects(v) for k, v in s["recipes"].items()},
                templates=[{"id": k, "title": T(*TEMPLATE_TITLES[k]), "steps": v,
                            "effects": effects(v)} for k, v in TEMPLATES.items()])


def changes_file(step):
    return step["kind"] == "shrink" or (step["kind"] == "cast_ready" and step.get("mode") == "in_place")


def effects(steps):
    """What every step of a recipe costs, in order: [{"labels": [codes], "warning": text or None}].
    Labels: `keeps_file` or `changes_file`, `ends_seeding` (it changes the file while the torrent may still seed), `gpu`, `moves`
    (seeding pauses while the client moves), `no_effect` (an "End seeding" below a step that already ended it)."""
    out, ended = [], None
    seed_at = next((n for n, s in enumerate(steps) if s["kind"] == "seed_end"), None)
    for n, s in enumerate(steps):
        labels, warn = [], None
        if changes_file(s):
            labels.append("changes_file")
            if ended is None:
                labels.append("ends_seeding")
                ended = n
                if seed_at is not None and n < seed_at:
                    warn = T("Above \"End seeding\": seeding ends at this step, at whatever ratio it reached",
                             "Nad „Zakończ seedowanie”: seedowanie skończy się na tym kroku, przy takim ratio, jakie będzie")
        else:
            labels.append("keeps_file")
        if s["kind"] == "shrink" and s.get("encoder") != "cpu":
            labels.append("gpu")
        if s["kind"] == "move":
            labels.append("moves")
        if s["kind"] == "seed_end":
            if ended is not None:
                labels.append("no_effect")
                warn = T(f"Seeding already ends at step {ended + 1}", f"Seedowanie kończy się już na kroku {ended + 1}")
            ended = n if ended is None else ended
        out.append({"labels": labels, "warning": warn})
    return out


# ---- the runs

class AfterDownload:
    def __init__(self, app, config_dir, log):
        self.app, self.log = app, log
        self.path = os.path.join(config_dir, "afterdl.json")
        self.lock = threading.Lock()
        try:
            with open(self.path) as f:
                self.st = json.load(f)
        except (OSError, ValueError):
            self.st = {}
        self.st.setdefault("seen", None)    # torrent ids seen complete (None: not yet initialised)
        self.st.setdefault("runs", [])      # newest first
        self.st.setdefault("torrents", {})  # torrent id → where it was decided to go, when seeding ended
        self._tries = {}  # torrent id → failed starts (in memory)
        self._now = {}  # torrent id → the client's torrent, this tick

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.st, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    @property
    def cfg(self):
        return settings(self.app.cfg)

    def enabled(self):
        return self.cfg["enabled"]

    # ---- new torrents (SleepGuard, every ~20 s)

    def tick(self, torrents):
        s = self.cfg
        if not s["enabled"]:
            return
        by_id = {t["id"].lower(): t for t in torrents}
        complete = [tid for tid, t in by_id.items() if t["progress"] >= 100]
        with self.lock:
            if self.st["seen"] is None:  # first run: what is complete now is the existing library, not new
                self.st["seen"] = sorted(complete)
                self._save()
                return
            new = [by_id[tid] for tid in complete if tid not in self.st["seen"]]
        for t in new:
            tid = t["id"].lower()
            try:
                self._start(t, s)
            except Exception as e:  # noqa: BLE001 — tried again next tick, given up after FAILS
                self._tries[tid] = self._tries.get(tid, 0) + 1
                self.log("after download:", t.get("name"), "not started:", e)
                if self._tries[tid] < FAILS:
                    continue
            with self.lock:
                self.st["seen"].append(tid)
                del self.st["seen"][:-3000]
                self._save()
        self._now = by_id  # the client's torrents this tick; ending seeding updates it for the steps after
        for run in [r for r in self.st["runs"] if r["state"] == "running"]:
            try:
                self._advance(run)
            except Exception as e:  # noqa: BLE001 — the same step again next tick
                step = run["steps"][min(run["step"], len(run["steps"]) - 1)]
                step["fails"] = step.get("fails", 0) + 1
                self.log("after download:", os.path.basename(run["file"]), step["kind"], "failed:", e)
                if step["fails"] >= FAILS:
                    self._finish_step(run, "error", _tx(str(e)[:200], str(e)[:200]))

    def _source(self, job):
        if job and job.get("torrent_url"):
            return "archive"
        return "app" if job and job.get("magnet") else "client"

    def _start(self, t, s):
        tid = t["id"].lower()
        job = self.app.jobs.get(tid) or {}
        source = self._source(job)
        if source not in s["sources"]:
            return
        save_path, files = self.app.backend.files(tid)
        # only what was downloaded: an archive.org item wants one file, the rest are in the torrent at 0 %
        vids = [f for f in files if os.path.splitext(f["path"])[1].lower() in VIDEO_EXT and f["size"] >= MIN_SIZE
                and f.get("progress", 100) >= 100 and not mp_ident.is_extra(f["path"])]
        if not vids:
            return
        g = job.get("guess") or self.app.ident.guess(t["name"], [(f["path"], f["size"]) for f in files])
        kind = "series" if g.get("kind") == "series" or len(vids) > 1 else "film"
        with self.lock:
            self.st["torrents"][tid] = {"name": t["name"], "save_path": save_path, "guess": g,
                                        # the videos' own top entry (an archive.org torrent also lists unwanted files)
                                        "top": vids[0]["path"].replace("\\", "/").split("/")[0],
                                        "dest": job.get("dest")}
            for f in sorted(vids, key=lambda f: f["path"]):
                run = {"id": secrets.token_hex(4), "tid": tid, "torrent": t["name"],
                       "file": os.path.join(save_path, f["path"]), "size": f["size"], "source": source,
                       "by": job.get("by"), "kind": kind, "created": time.time(), "state": "running", "step": 0,
                       "steps": [{"kind": x["kind"], "opts": {k: v for k, v in x.items() if k != "kind"},
                                  "state": "pending"} for x in s["recipes"][kind]],
                       "events": []}
                self._event(run, _tx("Downloaded", "Pobrane"))
                self.st["runs"].insert(0, run)
            self._prune()
            self._save()
        self.log(f"after download: {t['name']}: {len(vids)} videos, {source}, {kind}")

    def _prune(self):
        runs = self.st["runs"]
        if len(runs) > KEEP_RUNS:
            done = [r for r in runs if r["state"] != "running"]
            drop = {r["id"] for r in done[KEEP_RUNS // 2:]}
            self.st["runs"] = [r for r in runs if r["id"] not in drop]
            live = {r["tid"] for r in self.st["runs"]}
            self.st["torrents"] = {k: v for k, v in self.st["torrents"].items() if k in live}

    # ---- history

    @staticmethod
    def _event(run, text, step=None, auto=False):
        run["events"].append({"at": time.time(), "text": text, **({"step": step} if step is not None else {}),
                              **({"auto": True} if auto else {})})
        del run["events"][:-100]

    # ---- one run

    def _advance(self, run):
        """Does the run's steps until one has to wait."""
        for _ in range(len(run["steps"]) + 1):
            if run["state"] != "running":
                return
            if run["step"] >= len(run["steps"]):
                with self.lock:
                    run["state"] = "done"
                    run["finished"] = time.time()
                    self._event(run, _tx("All steps done", "Wszystkie kroki zrobione"))
                    self._save()
                return
            step = run["steps"][run["step"]]
            if step["state"] == "pending":
                with self.lock:
                    step.update(state="running", since=time.time())
                    self._save()
            out = getattr(self, "_step_" + step["kind"])(run, step, self._now.get(run["tid"]))
            if out is None:
                return  # waits
            self._finish_step(run, *out)

    def _finish_step(self, run, state, note, auto=True):
        with self.lock:
            step = run["steps"][run["step"]]
            step.update(state=state, note=note, done_at=time.time(), auto=auto)
            self._event(run, note, step=run["step"], auto=auto)
            if state == "error":  # the run stops here: "retry" or "skip" in the app
                run.update(state="error", finished=time.time())
            else:
                run["step"] += 1
            self._save()

    def _wait_note(self, step, note):
        if step.get("note") != note:
            with self.lock:
                step["note"] = note
                self._save()

    # ---- seeding

    def _end_seeding(self, run, t, why):
        """Ends seeding of the run's torrent: pause, or remove from the client (the files stay)."""
        tid = run["tid"]
        step = run["steps"][run["step"]]
        then = step["opts"].get("then", "remove") if step["kind"] == "seed_end" else "remove"
        self.app.backend.action(tid, then)
        if then == "remove":
            self._now.pop(tid, None)
        elif tid in self._now:
            self._now[tid] = dict(self._now[tid], state="Paused")
        ratio = _num(round(t.get("ratio", 0), 2))
        text = (_tx(f"Seeding ended at ratio {ratio}: {why['en']}; removed from the client, the files stay",
                    f"Seedowanie zakończone przy ratio {ratio}: {why['pl']}; usunięty z klienta, pliki zostają")
                if then == "remove" else
                _tx(f"Seeding paused at ratio {ratio}: {why['en']}", f"Seedowanie wstrzymane przy ratio {ratio}: {why['pl']}"))
        with self.lock:
            self.st["torrents"].setdefault(tid, {})["seed_ended"] = time.time()
            for r in self.st["runs"]:
                if r["tid"] == tid and r["state"] == "running" and r is not run:
                    self._event(r, text)
            self._save()
        self.log("after download:", run["torrent"], "seeding ended:", then, "ratio", ratio)
        return text

    def _step_seed_end(self, run, step, t):
        if not t:
            return "done", _tx("Not in the torrent client any more", "Już nie ma go w kliencie torrentów")
        o = _opts(step)
        secs, ratio = t.get("seeding_time", 0), t.get("ratio", 0)
        days = secs / 86400
        if days >= o["min_days"] and (ratio >= o["ratio"] or days >= o["days"]):
            why = (_tx(f"ratio {_num(o['ratio'])} reached", f"osiągnięte ratio {_num(o['ratio'])}") if ratio >= o["ratio"]
                   else _tx(f"{_num(o['days'])} days of seeding", f"{_num(o['days'])} dni seedowania"))
            return "done", self._end_seeding(run, t, why)
        self._wait_note(step, _tx(f"ratio {_num(round(ratio, 1))} / {_num(o['ratio'])} · day {int(days) + 1} of {_num(o['days'])}",
                                  f"ratio {_num(round(ratio, 1))} / {_num(o['ratio'])} · dzień {int(days) + 1} z {_num(o['days'])}"))
        return None

    # ---- move

    def _decide_dest(self, run):
        """Where the torrent goes: the app's choice when it was added from the app, else the folder for the
        recognised title, only when the recognition is sure. → torrent info with `dest_root_id`/`dest_sub` or `unsure`."""
        ti = self.st["torrents"].setdefault(run["tid"], {})
        if "dest_root_id" in ti or ti.get("unsure"):
            return ti
        lib = self.app.library
        if ti.get("dest"):  # placed when it was added (the app's folder choice): stays there
            real = os.path.realpath(ti["dest"])
            r = next((r for r in lib.roots() if real == os.path.realpath(r["path"])
                      or real.startswith(os.path.realpath(r["path"]) + os.sep)), None)
            if r:
                rel = os.path.relpath(real, os.path.realpath(r["path"]))
                ti.update(dest_root_id=r["id"], dest_sub="" if rel == "." else rel, dest_name=r["name"], placed=True)
                return ti
        g = ti.get("guess") or {}
        kind = g.get("kind") if g.get("tag") in ("seriale", "bajki") else None
        tg = lib.targets(g.get("tag"), g.get("title"), kind)
        if tg["pick"] is None or g.get("confidence") != "high":
            ti["unsure"] = True
            ti["guess_text"] = g.get("title") or ""
        else:
            t2 = tg["targets"][tg["pick"]]
            ti.update(dest_root_id=t2["id"], dest_sub=t2["sub"] or "", dest_name=t2["name"])
        return ti

    def _top_now(self, run, t):
        """The torrent's top entry where it is now (the client's save path, else where the run last saw it)."""
        ti = self.st["torrents"].get(run["tid"]) or {}
        base = t["save_path"] if t else ti.get("save_path")
        return os.path.join(base, ti.get("top") or "")

    def _step_move(self, run, step, t):
        with self.lock:
            ti = self._decide_dest(run)
            self._save()
        if ti.get("unsure"):
            return "skipped", _tx(f"Folder not sure (recognised as \"{ti.get('guess_text')}\"): left where it is",
                                  f"Folder niepewny (rozpoznany jako „{ti.get('guess_text')}”): zostaje, gdzie jest")
        root = next((r for r in self.app.library.roots() if r["id"] == ti["dest_root_id"]), None)
        if not root or not root["exists"]:
            return "error", _tx("The destination folder is gone or its disk is disconnected",
                                "Folderu docelowego nie ma albo dysk jest odłączony")
        dest_dir = os.path.realpath(os.path.join(root["path"], ti["dest_sub"]))
        top = self._top_now(run, t)
        where = "/".join(x for x in (root["name"], ti["dest_sub"]) if x)
        if os.path.realpath(os.path.dirname(top)) == dest_dir:
            if step.get("task") or step.get("client_move"):
                self._relocate(run["tid"], dest_dir)
            return "done", (_tx(f"In {where} (chosen when it was added)", f"W {where} (wybrane przy dodawaniu)")
                            if ti.get("placed") and not step.get("task") else _tx(f"→ {where}", f"→ {where}"))
        if step.get("task"):
            return self._task_result(run, step, on_done=lambda: self._relocate(run["tid"], dest_dir),
                                     done_note=_tx(f"→ {where}", f"→ {where}"))
        if step.get("client_move"):  # outside the library folders: the client moves it itself
            if t and os.path.realpath(t["save_path"]) == dest_dir and "moving" not in str(t.get("state", "")).lower():
                self._relocate(run["tid"], dest_dir)
                return "done", _tx(f"→ {where}", f"→ {where}")
            if time.time() - step["client_move"] > MOVE_TIMEOUT:
                return "error", _tx("The torrent client didn't finish moving in 12 hours",
                                    "Klient torrentów nie skończył przenosić w 12 godzin")
            return None
        with mp_i18n.both():
            res = self.app.tasks.one_move_task(top, root["id"], ti["dest_sub"], torrent=run["tid"] if t else None)
        with self.lock:
            if res.get("task"):
                step["task"] = res["task"]
            elif t:
                self.app.backend.move(run["tid"], dest_dir)
                step["client_move"] = time.time()
            else:
                self._save()
                return "skipped", _tx("Not in a library folder and not in the client: left where it is",
                                      "Poza folderami biblioteki i poza klientem: zostaje, gdzie jest")
            if t:
                self._event(run, _tx("Moved by the torrent client: seeding pauses while it moves",
                                     "Przenosi klient torrentów: seedowanie stoi na czas przenoszenia"), step=run["step"])
            self._save()
        return None

    def _relocate(self, tid, dest_dir):
        """The torrent's top entry is now in `dest_dir`: every video of it gets its new path (and subflow's records)."""
        with self.lock:
            ti = self.st["torrents"].setdefault(tid, {})
            old_base = ti.get("save_path")
            ti["save_path"] = dest_dir
            moved = []
            for r in self.st["runs"]:
                if r["tid"] == tid and old_base and r["file"].startswith(old_base.rstrip(os.sep) + os.sep):
                    new = os.path.join(dest_dir, os.path.relpath(r["file"], old_base))
                    moved.append((r["file"], new))
                    r["file"] = new
            self._save()
        for old, new in moved:
            self.app.subflow.moved(old, new)

    # ---- tasks (move, shrink, cast_ready)

    def _task_result(self, run, step, on_done=None, done_note=None):
        st = self.app.tasks.task_item(step["task"])
        if st is None:
            return "error", _tx("The task was removed", "Zadanie usunięto")
        if st["item"] in ("pending", "running") and st["task"] in ("queued", "running"):
            return None
        if st["item"] == "done":
            if on_done:
                on_done()
            if done_note:
                return "done", done_note
            saved = st.get("saved") or 0
            return "done", (_tx(f"Done, {saved >> 20} MB smaller", f"Zrobione, o {saved >> 20} MB mniej") if saved > 0
                            else _tx("Done", "Zrobione"))
        # an item's error is in the language of whoever started its task: here the server, so English
        if st["item"] == "skipped":
            return "skipped", _tx(st.get("error") or "Skipped", st.get("error") or "Pominięte")
        if st["item"] == "error":
            return "error", _tx(st["error"] or "Failed", st["error"] or "Nie wyszło")
        return "skipped", _tx("The task was cancelled", "Zadanie anulowano")

    def _follow_rename(self, run):
        """A step that rewrote the file may have changed its extension (AVI → MKV)."""
        if os.path.exists(run["file"]):
            return
        stem = os.path.splitext(run["file"])[0]
        for ext in (".mkv", ".mp4"):
            if os.path.exists(stem + ext):
                old = run["file"]
                with self.lock:
                    run["file"] = stem + ext
                    self._save()
                self.app.subflow.moved(old, stem + ext)
                return

    def _file_step(self, run, step, t, kind, opts):
        if step.get("task"):
            return self._task_result(run, step, on_done=lambda: self._follow_rename(run))
        note = None
        if t and changes_file(dict(_opts(step), kind=step["kind"])):
            note = self._end_seeding(run, t, _tx(f"step \"{STEP_NAMES[step['kind']][0]}\" changes the file",
                                                 f"krok „{STEP_NAMES[step['kind']][1]}” zmienia plik"))
            with self.lock:
                self._event(run, note, step=run["step"], auto=True)
                self._save()
        with mp_i18n.both():
            res = self.app.tasks.one_file_task(kind, run["file"], dict(STEP_DEFAULTS[step["kind"]], **opts))
        if res.get("none"):
            return "skipped", mp_i18n.pair(res["none"])
        with self.lock:
            step["task"] = res["task"]
            self._save()
        return None

    def _step_cast_ready(self, run, step, t):
        # "keep_seeding": while it seeds the file stays byte for byte, only text subtitles are written next to it
        # (Tasks._cast_need); "in_place" ends seeding first and does everything
        return self._file_step(run, step, t, "cast_ready", step["opts"])

    def _step_shrink(self, run, step, t):
        if not step.get("task"):
            o = _opts(step)
            rf = self.app.tasks.fact_of(run["file"])
            info = (rf[1].get("info") or {}) if rf else {}
            size, dur = os.path.getsize(run["file"]), info.get("duration") or 0
            mbps = size * 8 / dur / 1e6 if dur else 0
            if size < o["min_gb"] * (1 << 30) and mbps < o["min_mbps"]:
                gb = _num(round(size / (1 << 30), 1))
                return "skipped", _tx(f"{gb} GB, {_num(round(mbps, 1))} Mb/s: below the step's limits",
                                      f"{gb} GB, {_num(round(mbps, 1))} Mb/s: poniżej progów kroku")
        return self._file_step(run, step, t, "compress", step["opts"])

    # ---- subtitles

    def _step_subs(self, run, step, t):
        sf = self.app.subflow
        if not step.get("queued"):
            o = _opts(step)
            langs = list(o["langs"])
            if o["downloader"]:
                langs += (self.app.jobs.get(run["tid"]) or {}).get("sub_langs") or []
            sf.enqueue(run["file"], langs)
            with self.lock:
                step["queued"] = time.time()
                self._save()
            return None
        if sf.pending(run["file"]):
            return None
        r = sf.result(run["file"]) or {}
        if r.get("at", 0) < step["queued"] - 1:
            return "skipped", _tx("No result from the subtitle queue", "Brak wyniku z kolejki napisów")
        if not r.get("baseline"):
            return "skipped", _tx(f"No subtitles in the film's language ({r.get('lang') or '?'})",
                                  f"Brak napisów w języku filmu ({r.get('lang') or '?'})")
        parts = [f"{r.get('lang')} ✓"] + [f"{k} {'✓' if (x or {}).get('verdict') not in ('none', 'locked', None) else '✗'}"
                                          for k, x in (r.get("targets") or {}).items()]
        return "done", _tx("Subtitles: " + ", ".join(parts), "Napisy: " + ", ".join(parts))

    # ---- the API

    def _ref(self, full):
        real = os.path.realpath(full)
        for r in self.app.library.roots():
            base = os.path.realpath(r["path"])
            if real.startswith(base + os.sep):
                return {"root": r["index"], "path": os.path.relpath(real, base)}
        return None

    def _public(self, run, full=False):
        out = {k: run.get(k) for k in ("id", "torrent", "source", "by", "kind", "state", "created", "finished", "step")}
        out["name"] = os.path.basename(run["file"])
        out["ref"] = self._ref(run["file"])
        out["steps"] = [{"kind": s["kind"], "state": s["state"], "note": _say(s.get("note")), "auto": s.get("auto", False),
                         **({"opts": s["opts"]} if full else {})} for s in run["steps"]]
        if full:
            out["events"] = [dict(e, text=_say(e["text"])) for e in run["events"]]
        return out

    def runs(self, since=0.0):
        with self.lock:
            return {"enabled": self.cfg["enabled"],
                    "runs": [self._public(r) for r in self.st["runs"]
                             if r["state"] == "running" or (r.get("finished") or r["created"]) > since][:200]}

    def run(self, rid):
        with self.lock:
            r = next((r for r in self.st["runs"] if r["id"] == rid), None)
            return self._public(r, full=True) if r else None

    def action(self, rid, what):
        """`skip` the current step, `retry` the last failed one, `cancel` the rest. → the run, None when unknown."""
        with self.lock:
            r = next((r for r in self.st["runs"] if r["id"] == rid), None)
            if not r:
                return None
            if what == "skip" and r["state"] in ("running", "error") and r["step"] < len(r["steps"]):
                s = r["steps"][r["step"]]
                s.update(state="skipped", note=_tx("Skipped by hand", "Pominięte ręcznie"), auto=False)
                self._event(r, s["note"], step=r["step"])
                r.update(state="running", step=r["step"] + 1, finished=None)
            elif what == "retry" and r["state"] == "error":
                n = r["step"]
                r["steps"][n] = {"kind": r["steps"][n]["kind"], "opts": r["steps"][n]["opts"], "state": "pending"}
                r.update(state="running", finished=None)
                self._event(r, _tx("Step tried again", "Krok ponowiony"), step=n)
            elif what == "cancel" and r["state"] == "running":
                r.update(state="cancelled", finished=time.time())
                self._event(r, _tx("The remaining steps cancelled", "Pozostałe kroki anulowane"))
            else:
                raise ValueError("action: skip (a running or failed run), retry (a failed one) or cancel")
            self._save()
            return self._public(r, full=True)


STEP_NAMES = {"move": ("Move", "Przenieś"), "subs": ("Subtitles", "Napisy"),
              "cast_ready": ("Ready for the phone", "Przygotuj na telefon"), "shrink": ("Shrink", "Zmniejsz"),
              "seed_end": ("End seeding", "Zakończ seedowanie")}
