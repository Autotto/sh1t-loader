/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@link VanillaEarlyReturns} on bodies javac really compiles both ways: {@link Vanilla} written with guard clauses,
 * {@link Folded} the way the decompiler writes them back. A probe call is put before each method's last return —
 * what an {@code @Inject(at = @At("TAIL"))} handler compiles to — and the classes are DEFINED and RUN, so the JVM's
 * own verifier passes on every frame the split writes, and the probe is asserted to fire on exactly the calls it fires
 * on in vanilla. The unrepaired folded class is run too, and must differ: that is the bug, measured.
 */
class VanillaEarlyReturnsTest {
	/** What a TAIL handler sees: one line per call that reached the method's last return. */
	public static final class Probe {
		public static final List<String> HITS = new ArrayList<>();

		public static void tail() {
			HITS.add("TAIL");
		}
	}

	@SuppressWarnings("unused")
	public static final class Vanilla {
		public Vanilla() {
		}

		public Vanilla(Object a, List<String> log) {
			if (a == null) return;
			log.add("ctor");
		}

		public static void guard(Object a, Object b, List<String> log) {
			if (a == null || b == null) return;
			log.add("body");
		}

		public static int ternary(int x) {
			if (x < 0) return -1;
			return x * 2 + 1;
		}

		public static void earlyBlock(boolean a, List<String> log) {
			if (a) {
				log.add("early");
				return;
			}
			log.add("main");
		}

		public static void earlyFallsThrough(boolean a, List<String> log) {
			if (!a) {
				log.add("early");
				return;
			}
			log.add("main");
		}

		public static void wide(long t, double d, Object a, List<String> log) {
			double scaled = d * t;
			if (a == null || scaled < 0) return;
			long sum = t + (long) scaled;
			log.add("sum " + sum);
		}

		public static void inTry(Object a, List<String> log) {
			try {
				if (a == null) return;
				log.add("try " + a.hashCode() / (a.equals("zero") ? 0 : 1));
			} catch (ArithmeticException e) {
				log.add("catch");
			}
		}

		public static void twoEarly(int k, List<String> log) {
			if (k == 1) return;
			log.add("past one");
			if (k == 2) return;
			log.add("past two");
		}

		public static void mixed(int k, List<String> log) {
			if (k == 1) return;
			log.add("a");
			if (k == 2) return;
			log.add("b");
		}
	}

	@SuppressWarnings("unused")
	public static final class Folded {
		public Folded() {
		}

		public Folded(Object a, List<String> log) {
			if (a != null) {
				log.add("ctor");
			}
		}

		public static void guard(Object a, Object b, List<String> log) {
			if (a != null && b != null) {
				log.add("body");
			}
		}

		public static int ternary(int x) {
			return x < 0 ? -1 : x * 2 + 1;
		}

		public static void earlyBlock(boolean a, List<String> log) {
			if (a) {
				log.add("early");
			} else {
				log.add("main");
			}
		}

		public static void earlyFallsThrough(boolean a, List<String> log) {
			if (a) {
				log.add("main");
			} else {
				log.add("early");
			}
		}

		public static void wide(long t, double d, Object a, List<String> log) {
			double scaled = d * t;
			if (a != null && !(scaled < 0)) {
				long sum = t + (long) scaled;
				log.add("sum " + sum);
			}
		}

		public static void inTry(Object a, List<String> log) {
			try {
				if (a != null) {
					log.add("try " + a.hashCode() / (a.equals("zero") ? 0 : 1));
				}
			} catch (ArithmeticException e) {
				log.add("catch");
			}
		}

		public static void twoEarly(int k, List<String> log) {
			if (k != 1) {
				log.add("past one");
				if (k != 2) {
					log.add("past two");
				}
			}
		}

		/** The first guard survived the round trip, the second was folded: one inline return before the block. */
		public static void mixed(int k, List<String> log) {
			if (k == 1) return;
			log.add("a");
			if (k != 2) {
				log.add("b");
			}
		}
	}

	private static final String FOLDED = Type(Folded.class);

	@Test
	void theTailProbeFiresExactlyWhereVanillasDoes() throws Exception {
		Class<?> vanilla = define(probed(read(Vanilla.class)), Vanilla.class.getName());
		Class<?> folded = define(probed(read(Folded.class)), Folded.class.getName());
		ClassNode repairedNode = read(Folded.class);
		int split = VanillaEarlyReturns.restore(repairedNode, rows());
		assertEquals(9, split, "every fixture method is split");
		Class<?> repaired = define(probed(repairedNode), Folded.class.getName());

		List<String> differences = new ArrayList<>();
		for (Object[] call : calls()) {
			String expected = run(vanilla, call);
			assertEquals(expected, run(repaired, call), "repaired " + describe(call));
			String before = run(folded, call);
			if (!expected.equals(before)) differences.add(describe(call));
		}
		// The bug, measured on the same calls: the folded body reaches its tail on the early paths.
		assertTrue(differences.contains("guard(null, y)") && differences.contains("<init>(null)")
				&& differences.contains("ternary(-5)") && differences.contains("earlyBlock(true)")
				&& differences.contains("earlyFallsThrough(false)") && differences.contains("wide(null)")
				&& differences.contains("inTry(null)") && differences.contains("twoEarly(2)") && differences.contains("mixed(2)")
				&& !differences.contains("mixed(1)"), "folded differs on " + differences);
	}

	@Test
	void eachVanillaEarlyReturnGetsItsOwnBlockInVanillasOrder() {
		ClassNode node = read(Folded.class);
		VanillaEarlyReturns.restore(node, rows());
		MethodNode twoEarly = method(node, "twoEarly");
		VanillaEarlyReturns.Split split = VanillaEarlyReturns.splitOf(FOLDED, "twoEarly", twoEarly.desc);
		assertEquals(new VanillaEarlyReturns.Split(0, 2), split);
		assertEquals(3, VanillaEarlyReturns.returns(twoEarly).size(), "vanilla's return count, so RETURN ordinals count alike");
		VanillaEarlyReturns.Split guard = VanillaEarlyReturns.splitOf(FOLDED, "guard", method(node, "guard").desc);
		assertEquals(new VanillaEarlyReturns.Split(0, 1), guard, "two guard conditions, ONE vanilla return");
		assertEquals(new VanillaEarlyReturns.Split(1, 1), VanillaEarlyReturns.splitOf(FOLDED, "mixed", method(node, "mixed").desc),
				"the guard that survived stays where it is, before the block");
	}

	@Test
	void anEdgeVanillaAlsoSendsToTheTailIsNeverMoved() {
		// The fall-through after "main" reaches the tail in both: only the edge after "early" moves.
		ClassNode node = read(Folded.class);
		MethodNode before = method(node, "earlyBlock");
		int edgesBefore = tailEdges(before);
		VanillaEarlyReturns.restore(node, rows());
		assertEquals(edgesBefore - 1, tailEdges(method(node, "earlyBlock")));
	}

	@Test
	void aKeyThatOccursADifferentNumberOfTimesIsLeftAlone() {
		ClassNode node = read(Folded.class);
		Map<String, Map<String, List<String>>> rows = rows();
		String guard = "guard" + method(node, "guard").desc;
		Map<String, List<String>> doubled = new HashMap<>();
		rows.get(guard).forEach((key, labels) -> {
			List<String> more = new ArrayList<>(labels);
			more.add(labels.get(0));
			doubled.put(key, more);
		});
		rows.put(guard, doubled);
		VanillaEarlyReturns.restore(node, rows);
		assertEquals(1, VanillaEarlyReturns.returns(method(node, "guard")).size(), "ambiguity loses: the method stays folded");
	}

	@Test
	void theTableRowRoundTrips() {
		VanillaEarlyReturns.Row row = VanillaEarlyReturns.Row.parse("a/B#m(I)V 0000abcd=0,T 1234ffff=2");
		assertEquals("a/B", row.owner());
		assertEquals("m(I)V", row.method());
		assertEquals(List.of("0", "T"), row.keys().get("0000abcd"));
		assertEquals("a/B#m(I)V 0000abcd=0,T 1234ffff=2", row.format());
		assertNull(VanillaEarlyReturns.Row.parse("# comment"));
	}

	@Test
	void switchedOffItLeavesEveryBodyAsMerged() {
		String old = System.getProperty(VanillaEarlyReturns.PROPERTY);
		System.setProperty(VanillaEarlyReturns.PROPERTY, "off");
		try {
			byte[] bytes = bytes(Folded.class);
			assertSame(bytes, new VanillaEarlyReturns().transform(Folded.class.getName(), bytes, null));
		} finally {
			if (old == null) System.clearProperty(VanillaEarlyReturns.PROPERTY); else System.setProperty(VanillaEarlyReturns.PROPERTY, old);
		}
	}

	// --- fixtures ---

	/** The rows the census would write for the fixtures: every vanilla method's labels, keyed by the folded method. */
	static Map<String, Map<String, List<String>>> rows() {
		Map<String, Map<String, List<String>>> rows = new HashMap<>();
		for (MethodNode method : read(Vanilla.class).methods) {
			if ("<init>".equals(method.name) && "()V".equals(method.desc)) continue;
			rows.put(method.name + method.desc, VanillaEarlyReturns.labels(method));
		}
		return rows;
	}

	private static List<Object[]> calls() {
		List<Object[]> calls = new ArrayList<>();
		for (Object a : new Object[] {null, "x"}) for (Object b : new Object[] {null, "y"}) calls.add(new Object[] {"guard", a, b});
		calls.add(new Object[] {"<init>", null});
		calls.add(new Object[] {"<init>", "x"});
		for (int x : new int[] {-5, 0, 7}) calls.add(new Object[] {"ternary", x});
		for (boolean a : new boolean[] {true, false}) {
			calls.add(new Object[] {"earlyBlock", a});
			calls.add(new Object[] {"earlyFallsThrough", a});
		}
		calls.add(new Object[] {"wide", null});
		calls.add(new Object[] {"wide", "x"});
		calls.add(new Object[] {"wideNegative", "x"});
		calls.add(new Object[] {"inTry", null});
		calls.add(new Object[] {"inTry", "x"});
		calls.add(new Object[] {"inTry", "zero"});
		for (int k = 0; k < 4; k++) {
			calls.add(new Object[] {"twoEarly", k});
			calls.add(new Object[] {"mixed", k});
		}
		return calls;
	}

	private static String describe(Object[] call) {
		return switch ((String) call[0]) {
			case "guard" -> "guard(" + call[1] + ", " + call[2] + ")";
			case "wideNegative" -> "wide(negative)";
			default -> call[0] + "(" + call[1] + ")";
		};
	}

	/** One call's whole observable outcome: result or exception, what it logged, and whether the tail probe fired. */
	private static String run(Class<?> cls, Object[] call) throws Exception {
		Probe.HITS.clear();
		List<String> log = new ArrayList<>();
		Object result;
		try {
			result = switch ((String) call[0]) {
				case "guard" -> invoke(cls, "guard", call[1], call[2], log);
				case "<init>" -> {
					Constructor<?> ctor = cls.getConstructor(Object.class, List.class);
					ctor.newInstance(call[1], log);
					yield "constructed";
				}
				case "ternary" -> invoke(cls, "ternary", call[1]);
				case "earlyBlock", "earlyFallsThrough" -> invoke(cls, (String) call[0], call[1], log);
				case "wide" -> invoke(cls, "wide", 3L, 2.5, call[1], log);
				case "wideNegative" -> invoke(cls, "wide", 3L, -2.5, call[1], log);
				case "inTry" -> invoke(cls, "inTry", call[1], log);
				case "twoEarly", "mixed" -> invoke(cls, (String) call[0], call[1], log);
				default -> throw new AssertionError(call[0]);
			};
		} catch (InvocationTargetException thrown) {
			result = "threw " + thrown.getCause();
		}
		return result + " " + log + " " + Probe.HITS;
	}

	private static Object invoke(Class<?> cls, String name, Object... args) throws Exception {
		for (Method m : cls.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(null, args);
		}
		throw new AssertionError("no " + name);
	}

	/** Every method's last return gets the call a TAIL injector compiles to. */
	private static ClassNode probed(ClassNode node) {
		for (MethodNode method : node.methods) {
			AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
			if (tail == null || ("<init>".equals(method.name) && "()V".equals(method.desc))) continue;
			method.instructions.insertBefore(tail, new MethodInsnNode(Opcodes.INVOKESTATIC, Type(Probe.class), "tail", "()V", false));
		}
		return node;
	}

	private static Class<?> define(ClassNode node, String binaryName) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] bytes = writer.toByteArray();
		return new ClassLoader(VanillaEarlyReturnsTest.class.getClassLoader()) {
			Class<?> defined = defineClass(binaryName, bytes, 0, bytes.length);
		}.defined;
	}

	private static int tailEdges(MethodNode method) {
		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
		int n = 0;
		for (VanillaEarlyReturns.Edge edge : VanillaEarlyReturns.edges(method)) if (edge.target() == tail) n++;
		return n;
	}

	static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		throw new AssertionError("no " + name);
	}

	static ClassNode read(Class<?> cls) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes(cls)).accept(node, 0);
		return node;
	}

	static byte[] bytes(Class<?> cls) {
		String resource = "/" + cls.getName().replace('.', '/') + ".class";
		try (InputStream in = VanillaEarlyReturnsTest.class.getResourceAsStream(resource)) {
			return in.readAllBytes();
		} catch (Exception e) {
			throw new AssertionError(resource, e);
		}
	}

	private static String Type(Class<?> cls) {
		return cls.getName().replace('.', '/');
	}
}
