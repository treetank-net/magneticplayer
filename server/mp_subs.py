"""Napisy przez subliminal (agregator źródeł): Podnapisi, BSPlayer, Gestdown/Addic7ed, NapiProjekt,
a z kluczem API także OpenSubtitles. subliminal siedzi w venv obok agenta (install.sh)."""
import glob
import os
import sys
import threading
import time

VENV = os.path.expanduser("~/.local/share/magneticplayer/venv")
FREE_PROVIDERS = ["podnapisi", "bsplayer", "gestdown", "napiprojekt"]  # tvsubtitles: 403


class SubsError(Exception):
    def __init__(self, msg, status=502):
        super().__init__(msg)
        self.status = status


_sl = None


def _subliminal():
    """Leniwy import subliminal z venv (agent działa na systemowym pythonie tej samej wersji)."""
    global _sl
    if _sl is None:
        for sp in glob.glob(os.path.join(VENV, "lib", "python3*", "site-packages")):
            if sp not in sys.path:
                sys.path.append(sp)
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
            configs["opensubtitlescom"] = {"apikey": self.cfg["opensubtitles_api_key"]}
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

    def search(self, full, languages):
        sl, Language = _subliminal()
        langs = set()
        for code in languages:
            try:
                langs.add(Language.fromietf(code))
            except ValueError:
                pass
        if not langs:
            raise SubsError("podaj języki, np. en,pl", 400)
        v = self._video(full)
        names, configs = self.providers()
        with sl.ProviderPool(providers=names, provider_configs=configs) as pool:
            subs = pool.list_subtitles(v, langs)
        found = {}
        results = []
        for s in subs:
            key = f"{s.provider_name}:{s.id}"
            if key in found:
                continue
            found[key] = s
            matches = s.get_matches(v)
            results.append({
                "id": str(s.id), "provider": s.provider_name, "lang": s.language.alpha2,
                "release": str(s.info or ""), "score": sl.compute_score(s, v),
                "hash_match": "hash" in matches, "hi": bool(s.hearing_impaired),
            })
        order = [l.lower() for l in languages]
        results.sort(key=lambda r: (order.index(r["lang"]) if r["lang"] in order else 99, -r["score"]))
        with self.lock:
            self.cache[full] = (time.time(), v, found)
            for k in [k for k, c in self.cache.items() if time.time() - c[0] > 3600]:
                del self.cache[k]
        title = getattr(v, "series", None) or getattr(v, "title", None) or os.path.basename(full)
        if getattr(v, "season", None) and getattr(v, "episode", None):
            title += f" S{v.season:02d}E{v.episode if isinstance(v.episode, int) else v.episode[0]:02d}"
        return {"query": title, "providers": names, "results": results[:60]}

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

    def download(self, full, sub_id, provider, languages=("en",)):
        sl, _ = _subliminal()
        key = f"{provider}:{sub_id}"
        with self.lock:
            c = self.cache.get(full)
        if not c or key not in c[2]:
            self.search(full, list(languages))
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
