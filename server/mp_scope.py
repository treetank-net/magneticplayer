"""Task scopes (agent 1.16+, docs/plan-ux-tasks.md F1): which files a task list, a counter or a plan covers.

A scope is a JSON object; absent or `{}` means the whole machine:

    {"roots": [{"root": 0, "path": "Series/X"}],   folders (path "" = the whole library folder), OR among themselves
     "files": [{"root": 0, "path": "Series/X/S01E01.mkv"}],   an exact set of files
     "imdb": "tt0000000",                           a catalog title: all its files on this server
     "tag": "anime",                                a folder tag, a grouping folder ("Anime/") or a genre
     "age": [0, 7]}                                 catalog age groups (0, 7, 12, 16, 18)

`roots`, `tag` and `age` are filters combined with AND; `files` and `imdb` are exact sets, intersected with the
filters when both are present. Folders are given by their library folder's index (as in /lib/<index>/ and the app's
references). A file with no catalog title never matches `imdb`, `tag` (unless its folder carries the tag) or `age`.
"""
import json
import re

from mp_i18n import T

AGE_GROUPS = (0, 7, 12, 16, 18)


def _pl(n, few, many):
    """The Polish plural for n >= 2: "2 pliki", "5 plików", "22 pliki", "12 plików"."""
    return few if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14) else many


class ScopeError(ValueError):
    status = 400


def age_group(age):
    """A catalog minimum age (FSK/MPAA: 0, 6, 12, 16, 18) → its group on the boards: 0, 7, 12, 16 or 18."""
    if age is None:
        return None
    age = int(age)
    return 0 if age < 6 else 7 if age < 12 else 12 if age < 16 else 16 if age < 18 else 18


def parse(raw):
    """A scope from a request body (dict) or a query string value (JSON text): the normalized dict, or None for the
    whole machine. Raises ScopeError for something malformed."""
    if raw in (None, "", {}):
        return None
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            raise ScopeError(T("scope must be a JSON object", "scope musi być obiektem JSON")) from None
    if not isinstance(raw, dict):
        raise ScopeError(T("scope must be a JSON object", "scope musi być obiektem JSON"))
    out = {}

    def refs(key):
        v = raw.get(key)
        if v in (None, []):
            return None
        if not isinstance(v, list):
            raise ScopeError(T(f"scope.{key} must be a list", f"scope.{key} musi być listą"))
        res = []
        for x in v:
            try:
                res.append({"root": int(x["root"]), "path": str(x.get("path") or "").strip("/")})
            except (TypeError, KeyError, ValueError):
                raise ScopeError(T(f"scope.{key}: each entry needs root and path", f"scope.{key}: każdy wpis potrzebuje root i path")) from None
        return res
    if (r := refs("roots")) is not None:
        out["roots"] = r
    if (f := refs("files")) is not None:
        out["files"] = f
    if raw.get("imdb"):
        if not re.fullmatch(r"tt\d+", str(raw["imdb"])):
            raise ScopeError(T("scope.imdb must look like tt0000000", "scope.imdb ma postać tt0000000"))
        out["imdb"] = str(raw["imdb"])
    if raw.get("tag"):
        out["tag"] = str(raw["tag"]).strip()
    if raw.get("age") not in (None, []):
        try:
            ages = sorted({int(a) for a in raw["age"]})
        except (TypeError, ValueError):
            raise ScopeError(T("scope.age must be a list of numbers", "scope.age musi być listą liczb")) from None
        if any(a not in AGE_GROUPS for a in ages):
            raise ScopeError(T("scope.age: groups are 0, 7, 12, 16 and 18", "scope.age: grupy to 0, 7, 12, 16 i 18"))
        out["age"] = ages
    return out or None


class Matcher:
    """Decides which library files a scope covers. Built once per request or plan (a snapshot of the catalog)."""

    def __init__(self, scope, roots, catalog=None):
        self.scope = scope or {}
        self.roots = {r["id"]: r for r in roots}
        self.by_index = {r["index"]: r for r in roots}
        self.items, self.wd = {}, {}
        if catalog is not None:
            with catalog.lock:
                self.items = dict(catalog.items)
                self.wd = dict(catalog.wd)
        s = self.scope
        self.folders = [(r["root"], r["path"]) for r in s.get("roots") or []]
        self.files = {(f["root"], f["path"]) for f in s.get("files") or []}
        self.tag = (s.get("tag") or "").lower() or None
        self.ages = set(s.get("age") or [])

    @property
    def whole(self):
        return not self.scope

    def title(self, root_id, rel):
        """The catalog item a file (or a title's folder) belongs to, or None."""
        parts = rel.split("/")
        for k in range(len(parts), 0, -1):
            it = self.items.get(f"{root_id}/{'/'.join(parts[:k])}")
            if it:
                return it
        return None

    def age_of(self, it):
        d = self.wd.get(it.get("imdb") or "") or {}
        if d.get("age") is not None:
            return d["age"]
        return 0 if it.get("kids") else None

    def match(self, root, rel):
        """Whether `rel` (a file or folder relative to library folder `root`, a roots() entry) is in the scope."""
        if self.whole:
            return True
        idx, rel = root["index"], rel.strip("/")
        if self.folders and not any(i == idx and (p == "" or rel == p or rel.startswith(p + "/")) for i, p in self.folders):
            return False
        if self.files and (idx, rel) not in self.files:
            return False
        it = None
        if self.scope.get("imdb") or self.tag or self.ages:
            it = self.title(root["id"], rel)
        if self.scope.get("imdb") and (not it or it.get("imdb") != self.scope["imdb"]):
            return False
        if self.tag:
            tags = {t.lower() for t in root.get("tags") or []}
            if it:
                tags.add((it.get("group") or "").lower())
                tags |= {g.lower() for g in it.get("genres") or []}
            if self.tag not in tags:
                return False
        if self.ages:
            if not it or age_group(self.age_of(it)) not in self.ages:
                return False
        return True

    def label(self, lang):
        """A short name of the scope for screens and task lists ("Anime · 0+ and 7+"), None for the whole machine."""
        if self.whole:
            return None
        pl = lang == "pl"
        s, parts = self.scope, []
        if s.get("imdb"):
            it = next((x for x in self.items.values() if x.get("imdb") == s["imdb"]), None)
            d = self.wd.get(s["imdb"]) or {}
            parts.append((it or {}).get("title") or d.get("pl" if pl else "en") or d.get("en") or s["imdb"])
        if self.folders:
            if len(self.folders) == 1:
                i, p = self.folders[0]
                parts.append(p.rsplit("/", 1)[-1] if p else (self.by_index.get(i) or {}).get("name") or str(i))
            else:
                n = len(self.folders)
                parts.append(f"{n} " + _pl(n, "foldery", "folderów") if pl else f"{n} folders")
        if self.files:
            if len(self.files) == 1:
                parts.append(next(iter(self.files))[1].rsplit("/", 1)[-1])
            else:
                n = len(self.files)
                parts.append(f"{n} " + _pl(n, "pliki", "plików") if pl else f"{n} files")
        if s.get("tag"):
            t = s["tag"]
            parts.append(t[:1].upper() + t[1:])
        if self.ages:
            ages = [f"{a}+" for a in sorted(self.ages)]
            joined = ages[0] if len(ages) == 1 else ", ".join(ages[:-1]) + (" i " if pl else " and ") + ages[-1]
            parts.append(joined)
        return " · ".join(parts)
