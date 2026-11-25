package moonahmc.bubble;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Apotheosis elites and bosses get a hidden marker in their name (its "insertion"), which reaches every client with the
 * name. Clients use it to make the name pulse above their head (client/Rarity), so elites stand out from normal mobs.
 */
final class EliteNames {
	static final String MARK = "bubble:elite";

	private EliteNames() {
	}

	static void init() {
		NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
			if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Mob mob)) {
				return;
			}
			boolean elite = mob.getPersistentData().contains("apoth.miniboss") || mob.getPersistentData().contains("apoth.boss");
			Component name = mob.getCustomName();
			if (!elite || name == null || MARK.equals(name.getStyle().getInsertion())) {
				return;
			}
			mob.setCustomName(name.copy().withStyle(s -> s.withInsertion(MARK)));
		});
	}
}
