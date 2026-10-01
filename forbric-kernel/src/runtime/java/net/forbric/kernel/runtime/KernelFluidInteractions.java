/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * MinecraftForge's fluid-interaction registry, asked after NeoForge's (FluidInteractionsInjector).
 *
 * <p>Both merged {@code LiquidBlock} entry points ask NeoForge's {@code FluidInteractionRegistry}, which holds vanilla's
 * rules and every rule a NeoForge mod added. A MinecraftForge mod adds its rules to its own family's registry, which
 * nothing in the merged game asks; NeoForge's {@code canInteract} now ends here instead of in {@code return false}.
 *
 * <p>Asked only once MinecraftForge's registry is in use — its {@code addInteraction} has run, which its own class
 * initializer does for vanilla's two rules the moment anything first touches the class. A game without a MinecraftForge
 * mod that uses it never loads the class and never pays for a second walk. When it is in use its vanilla rules are
 * asked again after NeoForge's identical ones missed, and miss again; a MinecraftForge mod's rule is what can match.
 *
 * <p>A registry that does not link — a MinecraftForge class or member the merged game lacks — is reported once and then
 * left out, so liquids keep NeoForge's answer rather than failing every placement. Anything a mod's own rule throws is
 * the mod's, and propagates as it would on MinecraftForge.
 */
public final class KernelFluidInteractions {
	private static volatile boolean inUse;
	private static final AtomicBoolean BROKE = new AtomicBoolean();

	private KernelFluidInteractions() {
	}

	/** Called first thing in MinecraftForge's {@code addInteraction}. */
	public static void minecraftForgeInUse() {
		if (inUse) return;
		inUse = true;
		ForbricLog.info("[Forbric/Fluid] MinecraftForge's FluidInteractionRegistry is in use — a liquid placed or updated asks it "
				+ "after NeoForge's, when none of NeoForge's interactions matched");
	}

	/** NeoForge's {@code canInteract} answer when none of its own interactions matched: MinecraftForge's, or false. */
	public static boolean minecraftForge(Level level, BlockPos pos) {
		if (!inUse || BROKE.get()) return false;
		try {
			return net.minecraftforge.fluids.FluidInteractionRegistry.canInteract(level, pos);
		} catch (LinkageError broken) {
			if (BROKE.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Fluid] MinecraftForge's FluidInteractionRegistry (or a rule in it) failed to link on the "
						+ "merged game — MinecraftForge mods' fluid interactions are left out from now on; vanilla's and NeoForge "
						+ "mods' still run", broken);
			}
			return false;
		}
	}
}
