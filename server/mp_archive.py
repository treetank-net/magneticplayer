"""The built-in "Internet Archive" search site: public-domain and Creative Commons films from archive.org.

search() asks archive.org's advancedsearch for `mediatype:movies` items (by default only the ones that state a licence)
and returns them with the item's torrent address. Adding one (POST /api/torrents with `torrent_url`) uses the
**.torrent file**, never a magnet: archive.org serves the data through HTTP web seeds that are listed only in the
.torrent (a magnet built from it finds no peers and never gets the metadata). fetch_torrent() downloads it (archive.org
only, size limit, timeout), parse() reads its file list and info hash, pick_video() chooses the one file worth
downloading (an item often holds the film several times over plus an ISO, thumbnails and an MP3), and placed_name()
renames that file to "Title (Year)/Title (Year).ext" so the library and the subtitle search know the year."""
import hashlib
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from mp_core import ApiError
from mp_i18n import T

SEARCH_URL = "https://archive.org/advancedsearch.php"
UA = {"User-Agent": "MagneticPlayer (home media agent; https://gitlab.com/treetank/magneticplayer)"}
FIELDS = ["identifier", "title", "year", "date", "downloads", "item_size", "licenseurl", "format"]
TORRENT_URL = re.compile(r"^https://archive\.org/download/([A-Za-z0-9][A-Za-z0-9._-]*)/([A-Za-z0-9][A-Za-z0-9._-]*)_archive\.torrent$")
TORRENT_MAX = 8 * 1024 * 1024  # an archive.org .torrent is tens to hundreds of KB; a 60 GB item's was ~300 KB
VIDEO_PREF = [".mp4", ".m4v", ".mkv", ".avi", ".ogv", ".webm", ".mov", ".mpg", ".mpeg"]

_cache, _cache_lock = {}, threading.Lock()


def torrent_url(identifier):
    return f"https://archive.org/download/{identifier}/{identifier}_archive.torrent"


def split_year(q):
    """'Night of the Living Dead 1968' → ('Night of the Living Dead', 1968); a lone year stays the phrase."""
    q = " ".join(str(q or "").split())
    m = re.search(r"[\s(\[]*\b(18[89]\d|19\d\d|20\d\d)\b[)\]]*$", q)
    if m and m.start() > 0:
        return q[:m.start()].strip(), int(m.group(1))
    return q, None


def _year(doc):
    for v in (doc.get("year"), doc.get("date")):
        if isinstance(v, list):
            v = v[0] if v else None
        m = re.match(r"\s*(1[89]\d\d|20\d\d)", str(v or ""))
        if m:
            return int(m.group(1))
    m = re.search(r"\b(18[89]\d|19\d\d|20\d\d)\b", str(doc.get("title") or ""))
    return int(m.group(1)) if m else None


def licence(url):
    """('public_domain' | 'cc' | 'other' | None, short code like 'BY-SA 3.0' for Creative Commons)."""
    u = str(url or "").lower()
    if not u:
        return None, None
    if "publicdomain" in u:
        return "public_domain", None
    m = re.search(r"creativecommons\.org/licenses/([a-z-]+)(?:/([0-9.]+))?", u)
    if m:
        return "cc", (m.group(1).upper() + (" " + m.group(2) if m.group(2) else ""))
    return "other", None


def _get_json(url, timeout=20):
    import json
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
            return json.loads(r.read(4 * 1024 * 1024))
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise ApiError(T(f"Internet Archive didn't answer ({e})", f"Internet Archive nie odpowiada ({e})"), 502)


def search(q, rows=30, any_licence=False):
    """Films on archive.org for a phrase ("Title" or "Title Year"): those with a torrent, the stated year first, then by
    downloads. any_licence=False keeps only items that state a licence (public domain or Creative Commons)."""
    phrase, year = split_year(q)
    words = re.findall(r"\w+", phrase, re.UNICODE)[:12]
    if not words:
        raise ApiError(T("type a title to search for", "wpisz tytuł do wyszukania"), 400)
    rows = max(1, min(int(rows) if str(rows).isdigit() else 30, 50))
    query = "title:(" + " ".join(words) + ") AND mediatype:movies"
    if not any_licence:
        query += " AND licenseurl:*"
    key = (query, year, rows)
    with _cache_lock:
        hit = _cache.get(key)
        if hit and time.time() - hit[0] < 600:
            return hit[1]
    params = [("q", query), ("rows", "60"), ("output", "json"), ("sort[]", "downloads desc")] + \
        [("fl[]", f) for f in FIELDS]
    data = _get_json(SEARCH_URL + "?" + urllib.parse.urlencode(params))
    docs = (data.get("response") or {}).get("docs") or []
    out = []
    for d in docs:
        ident = str(d.get("identifier") or "")
        formats = d.get("format") or []
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", ident) or "Archive BitTorrent" not in formats:
            continue  # no torrent (archive.org doesn't make one for every item)
        title = d.get("title")
        title = (title[0] if isinstance(title, list) and title else title) or ident
        kind, code = licence(d.get("licenseurl"))
        out.append({
            "id": ident, "title": str(title).strip()[:200], "year": _year(d),
            "size": int(d.get("item_size") or 0), "downloads": int(d.get("downloads") or 0),
            "licence": kind, "licence_code": code, "licence_url": d.get("licenseurl") or None,
            "page": f"https://archive.org/details/{ident}", "torrent_url": torrent_url(ident),
        })
    if year:
        out.sort(key=lambda r: (r["year"] != year, -r["downloads"]))  # stable: downloads order otherwise
    res = {"query": phrase, "year": year, "results": out[:rows], "any_licence": bool(any_licence)}
    with _cache_lock:
        if len(_cache) > 200:
            _cache.clear()
        _cache[key] = (time.time(), res)
    return res


# ---------------------------------------------------------------- the .torrent

class _ArchiveOnly(urllib.request.HTTPRedirectHandler):
    """archive.org/download/… redirects to a storage node (dnXXXX.ca.archive.org): follow only https *.archive.org."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        u = urllib.parse.urlparse(newurl)
        if u.scheme != "https" or not (u.hostname or "").endswith(".archive.org"):
            raise ApiError(T("Internet Archive redirected somewhere else", "Internet Archive przekierował gdzie indziej"), 502)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def check_url(url):
    m = TORRENT_URL.match(str(url or ""))
    if not m or m.group(1) != m.group(2):
        raise ApiError(T("only an Internet Archive item's torrent can be added this way",
                         "tak da się dodać tylko torrent pozycji z Internet Archive"), 400)
    return m.group(1)


def fetch_torrent(url, timeout=30):
    """The .torrent bytes of an archive.org item (only https://archive.org/download/<id>/<id>_archive.torrent)."""
    check_url(url)
    opener = urllib.request.build_opener(_ArchiveOnly)
    try:
        with opener.open(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
            data = r.read(TORRENT_MAX + 1)
    except urllib.error.HTTPError as e:
        raise ApiError(T(f"Internet Archive: no torrent for this item (HTTP {e.code})",
                         f"Internet Archive: ta pozycja nie ma torrenta (HTTP {e.code})"), 502)
    except (urllib.error.URLError, OSError) as e:
        raise ApiError(T(f"Internet Archive didn't answer ({e})", f"Internet Archive nie odpowiada ({e})"), 502)
    if len(data) > TORRENT_MAX or not data.startswith(b"d"):
        raise ApiError(T("Internet Archive sent something that isn't a torrent", "Internet Archive przysłał coś, co nie jest torrentem"), 502)
    return data


def _bdecode(b, i=0, spans=None, depth=0):
    """Bencode → Python (bytes keys); the byte span of the top-level "info" goes to spans["info"] (for the hash)."""
    c = b[i:i + 1]
    if c == b"i":
        j = b.index(b"e", i)
        return int(b[i + 1:j]), j + 1
    if c == b"l":
        out, i = [], i + 1
        while b[i:i + 1] != b"e":
            v, i = _bdecode(b, i, spans, depth + 1)
            out.append(v)
        return out, i + 1
    if c == b"d":
        out, i = {}, i + 1
        while b[i:i + 1] != b"e":
            k, i = _bdecode(b, i, spans, depth + 1)
            start = i
            v, i = _bdecode(b, i, spans, depth + 1)
            if depth == 0 and k == b"info" and spans is not None:
                spans["info"] = (start, i)
            out[k] = v
        return out, i + 1
    if c.isdigit():
        j = b.index(b":", i)
        n = int(b[i:j])
        return b[j + 1:j + 1 + n], j + 1 + n
    raise ValueError(f"bad bencode at {i}")


def _s(x):
    return x.decode("utf-8", "replace") if isinstance(x, bytes) else str(x)


def parse(data):
    """{"hash", "name", "files": [{"index", "path", "size", "pad"}]} in the torrent's own order (Deluge and
    libtorrent index files the same way)."""
    spans = {}
    try:
        meta, _ = _bdecode(data, 0, spans)
        info = meta[b"info"]
        start, end = spans["info"]
    except (ValueError, KeyError, IndexError, TypeError):
        raise ApiError(T("Internet Archive sent a broken torrent", "Internet Archive przysłał uszkodzony torrent"), 502)
    name = _s(info.get(b"name.utf-8") or info.get(b"name") or b"")
    files = []
    if b"files" in info:
        for k, f in enumerate(info[b"files"]):
            parts = [_s(p) for p in (f.get(b"path.utf-8") or f.get(b"path") or [])]
            files.append({"index": k, "path": "/".join([name] + parts), "size": int(f.get(b"length") or 0),
                          "pad": b"p" in (f.get(b"attr") or b"")})
    else:
        files.append({"index": 0, "path": name, "size": int(info.get(b"length") or 0), "pad": False})
    return {"hash": hashlib.sha1(data[start:end]).hexdigest(), "name": name, "files": files}


def file_formats(ident, timeout=15):
    """{file name: (format, height)} from archive.org's item metadata: the format says what the extension doesn't (an
    item's ".mp4" can be h.264 or an old MPEG-4 Part 2 upload that phones play badly). {} when it doesn't answer."""
    import json
    try:
        url = f"https://archive.org/metadata/{ident}/files"
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
            res = json.loads(r.read(8 * 1024 * 1024)).get("result") or []
    except (urllib.error.URLError, OSError, ValueError, AttributeError):
        return {}
    out = {}
    for f in res if isinstance(res, list) else []:
        try:
            out[str(f["name"])] = (str(f.get("format") or ""), int(float(f.get("height") or 0)))
        except (KeyError, TypeError, ValueError):
            continue
    return out


def _codec_rank(fmt):
    """0 = h.264/HEVC (archive.org's "h.264", "h.264 IA", "h.264 HD"…), 1 = unknown, 2 = old codecs (MPEG-4 Part 2
    "HiRes MPEG4", MPEG-1/2, Cinepak, Ogg Theora) that phones decode badly or not at all."""
    f = fmt.lower()
    if not f:
        return 1
    if "h.264" in f or "h264" in f or "hevc" in f or "h.265" in f or "matroska" in f:
        return 0
    if "hires mpeg4" in f or "mpeg2" in f or "mpeg1" in f or "cinepa" in f or "ogg" in f or "divx" in f or "xvid" in f:
        return 2
    return 1


def pick_video(files, formats=None):
    """Index of the one file to download. With `formats` (file_formats()): h.264 first, then the tallest picture,
    then the largest. Without: the largest .mp4 (usually archive.org's h.264 copy), else .mkv / .avi / .ogv / … by
    size. Skipped: ISO images, thumbnails, the low-quality "_512kb" duplicates (unless nothing else is left),
    padding. None when the item has no video."""
    formats = formats or {}
    cands = []
    for f in files:
        p = f["path"].lower()
        base, ext = os.path.splitext(p)
        if f.get("pad") or ext not in VIDEO_PREF or "/.thumbs/" in p or re.search(r"[._]thumbs?$", base):
            continue
        fmt, height = formats.get(f["path"].split("/", 1)[-1], ("", 0))
        rank = _codec_rank(fmt) if formats else (0 if ext in (".mp4", ".m4v") else 1)
        cands.append((base.endswith("_512kb") or "512kb" in fmt.lower(), rank, -height, -f["size"], f["index"]))
    if not cands:
        return None
    full = [c for c in cands if not c[0]] or cands
    return min(full, key=lambda c: c[1:4])[4]


def clean_name(title, year=None):
    """'Night of the Living Dead (1968)': what the folder and file are called (no path characters)."""
    t = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', " ", str(title or "")).strip(" .")
    t = " ".join(t.split())[:100]
    if not t:
        return ""
    year = int(year) if str(year or "").isdigit() else None
    if year and not re.search(r"\b" + str(year) + r"\b", t):
        t += f" ({int(year)})"
    return t


def placed_name(name, path):
    """The chosen file's new path inside the torrent: '<name>/<name>.<ext>'."""
    ext = os.path.splitext(path)[1].lower()
    return f"{name}/{name}{ext}"


def priorities(files, chosen):
    """Deluge file_priorities: 4 (normal) for the chosen file, 0 (skip) for the rest."""
    return [4 if f["index"] == chosen else 0 for f in files]
