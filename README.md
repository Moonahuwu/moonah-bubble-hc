# Moonah Bubble HC

The modpack for the Bubble HC server (Minecraft 1.21.1, NeoForge 21.1.267), plus the source of our own mod
**Bubble** and the **Lucky Block** outcome pack.

## Playing

1. Install [Prism Launcher](https://prismlauncher.org/).
2. Download **MoonahBubbleHC.zip** from the [latest release](https://github.com/Moonahuwu/moonah-bubble-hc/releases/latest).
3. In Prism: **Add Instance > Import**, pick the zip, then press **Play**.

Every time you press Play, the instance checks this repo first and downloads any mods that changed, before the game
starts. You never have to update anything by hand, and it works even when the server is off. The first launch
downloads everything, so it takes a few minutes.

If a mod's author doesn't allow other launchers to download it, the updater shows a window with a link. Download
that file, drop it where it says, and press continue. It only asks once.

## What's in here

| Path | What it is |
|---|---|
| `pack/` | The modpack, in [packwiz](https://packwiz.infra.link/) format. Mods from Modrinth and CurseForge are small `.pw.toml` files (a download link and a hash), not jars. Our own Bubble jar and the Lucky Block pack (`addons/`) are stored as files. |
| `mods-src/bubble-neoforge/` | Source of the Bubble mod: the bubble itself, the shop, the wheels, random events, objectives and more. See its README. |
| `mods-src/lucky-addon/` | `build_addon.py` builds the Lucky Block outcome pack. |
| `update-pack.py` | Brings `pack/` in line with the host's Prism instance. |
| `sync-bubble-hc-mods.py` | Copies the host's instance to the server, then runs `update-pack.py`. |
| `make-instance.py` | Builds the importable Prism instance zip for the release. |

Want to add a mod, change Bubble or fix something? See [CONTRIBUTING.md](CONTRIBUTING.md).

## For the host

The **MoonahBubbleHC** Prism instance is still where mods are added, updated and removed. After changing mods:

1. Stop the server, then run `sync-bubble-hc-mods.bat`. It updates the server, then the pack.
2. Commit and push `pack/` (GitHub Desktop, or `git add pack` then `git commit -m "Update mods"` then `git push`).
3. Start the server. Players get the new mods the next time they launch, and AutoModpack still works for anyone on
   the old setup.

When someone else's pull request changes `pack/`, merge it. Then put the same change in your instance, because the
server copies the instance. Either run the updater in your instance the same way players do, or add the mod in
Prism by hand. Then run the sync.

Bubble itself: `cd mods-src/bubble-neoforge`, `python get-libs.py` once, then `gradlew build`. The jar is in
`build/libs/`; copy it into the instance's mods folder in place of the old one and run the sync.
