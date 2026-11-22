"""Subtitles via subliminal (a provider aggregator): Podnapisi, BSPlayer, Gestdown/Addic7ed, NapiProjekt,
and with an API key also OpenSubtitles. subliminal lives in the venv next to the agent (install.sh)."""
import glob
import os
import re
import shutil
import sys
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, wait

from mp_i18n import T, bind

VENV = os.path.expanduser("~/.local/share/magneticplayer/venv")
STOPPED = ("interrupted", "przerwane")  # (en, pl) — provider state after "Enough" (stop)
BY_HASH_ONLY = {"bsplayer", "napiprojekt"}  # they search by file hash — changing the title won't help them
FREE_PROVIDERS = ["podnapisi", "bsplayer", "gestdown", "napiprojekt"]  # tvsubtitles: 403


class SubsError(Exception):
    def __init__(self, msg, status=502):
        super().__init__(msg)
        self.status = status


_sl = None


def add_venv_path():
    """Puts the agent venv site-packages on sys.path (Linux: lib/python3*/; Windows: Lib/, although the agent runs from the venv there anyway)."""
    for sp in glob.glob(os.path.join(VENV, "lib", "python3*", "site-packages")) + [os.path.join(VENV, "Lib", "site-packages")]:
        if os.path.isdir(sp) and sp not in sys.path:
            sys.path.append(sp)


def _subliminal():
    """Lazy import of subliminal from the venv (the agent runs on the system python of the same version)."""
    global _sl
    if _sl is None:
        add_venv_path()
        try:
            import subliminal
            from babelfish import Language
        except ImportError:
            raise SubsError(T("subliminal is missing — run server/install.sh again", "brak subliminal — uruchom ponownie server/install.sh"), 500)
        subliminal.region.configure("dogpile.cache.memory")
        _keep_oscom_attributes()
        _sl = (subliminal, Language)
    return _sl


def _keep_oscom_attributes():
    """subliminal reads only part of an OpenSubtitles.com result (trusted uploader, AI translation and the uploader's
    rank are commented out): keep the raw attributes on the subtitle so the list can show them."""
    try:
        from subliminal.providers.opensubtitlescom import OpenSubtitlesComSubtitle as C
    except Exception:  # noqa: BLE001 — another subliminal version: the extra signals are simply missing
        return
    if getattr(C.from_response, "_mp", False):
        return
    orig = C.from_response.__func__

    def from_response(cls, response, **kw):
        sub = orig(cls, response, **kw)
        sub.mp_attributes = response.get("attributes") or {}
        return sub
    from_response._mp = True
    C.from_response = classmethod(from_response)


def signals(s, video_fps=None):
    """What the search result itself says about a subtitle: downloads, machine translation, trusted uploader,
    frame rate (a different one than the video's usually means another release, out of sync), the film's IMDb id."""
    a = getattr(s, "mp_attributes", None) or {}
    fps = getattr(s, "fps", None) or None
    imdb = getattr(s, "movie_imdb_id", None) or getattr(s, "series_imdb_id", None)
    out = {"downloads": int(getattr(s, "download_count", 0) or 0),
           "machine": bool(getattr(s, "machine_translated", False) or a.get("ai_translated")),
           "trusted": bool(a.get("from_trusted")), "fps": round(fps, 3) if fps else None,
           "fps_off": bool(fps and video_fps and abs(fps - video_fps) > 0.05),
           "imdb": imdb if imdb and str(imdb).startswith("tt") else None}
    rank = (a.get("uploader") or {}).get("rank")
    if rank:
        out["rank"] = str(rank)
    return out


QUOTA = {"opensubtitlescom"}  # providers whose downloads count against a daily limit


def rank_key(r):
    """Best first (within a language): not machine-translated, hash match, same frame rate, among hash matches one that
    costs no download quota (NapiProjekt's hash hit is made for this very file just as well), score, trusted, downloads."""
    return (bool(r.get("machine")), not r.get("hash_match"), bool(r.get("fps_off")),
            bool(r.get("hash_match") and r.get("provider") in QUOTA), -r.get("score", 0),
            not r.get("trusted"), -(r.get("downloads") or 0))


_CUE = re.compile(r"(\d+):(\d\d):(\d\d)[,.](\d+)\s*-->\s*(\d+):(\d\d):(\d\d)[,.](\d+)[^\n]*\n(.*?)(?:\n\s*\n|\Z)", re.S)


def _broken(sub):
    """An SRT where nearly every cue lasts zero time or has no text (such uploads exist on OpenSubtitles: 750 cues at
    00:00 with a space). Other formats are not judged."""
    try:
        text = sub.text or ""
    except Exception:  # noqa: BLE001 — undecodable: let subliminal deal with it
        return False
    cues = _CUE.findall(text)
    if len(cues) < 10:
        return False
    def ms(h, m, s, f):
        return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(f[:3].ljust(3, "0"))
    bad = sum(1 for c in cues if ms(*c[4:8]) <= ms(*c[:4]) or not c[8].strip())
    return bad > 0.8 * len(cues)


def _video_fps(video):
    """Frame rate of the video (ffprobe), for frame-based subtitles without their own declaration."""
    import subprocess
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate",
                              "-of", "csv=p=0", video], capture_output=True, text=True, timeout=30).stdout.strip()
        n, _, d = out.partition("/")
        fps = float(n) / float(d or 1)
        return fps if 5 < fps < 121 else None
    except Exception:  # noqa: BLE001
        return None


def _ts(sec, sep=","):
    ms = max(0, round(sec * 1000))
    return "%02d:%02d:%02d%s%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, sep, ms % 1000)


def _cue_text(t):
    # MicroDVD/MPL2/TMPlayer: "|" separates lines, "/" marks italics, {y:i}-style codes are dropped
    lines = [re.sub(r"\{[^}]*\}", "", x).strip() for x in t.split("|")]
    return "\n".join("<i>%s</i>" % x[1:].strip() if x.startswith("/") else x for x in lines if x)


def readable_srt(path, video=None):
    """Text of a sidecar ".srt" as real UTF-8 SubRip. Many files named .srt are something else: MicroDVD
    ({frame}{frame}text, from NapiProjekt and old OpenSubtitles uploads), MPL2 ([ds][ds]text) or TMPlayer (h:mm:ss:text),
    often in cp1250. mpv reads them all, the phone (SubRip) and browsers show nothing. The file itself is not changed."""
    with open(path, "rb") as f:
        return to_srt(f.read(), video)


_place_lock = threading.Lock()


def release_name(s):
    """The release a subtitle was made for, or "" when the provider only gives a file hash (NapiProjekt): a hash says
    nothing to people."""
    r = str(s.info or "").strip()
    return "" if re.fullmatch(r"[0-9a-fA-F]{32,40}", r) else r


def to_srt(raw, video=None):
    """Bytes or text of subtitles in any of those formats → SubRip text (unchanged when no format was recognised)."""
    if isinstance(raw, str):
        raw = raw.encode("utf-8")
    for enc in ("utf-8-sig", "cp1250", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if re.search(r"\d+:\d\d:\d\d[,.]\d+\s*-->", text):
        return text
    cues = []
    micro = re.findall(r"^\{(\d+)\}\{(\d*)\}(.*)$", text, re.M)
    if micro:
        fps = None
        if micro[0][0] in ("0", "1") and re.fullmatch(r"\d+(\.\d+)?", micro[0][2].strip()):
            fps = float(micro[0][2])  # {1}{1}25.000: the frame rate the file was made for
            micro = micro[1:]
        fps = fps or (video and _video_fps(video)) or 23.976
        cues = [(int(a) / fps, int(b or int(a) + 3 * fps) / fps, t) for a, b, t in micro]
    else:
        mpl = re.findall(r"^\[(\d+)\]\[(\d*)\](.*)$", text, re.M)
        if mpl:
            cues = [(int(a) / 10, int(b or int(a) + 30) / 10, t) for a, b, t in mpl]
        else:
            tmp = [(int(h) * 3600 + int(m) * 60 + int(sec), t)
                   for h, m, sec, t in re.findall(r"^(\d{1,2}):(\d\d):(\d\d)[:=](.*)$", text, re.M)]
            # TMPlayer has no end: until the next line, at most 5 s
            cues = [(a, min(a + 5, tmp[i + 1][0]) if i + 1 < len(tmp) else a + 5, t) for i, (a, t) in enumerate(tmp)]
    if not cues:
        return text
    out = []
    for a, b, t in cues:
        t = _cue_text(t)
        if t and b > a:
            out.append("%d\n%s --> %s\n%s\n" % (len(out) + 1, _ts(a), _ts(b), t))
    return "\n".join(out)


class Subtitles:
    def __init__(self, cfg, save_cfg):
        self.cfg = cfg
        self.save_cfg = save_cfg
        self.lock = threading.Lock()
        self.jobs = {}  # id -> progress of the background search
        self.cache = {}  # path -> (time, video, {provider:id: subtitle})

    # ---- configuration (OpenSubtitles optional)
    def set_credentials(self, api_key, username, password):
        if api_key is not None and api_key.strip():
            self.cfg["opensubtitles_api_key"] = api_key.strip()
        if username is not None:
            self.cfg["opensubtitles_username"] = username.strip()
        if password:
            self.cfg["opensubtitles_password"] = password
        self.save_cfg()

    def providers(self):
        p = list(FREE_PROVIDERS)
        configs = {}
        if self.cfg.get("opensubtitles_api_key"):
            p.insert(0, "opensubtitlescom")
            # max_result_pages: with no hits OpenSubtitles returns hundreds of "similar" results — further pages are junk
            configs["opensubtitlescom"] = {"apikey": self.cfg["opensubtitles_api_key"], "max_result_pages": 3}
            # account optional: without it downloads are anonymous (key limit; with "Under dev" 100/day)
            if self.cfg.get("opensubtitles_username") and self.cfg.get("opensubtitles_password"):
                configs["opensubtitlescom"].update(username=self.cfg["opensubtitles_username"],
                                                   password=self.cfg["opensubtitles_password"])
        return p, configs

    # ---- searching / downloading
    def _video(self, full):
        sl, _ = _subliminal()
        if not os.path.exists(full):
            raise SubsError(T("the file isn't on disk yet", "pliku jeszcze nie ma na dysku"), 404)
        v = sl.scan_video(full)
        names, _ = self.providers()
        try:
            sl.refine(v, refiners=["hash", "metadata"], providers=names)
        except Exception:  # noqa: BLE001 — searching by name works without the hash too
            pass
        return v

    @staticmethod
    def _is_episode(v):
        return getattr(v, "series", None) is not None

    def _set_title(self, v, title, year):
        if title:
            if self._is_episode(v):
                v.series = title
            else:
                v.title = title
        if year:
            v.year = int(year)

    @staticmethod
    def _alt_title(full):
        """Title in square brackets, e.g. "Der Golem [The Golem] ..." → "The Golem"."""
        m = re.search(r"\[([^\]\d][^\]]{2,60})\]", os.path.basename(full))
        if m and re.search(r"[A-Za-z]{3}", m.group(1)) and not re.search(r"(?i)\b(1080p|720p|2160p|x26[45]|hevc|av1|web|bluray)\b", m.group(1)):
            return m.group(1).strip()
        return None

    def _list(self, v, langs, progress=None, skip=()):
        """Each provider in its own thread (faster, and progress can be shown per provider)."""
        sl, _ = _subliminal()
        names, configs = self.providers()
        prov = (progress if progress is not None else {}).setdefault("providers", {})
        for n in names:
            prov[n] = {"state": "skip", "error": T("searches by hash — no change", "szuka po hashu — bez zmian")} if n in skip else {"state": "pending"}
        names = [n for n in names if n not in skip]

        stopped = set(STOPPED)

        def one(name):
            if (progress or {}).get("stop"):
                prov[name] = {"state": "skip", "error": T(*STOPPED)}
                return []
            prov[name] = {"state": "running"}
            t0 = time.time()
            try:
                with sl.ProviderPool(providers=[name], provider_configs=configs) as pool:
                    res = pool.list_subtitles_provider(name, v, langs)
                status = getattr(getattr(res, "status", None), "name", "OK")
                subs = list(getattr(res, "subtitles", None) or [])
                state = {"UNSUPPORTED_VIDEO": "skip", "UNSUPPORTED_LANGUAGES": "skip",
                         "OUTAGE": "error", "FAILURE": "error"}.get(status, "done")
                why = {"UNSUPPORTED_VIDEO": T("not for this type of video", "nie dla tego typu wideo"),
                       "UNSUPPORTED_LANGUAGES": T("doesn't have these languages", "nie ma tych języków"),
                       "OUTAGE": T("service is down", "serwis nie działa"),
                       "FAILURE": T("error or no connection", "błąd lub brak połączenia")}.get(status)
                if prov[name].get("error") not in stopped:  # "Enough" (stop) — nobody cares about the result any more
                    prov[name] = {"state": state, "count": len(subs), "secs": round(time.time() - t0, 1),
                                  **({"error": why} if why else {})}
                return subs
            except Exception as e:  # noqa: BLE001
                if prov[name].get("error") not in stopped:
                    prov[name] = {"state": "error", "error": str(e)[:120], "secs": round(time.time() - t0, 1)}
                return []

        ex = ThreadPoolExecutor(max_workers=max(1, len(names)))
        futs = {ex.submit(bind(one), n): n for n in names}
        pending, subs = set(futs), []
        while pending:
            done, pending = wait(pending, timeout=0.4)
            for f in done:
                subs += f.result()
            if pending and (progress or {}).get("stop"):
                for f in pending:  # the threads will finish in the background, but we no longer wait for them
                    prov[futs[f]] = {"state": "skip", "error": T(*STOPPED)}
                break
        ex.shutdown(wait=False)
        found, results = {}, []
        vfps = _video_fps(v.name) if any(getattr(s, "fps", None) for s in subs) else None
        if progress is not None:
            progress.update(phase="match", matched=0, total=len(subs))
        for i, s in enumerate(subs):
            if progress is not None:
                progress["matched"] = i
            key = f"{s.provider_name}:{s.id}"
            if key in found:
                continue
            found[key] = s
            matches = s.get_matches(v)
            results.append({
                "id": str(s.id), "provider": s.provider_name, "lang": s.language.alpha2,
                "release": release_name(s), "score": sl.compute_score(s, v),
                "hash_match": "hash" in matches, "hi": bool(s.hearing_impaired),
                # with no hits OpenSubtitles returns "something similar" (Der Golem → a completely different film) — without
                # a matching title/series and without a hash these are almost certainly subtitles for another film
                "title_match": "hash" in matches or ("series" if self._is_episode(v) else "title") in matches
                or bool(matches & {"imdb_id", "series_imdb_id"}),
                **signals(s, vfps),
            })
        return found, results

    def hash_ids(self, full):
        """IMDb ids OpenSubtitles.com files subtitles of exactly this file under (a search by the file's hash only, no
        download): [(imdb, how many subtitles)], most first — the series' id for an episode; None when it can't be
        asked (no API key, an error)."""
        if not self.cfg.get("opensubtitles_api_key"):
            return None
        sl, Language = _subliminal()
        from subliminal.providers.opensubtitlescom import OpenSubtitlesComProvider
        from subliminal.refiners.hash import hash_opensubtitles
        h = hash_opensubtitles(full)
        if not h:
            return None
        p = OpenSubtitlesComProvider(apikey=self.cfg["opensubtitles_api_key"])
        try:
            p.initialize()
            subs = p.query({Language(x) for x in ("eng", "pol", "fra", "spa", "deu")}, moviehash=h)
        except Exception:  # noqa: BLE001 — offline, rate limit: try again another time
            return None
        finally:
            try:
                p.terminate()
            except Exception:  # noqa: BLE001
                pass
        n = {}
        for s in subs:
            i = getattr(s, "movie_imdb_id", None)
            if getattr(s, "movie_kind", None) == "episode":
                i = getattr(s, "series_imdb_id", None)
            if getattr(s, "moviehash_match", False) and i and str(i).startswith("tt"):
                n[i] = n.get(i, 0) + 1
        return sorted(n.items(), key=lambda x: -x[1])

    # ---- background search with progress (the app polls /api/subs/job)
    def start_search(self, full, languages, title=None, year=None, known=None):
        jid = uuid.uuid4().hex[:12]
        job = {"id": jid, "phase": "file", "started": time.time(), "done": False}
        with self.lock:
            for k in [k for k, j in self.jobs.items() if time.time() - j["started"] > 1800]:
                del self.jobs[k]
            self.jobs[jid] = job

        def run():
            try:
                job["result"] = self.search(full, languages, title, year, progress=job, known=known)
            except SubsError as e:
                job["error"] = str(e)
            except Exception as e:  # noqa: BLE001
                job["error"] = repr(e)
            job["phase"] = "done"
            job["done"] = True
        threading.Thread(target=bind(run), daemon=True).start()
        return jid

    def start_job(self, fn, phase):
        """Some work in the background with progress the app polls (/api/subs/job): `fn(job)` returns the result and may
        set job["phase"]."""
        jid = uuid.uuid4().hex[:12]
        job = {"id": jid, "phase": phase, "started": time.time(), "done": False}
        with self.lock:
            self.jobs[jid] = job

        def run():
            try:
                job["result"] = fn(job)
            except SubsError as e:
                job["error"] = str(e)
            except Exception as e:  # noqa: BLE001 — the app shows it
                job["error"] = str(e) or repr(e)
            job["phase"] = "done"
            job["done"] = True
        threading.Thread(target=bind(run), daemon=True).start()
        return jid

    def stop(self, jid):
        """\"Enough\": don't wait for slow providers, show what is already there (no second round)."""
        j = self.jobs.get(jid)
        if not j:
            raise SubsError(T("no such search", "nie ma takiego wyszukiwania"), 404)
        j["stop"] = True

    def job(self, jid):
        j = self.jobs.get(jid)
        if not j:
            raise SubsError(T("no such search (did the agent restart?)", "nie ma takiego wyszukiwania (agent restartował?)"), 404)
        out = dict(j)
        out["providers"] = {k: dict(v) for k, v in (j.get("providers") or {}).items()}
        out["elapsed"] = round(time.time() - j["started"], 1)
        return out

    def search(self, full, languages, title=None, year=None, progress=None, known=None):
        """`known` (agent 1.17.2+, from the catalog: imdb, kind, year, titles): when the file's hash and name find
        nothing for this title, one more round asks by the catalog's titles, year and IMDb id (what typing the title
        by hand did: a file named oddly, a title only known in another language)."""
        progress = progress if progress is not None else {}
        _, Language = _subliminal()
        langs = set()
        for code in languages:
            try:
                langs.add(Language.fromietf(code))
            except ValueError:
                pass
        if not langs:
            raise SubsError(T("give languages, e.g. en,pl", "podaj języki, np. en,pl"), 400)
        progress["phase"] = "file"  # file hash + title recognition
        v = self._video(full)
        self._set_title(v, title, year)
        progress["query"] = v.series if self._is_episode(v) else v.title
        progress["phase"] = "search"
        progress["round"] = 1
        found, results = self._list(v, langs, progress)
        alt = None if title or progress.get("stop") else self._alt_title(full)
        if alt and not any(r["title_match"] for r in results):
            orig = v.series if self._is_episode(v) else v.title
            self._set_title(v, alt, None)
            progress["query"] = alt
            progress["phase"] = "search"
            progress["round"] = 2  # second round with the title from [brackets]
            f2, r2 = self._list(v, langs, progress, skip=BY_HASH_ONLY)
            if any(r["title_match"] for r in r2):
                found, results = f2, r2
            else:
                self._set_title(v, orig, None)
        if known and known.get("titles") and not title and not progress.get("stop") \
                and not any(r["title_match"] for r in results):
            ep = self._is_episode(v)
            orig, orig_year, orig_ids = (v.series if ep else v.title), getattr(v, "year", None), dict(v.external_ids or {})
            v.external_ids = dict(orig_ids, **{"series_imdb_id" if ep else "imdb_id": known["imdb"]})
            hit = False
            for t in [t for t in known["titles"] if t != orig][:2] or known["titles"][:1]:
                self._set_title(v, t, None if ep else known.get("year"))
                progress.update(query=t, phase="search", round=2)  # the app: "second round with the title …"
                f3, r3 = self._list(v, langs, progress, skip=BY_HASH_ONLY)
                hit = any(r["title_match"] for r in r3)
                if hit:
                    found, results = f3, r3
                if hit or progress.get("stop"):
                    break
            if not hit:
                self._set_title(v, orig, None)
                v.year, v.external_ids = orig_year, orig_ids
        names, _ = self.providers()
        hidden = sum(1 for r in results if not r["title_match"])
        results = [r for r in results if r["title_match"]]
        order = [l.lower() for l in languages]
        results.sort(key=lambda r: (order.index(r["lang"]) if r["lang"] in order else 99, *rank_key(r)))
        with self.lock:
            self.cache[full] = (time.time(), v, found)
            for k in [k for k, c in self.cache.items() if time.time() - c[0] > 3600]:
                del self.cache[k]
        q = (v.series if self._is_episode(v) else v.title) or os.path.basename(full)
        query = q
        if getattr(v, "season", None) and getattr(v, "episode", None):
            query += f" S{v.season:02d}E{v.episode if isinstance(v.episode, int) else v.episode[0]:02d}"
        return {"query": query, "title": q, "year": getattr(v, "year", None), "providers": names,
                "results": results[:60], "hidden": hidden}

    @staticmethod
    def _oscom_anonymous(pool, s):
        """subliminal enforces a login (@requires_auth) when downloading from OpenSubtitles, while the API allows
        downloading with the key alone — we call the method without the decorator."""
        from subliminal.exceptions import DownloadLimitExceeded as DownloadLimitReached
        prov = pool["opensubtitlescom"]
        try:
            type(prov).download_subtitle.__wrapped__(prov, s)
        except DownloadLimitReached:
            raise SubsError(T("OpenSubtitles: daily download limit reached", "OpenSubtitles: dzienny limit pobrań wyczerpany"), 429)
        except Exception as e:  # noqa: BLE001
            raise SubsError(f"OpenSubtitles: {e}", 502)
        if not s.content:
            raise SubsError(T("OpenSubtitles returned no subtitles", "OpenSubtitles nie oddał napisów"), 502)

    def download(self, full, sub_id, provider, languages=("en",), title=None, year=None, place=None, prepare=None,
                 moved=None):
        """Downloads one result and saves it as `<name>.<lang>.srt` next to the video. `place(path)` (agent 1.12+,
        mp_subfiles) may first move the current default away and returns where to write: that path, or another one
        (Subtitles/…) when the default can't move. `prepare(path)` (agent 1.12.1+: fitting to the film) runs on the file
        in a temporary folder first, so a player never sees it next to the video before it is fitted; it returns a dict
        with the file's "name" (fitting may rename a .sub to .srt), and `moved(old, new)` follows the file to its place.
        Returns {"name" (relative to the video's folder), "provider", "path", "release", "prepared"}."""
        sl, _ = _subliminal()
        key = f"{provider}:{sub_id}"
        with self.lock:
            c = self.cache.get(full)
        if not c or key not in c[2]:
            self.search(full, list(languages), title, year)
            with self.lock:
                c = self.cache.get(full)
            if not c or key not in c[2]:
                raise SubsError(T("these subtitles are no longer in the results — search again",
                                  "tych napisów już nie ma w wynikach — szukaj jeszcze raz"), 404)
        _, v, found = c
        s = found[key]
        names, configs = self.providers()
        with sl.ProviderPool(providers=names, provider_configs=configs) as pool:
            if provider == "opensubtitlescom" and "username" not in configs.get(provider, {}):
                self._oscom_anonymous(pool, s)
            elif not pool.download_subtitle(s) or not s.content:
                raise SubsError(T(f"{provider} returned no subtitles (download limit?)", f"{provider} nie oddał napisów (limit pobrań?)"), 502)
        if _broken(s):
            raise SubsError(T("these subtitles are empty (a broken upload at the provider) — pick others",
                              "te napisy są puste (zepsuty plik u dostawcy) — wybierz inne"), 422)
        d = os.path.dirname(full)
        try:
            target = s.get_path(v)
            # NapiProjekt gives MPL2 (subliminal names it .mpl), others MicroDVD or TMPlayer: the phone, browsers and
            # TVs read only SubRip, so such subtitles are saved as a real .srt
            text = to_srt(s.content or s.text.encode("utf-8"), full)
            if re.search(r"\d+:\d\d:\d\d,\d+ -->", text):
                target = os.path.splitext(target)[0] + ".srt"
            else:
                text = s.text
            release = release_name(s)
            prepared = None
            tmp_dir = tempfile.mkdtemp(prefix="mp-subs-")
            try:
                tmp = os.path.join(tmp_dir, os.path.basename(target))
                with open(tmp, "w", encoding="utf-8") as f:
                    f.write(text)
                if prepare is not None:
                    prepared = prepare(tmp)
                    if (prepared or {}).get("name"):
                        tmp = os.path.join(tmp_dir, prepared["name"])
                        target = os.path.join(d, prepared["name"])
                with _place_lock:  # two downloads at once (two phones): one after the other, each sees the other's file
                    where = target
                    if place is not None:
                        where = place(target, {"provider": provider, "id": str(sub_id), "release": release})
                    os.makedirs(os.path.dirname(where), exist_ok=True)
                    shutil.move(tmp, where)
                    if moved is not None:
                        moved(tmp, where)
            finally:
                shutil.rmtree(tmp_dir, ignore_errors=True)
            if prepared is not None:
                prepared["name"] = os.path.relpath(where, d)
            return {"name": os.path.relpath(where, d), "provider": provider, "path": where, "release": release,
                    "prepared": prepared}
        except OSError as e:
            raise SubsError(T(f"can't save subtitles next to the video: {e.strerror}",
                              f"nie da się zapisać napisów obok filmu: {e.strerror}"), 500)
