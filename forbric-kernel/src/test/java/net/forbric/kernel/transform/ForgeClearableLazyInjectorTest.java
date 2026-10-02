/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * MinecraftForge's real {@code ClearableLazy$Concurrent}, raced deterministically: one thread computes inside the lock
 * while a second has already read {@code null} and waits for that lock. Unpatched, the second returns {@code null};
 * patched, it returns the computed value.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ForgeClearableLazyInjectorTest {
	private static final Path FORGE_RUNTIME = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run", "forge-runtime", "forge-runtime.jar");
	private static final String ENTRY = "net/minecraftforge/common/util/ClearableLazy$Concurrent.class";

	private static byte[] original() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged forge-runtime absent");
		try (ZipFile zip = new ZipFile(FORGE_RUNTIME.toFile())) {
			var entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "ClearableLazy$Concurrent moved");
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static byte[] patched(byte[] original) {
		return new ForgeClearableLazyInjector().transform(ForgeClearableLazyInjector.TARGET, original, null);
	}

	@Test
	void theLosingThreadGetsTheComputedValueOnlyAfterTheRepair() throws Exception {
		byte[] original = original();
		assertNull(race(original), "premise: MinecraftForge's own body returns null to the thread that waited for the lock");
		assertEquals("computed", race(patched(original)));
	}

	@Test
	void theRepairIsThreeInstructionsVerifiesAndRunsOnce() throws Exception {
		byte[] original = original();
		byte[] once = patched(original);
		assertNotSame(original, once);
		ClassNode node = new ClassNode();
		new ClassReader(once).accept(node, 0);
		var get = node.methods.stream().filter(m -> m.name.equals("get")).findFirst().orElseThrow();
		new Analyzer<>(new BasicVerifier()).analyze(node.name, get);
		ClassNode before = new ClassNode();
		new ClassReader(original).accept(before, 0);
		var oldGet = before.methods.stream().filter(m -> m.name.equals("get")).findFirst().orElseThrow();
		assertEquals(oldGet.instructions.size() + 3, get.instructions.size());
		assertSame(once, patched(once), "a body that already re-reads is left alone");
	}

	@Test
	void theOffSwitchAndOtherClassesAreLeftAlone() throws Exception {
		byte[] original = original();
		assertSame(original, new ForgeClearableLazyInjector().transform("net.minecraftforge.common.util.Lazy$Concurrent", original, null));
		String old = System.setProperty(ForgeClearableLazyInjector.PROPERTY, "off");
		try {
			assertSame(original, patched(original));
		} finally {
			if (old == null) System.clearProperty(ForgeClearableLazyInjector.PROPERTY);
			else System.setProperty(ForgeClearableLazyInjector.PROPERTY, old);
		}
	}

	/**
	 * Thread A enters {@code get()} and blocks inside the supplier while holding the lock; thread B then reads
	 * {@code null}, and is parked on the monitor. A finishes; B's answer is what this returns.
	 */
	private static Object race(byte[] concurrent) throws Exception {
		try (URLClassLoader carrier = new URLClassLoader(new URL[] { FORGE_RUNTIME.toUri().toURL() },
				ForgeClearableLazyInjectorTest.class.getClassLoader());
				Loader loader = new Loader(carrier, concurrent)) {
			Class<?> type = loader.loadClass(ForgeClearableLazyInjector.TARGET);
			Constructor<?> make = type.getDeclaredConstructor(Supplier.class);
			make.setAccessible(true);
			CountDownLatch inSupplier = new CountDownLatch(1), release = new CountDownLatch(1);
			Supplier<Object> slow = () -> {
				inSupplier.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "computed";
			};
			Object lazy = make.newInstance(slow);
			Method get = type.getMethod("get");
			AtomicReference<Object> winner = new AtomicReference<>(), loser = new AtomicReference<>();
			Thread a = new Thread(() -> winner.set(call(get, lazy)), "lazy-winner");
			a.start();
			assertTrue(inSupplier.await(10, TimeUnit.SECONDS));
			Thread b = new Thread(() -> loser.set(call(get, lazy)), "lazy-loser");
			b.start();
			for (int i = 0; i < 1000 && b.getState() != Thread.State.BLOCKED; i++) Thread.sleep(5);
			assertEquals(Thread.State.BLOCKED, b.getState(), "the second thread must be waiting for the lock");
			release.countDown();
			a.join(10_000);
			b.join(10_000);
			assertEquals("computed", winner.get());
			return loser.get();
		}
	}

	private static Object call(Method get, Object lazy) {
		try {
			return get.invoke(lazy);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Defines the given {@code ClearableLazy$Concurrent} bytes; everything else comes from the carrier. */
	private static final class Loader extends ClassLoader implements AutoCloseable {
		private final byte[] concurrent;

		Loader(ClassLoader parent, byte[] concurrent) {
			super(parent);
			this.concurrent = concurrent;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!ForgeClearableLazyInjector.TARGET.equals(name)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> c = findLoadedClass(name);
				return c != null ? c : defineClass(name, concurrent, 0, concurrent.length);
			}
		}

		@Override
		public void close() {
		}
	}
}
