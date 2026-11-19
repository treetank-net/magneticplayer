"""Versions of a film's subtitles on disk (agent 1.12+).

- The default version of a language is the one used last: `<name>.<lang>.srt` next to the video, which every player
  (mpv, VLC, TVs over DLNA, Jellyfin, Plex) finds by itself.
- Other versions wait in `Subtitles/` next to the video: `Subtitles/<name>.<source>.<lang>.srt`, where <source> tells
  them apart (provider and release, e.g. `opensubtitles-Punisher.S01E04.WEBRip.x264-ION10`). The video's name in front
  ties them to one episode of a season folder.
- Downloading new subtitles for a language moves the current default to `Subtitles/` (nothing is overwritten); picking
  another version (`promote`) swaps the two.
- Where each file came from (provider, id, release) is remembered in `<config>/subs-sources.json`, so search results
  can say "you have these already". The fitting assessments (mp_subsync.Store) move with the files.
- Files of a torrent that is still seeding are never moved or changed: such a version is copied out instead, and a
  default that belongs to a torrent stays where it is.
"""
import json
import os
import re
import shutil
import threading
import time

from mp_i18n import T

ALT_DIR = "Subtitles"
_lock = threading.Lock()


class FileError(Exception):
    def __init__(self, msg, status=409):
        super().__init__(msg)
        self.status = status


class Sources:
    """provider, id and release per subtitle file (by real path)."""

    def __init__(self, config_dir):
        self.path = os.path.join(config_dir, "subs-sources.json")
        self.lock = threading.Lock()
        try:
            with open(self.path) as f:
                self.rec = json.load(f)
        except (OSError, ValueError):
            self.rec = {}

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.rec, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def get(self, path):
        return self.rec.get(os.path.realpath(path))

    def put(self, path, provider, sub_id, release):
        with self.lock:
            self.rec[os.path.realpath(path)] = {"provider": provider, "id": str(sub_id), "release": release or "",
                                                "at": int(time.time())}
            self._save()

    def move(self, old, new, copy=False):
        with self.lock:
            r = self.rec.get(os.path.realpath(old))
            if r is None:
                return
            if not copy:
                self.rec.pop(os.path.realpath(old), None)
            self.rec[os.path.realpath(new)] = r
            self._save()

    def keys_for(self, paths):
        """{"provider:id", …} of these files: what the user has already."""
        out = set()
        for p in paths:
            r = self.get(p)
            if r:
                out.add(f"{r['provider']}:{r['id']}")
        return out


def _clean(s, n=60):
    s = re.sub(r"[^\w.+-]+", "-", s or "", flags=re.UNICODE).strip("-.")
    return s[:n].rstrip("-.")


def _lang_ext(full, path):
    """("pl", ".srt") of a default file `<name>.pl.srt` (lang "" for `<name>.srt`)."""
    stem = os.path.splitext(os.path.basename(full))[0]
    s, ext = os.path.splitext(os.path.basename(path))
    return (s[len(stem) + 1:] if len(s) > len(stem) else ""), ext.lower()


def alt_dir(full):
    """The existing Subtitles folder next to the video (any letter case), else the one to create."""
    d = os.path.dirname(full)
    try:
        for n in os.listdir(d):
            if n.lower() == ALT_DIR.lower() and os.path.isdir(os.path.join(d, n)):
                return os.path.join(d, n)
    except OSError:
        pass
    return os.path.join(d, ALT_DIR)


def _unique(path):
    if not os.path.exists(path):
        return path
    s, ext = os.path.splitext(path)
    n = 2
    while os.path.exists(f"{s}-{n}{ext}"):
        n += 1
    return f"{s}-{n}{ext}"


def alt_name(full, path, sources):
    """Where the default file `path` goes when it stops being the default."""
    stem = os.path.splitext(os.path.basename(full))[0]
    lang, ext = _lang_ext(full, path)
    r = sources.get(path)
    if r:
        tag = _clean(f"{r['provider']}-{r.get('release') or r['id']}")
    else:
        tag = "earlier-" + time.strftime("%Y%m%d-%H%M", time.localtime(os.path.getmtime(path)))
    return _unique(os.path.join(alt_dir(full), f"{stem}.{tag}" + (f".{lang}" if lang else "") + ext))


def demote(full, path, sources, owned, moved):
    """Moves the default `path` to Subtitles/. Returns the new path, or None when it can't move (a seeding torrent's
    file: the caller then saves the new version as an alternative instead)."""
    if not os.path.exists(path):
        return None
    if owned(path):
        return None
    with _lock:
        dest = alt_name(full, path, sources)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.move(path, dest)
        sources.move(path, dest)
        moved(path, dest)
    return dest


def promote(full, rel, sources, owned, moved, lang=None):
    """Makes the version `rel` (a path relative to the video's folder, as in the subtitle list) the default of its
    language: the current default goes to Subtitles/, `rel` takes its place. Returns the new name (relative)."""
    d = os.path.dirname(full)
    src = os.path.join(d, rel)
    if os.path.dirname(rel) == "":
        return rel  # already the default
    if not os.path.isfile(src):
        raise FileError(T("no such subtitles", "nie ma takich napisów"), 404)
    stem = os.path.splitext(os.path.basename(full))[0]
    ext = os.path.splitext(src)[1].lower()
    if lang is None:
        t = os.path.splitext(os.path.basename(src))[0].rsplit(".", 1)
        lang = t[1] if len(t) == 2 and re.fullmatch(r"[a-z]{2,3}(-[A-Za-z]{2,4})?", t[1]) else ""
    target = os.path.join(d, f"{stem}.{lang}{ext}" if lang else f"{stem}{ext}")
    with _lock:
        if os.path.exists(target):
            if owned(target):
                raise FileError(T("the current subtitles belong to a torrent that is still seeding: they stay",
                                  "obecne napisy należą do torrenta, który jeszcze seeduje: zostają"))
            dest = alt_name(full, target, sources)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.move(target, dest)
            sources.move(target, dest)
            moved(target, dest)
        if owned(src):  # a torrent's own file: a copy becomes the default, the original stays for seeding
            shutil.copy2(src, target)
            sources.move(src, target, copy=True)
            moved(src, target, copy=True)
        else:
            shutil.move(src, target)
            sources.move(src, target)
            moved(src, target)
    return os.path.basename(target)


def place(full, target, src, sources, owned, moved):
    """For mp_subs.download: frees `target` (the default name) by moving the current default to Subtitles/, and
    returns where the new file goes: `target`, or Subtitles/<name>.<source>.<lang>.srt when the current default
    belongs to a seeding torrent and must stay."""
    def same(path):
        r = sources.get(path)
        return bool(r) and r["provider"] == src["provider"] and str(r["id"]) == str(src["id"])

    if os.path.exists(target) and same(target):
        return target  # the same subtitles again: rewritten in place
    stem = os.path.splitext(os.path.basename(full))[0]
    lang, ext = _lang_ext(full, target)
    ad = alt_dir(full)
    try:
        alts = [n for n in os.listdir(ad) if n.startswith(stem + ".") and same(os.path.join(ad, n))]
    except OSError:
        alts = []
    if alts:  # already waiting in Subtitles/: swap that version in rather than keep two copies
        try:
            promote(full, os.path.relpath(os.path.join(ad, alts[0]), os.path.dirname(full)), sources, owned, moved,
                    lang=lang)
            if os.path.exists(target):
                return target
        except FileError:
            pass
    if not os.path.exists(target) or demote(full, target, sources, owned, moved):
        return target
    stem = os.path.splitext(os.path.basename(full))[0]
    lang, ext = _lang_ext(full, target)
    tag = _clean(f"{src['provider']}-{src.get('release') or src['id']}")
    return _unique(os.path.join(alt_dir(full), f"{stem}.{tag}" + (f".{lang}" if lang else "") + ext))
