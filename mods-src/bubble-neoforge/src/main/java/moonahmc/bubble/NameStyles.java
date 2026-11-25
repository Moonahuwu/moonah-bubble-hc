package moonahmc.bubble;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Eidolon name colours: your name in chat, the tab list and above your head. Solid colours and gradients look the same
 * for everyone; animated and glowing ones move on the client (with the Prism library, which the pack has; without it
 * they show as a still gradient). The server only ever sends the name in its main colour; each client styles it.
 */
public final class NameStyles {
	public static final int SOLID = 0;
	public static final int GRADIENT = 1;
	/** The colours flow along the name. */
	public static final int ANIMATED = 2;
	/** A bright shimmer sweeps across the name. */
	public static final int GLOW = 3;

	public record Spec(String id, int mode, int[] colors, float seconds) {
		public int main() {
			return colors[0];
		}
	}

	public static final Map<String, Spec> SPECS = new LinkedHashMap<>();

	private static void add(String id, int mode, float seconds, int... colors) {
		SPECS.put(id, new Spec(id, mode, colors, seconds));
	}

	static {
		add("name_gold", SOLID, 0, 0xFFAA00);
		add("name_aqua", SOLID, 0, 0x55FFFF);
		add("name_lime", SOLID, 0, 0x7CFF4F);
		add("name_pink", SOLID, 0, 0xFF5FC8);
		add("name_crimson", SOLID, 0, 0xE0303A);
		add("name_lavender", SOLID, 0, 0xB79CFF);
		add("name_sunset", GRADIENT, 0, 0xFF5E62, 0xFF9966, 0xFFC371);
		add("name_ocean", GRADIENT, 0, 0x2E8BFF, 0x4FF0D0);
		add("name_toxic", GRADIENT, 0, 0xB6FF3B, 0x2BD94F);
		add("name_candy", GRADIENT, 0, 0xFF77E9, 0x7AD7FF);
		add("name_rainbow", ANIMATED, 3f, 0xFF4040, 0xFF9A3C, 0xFFE24A, 0x6BE07A, 0x4FC3FF, 0x7A6BFF, 0xD36BFF);
		add("name_aurora", ANIMATED, 4f, 0x3CF0C0, 0x5FA8FF, 0xB76BFF);
		add("name_inferno", ANIMATED, 1.6f, 0xFF3A1A, 0xFFA51A, 0xFFE24A);
		add("name_glow_gold", GLOW, 2.2f, 0xFFC93C);
		add("name_glow_ghost", GLOW, 2.2f, 0x8FF3FF);
		add("name_glow_void", GLOW, 2.2f, 0xB266FF);
	}

	/**
	 * Client only (set by the client): builds the fancy version of a name. Null on the server, where names are sent in
	 * their main colour.
	 */
	public static BiFunction<String, Spec, MutableComponent> fancy;

	private NameStyles() {
	}

	public static Spec spec(String id) {
		return id == null ? null : SPECS.get(id);
	}

	/** `name` in style `id`: fancy on the client, the main colour on the server. */
	public static MutableComponent style(String name, String id, Style base) {
		Spec s = spec(id);
		if (s == null) {
			return Component.literal(name).setStyle(base);
		}
		if (fancy != null) {
			return Component.empty().setStyle(base).append(fancy.apply(name, s));
		}
		return Component.literal(name).setStyle(base.withColor(s.main()));
	}

	/** Colour `t` (0..1) of the way along a list of colours. */
	public static int along(int[] colors, float t) {
		if (colors.length == 1) {
			return colors[0];
		}
		float f = Math.max(0, Math.min(1, t)) * (colors.length - 1);
		int i = Math.min(colors.length - 2, (int) f);
		return mix(colors[i], colors[i + 1], f - i);
	}

	public static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
		int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
		int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
		return (r << 16) | (g << 8) | bl;
	}
}
