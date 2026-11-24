package moonahmc.bubble;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ammo and attachments that fit the guns you're carrying (TACZ). Only touched when TACZ is installed (check
 * {@link Guns#available()} first), since this class uses TACZ's API directly.
 */
final class GunSupply {
	private GunSupply() {
	}

	/** No shooting or gun-bashing while you're down (both sides, so the gun doesn't even go off on your screen). */
	static void init() {
		var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
		bus.addListener((com.tacz.guns.api.event.common.GunShootEvent e) -> {
			if (e.getShooter() instanceof net.minecraft.world.entity.player.Player p && Revive.isDown(p)) {
				e.setCanceled(true);
			}
		});
		bus.addListener((com.tacz.guns.api.event.common.GunMeleeEvent e) -> {
			if (e.getShooter() instanceof net.minecraft.world.entity.player.Player p && Revive.isDown(p)) {
				e.setCanceled(true);
			}
		});
	}

	/** Every gun in the player's inventory (main inventory, hotbar and offhand), held one first. */
	static List<ItemStack> guns(ServerPlayer p) {
		List<ItemStack> out = new ArrayList<>();
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack s = p.getItemInHand(hand);
			if (IGun.getIGunOrNull(s) != null) {
				out.add(s);
			}
		}
		for (ItemStack s : p.getInventory().items) {
			if (IGun.getIGunOrNull(s) != null && out.stream().noneMatch(o -> o == s)) {
				out.add(s);
			}
		}
		return out;
	}

	static boolean armed(ServerPlayer p) {
		return !guns(p).isEmpty();
	}

	/** The gun this is about: the one in your hand, or else the first one in your inventory. */
	static ItemStack mainGun(ServerPlayer p) {
		List<ItemStack> g = guns(p);
		return g.isEmpty() ? ItemStack.EMPTY : g.get(0);
	}

	private static Optional<CommonGunIndex> index(ItemStack gun) {
		IGun ig = IGun.getIGunOrNull(gun);
		return ig == null ? Optional.empty() : TimelessAPI.getCommonGunIndex(ig.getGunId(gun));
	}

	static Component gunName(ItemStack gun) {
		return index(gun).map(i -> (Component) Component.translatable(i.getPojo().getName())).orElse(gun.getHoverName());
	}

	/**
	 * `mags` magazines of ammo for every gun you're carrying (one lot per kind of ammo, sized by the biggest magazine
	 * that uses it), split into stacks. Empty if you have no guns.
	 */
	static List<ItemStack> ammoFor(ServerPlayer p, int mags) {
		Map<ResourceLocation, Integer> want = new LinkedHashMap<>();
		for (ItemStack gun : guns(p)) {
			index(gun).ifPresent(i -> {
				ResourceLocation ammo = i.getGunData().getAmmoId();
				if (ammo == null || TimelessAPI.getCommonAmmoIndex(ammo).isEmpty()) {
					return;
				}
				int mag = Math.max(1, i.getGunData().getAmmoAmount());
				// at least 10 rounds (4 for one-shot launchers), at most 3 stacks' worth
				int count = Math.min(180, Math.max(mag * mags, Math.min(10, mag * 4)));
				want.merge(ammo, count, Math::max);
			});
		}
		List<ItemStack> out = new ArrayList<>();
		want.forEach((ammo, count) -> {
			int left = count;
			while (left > 0) {
				ItemStack s = AmmoItemBuilder.create().setId(ammo).setCount(1).build();
				int n = Math.min(left, Math.max(1, s.getMaxStackSize()));
				s.setCount(n);
				out.add(s);
				left -= n;
			}
		});
		return out;
	}

	/** Gives the stacks (dropping what doesn't fit) and says what you got. */
	static void give(ServerPlayer p, List<ItemStack> stacks, String why) {
		Map<String, Integer> said = new LinkedHashMap<>();
		Map<String, Component> names = new LinkedHashMap<>();
		for (ItemStack s : stacks) {
			Component name = name(s);
			said.merge(name.getString(), s.getCount(), Integer::sum);
			names.putIfAbsent(name.getString(), name);
			if (!p.getInventory().add(s)) {
				p.drop(s, false);
			}
		}
		p.containerMenu.broadcastChanges();
		var msg = Component.literal(why).withStyle(net.minecraft.ChatFormatting.GOLD);
		boolean first = true;
		for (var e : said.entrySet()) {
			msg.append(Component.literal(first ? " " : ", ").withStyle(net.minecraft.ChatFormatting.GRAY))
					.append(Component.literal(e.getValue() + "× ").withStyle(net.minecraft.ChatFormatting.WHITE))
					.append(names.get(e.getKey()).copy().withStyle(net.minecraft.ChatFormatting.YELLOW));
			first = false;
		}
		p.sendSystemMessage(msg);
	}

	/** A TACZ ammo or attachment's real name (the item's own name only works on the client). */
	static Component name(ItemStack s) {
		IAmmo ammo = IAmmo.getIAmmoOrNull(s);
		if (ammo != null) {
			var i = TimelessAPI.getCommonAmmoIndex(ammo.getAmmoId(s));
			if (i.isPresent()) {
				return Component.translatable(i.get().getPojo().getName());
			}
		}
		IAttachment att = IAttachment.getIAttachmentOrNull(s);
		if (att != null) {
			var i = TimelessAPI.getCommonAttachmentIndex(att.getAttachmentId(s));
			if (i.isPresent()) {
				return Component.translatable(i.get().getPojo().getName());
			}
		}
		if (IGun.getIGunOrNull(s) != null) {
			return gunName(s);
		}
		return s.getHoverName();
	}

	// ------------------------------------------------------------------ the Attachment Case

	/** Left out: the factory parts guns come with, and high-explosive rounds (they blow up the landscape). */
	private static boolean skipped(String path, CommonAttachmentIndex i) {
		return path.startsWith("oem_") || path.equals("ammo_mod_he") || i.getPojo().isHidden() || i.getPojo().isBuiltinAttachment()
				|| i.getType() == AttachmentType.NONE;
	}

	/** How rare an attachment is in the case (0 = common ... 4 = legendary), from what it is. */
	static int tier(String path) {
		if (path.endsWith("extended_mag_3") || path.startsWith("ammo_mod_")) {
			return 4;
		}
		if (path.endsWith("extended_mag_2") || path.startsWith("muzzle_silencer_") || path.contains("_ir") || path.startsWith("scope_xl50")) {
			return 3;
		}
		if (path.endsWith("extended_mag_1") || path.startsWith("scope_")) {
			return 2;
		}
		if (path.startsWith("sight_") || path.startsWith("stock_") || path.startsWith("muzzle_")) {
			return 1;
		}
		return 0; // grips, lasers, bayonets...
	}

	/**
	 * The case for this gun: every attachment that fits it, by rarity, without the ones already on it. Empty tiers
	 * are fine (they can't be rolled).
	 */
	static List<List<ItemStack>> attachmentsFor(ItemStack gun) {
		List<List<ItemStack>> tiers = new ArrayList<>();
		for (int t = 0; t < 5; t++) {
			tiers.add(new ArrayList<>());
		}
		IGun ig = IGun.getIGunOrNull(gun);
		if (ig == null) {
			return tiers;
		}
		for (var e : TimelessAPI.getAllCommonAttachmentIndex()) {
			ResourceLocation id = e.getKey();
			CommonAttachmentIndex i = e.getValue();
			if (skipped(id.getPath(), i)) {
				continue;
			}
			ItemStack att = AttachmentItemBuilder.create().setId(id).setCount(1).build();
			if (att.isEmpty() || !ig.allowAttachment(gun, att) || id.equals(ig.getAttachmentId(gun, i.getType()))) {
				continue;
			}
			tiers.get(tier(id.getPath())).add(att);
		}
		return tiers;
	}
}
