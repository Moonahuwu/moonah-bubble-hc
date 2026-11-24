package moonahmc.bubble;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ops: give, take or set anyone's Strange Matter (this world's wallet) or Eidolons (kept forever), online or not.
 * /bubble currency matter|eidolons give|take|set <players> <amount>, /bubble currency list, and the Currencies screen
 * in the Bubble menu's Admin tab. Every change is announced to the ops and the player, and goes in the run log.
 */
public final class CurrencyAdmin {
	public static final String MATTER = "matter";
	public static final String EIDOLONS = "eidolons";

	private CurrencyAdmin() {
	}

	/** One player in the Currencies screen. */
	public record Row(UUID id, String name, boolean online, double matter, long eidolons) {
	}

	/** Server to client: everyone's balances. */
	public record ListRows(List<Row> rows) implements CustomPacketPayload {
		public static final Type<ListRows> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "currency_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ListRows> CODEC = StreamCodec.ofMember((l, buf) -> {
			buf.writeVarInt(l.rows.size());
			for (Row r : l.rows) {
				buf.writeUUID(r.id);
				buf.writeUtf(r.name, 64);
				buf.writeBoolean(r.online);
				buf.writeDouble(r.matter);
				buf.writeVarLong(r.eidolons);
			}
		}, buf -> {
			int n = Math.min(buf.readVarInt(), 500);
			List<Row> rows = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				rows.add(new Row(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), buf.readDouble(), buf.readVarLong()));
			}
			return new ListRows(rows);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: "request" (send the list), or give / take / set `amount` of `currency` for `target`. */
	public record Edit(String action, UUID target, String currency, double amount) implements CustomPacketPayload {
		public static final Type<Edit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "currency_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeUtf(e.action, 16);
			buf.writeUUID(e.target);
			buf.writeUtf(e.currency, 16);
			buf.writeDouble(e.amount);
		}, buf -> new Edit(buf.readUtf(16), buf.readUUID(), buf.readUtf(16), buf.readDouble()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(ListRows.ID, ListRows.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Edit.ID, (payload, context) -> context.server().execute(() -> {
			ServerPlayer op = context.player();
			if (!op.hasPermissions(2)) {
				return;
			}
			if (!payload.action().equals("request")) {
				String name = nameOf(op.server, payload.target());
				String result = change(op.server, payload.target(), name, payload.currency(), payload.action(), payload.amount(), op.getGameProfile().getName());
				if (result == null) {
					Notice.send(op, false, "Currencies", "That didn't work (unknown currency or amount).");
				}
			}
			sendList(op);
		}));
	}

	// ------------------------------------------------------------------ the change itself

	/**
	 * Gives, takes or sets a currency. Returns a line saying what happened (also sent to ops and the player), or null
	 * if the currency or action wasn't understood. Taking more than they have leaves them at 0.
	 */
	static String change(MinecraftServer s, UUID id, String name, String currency, String action, double amount, String by) {
		if (!Double.isFinite(amount) || amount < 0 || amount > 1e9) {
			return null;
		}
		double before;
		double after;
		String unit;
		switch (currency) {
			case MATTER -> {
				before = Matter.balance(id);
				after = target(action, before, amount);
				if (after < 0) {
					return null;
				}
				Matter.setBalance(id, after);
				unit = "Strange Matter";
			}
			case EIDOLONS -> {
				long whole = Math.round(amount);
				before = Eidolons.balance(id);
				after = target(action, before, whole);
				if (after < 0) {
					return null;
				}
				Eidolons.setBalance(id, name, Math.round(after));
				unit = "Eidolons";
			}
			default -> {
				return null;
			}
		}
		String verb = switch (action) {
			case "give" -> "gave " + name + " " + Matter.format(after - before);
			case "take" -> "took " + Matter.format(before - after) + " from " + name;
			default -> "set " + name + " to " + Matter.format(after);
		};
		String line = by + " " + verb + " " + unit + " (now " + Matter.format(after) + ")";
		RunLog.add(RunLog.ADMIN, line);
		Bubble.LOG.info("[Currencies] {}", line);
		for (ServerPlayer q : s.getPlayerList().getPlayers()) {
			if (q.hasPermissions(2) || q.getUUID().equals(id)) {
				q.sendSystemMessage(Component.literal("[Currencies] " + line).withStyle(ChatFormatting.GRAY));
			}
		}
		return line;
	}

	/** -1 if the action isn't one we know. */
	private static double target(String action, double before, double amount) {
		return switch (action) {
			case "give" -> before + amount;
			case "take" -> Math.max(0, before - amount);
			case "set" -> amount;
			default -> -1;
		};
	}

	private static String nameOf(MinecraftServer s, UUID id) {
		ServerPlayer p = s.getPlayerList().getPlayer(id);
		if (p != null) {
			return p.getGameProfile().getName();
		}
		String known = Eidolons.knownNames().get(id);
		if (known != null && !known.isEmpty()) {
			return known;
		}
		var cached = s.getProfileCache() == null ? java.util.Optional.<GameProfile>empty() : s.getProfileCache().get(id);
		return cached.map(GameProfile::getName).orElse(id.toString().substring(0, 8));
	}

	/** Everyone who's online, has a wallet in this world, or has ever had Eidolons. Online first, then by name. */
	static List<Row> rows(MinecraftServer s) {
		Map<UUID, String> who = new LinkedHashMap<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			who.put(p.getUUID(), p.getGameProfile().getName());
		}
		Eidolons.knownNames().forEach((id, n) -> who.putIfAbsent(id, n));
		for (UUID id : Matter.walletIds()) {
			who.putIfAbsent(id, null);
		}
		List<Row> out = new ArrayList<>();
		who.forEach((id, n) -> out.add(new Row(id, n == null || n.isEmpty() ? nameOf(s, id) : n, s.getPlayerList().getPlayer(id) != null,
				Matter.balance(id), Eidolons.balance(id))));
		out.sort((a, b) -> a.online != b.online ? (a.online ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
		return out;
	}

	static void sendList(ServerPlayer op) {
		if (ServerPlayNetworking.canSend(op, ListRows.ID)) {
			ServerPlayNetworking.send(op, new ListRows(rows(op.server)));
		}
	}

	// ------------------------------------------------------------------ /bubble currency

	static LiteralArgumentBuilder<CommandSourceStack> command() {
		var root = Commands.literal("currency").requires(src -> src.hasPermission(2))
				.then(Commands.literal("list").executes(CurrencyAdmin::list));
		for (String currency : new String[] {MATTER, EIDOLONS}) {
			var cur = Commands.literal(currency);
			for (String action : new String[] {"give", "take", "set"}) {
				cur.then(Commands.literal(action).then(Commands.argument("players", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 1e9))
								.executes(c -> run(c, currency, action)))));
			}
			root.then(cur);
		}
		return root;
	}

	private static int run(CommandContext<CommandSourceStack> c, String currency, String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(c, "players");
		double amount = DoubleArgumentType.getDouble(c, "amount");
		int n = 0;
		for (GameProfile gp : profiles) {
			String line = change(c.getSource().getServer(), gp.getId(), gp.getName(), currency, action, amount, c.getSource().getTextName());
			if (line != null) {
				c.getSource().sendSuccess(() -> Component.literal(line), false);
				n++;
			}
		}
		if (n == 0) {
			c.getSource().sendFailure(Component.literal("Nothing changed."));
		}
		return n;
	}

	private static int list(CommandContext<CommandSourceStack> c) {
		List<Row> rows = rows(c.getSource().getServer());
		c.getSource().sendSuccess(() -> Component.literal("Name: Strange Matter / Eidolons").withStyle(ChatFormatting.GOLD), false);
		for (Row r : rows) {
			String line = (r.online ? "● " : "○ ") + r.name + ": " + Matter.format(r.matter) + " / " + r.eidolons;
			c.getSource().sendSuccess(() -> Component.literal(line).withStyle(r.online ? ChatFormatting.WHITE : ChatFormatting.GRAY), false);
		}
		return rows.size();
	}
}
