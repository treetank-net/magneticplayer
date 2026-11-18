"""Język komunikatów dla użytkownika: angielski albo polski, wybierany per żądanie z nagłówka Accept-Language.

Użycie w miejscu komunikatu: `T("File not found", "Nie ma takiego pliku")`. Handler agenta woła `set_from_header()`
na początku każdego żądania (wątek serwera obsługuje jedno żądanie naraz). Wątki tła startowane z żądania
(szukanie napisów, konwersja) dostają język przez `bind(fn)`. Bez żądania (CLI): `set_from_env()` (LANG itd.).
Logi agenta zostają po polsku.
"""
import os
import threading

_local = threading.local()
DEFAULT = "en"


def norm(code):
    """pl* → "pl", wszystko inne → "en"."""
    return "pl" if str(code or "").strip().lower().startswith("pl") else "en"


def lang():
    return getattr(_local, "lang", DEFAULT)


def set_lang(code):
    _local.lang = norm(code)
    return _local.lang


def parse_accept_language(header):
    """Najwyżej oceniony język z Accept-Language (np. "pl-PL,pl;q=0.9,en;q=0.8" → "pl"); brak → angielski."""
    best, best_q = None, -1.0
    for i, part in enumerate((header or "").split(",")):
        tag, _, params = part.strip().partition(";")
        tag = tag.strip()
        if not tag or tag == "*":
            continue
        q = 1.0
        for p in params.split(";"):
            k, _, v = p.strip().partition("=")
            if k.strip() == "q":
                try:
                    q = float(v)
                except ValueError:
                    q = 0.0
        if q > best_q:  # przy równych wagach wygrywa pierwszy
            best, best_q = tag, q
    return norm(best)


def set_from_header(header):
    return set_lang(parse_accept_language(header))


def set_from_env():
    """Terminal (install.sh → --pair/--join): LC_ALL, LC_MESSAGES, LANG jak w gettext."""
    for k in ("LC_ALL", "LC_MESSAGES", "LANG"):
        if os.environ.get(k):
            return set_lang(os.environ[k])
    return set_lang(DEFAULT)


def T(en, pl):
    """Tekst w bieżącym języku."""
    return pl if lang() == "pl" else en


def bind(fn):
    """`fn` uruchomiona w innym wątku dostanie język bieżącego wątku (żądania, które ją zleciło)."""
    captured = lang()

    def run(*a, **kw):
        _local.lang = captured
        return fn(*a, **kw)
    return run
