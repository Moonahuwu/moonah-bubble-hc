package moonahmc.bubble;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /bubble                          open the settings screen (or print the status for players without the mod)
 * /bubble status                   print the settings
 * /bubble radius <n>               (ops) set the radius, 2-500
 * /bubble enable|disable           (ops) turn the bubble on/off
 * /bubble edge teleport|push       (ops) what happens at the edge
 * /bubble shape ring|sphere        (ops) sphere also limits how far apart people can be vertically
 * /bubble soullink on|off          (ops) shared health
 * /bubble hardcore on|off          (ops) death ends the run
 * /bubble givecase <p> <case> [n] (ops) give case items (right-click to open)
 * /bubble givespin <p> [n]         (ops) give Wheel Spins (right-click: spin the mid-run wheel)
 * /bubble gun <p> [gun]            (ops) give a TACZ gun (random if no gun given) with a full magazine and ammo
 * /bubble ammo <p> [mags]          (ops) ammo for every gun they carry (3 magazines by default)
 * /bubble attachments <gun>       (ops) what the Attachment Case can give for that gun
 * /bubble nuke [pos]               (ops) set off a nuke (Lucky Blocks use this)
 * /bubble video stop               (ops) stop the video someone is showing everyone (shop Video tab)
 * /bubble newrun                   (ops) end this run now (post-game vote)
 * /bubble next                     (ops) skip the kill cam / post-game countdown (the votes decide)
 * /bubble decide next|retry|life   (ops) end the post-game now with that outcome
 * /bubble vote next|retry|life     vote in the post-game
 * /bubble saveseed                 vote to keep this run's seed in the favourites
 * /bubble seeds                    list the favourite seeds
 * /bubble seed <seed>|random       (ops) the seed for the next new world
 * /bubble matter                   your Strange Matter
 * /bubble matter give <player> <n> (ops) give (or with a negative number, take) Strange Matter
 * /bubble matter worth <item>      what an item sells for
 * /bubble shop reload              (ops) reload config/bubble-shop.json
 */
final class BubbleCommand {
	private static final String[] OPTIONS = {"next", "retry", "life"};

	private BubbleCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext registries) {
		var vote = literal("vote");
		var decide = literal("decide").requires(s -> s.hasPermission(2));
		for (int i = 0; i < OPTIONS.length; i++) {
			int option = i;
			vote.then(literal(OPTIONS[i]).executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				if (!BubbleServer.vote(p, option)) {
					c.getSource().sendFailure(Component.literal("There's nothing to vote on right now."));
					return 0;
				}
				return 1;
			}));
			decide.then(literal(OPTIONS[i]).executes(c -> {
				if (!BubbleServer.decide(option)) {
					c.getSource().sendFailure(Component.literal("The run isn't over."));
					return 0;
				}
				return 1;
			}));
		}

		d.register(literal("bubble").then(Wheel.command())); // /bubble wheel [reload]
		d.register(literal("bubble").then(WheelAdmin.command())); // /bubble event [id] [silent]
		d.register(literal("bubble").then(Revive.command())); // /bubble revive [on|off] [seconds]
		d.register(literal("bubble").then(Lobby.command())); // /bubble start
		d.register(literal("bubble").then(literal("eidolons").requires(src -> src.hasPermission(2))
				.then(literal("give").then(argument("player", EntityArgument.player())
						.then(argument("amount", IntegerArgumentType.integer(1, 100000)).executes(c -> {
							Eidolons.adminGive(EntityArgument.getPlayer(c, "player"), IntegerArgumentType.getInteger(c, "amount"));
							return 1;
						}))))));
		var modifier = literal("modifier").requires(src -> src.hasPermission(2));
		for (String id : RunModifiers.ALL) {
			modifier.then(literal(id).executes(c -> {
				RunModifiers.activate(id);
				c.getSource().sendSuccess(() -> Component.literal("Run modifier on: " + id), true);
				return 1;
			}));
		}
		d.register(literal("bubble").then(modifier));
		// /bubble givecase <players> <case> [count]
		d.register(literal("bubble").then(literal("givecase").requires(src -> src.hasPermission(2))
				.then(argument("players", EntityArgument.players())
						.then(argument("case", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(Gamble.ids(), b))
								.executes(c -> giveCase(c, 1))
								.then(argument("count", IntegerArgumentType.integer(1, 64))
										.executes(c -> giveCase(c, IntegerArgumentType.getInteger(c, "count"))))))));
		d.register(literal("bubble").then(literal("givespin").requires(src -> src.hasPermission(2))
				.then(argument("players", EntityArgument.players())
						.executes(c -> giveSpin(c, 1))
						.then(argument("count", IntegerArgumentType.integer(1, 64))
								.executes(c -> giveSpin(c, IntegerArgumentType.getInteger(c, "count")))))));
		d.register(literal("bubble").then(literal("gun").requires(src -> src.hasPermission(2))
				.then(argument("players", EntityArgument.players())
						.executes(c -> giveGun(c, null))
						.then(argument("gun", StringArgumentType.word())
								.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(Guns.GUNS.stream().map(Guns.Gun::id), b))
								.executes(c -> giveGun(c, StringArgumentType.getString(c, "gun")))))));
		d.register(literal("bubble").then(literal("ammo").requires(src -> src.hasPermission(2))
				.then(argument("players", EntityArgument.players())
						.executes(c -> giveAmmo(c, 3))
						.then(argument("magazines", IntegerArgumentType.integer(1, 20))
								.executes(c -> giveAmmo(c, IntegerArgumentType.getInteger(c, "magazines")))))));
		d.register(literal("bubble").then(literal("attachments").requires(src -> src.hasPermission(2))
				.then(argument("gun", StringArgumentType.word())
						.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(Guns.GUNS.stream().map(Guns.Gun::id), b))
						.executes(c -> listAttachments(c.getSource(), StringArgumentType.getString(c, "gun"))))));
		d.register(literal("bubble").then(literal("video").then(literal("stop").requires(src -> src.hasPermission(2)).executes(c -> {
			if (!VideoShare.stop(c.getSource().getTextName())) {
				c.getSource().sendFailure(Component.literal("No video is playing."));
				return 0;
			}
			return 1;
		}))));
		d.register(literal("bubble").then(literal("nuke").requires(src -> src.hasPermission(2))
				.executes(c -> nuke(c.getSource(), net.minecraft.core.BlockPos.containing(c.getSource().getPosition())))
				.then(argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
						.executes(c -> nuke(c.getSource(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos"))))));
		if (net.neoforged.fml.ModList.get().isLoaded("apotheosis")) {
			d.register(literal("bubble").then(ApothTiers.command())); // /bubble tier [name]
			d.register(literal("bubble").then(ApothRoll.command())); // /bubble apothloot <player> [rarity]
		}
		d.register(literal("bubble")
				.executes(c -> {
					ServerPlayer p = c.getSource().getPlayer();
					if (p == null || !BubbleServer.openFor(p)) {
						c.getSource().sendSuccess(() -> Component.literal("Bubble: ").append(BubbleServer.statusText()), false);
					}
					return 1;
				})
				.then(literal("status").executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("Bubble: ").append(BubbleServer.statusText()), false);
					return 1;
				}))
				.then(literal("radius").requires(s -> s.hasPermission(2))
						.then(argument("blocks", IntegerArgumentType.integer(BubbleConfig.MIN_RADIUS, BubbleConfig.MAX_RADIUS))
								.executes(c -> BubbleServer.setRadius(IntegerArgumentType.getInteger(c, "blocks"), c.getSource()))))
				.then(literal("enable").requires(s -> s.hasPermission(2)).executes(c -> set(c.getSource(), true, null, null, null, null)))
				.then(literal("disable").requires(s -> s.hasPermission(2)).executes(c -> set(c.getSource(), false, null, null, null, null)))
				.then(literal("edge").requires(s -> s.hasPermission(2))
						.then(literal("teleport").executes(c -> set(c.getSource(), null, false, null, null, null)))
						.then(literal("push").executes(c -> set(c.getSource(), null, true, null, null, null))))
				.then(literal("shape").requires(s -> s.hasPermission(2))
						.then(literal("ring").executes(c -> set(c.getSource(), null, null, null, null, false)))
						.then(literal("sphere").executes(c -> set(c.getSource(), null, null, null, null, true))))
				.then(literal("soullink").requires(s -> s.hasPermission(2))
						.then(literal("on").executes(c -> set(c.getSource(), null, null, true, null, null)))
						.then(literal("off").executes(c -> set(c.getSource(), null, null, false, null, null))))
				.then(literal("hardcore").requires(s -> s.hasPermission(2))
						.then(literal("on").executes(c -> set(c.getSource(), null, null, null, true, null)))
						.then(literal("off").executes(c -> set(c.getSource(), null, null, null, false, null))))
				.then(literal("newrun").requires(s -> s.hasPermission(2)).executes(c -> {
					boolean ok = BubbleServer.endRun(Component.literal(c.getSource().getTextName() + " ended the run"), null);
					if (!ok) {
						c.getSource().sendFailure(Component.literal("The run is already ending, or this isn't a dedicated server."));
					}
					return ok ? 1 : 0;
				}))
				.then(literal("next").requires(s -> s.hasPermission(2)).executes(c -> {
					boolean ok = BubbleServer.skipToNextRun();
					if (!ok) {
						c.getSource().sendFailure(Component.literal("Nothing to skip: the run isn't over."));
					}
					return ok ? 1 : 0;
				}))
				.then(decide)
				.then(vote)
				.then(literal("saveseed").executes(c -> {
					if (!BubbleServer.toggleSaveSeed(c.getSource().getPlayerOrException())) {
						c.getSource().sendFailure(Component.literal("You can save the seed on the summary screen after a run."));
						return 0;
					}
					return 1;
				}))
				.then(literal("seeds").executes(BubbleCommand::listSeeds))
				.then(literal("seed").requires(s -> s.hasPermission(2))
						.then(literal("random").executes(c -> setSeed(c, "")))
						.then(argument("seed", StringArgumentType.word()).executes(c -> setSeed(c, StringArgumentType.getString(c, "seed")))))
				.then(literal("matter")
						.executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							c.getSource().sendSuccess(() -> Component.literal("You have " + Matter.format(Matter.balance(p)) + " Strange Matter")
									.withStyle(ChatFormatting.AQUA), false);
							return 1;
						})
						.then(literal("worth").then(argument("item", ItemArgument.item(registries)).executes(c -> {
							var stack = ItemArgument.getItem(c, "item").createItemStack(1, false);
							c.getSource().sendSuccess(() -> Component.literal("Worth: ").append(stack.getHoverName())
									.append(Component.literal(" sells for " + Matter.format(Matter.value(stack)) + " Strange Matter ("
											+ String.format("%.3f", Matter.value(stack)) + ")")), false);
							return 1;
						})))
						.then(literal("give").requires(s -> s.hasPermission(2))
								.then(argument("player", EntityArgument.player())
										.then(argument("amount", DoubleArgumentType.doubleArg(-1_000_000, 1_000_000)).executes(c -> {
											ServerPlayer p = EntityArgument.getPlayer(c, "player");
											double amount = DoubleArgumentType.getDouble(c, "amount");
											if (!Matter.add(p, amount)) {
												Matter.add(p, -Matter.balance(p));
											}
											c.getSource().sendSuccess(() -> Component.literal(p.getName().getString() + " now has "
													+ Matter.format(Matter.balance(p)) + " Strange Matter"), true);
											return 1;
										})))))
				.then(literal("shop").requires(s -> s.hasPermission(2))
						.then(literal("reload").executes(c -> {
							Shop.reload();
							c.getSource().sendSuccess(() -> Component.literal("Shop reloaded from config/bubble-shop.json"), true);
							return 1;
						}))));
	}

	private static int listSeeds(CommandContext<CommandSourceStack> c) {
		List<SeedBook.Saved> top = SeedBook.top();
		if (top.isEmpty()) {
			c.getSource().sendSuccess(() -> Component.literal("No favourite seeds yet. Save one from the summary screen after a run."), false);
			return 0;
		}
		c.getSource().sendSuccess(() -> Component.literal("★ Favourite seeds").withStyle(ChatFormatting.YELLOW), false);
		for (int i = 0; i < Math.min(10, top.size()); i++) {
			SeedBook.Saved s = top.get(i);
			int n = i + 1;
			c.getSource().sendSuccess(() -> Component.literal(n + ". " + s.seed).withStyle(ChatFormatting.WHITE)
					.append(Component.literal("  run #" + s.run + ", " + s.date + ", saved by " + String.join(", ", s.savedBy))
							.withStyle(ChatFormatting.GRAY)), false);
		}
		String next = BubbleServer.config.nextSeed;
		c.getSource().sendSuccess(() -> Component.literal("Next new world: " + (next.isEmpty() ? "random seed" : "seed " + next)
				+ ". Ops: /bubble seed <seed>").withStyle(ChatFormatting.GRAY), false);
		return top.size();
	}

	private static int setSeed(CommandContext<CommandSourceStack> c, String seed) {
		String clean = seed.replaceAll("[^0-9A-Za-z_-]", "");
		BubbleServer.config.nextSeed = clean;
		BubbleServer.saveConfig();
		c.getSource().sendSuccess(() -> Component.literal("The next new world will use " + (clean.isEmpty() ? "a random seed" : "seed " + clean)
				+ " (\"Retry\" still replays the current seed)."), true);
		return 1;
	}

	private static int set(CommandSourceStack source, Boolean bubble, Boolean push, Boolean soulLink, Boolean hardcore, Boolean vertical) {
		BubbleConfig c = BubbleServer.config;
		BubbleServer.apply(bubble != null ? bubble : c.bubbleEnabled, push != null ? push : c.pushMode,
				soulLink != null ? soulLink : c.soulLink, hardcore != null ? hardcore : c.hardcoreReset,
				vertical != null ? vertical : c.vertical, c.radius, source.getTextName());
		return 1;
	}

	private static int giveSpin(CommandContext<CommandSourceStack> c, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var players = EntityArgument.getPlayers(c, "players");
		for (ServerPlayer p : players) {
			net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(ModContent.WHEEL_SPIN.get(), count);
			if (!p.getInventory().add(stack)) {
				p.drop(stack, false);
			}
		}
		c.getSource().sendSuccess(() -> Component.literal("Gave " + count + "x Wheel Spin to " + players.size() + " player(s)"), true);
		return players.size();
	}

	private static int giveGun(CommandContext<CommandSourceStack> c, String id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		if (!Guns.available()) {
			c.getSource().sendFailure(Component.literal("TACZ isn't installed."));
			return 0;
		}
		var players = EntityArgument.getPlayers(c, "players");
		var chosen = id == null ? java.util.Optional.<Guns.Gun>empty() : Guns.find(id);
		if (id != null && chosen.isEmpty()) {
			c.getSource().sendFailure(Component.literal("No gun called '" + id + "'."));
			return 0;
		}
		for (ServerPlayer p : players) {
			Guns.give(p, chosen.orElseGet(() -> Guns.random(p.getRandom())));
		}
		c.getSource().sendSuccess(() -> Component.literal("Gave a gun to " + players.size() + " player(s)"), true);
		return players.size();
	}

	private static int giveAmmo(CommandContext<CommandSourceStack> c, int mags) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		if (!Guns.available()) {
			c.getSource().sendFailure(Component.literal("TACZ isn't installed."));
			return 0;
		}
		int n = 0;
		for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
			var stacks = GunSupply.ammoFor(p, mags);
			if (!stacks.isEmpty()) {
				GunSupply.give(p, stacks, "Ammo:");
				n++;
			}
		}
		int given = n;
		c.getSource().sendSuccess(() -> Component.literal("Gave ammo to " + given + " player(s) with guns"), true);
		return n;
	}

	/** What the Attachment Case can give for a gun, by rarity. */
	private static int listAttachments(CommandSourceStack src, String id) {
		if (!Guns.available()) {
			src.sendFailure(Component.literal("TACZ isn't installed."));
			return 0;
		}
		String plain = id.startsWith("tacz:") ? id.substring(5) : id;
		var tiers = GunSupply.attachmentsFor(Guns.gun(new Guns.Gun(plain, "", 1, "SEMI")));
		int total = tiers.stream().mapToInt(java.util.List::size).sum();
		if (total == 0) {
			src.sendFailure(Component.literal("Nothing fits '" + plain + "' (is that a TACZ gun?)."));
			return 0;
		}
		src.sendSuccess(() -> Component.literal("Attachment Case for " + plain + ": " + total + " attachments"), false);
		for (int t = 0; t < tiers.size(); t++) {
			var names = tiers.get(t).stream().map(s -> GunSupply.name(s).getString()).toList();
			String line = Gamble.TIERS[t] + " (" + names.size() + "): " + String.join(", ", names);
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return total;
	}

	private static int nuke(CommandSourceStack src, net.minecraft.core.BlockPos pos) {
		NukeCreeper.nuke(src.getLevel(), pos, "A nuke (" + src.getTextName() + ")");
		return 1;
	}

	private static int giveCase(CommandContext<CommandSourceStack> c, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		String id = StringArgumentType.getString(c, "case");
		if (!Gamble.exists(id)) {
			c.getSource().sendFailure(Component.literal("No case called '" + id + "'. Cases: " + String.join(", ", Gamble.ids())));
			return 0;
		}
		var players = EntityArgument.getPlayers(c, "players");
		for (ServerPlayer p : players) {
			net.minecraft.world.item.ItemStack stack = CaseItem.of(id, count);
			if (!p.getInventory().add(stack)) {
				p.drop(stack, false);
			}
		}
		c.getSource().sendSuccess(() -> Component.literal("Gave " + count + "x " + Gamble.caseName(id) + " to " + players.size() + " player(s)"), true);
		return players.size();
	}
}
