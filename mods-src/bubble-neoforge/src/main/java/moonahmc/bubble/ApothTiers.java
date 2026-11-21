package moonahmc.bubble;

import dev.shadowsoffire.apotheosis.tiers.WorldTier;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared Apotheosis world tiers. When anyone unlocks a tier (Frontier, Ascent, Summit, Pinnacle), everyone gets it
 * unlocked and switched to it right away, and anyone who joins later catches up. Going into the Nether unlocks and
 * activates Ascent. The shared tier is saved in the world folder, so a new world (hardcore reset) starts at Haven again.
 * Only loaded when Apotheosis is installed.
 */
final class ApothTiers {
	private static MinecraftServer server;
	private static Path file;
	/** Highest tier unlocked by anyone in this world. */
	private static WorldTier shared = WorldTier.HAVEN;
	/** Set while we hand out advancements, so those grants don't start another round. */
	private static boolean granting;

	private ApothTiers() {
	}

	static void init() {
		var bus = NeoForge.EVENT_BUS;
		bus.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			file = server.getWorldPath(LevelResource.ROOT).resolve("bubble-tiers.json");
			shared = load();
			Bubble.LOG.info("Shared world tier: {}", shared.getSerializedName());
		});
		bus.addListener((ServerStoppedEvent e) -> server = null);
		bus.addListener((AdvancementEvent.AdvancementEarnEvent e) -> {
			if (granting || !(e.getEntity() instanceof ServerPlayer player)) {
				return;
			}
			ResourceLocation id = e.getAdvancement().id();
			for (WorldTier t : WorldTier.values()) {
				if (t != WorldTier.HAVEN && t.getUnlockAdvancement().equals(id)) {
					// After the advancement has been recorded for this player
					player.server.execute(() -> raise(t, player.getDisplayName().copy().append(" unlocked it")));
				}
			}
		});
		bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
			if (e.getTo() == Level.NETHER && e.getEntity() instanceof ServerPlayer player) {
				player.server.execute(() -> raise(WorldTier.ASCENT, Component.literal("someone reached the Nether")));
			}
		});
		bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				player.server.execute(() -> {
					if (player.level().dimension() == Level.NETHER) {
						raise(WorldTier.ASCENT, Component.literal("someone is in the Nether"));
					}
					catchUp(player);
				});
			}
		});
	}

	/** Makes `tier` (and everything below it) the shared tier if it's higher, and brings everyone up to it. */
	static void raise(WorldTier tier, Component why) {
		if (server == null) {
			return;
		}
		boolean higher = tier.ordinal() > shared.ordinal();
		if (higher) {
			shared = tier;
			save();
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			catchUp(p);
		}
		if (higher) {
			server.getPlayerList().broadcastSystemMessage(Component.literal("⚔ World tier ").withStyle(ChatFormatting.GOLD)
					.append(tier.toComponent().copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
					.append(Component.literal(" is now active for everyone").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(" (").withStyle(ChatFormatting.GRAY)).append(why.copy().withStyle(ChatFormatting.GRAY))
					.append(Component.literal(")").withStyle(ChatFormatting.GRAY)), false);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7f, 1.0f);
			}
			Bubble.LOG.info("Shared world tier raised to {} ({})", tier.getSerializedName(), why.getString());
		}
	}

	/** The start-of-run wheel's "Hard world": straight to Ascent (normally that takes reaching the Nether). */
	static void hardStart() {
		raise(WorldTier.ASCENT, Component.literal("the wheel landed on Hard world"));
	}

	/** Unlocks every tier up to the shared one for this player and switches them to it (never down). */
	private static void catchUp(ServerPlayer p) {
		granting = true;
		try {
			for (WorldTier t : WorldTier.values()) {
				if (t.ordinal() <= shared.ordinal()) {
					grant(p, t.getUnlockAdvancement());
				}
			}
		} finally {
			granting = false;
		}
		if (WorldTier.getTier(p).ordinal() < shared.ordinal()) {
			WorldTier.setTier(p, shared);
		}
		skipTutorial(p);
	}

	/**
	 * Apotheosis keeps affixes, gems etc. switched off ("This item has an unknown power") until each player has opened
	 * the World Tier screen and activated a tier once. Do that for them: re-activating their current tier records the
	 * "world tiers activated" stat, which is what ends the tutorial. The stat is sent right away so tooltips update too.
	 */
	private static void skipTutorial(ServerPlayer p) {
		try {
			if (WorldTier.isTutorialActive(p)) {
				WorldTier.setTier(p, WorldTier.getTier(p));
				p.getStats().sendStats(p);
				Bubble.LOG.info("Apotheosis: skipped the world tier tutorial for {}", p.getGameProfile().getName());
			}
		} catch (Throwable t) {
			Bubble.LOG.warn("Apotheosis: couldn't skip the world tier tutorial for {}", p.getGameProfile().getName(), t);
		}
	}

	private static void grant(ServerPlayer p, ResourceLocation id) {
		AdvancementHolder adv = server.getAdvancements().get(id);
		if (adv == null) {
			return;
		}
		AdvancementProgress progress = p.getAdvancements().getOrStartProgress(adv);
		if (progress.isDone()) {
			return;
		}
		for (String criterion : progress.getRemainingCriteria()) {
			p.getAdvancements().award(adv, criterion);
		}
	}

	private static WorldTier load() {
		try {
			if (Files.exists(file)) {
				String name = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("tier").getAsString();
				for (WorldTier t : WorldTier.values()) {
					if (t.getSerializedName().equals(name)) {
						return t;
					}
				}
			}
		} catch (Exception e) {
			Bubble.LOG.error("Could not read {}", file, e);
		}
		return WorldTier.HAVEN;
	}

	private static void save() {
		try {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("tier", shared.getSerializedName());
			Files.writeString(file, o.toString());
		} catch (Exception e) {
			Bubble.LOG.error("Could not save {}", file, e);
		}
	}

	/** /bubble tier shows the shared tier; ops can raise it with /bubble tier <name> (it never goes down). */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
		var node = net.minecraft.commands.Commands.literal("tier").executes(c -> {
			c.getSource().sendSuccess(() -> Component.literal("Shared world tier: ").append(shared.toComponent()), false);
			return 1;
		});
		for (WorldTier t : WorldTier.values()) {
			node.then(net.minecraft.commands.Commands.literal(t.getSerializedName()).requires(s -> s.hasPermission(2)).executes(c -> {
				if (t.ordinal() <= shared.ordinal()) {
					c.getSource().sendSuccess(() -> Component.literal("Already at ").append(shared.toComponent())
							.append(" (the shared tier only goes up; a new world starts at Haven)"), false);
				} else {
					raise(t, Component.literal("set by " + c.getSource().getTextName()));
				}
				return 1;
			}));
		}
		return node;
	}
}
