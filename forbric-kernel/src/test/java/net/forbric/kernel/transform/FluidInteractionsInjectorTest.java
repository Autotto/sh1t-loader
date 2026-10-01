/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.runtime.StagedGameClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Lava placed next to water on the merged game: on the real merged LiquidBlock and both carriers' registries, and in a
 * real JVM, where the merged {@code onPlace}/{@code neighborChanged}, NeoForge's and MinecraftForge's
 * {@code FluidInteractionRegistry} and the merged {@code FluidState} are linked against each other and handed fixture
 * fluids in a fixture level. The registries' initializers (which need a bootstrapped game) are replaced by an empty
 * map, and the interactions are the test's own, registered through each registry's real {@code addInteraction}; the
 * control is the game as it was: the merged LiquidBlock with MinecraftForge's {@code canInteract} neutered.
 */
@ResourceLock("system-properties")
class FluidInteractionsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO_GAME = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path FORGE_GAME = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_CARRIER = STAGED.resolve("forge-runtime/forge-runtime.jar");
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final String NEO = FluidInteractionsInjector.NEO_INTERNAL;
	private static final String FORGE = FluidInteractionsInjector.FORGE_INTERNAL;
	private static final String FLUID_STATE = "net/minecraft/world/level/material/FluidState";
	private static final String LEVEL = "net/minecraft/world/level/Level";
	private static final String POS = "net/minecraft/core/BlockPos";
	private static final String INTERACT = FluidInteractionsInjector.INTERACT_DESC;

	@AfterEach void reset() { System.clearProperty(FluidInteractionsInjector.PROPERTY); }

	// ------------------------------------------------------------------------------------------------ bytecode shape

	@Test void onPlaceAsksNeoForgesRegistryAsNeighborChangedDoes() throws Exception {
		byte[] merged = NativeCoremodParityTest.read(MERGED, LIQUID);
		ClassNode original = node(merged);
		assertEquals(List.of(FORGE), canInteractOwners(method(original, "onPlace")), "premise: the merged onPlace is MinecraftForge's");
		assertEquals(List.of(NEO), canInteractOwners(method(original, "neighborChanged")), "premise: the merged neighborChanged is NeoForge's");

		byte[] out = new FluidInteractionsInjector().transform(FluidInteractionsInjector.LIQUID, merged, null);
		ClassNode repaired = node(out);
		assertEquals(List.of(NEO), canInteractOwners(method(repaired, "onPlace")));
		assertEquals(List.of(NEO), canInteractOwners(method(repaired, "neighborChanged")));
		assertEquals(opcodes(method(original, "onPlace")), opcodes(method(repaired, "onPlace")), "the call's owner is all that changed");
		assertEquals(opcodes(method(original, "neighborChanged")), opcodes(method(repaired, "neighborChanged")));
		for (MethodNode m : repaired.methods) if (m.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(LIQUID, m);
		assertSame(out, new FluidInteractionsInjector().transform(FluidInteractionsInjector.LIQUID, out, null), "a second pass changes nothing");
	}

	@Test void neoForgesUnmatchedAnswerAsksMinecraftForgesRegistry() throws Exception {
		byte[] carrier = NativeCoremodParityTest.read(NEO_CARRIER, NEO);
		List<AbstractInsnNode> before = real(method(node(carrier), "canInteract", INTERACT));
		assertEquals(List.of(Opcodes.ICONST_0, Opcodes.IRETURN), before.subList(before.size() - 2, before.size()).stream()
				.map(AbstractInsnNode::getOpcode).toList(), "premise: NeoForge's canInteract ends `return false`");

		byte[] out = new FluidInteractionsInjector().transform(FluidInteractionsInjector.NEO, carrier, null);
		MethodNode canInteract = method(node(out), "canInteract", INTERACT);
		List<AbstractInsnNode> after = real(canInteract);
		List<AbstractInsnNode> tail = after.subList(after.size() - 4, after.size());
		assertEquals(List.of(Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.INVOKESTATIC, Opcodes.IRETURN), tail.stream().map(AbstractInsnNode::getOpcode).toList());
		assertEquals(List.of(0, 1), tail.subList(0, 2).stream().map(i -> ((VarInsnNode) i).var).toList(), "(level, pos)");
		MethodInsnNode ask = (MethodInsnNode) tail.get(2);
		assertEquals(List.of(FluidInteractionsInjector.RUNTIME, FluidInteractionsInjector.FORGE_LEG, INTERACT), List.of(ask.owner, ask.name, ask.desc));
		assertEquals(before.size() + 2, after.size(), "nothing else in NeoForge's walk changed");
		assertEquals(1, Arrays.stream(canInteract.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.name.equals("interact")).count(),
				"its own first match still returns at once");
		new Analyzer<>(new BasicVerifier()).analyze(NEO, canInteract);
		assertSame(out, new FluidInteractionsInjector().transform(FluidInteractionsInjector.NEO, out, null));
	}

	@Test void minecraftForgesAddInteractionReportsInAndItsCanInteractIsLeftWhole() throws Exception {
		byte[] carrier = NativeCoremodParityTest.read(FORGE_CARRIER, FORGE);
		byte[] out = new FluidInteractionsInjector().transform(FluidInteractionsInjector.FORGE, carrier, null);
		ClassNode repaired = node(out);
		MethodNode add = repaired.methods.stream().filter(m -> m.name.equals("addInteraction")).findFirst().orElseThrow();
		assertTrue(real(add).getFirst() instanceof MethodInsnNode first && first.owner.equals(FluidInteractionsInjector.RUNTIME)
				&& first.name.equals(FluidInteractionsInjector.IN_USE) && first.desc.equals("()V"), "reports in before it adds");
		assertEquals(opcodes(method(node(carrier), "canInteract", INTERACT)), opcodes(method(repaired, "canInteract", INTERACT)),
				"MinecraftForge's own walk is not neutered any more, and not edited");
		for (MethodNode m : repaired.methods) if (m.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(FORGE, m);
		assertSame(out, new FluidInteractionsInjector().transform(FluidInteractionsInjector.FORGE, out, null));
	}

	@Test void eachFamilysOwnLiquidBlockIsLeftAlone() throws Exception {
		for (Path game : List.of(NEO_GAME, FORGE_GAME)) {
			byte[] own = NativeCoremodParityTest.read(game, LIQUID);
			assertSame(own, new FluidInteractionsInjector().transform(FluidInteractionsInjector.LIQUID, own, null), game.toString());
		}
	}

	@Test void theSwitchLeavesAllThreeAlone() throws Exception {
		System.setProperty(FluidInteractionsInjector.PROPERTY, "off");
		for (String[] target : List.of(new String[] {LIQUID, MERGED.toString()}, new String[] {NEO, NEO_CARRIER.toString()},
				new String[] {FORGE, FORGE_CARRIER.toString()})) {
			byte[] bytes = NativeCoremodParityTest.read(Path.of(target[1]), target[0]);
			assertSame(bytes, new FluidInteractionsInjector().transform(target[0].replace('/', '.'), bytes, null), target[0]);
		}
	}

	// ------------------------------------------------------------------------------------------------ in a real JVM

	@Test void asMergedPlacingLavaNextToWaterNeverReachesAnInteraction() throws Exception {
		try (World merged = new World(false)) {
			merged.neoRule("water", merged.neighbourIs("water"));
			merged.lavaNextTo("water");
			merged.onPlace();
			assertEquals(List.of("tick"), merged.events, "control: onPlace asked MinecraftForge's neutered registry, found nothing and let the lava flow");
			merged.events.clear();
			merged.neighborChanged();
			assertEquals(List.of("neo:water"), merged.events, "only water arriving next to lava reacted");
		}
	}

	@Test void placingLavaNextToWaterRunsNeoForgesInteractionOnceAndSchedulesNothing() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("water", repaired.neighbourIs("water"));
			repaired.lavaNextTo("water");
			repaired.onPlace();
			assertEquals(List.of("neo:water"), repaired.events, "the same interaction neighborChanged runs, and no fluid tick");
			repaired.events.clear();
			repaired.neighborChanged();
			assertEquals(List.of("neo:water"), repaired.events);
			assertFalse(repaired.loaded(FORGE), "no MinecraftForge mod used its registry, so it was never even loaded");
		}
	}

	@Test void withNothingToReactToTheLavaStillFlows() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("water", repaired.neighbourIs("water"));
			repaired.lavaNextTo("stone");
			repaired.onPlace();
			repaired.neighborChanged();
			assertEquals(List.of("tick", "tick"), repaired.events);
		}
	}

	@Test void aMinecraftForgeModsInteractionRunsFromBothEntryPoints() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("water", repaired.neighbourIs("water"));
			repaired.forgeRule("honey", repaired.neighbourIs("honey"));
			repaired.lavaNextTo("honey");
			repaired.onPlace();
			assertEquals(List.of("forge:honey"), repaired.events, "NeoForge had nothing for honey; MinecraftForge's registry did");
			repaired.events.clear();
			repaired.neighborChanged();
			assertEquals(List.of("forge:honey"), repaired.events);
		}
		try (World merged = new World(false)) {
			merged.forgeRule("honey", merged.neighbourIs("honey"));
			merged.lavaNextTo("honey");
			merged.onPlace();
			merged.neighborChanged();
			assertEquals(List.of("tick", "tick"), merged.events, "control: as merged it ran from neither");
		}
	}

	@Test void oneLiquidNeverReactsTwice() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("water", repaired.neighbourIs("water"));
			repaired.forgeRule("water", repaired.neighbourIs("water"));   // MinecraftForge's copy of vanilla's rule
			repaired.lavaNextTo("water");
			repaired.onPlace();
			repaired.neighborChanged();
			assertEquals(List.of("neo:water", "neo:water"), repaired.events, "NeoForge's match returns at once; MinecraftForge's is never asked");
		}
	}

	@Test void aMinecraftForgeRegistryThatFailsToLinkIsLeftOutOnce() throws Exception {
		try (World repaired = new World(true)) {
			repaired.neoRule("water", repaired.neighbourIs("water"));
			int[] asked = {0};
			repaired.forgeRule("honey", (level, current, relative, state) -> {
				asked[0]++;
				throw new NoClassDefFoundError("net/minecraftforge/SomethingTheMergeLacks");
			});
			repaired.lavaNextTo("honey");
			repaired.onPlace();
			repaired.onPlace();
			assertEquals(List.of("tick", "tick"), repaired.events, "the lava flows as NeoForge decided, both times");
			assertEquals(1, asked[0], "asked once, then left out");
			repaired.lavaNextTo("water");
			repaired.events.clear();
			repaired.onPlace();
			assertEquals(List.of("neo:water"), repaired.events, "vanilla's and NeoForge mods' interactions are untouched");
		}
	}

	/** {@code (level, current, relative, state)}, as both families' HasFluidInteraction take it. */
	@FunctionalInterface
	private interface Rule {
		boolean test(Object level, Object current, Object relative, Object state);
	}

	/**
	 * The merged game around one lava source at (0, 64, 0) and the liquid east of it: LiquidBlock (merged or repaired),
	 * both registries (MinecraftForge's neutered as KernelBoot did, or repaired), the merged FluidState and Level, and the
	 * compiled game side (KernelFluidInteractions). Every fluid is a fixture answering both families' getFluidType().
	 */
	private static final class World implements AutoCloseable {
		final List<Object> events = new ArrayList<>();
		private final Loader loader;
		private final Object level, liquid, lavaState, pos, east;
		private final Map<String, Object> neoTypes = new HashMap<>(), forgeTypes = new HashMap<>(), states = new HashMap<>();
		private final Map<Object, Object> fluids = new HashMap<>();

		World(boolean repaired) throws Exception {
			Map<String, byte[]> defined = new HashMap<>();
			FluidInteractionsInjector injector = new FluidInteractionsInjector();
			byte[] liquidBlock = NativeCoremodParityTest.read(MERGED, LIQUID);
			byte[] neo = NativeCoremodParityTest.read(NEO_CARRIER, NEO);
			byte[] forge = NativeCoremodParityTest.read(FORGE_CARRIER, FORGE);
			if (repaired) {
				liquidBlock = injector.transform(FluidInteractionsInjector.LIQUID, liquidBlock, null);
				neo = injector.transform(FluidInteractionsInjector.NEO, neo, null);
				forge = injector.transform(FluidInteractionsInjector.FORGE, forge, null);
			} else {
				// What KernelBoot registered before the repair, and registers again with the repair off.
				forge = new MethodBodyNeuter().add(new MethodBodyNeuter.Target(FluidInteractionsInjector.FORGE, "canInteract", INTERACT, "control"))
						.transform(FluidInteractionsInjector.FORGE, forge, null);
			}
			defined.put(dotted(LIQUID), flowDirectionsOnly(liquidBlock));
			defined.put(dotted(NEO), emptyRegistry(neo, NEO));
			defined.put(dotted(FORGE), emptyRegistry(forge, FORGE));
			for (String name : List.of("net/minecraft/world/level/block/Block", "net/minecraft/world/level/block/state/BlockBehaviour",
					"net/minecraft/world/level/block/state/BlockState", "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
					"net/minecraft/world/level/block/state/StateHolder", FLUID_STATE, "net/minecraft/world/level/material/Fluid",
					"net/minecraft/world/level/material/FlowingFluid", LEVEL)) {
				defined.put(dotted(name), withoutInitializer(NativeCoremodParityTest.read(MERGED, name)));
			}
			defined.put("net.neoforged.neoforge.attachment.AttachmentHolder",
					withoutInitializer(NativeCoremodParityTest.read(NEO_CARRIER, "net/neoforged/neoforge/attachment/AttachmentHolder")));
			defined.put("net.neoforged.neoforge.fluids.FluidType",
					withoutInitializer(NativeCoremodParityTest.read(NEO_CARRIER, "net/neoforged/neoforge/fluids/FluidType")));
			defined.put("net.minecraftforge.fluids.FluidType",
					withoutInitializer(NativeCoremodParityTest.read(FORGE_CARRIER, "net/minecraftforge/fluids/FluidType")));
			defined.put("fixture.TestLevel", testLevel());
			defined.put("fixture.TestFluid", testFluid());
			defined.put("fixture.NoTags", noTags());
			loader = new Loader(defined);

			Class<?> testLevel = loader.loadClass("fixture.TestLevel");
			testLevel.getField("FLUIDS").set(null, fluids);
			testLevel.getField("EVENTS").set(null, events);
			level = unsafe().allocateInstance(testLevel);
			loader.loadClass("fixture.TestFluid").getField("NO_TAGS").set(null, unsafe().allocateInstance(loader.loadClass("fixture.NoTags")));
			for (String name : List.of("lava", "water", "honey", "stone")) fluid(name, name.equals("lava") || name.equals("water"));
			testLevel.getField("EMPTY").set(null, states.get("stone"));
			Class<?> posClass = loader.loadClass(dotted(POS));
			pos = posClass.getConstructor(int.class, int.class, int.class).newInstance(0, 64, 0);
			east = posClass.getMethod("east").invoke(pos);
			lavaState = states.get("lava");
			fluids.put(pos, lavaState);
			Class<?> liquidClass = loader.loadClass(dotted(LIQUID));
			liquid = unsafe().allocateInstance(liquidClass);
			field(liquidClass, "fluid").set(liquid, field(lavaState.getClass(), "owner").get(lavaState));
		}

		/** A fixture fluid {@code name}, with a NeoForge and a MinecraftForge type of its own, and its one state. */
		private void fluid(String name, boolean source) throws Exception {
			Object neoType = unsafe().allocateInstance(loader.loadClass("net.neoforged.neoforge.fluids.FluidType"));
			Object forgeType = unsafe().allocateInstance(loader.loadClass("net.minecraftforge.fluids.FluidType"));
			Class<?> fluidClass = loader.loadClass("fixture.TestFluid");
			Object fluid = unsafe().allocateInstance(fluidClass);
			fluidClass.getField("name").set(fluid, name);
			fluidClass.getField("neoType").set(fluid, neoType);
			fluidClass.getField("forgeType").set(fluid, forgeType);
			fluidClass.getField("source").setBoolean(fluid, source);
			Object state = unsafe().allocateInstance(loader.loadClass(dotted(FLUID_STATE)));
			field(state.getClass(), "owner").set(state, fluid);
			neoTypes.put(name, neoType);
			forgeTypes.put(name, forgeType);
			states.put(name, state);
		}

		/** Lava at the origin, {@code liquid} east of it. */
		void lavaNextTo(String liquid) {
			fluids.put(east, states.get(liquid));
		}

		/** Whether the liquid at {@code relative} is {@code name}, read the way the registries read it: its fluid state. */
		Rule neighbourIs(String name) {
			return (level, current, relative, state) -> fluids.get(relative) == states.get(name);
		}

		/** A NeoForge mod's interaction for lava next to {@code name}, through NeoForge's real addInteraction. */
		void neoRule(String name, Rule rule) throws Exception {
			register(NEO, neoTypes.get("lava"), "neo:" + name, rule);
		}

		/** A MinecraftForge mod's interaction for lava next to {@code name}, through MinecraftForge's real addInteraction. */
		void forgeRule(String name, Rule rule) throws Exception {
			register(FORGE, forgeTypes.get("lava"), "forge:" + name, rule);
		}

		private void register(String registry, Object lavaType, String event, Rule rule) throws Exception {
			Class<?> owner = loader.loadClass(dotted(registry));
			Class<?> predicate = loader.loadClass(dotted(registry) + "$HasFluidInteraction");
			Class<?> interaction = loader.loadClass(dotted(registry) + "$FluidInteraction");
			Class<?> information = loader.loadClass(dotted(registry) + "$InteractionInformation");
			Object test = Proxy.newProxyInstance(loader, new Class<?>[] {predicate}, (proxy, method, args) -> switch (method.getName()) {
				case "test" -> rule.test(args[0], args[1], args[2], args[3]);
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> event;
			});
			Object react = Proxy.newProxyInstance(loader, new Class<?>[] {interaction}, (proxy, method, args) -> {
				if (method.getName().equals("interact")) events.add(event);
				return method.getName().equals("hashCode") ? System.identityHashCode(proxy) : method.getName().equals("equals") ? proxy == args[0] : null;
			});
			Object info = information.getConstructor(predicate, interaction).newInstance(test, react);
			owner.getMethod("addInteraction", lavaType.getClass(), information).invoke(null, lavaType, info);
		}

		void onPlace() throws Exception {
			invoke("onPlace", lavaBlockState(), level, pos, lavaBlockState(), false);
		}

		void neighborChanged() throws Exception {
			invoke("neighborChanged", lavaBlockState(), level, pos, null, null, false);
		}

		private void invoke(String name, Object... args) throws Exception {
			Method method = Arrays.stream(liquid.getClass().getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
			method.setAccessible(true);
			try {
				method.invoke(liquid, args);
			} catch (InvocationTargetException thrown) {
				if (thrown.getCause() instanceof Exception e) throw e;
				throw new AssertionError(thrown.getCause());
			}
		}

		/** The lava source's block state: all onPlace reads of it is its fluid state. */
		private Object lavaBlockState() throws Exception {
			Object state = unsafe().allocateInstance(loader.loadClass("net.minecraft.world.level.block.state.BlockState"));
			field(state.getClass(), "fluidState").set(state, lavaState);
			return state;
		}

		boolean loaded(String internalName) {
			return loader.loaded(dotted(internalName));
		}

		@Override public void close() throws Exception { loader.close(); }
	}

	/** The compiled game side, the staged game and its libraries, with some classes defined from given bytes. */
	private static final class Loader extends URLClassLoader {
		private final Map<String, byte[]> defined;

		Loader(Map<String, byte[]> defined) throws Exception {
			super(urls(), ClassLoader.getPlatformClassLoader());
			this.defined = defined;
		}

		private static URL[] urls() throws Exception {
			assumeTrue(Runtime.version().feature() >= 25, "the merged game is class-file 69, which only Java 25 links");
			for (Path jar : List.of(MERGED, NEO_CARRIER, FORGE_CARRIER)) assumeTrue(Files.isRegularFile(jar), jar + " absent");
			List<URL> urls = new ArrayList<>(StagedGameClassLoader.urls());
			// The game side logs through ForbricLog, a boot-side class.
			urls.add(Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL());
			return urls.toArray(URL[]::new);
		}

		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = defined.get(name);
			return bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.findClass(name);
		}

		boolean loaded(String name) {
			return findLoadedClass(name) != null;
		}
	}

	// ------------------------------------------------------------------------------------------------ fixtures

	/** LiquidBlock with its initializer cut to the one static both registries read: POSSIBLE_FLOW_DIRECTIONS. */
	private static byte[] flowDirectionsOnly(byte[] bytes) {
		ClassNode node = node(bytes);
		MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		clinit.instructions.clear();
		clinit.tryCatchBlocks.clear();
		clinit.localVariables = null;
		for (String direction : List.of("DOWN", "SOUTH", "NORTH", "EAST", "WEST")) {
			clinit.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/core/Direction", direction, "Lnet/minecraft/core/Direction;"));
		}
		String object = "Ljava/lang/Object;";
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableList", "of",
				"(" + object.repeat(5) + ")Lcom/google/common/collect/ImmutableList;", false));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, LIQUID, "POSSIBLE_FLOW_DIRECTIONS", "Lcom/google/common/collect/ImmutableList;"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** A registry whose initializer only creates its (empty) map: vanilla's rules need NeoForgeMod/ForgeMod, a game's. */
	private static byte[] emptyRegistry(byte[] bytes, String owner) {
		ClassNode node = node(bytes);
		MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		clinit.instructions.clear();
		clinit.tryCatchBlocks.clear();
		clinit.localVariables = null;
		clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
		clinit.instructions.add(new InsnNode(Opcodes.DUP));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
		clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "INTERACTIONS", "Ljava/util/Map;"));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** A Level whose fluid states come from FLUIDS (EMPTY elsewhere) and whose fluid ticks are recorded in EVENTS. */
	private static byte[] testLevel() {
		String name = "fixture/TestLevel";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, LEVEL, null);
		for (String field : List.of("FLUIDS:Ljava/util/Map;", "EVENTS:Ljava/util/List;", "EMPTY:Ljava/lang/Object;")) {
			cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.split(":")[0], field.split(":")[1], null, null).visitEnd();
		}
		MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "getFluidState", "(L" + POS + ";)L" + FLUID_STATE + ";", null, null);
		get.visitCode();
		get.visitFieldInsn(Opcodes.GETSTATIC, name, "FLUIDS", "Ljava/util/Map;");
		get.visitVarInsn(Opcodes.ALOAD, 1);
		get.visitFieldInsn(Opcodes.GETSTATIC, name, "EMPTY", "Ljava/lang/Object;");
		get.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		get.visitTypeInsn(Opcodes.CHECKCAST, FLUID_STATE);
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(0, 0);
		get.visitEnd();
		MethodVisitor tick = cw.visitMethod(Opcodes.ACC_PUBLIC, "scheduleTick", "(L" + POS + ";Lnet/minecraft/world/level/material/Fluid;I)V", null, null);
		tick.visitCode();
		tick.visitFieldInsn(Opcodes.GETSTATIC, name, "EVENTS", "Ljava/util/List;");
		tick.visitLdcInsn("tick");
		tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
		tick.visitInsn(Opcodes.POP);
		tick.visitInsn(Opcodes.RETURN);
		tick.visitMaxs(0, 0);
		tick.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A fluid answering both families' getFluidType() from its fields, with a tick delay and no tags. */
	private static byte[] testFluid() {
		String name = "fixture/TestFluid";
		String neoType = "net/neoforged/neoforge/fluids/FluidType", forgeType = "net/minecraftforge/fluids/FluidType";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "net/minecraft/world/level/material/FlowingFluid", null);
		for (String field : List.of("name:Ljava/lang/String;", "neoType:Ljava/lang/Object;", "forgeType:Ljava/lang/Object;", "source:Z")) {
			cw.visitField(Opcodes.ACC_PUBLIC, field.split(":")[0], field.split(":")[1], null, null).visitEnd();
		}
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "NO_TAGS", "Ljava/lang/Object;", null, null).visitEnd();
		for (String[] type : List.of(new String[] {"neoType", neoType}, new String[] {"forgeType", forgeType})) {
			MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "getFluidType", "()L" + type[1] + ";", null, null);
			m.visitCode();
			m.visitVarInsn(Opcodes.ALOAD, 0);
			m.visitFieldInsn(Opcodes.GETFIELD, name, type[0], "Ljava/lang/Object;");
			m.visitTypeInsn(Opcodes.CHECKCAST, type[1]);
			m.visitInsn(Opcodes.ARETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}
		MethodVisitor source = cw.visitMethod(Opcodes.ACC_PUBLIC, "isSource", "(L" + FLUID_STATE + ";)Z", null, null);
		source.visitCode();
		source.visitVarInsn(Opcodes.ALOAD, 0);
		source.visitFieldInsn(Opcodes.GETFIELD, name, "source", "Z");
		source.visitInsn(Opcodes.IRETURN);
		source.visitMaxs(0, 0);
		source.visitEnd();
		MethodVisitor delay = cw.visitMethod(Opcodes.ACC_PUBLIC, "getTickDelay", "(Lnet/minecraft/world/level/LevelReader;)I", null, null);
		delay.visitCode();
		delay.visitIntInsn(Opcodes.BIPUSH, 30);
		delay.visitInsn(Opcodes.IRETURN);
		delay.visitMaxs(0, 0);
		delay.visitEnd();
		MethodVisitor holder = cw.visitMethod(Opcodes.ACC_PUBLIC, "builtInRegistryHolder", "()Lnet/minecraft/core/Holder$Reference;", null, null);
		holder.visitCode();
		holder.visitFieldInsn(Opcodes.GETSTATIC, name, "NO_TAGS", "Ljava/lang/Object;");
		holder.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/core/Holder$Reference");
		holder.visitInsn(Opcodes.ARETURN);
		holder.visitMaxs(0, 0);
		holder.visitEnd();
		MethodVisitor string = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
		string.visitCode();
		string.visitVarInsn(Opcodes.ALOAD, 0);
		string.visitFieldInsn(Opcodes.GETFIELD, name, "name", "Ljava/lang/String;");
		string.visitInsn(Opcodes.ARETURN);
		string.visitMaxs(0, 0);
		string.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A registry holder in no tag: the bubble-column check onPlace makes of the placed liquid's fluid. */
	private static byte[] noTags() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/NoTags", null, "net/minecraft/core/Holder$Reference", null);
		MethodVisitor is = cw.visitMethod(Opcodes.ACC_PUBLIC, "is", "(Lnet/minecraft/tags/TagKey;)Z", null, null);
		is.visitCode();
		is.visitInsn(Opcodes.ICONST_0);
		is.visitInsn(Opcodes.IRETURN);
		is.visitMaxs(0, 0);
		is.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private static List<String> canInteractOwners(MethodNode method) {
		List<String> owners = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("canInteract") && call.desc.equals(INTERACT)) owners.add(call.owner);
		}
		return owners;
	}

	private static List<Integer> opcodes(MethodNode method) {
		return real(method).stream().map(AbstractInsnNode::getOpcode).toList();
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name));
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(node.name + "." + name + desc));
	}

	private static byte[] withoutInitializer(byte[] bytes) {
		ClassNode node = node(bytes);
		node.methods.removeIf(m -> m.name.equals("<clinit>"));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static String dotted(String internalName) {
		return internalName.replace('/', '.');
	}

	private static Field field(Class<?> owner, String name) throws Exception {
		for (Class<?> at = owner; at != null; at = at.getSuperclass()) {
			try {
				Field f = at.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException notHere) {
				// declared further up
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (sun.misc.Unsafe) f.get(null);
	}
}
