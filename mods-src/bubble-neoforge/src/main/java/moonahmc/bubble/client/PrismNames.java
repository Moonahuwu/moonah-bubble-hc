package moonahmc.bubble.client;

import com.anthonyhilyard.prism.text.DynamicColor;
import com.anthonyhilyard.prism.util.IColor;
import moonahmc.bubble.NameStyles;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Animated and glowing names, using the Prism library's DynamicColor (a text colour that moves through a list of
 * colours, blending as it goes, wherever the text is drawn). Only loaded when Prism is installed.
 * <p>
 * Each letter gets the same loop of colours, one step further along than the letter before, so the colours flow
 * along the name. The colours for a style are made once and shared by every name and message (Prism keeps each one
 * ticking for good), and all at the same moment so they stay in step.
 */
final class PrismNames {
	/** Prism's timer counts render ticks / 50, so a second is 20 / 50 of its units. */
	private static final float UNITS_PER_SECOND = 0.4f;
	private static final Map<String, DynamicColor[]> phases = new HashMap<>();

	private PrismNames() {
	}

	static MutableComponent letters(String name, NameStyles.Spec s) {
		DynamicColor[] ph = phases.computeIfAbsent(s.id(), k -> make(s));
		MutableComponent out = Component.empty();
		int letter = 0;
		for (int i = 0; i < name.length(); ) {
			int cp = name.codePointAt(i);
			DynamicColor c = ph[letter % ph.length];
			out.append(Component.literal(new String(Character.toChars(cp))).withStyle(st -> st.withColor(c)));
			i += Character.charCount(cp);
			letter++;
		}
		return out;
	}

	/** The loop of colours a letter goes round. */
	private static int[] loop(NameStyles.Spec s) {
		if (s.mode() == NameStyles.GLOW) {
			// mostly the colour, with a bright flash that sweeps along the name, then a pause
			int base = s.main();
			int bright = NameStyles.mix(base, 0xFFFFFF, 0.75f);
			int half = NameStyles.mix(base, 0xFFFFFF, 0.35f);
			int[] out = new int[12];
			for (int i = 0; i < out.length; i++) {
				out[i] = base;
			}
			out[0] = bright;
			out[1] = half;
			out[out.length - 1] = half;
			return out;
		}
		// the colours round and back to the first, with a blend between each pair
		int[] cs = s.colors();
		int[] out = new int[cs.length * 2];
		for (int i = 0; i < cs.length; i++) {
			out[i * 2] = cs[i];
			out[i * 2 + 1] = NameStyles.mix(cs[i], cs[(i + 1) % cs.length], 0.5f);
		}
		return out;
	}

	private static DynamicColor[] make(NameStyles.Spec s) {
		int[] loop = loop(s);
		DynamicColor[] out = new DynamicColor[loop.length];
		for (int k = 0; k < loop.length; k++) {
			List<IColor> list = new ArrayList<>(loop.length);
			for (int j = 0; j < loop.length; j++) {
				list.add(DynamicColor.fromRgb(loop[(j + loop.length - k) % loop.length]));
			}
			out[k] = new DynamicColor(list, s.seconds() * UNITS_PER_SECOND);
		}
		return out;
	}
}
