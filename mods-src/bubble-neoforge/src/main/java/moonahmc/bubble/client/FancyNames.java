package moonahmc.bubble.client;

import moonahmc.bubble.Eidolons;
import moonahmc.bubble.NameStyles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws Eidolon name colours on this client: above heads (NameFormat), in chat (any message that names a player) and
 * in the tab list (TabNameMixin). The server sends names in their main colour; this swaps in the real style.
 */
public final class FancyNames {
	private static boolean prism;

	private FancyNames() {
	}

	static void init() {
		prism = ModList.get().isLoaded("prism");
		NameStyles.fancy = FancyNames::build;
	}

	/** `name` drawn in style `s`, one colour per letter where it changes along the name. */
	static MutableComponent build(String name, NameStyles.Spec s) {
		if (s.mode() == NameStyles.SOLID || name.length() < 2 && s.mode() == NameStyles.GRADIENT) {
			return Component.literal(name).withStyle(st -> st.withColor(s.main()));
		}
		if (prism && (s.mode() == NameStyles.ANIMATED || s.mode() == NameStyles.GLOW)) {
			try {
				return PrismNames.letters(name, s);
			} catch (Throwable t) {
				prism = false; // a Prism we don't understand: still names, just not moving
			}
		}
		if (s.mode() == NameStyles.GLOW) {
			return Component.literal(name).withStyle(st -> st.withColor(s.main()));
		}
		MutableComponent out = Component.empty();
		int n = name.length();
		for (int i = 0; i < n; ) {
			int cp = name.codePointAt(i);
			int rgb = NameStyles.along(s.colors(), n <= 1 ? 0 : i / (float) (n - 1));
			out.append(Component.literal(new String(Character.toChars(cp))).withStyle(st -> st.withColor(rgb)));
			i += Character.charCount(cp);
		}
		return out;
	}

	/** Everyone online with a name colour, by the name they show as. */
	private static Map<String, NameStyles.Spec> styled() {
		Map<String, NameStyles.Spec> out = new HashMap<>();
		if (Eidolons.NAME_STYLES.isEmpty()) {
			return out;
		}
		ClientPacketListener conn = Minecraft.getInstance().getConnection();
		if (conn == null) {
			return out;
		}
		for (Map.Entry<UUID, String> e : Eidolons.NAME_STYLES.entrySet()) {
			PlayerInfo info = conn.getPlayerInfo(e.getKey());
			NameStyles.Spec s = NameStyles.spec(e.getValue());
			if (info != null && s != null) {
				out.put(info.getProfile().getName(), s);
			}
		}
		return out;
	}

	/** Chat and the tab list: any bit of text that is exactly a styled player's name gets their style. */
	public static Component restyle(Component c) {
		if (c == null) {
			return null;
		}
		return walk(c, styled());
	}

	/**
	 * Also: a case unbox broadcast marks the prize with "[Epic] ..." / "[Legendary] ..." (its shift-click text), an
	 * elite's name carries "bubble:elite", and Factory New is its own label; those get their Rarity style.
	 */
	private static Component walk(Component c, Map<String, NameStyles.Spec> by) {
		ComponentContents con = c.getContents();
		String ins = c.getStyle().getInsertion();
		if (ins != null) {
			if (ins.startsWith("[Legendary] ") || ins.startsWith("[Epic] ")) {
				return Component.empty().setStyle(c.getStyle().withColor((TextColor) null))
						.append(Rarity.tierName(c.getString(), ins.startsWith("[Legendary] ") ? 4 : 3));
			}
			if (ins.equals("bubble:elite")) {
				TextColor own = c.getStyle().getColor();
				int base = own != null ? own.getValue() & 0xFFFFFF : 0xFF5555;
				return Component.literal(c.getString()).setStyle(c.getStyle().withColor(Rarity.elitePulse(base)));
			}
		}
		if (con instanceof PlainTextContents p && p.text().equals(Rarity.FACTORY_NEW_TEXT)) {
			return Component.empty().setStyle(c.getStyle().withColor((TextColor) null)).append(build(p.text(), Rarity.FACTORY_NEW));
		}
		MutableComponent out;
		if (con instanceof PlainTextContents p && by.containsKey(p.text())) {
			out = Component.empty().setStyle(c.getStyle().withColor((TextColor) null)).append(build(p.text(), by.get(p.text())));
		} else if (con instanceof TranslatableContents tc && tc.getArgs().length > 0) {
			Object[] args = tc.getArgs().clone();
			for (int i = 0; i < args.length; i++) {
				if (args[i] instanceof Component a) {
					args[i] = walk(a, by);
				}
			}
			out = MutableComponent.create(new TranslatableContents(tc.getKey(), tc.getFallback(), args)).setStyle(c.getStyle());
		} else {
			out = MutableComponent.create(con).setStyle(c.getStyle());
		}
		for (Component sib : c.getSiblings()) {
			out.append(walk(sib, by));
		}
		return out;
	}

	/** For the Eidolon screen's previews. */
	static Component preview(String name, String id) {
		NameStyles.Spec s = NameStyles.spec(id);
		return s == null ? Component.literal(name) : build(name, s);
	}
}
