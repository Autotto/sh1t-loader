package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Built artifacts" / {@code --artifacts}: a supplied file is judged by what is IN it, not by its name (#13).
 *
 * <p>Each fake jar below is built from the entries the real one was found to carry (or, for the negatives, the
 * entries the file a player might grab instead carries). Class bodies are placeholder bytes holding the package
 * references the merged-base check looks for; nothing here is loaded or link-checked, which is
 * {@link MergedBaseLinkGateTest}'s job.
 */
public final class SuppliedArtifactContentTest {
	private static final String MC = "26.2";
	private static final String MERGED = ArtifactBuilder.MERGED;
	private static final String FORGE = ArtifactBuilder.FORGE_RUNTIME;
	private static final String NEO = ArtifactBuilder.NEOFORGE_RUNTIME;

	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]));
		int checks = 0;

		// ---- the real shapes pass ----
		Path merged = jar(work.resolve("ok/patched-mc-merged-26.2.jar"), mergedBase(MC, true, true));
		Path forge = jar(work.resolve("ok/forge-runtime-interop.jar"), forgeRuntime());
		Path neo = jar(work.resolve("ok/neoforge-runtime.jar"), neoRuntime());
		requireOk(MERGED, merged);
		requireOk(FORGE, forge);
		requireOk(NEO, neo);
		GameArtifacts good = GameArtifacts.locate(MC, work.resolve("ok"));
		good.verifyContents(MC);
		checks += 4;

		// ---- what a player might pick up instead ----
		Path gson = jar(work.resolve("gson.jar"), entries("com/google/gson/Gson.class", "gson",
				"META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
		Path empty = jar(work.resolve("empty.jar"), Map.of());
		Path forgeInstaller = jar(work.resolve("forge-installer.jar"), entries(
				"install_profile.json", "{}",
				"version.json", "{\"id\": \"26.2-forge-65.0.1\"}",
				"net/minecraftforge/installer/SimpleInstaller.class", "installer"));
		Path neoInstaller = jar(work.resolve("neoforge-installer.jar"), entries(
				"install_profile.json", "{}",
				"version.json", "{\"id\": \"neoforge-26.2.0.88\"}",
				"net/minecraftforge/installer/SimpleInstaller.class", "installer",
				"net/neoforged/cliutils/progress/ProgressReporter.class", "installer"));
		Path fabricInstaller = jar(work.resolve("fabric-installer.jar"), entries(
				"net/fabricmc/installer/Main.class", "installer"));
		Path notAJar = work.resolve("notes.jar");
		Files.writeString(notAJar, "this is a text file someone renamed");

		for (String coordinate : List.of(MERGED, FORGE, NEO)) {
			requireProblem(coordinate, gson, coordinate.equals(MERGED) ? "does not contain Minecraft"
					: "does not contain " + (coordinate.equals(FORGE) ? "MinecraftForge" : "NeoForge"));
			requireProblem(coordinate, empty, "empty archive");
			requireProblem(coordinate, forgeInstaller, "the MinecraftForge installer");
			requireProblem(coordinate, neoInstaller, "the NeoForge installer");
			requireProblem(coordinate, fabricInstaller, "the Fabric installer");
			requireProblem(coordinate, notAJar, "not a jar file");
			checks += 6;
		}

		// ---- Minecraft, but not the merged base ----
		requireProblem(MERGED, jar(work.resolve("vanilla.jar"), mergedBase(MC, false, false)), "plain Minecraft 26.2");
		requireProblem(MERGED, jar(work.resolve("mc-forge.jar"), mergedBase(MC, true, false)),
				"patched by MinecraftForge only");
		requireProblem(MERGED, jar(work.resolve("mc-neo.jar"), mergedBase(MC, false, true)), "patched by NeoForge only");
		requireProblem(MERGED, jar(work.resolve("mc-26.1.jar"), mergedBase("26.1", true, true)),
				"Minecraft 26.1, but this install is for Minecraft 26.2");
		checks += 4;

		// ---- the right family, but not the runtime Forbric assembles; and the two runtimes swapped ----
		requireProblem(FORGE, jar(work.resolve("forge-universal.jar"),
				entries("net/minecraftforge/common/MinecraftForge.class", "x", "META-INF/mods.toml", "")),
				"not the MinecraftForge runtime Forbric puts together");
		requireProblem(NEO, jar(work.resolve("neoforge-universal.jar"),
				entries("net/neoforged/neoforge/common/NeoForge.class", "x", "META-INF/neoforge.mods.toml", "")),
				"not the NeoForge runtime Forbric puts together");
		requireProblem(FORGE, neo, "does not contain MinecraftForge (it looks like NeoForge instead)");
		requireProblem(NEO, forge, "does not contain NeoForge (it looks like MinecraftForge instead)");
		requireProblem(NEO, merged, "does not contain NeoForge (it looks like Minecraft instead)");
		checks += 5;

		// ---- the whole set: every bad file named at once, with the way out ----
		Path wrong = Files.createDirectories(work.resolve("wrong"));
		Files.copy(gson, wrong.resolve("patched-mc-merged-26.2.jar"));
		Files.copy(empty, wrong.resolve("forge-runtime-interop.jar"));
		Files.copy(neoInstaller, wrong.resolve("neoforge-runtime.jar"));
		try {
			GameArtifacts.locate(MC, wrong).verifyContents(MC);
			throw new AssertionError("a set of three renamed jars was accepted");
		} catch (IOException expected) {
			String message = expected.getMessage();
			for (String part : List.of("these files are not the game files Forbric needs",
					wrong.resolve("patched-mc-merged-26.2.jar").toString(),
					wrong.resolve("forge-runtime-interop.jar").toString(),
					wrong.resolve("neoforge-runtime.jar").toString(),
					"Leave \"Built artifacts\" empty")) {
				require(message.contains(part), "the refusal does not say \"" + part + "\":\n" + message);
			}
		}
		checks++;

		// ---- the installer refuses it before anything is downloaded, staged or written ----
		// versions/26.2 holds a jar and an unparseable JSON: if the supplied set were judged after the base version
		// is read, this would fail on the JSON (or, with no base at all, go to Mojang) instead of on the files.
		Path mcDir = work.resolve("minecraft");
		Path base = Files.createDirectories(mcDir.resolve("versions/26.2"));
		Files.writeString(base.resolve("26.2.json"), "not json");
		Files.write(base.resolve("26.2.jar"), new byte[0]);
		try {
			new Installer(line -> { }).install(mcDir, MC, wrong);
			throw new AssertionError("the installer installed three renamed jars");
		} catch (IOException expected) {
			require(expected.getMessage().contains("not the game files Forbric needs"),
					"refused for the wrong reason (was the base version read first?): " + expected.getMessage());
		}
		require(!Files.exists(mcDir.resolve("versions/26.2-forbric")), "a profile directory was written");
		require(!Files.exists(mcDir.resolve("libraries")), "something was staged into libraries/");
		require(!Files.exists(mcDir.resolve(".forbric-build")), "the link-check tools were unpacked for a refused set");
		checks++;

		// ---- a missing file: the way out comes first, the developer detail after ----
		// Run from a checkout that has built its own artifacts (forbric-loader/run/...), this used to be completed
		// from there; only the directory named counts now.
		Path partial = Files.createDirectories(work.resolve("partial"));
		Files.copy(merged, partial.resolve("patched-mc-merged-26.2.jar"));
		try {
			GameArtifacts.locate(MC, partial);
			throw new AssertionError("an incomplete set was located");
		} catch (IOException expected) {
			String message = expected.getMessage();
			require(message.contains("cannot find forge-runtime-interop.jar, neoforge-runtime.jar in " + partial),
					"missing files not named: " + message);
			require(message.contains("Leave \"Built artifacts\" empty"),
					"missing-file error has no way out: " + message);
			require(message.indexOf("Leave \"Built artifacts\"") < message.indexOf("Developers"),
					"the developer detail comes before the player's answer: " + message);
		}
		checks++;

		System.out.println("PASS installer --artifacts content: " + checks + " checks (real shapes accepted;"
				+ " renamed gson, empty zip, installers, vanilla, half-patched, universal and swapped jars refused)");
	}

	/** Minecraft {@code version} as the merged base carries it, with or without each family's patches. */
	private static Map<String, byte[]> mergedBase(String version, boolean forgePatched, boolean neoPatched) {
		String body = "client" + (forgePatched ? " net/minecraftforge/common/extensions/IForgeMinecraft" : "")
				+ (neoPatched ? " net/neoforged/neoforge/client/ClientHooks" : "");
		Map<String, byte[]> entries = entries(
				"version.json", "{\"id\": \"" + version + "\", \"name\": \"" + version + "\"}",
				"net/minecraft/client/Minecraft.class", body,
				"net/minecraft/world/item/ItemStack.class", "item");
		if (forgePatched) entries.putAll(entries("net/minecraftforge/api/distmarker/Dist.class", "dist"));
		if (neoPatched) entries.putAll(entries("META-INF/neoforge.mods.toml", "modLoader=\"minecraft\""));
		return entries;
	}

	private static Map<String, byte[]> forgeRuntime() {
		return entries(
				"fabric.mod.json", "{\"id\": \"forge\"}",
				"META-INF/mods.toml", "modId=\"forge\"",
				"net/minecraftforge/common/MinecraftForge.class", "core",
				"net/minecraftforge/fml/loading/FMLLoader.class", "loader",
				"net/minecraftforge/forgespi/language/IModInfo.class", "spi");
	}

	private static Map<String, byte[]> neoRuntime() {
		return entries(
				"fabric.mod.json", "{\"id\": \"neoforge\"}",
				"META-INF/neoforge.mods.toml", "modId=\"neoforge\"",
				"net/neoforged/neoforge/common/NeoForge.class", "core",
				"net/neoforged/fml/loading/FMLLoader.class", "loader",
				"net/neoforged/neoforgespi/language/IModInfo.class", "spi");
	}

	private static Map<String, byte[]> entries(String... nameThenContent) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (int i = 0; i < nameThenContent.length; i += 2) {
			out.put(nameThenContent[i], nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
		}
		return out;
	}

	private static Path jar(Path dest, Map<String, byte[]> entries) throws IOException {
		Files.createDirectories(dest.getParent());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dest))) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(e.getKey()));
				out.write(e.getValue());
				out.closeEntry();
			}
		}
		return dest;
	}

	private static void requireOk(String coordinate, Path jar) {
		String problem = GameArtifacts.problem(coordinate, jar, MC);
		require(problem == null, coordinate + " refused a correct " + jar.getFileName() + ": " + problem);
	}

	private static void requireProblem(String coordinate, Path jar, String expected) {
		String problem = GameArtifacts.problem(coordinate, jar, MC);
		require(problem != null, coordinate + " accepted " + jar.getFileName());
		require(problem.contains(expected), coordinate + " refused " + jar.getFileName() + " but said \"" + problem
				+ "\", not \"" + expected + "\"");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
