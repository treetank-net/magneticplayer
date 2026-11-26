"""What the worker does with an item: subtitles downloaded and fitted, replication, moving (rename, copy, the torrent
client), the trash."""
import json
import os
import secrets
import shutil
import time

import mp_detached
import mp_library
import mp_media
from mp_i18n import T
from mp_tasks_common import RESERVE, TaskError, _Cancelled, _dev, _lang2, _signal, tree_size


TRASH = ".mp-trash"


def free_name(path):
    """`path`, or `name (2).ext`, `name (3).ext`… when something with that name is already there (the trash keeps
    every original: a second job on the same file the same day must not overwrite the first one's original)."""
    if not os.path.lexists(path):
        return path
    stem, ext = os.path.splitext(path)
    if os.path.isdir(path):
        stem, ext = path, ""
    for n in range(2, 1000):
        cand = f"{stem} ({n}){ext}"
        if not os.path.lexists(cand):
            return cand
    return f"{stem} ({secrets.token_hex(3)}){ext}"


def _fit_text(x):
    """One subtitle file's outcome, for the task's item list."""
    who = x.get("lang") or x["name"]
    if x["verdict"] == "bad" and x.get("aside"):
        return T(f"{who}: doesn't fit this copy of the film, set aside", f"{who}: nie pasuje do tej wersji filmu, odłożone")
    if x["verdict"] == "bad":
        return T(f"{who}: doesn't fit this copy of the film", f"{who}: nie pasuje do tej wersji filmu")
    if x["verdict"] == "unsure":
        return T(f"{who}: unsure, left as it was", f"{who}: niepewne, zostawione bez zmian")
    how = []
    if abs(x["ratio"] - 1) > 1e-4:
        how.append(T("other frame rate", "inna liczba klatek"))
    if abs(x["shift"]) >= 0.4:
        how.append(f"{x['shift']:+.1f} s")
    if x["changed"] and not how and x.get("converted"):
        return T(f"{who}: fits, converted to SRT", f"{who}: pasują, zamienione na SRT")
    if x["changed"]:
        return T(f"{who}: fitted", f"{who}: dopasowane") + (f" ({', '.join(how)})" if how else "")
    if how and x.get("locked"):
        return T(f"{who}: off ({', '.join(how)}), but part of a seeded torrent, left alone",
                 f"{who}: przesunięte ({', '.join(how)}), ale należą do seedowanego torrenta, bez zmian")
    return T(f"{who}: fits", f"{who}: pasują")


def _copy(src, dst, tick):
    """Copies a file or a tree, reporting bytes; metadata where the filesystem allows it (ntfs-3g refuses some)."""
    if os.path.isdir(src):
        os.makedirs(dst)
        for name in sorted(os.listdir(src)):
            if name.startswith(".mp-cast"):
                continue  # "for Chromecast" versions: regenerated on demand
            _copy(os.path.join(src, name), os.path.join(dst, name), tick)
        return
    with open(src, "rb") as a, open(dst, "wb") as b:
        while chunk := a.read(8 << 20):
            b.write(chunk)
            tick(len(chunk))
        b.flush()
        os.fsync(b.fileno())
    try:
        shutil.copystat(src, dst)
    except OSError:
        pass


def _merge_into(src, dst):
    """Moves what is left in folder `src` into folder `dst` (same relative paths, nothing overwritten), then removes
    the folders left empty."""
    for dp, _, names in os.walk(src):
        for n in names:
            a = os.path.join(dp, n)
            b = os.path.join(dst, os.path.relpath(a, src))
            if not os.path.exists(b):
                os.makedirs(os.path.dirname(b), exist_ok=True)
                shutil.move(a, b)
    for dp, _, _ in sorted(os.walk(src), key=lambda x: -len(x[0])):
        try:
            os.rmdir(dp)
        except OSError:
            pass


class OpsMixin:

    def _subs_get(self, i, t):
        """Downloads the missing languages of one video (the best result that fits, see the agent's
        fetch_best_subs); in a folder the provider that worked for the previous episode goes first."""
        if not self.fetch_subs:
            raise TaskError(T("subtitle downloading is not available", "pobieranie napisów jest niedostępne"))
        full = os.path.join(self._root_path(i["root_id"]), i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the video no longer exists", "filmu już nie ma"))
        prefer = t.setdefault("prefer", {})
        out, missing = [], []
        for lang in i["langs"]:
            self._check_cancel()
            key = f"{os.path.dirname(full)}|{lang}"
            r = self.fetch_subs(full, lang, prefer.get(key))
            if not r:
                missing.append(lang)
                continue
            prefer[key] = r.get("provider")
            v = (r.get("fit") or {}).get("verdict")
            out.append(f"{lang}: {r.get('release') or r.get('provider')}" + (f" ({v})" if v and v != "good" else ""))
        i["note"] = "; ".join(out + [T(f"{l}: none found", f"{l}: nie znaleziono") for l in missing])
        if missing and not out:
            raise TaskError(i["note"])

    def _subs(self, i, t):
        """Fits every subtitle file of one video; a file inside a seeded torrent is only assessed."""
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            raise TaskError(T("the video no longer exists", "filmu już nie ma"))
        try:
            dev = os.stat(full).st_dev
        except OSError:
            dev = None
        # reading the whole file for its sound: not while somebody watches from the same disk
        while self._hold(dev) is not None:
            t["note"] = self.hold_text("same_disk")
            if self.cancel_ev.wait(10):
                t.pop("note", None)
                raise _Cancelled()
        t.pop("note", None)
        import mp_subsync
        out, errors = [], []
        for x in i["subs"]:
            path = os.path.join(os.path.dirname(full), x["name"])
            if not os.path.exists(path):
                continue
            try:
                r = self.subsync.fit(full, path, change=not x.get("locked"))
            except (mp_subsync.SyncError, OSError, ValueError) as e:
                errors.append(f"{x['name']}: {e}")
                continue
            out.append({"name": r["name"], "lang": x.get("lang"), "verdict": r["verdict"], "z": r["z"],
                        "changed": r["changed"], "locked": bool(x.get("locked")), "converted": r["name"] != x["name"],
                        "shift": r.get("fixed_offset", r["offset"]), "ratio": r.get("fixed_ratio", r["ratio"])})
            if r["verdict"] == "bad" and not x.get("locked"):
                # 1.16+: set aside to Subtitles/ (kept, no longer the default), and/or downloaded again afterwards
                if i.get("mark_bad") and self.subsources is not None and "/" not in r["name"]:
                    import mp_subfiles
                    moved = mp_subfiles.demote(full, os.path.join(os.path.dirname(full), r["name"]), self.subsources,
                                               self.torrent_owned, self.subsync.move)
                    if moved:
                        out[-1]["aside"] = os.path.relpath(moved, os.path.dirname(full))
                code = _lang2(x.get("lang"))
                if i.get("queue_bad") and len(code) == 2:
                    t.setdefault("bad_subs", []).append([i["root_id"], i.get("root"), i["root_name"], i["path"], code])
            t["fixed"] = t.get("fixed", 0) + (1 if r["changed"] else 0)
            t["unfit"] = t.get("unfit", 0) + (1 if r["verdict"] == "bad" else 0)
            self._check_cancel()
        i["result"] = out
        i["note"] = "; ".join(_fit_text(x) for x in out)
        if errors and not out:
            raise TaskError("; ".join(errors)[:300])
        if errors:
            i["note"] += "; " + "; ".join(errors)

    def trash_days(self):
        return int(self.cfg.get("trash_days", 14))

    # ---- replication

    def replicate(self):
        roots = {r["id"]: r for r in self.library.roots()}
        try:
            unfinished = {os.path.realpath(os.path.join(t["save_path"], t["name"]))
                          for t in self.backend().list() if t.get("progress", 100) < 100}
        except Exception:  # noqa: BLE001 — no client: nothing is downloading as far as we can tell
            unfinished = set()
        for r in roots.values():
            to = roots.get(r.get("replicate_to") or "")
            if not to:
                mp_library.REPLICA.pop(r["id"], None)
                continue
            st = mp_library.REPLICA.setdefault(r["id"], {"pending": 0, "copied": 0, "last": None, "error": None})
            if not r["exists"] or not to["exists"]:
                st["error"] = T("a disk is disconnected", "dysk jest odłączony")
                continue
            todo = []
            for name in sorted(os.listdir(r["path"])):
                if name.startswith(".") or name.endswith(".mp-part"):
                    continue
                src, dst = os.path.join(r["path"], name), os.path.join(to["path"], name)
                if os.path.realpath(src) in unfinished:
                    continue
                if os.path.exists(dst) and tree_size(src) == tree_size(dst):
                    continue
                todo.append((src, dst))
            st.update(pending=len(todo), error=None)
            for src, dst in todo:
                if self.streaming() or any(t["state"] == "queued" for t in self.items):
                    return  # somebody watches, or the owner started a task: next round
                need = tree_size(src)[0]
                if shutil.disk_usage(to["path"]).free < need + (1 << 30):
                    st["error"] = T("not enough room for the copy", "za mało miejsca na kopię")
                    break
                part = dst + ".mp-part"
                with self.lock:
                    self.replicating = True
                try:
                    if os.path.exists(part):
                        (shutil.rmtree if os.path.isdir(part) else os.remove)(part)
                    _copy(src, part, lambda n: None)
                    if tree_size(src) != tree_size(part):
                        raise OSError(T("the copy differs from the source", "kopia różni się od źródła"))
                    if os.path.exists(dst):
                        (shutil.rmtree if os.path.isdir(dst) else os.remove)(dst)
                    os.rename(part, dst)
                    st["copied"] += 1
                    st["pending"] -= 1
                    st["last"] = time.time()
                except OSError as e:
                    st["error"] = str(e)[:200]
                    self.log("replication:", os.path.basename(src), e)
                    if os.path.exists(part):
                        (shutil.rmtree if os.path.isdir(part) else os.remove)(part)
                finally:
                    with self.lock:
                        self.replicating = False

    # ---- moving

    def _root_path(self, rid):
        r = self._roots().get(rid)
        if not r or not r["exists"]:
            raise TaskError(T("the library folder is gone or its disk is disconnected",
                              "katalogu biblioteki już nie ma albo dysk jest odłączony"))
        return r["path"]

    def _move(self, i, progress):
        src_root, dst_root = self._root_path(i["root_id"]), self._root_path(i["to_root_id"])
        src = os.path.join(src_root, i["path"])
        dst_dir = os.path.join(dst_root, i.get("to_sub") or "")
        dst = os.path.join(dst_dir, os.path.basename(i["path"]))
        if not os.path.exists(src):
            if os.path.exists(dst):
                return  # already moved before a restart
            raise TaskError(T("the source no longer exists", "źródła już nie ma"))
        os.makedirs(dst_dir, exist_ok=True)
        if i.get("torrent"):
            return self._move_torrent(i, src, dst_dir, dst, progress)
        if os.path.exists(dst):
            raise TaskError(T("the destination already exists", "w miejscu docelowym już coś jest"))
        pairs = [(src, dst)] + [(os.path.join(os.path.dirname(src), e), os.path.join(dst_dir, e)) for e in i.get("extra") or []]
        if _dev(src) == _dev(dst_dir):
            for a, b in pairs:
                if os.path.exists(a) and not os.path.exists(b):
                    os.rename(a, b)
            progress(i["size"])
            return
        copied = [0]

        def tick(n):
            copied[0] += n
            progress(copied[0])
            self._check_cancel()
        for a, b in pairs:
            if not os.path.exists(a):
                continue
            part = b + ".mp-part"
            if os.path.exists(part):
                (shutil.rmtree if os.path.isdir(part) else os.remove)(part)
            _copy(a, part, tick)
            if tree_size(a) != tree_size(part):
                raise TaskError(T("the copy differs from the source, the source was left alone",
                                  "kopia różni się od źródła, źródło zostało nietknięte"))
            os.rename(part, b)
        for a, _ in pairs:
            if os.path.isdir(a):
                shutil.rmtree(a)
            elif os.path.exists(a):
                os.remove(a)
        mp_media.remove_variant(src)

    def _record_move(self, i):
        """Phones keep positions and "watched" under the library folder and path, so every move is journalled:
        GET /api/library/moves lets them re-key what they saved (agent 1.14+)."""
        to = "/".join(x for x in ((i.get("to_sub") or "").strip("/"), os.path.basename(i["path"])) if x)
        with self.lock:
            try:
                with open(self.moves_path) as f:
                    j = json.load(f)
            except (OSError, ValueError):
                j = []
            j.append({"at": time.time(), "from_root_id": i["root_id"], "from": i["path"],
                      "to_root_id": i["to_root_id"], "to": to})
            j = j[-5000:]
            tmp = self.moves_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(j, f)
            os.replace(tmp, self.moves_path)

    def moves(self, since=0.0):
        """Moves after `since` (epoch s) with the library folders' current numbers; unknown folders are left out."""
        try:
            with open(self.moves_path) as f:
                j = json.load(f)
        except (OSError, ValueError):
            j = []
        idx = {r["id"]: r["index"] for r in self.library.roots()}
        out = [{"at": m["at"], "from_root": idx[m["from_root_id"]], "from": m["from"], "to_root": idx[m["to_root_id"]],
                "to": m["to"]} for m in j if m["at"] > since and m["from_root_id"] in idx and m["to_root_id"] in idx]
        return {"moves": out, "now": time.time()}

    def _move_torrent(self, i, src, dst_dir, dst, progress):
        be = self.backend()
        be.move(i["torrent"], dst_dir)
        deadline = time.time() + 12 * 3600
        while time.time() < deadline:
            time.sleep(5)
            self._check_cancel()
            progress(tree_size(dst)[0] if os.path.exists(dst) else 0)
            t = next((t for t in be.list() if t["id"] == i["torrent"]), None)
            if not t:
                raise TaskError(T("the torrent disappeared from the client while moving", "torrent zniknął z klienta w trakcie przenoszenia"))
            if os.path.realpath(t["save_path"]) == os.path.realpath(dst_dir) \
                    and "moving" not in str(t.get("state", "")).lower() and not self._torrent_left(be, i["torrent"], src):
                for e in i.get("extra") or []:  # files next to a single-file torrent that aren't part of it
                    a = os.path.join(os.path.dirname(src), e)
                    if os.path.exists(a):
                        shutil.move(a, os.path.join(dst_dir, e))
                if os.path.isdir(src):  # a folder torrent moved to another disk: files added to it (subtitles) stay
                    _merge_into(src, dst)
                progress(i["size"])
                return
        raise TaskError(T("the torrent client didn't finish moving in 12 hours", "klient torrentów nie skończył przenosić w 12 godzin"))

    @staticmethod
    def _torrent_left(be, tid, src):
        """Whether any of the torrent's own files is still at the old place (`src` = its top entry there)."""
        if not os.path.exists(src):
            return False
        try:
            _, files = be.files(tid)
        except Exception:  # noqa: BLE001 — not answering now: ask again on the next round
            return True
        old = os.path.dirname(src)
        return any(os.path.exists(os.path.join(old, f["path"])) for f in files)

    def _trash(self, i):
        root = self._root_path(i["root_id"])
        full = os.path.join(root, i["path"])
        if not os.path.exists(full):
            return
        dest = os.path.join(root, TRASH, time.strftime("%Y-%m-%d"), os.path.dirname(i["path"]))
        os.makedirs(dest, exist_ok=True)
        for name in [os.path.basename(full)] + (i.get("extra") or []):
            a = os.path.join(os.path.dirname(full), name)
            if os.path.exists(a):
                if self.trash_days() > 0:
                    os.rename(a, free_name(os.path.join(dest, name)))
                elif os.path.isdir(a):
                    shutil.rmtree(a)
                else:
                    os.remove(a)
        mp_media.remove_variant(full)

    # ---- shrinking

    def _drop_job(self, i):
        """An item's detached encode (one paused or left by a restart) stopped and forgotten."""
        j = mp_detached.Detached.find(self.encodes, i.pop("job", None))
        if j:
            _signal(j, "SIGCONT")
            j.terminate()
            self._drop_part(j)
            j.remove()

    # ---- trash

    def _spare_disk(self, avoid_dev, size):
        """A library folder on another disk with `size` to spare above its reserve (the most room first): a trash that
        doesn't fit on its own disk goes there. None when no disk has the room."""
        best = None
        for r in self.library.roots():
            d = _dev(r["path"]) if r["exists"] else None
            if d is None or d == avoid_dev:
                continue
            try:
                u = shutil.disk_usage(r["path"])
            except OSError:
                continue
            room = u.free - int(u.total * RESERVE) - size
            if room > 0 and (best is None or room > best[0]):
                best = (room, r)
        return best[1] if best else None

    def _trash_name(self, root):
        """The folder for one library folder's originals inside another disk's trash day."""
        r = next((x for x in self.library.roots() if x["path"] == root), None)
        return ((r or {}).get("name") or os.path.basename(root)).replace("/", "_")

    def _to_trash(self, full, root, i):
        """A replaced original to the trash. On its own disk that's a rename, but the disk then keeps the original and
        the new file both: a big run filled a disk that way (hundreds of "no space left"). When the disk would stay
        under its reserve, the original goes to the trash of a library folder on another disk with room instead."""
        day, rel_dir = time.strftime("%Y-%m-%d"), os.path.dirname(i["path"])
        u = shutil.disk_usage(root)
        size = os.path.getsize(full)
        other = self._spare_disk(_dev(root), size) if u.free < int(u.total * RESERVE) else None
        if other is None:
            dest = os.path.join(root, TRASH, day, rel_dir)
            os.makedirs(dest, exist_ok=True)
            os.rename(full, free_name(os.path.join(dest, os.path.basename(full))))
            return
        dest = os.path.join(other["path"], TRASH, day, self._trash_name(root), rel_dir)
        os.makedirs(dest, exist_ok=True)
        shutil.move(full, free_name(os.path.join(dest, os.path.basename(full))))
        i["trash_on"] = other["name"]
        self.log(f"trash: {os.path.basename(full)} to the trash of {other['name']} (its disk is short of room)")

    def _relieve(self, root, need):
        """Make `need` bytes on the disk of `root` by moving its trash (oldest days first) to another disk with room.
        Nothing is deleted. Returns the bytes moved."""
        dev, moved = _dev(root), 0
        days = []
        for r in self.library.roots():
            d = os.path.join(r["path"], TRASH)
            if r["exists"] and _dev(r["path"]) == dev and os.path.isdir(d):
                days += [(day, r["path"]) for day in os.listdir(d) if os.path.isdir(os.path.join(d, day))]
        for day, rpath in sorted(days):
            if moved >= need:
                break
            src = os.path.join(rpath, TRASH, day)
            size = tree_size(src)[0]
            other = self._spare_disk(dev, size)
            if other is None:
                continue
            dest = os.path.join(other["path"], TRASH, day, self._trash_name(rpath))
            try:
                shutil.copytree(src, dest, dirs_exist_ok=True)
                shutil.rmtree(src)
            except OSError as e:
                self.log("trash: moving", src, "failed:", e)
                continue
            moved += size
            self.log(f"trash: {day} of {self._trash_name(rpath)} ({size >> 20} MB) moved to {other['name']} for room")
        return moved

    def _trash_list(self):
        """trash(), at most a minute old (it walks every trash folder)."""
        at_t, tr = self._trash_cache
        if time.time() - at_t > 60 or tr is None:
            tr = self.trash()
            self._trash_cache = (time.time(), tr)
        return tr

    def _trash_size(self):
        return sum(x["size"] for x in self._trash_list())

    def trash(self):
        out = []
        for r in self.library.roots():
            d = os.path.join(r["path"], TRASH)
            if r["exists"] and os.path.isdir(d):
                for day in sorted(os.listdir(d)):
                    size, n = tree_size(os.path.join(d, day))
                    out.append({"root": r["index"], "root_name": r["name"], "day": day, "size": size, "files": n})
        return out

    def purge_trash(self, everything=False):
        days, now, freed = self.trash_days(), time.time(), 0
        for r in self.library.roots():
            d = os.path.join(r["path"], TRASH)
            if not r["exists"] or not os.path.isdir(d):
                continue
            for day in os.listdir(d):
                try:
                    age = (now - time.mktime(time.strptime(day, "%Y-%m-%d"))) / 86400
                except ValueError:
                    continue
                if everything or age > days:
                    freed += tree_size(os.path.join(d, day))[0]
                    shutil.rmtree(os.path.join(d, day), ignore_errors=True)
            try:
                os.rmdir(d)
            except OSError:
                pass
        if freed:
            self.log(f"trash: freed {freed >> 20} MB")
        return freed
