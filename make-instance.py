"""Makes dist/MoonahBubbleHC.zip: a Prism Launcher instance that updates itself from the GitHub pack every time
it's launched (Prism runs packwiz-installer as a pre-launch command, so new mods are there before the game starts).

Friends import it once (Prism > Add Instance > Import > pick the zip) and press Play. That's all.

Usage: python make-instance.py <pack.toml URL>
  e.g. python make-instance.py https://raw.githubusercontent.com/<you>/<repo>/main/pack/pack.toml
"""
import json
import os
import sys
import tomllib
import urllib.request
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
BOOTSTRAP_URL = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/latest/download/packwiz-installer-bootstrap.jar"
CACHE = os.path.join(BASE, "dist", "packwiz-installer-bootstrap.jar")


def main():
    if len(sys.argv) < 2 or not sys.argv[1].startswith("https://"):
        sys.exit(__doc__)
    url = sys.argv[1]
    pack = tomllib.load(open(os.path.join(BASE, "pack", "pack.toml"), "rb"))
    mc = pack["versions"]["minecraft"]
    neo = pack["versions"]["neoforge"]
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    if not os.path.exists(CACHE):
        print("Downloading packwiz-installer-bootstrap.jar ...")
        req = urllib.request.Request(BOOTSTRAP_URL, headers={"User-Agent": "moonah-pack-updater/1.0"})
        with urllib.request.urlopen(req, timeout=60) as r, open(CACHE, "wb") as f:
            f.write(r.read())

    cfg = "\n".join([
        "[General]",
        "ConfigVersion=1.3",
        "InstanceType=OneSix",
        "name=" + pack["name"],
        "iconKey=bee",
        "OverrideMemory=true",
        "MinMemAlloc=2048",
        "MaxMemAlloc=8192",
        "OverrideCommands=true",
        # Prism runs this in the instance's minecraft folder before every launch: it downloads anything new from the pack.
        'PreLaunchCommand="$INST_JAVA" -jar packwiz-installer-bootstrap.jar ' + url,
        "",
    ])
    mmc = {
        "components": [
            {"important": True, "uid": "net.minecraft", "version": mc},
            {"uid": "net.neoforged", "version": neo},
        ],
        "formatVersion": 1,
    }
    out = os.path.join(BASE, "dist", "MoonahBubbleHC.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("instance.cfg", cfg)
        z.writestr("mmc-pack.json", json.dumps(mmc, indent=4))
        z.write(CACHE, "minecraft/packwiz-installer-bootstrap.jar")
    print(f"Made {out} (Minecraft {mc}, NeoForge {neo}), updating from {url}")


if __name__ == "__main__":
    main()
