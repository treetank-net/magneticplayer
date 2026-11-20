"""Routes: the catalog (films and series instead of folders), people, what to watch next, the wishlist. Each function
is a route of mp_routes.ROUTES: (req = the Handler, a = the App, method, p = path, q = query lists, qd = query
values, path parameters)."""
import mp_i18n
from mp_core import ApiError
from mp_i18n import T


# ---- catalog: films and series instead of folders (mp_catalog.py)
def catalog(req, a, qd, **_):
    if qd.get("since") and qd["since"] == str(a.catalog.version):
        return req.send_json({"version": a.catalog.version, "same": True})
    return req.send_json(a.catalog.public(mp_i18n.lang()))


def title(req, a, qd, **_):
    try:
        return req.send_json(a.catalog.title(qd.get("imdb", ""), mp_i18n.lang()))
    except ValueError:
        raise ApiError(T("bad IMDb id", "zły numer IMDb"), 400)


# the local IMDb index by title (agent 1.17+)
def search(req, a, qd, **_):
    return req.send_json(a.catalog.search(qd.get("q", ""), qd.get("limit") or 20))


def episodes(req, a, qd, **_):
    return req.send_json(a.series_episodes(qd.get("imdb", "")))


def person(req, a, qd, **_):
    try:
        return req.send_json(a.catalog.person(qd.get("id", ""), mp_i18n.lang()))
    except ValueError:
        raise ApiError(T("bad person id", "zły identyfikator osoby"), 400)


def next_titles(req, a, qd, **_):
    return req.send_json(a.catalog.next(qd.get("imdb", ""), mp_i18n.lang()))


def wishlist(req, a, **_):
    return req.send_json({"items": a.wishlist.list(a.catalog, mp_i18n.lang())})


def wishlist_add(req, a, **_):
    try:
        a.wishlist.add(req.body(), req.device_name())
    except ValueError:
        raise ApiError(T("bad IMDb id", "zły numer IMDb"), 400)
    return req.send_json({"items": a.wishlist.list(a.catalog, mp_i18n.lang())})


def wishlist_remove(req, a, **_):
    a.wishlist.remove(str(req.body().get("imdb") or ""))
    return req.send_json({"items": a.wishlist.list(a.catalog, mp_i18n.lang())})
