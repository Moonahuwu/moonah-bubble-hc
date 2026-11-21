package moonahmc.bubble.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** Tiny helper so static widget classes can reach the font renderer. */
final class MinecraftClientHolder {
	private MinecraftClientHolder() {
	}

	static Font text() {
		return Minecraft.getInstance().font;
	}
}
