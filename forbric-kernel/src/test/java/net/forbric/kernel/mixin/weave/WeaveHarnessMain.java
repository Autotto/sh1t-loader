package net.forbric.kernel.mixin.weave;

import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.mixin.KernelMixinBootstrap;
import net.forbric.kernel.mixin.MixinConfigOwners;

/**
 * The child half of {@link WeaveHarness}: one JVM, one real Mixin bootstrap.
 *
 * <p>Everything a production boot does to a guest mixin happens here, through the same entry point
 * ({@link KernelMixinBootstrap#init}): {@code ForbricMixinService} serves the fixture jar's bytes and runs its
 * pre-Mixin adapter chain, real sponge-mixin weaves, the post-Mixin stages run, and
 * {@code FinalMixinApplications} audits the woven class as {@link ForbricClassLoader} defines it. Then the probe
 * method is CALLED, so what the test asserts is what the woven code did, not what the mixin said it would do.
 *
 * <p>A child JVM because the bootstrap is one-shot per JVM and leaves Mixin's and the kernel's global state behind.
 *
 * <p>Arguments: fixtureJar mixinExtrasJar config modId ecosystem side probeClass probeMethod outDir.
 */
public final class WeaveHarnessMain {
	static final String DONE = "[WeaveHarness] probe returned";
	static final String THREW = "[WeaveHarness] probe threw ";

	private WeaveHarnessMain() {
	}

	public static void main(String[] args) throws Exception {
		Path fixture = Path.of(args[0]);
		String mixinExtras = args[1];
		String config = args[2];
		String modId = args[3];
		Ecosystem ecosystem = Ecosystem.valueOf(args[4]);
		EnvType side = EnvType.valueOf(args[5]);
		String probeClass = args[6];
		String probeMethod = args[7];
		Path out = Path.of(args[8]);

		List<URL> owned = new ArrayList<>();
		owned.add(fixture.toUri().toURL());
		if (!mixinExtras.isEmpty()) owned.add(Path.of(mixinExtras).toUri().toURL());
		ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(URL[]::new), ClassLoader.getSystemClassLoader());
		Thread.currentThread().setContextClassLoader(loader);

		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(config, modId, ecosystem, "")));
		if ("off".equals(System.getProperty("forbric.weaveHarness.bootstrap"))) {
			System.out.println("[WeaveHarness] bootstrap skipped (control run)");
		} else {
			KernelMixinBootstrap.init(loader, side, List.of(config));
		}

		try {
			Class<?> target = Class.forName(probeClass, true, loader);
			Object result = target.getMethod(probeMethod).invoke(target.getDeclaredConstructor().newInstance());
			System.out.println(DONE + " " + result);
		} catch (Throwable thrown) {
			Throwable cause = thrown instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : thrown;
			System.out.println(THREW + cause);
			cause.printStackTrace(System.out);
		}
		System.out.flush();

		StringBuilder findings = new StringBuilder();
		for (CompatibilityFinding f : CompatibilityFindings.all()) {
			findings.append(String.join("\t", f.id(), f.modId(), f.confidence().name(), String.valueOf(f.required()),
					f.source(), flat(f.detail()))).append('\n');
		}
		Files.writeString(out.resolve("findings.tsv"), findings, StandardCharsets.UTF_8);
		PrintStream done = System.out;
		done.println("[WeaveHarness] findings written: " + CompatibilityFindings.all().size());
	}

	private static String flat(String text) {
		return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
