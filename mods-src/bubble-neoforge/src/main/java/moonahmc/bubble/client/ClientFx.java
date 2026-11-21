package moonahmc.bubble.client;

import moonahmc.bubble.Fx;

/** Client side of the mid-run wheel's timed effects: the upside-down camera (eases over and back) and ice ground. */
public final class ClientFx {
	private static final long FLIP_MS = 700;
	private static long upsideStart;
	private static long upsideUntil;

	private ClientFx() {
	}

	static void start(Fx.Start s) {
		long now = System.currentTimeMillis();
		long until = now + s.ticks() * 50L;
		switch (s.kind()) {
			case "upside_down" -> {
				if (now >= upsideUntil) {
					upsideStart = now;
				}
				upsideUntil = s.ticks() <= 0 ? 0 : until;
			}
			case "ice" -> Fx.iceUntil = s.ticks() <= 0 ? 0 : until;
			default -> {
			}
		}
	}

	static void stop() {
		upsideUntil = 0;
		Fx.iceUntil = 0;
	}

	/** The view is (more than half way) upside down: the mouse and strafing get flipped so they feel normal. */
	public static boolean flipped() {
		return roll() > 90;
	}

	/** Extra camera roll in degrees: 0, easing to 180 over the first 0.7 s and back over the last 0.7 s. */
	static float roll() {
		long now = System.currentTimeMillis();
		if (now >= upsideUntil) {
			return 0;
		}
		double in = Math.min(1, (now - upsideStart) / (double) FLIP_MS);
		double out = Math.min(1, (upsideUntil - now) / (double) FLIP_MS);
		double t = Math.min(in, out);
		double eased = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
		return (float) (180 * eased);
	}
}
