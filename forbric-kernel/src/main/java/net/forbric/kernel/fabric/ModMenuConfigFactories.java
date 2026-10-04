/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.fabric;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.forbric.kernel.util.ForbricLog;

/**
 * Fabric mods' config screen factories, read from their {@code "modmenu"} entrypoints the way Mod Menu reads them.
 *
 * <p>Used when Mod Menu itself is not installed (with it, the kernel asks Mod Menu). The only place a Fabric mod says
 * it has a config screen is a {@code "modmenu"} entrypoint implementing Mod Menu's {@code ModMenuApi}; the kernel's
 * stand-in for that interface makes those classes linkable, and this reads them. Mod Menu's own reading is the
 * contract, because it is what every one of these mods was written and tested against:
 *
 * <ul>
 *   <li>per entrypoint, in declaration order, the providing mod's own {@code getModConfigScreenFactory()} is stored
 *       under its mod id with {@code put} — unless it is the interface's default, which produces no screen;</li>
 *   <li>then every entrypoint's {@code getProvidedConfigScreenFactories()} — a config library's way of supplying
 *       screens for the mods built on it — is merged with {@code putIfAbsent}, AFTER all of the mods' own, so a mod's
 *       own factory always beats one a library offers for it;</li>
 *   <li>an entrypoint that throws while being built or asked is that mod's loss alone: logged once and skipped,
 *       never costing another mod its button or the player the screen.</li>
 * </ul>
 *
 * <p>Two deliberate differences. Read once, lazily, on the first question — after every mod's client initializer has
 * run, which is later than Mod Menu reads, never earlier. And whether a mod HAS a config is decided without building
 * its screen: a factory is present when the mod's class OVERRIDES {@code getModConfigScreenFactory} and returns one.
 * Mod Menu tells its own default apart with {@code instanceof NullScreenFactory}, a class of its internals that the
 * stand-in does not have; the override is the same fact asked of the mod rather than of the factory. Update checkers
 * and modpack badges are never asked for — the kernel checks nothing over the network and draws no badges.
 *
 * <p>Boot-side and reflective on purpose: it names no game type, so it is testable off-game against any interface of
 * the same shape, and the same code serves the stand-in and a real Mod Menu API shipped by some other jar.
 */
public final class ModMenuConfigFactories {
	/** The entrypoint key Mod Menu reads. */
	public static final String KEY = "modmenu";

	private final Map<String, Object> factories;
	private final Method create;
	private final int entrypoints;
	private final int broken;

	private ModMenuConfigFactories(Map<String, Object> factories, Method create, int entrypoints, int broken) {
		this.factories = factories;
		this.create = create;
		this.entrypoints = entrypoints;
		this.broken = broken;
	}

	/**
	 * Reads every {@code "modmenu"} entrypoint of type {@code api}.
	 *
	 * @param api the {@code ModMenuApi} interface as the game class loader defined it — the stand-in, or a real copy
	 * @throws NoSuchMethodException when {@code api} is not shaped like Mod Menu's API at all
	 */
	public static ModMenuConfigFactories read(FabricLoader fabric, Class<?> api) throws NoSuchMethodException {
		Method own = api.getMethod("getModConfigScreenFactory");
		Method provided = api.getMethod("getProvidedConfigScreenFactories");
		Method create = factoryMethod(own.getReturnType());

		Map<String, Object> factories = new LinkedHashMap<>();
		List<Object> implementations = new ArrayList<>();
		List<String> implementers = new ArrayList<>();
		List<String> definitions = new ArrayList<>();
		Set<String> failed = new LinkedHashSet<>();
		List<? extends EntrypointContainer<?>> containers = fabric.getEntrypointContainers(KEY, api);
		for (EntrypointContainer<?> container : containers) {
			String modId = modId(container);
			String definition = container.getDefinition();
			try {
				Object implementation = container.getEntrypoint();
				// put, not putIfAbsent: a mod declaring two entrypoints ends with its last one's, as under Mod Menu.
				if (overrides(implementation.getClass(), own)) factories.put(modId, own.invoke(implementation));
				implementations.add(implementation);
				implementers.add(modId);
				definitions.add(definition);
			} catch (Throwable t) {
				if (failed.add(modId + " " + definition)) broken(modId, definition, "building it or asking it for its factory", t);
			}
		}
		for (int i = 0; i < implementations.size(); i++) {
			try {
				if (!(provided.invoke(implementations.get(i)) instanceof Map<?, ?> offered)) continue;
				for (Map.Entry<?, ?> entry : offered.entrySet()) {
					if (entry.getKey() instanceof String id) factories.putIfAbsent(id, entry.getValue());
				}
			} catch (Throwable t) {
				String key = implementers.get(i) + " " + definitions.get(i);
				if (failed.add(key)) broken(implementers.get(i), definitions.get(i), "asking it for the factories it provides", t);
			}
		}
		// A null stored with put means "no screen" under Mod Menu too: it answers hasConfigScreen with a null check.
		factories.values().removeIf(java.util.Objects::isNull);
		return new ModMenuConfigFactories(Collections.unmodifiableMap(factories), create, containers.size(), failed.size());
	}

	/** Whether {@code modId} has a config screen factory. Builds nothing. */
	public boolean has(String modId) {
		return modId != null && factories.containsKey(modId);
	}

	/**
	 * Builds {@code modId}'s config screen over {@code parent}, or returns {@code null} when it has no factory or its
	 * factory produced no screen. A factory that throws throws its own exception, not a reflective wrapper.
	 */
	public Object create(String modId, Object parent) throws Exception {
		Object factory = modId == null ? null : factories.get(modId);
		if (factory == null) return null;
		try {
			return create.invoke(factory, parent);
		} catch (InvocationTargetException thrown) {
			if (thrown.getCause() instanceof Exception e) throw e;
			if (thrown.getCause() instanceof Error e) throw e;
			throw thrown;
		}
	}

	/** Mod ids with a factory, own ones first in entrypoint order, then provided ones. */
	public Set<String> modIds() {
		return factories.keySet();
	}

	/** {@code "modmenu"} entrypoints of the API type that were read. */
	public int entrypoints() {
		return entrypoints;
	}

	/** Entrypoints skipped because they threw. */
	public int broken() {
		return broken;
	}

	/**
	 * Whether {@code type} declares its own {@code method} somewhere below the interface.
	 *
	 * <p>Asked of the public member set, so an override in an abstract base class a config library provides counts,
	 * and a re-abstraction cannot be mistaken for one: a constructed class has a concrete method.
	 */
	static boolean overrides(Class<?> type, Method method) {
		try {
			Method found = type.getMethod(method.getName(), method.getParameterTypes());
			return found.getDeclaringClass() != method.getDeclaringClass() && !Modifier.isAbstract(found.getModifiers());
		} catch (NoSuchMethodException | SecurityException e) {
			return false;
		}
	}

	/** {@code ConfigScreenFactory}'s single abstract method — {@code create(Screen)} — found without naming Screen. */
	private static Method factoryMethod(Class<?> factory) throws NoSuchMethodException {
		for (Method method : factory.getMethods()) {
			if (method.getName().equals("create") && method.getParameterCount() == 1
					&& Modifier.isAbstract(method.getModifiers())) {
				return method;
			}
		}
		throw new NoSuchMethodException(factory.getName() + ".create(Screen)");
	}

	private static String modId(EntrypointContainer<?> container) {
		try {
			return container.getProvider().getMetadata().getId();
		} catch (RuntimeException e) {
			return "<unknown>";
		}
	}

	private static void broken(String modId, String definition, String doing, Throwable t) {
		Throwable cause = t instanceof InvocationTargetException wrapped && wrapped.getCause() != null ? wrapped.getCause() : t;
		ForbricLog.warn("[Forbric/ModConfig] %s's Mod Menu entrypoint %s threw while %s (%s) — it is skipped, as Mod "
				+ "Menu skips a broken one; no other mod's config is affected", modId, definition, doing, String.valueOf(cause));
	}
}
