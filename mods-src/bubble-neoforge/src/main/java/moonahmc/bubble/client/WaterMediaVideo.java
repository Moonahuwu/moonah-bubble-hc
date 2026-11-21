package moonahmc.bubble.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;

/**
 * The only class that touches WaterMedia (loaded only when WaterMedia is installed): resolves the link, plays it with
 * Minecraft's GL thread and OpenAL, and hands VideoOverlay a GL texture to draw. Same calls WaterFrames' UI player uses.
 */
final class WaterMediaVideo implements VideoOverlay.Backend {
	private final MRL mrl;
	private MediaPlayer player;
	private long seekTo;
	private boolean failed;
	private int lastVolume = -1;
	private boolean lastMuted;

	WaterMediaVideo(String url, long elapsedMs) {
		this.mrl = PlayerAPI.mrl(url);
		this.seekTo = elapsedMs;
	}

	@Override
	public void tick(boolean muted, int pixelW, int pixelH) {
		if (failed) {
			return;
		}
		try {
			if (player == null) {
				if (mrl.status().failed()) {
					failed = true;
					return;
				}
				if (!mrl.status().loaded()) {
					return;
				}
				Minecraft mc = Minecraft.getInstance();
				Thread render = Thread.currentThread();
				player = PlayerAPI.player(mrl, 0, () -> PlayerAPI.glEngine(render, mc), PlayerAPI::alEngine);
				if (player == null) {
					failed = true;
					return;
				}
				if (pixelW > 0 && pixelH > 0) {
					player.scale(pixelW, pixelH);
				}
				player.start();
			}
			Minecraft mc = Minecraft.getInstance();
			int volume = Math.round(100 * mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.RECORDS));
			if (volume != lastVolume) {
				player.volume(volume);
				lastVolume = volume;
			}
			if (muted != lastMuted) {
				player.mute(muted);
				lastMuted = muted;
			}
			// Joined mid-video: jump to where everyone else is.
			if (seekTo > 3000 && player.playing() && player.canSeek()) {
				player.seek(seekTo);
				seekTo = 0;
			}
		} catch (Throwable t) {
			moonahmc.bubble.Bubble.LOG.warn("Video: WaterMedia failed", t);
			failed = true;
		}
	}

	@Override
	public boolean failed() {
		return failed || (player != null && player.error());
	}

	@Override
	public boolean ended() {
		return player != null && player.ended();
	}

	@Override
	public int texture() {
		if (player == null || !player.canPlay()) {
			return 0;
		}
		long t = player.texture();
		return t <= 0 ? 0 : (int) t;
	}

	@Override
	public int width() {
		return player == null ? 0 : player.width();
	}

	@Override
	public int height() {
		return player == null ? 0 : player.height();
	}

	@Override
	public void release() {
		try {
			if (player != null) {
				player.release();
			}
		} catch (Throwable t) {
			moonahmc.bubble.Bubble.LOG.warn("Video: couldn't release the player", t);
		}
		player = null;
	}
}
