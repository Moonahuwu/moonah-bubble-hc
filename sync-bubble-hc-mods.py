"""Copies the mods of the MoonahBubbleHC Prism instance to the Bubble HC (NeoForge) server.

The Prism instance is the list of mods: add, update or remove mods there (Prism shows them all), then run this
(sync-bubble-hc-mods.bat) while the Bubble HC server is stopped, and start the server.

- Mods the server can run go to servers/bubble-hc/mods/.
- Client-only mods (listed in servers/bubble-hc/client-only-mods.txt) go to
  servers/bubble-hc/automodpack/host-modpack/main/mods/, so AutoModpack still sends them to friends.
- The instance's addons/ folder (Lucky Block addons) goes to the server and to players too.
- Mods that were removed from the instance are moved to servers/bubble-hc/removed-mods/ (not deleted).
- Disabled mods in Prism (*.jar.disabled) are left out.

Usage: python sync-bubble-hc-mods.py [--dry-run]
"""
import hashlib
import json
import os
import shutil
import socket
import sys
import time
import tomllib
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
INSTANCE = os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher", "instances", "MoonahBubbleHC", "minecraft", "mods")
SERVER = os.path.join(BASE, "servers", "bubble-hc")
if "--server" in sys.argv:  # for testing on a copy
    SERVER = sys.argv[sys.argv.index("--server") + 1]
SERVER_MODS = os.path.join(SERVER, "mods")
HOST_MODS = os.path.join(SERVER, "automodpack", "host-modpack", "main", "mods")
CLIENT_ONLY = os.path.join(SERVER, "client-only-mods.txt")
REMOVED = os.path.join(SERVER, "removed-mods")
# Kept on the server even though the instance doesn't have them (the host plays without AutoModpack).
SERVER_ONLY_PREFIXES = ("automodpack-",)


def mod_ids(path):
    try:
        with zipfile.ZipFile(path) as z:
            data = tomllib.loads(z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace"))
        return [m["modId"] for m in data.get("mods", [])]
    except Exception:
        return []


def client_only_list():
    if not os.path.exists(CLIENT_ONLY):
        return set()
    out = set()
    for line in open(CLIENT_ONLY, encoding="utf-8"):
        line = line.split("#", 1)[0].strip().lower()
        if line:
            out.add(line)
    return out


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def server_running():
    try:
        active = json.load(open(os.path.join(BASE, "profiles.json"), encoding="utf-8")).get("active")
    except Exception:
        active = None
    if active != "bubble-hc":
        return False
    port = 25565
    for line in open(os.path.join(SERVER, "server.properties"), encoding="utf-8"):
        if line.startswith("server-port="):
            port = int(line.split("=", 1)[1].strip() or 25565)
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main():
    dry = "--dry-run" in sys.argv
    if not os.path.isdir(INSTANCE):
        sys.exit(f"Can't find the Prism instance's mods folder: {INSTANCE}")
    if not dry and "--server" not in sys.argv and server_running():
        sys.exit("The Bubble HC server is running. Stop it in the panel first, then run this again.")
    os.makedirs(HOST_MODS, exist_ok=True)
    client_only = client_only_list()

    want = {SERVER_MODS: {}, HOST_MODS: {}}
    for name in sorted(os.listdir(INSTANCE)):
        src = os.path.join(INSTANCE, name)
        if not name.lower().endswith(".jar") or not os.path.isfile(src):
            continue
        if name.lower().startswith(SERVER_ONLY_PREFIXES):
            continue  # AutoModpack: the server has its own copy
        ids = [i.lower() for i in mod_ids(src)]
        low = name.lower()
        is_client = any(i in client_only for i in ids) or any(low.startswith(c) for c in client_only)
        want[HOST_MODS if is_client else SERVER_MODS][name] = src

    changes = []
    stamp = time.strftime("%Y-%m-%d_%H-%M")
    for dest, files in want.items():
        os.makedirs(dest, exist_ok=True)
        where = "server" if dest == SERVER_MODS else "client-only (AutoModpack)"
        for name, src in files.items():
            target = os.path.join(dest, name)
            if os.path.exists(target) and os.path.getsize(target) == os.path.getsize(src) and sha1(target) == sha1(src):
                continue
            changes.append(f"  + {name}  -> {where}")
            if not dry:
                shutil.copy2(src, target)
        for name in sorted(os.listdir(dest)):
            if not name.lower().endswith(".jar") or name in files:
                continue
            if dest == SERVER_MODS and name.lower().startswith(SERVER_ONLY_PREFIXES):
                continue
            changes.append(f"  - {name}  (no longer in the instance, or moved to the other list)")
            if not dry:
                os.makedirs(os.path.join(REMOVED, stamp), exist_ok=True)
                shutil.move(os.path.join(dest, name), os.path.join(REMOVED, stamp, name))

    # Lucky Block addons (and anything else in the instance's addons/ folder): the server and every player need them.
    addons_src = os.path.join(os.path.dirname(INSTANCE), "addons")
    if os.path.isdir(addons_src):
        wanted = {n for n in os.listdir(addons_src) if os.path.isfile(os.path.join(addons_src, n))}
        for dest in (os.path.join(SERVER, "addons"), os.path.join(SERVER, "automodpack", "host-modpack", "main", "addons")):
            os.makedirs(dest, exist_ok=True)
            for name in sorted(wanted):
                src, target = os.path.join(addons_src, name), os.path.join(dest, name)
                if os.path.exists(target) and sha1(target) == sha1(src):
                    continue
                changes.append(f"  + addons/{name}")
                if not dry:
                    shutil.copy2(src, target)
            for name in sorted(os.listdir(dest)):
                if name not in wanted and os.path.isfile(os.path.join(dest, name)):
                    changes.append(f"  - addons/{name}")
                    if not dry:
                        os.makedirs(os.path.join(REMOVED, stamp), exist_ok=True)
                        shutil.move(os.path.join(dest, name), os.path.join(REMOVED, stamp, name))

    if not dry:
        with open(os.path.join(SERVER, "server-mods.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(sorted(n for n in os.listdir(SERVER_MODS) if n.lower().endswith(".jar"))) + "\n")
    print(f"{len(want[SERVER_MODS])} server mods, {len(want[HOST_MODS])} client-only mods.")
    if changes:
        print(("Would change:" if dry else "Changed:") + "\n" + "\n".join(changes))
        if not dry:
            print("Start the Bubble HC server; players get the update from AutoModpack when they join.")
    else:
        print("Already up to date.")

    # Keep the GitHub modpack (pack/) in step too, so the auto-updating instance gets the same mods.
    if not dry and "--server" not in sys.argv and os.path.exists(os.path.join(BASE, "update-pack.py")):
        print()
        import subprocess
        subprocess.run([sys.executable, os.path.join(BASE, "update-pack.py")], cwd=BASE)


if __name__ == "__main__":
    main()
