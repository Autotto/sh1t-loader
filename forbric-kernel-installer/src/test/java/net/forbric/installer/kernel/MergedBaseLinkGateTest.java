package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Runs the real installer subprocess gate against linked and deliberately broken game jars. */
public final class MergedBaseLinkGateTest {
	public static void main(String[] args) throws Exception {
		Path work = Path.of(args[0]);
		Path source = Files.createDirectories(work.resolve("src/game"));
		Path classes = Files.createDirectories(work.resolve("classes"));
		Path target = source.resolve("Target.java");
		Path caller = source.resolve("Caller.java");
		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		Files.writeString(caller, "package game; public class Caller { public static int read() { return Target.value; } }");
		compile(classes, target, caller);
		Path game = work.resolve("game.jar");
		jar(classes, game);
		Path empty = work.resolve("carrier.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(empty))) { }
		List<String> logs = new ArrayList<>();
		MergedBaseTool tool = new MergedBaseTool(work.resolve("tools"), logs::add);
		JdkLocator.Jvm jvm = new JdkLocator.Jvm(Path.of(ForgeTool.javaBin()), Runtime.version().feature(), "test");
		tool.linkCheck(jvm, game, empty, empty);
		require(logs.stream().anyMatch(s -> s.contains("(known 0, new 0)")), "successful gate did not scan");
		Files.writeString(target, "package game; public class Target { }");
		compile(classes, target); // Caller still refers to the removed field.
		jar(classes, game);
		try {
			tool.linkCheck(jvm, game, empty, empty);
			throw new AssertionError("installer accepted a new dangling reference");
		} catch (IOException expected) {
			require(expected.getMessage().contains("game/Target.value"), "lost the underlying evidence");
		}
		try {
			tool.linkCheck(jvm, empty, empty, empty);
			throw new AssertionError("installer accepted an empty merged base");
		} catch (IOException expected) {
			require(expected.getMessage().contains("no classes"), "empty scan was not explained");
		}
		System.out.println("PASS installer gate: valid build, new defect rejected, empty scan rejected");
		suppliedArtifacts(work, classes, target, logs);
		// The content check that runs before this link check on a supplied set; here so every runner of this gate
		// (gradle linkGateTest, run/test-link-gate.py, CI) exercises it too.
		SuppliedArtifactContentTest.main(new String[] { work.resolve("content").toString() });
	}

	/**
	 * {@code --artifacts DIR}: the supplied set is link-checked like a built one, the interop jar is what stands for
	 * the Forge runtime, and a failed check leaves no profile behind.
	 *
	 * <p>The set has to look like a real one first, or the content check refuses it before any link check runs:
	 * the game jar is Minecraft 26.2 whose client refers to both families, and each carrier holds its family's
	 * core class and mod loader. Those references are real bytecode, so they are resolved like any other.
	 */
	private static void suppliedArtifacts(Path work, Path classes, Path target, List<String> logs) throws Exception {
		Path supplied = Files.createDirectories(work.resolve("supplied"));
		Path game = Files.createDirectories(supplied.resolve("merged-base")).resolve("patched-mc-merged-26.2.jar");
		Path interop = supplied.resolve("merged-base").resolve("forge-runtime-interop.jar");
		Path raw = Files.createDirectories(supplied.resolve("forge-runtime")).resolve("forge-runtime.jar");
		Path neo = Files.createDirectories(supplied.resolve("neoforge-runtime")).resolve("neoforge-runtime.jar");
		Path carriers = Files.createDirectories(work.resolve("src/carriers"));
		Path forgeClasses = Files.createDirectories(work.resolve("forge-classes"));
		Path neoClasses = Files.createDirectories(work.resolve("neo-classes"));
		String hook = "{ public static void hook() { } }";
		compileWith(forgeClasses, null,
				source(carriers, "net.minecraftforge.common", "public class MinecraftForge " + hook),
				source(carriers, "net.minecraftforge.fml.loading", "public class FMLLoader { }"),
				source(carriers, "net.minecraftforge.forgespi.language", "public interface IModInfo { }"));
		compileWith(neoClasses, null,
				source(carriers, "net.neoforged.neoforge.common", "public class NeoForge " + hook),
				source(carriers, "net.neoforged.fml.loading", "public class FMLLoader { }"),
				source(carriers, "net.neoforged.neoforgespi.language", "public interface IModInfo { }"));
		jar(forgeClasses, interop);
		jar(forgeClasses, raw);
		jar(neoClasses, neo);
		compileWith(classes, forgeClasses + java.io.File.pathSeparator + neoClasses,
				source(work.resolve("src"), "net.minecraft.client", "public class Minecraft { void run() { "
						+ "net.minecraftforge.common.MinecraftForge.hook(); "
						+ "net.neoforged.neoforge.common.NeoForge.hook(); } }"));
		Files.writeString(classes.resolve("version.json"), "{\"id\": \"26.2\"}");
		Path mc = Files.createDirectories(work.resolve("minecraft/versions/26.2"));
		Files.writeString(mc.resolve("26.2.json"), "{\"id\":\"26.2\",\"libraries\":[]}");
		Files.write(mc.resolve("26.2.jar"), new byte[] { 'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		Path mcDir = work.resolve("minecraft");
		Path jdk = Path.of(ForgeTool.javaBin());

		Files.writeString(target, "package game; public class Target { public static int value = 3; }");
		compile(classes, target);
		jar(classes, game);
		logs.clear();
		var found = new Installer(logs::add).obtainGameArtifacts(mcDir, "26.2", supplied, jdk);
		require(found.get(ArtifactBuilder.FORGE_RUNTIME).equals(interop),
				"the interop jar, not the raw runtime, stands for the Forge runtime: " + found);
		require(logs.stream().anyMatch(s -> s.contains("(known 0, new 0)")), "a supplied set was not link-checked");

		Files.writeString(target, "package game; public class Target { }");
		compile(classes, target);
		jar(classes, game);
		try {
			new Installer(logs::add).install(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("installer published a supplied merged base with a new dangling reference");
		} catch (IOException expected) {
			require(expected.getMessage().contains("game/Target.value"), "lost the underlying evidence: " + expected);
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric/26.2-forbric.json")), "a profile was written");

		Files.delete(interop);
		try {
			new Installer(logs::add).obtainGameArtifacts(mcDir, "26.2", supplied, jdk);
			throw new AssertionError("the raw forge-runtime.jar was accepted in place of the interop jar");
		} catch (IOException expected) {
			require(expected.getMessage().contains("forge-runtime-interop.jar"), "missing interop not named: " + expected);
		}
		System.out.println("PASS installer --artifacts: link-checked, interop staged, broken set refused with no profile");
	}

	private static void compile(Path classes, Path... sources) {
		compileWith(classes, null, sources);
	}

	private static void compileWith(Path classes, String classpath, Path... sources) {
		List<String> args = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
		if (classpath != null) args.addAll(List.of("-cp", classpath));
		for (Path source : sources) args.add(source.toString());
		require(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) == 0, "javac failed");
	}

	/** Writes {@code body} as the one top-level type of {@code pkg}, named after the type it declares. */
	private static Path source(Path root, String pkg, String body) throws IOException {
		String name = body.replaceFirst("^public (?:class|interface) (\\w+).*$", "$1");
		Path file = Files.createDirectories(root.resolve(pkg.replace('.', '/'))).resolve(name + ".java");
		Files.writeString(file, "package " + pkg + "; " + body);
		return file;
	}

	private static void jar(Path classes, Path dest) throws IOException {
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(dest)); var files = Files.walk(classes)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, out);
				out.closeEntry();
			}
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
