"""Downloads the jars Bubble compiles against (build.gradle's libs/...) from the links in ../../pack, so they don't
have to be in the repo. Run once before the first build: python get-libs.py
"""
import hashlib
import os
import re
import sys
import tomllib
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PACK_MODS = os.path.join(HERE, "..", "..", "pack", "mods")
LIBS = os.path.join(HERE, "libs")

wanted = re.findall(r"libs/([^'\"]+\.jar)", open(os.path.join(HERE, "build.gradle"), encoding="utf-8").read())
links = {}
for f in os.listdir(PACK_MODS):
    if f.endswith(".pw.toml"):
        t = tomllib.load(open(os.path.join(PACK_MODS, f), "rb"))
        links[t["filename"]] = t.get("download", {})
os.makedirs(LIBS, exist_ok=True)
missing = []
for jar in wanted:
    target = os.path.join(LIBS, jar)
    if os.path.exists(target):
        print("have", jar)
        continue
    d = links.get(jar)
    if not d or not d.get("url"):
        missing.append(jar)
        continue
    print("downloading", jar)
    req = urllib.request.Request(d["url"], headers={"User-Agent": "moonah-pack-updater/1.0"})
    data = urllib.request.urlopen(req, timeout=120).read()
    algo = d.get("hash-format", "sha1")
    if hashlib.new(algo, data).hexdigest() != d.get("hash"):
        sys.exit(f"{jar}: the download doesn't match the pack's hash, not saving it")
    open(target, "wb").write(data)
if missing:
    print("Not in the pack with a direct link (download these by hand into libs/): " + ", ".join(missing))
