package moonahmc.bubble;

import net.minecraft.world.entity.monster.Creeper;

/** Sets a creeper's fuse length (Creeper.maxSwell is private; 30 ticks by default). */
final class CreeperFuse {
	private static java.lang.reflect.Field field;

	private CreeperFuse() {
	}

	static void set(Creeper creeper, int ticks) {
		try {
			if (field == null) {
				field = Creeper.class.getDeclaredField("maxSwell");
				field.setAccessible(true);
			}
			field.setInt(creeper, ticks);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Bubble.LOG.debug("Could not change a creeper's fuse", e);
		}
	}
}
