/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Lava placed next to water becomes obsidian again, flowing lava that reaches water becomes cobblestone, and lava over
 * soul soil beside blue ice becomes basalt — with no mods installed, as in vanilla.
 *
 * <p>Vanilla runs those rules from {@code LiquidBlock.shouldSpreadLiquid}, at both of its callers: {@code onPlace} (the
 * lava block was just set — a bucket, a command, or a flowing lava block the fluid tick just spread) and
 * {@code neighborChanged} (something next to it changed — water arriving). Both carriers replaced that call with their
 * own {@code FluidInteractionRegistry.canInteract}, which holds vanilla's two rules first and then the ones mods add.
 * The merge kept MinecraftForge's {@code onPlace} and NeoForge's {@code neighborChanged}, and the kernel neutered
 * MinecraftForge's {@code canInteract} to {@code return false} (its first lookup once ended in an AbstractMethodError,
 * before the merged fluids had MinecraftForge's {@code getFluidType()}). So only water arriving next to lava reacted:
 * a lava bucket emptied beside water stayed lava and was then washed over, a cobblestone generator never made a
 * block, and a basalt generator neither. Carpet's fluid adapter happened to cover it whenever Carpet was installed.
 *
 * <p>Three edits, each on the reviewed shape only:
 * <ul>
 *   <li>{@code LiquidBlock.onPlace} asks NeoForge's registry, as {@code neighborChanged} already does — when the merged
 *       class has exactly one MinecraftForge {@code canInteract} in {@code onPlace} and exactly one NeoForge one in
 *       {@code neighborChanged}. Both entry points then run vanilla's rules, in vanilla's order, and every rule a
 *       NeoForge mod added.</li>
 *   <li>NeoForge's {@code canInteract} ends {@code return KernelFluidInteractions.minecraftForge(level, pos)} where it
 *       ended {@code return false}: when no rule of its own matched, MinecraftForge's registry gets the same question,
 *       so a MinecraftForge mod's rule runs from both entry points too. Its own match still returns at once, so one
 *       placement never reacts twice.</li>
 *   <li>MinecraftForge's {@code addInteraction} tells the kernel its registry is in use. Until something initializes
 *       that class — a MinecraftForge mod adding a rule, or asking — the kernel never asks it, so a game without one
 *       runs NeoForge's registry alone, exactly as before for {@code neighborChanged}.</li>
 * </ul>
 * MinecraftForge's {@code canInteract} itself is no longer neutered: the merged {@code FluidState} implements
 * {@code IForgeFluidState}, and every merged fluid answers MinecraftForge's {@code getFluidType()} (the per-class bridge
 * and {@code ForeignFluidTypeInjector}), so its lookup works. {@code -Dforbric.fluidInteractions=off} leaves all three
 * classes as merged and KernelBoot puts the neuter back, which reproduces the bug.
 */
public final class FluidInteractionsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fluidInteractions";
	static final String LIQUID = "net.minecraft.world.level.block.LiquidBlock";
	static final String NEO = ForeignType.FLUID_INTERACTION_REGISTRY.binary(Ecosystem.NEOFORGE);
	static final String FORGE = ForeignType.FLUID_INTERACTION_REGISTRY.binary(Ecosystem.FORGE);
	static final String NEO_INTERNAL = ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.NEOFORGE);
	static final String FORGE_INTERNAL = ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.FORGE);
	static final String CAN_INTERACT = "canInteract";
	static final String INTERACT_DESC = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z";
	static final String ADD_INTERACTION = "addInteraction";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFluidInteractions";
	/** Asked at the end of NeoForge's canInteract; same descriptor, so the two return values are interchangeable. */
	static final String FORGE_LEG = "minecraftForge";
	/** Called first thing in MinecraftForge's addInteraction. */
	static final String IN_USE = "minecraftForgeInUse";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fluid-interactions"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("liquid placement left asking MinecraftForge's neutered registry with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(LIQUID, AnchorSet.Severity.REQUIRED,
						"lava placed or flowing next to water stays lava: no obsidian, no cobblestone, no basalt"),
				new AnchorSet.Anchor(NEO, AnchorSet.Severity.REQUIRED,
						"a MinecraftForge mod's fluid interactions never run"),
				new AnchorSet.Anchor(FORGE, AnchorSet.Severity.REQUIRED,
						"MinecraftForge's registry is never asked, so a MinecraftForge mod's fluid interactions never run"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean liquid = LIQUID.equals(className), neo = NEO.equals(className), forge = FORGE.equals(className);
		if (!liquid && !neo && !forge) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int changed = liquid ? liquidBlock(node) : neo ? neoForge(node) : minecraftForge(node);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		if (liquid) {
			ForbricLog.info("[Forbric/Fluid] LiquidBlock.onPlace asks NeoForge's FluidInteractionRegistry, as neighborChanged does — "
					+ "it asked MinecraftForge's neutered one, so lava placed or flowing next to water never became obsidian or "
					+ "cobblestone, and lava over soul soil beside blue ice never became basalt");
		} else if (neo) {
			ForbricLog.info("[Forbric/Fluid] NeoForge's FluidInteractionRegistry.canInteract asks MinecraftForge's registry when "
					+ "none of its own interactions matched, once a MinecraftForge mod uses that registry");
		} else {
			ForbricLog.info("[Forbric/Fluid] MinecraftForge's FluidInteractionRegistry.addInteraction tells the kernel its registry "
					+ "is in use, so NeoForge's canInteract asks it after its own interactions");
		}
		return writer.toByteArray();
	}

	/** onPlace's one MinecraftForge canInteract becomes NeoForge's, the call neighborChanged makes. */
	static int liquidBlock(ClassNode liquid) {
		MethodNode onPlace = method(liquid, "onPlace"), neighborChanged = method(liquid, "neighborChanged");
		if (onPlace == null || neighborChanged == null) return declined("LiquidBlock has no onPlace or no neighborChanged");
		List<MethodInsnNode> placeForge = calls(onPlace, FORGE_INTERNAL), placeNeo = calls(onPlace, NEO_INTERNAL);
		List<MethodInsnNode> neighborNeo = calls(neighborChanged, NEO_INTERNAL), neighborForge = calls(neighborChanged, FORGE_INTERNAL);
		if (placeForge.isEmpty() && placeNeo.size() == 1 && neighborNeo.size() == 1 && neighborForge.isEmpty()) return 0;   // already done
		if (placeForge.size() != 1 || !placeNeo.isEmpty() || neighborNeo.size() != 1 || !neighborForge.isEmpty()) {
			// MinecraftForge's or NeoForge's own LiquidBlock asks one registry from both, or none: nothing to bring into line.
			return declined("onPlace asks " + placeForge.size() + " MinecraftForge and " + placeNeo.size()
					+ " NeoForge canInteract, neighborChanged " + neighborForge.size() + " and " + neighborNeo.size());
		}
		placeForge.getFirst().owner = NEO_INTERNAL;
		return 1;
	}

	/** NeoForge's {@code return false} — no interaction of its own matched — asks MinecraftForge's registry instead. */
	static int neoForge(ClassNode registry) {
		MethodNode canInteract = staticMethod(registry, CAN_INTERACT, INTERACT_DESC);
		if (canInteract == null) return declined("NeoForge's FluidInteractionRegistry has no static canInteract(Level, BlockPos)");
		if (callsRuntime(canInteract)) return 0;   // already done
		List<AbstractInsnNode> unmatched = new ArrayList<>();
		int interactions = 0;
		for (AbstractInsnNode insn : canInteract.instructions) {
			if (insn.getOpcode() == Opcodes.ICONST_0 && next(insn) != null && next(insn).getOpcode() == Opcodes.IRETURN) unmatched.add(insn);
			if (insn instanceof MethodInsnNode call && call.name.equals("interact") && call.owner.equals(NEO_INTERNAL + "$FluidInteraction")) interactions++;
		}
		if (unmatched.size() != 1 || interactions != 1) {
			return declined("NeoForge's canInteract has " + unmatched.size() + " `return false` and " + interactions + " interact call(s)");
		}
		InsnList ask = new InsnList();
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new VarInsnNode(Opcodes.ALOAD, 1));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, FORGE_LEG, INTERACT_DESC, false));
		canInteract.instructions.insert(unmatched.getFirst(), ask);
		canInteract.instructions.remove(unmatched.getFirst());
		return 1;
	}

	/** MinecraftForge's addInteraction reports in before it adds: from here on its registry is worth asking. */
	static int minecraftForge(ClassNode registry) {
		MethodNode add = null;
		for (MethodNode m : registry.methods) {
			if (m.name.equals(ADD_INTERACTION) && (m.access & Opcodes.ACC_STATIC) != 0 && m.desc.endsWith(")V")) {
				if (add != null) return declined("MinecraftForge's FluidInteractionRegistry has more than one addInteraction");
				add = m;
			}
		}
		if (add == null || staticMethod(registry, CAN_INTERACT, INTERACT_DESC) == null) {
			return declined("MinecraftForge's FluidInteractionRegistry has no static addInteraction or canInteract");
		}
		if (callsRuntime(add)) return 0;   // already done
		add.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, IN_USE, "()V", false));
		return 1;
	}

	private static MethodNode method(ClassNode node, String name) {
		MethodNode found = null;
		for (MethodNode m : node.methods) {
			if (!m.name.equals(name)) continue;
			if (found != null) return null;
			found = m;
		}
		return found;
	}

	private static MethodNode staticMethod(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_STATIC) != 0) return m;
		}
		return null;
	}

	/** The {@code invokestatic <owner>.canInteract(Level, BlockPos)} calls in {@code method}. */
	private static List<MethodInsnNode> calls(MethodNode method, String owner) {
		List<MethodInsnNode> found = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner)
					&& call.name.equals(CAN_INTERACT) && call.desc.equals(INTERACT_DESC)) found.add(call);
		}
		return found;
	}

	private static boolean callsRuntime(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return true;
		}
		return false;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Fluid] left the fluid interactions as merged: %s — lava placed or flowing next to water may "
				+ "not react as in vanilla, or a mod's fluid interactions may not run", reason);
		return -1;
	}
}
