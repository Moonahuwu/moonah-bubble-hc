package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import moonahmc.bubble.net.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * How easily players turn into ragdolls (Ragdoll Reactions) and what ragdolls can do (Sable Ragdolls), set live from
 * Bubble: Bubble menu > Admin > Ragdolls, or config/bubble.json ("ragdolls"). Bubble's values are put into both mods
 * when the server starts and whenever they change (their own toml files are then only the starting point).
 * The wheel's "Fragile mode" status swaps in the Glass profile while it lasts, then puts these back.
 */
public final class RagdollTuning {
	/** The settings (saved in config/bubble.json). Speeds in m/s, damage in half-hearts like the mods use. */
	public static final class Tune {
		/** Players ragdoll from big hits, falls, crashes and explosions at all. */
		public boolean reactions = true;
		/** A sudden change of speed at least this big (m/s) ragdolls you (Ragdoll Reactions' impact trigger). */
		public double impactSpeed = 16;
		/** A hit doing at least this much damage ragdolls you. */
		public double hitDamage = 8;
		/** Fall or crash damage of at least this much ragdolls you. */
		public double fallDamage = 4;
		/** How hard hits and explosions throw you (1 = the mod's own). */
		public double fling = 1;
		/** Seconds before you can be ragdolled again. */
		public int cooldownSeconds = 3;
		/** Mobs ragdoll from big hits and falls too. */
		public boolean mobsReact = true;
		/** Ragdolls hitting things hard take damage. */
		public boolean impactDamage = true;
		public double impactDamageMultiplier = 0.75;
		/** Players can grab and drag ragdolls. */
		public boolean grab = true;
		/** Players can ragdoll themselves with the mod's key. */
		public boolean manualKey = true;

		Tune copy() {
			Tune t = new Tune();
			t.reactions = reactions;
			t.impactSpeed = impactSpeed;
			t.hitDamage = hitDamage;
			t.fallDamage = fallDamage;
			t.fling = fling;
			t.cooldownSeconds = cooldownSeconds;
			t.mobsReact = mobsReact;
			t.impactDamage = impactDamage;
			t.impactDamageMultiplier = impactDamageMultiplier;
			t.grab = grab;
			t.manualKey = manualKey;
			return t;
		}

		void clamp() {
			impactSpeed = Math.max(1, Math.min(200, impactSpeed));
			hitDamage = Math.max(0.5, Math.min(100, hitDamage));
			fallDamage = Math.max(0.5, Math.min(100, fallDamage));
			fling = Math.max(0.1, Math.min(10, fling));
			cooldownSeconds = Math.max(0, Math.min(60, cooldownSeconds));
			impactDamageMultiplier = Math.max(0, Math.min(5, impactDamageMultiplier));
		}

		/** Presets: 0 Sturdy, 1 Normal (the mods' own), 2 Fragile, 3 Glass (the wheel's Fragile mode). */
		static Tune preset(int which, Tune base) {
			Tune t = base.copy();
			double[][] v = {
					{24, 12, 6, 0.75, 5},
					{16, 8, 4, 1, 3},
					{8, 4, 2, 1.5, 2},
					{4, 1, 0.5, 2.5, 1}};
			double[] p = v[Math.max(0, Math.min(3, which))];
			t.impactSpeed = p[0];
			t.hitDamage = p[1];
			t.fallDamage = p[2];
			t.fling = p[3];
			t.cooldownSeconds = (int) p[4];
			t.reactions = true;
			return t;
		}
	}

	private static MinecraftServer server;
	private static boolean fragile;
	private static boolean warned;

	private RagdollTuning() {
	}

	static boolean sable() {
		return ModList.get().isLoaded("sable_player_ragdoll");
	}

	static boolean reactionsMod() {
		return ModList.get().isLoaded("ragdoll_reactions");
	}

	static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Edit.ID, (payload, context) -> context.server().execute(() -> edit(context.player(), payload)));
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
			server = e.getServer();
			fragile = false;
			apply();
		});
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> server = null);
	}

	/** Wheel status "fragile": Glass while it's on, back to the saved settings after. */
	static void fragile(boolean on) {
		fragile = on;
		apply();
	}

	static boolean fragileOn() {
		return fragile;
	}

	/** Puts the current settings (or Glass during Fragile mode) into the two mods. */
	static void apply() {
		Tune t = fragile ? Tune.preset(3, BubbleServer.config.ragdolls) : BubbleServer.config.ragdolls;
		try {
			if (reactionsMod()) {
				ReactionsTuningCompat.apply(t);
			}
			if (sable()) {
				SableTuningCompat.apply(t);
			}
		} catch (Throwable ex) {
			if (!warned) {
				warned = true;
				Bubble.LOG.error("Ragdolls: couldn't set the ragdoll mods' settings", ex);
			}
		}
	}

	/** /bubble ragdolls [sturdy|normal|fragile|glass]: shows (or sets a preset of) what the ragdoll mods are using. */
	static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
		var root = net.minecraft.commands.Commands.literal("ragdolls").requires(s -> s.hasPermission(2)).executes(c -> {
			c.getSource().sendSuccess(() -> Component.literal(describe()), false);
			return 1;
		});
		String[] names = {"sturdy", "normal", "fragile", "glass"};
		for (int i = 0; i < names.length; i++) {
			int which = i;
			root.then(net.minecraft.commands.Commands.literal(names[i]).executes(c -> {
				BubbleServer.config.ragdolls = Tune.preset(which, BubbleServer.config.ragdolls);
				BubbleServer.saveConfig();
				apply();
				c.getSource().sendSuccess(() -> Component.literal("Ragdolls: " + names[which] + ". " + describe()), true);
				return 1;
			}));
		}
		return root;
	}

	private static String describe() {
		StringBuilder b = new StringBuilder(fragile ? "Fragile mode on. " : "");
		try {
			if (reactionsMod()) {
				b.append("Ragdoll Reactions: ").append(ReactionsTuningCompat.describe()).append(". ");
			}
			if (sable()) {
				b.append("Sable Ragdolls: ").append(SableTuningCompat.describe()).append('.');
			}
		} catch (Throwable ex) {
			b.append("couldn't read them: ").append(ex);
		}
		return b.length() == 0 ? "No ragdoll mods installed." : b.toString();
	}

	// ------------------------------------------------------------------ the admin screen

	public record Settings(boolean sable, boolean reactionsMod, boolean fragile, boolean reactions, double impactSpeed, double hitDamage,
						   double fallDamage, double fling, int cooldownSeconds, boolean mobsReact, boolean impactDamage,
						   double impactDamageMultiplier, boolean grab, boolean manualKey) implements CustomPacketPayload {
		public static final Type<Settings> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "ragdoll_tuning"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = StreamCodec.ofMember((s, buf) -> {
			buf.writeBoolean(s.sable);
			buf.writeBoolean(s.reactionsMod);
			buf.writeBoolean(s.fragile);
			buf.writeBoolean(s.reactions);
			buf.writeDouble(s.impactSpeed);
			buf.writeDouble(s.hitDamage);
			buf.writeDouble(s.fallDamage);
			buf.writeDouble(s.fling);
			buf.writeVarInt(s.cooldownSeconds);
			buf.writeBoolean(s.mobsReact);
			buf.writeBoolean(s.impactDamage);
			buf.writeDouble(s.impactDamageMultiplier);
			buf.writeBoolean(s.grab);
			buf.writeBoolean(s.manualKey);
		}, buf -> new Settings(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
				buf.readDouble(), buf.readBoolean(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}

		/** With one of the presets' numbers in (0 Sturdy .. 3 Glass). */
		public Settings withPreset(int which) {
			double[][] v = {
					{24, 12, 6, 0.75, 5},
					{16, 8, 4, 1, 3},
					{8, 4, 2, 1.5, 2},
					{4, 1, 0.5, 2.5, 1}};
			double[] p = v[Math.max(0, Math.min(3, which))];
			return new Settings(sable, reactionsMod, fragile, true, p[0], p[1], p[2], p[3], (int) p[4], mobsReact, impactDamage,
					impactDamageMultiplier, grab, manualKey);
		}
	}

	/** Client to server: an op changed them (request = just send them). */
	public record Edit(boolean request, Settings settings) implements CustomPacketPayload {
		public static final Type<Edit> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, "ragdoll_tuning_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeBoolean(e.request);
			Settings.CODEC.encode(buf, e.settings);
		}, buf -> new Edit(buf.readBoolean(), Settings.CODEC.decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Settings.ID, Settings.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}

	private static Settings settings() {
		Tune t = BubbleServer.config.ragdolls;
		return new Settings(sable(), reactionsMod(), fragile, t.reactions, t.impactSpeed, t.hitDamage, t.fallDamage, t.fling,
				t.cooldownSeconds, t.mobsReact, t.impactDamage, t.impactDamageMultiplier, t.grab, t.manualKey);
	}

	private static void send(ServerPlayer p) {
		if (ServerPlayNetworking.canSend(p, Settings.ID)) {
			ServerPlayNetworking.send(p, settings());
		}
	}

	private static void edit(ServerPlayer p, Edit e) {
		if (!p.hasPermissions(2)) {
			return;
		}
		if (e.request()) {
			send(p);
			return;
		}
		Settings s = e.settings();
		Tune t = BubbleServer.config.ragdolls;
		t.reactions = s.reactions();
		t.impactSpeed = s.impactSpeed();
		t.hitDamage = s.hitDamage();
		t.fallDamage = s.fallDamage();
		t.fling = s.fling();
		t.cooldownSeconds = s.cooldownSeconds();
		t.mobsReact = s.mobsReact();
		t.impactDamage = s.impactDamage();
		t.impactDamageMultiplier = s.impactDamageMultiplier();
		t.grab = s.grab();
		t.manualKey = s.manualKey();
		t.clamp();
		BubbleServer.saveConfig();
		apply();
		if (server != null) {
			server.getPlayerList().getPlayers().stream().filter(q -> q.hasPermissions(2)).forEach(RagdollTuning::send);
		}
	}

	/** For the chat when Fragile mode starts/ends. */
	static void announce(boolean on) {
		if (server != null) {
			server.getPlayerList().broadcastSystemMessage(Component.literal(on
					? "🦴 Fragile mode: the smallest bump turns you into a ragdoll!"
					: "🦴 Fragile mode is over.").withStyle(on ? ChatFormatting.GOLD : ChatFormatting.GRAY), false);
		}
	}
}
