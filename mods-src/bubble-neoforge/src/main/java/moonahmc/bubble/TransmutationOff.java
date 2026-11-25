package moonahmc.bubble;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ProjectE without transmutation. Turning EMC back into items (Transmutation Table / Tablet, Energy Condensers,
 * Mercurial Eye, Gem of Eternal Density, Void Ring) lets you buy something in the shop, turn it into EMC, make cheaper-per-EMC items from it and sell
 * those for more than you paid, over and over. So those can't be crafted, placed or used. Alchemy, Dark and Red Matter
 * gear, rings and amulets all still work.
 * <p>
 * The Philosopher's Stone's swaps (1 gold = 8 iron, diamond = 2 emeralds...) are fixed ratios too: any of them whose
 * result sells for clearly more than what goes in is removed; the other way round stays (it only loses Strange Matter).
 */
final class TransmutationOff {
	static final Set<String> BANNED = Set.of("projecte:transmutation_table", "projecte:transmutation_tablet",
			"projecte:condenser_mk1", "projecte:condenser_mk2", "projecte:mercurial_eye",
			// these turn items into other items by EMC too (cobblestone into diamonds)
			"projecte:gem_density", "projecte:void_ring");
	/** A swap may make up to this much more than its ingredients (crafting bonus) before it counts as free money. */
	private static final double MARGIN = 1.1;

	private TransmutationOff() {
	}

	static boolean active() {
		return net.neoforged.fml.ModList.get().isLoaded("projecte");
	}

	static boolean banned(ItemStack s) {
		return !s.isEmpty() && BANNED.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
	}

	static void init() {
		if (!active()) {
			return;
		}
		var bus = NeoForge.EVENT_BUS;
		// /reload brings the recipes back: take them out again
		bus.addListener((OnDatapackSyncEvent e) -> {
			if (e.getPlayer() == null) {
				strip(e.getPlayerList().getServer());
			}
		});
		// if one turns up anyway (an old world, creative): it does nothing
		bus.addListener((BlockEvent.EntityPlaceEvent e) -> {
			ResourceLocation id = BuiltInRegistries.BLOCK.getKey(e.getPlacedBlock().getBlock());
			if (BANNED.contains(id.toString())) {
				e.setCanceled(true);
				if (e.getEntity() instanceof net.minecraft.world.entity.player.Player p) {
					tell(p);
				}
			}
		});
		bus.addListener((PlayerInteractEvent.RightClickItem e) -> {
			if (banned(e.getItemStack())) {
				e.setCanceled(true);
				if (!e.getLevel().isClientSide) {
					tell(e.getEntity());
				}
			}
		});
		bus.addListener((PlayerInteractEvent.RightClickBlock e) -> {
			ResourceLocation id = BuiltInRegistries.BLOCK.getKey(e.getLevel().getBlockState(e.getPos()).getBlock());
			if (BANNED.contains(id.toString())) {
				e.setCanceled(true);
				if (!e.getLevel().isClientSide) {
					tell(e.getEntity());
				}
			}
		});
	}

	private static void tell(net.minecraft.world.entity.player.Player p) {
		p.displayClientMessage(Component.literal("Transmutation is turned off on this server (it breaks Strange Matter)")
				.withStyle(ChatFormatting.RED), true);
	}

	/**
	 * Takes the transmutation recipes and the money-making Philosopher's Stone swaps out of the server's recipes.
	 * Returns how many went (prices should be worked out again if any did).
	 */
	static int strip(MinecraftServer s) {
		if (s == null || !active()) {
			return 0;
		}
		Item stone = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("projecte:philosophers_stone")).orElse(null);
		ItemStack stoneStack = stone == null ? ItemStack.EMPTY : new ItemStack(stone);
		List<RecipeHolder<?>> kept = new ArrayList<>();
		List<String> gone = new ArrayList<>();
		for (RecipeHolder<?> h : s.getRecipeManager().getRecipes()) {
			try {
				Recipe<?> r = h.value();
				ItemStack out = r.getResultItem(s.registryAccess());
				if (banned(out)) {
					gone.add(h.id().toString());
					continue;
				}
				if (!stoneStack.isEmpty() && !out.isEmpty() && swapMakesMoney(r, out, stoneStack)) {
					gone.add(h.id().toString());
					continue;
				}
			} catch (Throwable t) {
				// a recipe we can't read: leave it
			}
			kept.add(h);
		}
		if (!gone.isEmpty()) {
			s.getRecipeManager().replaceRecipes(kept);
			Bubble.LOG.info("ProjectE: transmutation off; removed {} recipes: {}", gone.size(), String.join(", ", gone));
		}
		return gone.size();
	}

	private static boolean swapMakesMoney(Recipe<?> r, ItemStack out, ItemStack stone) {
		boolean usesStone = false;
		double in = 0;
		for (Ingredient i : r.getIngredients()) {
			if (i.isEmpty()) {
				continue;
			}
			if (i.test(stone)) {
				usesStone = true;
				continue;
			}
			double best = Double.MAX_VALUE;
			for (ItemStack option : i.getItems()) {
				best = Math.min(best, Matter.baseValue(option.getItem()));
			}
			in += best == Double.MAX_VALUE ? 0 : best;
		}
		if (!usesStone) {
			return false;
		}
		double made = Matter.baseValue(out.getItem()) * out.getCount();
		return made > in * MARGIN + 0.01;
	}
}
