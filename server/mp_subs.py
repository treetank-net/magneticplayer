"""Napisy przez subliminal (agregator źródeł): Podnapisi, BSPlayer, Gestdown/Addic7ed, NapiProjekt,
a z kluczem API także OpenSubtitles. subliminal siedzi w venv obok agenta (install.sh)."""
import glob
import os
import re
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, wait

VENV = os.path.expanduser("~/.local/share/magneticplayer/venv")
BY_HASH_ONLY = {"bsplayer", "napiprojekt"}  # szukają po hashu pliku — zmiana tytułu nic im nie da
FREE_PROVIDERS = ["podnapisi", "bsplayer", "gestdown", "napiprojekt"]  # tvsubtitles: 403


class SubsError(Exception):
    def __init__(self, msg, status=502):
        super().__init__(msg)
        self.status = status


_sl = None


def add_venv_path():
    """site-packages venv agenta na sys.path (Linux: lib/python3*/; Windows: Lib/, choć tam agent i tak chodzi z venv)."""
    for sp in glob.glob(os.path.join(VENV, "lib", "python3*", "site-packages")) + [os.path.join(VENV, "Lib", "site-packages")]:
        if os.path.isdir(sp) and sp not in sys.path:
            sys.path.append(sp)


def _subliminal():
    """Leniwy import subliminal z venv (agent działa na systemowym pythonie tej samej wersji)."""
    global _sl
    if _sl is None:
        add_venv_path()
        try:
            import subliminal
            from babelfish import Language
        except ImportError:
            raise SubsError("brak subliminal — uruchom ponownie server/install.sh", 500)
        subliminal.region.configure("dogpile.cache.memory")
        _sl = (subliminal, Language)
    return _sl


class Subtitles:
    def __init__(self, cfg, save_cfg):
        self.cfg = cfg
        self.save_cfg = save_cfg
        self.lock = threading.Lock()
        self.jobs = {}  # id -> postęp wyszukiwania w tle
        self.cache = {}  # ścieżka -> (czas, video, {provider:id: subtitle})

    # ---- konfiguracja (OpenSubtitles opcjonalnie)
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
            # max_result_pages: przy braku trafień OpenSubtitles oddaje setki „podobnych” — dalsze strony to śmieci
            configs["opensubtitlescom"] = {"apikey": self.cfg["opensubtitles_api_key"], "max_result_pages": 3}
            # konto opcjonalne: bez niego pobrania anonimowe (limit klucza, z „Under dev” 100/dobę)
            if self.cfg.get("opensubtitles_username") and self.cfg.get("opensubtitles_password"):
                configs["opensubtitlescom"].update(username=self.cfg["opensubtitles_username"],
                                                   password=self.cfg["opensubtitles_password"])
        return p, configs

    # ---- szukanie / pobieranie
    def _video(self, full):
        sl, _ = _subliminal()
        if not os.path.exists(full):
            raise SubsError("pliku jeszcze nie ma na dysku", 404)
        v = sl.scan_video(full)
        names, _ = self.providers()
        try:
            sl.refine(v, refiners=["hash", "metadata"], providers=names)
        except Exception:  # noqa: BLE001 — bez hasha też da się szukać po nazwie
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
        """Tytuł w nawiasie kwadratowym, np. „Der Golem [The Golem] …” → „The Golem”."""
        m = re.search(r"\[([^\]\d][^\]]{2,60})\]", os.path.basename(full))
        if m and re.search(r"[A-Za-z]{3}", m.group(1)) and not re.search(r"(?i)\b(1080p|720p|2160p|x26[45]|hevc|av1|web|bluray)\b", m.group(1)):
            return m.group(1).strip()
        return None

    def _list(self, v, langs, progress=None, skip=()):
        """Każde źródło w osobnym wątku (szybciej, i da się pokazać postęp per źródło)."""
        sl, _ = _subliminal()
        names, configs = self.providers()
        prov = (progress if progress is not None else {}).setdefault("providers", {})
        for n in names:
            prov[n] = {"state": "skip", "error": "szuka po hashu — bez zmian"} if n in skip else {"state": "pending"}
        names = [n for n in names if n not in skip]

        stopped = {"przerwane"}

        def one(name):
            if (progress or {}).get("stop"):
                prov[name] = {"state": "skip", "error": "przerwane"}
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
                why = {"UNSUPPORTED_VIDEO": "nie dla tego typu wideo", "UNSUPPORTED_LANGUAGES": "nie ma tych języków",
                       "OUTAGE": "serwis nie działa", "FAILURE": "błąd lub brak połączenia"}.get(status)
                if prov[name].get("error") not in stopped:  # „Wystarczy” — wynik już nikogo nie obchodzi
                    prov[name] = {"state": state, "count": len(subs), "secs": round(time.time() - t0, 1),
                                  **({"error": why} if why else {})}
                return subs
            except Exception as e:  # noqa: BLE001
                if prov[name].get("error") not in stopped:
                    prov[name] = {"state": "error", "error": str(e)[:120], "secs": round(time.time() - t0, 1)}
                return []

        ex = ThreadPoolExecutor(max_workers=max(1, len(names)))
        futs = {ex.submit(one, n): n for n in names}
        pending, subs = set(futs), []
        while pending:
            done, pending = wait(pending, timeout=0.4)
            for f in done:
                subs += f.result()
            if pending and (progress or {}).get("stop"):
                for f in pending:  # wątki dobiegną w tle, ale już na nie nie czekamy
                    prov[futs[f]] = {"state": "skip", "error": "przerwane"}
                break
        ex.shutdown(wait=False)
        found, results = {}, []
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
                "release": str(s.info or ""), "score": sl.compute_score(s, v),
                "hash_match": "hash" in matches, "hi": bool(s.hearing_impaired),
                # OpenSubtitles przy braku trafień oddaje „coś podobnego” (Der Golem → zupełnie inny film) — bez
                # zgodnego tytułu/serialu i bez hasha to prawie na pewno napisy do innego filmu
                "title_match": "hash" in matches or ("series" if self._is_episode(v) else "title") in matches,
            })
        return found, results

    # ---- wyszukiwanie w tle z podglądem postępu (apka odpytuje /api/subs/job)
    def start_search(self, full, languages, title=None, year=None):
        jid = uuid.uuid4().hex[:12]
        job = {"id": jid, "phase": "file", "started": time.time(), "done": False}
        with self.lock:
            for k in [k for k, j in self.jobs.items() if time.time() - j["started"] > 1800]:
                del self.jobs[k]
            self.jobs[jid] = job

        def run():
            try:
                job["result"] = self.search(full, languages, title, year, progress=job)
            except SubsError as e:
                job["error"] = str(e)
            except Exception as e:  # noqa: BLE001
                job["error"] = repr(e)
            job["phase"] = "done"
            job["done"] = True
        threading.Thread(target=run, daemon=True).start()
        return jid

    def stop(self, jid):
        """„Wystarczy”: nie czekaj na wolne źródła, pokaż to, co już jest (bez drugiej rundy)."""
        j = self.jobs.get(jid)
        if not j:
            raise SubsError("nie ma takiego wyszukiwania", 404)
        j["stop"] = True

    def job(self, jid):
        j = self.jobs.get(jid)
        if not j:
            raise SubsError("nie ma takiego wyszukiwania (agent restartował?)", 404)
        out = dict(j)
        out["providers"] = {k: dict(v) for k, v in (j.get("providers") or {}).items()}
        out["elapsed"] = round(time.time() - j["started"], 1)
        return out

    def search(self, full, languages, title=None, year=None, progress=None):
        progress = progress if progress is not None else {}
        _, Language = _subliminal()
        langs = set()
        for code in languages:
            try:
                langs.add(Language.fromietf(code))
            except ValueError:
                pass
        if not langs:
            raise SubsError("podaj języki, np. en,pl", 400)
        progress["phase"] = "file"  # hash pliku + rozpoznanie tytułu
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
            progress["round"] = 2  # druga runda z tytułem z [nawiasu]
            f2, r2 = self._list(v, langs, progress, skip=BY_HASH_ONLY)
            if any(r["title_match"] for r in r2):
                found, results = f2, r2
            else:
                self._set_title(v, orig, None)
        names, _ = self.providers()
        hidden = sum(1 for r in results if not r["title_match"])
        results = [r for r in results if r["title_match"]]
        order = [l.lower() for l in languages]
        results.sort(key=lambda r: (order.index(r["lang"]) if r["lang"] in order else 99, -r["score"]))
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
        """subliminal wymusza login (@requires_auth) przy pobieraniu z OpenSubtitles, a API pozwala pobierać
        z samym kluczem — wołamy metodę bez dekoratora."""
        from subliminal.exceptions import DownloadLimitExceeded as DownloadLimitReached
        prov = pool["opensubtitlescom"]
        try:
            type(prov).download_subtitle.__wrapped__(prov, s)
        except DownloadLimitReached:
            raise SubsError("OpenSubtitles: dzienny limit pobrań wyczerpany", 429)
        except Exception as e:  # noqa: BLE001
            raise SubsError(f"OpenSubtitles: {e}", 502)
        if not s.content:
            raise SubsError("OpenSubtitles nie oddał napisów", 502)

    def download(self, full, sub_id, provider, languages=("en",), title=None, year=None):
        sl, _ = _subliminal()
        key = f"{provider}:{sub_id}"
        with self.lock:
            c = self.cache.get(full)
        if not c or key not in c[2]:
            self.search(full, list(languages), title, year)
            with self.lock:
                c = self.cache.get(full)
            if not c or key not in c[2]:
                raise SubsError("tych napisów już nie ma w wynikach — szukaj jeszcze raz", 404)
        _, v, found = c
        s = found[key]
        names, configs = self.providers()
        with sl.ProviderPool(providers=names, provider_configs=configs) as pool:
            if provider == "opensubtitlescom" and "username" not in configs.get(provider, {}):
                self._oscom_anonymous(pool, s)
            elif not pool.download_subtitle(s) or not s.content:
                raise SubsError(f"{provider} nie oddał napisów (limit pobrań?)", 502)
        before = time.time() - 1
        try:
            sl.save_subtitles(v, [s], encoding="utf-8")
        except OSError as e:
            raise SubsError(f"nie da się zapisać napisów obok filmu: {e.strerror}", 500)
        d = os.path.dirname(full)
        stem = os.path.splitext(os.path.basename(full))[0]
        saved = sorted((n for n in os.listdir(d) if n.startswith(stem) and n != os.path.basename(full)
                        and os.path.getmtime(os.path.join(d, n)) >= before),
                       key=lambda n: os.path.getmtime(os.path.join(d, n)))
        return {"name": saved[-1] if saved else None, "provider": provider}
