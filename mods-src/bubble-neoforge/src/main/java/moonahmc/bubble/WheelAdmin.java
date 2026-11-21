package moonahmc.bubble;

import com.google.gson.JsonObject;
import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Admin tab's "Wheel and events" screen: ops switch wheel slices and random events on/off, change their weights,
 * do a slice or force an event (announced or silent). Also /bubble event <id> [silent].
 */
public final class WheelAdmin {
	/** Client to server. what: request, slice_enabled, slice_weight, slice_apply, slice_move (list = "start"/"mid"),
	 *  events_enabled, events_announce, events_min, events_max, event_enabled, event_weight, event_force (value 1 = announced). */
	public record Edit(String what, String list, int index, int value) implements CustomPacketPayload {
		public static final Type<Edit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "wheel_admin_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeUtf(e.what, 32);
			buf.writeUtf(e.list, 16);
			buf.writeVarInt(e.index + 1);
			buf.writeVarInt(e.value);
		}, buf -> new Edit(buf.readUtf(32), buf.readUtf(16), buf.readVarInt() - 1, buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: {"start":[...], "mid":[...], "events":{...}}. */
	public record Data(String json) implements CustomPacketPayload {
		public static final Type<Data> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "wheel_admin_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.ofMember((d, buf) -> buf.writeUtf(d.json, 1 << 18),
				buf -> new Data(buf.readUtf(1 << 18)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	private WheelAdmin() {
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Data.ID, Data.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Edit.ID, (payload, context) -> context.server().execute(() -> edit(context.player(), payload)));
	}

	private static String json() {
		JsonObject o = Wheel.editorJson();
		o.add("events", RandomEvents.editorJson());
		return o.toString();
	}

	/** Sends the current state to every op online (so two ops editing see each other's changes). */
	static void broadcast(net.minecraft.server.MinecraftServer server) {
		String j = json();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.hasPermissions(2) && ServerPlayNetworking.canSend(p, Data.ID)) {
				ServerPlayNetworking.send(p, new Data(j));
			}
		}
	}

	private static void edit(ServerPlayer p, Edit e) {
		if (!p.hasPermissions(2)) {
			return;
		}
		boolean mid = "mid".equals(e.list());
		switch (e.what()) {
			case "request" -> {
				if (ServerPlayNetworking.canSend(p, Data.ID)) {
					ServerPlayNetworking.send(p, new Data(json()));
				}
				return;
			}
			case "slice_enabled" -> Wheel.editSlice(mid, e.index(), e.value() != 0, null);
			case "slice_weight" -> Wheel.editSlice(mid, e.index(), null, e.value());
			case "slice_move" -> {
				String name = Wheel.sliceName(mid, e.index());
				String why = Wheel.moveSlice(mid, e.index());
				if (why != null) {
					Notice.send(p, false, "Couldn't move it", why);
				} else {
					Notice.send(p, true, "Moved " + name, "It's on the " + (mid ? "start" : "mid-run") + " wheel now.");
				}
			}
			case "slice_apply" -> {
				String name = Wheel.sliceName(mid, e.index());
				if (!Wheel.applyIndex(mid, e.index(), p)) {
					Notice.send(p, false, "Couldn't do that slice", "");
				} else {
					Notice.send(p, true, "Did " + name, "For everyone, no spin.");
				}
			}
			case "events_enabled" -> RandomEvents.edit("enabled", 0, e.value());
			case "events_announce" -> RandomEvents.edit("announce", 0, e.value());
			case "events_min" -> RandomEvents.edit("min", 0, e.value());
			case "events_max" -> RandomEvents.edit("max", 0, e.value());
			case "event_enabled" -> RandomEvents.edit("event_enabled", e.index(), e.value());
			case "event_weight" -> RandomEvents.edit("event_weight", e.index(), e.value());
			case "event_force" -> {
				String name = RandomEvents.name(e.index());
				if (RandomEvents.force(e.index(), e.value() != 0, p)) {
					Notice.send(p, true, name, e.value() != 0 ? "Announced to everyone." : "Done quietly, nobody was told.");
				} else {
					Notice.send(p, false, name + " didn't happen", "Is anyone alive and in survival?");
				}
			}
			default -> {
				return;
			}
		}
		Bubble.LOG.info("Wheel/events admin: {} {} #{} = {} by {}", e.what(), e.list(), e.index(), e.value(), p.getGameProfile().getName());
		broadcast(p.server);
	}

	/** /bubble event [id] [silent]: force a random event (or a random one). */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> command() {
		var id = net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
				.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(RandomEvents.ids(), b))
				.executes(c -> force(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), true))
				.then(net.minecraft.commands.Commands.literal("silent")
						.executes(c -> force(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), false)));
		return net.minecraft.commands.Commands.literal("event").requires(s -> s.hasPermission(2))
				.executes(c -> force(c.getSource(), "random", true))
				.then(id);
	}

	private static int force(CommandSourceStack src, String id, boolean loud) {
		int index = id.equals("random") ? -1 : RandomEvents.indexOf(id);
		if (index == -2) {
			src.sendFailure(Component.literal("No event '" + id + "'. Events: " + String.join(", ", RandomEvents.ids())));
			return 0;
		}
		if (!RandomEvents.force(index, loud, src.getPlayer())) {
			src.sendFailure(Component.literal("That event couldn't happen (is anyone alive and in survival?)."));
			return 0;
		}
		src.sendSuccess(() -> Component.literal("Event " + id + (loud ? " (announced)" : " (silent)")), true);
		return 1;
	}
}
