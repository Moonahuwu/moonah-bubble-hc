# Bubble for NeoForge (1.21.1)

The NeoForge version of Bubble (`../bubble` is the Fabric one). It does the same things and talks to clients the same
way; only the loader hooks are different. Built for NeoForge 21.1.267 (the MoonahBubbleHC Prism instance).

Everyone on the server is tied together inside one bubble, one death can end the run, and there's a shop.

## The bubble

- **Center:** the median position of all players (one runaway can't drag it).
- **Shape:** **Ring** (default) limits how far apart you can be across. **Sphere** also limits up and down, so nobody
  can go caving 100 blocks below everyone else.
- **Edge:** step past the radius (2-500 blocks, 50 by default) and either
  - **Teleport** (default): you're teleported to the nearest player who is inside, or
  - **Push:** you're nudged back toward the group. If you're stuck (in a wall, boxed in, or just not getting any closer)
    for about 4 seconds (2.5 in a wall), you're teleported instead. Anyone more than 24 blocks past the edge is teleported too.
- **Forcefield:** players with the mod see the edge as a glowing wall (or dome, in sphere mode). It fades in as they
  get near (each player picks how near under *Wall shows within* in the Bubble menu: 4-64 blocks or always; saved in
  their own `config/bubble-client.json`) and ripples when it pushes them. Only the nearby patch is drawn, so it's cheap. Players without the mod see particles.
- **Soul link** (off by default): everyone shares one health bar. When someone gets hurt, everyone else hears a quieter,
  higher-pitched hurt sound and their camera shakes a little. If anyone dies, everyone dies.
- **Portals:** when someone changes dimension, everyone else follows them 2 seconds later.
- **Who counts:** survival/adventure players who are alive. With fewer than two players there's no bubble.

## When someone dies (hardcore reset)

1. **Kill cam** (8 s): a replay of the last 3 seconds of the player who died, with their skin and gear.
2. **Post-game** (60 s): highlights and everyone's stats, then a **vote**:
   - **Next world:** a new world with a new random seed (or the one an op picked with `/bubble seed`).
   - **Retry seed:** a fresh copy of the same world (same seed).
   - **Another life:** keep this world. Everyone goes back to how they were. The dead get their inventory and XP back at a
     safe spot from a few seconds before they died, with full health and 5 seconds of protection. Lives used are counted.

   It happens as soon as everyone has voted, or when the timer runs out. Most votes wins, and a tie or no votes means
   Next world. **★ Save seed** votes to keep the seed in the favourites list (`/bubble seeds`).
   Click a column header in the stats table to sort by it (click again to flip it; ▲/▼ shows which). **What happened**
   switches the table to the run's log, with the run time of each: what the start wheel landed on, every Wheel Spin and
   what it landed on, slices an op did, and random events (silent ones are marked). It's kept in the world folder
   (`bubble-runlog.json`), so it survives a server restart mid-run and a new world starts a new one.
3. For a new world, the server archives the old one to `world-archives/run-N_<date>`, the MoonahMC panel restarts it,
   and the disconnect screen has a **Rejoin** button.

## Strange Matter and the shop

- **Selling:** in your inventory there's a Strange Matter slot above the inventory's top-left corner (or right above the
  trash slot, if the TrashSlot mod is installed). Drop an item in to sell it. **Shift-click** sells every matching item, and **right-click** buys back
  your last sale (9 deep).
- **Quick sell:** Ctrl+click any item in your own inventory (in any container screen) to sell it instantly. You can pick a
  different key under Controls > Bubble > Quick-sell.
- **Values:** raw materials have set values (diamond 16, emerald 12, ender pearl 6, iron 2, dirt/cobble about nothing).
  Everything else is worked out from its recipes, plus 8% for each crafting step that combines different things, so
  long crafting trees pay more. Enchantments add value, and damage takes it away. `/bubble matter worth <item>` shows a value.
- **Shop:** the bag button above your inventory (or a key you can bind in Controls). Tabs: Essentials, Materials, Gear,
  Backpacks and Backpack upgrades (Sophisticated Backpacks), Fun, and **Charging** (fill anything that holds NeoForge
  energy: Oritech, Mekanism, Powah, backpack battery upgrades...; 1 matter = 2,000 energy). The Charging tab only shows
  when an installed mod has energy items. You never pay less than an item sells for.
- **Buying:** pick how many at the top (**×1 / ×5 / ×16 / ×64**) and click an item; with ×1, shift-click still buys 5.
  The tooltip shows the total, and **Need X more** when you can't afford it; tiles you can't afford at the chosen amount
  are dimmed. Your amount is remembered.
- **Search:** the box above the tabs looks through every tab (by name or item id, e.g. `diamond` or `sophisticated`).
  Clicking a tab clears it. On the Gamble side it searches the cases.
- **Sort:** Shop order / Cheapest / Priciest / A–Z (the button next to the amounts; remembered).
- **Favourites:** middle-click an item to star it (★ on the tile). Starred items get a **Favourites** tab at the top of
  the tab list, from every tab. Middle-click again to take it out.
- The shop opens on the tab (and Shop/Gamble side) you had open last, even after restarting the game. Long lists have
  a scroll bar on the right. All of this is saved just for you in `config/bubble-client.json`.
- **Sells for:** hovering an item in your own inventory shows what it would sell for in the sell slot (with your
  Eidolon sell bonus), and the price of one when it's a stack.
- **Apothic Rolls** tab: pay to give a random tool or weapon in your inventory a random Apotheosis rarity with fresh
  affixes (replacing what it had). Any rarity 150, rare or better 450, epic or mythic 1,200. Shift-click rolls up to 5
  times. You only pay for rolls that happened.
- **Lucky Blocks** tab: buy lucky blocks.
- The shop has a **Shop | Gamble** bar at the top: Gamble shows the case tabs, Shop everything else.
- **Gamble:** CS-style cases. Click one to open it (shift-click opens **5 at once**, five reels stopping one after
  another). **Speed up** stops the reels right away and hands the items over. Your **Luck** (Luck potions, gear and
  Apotheosis affixes that add Luck) makes the rarer tiers more likely: each point multiplies uncommon odds by 1.08,
  rare 1.15, epic 1.25 and legendary 1.35 (negative luck lowers them). The case screen shows your luck. Click one to open it: a reel of 60 items races past, ticking, and slows to a stop on
  what you won. The item arrives when the reel stops (or right away if you log out). Epic and legendary unboxings are
  announced to everyone. Odds: Common 79.92%, Uncommon 15.98%, Rare 3.2%, Epic 0.64%, Legendary 0.26%.
  - **Mod Case** (80): items from every mod. **Progression Case** (150): pearls, eyes of ender, blaze rods, netherite,
    elytra, nether stars, with progression items in the rarer tiers. **Weapon Case** (150): Simply Swords and Bows, mod
    swords and Apotheosis gear.
  - The Mod Case's Legendary tier also holds The Shooting Star's **Stellar Remote** (`shooting_star_demo:stellar_remote`),
    about 1 in 940 cases. It's added once to existing case files (migration `stellar_remote_v1`), and left out if that
    mod isn't installed.
  - Contents are in `config/bubble-cases.json` on the server: a list per tier, each entry an `item` (+ `count`), or a
    `command` with a `show` item and `name` (e.g. `bubble apothloot {player} epic`). Items from missing mods are left
    out. Restart the server after editing it by hand. The server log shows what each case sells back for on average.
  - **Edit cases (ops, in game):** on the Gamble side, *Edit cases* (bottom of the tab column). Pick a case; the tier
    tabs show each tier's chance. Type new odds per tier and *Save odds* (they're weights: any numbers, shown as a share
    of the total; stored as `"odds": [..5 numbers..]`). Pick a tier to see its items: click one twice to remove it, or
    click anything in your inventory (shown below) to add a copy, enchantments and all (saved as a full `stack`).
    Saved to `config/bubble-cases.json` and used from the next case opened.
- **Motion:** Bubble screens ease open; shop tiles lift on hover, the selected tab's highlight slides, the grid slides
  in on a tab change and your balance counts up/down.
- **Edit mode (ops):** the Edit button in the shop. Click an item to change its price and amount, move it, or remove it.
  **Add held item** adds whatever is in your main hand (with its enchantments etc.). You can also add and delete tabs.
  Changes show up for everyone right away and are saved in `config/bubble-shop.json`.
- **+N popups:** whenever your Strange Matter changes (selling, kills, rewards, buying) a "+125" (or red "-80") pops out
  of your balance in the inventory and shop, or above the hotbar while playing, floats up and fades. Quick changes add
  up into one number that bumps as it grows (kill a swarm and watch it count).
- Balances are saved in the world (`bubble-matter.json`), so everyone starts at 0 in a new world. "Another life" keeps them.
- Items from mods that aren't installed are hidden (and kept in the file). The default tabs list both Sophisticated
  Backpacks and Traveler's Backpack items, so whichever one is installed shows up.

## Creepers and other mobs

- **Rainbow creeper:** cycles through every colour and is absurdly fast (100x a normal creeper by default). It knows
  where the nearest player is the moment it spawns (up to 128 blocks) and has no fuse: within 3 blocks it goes off
  instantly. Its explosion is all show: the usual bang and smoke, nothing broken, and one heart of damage to anything within 4 blocks.
- **Nuke creeper:** big, slow (40% of normal speed), tanky, with a 4-second fuse. 1 in 100 is **unstable**: it pulses
  red and has no fuse at all. It sets off Oritech's nuke
  explosion (the same one as the Nuke block). Without Oritech it's a huge TNT blast. A warning shows when one appears.
- **Gatling skeleton:** looks and moves like a normal skeleton, but as soon as it can see its target it sprays arrows,
  8 a tick (160 a second), for as long as it keeps line of sight (up to 24 blocks). Its arrows are light, can't be
  picked up and vanish when they land, so they don't pile up. Hits are still limited by the half-second of
  invulnerability after each one, but that adds up fast; shields help. Spawns in the dark and burns in daylight.
- **The Jockey Jockey:** a spider with a cave spider riding it. One bite from the spider gives you every harmful effect
  in the game (every mod's too, but not instant ones like Instant Damage) for 10 seconds. Spawns in the dark.
- **Swarm zombie:** looks, sounds and acts exactly like a zombie (it's even called "Zombie"). Kill it and it bursts into
  **100 tiny zombies**: 1 HP, a third the size, half a heart per hit, no sunburn, no fall or cramming damage, no drops.
  Each one you kill pays **1 Strange Matter**. Spawns in the dark.
- How often each of these appears is set in the *Next run* tab (Off / Rare / Normal / Lots).
- **Watcher:** an enderman with a red outline (visible through walls). Look it in the eyes and you die instantly. A
  carved pumpkin on your head protects you, like with normal endermen. Spawns in the dark. The kill goes straight through
  armour (your armour isn't damaged); a Totem of Undying still saves you.
- **Evil Watcher:** rarer (a seventh as often as Watchers, same setting), with a rainbow outline and a rainbow aura.
  Looking anywhere at it kills you instantly, even through blocks. Only a carved pumpkin protects you.
- They all spawn near players in survival/adventure (18-36 blocks away, never within 16 of anyone, not in Peaceful).
  How often, and the rainbow creeper's speed, are set in the *Next run* tab and apply straight away. Spawn eggs are in
  the creative menu, and both can come out of lucky blocks.

## Run goals (top-right HUD)

Every run has a **game mode**: a list of stages plus bonus goals, shown in the top-right corner (Destiny style: the
mode and stage, then objectives with checkboxes and progress, bonus goals, and any run modifiers with timers). Progress
is shared, and anything you did earlier in the run counts when its stage comes up. Clearing an objective or stage pays
Strange Matter to everyone, and most stages (plus the Wither, Warden and Watcher bonus goals) also hand everyone online
**case items**: right-click one to open it with the usual reel, sneak + right-click to open up to 5. Finishing the last stage is a **Victory** (+10 Eidolons each). The HUD stays hidden so it doesn't cover
minimaps: it slides in for 8 seconds whenever the team makes progress (changed lines highlighted in gold), then
fades out. **F4** ("Show/hide objectives" in Controls) pins it open or hides it. Pick the mode for the next world in the *Next run* tab.

- **The Full Run:** Overworld (gear up) > Nether > Aether (Slider, Valkyrie Queen) > Twilight Forest (Naga, Lich,
  Hydra) > Deep Dark (Ancient City, echo shards) > Cataclysm (2 bosses) > the End (dragon). Bonus: Wither, Warden,
  a Watcher, enchanting, taming, world tier Ascent, 5 lucky blocks, a case.
- **Boss Rush:** 2, then 8 different bosses from every mod.
- **Dragon Speedrun:** blaze rods and pearls, then the dragon.
- **Just the Dragon:** no objectives, just kill the Ender Dragon (500 Strange Matter + 3 Mod Cases).

**Editing in game (ops):** Bubble menu > Admin > *Objectives: Edit*. Pick a mode, pick a stage (or Bonus goals), remove
objectives with the ✕, or add one: type (kill / item / dimension / advancement / structure / Bubble event), ID (*Fill*
uses your held item, the mob you're looking at, or your dimension), count, HUD text (blank = automatic), Strange Matter
and an optional case reward. Stages can be added and deleted too. Changes save to `config/bubble-goals.json` and apply
to the run right away; *Use this mode for this run* switches the current run's goals (they start over at stage 1).

Modes live in `config/bubble-goals.json` (objective types: `dimension`, `advancement`, `kill` (with `ids`, `count`,
`distinct`), `item` (team total), `structure`, `custom` (`case_opened`, `lucky_block`, `apothic_roll`)), each with a
Strange Matter `reward`. Any stage or objective can also give cases: `"case": "mod"` (a case id from
`config/bubble-cases.json`: `mod`, `progression`, `weapons`, `twilight`, or your own) and optionally `"cases": 2`.
A case's `"color"` (`"#rrggbb"` or a chat colour name) tints its item. Restart after editing.

## Run modifiers (wheel slices)

The wheel can land on a modifier that lasts the whole run: **Low gravity**, **Tiny mode** (half size), **Glass cannon**
(half your max health, but 5x damage on every hit you deal, bows and modded weapons included), **Swap!** (everyone swaps
places every 5 minutes, with a countdown), **Speedrun** (kill any boss within 30 minutes: 500 Strange Matter each),
**Lifesteal** (every hit you deal heals you for 20% of the damage). Ops can force one with `/bubble modifier <id>`.

## Eidolons (permanent)

A second currency that never resets (saved in `bubble-eidolons.json` in the server folder). Earned when a run ends:
1 per 100 Strange Matter you hold, 2 per stage cleared, 10 for a victory. Spend them in the shop's **Eidolons** button:

- **Upgrades:** Tough Skin (+1 heart, 3 levels), Four-Leaf (+0.5 Luck, better case odds, 4), Allowance (+50 Strange
  Matter at each run start, 5), Haggler (+5% selling, 4), Head Start (starting kit: stone, iron, then armour).
- **Cosmetics:** particle trails (hearts, flame, music, soul fire, end rod, cherry, rainbow) and titles shown above
  your head, in chat and the tab list (Gambler, Watcher Bait, Nuke Survivor, Bubble Veteran, Legend).
- **Hall of Fame:** longest run, most wins, most runs ended (the last one to end a run wears **[Run Ender]** until the
  next run ends), biggest gambler, most bosses, luckiest unboxing, most Eidolons earned.
- **Betting:** on the ready-up screen, bet Eidolons on who ends this run (or "Nobody: we finish"). Winners split the
  pot by stake; if nobody called it, everyone gets their bet back.
- Ops: `/bubble eidolons give <player> <amount>`.

## Ready-up lobby

A new world (after Next world or Retry seed) starts in a lobby. Everyone waits near spawn in adventure mode, can't be
hurt, and no Bubble mobs spawn. A ready screen opens when you join (or press B), with who's here and who's ready.
When everyone online has pressed **I'm ready**, there's a 5-second countdown, everyone goes back to survival, and the
start-of-run wheel spins. Ops can press **Start now** any time (or `/bubble start`, also from the console). It can be
switched off for the next world in the *Next run* tab.

The ready screen shows each player's head next to their name, and a line of what this run is: the game mode,
difficulty, whether the start wheel will spin, whether random events are on, and Hardcore reset / Soul link if they're
on. A soft chime plays when someone readies up, a bell when the countdown starts, and a tick for the last 3 seconds.

## Admin tab (ops)

The Bubble menu has an **Admin** tab: Start the run (during the lobby), Next world, Retry same seed, End the run (goes
to the vote), Skip (kill cam / vote), Revive (everyone spectating comes back next to the group; at the end of a run it
does Another life), Spin the wheel, and **Close shop / Open shop**: turns off buying from the shop tabs (and Oritech
charging) for everyone, while the Gamble tab, case items and selling keep working. It stays that way (saved as
`shopClosed` in `config/bubble-shop.json`) until an op opens it again. Red buttons need a second click within 3 seconds.
At the end of a round, an op can **shift-click** a vote button (Next world / Retry seed / Another life) to decide it for
everyone straight away.

Every admin button (and Do it / Move / Force / Silent in the wheel and events screens) answers with a small pop-up in
the top-right corner, over any screen: "✔ Run started", "✔ Shop closed", "✔ Moved Backpacks", or a red "✖" with why it
couldn't be done ("The wheel is already spinning").

**Wheel slices: Edit** opens a screen with a tab for each wheel. Every slice has an on/off switch (off = left off the
wheel but kept in the file), a weight with - and + (how likely it is, 0 = never) and **Do it** (click twice: does that
slice for everyone now, no spin). **→ Mid** / **→ Start** moves a slice to the other wheel (it goes on the end of
that wheel's list). A slice that works differently on the other wheel has its Move button in yellow and needs two
clicks, and once moved it shows a ⚠ you can hover to read what's different: a run rule on the mid-run wheel stays on
from when it lands for the rest of the run (Sky island sends everyone to the Aether right then, Pacifist's 10 minutes
start then), and a timed status on the start wheel only lasts its few seconds or minutes. Moving is refused while a
wheel is spinning. `/bubble wheel move <slice>` does the same from chat. **Random events: Edit** opens the Events tab: random events on/off, whether random
ones are announced, how often they come (between X and Y minutes), when the next one is due, and for each event an
on/off switch, a weight, **Force** (now, announced to everyone) and **Silent** (now, nobody is told). Everything is
saved to `config/bubble-wheel.json` / `config/bubble-events.json` straight away and other ops see the change.

## Random events

Every 10 to 20 minutes of a run (not in the lobby) something happens near a random player. Default events:

- **Rainbow creeper horde:** 100 rainbow creepers spawn 40-64 blocks out (a few chunks away) and come for you.
- **The Watcher** (16-28 blocks away) and, rarer, **the Evil Watcher**.
- **Zombie siege:** 30 zombies and husks, all from one direction.
- **Gatling squad** (4 gatling skeletons), **The swarm** (a swarm zombie), **Jockey jockeys**, a **Nuke creeper**.
- **Pillager raid party:** pillagers, vindicators and a ravager from one side.
- **Phantom flock:** 8 phantoms in the sky.
- **Meteor shower** and **Lightning storm** (the wheel's timed statuses).
- **Supply drop:** a loot chest lands 20-40 blocks away with a beam of light over it (the announcement says where).
- **Free Wheel Spin:** the mid-run wheel spins by itself.

Event mobs hunt the player they spawned near, and leave again after a few minutes (they puff away) so they don't pile
up. Announced events get a chat line, a title and a horn. Ops: `/bubble event [id] [silent]` (no id = a random one).
Event types for your own: `horde` (`entity` or `entities`, `count`, `min`/`max` distance, `side`, `air`,
`leave_after`), `status`, `supply_drop`, `wheel`.

## Start-of-run wheel

When a new world starts (Next world or Retry seed), a wheel spins for everyone as soon as the lobby ends (or 10 seconds after
the first player joins if the lobby is off).
It's drawn over the game on the left edge of the screen, big, with only its right half showing and the pointer at 3 o'clock: every name runs along its slice, so the one at the pointer reads left to right, and the slice it's on is written out beside the pointer (then the result, big). It ticks as it slows down, and whatever it lands on happens for everyone. Players who join later
still get the items. Default slices:

- **Warship:** an Immersive Aircraft warship appears next to the group, with a stack of coal blocks.
- **Artifact for everyone:** each player gets one random artifact (anything in the `artifacts:artifacts` tag).
- **Lucky blocks:** 8 each.
- **Charm of Shrinking:** each player gets one.
- **Lifesteal:** the run-long modifier above.
- **Charged railgun:** everyone gets Oritech's Enderic Railgun, fully charged.
- **Legendary sword:** everyone gets a random unique Simply Swords weapon.
- Plus the run modifiers (Low gravity, Tiny mode, Glass cannon, Swap!, Speedrun).

Starting kits (each player gets one):
- **Backpacks:** a Sophisticated Backpacks diamond backpack.
- **Waystone network:** a warp stone and 2 waystones.
- **Air force:** a biplane or gyrodyne and 16 coal blocks.
- **Armed and dangerous:** a random TACZ pistol, loaded, with ammo.
- **Bow master:** a random Simply Bows unique bow and 64 arrows.
- **Relic haul:** 3 random artifacts (fully levelled relics).
- **Portal kit** (dropped once for the group): 14 glowstone, 2 water buckets, a diamond and 12 poppies, enough for an
  Aether portal and a Twilight Forest portal.
- **Treasure hunt:** an Explorer's Compass (find Dungeons Arise and Cataclysm structures).
- **Band camp:** a random Immersive Melodies instrument.
- **Oritech head start:** a charged exo jetpack and a charged railgun.
- **Mythic loot:** a random Apotheosis affix item, epic or better.

Run-long rules:
- **UHC mode:** no natural health regen (the gamerule); potions, golden apples and lifesteal still heal.
- **Hard world:** the shared Apotheosis world tier goes straight to Ascent.
- **Boss bounty:** the first boss killed (Cataclysm bosses included) pays 1000 Strange Matter each.
- **Double loot:** mobs killed by players drop everything twice.
- **Eternal night:** always midnight.
- **Miner's luck:** permanent Haste II and ores drop twice.
- **No hunger:** food stays at 8.5 shanks, so you never starve but never heal from food either.
- **Pacifist start:** you can't hurt mobs for the first 10 minutes, then Strength I for the rest of the run.
- **Explosive mobs:** every mob that dies goes off with a small explosion (hurts, doesn't break blocks).
- **Sky island start:** everyone is sent to the Aether together with Slow Falling, 14 glowstone and a water bucket to
  make a portal home.

## Mid-run wheel (Wheel Spins)

**Wheel Spin** items turn up in cases (rare/epic), in chests (about 3% of chests, Lootr ones included) and in Lucky
Blocks. Right-click one to spin the mid-run wheel; everyone sees it. Default slices:

- **Upside down:** everyone's view flips over for 5 minutes (the mouse and A/D still feel normal).
- **Ice ground:** the ground is as slippery as blue ice for 1 minute.
- **Super speed:** Speed VII for 60 seconds.
- **Sky drop (one of you):** one random player is teleported 100 blocks up with a water bucket in hand. MLG or die.
- **Sky drop (everyone):** the same, for everyone. (Not in the Nether: the roof's in the way.)
- **Girl Power:** Estrogen's Girl Power for 5 minutes (you can dash).
- **Jackpot:** 150 Strange Matter each.

Challenges (each timed one shows a countdown bar at the top of the screen and switches itself off):
- **Hotbar shuffle:** everyone's hotbar is mixed up.
- **Lights out:** Blindness for 60 seconds.
- **A Quiet Place:** a Warden digs up near the group and leaves again after 3 minutes.
- **Meteor rain:** fireballs fall around everyone for 30 seconds.
- **Mob ambush:** a ring of 6 zombies, skeletons, spiders and creepers around each player.
- **Hunger drain:** Hunger V for 2 minutes.
- **Random gravity:** every 10 seconds gravity turns heavy, floaty or launches you up, for 60 seconds (no fall damage
  from it).
- **Floor is lava:** for 30 seconds every block you stand on turns to magma (sneak!). The blocks go back afterwards.
- **Butterfingers:** for 60 seconds you drop what you're holding every so often.
- **TNT rain:** lit TNT drops around everyone for 20 seconds.
- **Lightning storm:** lightning strikes around everyone for 30 seconds.

Buffs:
- **Midas touch:** hitting mobs knocks gold out of them, for 2 minutes.
- **Flight:** creative-style flying for 60 seconds (Slow Falling afterwards if you were still in the air).
- **Heal & feed:** full health and food.
- **Loot pinata:** a burst of chest loot around each player.
- **Double Matter:** Strange Matter earned from goals, kills and the wheel is doubled for 5 minutes (not selling).
- **Item gift:** a random rare item each (totem, god apple, netherite, elytra, trident...).

Funny ones:
- **Hotbar swap:** everyone's hotbar moves on to the next player.
- **Tiny or giant:** everyone is randomly tiny or giant for 3 minutes.
- **Random teleport:** everyone is sent somewhere safe up to 50 blocks away.
- **Chickens everywhere:** 30 chickens, gone again after 2 minutes.

Both wheels are in `config/bubble-wheel.json` (`slices` is the start-of-run wheel, `midrun` the mid-run one). Each slice
has a name, colour, optional weight and a list of actions: `give` (add `"charged": true` for energy items),
`give_random` from a tag, `give_one_of` a list, `summon` (optional `count`, `radius`, `leave_after` seconds), `drop`,
`command`, `modifier`, `effect`, `fx` (`upside_down` / `ice`), `skydrop`, `matter`, `status` (a timed one: `meteor_rain`,
`tnt_rain`, `lightning`, `random_gravity`, `floor_is_lava`, `butterfingers`, `midas`, `flight`, `double_matter`, `size`),
`shuffle_hotbar`, `swap_hotbars`, `heal`, `teleport`, `ambush`, `loot`. Actions can have `"who": "all" | "spinner" | "random"`. Add as many as you
like, then `/bubble wheel reload`. Ops can spin with `/bubble wheel` (start-of-run) or `/bubble wheel midrun`. The
start-of-run wheel can be switched off for the next world in the *Next run* tab.

## Guns (TACZ)

With TACZ installed, guns are super rare: in the Weapon and Mod cases' legendary tier, in about 0.6% of chests, in Lucky
Blocks, and on about 1 in 500 zombies, skeletons, piglins and illagers (they can't fire it, but drop it with ammo). Every
gun comes with a full magazine and a stack of its ammo. Rocket and grenade launchers are left out. The chances are in
`config/bubble-cases.json` under `extras` (`chestWheelSpin`, `chestGun`, `mobGunChance`).

## Video tab (shop)

The shop's **Video** tab has a link box: paste a YouTube / Twitch / video-file link, pay (250 Strange Matter by default;
ops change it in Edit mode like any price) and it plays in a window at the top left of everyone's screen, through
WaterMedia (the library WaterFrames uses). One video at a time, a new one can start 20 seconds after the last, people
who join mid-video catch up, and it closes when it ends. **F7** hides and mutes it just for you. Ops: `/bubble video stop`.

## Accessories (Curios, Artifacts, Relics)

- Right-click an accessory to put it on straight away (a free slot, or it swaps with what's there).
- Artifacts (Reliquified Artifacts) and other relics are fully levelled as soon as they're in your inventory: max level,
  every ability unlocked, researched and maxed, best stats (Relics' own "maximize").
- A death that ends the run keeps your accessories with the rest of your inventory for "Another life".

## Joining problems

If joining hangs on *Connecting* or *Loading terrain* for more than 5 seconds, a **Rejoin** and a **Leave** button
appear in the middle of the screen. If joining fails, the disconnected screen has a **Rejoin** button.

## Estrogen and WaterFrames

The shop has an **Estrogen** tab (pills, patches, thigh highs, the Rosy Maple Elytra...) and a **Screens** tab
(WaterFrames frames, TVs and projectors that show a picture or video from a link). Estrogen items are also in the Mod
Case and Lucky Blocks, and Girl Power is a mid-run wheel slice.

## Pre-game setup ("Next run" tab)

The Bubble menu has a second tab. Ops can change, everyone can look:

- **Lucky blocks in the world:** Off / Rare / Normal / Common / Lots, on the surface and in caves.
- **Rainbow creepers**, **rainbow creeper speed** (1x-100x), **nuke creepers**, **gatling skeletons** and **watchers:** apply now.
- **Start-of-run wheel:** On / Off for the next world.
- **Difficulty** and **seed** for the next world.
- **Structures, per mod** (Vanilla, When Dungeons Arise, Twilight Forest, the Aether...): Off / Rare / Normal / Common /
  Lots. Off stops that mod's structures from generating at all.
- Hover a row's name to see what it does.

World settings can't change a world that already exists, so they're written to a data pack (`bubble-next-datapack/` in
the server folder) every time they change. When a run ends (Next world or Retry seed), that pack goes into the new
world's `datapacks/bubble_pregame`. Files for mods that were removed in the meantime are left out, so the world still
loads. Another life keeps the current world, so nothing changes. Settings are saved in `config/bubble-pregame.json`.

## Apotheosis world tiers (shared)

Everyone skips Apotheosis's World Tier tutorial automatically, so affixes and gems work straight away (no "This item has
an unknown power" until you open the World Tier screen).

When Apotheosis is installed, world tiers are shared by everyone:

- When anyone unlocks a tier (Frontier, Ascent, Summit or Pinnacle, by meeting its advancement's requirements),
  everyone gets it unlocked and switched to it straight away, with a message in chat.
- Going into the Nether (or joining while someone is there) unlocks and activates **Ascent** for everyone.
- Players who join later catch up when they log in. Tiers only go up. You can still pick a lower tier yourself in
  Apotheosis' world tier screen; Bubble only moves people up when the shared tier rises.
- It's saved per world (`world/bubble-tiers.json`), so a new world after a hardcore reset starts at Haven again.
  "Another life" keeps it.
- `/bubble tier` shows the shared tier; ops can raise it with `/bubble tier <frontier|ascent|summit|pinnacle>`.

## Screens and GUI scale

The Bubble menu is only as tall as the window: when its tab doesn't fit (the Admin and Next run tabs at GUI scale 3 or 4,
say) the middle scrolls with the mouse wheel, with a scroll bar on the right, and the text stays full size. The
structures list is part of that scroll. The Shop and post-game screens shrink to fit when the window is too small at
your GUI scale, so they never get cut off. At sizes where they fit, nothing changes.

## Commands

- `/bubble` opens the settings screen; `/bubble status` prints them.
- Ops: `/bubble radius <2-500>`, `enable|disable`, `edge teleport|push`, `shape ring|sphere`, `soullink on|off`,
  `hardcore on|off`, `newrun` (end the run now), `next` (skip the kill cam / end the vote now),
  `decide next|retry|life`, `seed <seed>|random`, `matter give <player> <amount>`, `shop reload`, `tier <name>`,
  `apothloot <player> [common|uncommon|rare|epic|mythic]` (a random Apotheosis item; the lucky blocks use it).
- Ops: `/bubble start` (end the lobby), `/bubble wheel [reload|midrun]`, `/bubble wheel clear [status]` (take wheel statuses off: a run modifier, a timed status, `upside_down`, `ice`, `effects`, or everything), `/bubble wheel apply <slice>` (do one slice right now, no spin), `/bubble wheel move <slice>` (move it to the other wheel), `/bubble event [id] [silent]` (force a random event), `/bubble givecase <players> <case> [count]`,
  `/bubble givespin <players> [count]`, `/bubble gun <players> [gun]`, `/bubble nuke [pos]` (a Lucky Block can do this).
- Everyone: `/bubble vote next|retry|life`, `/bubble saveseed`, `/bubble seeds`, `/bubble matter`, `/bubble matter worth <item>`.
- Settings are saved in `config/bubble.json`, favourite seeds in `bubble-seeds.json`.

## Installing

- **Server:** a NeoForge 21.1.267 server with `bubble-neoforge-1.19.1.jar` in `mods/`.
- **Players:** the MoonahBubbleHC instance (Minecraft 1.21.1, NeoForge 21.1.267) with `bubble-neoforge-1.19.1.jar` in its
  mods folder (also in `MoonahMC2026/client-extras/`). Players without the mod can still join, but they get no screens,
  forcefield, replay, shop or sell slot. They can still vote with `/bubble vote`.

## Building

```
set JAVA_HOME=%APPDATA%\PrismLauncher\java\java-runtime-epsilon
gradlew build
```

Gradle runs on Java 25; NeoForge's tools also need a Java 21, which `gradle.properties` points at Prism Launcher's
(`java-runtime-delta`). The jar ends up in `build/libs/`. `gradlew runServer` starts a test server in `run/`.

## Differences from the Fabric version

- Payloads, commands and settings files are identical, so the server-side behaviour and the test script are the same.
- Death handling: NeoForge's `LivingDeathEvent` keeps the inventory for "Another life", and a small mixin at the end of
  `ServerPlayer.die` runs the rest (like Fabric's after-death event).
- No client mixins: NeoForge already exposes the inventory screen's position and hovered slot.
- `moonahmc.bubble.net` has small stand-ins for Fabric's networking classes so the rest of the code didn't change.
