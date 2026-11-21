"""Builds MoonahMC-Lucky-Blocks.zip: a Lucky Block (creoii "Modern Lucky Block", mod id `lucky`) addon with outcomes from
every mod in the MoonahBubbleHC instance.

Run:  python build_addon.py            (writes the zip here and into the instance's addons/ folder)

Only items and mobs that really exist in the instance's jars are used (checked against each mod's language file), so
updating or removing a mod and rebuilding never leaves broken outcomes behind. Edit the lists below to change drops.

Luck goes from -2 (very bad) to 2 (very good); `chance` is a weight (2 = twice as likely, 0.1 = ten times rarer).
"""
import glob
import io
import json
import os
import re
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
INSTANCE = os.path.join(os.environ["APPDATA"], "PrismLauncher", "instances", "MoonahBubbleHC", "minecraft")
VANILLA = os.path.join(HERE, "..", "bubble-neoforge", "build", "moddev", "artifacts", "neoforge-21.1.267-client-extra-aka-minecraft-resources.jar")
OUT = "MoonahMC-Lucky-Blocks.zip"

# ---------------------------------------------------------------- what exists

items, entities, models = set(), set(), set()


def scan(jar):
    try:
        z = zipfile.ZipFile(jar)
    except zipfile.BadZipFile:
        return
    for name in z.namelist():
        mm = re.fullmatch(r"assets/([a-z0-9_-]+)/(?:models/item|items)/([a-z0-9_/.-]+)\.json", name)
        if mm:
            models.add(f"{mm.group(1)}:{mm.group(2)}")
        m = re.fullmatch(r"assets/([a-z0-9_.-]+)/lang/en_us\.json", name)
        if not m:
            continue
        try:
            lang = json.loads(z.read(name).decode("utf-8", "replace"))
        except ValueError:
            continue
        for key in lang:
            k = re.fullmatch(r"(item|block|entity)\.([a-z0-9_-]+)\.([a-z0-9_/.-]+)", key)
            if k:
                target = entities if k.group(1) == "entity" else items
                target.add(f"{k.group(2)}:{k.group(3)}")
                if "." in k.group(3):  # ids with a slash (simplybows:ice_bow/ice_bow) have dots in their language key
                    target.add(f"{k.group(2)}:{k.group(3).replace('.', '/')}")


scan(VANILLA)
for jar in glob.glob(os.path.join(INSTANCE, "mods", "*.jar")):
    scan(jar)
# A real item has a name and an item model (language files also hold tooltips and other text)
items &= models
# Vanilla blocks/items whose names come from elsewhere
items |= {"minecraft:enchanted_golden_apple", "minecraft:tnt"}
skipped = []

# ---------------------------------------------------------------- outcome helpers

outcomes = {}


def count(c):
    return {"type": "minecraft:uniform", "min_inclusive": c[0], "max_inclusive": c[1]} if isinstance(c, tuple) else c


def add(name, outcome, needs=()):
    missing = [n for n in needs if n not in items and n not in entities]
    if missing:
        skipped.append((name, missing))
        return
    outcomes[name] = outcome


def item(name, id, n=1, luck=0, chance=1.0, components=None):
    o = {"type": "lucky:item", "luck": luck, "chance": chance, "item": id, "count": count(n)}
    if components:
        o["components"] = components
    add(name, o, [id])


def kit(name, ids, luck=0, chance=1.0, picks=3):
    """A random handful from a list (only the ones that exist)."""
    present = [(i, n) for i, n in ids if i in items]
    if len(present) < 2:
        skipped.append((name, [i for i, _ in ids]))
        return
    outcomes[name] = {"type": "lucky:random", "luck": luck, "chance": chance, "count": min(picks, len(present)), "duplicates": False,
                      "outcomes": [{"type": "lucky:item", "item": i, "count": count(n)} for i, n in present]}


def mob(name, id, n=1, luck=-1, chance=1.0):
    add(name, {"type": "lucky:entity", "luck": luck, "chance": chance, "entity_type": id, "count": count(n)}, [id])


def cmd(name, command, luck=0, chance=1.0, needs=()):
    add(name, {"type": "lucky:command", "luck": luck, "chance": chance, "command": command}, needs)


def msg(name, text, luck=0, chance=1.0):
    outcomes[name] = {"type": "lucky:message", "luck": luck, "chance": chance, "message": text}


def ids_like(ns, pattern, exclude=r"$^"):
    return sorted(i for i in items if i.startswith(ns + ":") and re.search(pattern, i.split(":")[1]) and not re.search(exclude, i))

# ---------------------------------------------------------------- vanilla

item("diamonds", "minecraft:diamond", (2, 6), luck=1)
item("diamond_block", "minecraft:diamond_block", 1, luck=2, chance=0.4)
item("emeralds", "minecraft:emerald", (3, 9), luck=1)
item("gold", "minecraft:gold_ingot", (4, 12))
item("iron", "minecraft:iron_ingot", (6, 16))
item("netherite", "minecraft:netherite_ingot", 1, luck=2, chance=0.3)
item("god_apple", "minecraft:enchanted_golden_apple", 1, luck=2, chance=0.3)
item("golden_apples", "minecraft:golden_apple", (2, 4), luck=1)
item("totem", "minecraft:totem_of_undying", 1, luck=2, chance=0.4)
item("elytra", "minecraft:elytra", 1, luck=2, chance=0.12)
item("trident", "minecraft:trident", 1, luck=1, chance=0.4)
item("ender_pearls", "minecraft:ender_pearl", (2, 6))
item("xp_bottles", "minecraft:experience_bottle", (8, 24), luck=1)
item("golden_carrots", "minecraft:golden_carrot", (8, 16))
item("cake", "minecraft:cake", 1)
item("cookies", "minecraft:cookie", (8, 32))
item("arrows", "minecraft:arrow", (16, 48))
item("lapis", "minecraft:lapis_lazuli", (8, 24))
item("obsidian", "minecraft:obsidian", (4, 10))
item("saddle", "minecraft:saddle", 1)
item("name_tags", "minecraft:name_tag", (1, 3))
item("heart_of_the_sea", "minecraft:heart_of_the_sea", 1, luck=2, chance=0.2)
item("dirt", "minecraft:dirt", 64, luck=-1)
item("rotten_flesh", "minecraft:rotten_flesh", (16, 64), luck=-1)
item("poison_potato", "minecraft:poisonous_potato", (8, 32), luck=-1)
kit("diamond_gear", [("minecraft:diamond_sword", 1), ("minecraft:diamond_pickaxe", 1), ("minecraft:diamond_axe", 1),
                     ("minecraft:diamond_helmet", 1), ("minecraft:diamond_chestplate", 1), ("minecraft:diamond_leggings", 1),
                     ("minecraft:diamond_boots", 1)], luck=1, picks=2)
kit("iron_gear", [("minecraft:iron_sword", 1), ("minecraft:iron_pickaxe", 1), ("minecraft:iron_helmet", 1),
                  ("minecraft:iron_chestplate", 1), ("minecraft:iron_leggings", 1), ("minecraft:iron_boots", 1), ("minecraft:shield", 1)], picks=3)
kit("animals", [], picks=1)  # filled below as mobs
mob("farm_cows", "minecraft:cow", (2, 4), luck=0)
mob("farm_pigs", "minecraft:pig", (2, 4), luck=0)
mob("farm_chickens", "minecraft:chicken", (3, 6), luck=0)
mob("horse", "minecraft:horse", 1, luck=1, chance=0.5)
mob("wolves", "minecraft:wolf", 2, luck=1, chance=0.5)
mob("zombies", "minecraft:zombie", (3, 6), luck=-1)
mob("skeletons", "minecraft:skeleton", (2, 5), luck=-1)
mob("spiders", "minecraft:spider", (2, 4), luck=-1)
mob("creepers", "minecraft:creeper", (2, 3), luck=-2)
mob("silverfish", "minecraft:silverfish", (6, 12), luck=-1)
mob("witch", "minecraft:witch", 1, luck=-1)
mob("phantoms", "minecraft:phantom", (2, 3), luck=-1, chance=0.5)
mob("ravager", "minecraft:ravager", 1, luck=-2, chance=0.2)
cmd("charged_creeper", "summon minecraft:creeper ~ ~1 ~ {powered:1b}", luck=-2, chance=0.4)
cmd("tnt_ring", "execute positioned ~ ~1 ~ run summon minecraft:tnt ~2 ~ ~ {fuse:50}", luck=-2, chance=0.5)
cmd("tnt_rain", "summon minecraft:tnt ~ ~8 ~ {fuse:60}", luck=-2, chance=0.5)
cmd("cobweb_trap", "fill ~-1 ~ ~-1 ~1 ~2 ~1 minecraft:cobweb replace minecraft:air", luck=-1)
cmd("lightning", "summon minecraft:lightning_bolt ~ ~ ~", luck=-1, chance=0.6)
cmd("blindness", "effect give @p minecraft:blindness 15 0", luck=-1)
cmd("levitation", "effect give @p minecraft:levitation 6 1", luck=-1)
cmd("speed_haste", "effect give @p minecraft:haste 300 1", luck=1)
cmd("strength", "effect give @p minecraft:strength 180 1", luck=1)
cmd("regeneration", "effect give @p minecraft:regeneration 30 1", luck=1)
cmd("fireworks", "summon minecraft:firework_rocket ~ ~1 ~ {LifeTime:20,FireworksItem:{id:\"minecraft:firework_rocket\",count:1,components:{\"minecraft:fireworks\":{explosions:[{shape:\"large_ball\",colors:[I;16711680,65280,255]}]}}}}", luck=0)
cmd("xp_burst", "summon minecraft:experience_orb ~ ~1 ~ {Value:200}", luck=1)
msg("nothing", "Nothing happened... this time.", luck=0, chance=0.6)

# ---------------------------------------------------------------- Bubble (Strange Matter, the special creepers)

cmd("matter_small", "bubble matter give @p 25", luck=1)
cmd("matter_big", "bubble matter give @p 120", luck=2, chance=0.4)
mob("rainbow_creepers", "bubble:rainbow_creeper", (2, 4), luck=-1)
mob("nuke_creeper", "bubble:nuke_creeper", 1, luck=-2, chance=0.04)
mob("gatling_skeleton", "bubble:gatling_skeleton", 1, luck=-2, chance=0.3)
mob("watcher", "bubble:watcher", 1, luck=-2, chance=0.2)
mob("evil_watcher", "bubble:evil_watcher", 1, luck=-3, chance=0.04)
mob("jockey_jockey", "bubble:jockey_jockey", 1, luck=-2, chance=0.3)
mob("swarm_zombie", "bubble:swarm_zombie", 1, luck=-1, chance=0.5)
# Low chance: just nukes everything (Oritech's Manhattan-style explosion, right where the block was)
cmd("manhattan_nuke", "bubble nuke ~ ~ ~", luck=-3, chance=0.03)
cmd("wheel_spin", "bubble givespin @p", luck=1, chance=0.35)
cmd("gun", "bubble gun @p", luck=2, chance=0.04)  # TACZ gun with ammo, super rare
item("estrogen_pills", "estrogen:estrogen_pill", (2, 4), luck=1, chance=0.5)
item("thigh_highs", "estrogen:thigh_highs", 1, luck=1, chance=0.3)

# ---------------------------------------------------------------- Apotheosis family

cmd("apoth_loot", "bubble apothloot @p", luck=1, needs=["apotheosis:gem_dust"])
cmd("apoth_loot_rare", "bubble apothloot @p rare", luck=2, chance=0.5, needs=["apotheosis:gem_dust"])
cmd("apoth_loot_epic", "bubble apothloot @p epic", luck=2, chance=0.15, needs=["apotheosis:gem_dust"])
kit("apoth_sigils", [("apotheosis:sigil_of_socketing", (1, 2)), ("apotheosis:sigil_of_withdrawal", 1), ("apotheosis:sigil_of_rebirth", 1),
                     ("apotheosis:sigil_of_enhancement", 1), ("apotheosis:sigil_of_unnaming", 1)], luck=1, picks=2)
kit("apoth_materials", [("apotheosis:mysterious_scrap_metal", (4, 12)), ("apotheosis:timeworn_fabric", (2, 6)),
                        ("apotheosis:luminous_crystal_shard", (1, 3)), ("apotheosis:arcane_sands", 1), ("apotheosis:gem_dust", (8, 24))], picks=2)
item("godforged_pearl", "apotheosis:godforged_pearl", 1, luck=2, chance=0.2)
item("invader_summoner", "apotheosis:boss_summoner", 1, luck=-1, chance=0.5)
kit("apoth_tomes", [(i, 1) for i in ids_like("apothic_enchanting", r"_tome$")], luck=1, picks=2)
kit("apoth_shelves", [(i, (1, 3)) for i in ids_like("apothic_enchanting", r"(hellshelf|seashelf|endshelf|deepshelf|sculkshelf)$", r"infused|dormant")],
    luck=1, picks=2)
kit("apoth_rare_enchanting", [("apothic_enchanting:infused_breath", (2, 4)), ("apothic_enchanting:warden_tendril", (1, 2)),
                              ("apothic_enchanting:prismatic_web", 1), ("apothic_enchanting:inert_trident", 1),
                              ("apothic_enchanting:library", 1)], luck=2, chance=0.6, picks=1)

# ---------------------------------------------------------------- Twilight Forest

kit("tf_ingots", [("twilightforest:ironwood_ingot", (2, 6)), ("twilightforest:steeleaf_ingot", (2, 6)), ("twilightforest:knightmetal_ingot", (1, 4)),
                  ("twilightforest:fiery_ingot", (1, 2)), ("twilightforest:carminite", (1, 3)), ("twilightforest:naga_scale", (2, 5))], luck=1, picks=2)
kit("tf_charms", [("twilightforest:charm_of_life_1", 1), ("twilightforest:charm_of_keeping_1", 1), ("twilightforest:charm_of_life_2", 1),
                  ("twilightforest:charm_of_keeping_2", 1)], luck=2, chance=0.5, picks=1)
kit("tf_magic", [("twilightforest:magic_map_focus", 1), ("twilightforest:ore_magnet", 1), ("twilightforest:transformation_powder", (4, 8)),
                 ("twilightforest:peacock_feather_fan", 1), ("twilightforest:moonworm_queen", 1), ("twilightforest:torchberries", (8, 16)),
                 ("twilightforest:uncrafting_table", 1)], luck=1, picks=2)
kit("tf_weapons", [("twilightforest:fiery_sword", 1), ("twilightforest:knightmetal_sword", 1), ("twilightforest:ice_bow", 1),
                   ("twilightforest:triple_bow", 1), ("twilightforest:seeker_bow", 1), ("twilightforest:ender_bow", 1),
                   ("twilightforest:giant_sword", 1), ("twilightforest:block_and_chain", 1)], luck=2, chance=0.5, picks=1)
item("lamp_of_cinders", "twilightforest:lamp_of_cinders", 1, luck=2, chance=0.15)
mob("tf_redcaps", "twilightforest:redcap", (2, 4), luck=-1)
mob("tf_kobolds", "twilightforest:kobold", (3, 5), luck=-1)
mob("tf_wraith", "twilightforest:wraith", 1, luck=-2, chance=0.4)
mob("tf_minotaur", "twilightforest:minotaur", 1, luck=-2, chance=0.4)
mob("tf_deer", "twilightforest:deer", 2, luck=0)

# ---------------------------------------------------------------- Oritech

kit("ori_metals", [("oritech:nickel_ingot", (4, 10)), ("oritech:platinum_ingot", (2, 6)), ("oritech:steel_ingot", (4, 10)),
                   ("oritech:electrum_ingot", (3, 8)), ("oritech:adamant_ingot", (1, 3)), ("oritech:energite_ingot", (2, 5)),
                   ("oritech:biosteel_ingot", (2, 5)), ("oritech:duratium_ingot", 1), ("oritech:fluxite", (2, 6))], luck=1, picks=2)
kit("ori_machines", [("oritech:basic_generator_block", 1), ("oritech:pulverizer_block", 1), ("oritech:powered_furnace_block", 1),
                     ("oritech:assembler_block", 1), ("oritech:foundry_block", 1), ("oritech:centrifuge_block", 1),
                     ("oritech:fuel_generator_block", 1), ("oritech:bio_generator_block", 1), ("oritech:charger_block", 1)], luck=1, picks=1)
kit("ori_tools", [("oritech:hand_drill", 1), ("oritech:chainsaw", 1), ("oritech:promethium_pickaxe", 1), ("oritech:electric_mace", 1),
                  ("oritech:exo_jetpack", 1), ("oritech:exo_helmet", 1), ("oritech:exo_chestplate", 1), ("oritech:exo_leggings", 1),
                  ("oritech:exo_boots", 1), ("oritech:portable_laser", 1)], luck=2, chance=0.5, picks=1)
kit("ori_batteries", [("oritech:basic_battery", (1, 3)), ("oritech:advanced_battery", 1)], luck=1, picks=1)
item("low_yield_nuke", "oritech:low_yield_nuke", 1, luck=-1, chance=0.1)

# ---------------------------------------------------------------- The Aether

kit("aether_gems", [("aether:zanite_gemstone", (3, 8)), ("aether:ambrosium_shard", (4, 12)), ("aether:enchanted_gravitite", (1, 3)),
                    ("aether:golden_amber", (4, 10)), ("aether:aechor_petal", (2, 6))], luck=1, picks=2)
kit("aether_gear", [("aether:golden_parachute", 1), ("aether:cold_parachute", 1), ("aether:cloud_staff", 1), ("aether:agility_cape", 1),
                    ("aether:zanite_sword", 1), ("aether:gravitite_sword", 1), ("aether:gravitite_pickaxe", 1), ("aether:flaming_sword", 1),
                    ("aether:lightning_knife", (4, 8)), ("aether:iron_ring", 1), ("aether:golden_ring", 1)], luck=1, picks=1)
item("aether_moa_egg", "aether:blue_moa_egg", 1, luck=1, chance=0.4)
item("aether_bronze_key", "aether:bronze_dungeon_key", 1, luck=1, chance=0.3)

# ---------------------------------------------------------------- Simply Swords / Simply Bows

uniques = [i for i in ids_like("simplyswords", r"^[a-z_]+$", r"(netherite|diamond|iron|gold|stone|wooden|_compat|runic|tablet|relic|remnant|_handheld|gem|essence)")
           if not re.search(r"_(chakram|claymore|cutlass|glaive|greataxe|greathammer|halberd|katana|longsword|rapier|sai|scythe|spear|twinblade|warglaive)$", i)]
kit("simply_unique_sword", [(i, 1) for i in uniques], luck=2, chance=0.6, picks=1)
kit("simply_iron_weapons", [(i, 1) for i in ids_like("simplyswords", r"^iron_(chakram|claymore|cutlass|glaive|greataxe|greathammer|halberd|katana|longsword|rapier|sai|scythe|spear|twinblade|warglaive)$")],
    luck=0, picks=1)
kit("simply_diamond_weapons", [(i, 1) for i in ids_like("simplyswords", r"^diamond_(chakram|claymore|cutlass|glaive|greataxe|greathammer|halberd|katana|longsword|rapier|sai|scythe|spear|twinblade|warglaive)$")],
    luck=1, picks=1)
kit("simply_bows", [(i, 1) for i in ids_like("simplybows", r"^([a-z_]+_bow)/\1$")], luck=1, chance=0.8, picks=1)

# ---------------------------------------------------------------- Artifacts, Relics, L_Ender's Cataclysm

kit("artifact", [(i, 1) for i in ids_like("artifacts", r"^[a-z_]+$", r"spawn_egg|umbrella_|_block")], luck=2, chance=0.8, picks=1)
cmd("artifact_mimic", "summon artifacts:mimic ~ ~1 ~", luck=-1, chance=0.4, needs=["artifacts:mimic"])
kit("cataclysm_materials", [(i, (1, 3)) for i in ids_like("cataclysm", r"(ingot|fragment|nugget|scale|crystal|essence|chitin|bone|shard)$", r"block|template")],
    luck=1, picks=2)
kit("cataclysm_eyes", [(i, 1) for i in ids_like("cataclysm", r"_eye$", r"spawn_egg")], luck=1, chance=0.5, picks=1)
mob("cataclysm_mob", "cataclysm:koboleton", (2, 3), luck=-1, chance=0.6)

# ---------------------------------------------------------------- utility mods

kit("backpacks", [("sophisticatedbackpacks:backpack", 1), ("sophisticatedbackpacks:copper_backpack", 1), ("sophisticatedbackpacks:iron_backpack", 1),
                  ("sophisticatedbackpacks:gold_backpack", 1), ("sophisticatedbackpacks:diamond_backpack", 1)], luck=1, picks=1)
kit("backpack_upgrades", [(i, 1) for i in ids_like("sophisticatedbackpacks", r"_upgrade$", r"advanced|infinity|creative|survival")], luck=1, picks=2)
kit("aircraft", [("immersive_aircraft:biplane", 1), ("immersive_aircraft:gyrodyne", 1), ("immersive_aircraft:quadrocopter", 1),
                 ("immersive_aircraft:airship", 1)], luck=2, chance=0.4, picks=1)
kit("aircraft_parts", [("immersive_aircraft:engine", 1), ("immersive_aircraft:propeller", (1, 2)), ("immersive_aircraft:hull", (2, 4)),
                       ("immersive_aircraft:sail", (2, 4)), ("immersive_aircraft:boiler", 1)], luck=0, picks=2)
kit("instruments", [(i, 1) for i in ids_like("immersive_melodies", r"^[a-z_]+$", r"_hand$")], luck=0, picks=1)
kit("waystones", [("waystones:waystone", 1), ("waystones:warp_stone", 1), ("waystones:return_scroll", (2, 4)), ("waystones:warp_scroll", (1, 3)),
                  ("waystones:warp_dust", (4, 8))], luck=1, picks=2)
kit("compasses", [("naturescompass:naturescompass", 1), ("explorerscompass:explorerscompass", 1)], luck=1, picks=1)

# ---------------------------------------------------------------- the block itself

lucky_block = {
    "id": "lucky:lucky_block",
    "activations": ["break_survival", "power"],
    "settings": {"hardness": 0.3, "resistance": 6.0, "rarity": "rare"},
    "item_luck": {k: v for k, v in {
        "minecraft:diamond": 12, "minecraft:emerald": 8, "minecraft:gold_ingot": 3, "minecraft:gold_block": 30, "minecraft:diamond_block": 80,
        "minecraft:netherite_ingot": 50, "minecraft:nether_star": 100, "minecraft:golden_apple": 15, "minecraft:enchanted_golden_apple": 60,
        "minecraft:rotten_flesh": -5, "minecraft:spider_eye": -5, "minecraft:poisonous_potato": -10, "minecraft:fermented_spider_eye": -15,
        "apotheosis:godforged_pearl": 60, "apotheosis:arcane_sands": 25, "twilightforest:fiery_ingot": 25, "oritech:adamant_ingot": 20,
        "aether:enchanted_gravitite": 20}.items() if k in items},
}
recipe = {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["GGG", "GDG", "GGG"],
          "key": {"G": {"item": "minecraft:gold_ingot"}, "D": {"item": "minecraft:dropper"}}, "result": {"id": "lucky:lucky_block", "count": 1}}


def png(pixels):
    """16x16 RGBA PNG from a list of rows of (r,g,b,a)."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in row) for row in pixels)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


QUESTION = ["................", "................", "................", "......####......", ".....##..##.....", ".........##.....",
            "........##......", ".......##.......", ".......##.......", "................", ".......##.......", ".......##.......",
            "................", "................", "................", "................"]
pixels = []
for y in range(16):
    row = []
    for x in range(16):
        edge = x in (0, 15) or y in (0, 15)
        inner = x in (1, 14) or y in (1, 14)
        if QUESTION[y][x] == "#":
            row.append((255, 255, 255, 255))
        elif edge:
            row.append((150, 98, 10, 255))
        elif inner:
            row.append((255, 226, 92, 255))
        else:
            shade = 232 - (x + y) * 2
            row.append((255, max(150, shade), 30, 255))
    pixels.append(row)

files = {
    "pack.mcmeta": json.dumps({"pack": {"pack_format": 48, "description": "MoonahMC lucky block (every mod in the pack)"}}, indent=2),
    "data/lucky/lucky_block.json": json.dumps(lucky_block, indent=2),
    "data/lucky/recipe/lucky_block.json": json.dumps(recipe, indent=2),
    "assets/lucky/blockstates/lucky_block.json": json.dumps({"variants": {"": {"model": "lucky:block/lucky_block"}}}, indent=2),
    "assets/lucky/models/block/lucky_block.json": json.dumps({"parent": "minecraft:block/cube_all", "textures": {"all": "lucky:block/lucky_block"}}, indent=2),
    "assets/lucky/models/item/lucky_block.json": json.dumps({"parent": "lucky:block/lucky_block"}, indent=2),
    "assets/lucky/lang/en_us.json": json.dumps({"block.lucky.lucky_block": "Lucky Block"}, indent=2),
}
outcomes.pop("animals", None)
for name, o in outcomes.items():
    files[f"data/lucky/outcome/{name}.json"] = json.dumps(o, indent=2)

buf = io.BytesIO()
with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
    for path, text in files.items():
        z.writestr(path, text)
    z.writestr("assets/lucky/textures/block/lucky_block.png", png(pixels))
data = buf.getvalue()
open(os.path.join(HERE, OUT), "wb").write(data)
addons = os.path.join(INSTANCE, "addons")
os.makedirs(addons, exist_ok=True)
open(os.path.join(addons, OUT), "wb").write(data)
print(f"{len(outcomes)} outcomes written to {OUT} (and the instance's addons folder). Known: {len(items)} items, {len(entities)} mobs.")
for name, missing in skipped:
    print("  skipped", name, "- not installed:", ", ".join(missing[:4]) + ("..." if len(missing) > 4 else ""))
