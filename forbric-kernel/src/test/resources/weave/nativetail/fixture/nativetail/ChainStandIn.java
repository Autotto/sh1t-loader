package fixture.nativetail;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * NOT what a mod does: a stand-in for the one piece of KernelBoot the weave harness does not run.
 *
 * <p>MixinNativeTail only acts on a method VanillaEarlyReturns split, and that split happens in the pre-Mixin
 * transform chain KernelBoot installs on the loader. The harness installs no chain, so this installs the production
 * stage as the whole chain — with KernelBoot's own condition (registered only while it is enabled) — before Mixin
 * prepares the config and reads the mixin. Delete it once the harness can install a pre-Mixin chain itself.
 */
public final class ChainStandIn implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		try {
			ClassLoader loader = ChainStandIn.class.getClassLoader();
			Class<?> stage = Class.forName("net.forbric.kernel.transform.VanillaEarlyReturns", true, loader);
			if (!(Boolean) stage.getMethod("enabled").invoke(null)) {
				System.out.println("[NativeTail] chain stand-in: VanillaEarlyReturns is switched off, nothing installed");
				return;
			}
			Object instance = stage.getConstructor().newInstance();
			Class<?> context = Class.forName("net.forbric.kernel.transform.TransformContext", true, loader);
			Method transform = stage.getMethod("transform", String.class, byte[].class, context);
			BiFunction<String, byte[], byte[]> chain = (name, bytes) -> {
				try {
					return (byte[]) transform.invoke(instance, name, bytes, null);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			};
			loader.getClass().getMethod("setTransformer", BiFunction.class).invoke(loader, chain);
			System.out.println("[NativeTail] chain stand-in: VanillaEarlyReturns installed on " + loader.getClass().getSimpleName());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not stand in for KernelBoot's chain", e);
		}
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
	}
}
