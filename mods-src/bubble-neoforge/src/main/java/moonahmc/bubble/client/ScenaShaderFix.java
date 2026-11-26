package moonahmc.bubble.client;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fix for Chisels & Bits crashing the game with shaders on (C&B issues #1381 / #1385: "ChunkBuildBuffers cannot be
 * cast to BlockSensitiveBufferBuilder"). C&B's rendering library Scena still tells Iris which block it's drawing the
 * old way, through Sodium's chunk buffers; current Iris takes it on Sodium's block renderer instead
 * (VertexEncoderInterface). This rewrites just those two calls in Scena's hook to the new way, the same change as the
 * MIT-licensed chisels-bits-shader-compat patch. If the hook isn't there or doesn't look exactly as expected (a fixed
 * C&B, another Iris), nothing is touched. Game client only (bubble-scena.mixins.json).
 */
public final class ScenaShaderFix implements IMixinConfigPlugin {
	private static final Logger LOG = LoggerFactory.getLogger("Bubble");
	private static final String OLD = "net/irisshaders/iris/vertices/BlockSensitiveBufferBuilder";
	private static final String NEW = "net/irisshaders/iris/vertices/sodium/terrain/VertexEncoderInterface";
	private static final String ACCESSOR = "com/communi/suggestu/scena/forge/compat/shaders/sodium/BuffersAccessor";

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		try {
			String result = patch(targetClass);
			if (result != null) {
				LOG.info("Chisels & Bits shader fix: {}", result);
			}
		} catch (Throwable t) {
			LOG.warn("Chisels & Bits shader fix: left alone ({})", t.toString());
		}
	}

	private static boolean hasNewIris() {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		return cl != null && cl.getResource(NEW + ".class") != null;
	}

	/** What happened, or null when there was nothing to do (no Scena hook). */
	static String patch(ClassNode target) {
		List<MethodNode> hooks = new ArrayList<>();
		for (MethodNode m : target.methods) {
			if (m.name.startsWith("handler$") && m.name.endsWith("$scena$beforeEmitVanillaQuad")) {
				hooks.add(m);
			}
		}
		if (hooks.isEmpty()) {
			return null;
		}
		if (hooks.size() != 1) {
			return "left alone (" + hooks.size() + " Scena hooks)";
		}
		MethodNode m = hooks.get(0);
		// receiver chains: CHECKCAST BuffersAccessor; INVOKEINTERFACE getBuffers; CHECKCAST BlockSensitiveBufferBuilder
		List<AbstractInsnNode[]> chains = new ArrayList<>();
		List<MethodInsnNode> calls = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(ACCESSOR)) {
				AbstractInsnNode get = next(cast);
				AbstractInsnNode old = get == null ? null : next(get);
				if (get instanceof MethodInsnNode g && g.owner.equals(ACCESSOR) && g.name.equals("getBuffers")
						&& old instanceof TypeInsnNode o && o.getOpcode() == Opcodes.CHECKCAST && o.desc.equals(OLD)) {
					chains.add(new AbstractInsnNode[] {cast, get, old});
				}
			}
			if (insn instanceof MethodInsnNode call && call.owner.equals(OLD)) {
				calls.add(call);
			}
		}
		if (chains.isEmpty() && calls.isEmpty()) {
			return "not needed (Scena already uses the new Iris way)";
		}
		MethodInsnNode end = null;
		MethodInsnNode begin = null;
		for (MethodInsnNode c : calls) {
			if (c.name.equals("endBlock") && c.desc.equals("()V")) {
				end = c;
			} else if (c.name.equals("beginBlock") && c.desc.equals("(IBBIII)V")) {
				begin = c;
			}
		}
		if (chains.size() != 2 || calls.size() != 2 || end == null || begin == null) {
			return "left alone (Scena's hook isn't the known broken one)";
		}
		if (!hasNewIris()) {
			return "left alone (this Iris doesn't have VertexEncoderInterface)";
		}
		for (AbstractInsnNode[] ch : chains) {
			((TypeInsnNode) ch[0]).desc = NEW; // the block renderer itself is the encoder now
			m.instructions.remove(ch[1]);
			m.instructions.remove(ch[2]);
		}
		// restoreBlock also clears any per-model override Iris set, so Scena's block id is the one used
		m.instructions.set(end, new MethodInsnNode(Opcodes.INVOKEINTERFACE, NEW, "restoreBlock", "()V", true));
		m.instructions.set(begin, new MethodInsnNode(Opcodes.INVOKEINTERFACE, NEW, "beginBlock", "(IBBIII)V", true));
		return "patched Scena's shader hook (chiseled blocks with shaders no longer crash)";
	}

	private static AbstractInsnNode next(AbstractInsnNode n) {
		AbstractInsnNode x = n.getNext();
		while (x != null && x.getOpcode() < 0) {
			x = x.getNext(); // labels, line numbers, frames
		}
		return x;
	}
}
