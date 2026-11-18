#!/usr/bin/env python3
"""GitHub release via the API (no gh): creates a release for an existing tag and uploads files.

Usage: GITHUB_TOKEN=… github-release.py <owner/repo> <tag> <title> <notes.md> <file>...
If the release for the tag already exists (CI job retried), only the missing files are uploaded.
"""
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def call(method, url, token, data=None, ctype="application/json"):
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "magneticplayer-release",
        **({"Content-Type": ctype} if body is not None else {})})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read() or b"null")


def main():
    repo, tag, title, notes, *files = sys.argv[1:]
    token = os.environ["GITHUB_TOKEN"]
    api = f"https://api.github.com/repos/{repo}"
    try:
        rel = call("GET", f"{api}/releases/tags/{urllib.parse.quote(tag)}", token)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        rel = call("POST", f"{api}/releases", token, {"tag_name": tag, "name": title,
                                                      "body": open(notes, encoding="utf-8").read()})
    have = {a["name"] for a in rel.get("assets", [])}
    upload = rel["upload_url"].split("{")[0]
    for path in files:
        name = os.path.basename(path)
        if name in have:
            print(f"already there: {name}")
            continue
        ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
        if name.endswith(".apk"):
            ctype = "application/vnd.android.package-archive"
        call("POST", f"{upload}?name={urllib.parse.quote(name)}", token, open(path, "rb").read(), ctype)
        print(f"wgrano: {name}")
    print(rel["html_url"])


if __name__ == "__main__":
    main()
