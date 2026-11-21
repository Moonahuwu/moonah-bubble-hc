# Contributing

Thanks for helping. Every change goes through a pull request, and the host merges it and puts it on the server.

## Setting up

1. Install [Git](https://git-scm.com/) or [GitHub Desktop](https://desktop.github.com/), then fork this repo and clone
   your fork.
2. For pack changes, get [packwiz](https://packwiz.infra.link/installation/). Either download `packwiz.exe` from its
   GitHub Actions builds, or run `go install github.com/packwiz/packwiz@latest` if you have Go.
3. For Bubble changes: Java 21 (Prism's bundled one works), then in `mods-src/bubble-neoforge` run `python get-libs.py`
   once and `gradlew build`.

## Adding, updating or removing a mod

Run these inside `pack/`:

```powershell
packwiz modrinth add <modrinth link or slug>      # add (prefer Modrinth)
packwiz curseforge add <curseforge link>          # add, if it's only on CurseForge
packwiz update <name>                              # update one mod
packwiz remove <name>                              # remove
```

- It has to be for **Minecraft 1.21.1 and NeoForge**. packwiz picks the right file if one exists.
- When packwiz offers to add dependencies, check them: say yes to ones the pack doesn't have yet.
- Don't put other people's jars in the repo. If a mod isn't on Modrinth or CurseForge, open an issue with its
  download page instead.
- Client-only mods (shaders, minimaps, HUD mods) are fine. packwiz marks them client-side from the site's data.
- In the pull request, say what the mod is and why. Big content mods change the game for everyone, so talk to the
  group first.

You can test your change before opening the pull request:

1. Run `packwiz serve` in `pack/`.
2. Make a copy of the instance.
3. In the copy's settings, under **Custom commands**, change the pre-launch command's address to
   `http://localhost:8080/pack.toml`.
4. Press Play.

## Changing Bubble or the Lucky Blocks

- **Bubble:** `mods-src/bubble-neoforge/src/main/java/moonahmc/bubble/`. Its README explains each part. Put new
  settings in its config files rather than hard-coding them, and keep the README up to date.
- **Lucky Blocks:** `mods-src/lucky-addon/build_addon.py`.
- Some rules for the pack:
  - Herobrine never griefs.
  - The colored Lucky Blocks from the separate Fabric pack are left alone.
  - Nothing should break builds or other people's stuff without a warning.
- Build it and try it before opening the pull request, and say in the PR what you tested.

## Pull requests

- Keep each one to one thing: one mod, one feature or one fix.
- Describe what changes for players.
- Don't commit worlds, server files, passwords or IP addresses. The `.gitignore` blocks those, so don't force-add
  anything.
