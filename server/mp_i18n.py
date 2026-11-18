"""Language of user-facing messages: English or Polish, chosen per request from the Accept-Language header.

Usage in place of a message: `T("File not found", "Nie ma takiego pliku")`. The agent's handler calls
`set_from_header()` at the start of every request (a server thread handles one request at a time). Background threads
started from a request (subtitle search, conversion) get the language via `bind(fn)`. Without a request (CLI):
`set_from_env()` (LANG etc.). Agent logs are always in English.
"""
import os
import threading

_local = threading.local()
DEFAULT = "en"


def norm(code):
    """pl* → "pl", anything else → "en"."""
    return "pl" if str(code or "").strip().lower().startswith("pl") else "en"


def lang():
    return getattr(_local, "lang", DEFAULT)


def set_lang(code):
    _local.lang = norm(code)
    return _local.lang


def parse_accept_language(header):
    """Highest-ranked language from Accept-Language (e.g. "pl-PL,pl;q=0.9,en;q=0.8" → "pl"); none → English."""
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
        if q > best_q:  # with equal weights the first one wins
            best, best_q = tag, q
    return norm(best)


def set_from_header(header):
    return set_lang(parse_accept_language(header))


def set_from_env():
    """Terminal (install.sh → --pair/--join): LC_ALL, LC_MESSAGES, LANG as in gettext."""
    for k in ("LC_ALL", "LC_MESSAGES", "LANG"):
        if os.environ.get(k):
            return set_lang(os.environ[k])
    return set_lang(DEFAULT)


def T(en, pl):
    """Text in the current language."""
    return pl if lang() == "pl" else en


def bind(fn):
    """`fn` run in another thread gets the language of the current thread (of the request that scheduled it)."""
    captured = lang()

    def run(*a, **kw):
        _local.lang = captured
        return fn(*a, **kw)
    return run
