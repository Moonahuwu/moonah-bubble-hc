package moonahmc.bubble;

import moonahmc.bubble.net.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Network messages for Strange Matter: the wallet, the sell slot and the shop. */
public final class ShopPayloads {
	private ShopPayloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
		return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Bubble.MOD_ID, path));
	}

	private static void writeStack(RegistryFriendlyByteBuf buf, ItemStack s) {
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
	}

	private static ItemStack readStack(RegistryFriendlyByteBuf buf) {
		return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
	}

	/** Server to client: how much Strange Matter you have. */
	public record Balance(double matter) implements CustomPacketPayload {
		public static final Type<Balance> ID = id("balance");
		public static final StreamCodec<RegistryFriendlyByteBuf, Balance> CODEC = StreamCodec.ofMember((b, buf) -> buf.writeDouble(b.matter),
				buf -> new Balance(buf.readDouble()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: send me the shop. */
	public record Request() implements CustomPacketPayload {
		public static final Type<Request> ID = id("shop_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** One thing for sale. sellValue is what the item would sell for, so the edit mode can warn about cheap prices. */
	/** group: the filter chip it sits under in a merged tab (the tab it used to be in). */
	public record Offer(ItemStack stack, int price, double sellValue, String group) {
	}

	/** A shop tab. charging = the special "charge your Oritech gear" tab. */
	/** gamble = a tab of cases: shown under Gamble instead of Shop. video = the "paste a link, show everyone" tab. */
	/** alwaysOpen: buyable between shop windows too (ShopWindows). */
	public record Section(String name, ItemStack icon, boolean charging, List<Offer> offers, boolean gamble, boolean video, boolean alwaysOpen) {
	}

	/** Server to client: the whole catalogue (sent when the shop opens, and again whenever an op edits it). */
	/**
	 * window: -1 = the shop is open (no shop windows right now), 0 = closed until the next window, otherwise ticks left in
	 * this one. windowInfo: what opens it.
	 */
	public record Catalog(boolean canEdit, int chargeRate, int emcRate, List<Section> sections, boolean shopClosed, int window,
			String windowInfo) implements CustomPacketPayload {
		public static final Type<Catalog> ID = id("shop_catalog");
		public static final StreamCodec<RegistryFriendlyByteBuf, Catalog> CODEC = StreamCodec.ofMember((c, buf) -> {
			buf.writeBoolean(c.canEdit);
			buf.writeVarInt(c.chargeRate);
			buf.writeVarInt(c.emcRate);
			buf.writeVarInt(c.sections.size());
			for (Section s : c.sections) {
				buf.writeUtf(s.name);
				writeStack(buf, s.icon);
				buf.writeBoolean(s.charging);
				buf.writeVarInt(s.offers.size());
				for (Offer o : s.offers) {
					writeStack(buf, o.stack);
					buf.writeVarInt(o.price);
					buf.writeDouble(o.sellValue);
					buf.writeUtf(o.group == null ? "" : o.group, 64);
				}
				buf.writeBoolean(s.gamble);
				buf.writeBoolean(s.video);
				buf.writeBoolean(s.alwaysOpen);
			}
			buf.writeBoolean(c.shopClosed);
			buf.writeVarInt(c.window + 1);
			buf.writeUtf(c.windowInfo, 512);
		}, buf -> {
			boolean canEdit = buf.readBoolean();
			int rate = buf.readVarInt();
			int emcRate = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 64);
			List<Section> sections = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				String name = buf.readUtf();
				ItemStack icon = readStack(buf);
				boolean charging = buf.readBoolean();
				int m = Math.min(buf.readVarInt(), 512);
				List<Offer> offers = new ArrayList<>(m);
				for (int j = 0; j < m; j++) {
					offers.add(new Offer(readStack(buf), buf.readVarInt(), buf.readDouble(), buf.readUtf(64)));
				}
				boolean gamble = buf.readBoolean();
				boolean video = buf.readBoolean();
				sections.add(new Section(name, icon, charging, offers, gamble, video, buf.readBoolean()));
			}
			boolean shut = buf.readBoolean();
			int window = buf.readVarInt() - 1;
			return new Catalog(canEdit, rate, emcRate, sections, shut, window, buf.readUtf(512));
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: buy `times` lots of an offer. */
	public record Buy(int section, int index, int times) implements CustomPacketPayload {
		public static final Type<Buy> ID = id("shop_buy");
		public static final StreamCodec<RegistryFriendlyByteBuf, Buy> CODEC = StreamCodec.ofMember((b, buf) -> {
			buf.writeVarInt(b.section);
			buf.writeVarInt(b.index);
			buf.writeVarInt(b.times);
		}, buf -> new Buy(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: sell what's on my cursor (all = also every matching item in my inventory). */
	public record Sell(boolean all) implements CustomPacketPayload {
		public static final Type<Sell> ID = id("sell");
		public static final StreamCodec<RegistryFriendlyByteBuf, Sell> CODEC = StreamCodec.ofMember((s, buf) -> buf.writeBoolean(s.all),
				buf -> new Sell(buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: Ctrl+click sold the item in this slot of my open screen. */
	public record QuickSell(int slot) implements CustomPacketPayload {
		public static final Type<QuickSell> ID = id("quick_sell");
		public static final StreamCodec<RegistryFriendlyByteBuf, QuickSell> CODEC = StreamCodec.ofMember((q, buf) -> buf.writeVarInt(q.slot),
				buf -> new QuickSell(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: undo my last sale (buy it back for what I got). */
	public record BuyBack() implements CustomPacketPayload {
		public static final Type<BuyBack> ID = id("buy_back");
		public static final StreamCodec<RegistryFriendlyByteBuf, BuyBack> CODEC = StreamCodec.unit(new BuyBack());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: what is this worth? */
	public record Quote(ItemStack stack) implements CustomPacketPayload {
		public static final Type<Quote> ID = id("quote");
		public static final StreamCodec<RegistryFriendlyByteBuf, Quote> CODEC = StreamCodec.ofMember((q, buf) -> writeStack(buf, q.stack),
				buf -> new Quote(readStack(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: the answer, plus whether a buy-back is available. */
	public record QuoteResult(ItemStack stack, double total, ItemStack lastSold, double lastSoldFor) implements CustomPacketPayload {
		public static final Type<QuoteResult> ID = id("quote_result");
		public static final StreamCodec<RegistryFriendlyByteBuf, QuoteResult> CODEC = StreamCodec.ofMember((q, buf) -> {
			writeStack(buf, q.stack);
			buf.writeDouble(q.total);
			writeStack(buf, q.lastSold);
			buf.writeDouble(q.lastSoldFor);
		}, buf -> new QuoteResult(readStack(buf), buf.readDouble(), readStack(buf), buf.readDouble()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: what do these sell for (for your own tooltips; Eidolon sell bonus included)? */
	public record Worth(List<ItemStack> stacks) implements CustomPacketPayload {
		public static final Type<Worth> ID = id("worth");
		public static final StreamCodec<RegistryFriendlyByteBuf, Worth> CODEC = StreamCodec.ofMember((w, buf) -> {
			buf.writeVarInt(w.stacks.size());
			w.stacks.forEach(s -> writeStack(buf, s));
		}, buf -> {
			int n = Math.min(buf.readVarInt(), 64);
			List<ItemStack> list = new java.util.ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(readStack(buf));
			}
			return new Worth(list);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: each stack with what the whole stack sells for. */
	public record WorthResult(List<ItemStack> stacks, List<Double> values) implements CustomPacketPayload {
		public static final Type<WorthResult> ID = id("worth_result");
		public static final StreamCodec<RegistryFriendlyByteBuf, WorthResult> CODEC = StreamCodec.ofMember((w, buf) -> {
			buf.writeVarInt(w.stacks.size());
			for (int i = 0; i < w.stacks.size(); i++) {
				writeStack(buf, w.stacks.get(i));
				buf.writeDouble(w.values.get(i));
			}
		}, buf -> {
			int n = Math.min(buf.readVarInt(), 64);
			List<ItemStack> stacks = new java.util.ArrayList<>(n);
			List<Double> values = new java.util.ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				stacks.add(readStack(buf));
				values.add(buf.readDouble());
			}
			return new WorthResult(stacks, values);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** An item in your inventory that holds energy. */
	public record Chargeable(int slot, ItemStack stack, long amount, long capacity, int cost, String unit) {
	}

	/** Client to server: which of my items can be charged? */
	public record ChargeQuery() implements CustomPacketPayload {
		public static final Type<ChargeQuery> ID = id("charge_query");
		public static final StreamCodec<RegistryFriendlyByteBuf, ChargeQuery> CODEC = StreamCodec.unit(new ChargeQuery());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Server to client: your chargeable items. available = false when no energy mod is installed. */
	public record ChargeList(boolean available, List<Chargeable> items) implements CustomPacketPayload {
		public static final Type<ChargeList> ID = id("charge_list");
		public static final StreamCodec<RegistryFriendlyByteBuf, ChargeList> CODEC = StreamCodec.ofMember((c, buf) -> {
			buf.writeBoolean(c.available);
			buf.writeVarInt(c.items.size());
			for (Chargeable ch : c.items) {
				buf.writeVarInt(ch.slot);
				writeStack(buf, ch.stack);
				buf.writeVarLong(ch.amount);
				buf.writeVarLong(ch.capacity);
				buf.writeVarInt(ch.cost);
				buf.writeUtf(ch.unit);
			}
		}, buf -> {
			boolean available = buf.readBoolean();
			int n = Math.min(buf.readVarInt(), 64);
			List<Chargeable> items = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				items.add(new Chargeable(buf.readVarInt(), readStack(buf), buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readUtf()));
			}
			return new ChargeList(available, items);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	/** Client to server: fill this inventory slot with energy (-1 = everything). */
	public record Charge(int slot) implements CustomPacketPayload {
		public static final Type<Charge> ID = id("charge");
		public static final StreamCodec<RegistryFriendlyByteBuf, Charge> CODEC = StreamCodec.ofMember((c, buf) -> buf.writeVarInt(c.slot),
				buf -> new Charge(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	public static final int EDIT_SET = 0;
	public static final int EDIT_REMOVE = 1;
	public static final int EDIT_ADD_HELD = 2;
	public static final int EDIT_ADD_SECTION = 3;
	public static final int EDIT_REMOVE_SECTION = 4;
	public static final int EDIT_MOVE = 5;

	/** Client to server (ops only): change the shop. */
	public record Edit(int action, int section, int index, int price, int count, String text) implements CustomPacketPayload {
		public static final Type<Edit> ID = id("shop_edit");
		public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.ofMember((e, buf) -> {
			buf.writeVarInt(e.action);
			buf.writeVarInt(e.section);
			buf.writeVarInt(e.index);
			buf.writeVarInt(e.price);
			buf.writeVarInt(e.count);
			buf.writeUtf(e.text);
		}, buf -> new Edit(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return ID;
		}
	}

	static void register(PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c, PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s) {
		s2c.register(Balance.ID, Balance.CODEC);
		s2c.register(Catalog.ID, Catalog.CODEC);
		s2c.register(QuoteResult.ID, QuoteResult.CODEC);
		s2c.register(WorthResult.ID, WorthResult.CODEC);
		s2c.register(ChargeList.ID, ChargeList.CODEC);
		c2s.register(Request.ID, Request.CODEC);
		c2s.register(Buy.ID, Buy.CODEC);
		c2s.register(Sell.ID, Sell.CODEC);
		c2s.register(BuyBack.ID, BuyBack.CODEC);
		c2s.register(QuickSell.ID, QuickSell.CODEC);
		c2s.register(Quote.ID, Quote.CODEC);
		c2s.register(Worth.ID, Worth.CODEC);
		c2s.register(ChargeQuery.ID, ChargeQuery.CODEC);
		c2s.register(Charge.ID, Charge.CODEC);
		c2s.register(Edit.ID, Edit.CODEC);
	}
}
