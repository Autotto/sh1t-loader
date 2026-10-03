package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/** The harness must be able to tell a woven class from an unwoven one, or nothing it reports means anything. */
class WeaveHarnessSelfTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/selftest");
	private static final String CONFIG = "selftest.mixins.json";

	@TempDir Path work;

	@Test void realMixinWeavesTheFixtureAndTheWovenCodeRuns() throws Exception {
		Path fixture = fixture();
		WeaveHarness.Result woven = run(fixture, "woven", Map.of());
		WeaveHarness.Result plain = run(fixture, "control-no-bootstrap", Map.of("forbric.weaveHarness.bootstrap", "off"));

		// What the woven method DID, not what the mixin declared.
		assertTrue(woven.printed(WeaveHarnessMain.DONE + " woven"), woven.describe());
		assertTrue(woven.printed("Mixin up on the sovereign kernel — 1 config(s) registered"), woven.describe());
		assertTrue(WeaveHarness.hasMergedMethod(woven.defined("fixture/selftest/Greeter")),
				"ForbricClassLoader defined Greeter without Mixin's merged handler — " + woven.describe());
		WeaveHarness.assertWovenAndVerified(woven, "fixture/selftest/Greeter", fixture);

		// The absent anchor is the runtime audit's job: one confirmed, required loss, named for its mod.
		List<WeaveHarness.Finding> losses = woven.findings().stream()
				.filter(f -> f.id().startsWith("mixin-injector:") && f.confirmedRequired()).toList();
		assertEquals(1, losses.size(), "findings: " + woven.findings() + "\n" + woven.describe());
		assertEquals("selftest", losses.get(0).modId());
		assertTrue(losses.get(0).id().contains("missing"), losses.get(0).toString());
		assertFalse(woven.printed("absent site executed"), woven.describe());

		// Control: the same fixture and probe without the bootstrap. Every assertion above must be able to fail.
		assertTrue(plain.printed(WeaveHarnessMain.DONE + " plain"), plain.describe());
		assertFalse(plain.printed("Mixin up on the sovereign kernel"), plain.describe());
		assertFalse(WeaveHarness.hasMergedMethod(plain.defined("fixture/selftest/Greeter")), plain.describe());
		assertTrue(plain.findings().stream().noneMatch(f -> f.id().startsWith("mixin-injector:")), plain.findings().toString());
	}

	private Path fixture() throws Exception {
		return WeaveHarness.fixture(work, "selftest", List.of(
				SOURCES.resolve("fixture/selftest/Greeter.java"),
				SOURCES.resolve("fixture/selftest/mixin/GreeterMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
	}

	private WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "selftest", Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.selftest.Greeter", "greet", properties);
	}
}
