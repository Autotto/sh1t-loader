/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.VanillaEarlyReturns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A NeoForge or MinecraftForge mixin's TAIL, on a method whose early returns were restored, still names every return
 * the folded body sent to its tail; a Fabric mixin's keeps vanilla's meaning. The targets are the real split fixtures
 * of {@code VanillaEarlyReturnsTest}: {@code guard} (one block, nothing before it) and {@code mixed} (one inline
 * return, then one block).
 */
class MixinNativeTailTest {
	private static final String FOLDED = "net/forbric/kernel/transform/VanillaEarlyReturnsTest$Folded";
	private static final String VANILLA = "net/forbric/kernel/transform/VanillaEarlyReturnsTest$Vanilla";

	@AfterEach
	void forget() {
		MixinStubRebind.forget();
	}

	@Test
	void aNeoForgeTailOnAFullyFoldedMethodBecomesEveryReturn() {
		ClassNode mixin = mixin("test/NeoGuard", Ecosystem.NEOFORGE, "guard", List.of(at("TAIL", null)));
		assertEquals(1, MixinNativeTail.adapt(mixin, repairedTargets()));
		List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.get(0)));
		assertEquals(1, ats.size());
		assertEquals("RETURN", MixinFit.value(ats.get(0), "value"));
		assertNull(MixinFit.value(ats.get(0), "ordinal"), "nothing returns before the block: all returns, no ordinal");
	}

	@Test
	void aMinecraftForgeTailAfterAnInlineReturnNamesTheBlockAndTheTail() {
		ClassNode mixin = mixin("test/ForgeMixed", Ecosystem.FORGE, "mixed", List.of(at("TAIL", null)));
		assertEquals(1, MixinNativeTail.adapt(mixin, repairedTargets()));
		List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.get(0)));
		assertEquals(List.of(1, 2), ats.stream().map(a -> MixinFit.value(a, "ordinal")).toList(),
				"returns 1 (the block) and 2 (the tail) — never 0, the guard that returned early on its own loader too");
		ats.forEach(a -> assertEquals("RETURN", MixinFit.value(a, "value")));
	}

	@Test
	void aReturnOrdinalThatNamedTheOldTailFollowsIt() {
		ClassNode mixin = mixin("test/NeoOrdinal", Ecosystem.NEOFORGE, "mixed",
				List.of(at("RETURN", 0), at("RETURN", 1)));
		assertEquals(1, MixinNativeTail.adapt(mixin, repairedTargets()));
		List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.get(0)));
		assertEquals(List.of(0, 1, 2), ats.stream().map(a -> MixinFit.value(a, "ordinal")).toList(),
				"ordinal 0 was the inline return and stays; ordinal 1 was the tail and becomes the block and the tail");
	}

	@Test
	void aFabricTailKeepsVanillasMeaning() {
		ClassNode mixin = mixin("test/FabricGuard", Ecosystem.FABRIC, "guard", List.of(at("TAIL", null)));
		assertEquals(0, MixinNativeTail.adapt(mixin, repairedTargets()));
		assertEquals("TAIL", MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.get(0))).get(0), "value"));
	}

	@Test
	void aSingleAtThatCannotCarryTwoOrdinalsIsLeftAndSaysSo() {
		ClassNode mixin = mixin("test/NeoSingle", Ecosystem.NEOFORGE, "mixed", null);
		AnnotationNode injector = MixinFit.injectorOf(mixin.methods.get(0));
		CarpetMixinAdapter.set(injector, "at", at("TAIL", null));
		assertEquals(0, MixinNativeTail.adapt(mixin, repairedTargets()));
		assertEquals("TAIL", MixinFit.value((AnnotationNode) MixinFit.value(injector, "at"), "value"));
	}

	@Test
	void aMethodThatWasNotSplitIsNotTouched() {
		ClassNode mixin = mixin("test/NeoUnsplit", Ecosystem.NEOFORGE, "<init>()V", List.of(at("TAIL", null)));
		assertEquals(0, MixinNativeTail.adapt(mixin, repairedTargets()));
	}

	// --- fixtures ---

	private static java.util.function.Function<String, ClassNode> repairedTargets() {
		ClassNode folded = read(FOLDED);
		Map<String, Map<String, List<String>>> rows = new HashMap<>();
		for (MethodNode method : read(VANILLA).methods) {
			if ("<init>".equals(method.name) && "()V".equals(method.desc)) continue;
			rows.put(method.name + method.desc, labels(method));
		}
		restore(folded, rows);
		return name -> FOLDED.equals(name) ? folded : null;
	}

	private static ClassNode mixin(String name, Ecosystem ecosystem, String selector, List<AnnotationNode> ats) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(FOLDED))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handler",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", List.of(selector)));
		if (ats != null) {
			inject.values.add("at");
			inject.values.add(new ArrayList<>(ats));
		}
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods.add(handler);
		MixinStubRebind.noteEcosystem(name, ecosystem);
		return mixin;
	}

	private static AnnotationNode at(String value, Integer ordinal) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		if (ordinal != null) {
			at.values.add("ordinal");
			at.values.add(ordinal);
		}
		return at;
	}

	private static ClassNode read(String internal) {
		try (InputStream in = MixinNativeTailTest.class.getResourceAsStream("/" + internal + ".class")) {
			ClassNode node = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(node, 0);
			return node;
		} catch (Exception e) {
			throw new AssertionError(internal, e);
		}
	}

	// VanillaEarlyReturns keeps its census helpers package-private; reach them the way the census test does.
	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> labels(MethodNode method) {
		return (Map<String, List<String>>) call("labels", new Class<?>[] {MethodNode.class}, method);
	}

	private static void restore(ClassNode node, Map<String, Map<String, List<String>>> rows) {
		call("restore", new Class<?>[] {ClassNode.class, Map.class}, node, rows);
	}

	private static Object call(String name, Class<?>[] types, Object... args) {
		try {
			java.lang.reflect.Method m = VanillaEarlyReturns.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			return m.invoke(null, args);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(name, e);
		}
	}
}
