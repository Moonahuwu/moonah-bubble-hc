"""Brings the packwiz pack in pack/ up to date with the MoonahBubbleHC Prism instance.

The instance is still where you add, update and remove mods (or a friend changes pack/ in a pull request and your
instance picks it up on launch). Run this after changing mods in the instance; sync-bubble-hc-mods.py also runs it.

- Mods on Modrinth or CurseForge are stored as small .pw.toml files (a download link and a hash), not as jars, so the
  repo never re-uploads other people's mods.
- Our own files (the Bubble mod, the Lucky Block pack in addons/) are copied in as they are.
- A mod that's on neither site is left out of the pack, with a warning: add its download page by hand or ask its
  author, rather than putting their jar in a public repo.

Needs packwiz (https://packwiz.infra.link/installation/): it looks in PATH and in %USERPROFILE%\\go\\bin.
Usage: python update-pack.py [--dry-run]
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tomllib
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(BASE, "pack")
PACK_MODS = os.path.join(PACK, "mods")
PACK_ADDONS = os.path.join(PACK, "addons")
GAME = os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher", "instances", "MoonahBubbleHC", "minecraft")
INSTANCE_MODS = os.path.join(GAME, "mods")
INSTANCE_ADDONS = os.path.join(GAME, "addons")
# Jars we make ourselves: copied into the pack as files.
OWN_PREFIXES = ("bubble-neoforge-",)
# Never in the pack: the auto-updater replaces AutoModpack in the pack's instance.
SKIP_PREFIXES = ("automodpack-",)


def packwiz():
    found = shutil.which("packwiz")
    if found:
        return found
    guess = os.path.join(os.path.expanduser("~"), "go", "bin", "packwiz.exe")
    if os.path.exists(guess):
        return guess
    sys.exit("Can't find packwiz. Install it: go install github.com/packwiz/packwiz@latest")


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(args, dry):
    if dry:
        print("    (would run) packwiz " + " ".join(args))
        return True
    r = subprocess.run([packwiz()] + args, cwd=PACK, input="n\n" * 8, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        out = (r.stdout + r.stderr).strip().splitlines()
        print("    packwiz failed: " + (out[-1] if out else ""))
    return r.returncode == 0


def main():
    dry = "--dry-run" in sys.argv
    if not os.path.isdir(INSTANCE_MODS):
        sys.exit(f"Can't find the Prism instance's mods folder: {INSTANCE_MODS}")
    os.makedirs(PACK_MODS, exist_ok=True)

    have = {}  # jar filename -> its .pw.toml
    for f in os.listdir(PACK_MODS):
        if f.endswith(".pw.toml"):
            try:
                have[tomllib.load(open(os.path.join(PACK_MODS, f), "rb"))["filename"]] = f
            except Exception:
                pass
    own_in_pack = {f for f in os.listdir(PACK_MODS) if f.endswith(".jar")}
    jars = sorted(f for f in os.listdir(INSTANCE_MODS)
                  if f.endswith(".jar") and os.path.isfile(os.path.join(INSTANCE_MODS, f)) and not f.lower().startswith(SKIP_PREFIXES))
    changes, left_out = [], []

    new = []
    for f in jars:
        if f.lower().startswith(OWN_PREFIXES):
            src, dst = os.path.join(INSTANCE_MODS, f), os.path.join(PACK_MODS, f)
            if not os.path.exists(dst) or sha1(src) != sha1(dst):
                changes.append(f"  + {f} (our own, copied in)")
                if not dry:
                    shutil.copy2(src, dst)
        elif f not in have:
            new.append(f)

    # New third-party mods: Modrinth first (by file hash), then CurseForge
    if new:
        hashes = {sha1(os.path.join(INSTANCE_MODS, f)): f for f in new}
        try:
            req = urllib.request.Request("https://api.modrinth.com/v2/version_files",
                                         data=json.dumps({"hashes": list(hashes), "algorithm": "sha1"}).encode(),
                                         headers={"Content-Type": "application/json", "User-Agent": "moonah-pack-updater/1.0"})
            found = json.load(urllib.request.urlopen(req, timeout=60))
        except Exception as e:
            print(f"Couldn't reach Modrinth ({e}); trying CurseForge for everything new.")
            found = {}
        rest = []
        for h, f in hashes.items():
            v = found.get(h)
            if v and run(["modrinth", "add", "--project-id", v["project_id"], "--version-id", v["id"]], dry):
                changes.append(f"  + {f} (Modrinth)")
            else:
                rest.append(f)
        if rest:
            for f in rest:
                if not dry:
                    shutil.copy2(os.path.join(INSTANCE_MODS, f), os.path.join(PACK_MODS, f))
            run(["curseforge", "detect"], dry)
            for f in rest:
                leftover = os.path.join(PACK_MODS, f)
                if os.path.exists(leftover):  # CurseForge didn't know it either
                    os.remove(leftover)
                    left_out.append(f)
                else:
                    changes.append(f"  + {f} (CurseForge)")

    # Gone from the instance: out of the pack too
    for f, toml in sorted(have.items()):
        if f not in jars:
            changes.append(f"  - {f}")
            if not dry:
                os.remove(os.path.join(PACK_MODS, toml))
    for f in sorted(own_in_pack):
        if f not in jars:
            changes.append(f"  - {f}")
            if not dry:
                os.remove(os.path.join(PACK_MODS, f))

    # Lucky Block addons (our own): copied as they are
    if os.path.isdir(INSTANCE_ADDONS):
        os.makedirs(PACK_ADDONS, exist_ok=True)
        want = {n for n in os.listdir(INSTANCE_ADDONS) if os.path.isfile(os.path.join(INSTANCE_ADDONS, n))}
        for n in sorted(want):
            src, dst = os.path.join(INSTANCE_ADDONS, n), os.path.join(PACK_ADDONS, n)
            if not os.path.exists(dst) or sha1(src) != sha1(dst):
                changes.append(f"  + addons/{n}")
                if not dry:
                    shutil.copy2(src, dst)
        for n in sorted(os.listdir(PACK_ADDONS)):
            if n not in want:
                changes.append(f"  - addons/{n}")
                if not dry:
                    os.remove(os.path.join(PACK_ADDONS, n))

    if not dry:
        run(["refresh"], dry)
    print("Pack:" if changes else "Pack: already up to date.")
    for c in changes:
        print(c)
    for f in left_out:
        print(f"  ! {f} isn't on Modrinth or CurseForge, so it was left out of the pack (players won't get it from the pack).")
    if changes and not dry:
        print("Commit and push pack/ (GitHub Desktop, or: git add pack && git commit -m \"Update mods\" && git push).")


if __name__ == "__main__":
    main()
