/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class SoundRegistryIdentityInjectorTest {
	@Test void stagedForgeConstructorReplacesAllValueIndexesAndRemainsVerifiable() throws Exception {
		Path staged = Path.of(System.getProperty("forbric.stagedRoot"), "forge-runtime", "forge-runtime.jar");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(staged));
		byte[] original;
		try (var jar = new ZipFile(staged.toFile())) {
			original = jar.getInputStream(jar.getEntry("net/minecraftforge/registries/ForgeRegistry.class")).readAllBytes();
		}
		var transformer = new SoundRegistryIdentityInjector();
		assertSame(original, transformer.transform("unrelated.Class", original, null));
		byte[] patched = transformer.transform(SoundRegistryIdentityInjector.TARGET, original, null);
		assertNotSame(original, patched);
		assertSame(patched, transformer.transform(SoundRegistryIdentityInjector.TARGET, patched, null));
		var node = new ClassNode(); new ClassReader(patched).accept(node, 0);
		int bimaps = 0, holders = 0;
		for (var method : node.methods) {
			if (!method.name.equals("<init>")) continue;
			new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
			for (var instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals("net/forbric/kernel/runtime/IdentityValueBiMap")) continue;
				if (call.name.equals("forRegistry")) bimaps++;
				if (call.name.equals("delegatesForRegistry")) holders++;
			}
		}
		assertEquals(4, bimaps); assertEquals(1, holders);
	}
}
