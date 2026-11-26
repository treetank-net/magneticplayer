"""After download (mp_afterdl) against stub parts of the agent: no torrent client, no ffmpeg, temporary files only.

Run: python3 -m unittest discover -s server/tests   (from the repository root)
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import mp_afterdl  # noqa: E402

MB = 1 << 20


class Backend:
    def __init__(self):
        self.torrents, self.files_of, self.calls = {}, {}, []

    def list(self):
        return list(self.torrents.values())

    def files(self, tid):
        t = self.torrents[tid]
        return t["save_path"], self.files_of[tid]

    def action(self, tid, what):
        self.calls.append((what, tid))
        if what == "remove":
            self.torrents.pop(tid)
        else:
            self.torrents[tid]["state"] = "Paused"

    def move(self, tid, dest):
        self.calls.append(("move", tid, dest))


class Jobs:
    def __init__(self):
        self.items = {}

    def get(self, tid):
        return self.items.get(tid)


class Ident:
    def __init__(self, guess):
        self.g = guess

    def guess(self, name, files=None):
        return dict(self.g)


class Library:
    def __init__(self, roots):
        self._roots = roots

    def roots(self):
        return [dict(r, index=i, exists=True) for i, r in enumerate(self._roots)]

    def targets(self, tag=None, title=None, kind=None):
        out = [{"root": i, "id": r["id"], "name": r["name"], "path": r["path"], "tags": r["tags"], "sub": ""}
               for i, r in enumerate(self._roots)]
        pick = next((i for i, t in enumerate(out) if tag in t["tags"]), None)
        return {"targets": out, "pick": pick}


class Tasks:
    """Records one-item tasks; `finish` plays the worker."""

    def __init__(self, backend):
        self.backend, self.tasks, self.n = backend, {}, 0

    def _new(self, **kw):
        self.n += 1
        tid = f"{self.n:08x}"
        self.tasks[tid] = dict(kw, task="queued", item="pending")
        return {"task": tid}

    def one_move_task(self, top, to_root_id, to_sub="", torrent=None):
        return self._new(op="move", top=top, to=to_root_id, torrent=torrent)

    def one_file_task(self, kind, full, o):
        return self._new(op=kind, full=full, opts=o)

    def fact_of(self, full):
        return ({}, {"info": {"duration": 3600}})

    def task_item(self, tid):
        t = self.tasks.get(tid)
        return {"task": t["task"], "item": t["item"], "error": None, "saved": 0, "est": None} if t else None

    def finish(self, tid, item="done"):
        self.tasks[tid].update(task="done", item=item)


class SubFlow:
    def __init__(self):
        self.queue, self.done, self.moves = [], {}, []

    def enqueue(self, full, langs=()):
        self.queue.append(full)

    def pending(self, full):
        return full in self.queue

    def result(self, full):
        return self.done.get(full)

    def moved(self, old, new):
        self.moves.append((old, new))


class App:
    def __init__(self, tmp, guess):
        self.cfg = {}
        self.backend = Backend()
        self.jobs = Jobs()
        self.ident = Ident(guess)
        films = os.path.join(tmp, "films")
        down = os.path.join(tmp, "downloads")
        os.makedirs(films)
        os.makedirs(down)
        self.library = Library([{"id": "aaa", "name": "Downloads", "path": down, "tags": []},
                                {"id": "bbb", "name": "Films", "path": films, "tags": ["filmy"]}])
        self.tasks = Tasks(self.backend)
        self.subflow = SubFlow()
        self.down, self.films = down, films

    def save_cfg(self):
        pass


def log(*a):
    pass


class AfterDownloadTest(unittest.TestCase):
    def setUp(self):
        self.tmpd = tempfile.TemporaryDirectory()
        tmp = self.tmpd.name
        self.app = App(tmp, {"tag": "filmy", "title": "Sintel", "confidence": "high", "kind": "film"})
        mp_afterdl.set_settings(self.app, {"enabled": True})
        self.ad = mp_afterdl.AfterDownload(self.app, tmp, log)

    def tearDown(self):
        self.tmpd.cleanup()

    def torrent(self, tid="t1", progress=100, ratio=0.0, seeding_time=0):
        path = os.path.join(self.app.down, "Sintel", "Sintel.mkv")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.truncate(700 * MB)  # sparse: big enough for MIN_SIZE, no real disk use
        self.app.backend.torrents[tid] = {"id": tid, "name": "Sintel", "progress": progress, "state": "Seeding",
                                          "save_path": self.app.down, "ratio": ratio, "seeding_time": seeding_time}
        self.app.backend.files_of[tid] = [{"index": 0, "path": "Sintel/Sintel.mkv", "size": 700 * MB}]
        return path

    def tick(self):
        self.ad.tick(self.app.backend.list())

    def test_existing_library_is_left_alone(self):
        self.torrent()
        self.tick()  # first run: what is complete is the old library
        self.tick()
        self.assertEqual(self.ad.runs()["runs"], [])

    def test_right_away_recipe(self):
        self.tick()  # nothing yet: initialises "seen"
        path = self.torrent()
        self.tick()
        runs = self.ad.runs()["runs"]
        self.assertEqual(len(runs), 1)
        self.assertEqual(runs[0]["source"], "client")
        self.assertEqual([s["kind"] for s in runs[0]["steps"]], ["subs", "cast_ready", "move", "seed_end", "shrink"])
        # subtitles: queued, then a result
        self.assertEqual(self.app.subflow.queue, [path])
        self.app.subflow.queue.clear()
        self.app.subflow.done[path] = {"at": 1e12, "lang": "en", "baseline": {"by": "rhythm"},
                                       "targets": {"pl": {"verdict": "good"}}}
        self.tick()
        run = self.ad.run(runs[0]["id"])
        self.assertEqual(run["steps"][0]["state"], "done")
        # cast_ready (keeps seeding): a task; the torrent stays in the client
        cast = run["steps"][1]
        self.assertEqual(cast["state"], "running")
        self.app.tasks.finish("00000001")
        self.tick()
        # move: a one-item move task by the client, to the "filmy" folder
        mv = self.app.tasks.tasks["00000002"]
        self.assertEqual((mv["op"], mv["to"], mv["torrent"]), ("move", "bbb", "t1"))
        self.app.backend.torrents["t1"]["save_path"] = self.app.films  # the client moved it
        os.renames(os.path.join(self.app.down, "Sintel"), os.path.join(self.app.films, "Sintel"))
        self.app.tasks.finish("00000002")
        self.tick()
        run = self.ad.run(runs[0]["id"])
        self.assertEqual(run["steps"][2]["state"], "done")
        self.assertEqual(self.app.subflow.moves, [(path, os.path.join(self.app.films, "Sintel", "Sintel.mkv"))])
        # seed_end waits for ratio 2.0 or 7 days, never before 1 day
        self.assertEqual(run["steps"][3]["state"], "running")
        self.app.backend.torrents["t1"].update(ratio=2.5, seeding_time=3600)
        self.tick()
        self.assertEqual(self.ad.run(runs[0]["id"])["steps"][3]["state"], "running")  # under the minimum day
        self.app.backend.torrents["t1"].update(seeding_time=2 * 86400)
        self.tick()
        self.assertEqual(self.app.backend.calls.count(("remove", "t1")), 1)  # once, not again by the next step
        # shrink: 700 MB at 1.6 Mb/s is below 8 GB / 6 Mb/s
        run = self.ad.run(runs[0]["id"])
        self.assertEqual(run["steps"][4]["state"], "skipped")
        self.assertEqual(run["state"], "done")

    def test_shrink_ends_seeding_first(self):
        mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "shrink", "min_gb": 0.1}]}})
        self.tick()
        self.torrent()
        self.tick()
        self.assertIn(("remove", "t1"), self.app.backend.calls)
        run = self.ad.runs()["runs"][0]
        self.assertEqual(run["steps"][0]["state"], "running")
        self.assertEqual(self.app.tasks.tasks["00000001"]["opts"]["original"], "delete")  # seeding over: no trash
        self.app.tasks.finish("00000001", item="error")
        self.tick()
        run = self.ad.run(run["id"])
        self.assertEqual(run["state"], "error")
        self.ad.action(run["id"], "retry")
        self.tick()
        self.assertEqual(self.ad.run(run["id"])["state"], "running")

    def test_seed_end_then_shrink_in_one_tick(self):
        mp_afterdl.set_settings(self.app, {"recipes": {"film": [
            {"kind": "seed_end", "ratio": 0.0, "days": 0.0, "min_days": 0.0}, {"kind": "shrink", "min_gb": 0.0}]}})
        self.tick()
        self.torrent()
        self.tick()  # seeding ends, then shrink starts in the same tick without asking the client again
        self.assertEqual(self.app.backend.calls, [("remove", "t1")])
        run = self.ad.runs()["runs"][0]
        self.assertEqual([s["state"] for s in run["steps"]], ["done", "running"])

    def test_cast_ready_modes(self):
        mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "cast_ready"}]}})
        self.tick()
        self.torrent()
        self.tick()  # keep_seeding: a task, the torrent stays
        self.assertEqual(self.app.backend.calls, [])
        mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "cast_ready", "mode": "in_place"}]}})
        self.torrent(tid="t2")
        self.tick()
        self.assertEqual(self.app.backend.calls, [("remove", "t2")])

    def test_texts_in_both_languages(self):
        import mp_i18n
        with mp_i18n.both():
            x = mp_i18n.T("nothing to prepare", "nie ma czego przygotować")
        self.assertEqual(mp_i18n.pair(x), {"en": "nothing to prepare", "pl": "nie ma czego przygotować"})
        self.assertEqual(mp_i18n.T("a", "b"), "a")  # outside: the current language again

    def test_unsure_folder_stays(self):
        self.app.ident.g = {"tag": "filmy", "title": "?", "confidence": "low"}
        mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "move"}]}})
        self.tick()
        self.torrent()
        self.tick()
        run = self.ad.runs()["runs"][0]
        self.assertEqual(run["steps"][0]["state"], "skipped")
        self.assertEqual(self.app.tasks.tasks, {})

    def test_unwanted_files_and_partial_recipes(self):
        # an archive.org item: one file wanted, another video in the torrent at 0 %; only "film" set by the owner
        self.app.cfg["afterdl"]["recipes"] = {"film": [{"kind": "subs"}]}
        self.tick()
        self.torrent()
        self.app.backend.files_of["t1"].append({"index": 1, "path": "Sintel/Sintel.ogv", "size": 300 * MB, "progress": 0})
        self.tick()
        runs = self.ad.runs()["runs"]
        self.assertEqual([(r["name"], r["kind"]) for r in runs], [("Sintel.mkv", "film")])
        self.assertEqual(mp_afterdl.settings(self.app.cfg)["recipes"]["series"], mp_afterdl.TEMPLATES["right_away"])

    def test_runs_of_one_file(self):
        mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "subs"}]}})
        self.tick()
        self.torrent()
        self.tick()
        self.assertEqual(len(self.ad.runs_of(0, "Sintel/Sintel.mkv")), 1)  # root 0 = the downloads folder
        self.assertEqual(self.ad.runs_of(1, "Sintel/Sintel.mkv"), [])
        self.assertEqual(self.ad.runs_of(9, "Sintel/Sintel.mkv"), [])

    def test_sources_filter(self):
        mp_afterdl.set_settings(self.app, {"sources": ["app"]})
        self.tick()
        self.torrent()
        self.tick()
        self.assertEqual(self.ad.runs()["runs"], [])

    def test_settings_validation_and_effects(self):
        with self.assertRaises(ValueError):
            mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "shrink", "quality": "best"}]}})
        with self.assertRaises(ValueError):
            mp_afterdl.set_settings(self.app, {"recipes": {"film": [{"kind": "seed_end"}, {"kind": "seed_end"}]}})
        s = mp_afterdl.set_settings(self.app, {"recipes": {"film": "archive"}})
        labels = [e["labels"] for e in s["effects"]["film"]]
        self.assertIn("ends_seeding", labels[0])
        eff = mp_afterdl.effects(mp_afterdl._recipe(("shrink", {}), ("seed_end", {})))
        self.assertIsNotNone(eff[0]["warning"])
        self.assertIn("no_effect", eff[1]["labels"])


if __name__ == "__main__":
    unittest.main()
