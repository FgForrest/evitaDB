/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.expression.evaluate.object.accessor;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Singleton registry for {@link ObjectPropertyAccessor}s and {@link ObjectElementAccessor}s. Accessors are registered
 * by the object type they support. The registry supports type hierarchy traversal, meaning if an accessor
 * is not found for the exact type, it will search through superclasses and interfaces.
 *
 * This registry is thread-safe and uses caching for optimal lookup performance.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ObjectAccessorRegistry {

	private static ObjectAccessorRegistry INSTANCE;

	/**
	 * Map of property accessors keyed by the exact type they are registered for.
	 */
	private final Map<Class<?>, ObjectPropertyAccessor> propertyAccessors;

	/**
	 * Map of element accessors keyed by the exact type they are registered for.
	 */
	private final Map<Class<?>, ObjectElementAccessor> elementAccessors;

	/**
	 * Cache for property accessor lookups that includes type hierarchy resolution.
	 */
	private final Map<Class<?>, Optional<ObjectPropertyAccessor>> propertyAccessorCache;

	/**
	 * Cache for element accessor lookups that includes type hierarchy resolution.
	 */
	private final Map<Class<?>, Optional<ObjectElementAccessor>> elementAccessorCache;

	/**
	 * Returns the singleton instance of the registry.
	 *
	 * @return the singleton instance
	 */
	@Nonnull
	public static ObjectAccessorRegistry getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new ObjectAccessorRegistry();
		}
		return INSTANCE;
	}

	private ObjectAccessorRegistry() {
		final List<ObjectPropertyAccessor> foundPropertyAccessors = ServiceLoader.load(ObjectPropertyAccessor.class)
			.stream()
			.map(Provider::get)
			.toList();
		final List<ObjectElementAccessor> foundElementAccessors = ServiceLoader.load(ObjectElementAccessor.class)
			.stream()
			.map(Provider::get)
			.toList();

		this.propertyAccessors = createHashMap(foundPropertyAccessors.size());
		this.elementAccessors = createHashMap(foundElementAccessors.size());
		this.propertyAccessorCache = createConcurrentHashMap(Math.round(foundPropertyAccessors.size() * 1.5f));
		this.elementAccessorCache = createConcurrentHashMap(Math.round(foundElementAccessors.size() * 1.5f));

		foundPropertyAccessors.forEach(this::registerPropertyAccessor);
		foundElementAccessors.forEach(this::registerElementAccessor);
	}

	/**
	 * Registers a property accessor for the specified type. Only one accessor can be registered
	 * per type - attempting to register a duplicate will result in an error.
	 *
	 * @param accessor the accessor to register
	 * @throws IllegalStateException if an accessor is already registered for the given type
	 */
	public void registerPropertyAccessor(@Nonnull ObjectPropertyAccessor accessor) {
		for (final Class<?> supportedType : accessor.getSupportedTypes()) {
			final ObjectPropertyAccessor existing = this.propertyAccessors.putIfAbsent(supportedType, accessor);
			Assert.isTrue(
				existing == null,
				"PropertyAccessor already registered for type `" + supportedType.getName() + "`."
			);
		}
		// invalidate cache as new accessor may affect lookups
		this.propertyAccessorCache.clear();
	}

	/**
	 * Registers an element accessor for the specified type. Only one accessor can be registered
	 * per type - attempting to register a duplicate will result in an error.
	 *
	 * @param accessor the accessor to register
	 * @throws IllegalStateException if an accessor is already registered for the given type
	 */
	public void registerElementAccessor(@Nonnull ObjectElementAccessor accessor) {
		for (final Class<?> supportedType : accessor.getSupportedTypes()) {
			final ObjectElementAccessor existing = this.elementAccessors.putIfAbsent(supportedType, accessor);
			Assert.isTrue(
				existing == null,
				"ElementAccessor already registered for type `" + supportedType.getName() + "`."
			);
		}
		// invalidate cache as new accessor may affect lookups
		this.elementAccessorCache.clear();
	}

	/**
	 * Gets the property accessor for the specified type. If no accessor is registered for the exact type,
	 * the registry will search through the type hierarchy (superclasses and interfaces).
	 *
	 * @param type the type to get the accessor for
	 * @param <T> the type parameter
	 * @return an optional containing the accessor if found, empty otherwise
	 */
	@Nonnull
	public <T extends Serializable> Optional<ObjectPropertyAccessor> getPropertyAccessor(@Nonnull Class<T> type) {
		return this.propertyAccessorCache.computeIfAbsent(
			type,
			t -> findAccessorInHierarchy(
				t,
				// todo lho use instanceof like bellow?
				this.propertyAccessors::get
			)
		);
	}

	/**
	 * Gets the element accessor for the specified type. If no accessor is registered for the exact type,
	 * the registry will search through the type hierarchy (superclasses and interfaces).
	 *
	 * @param type the type to get the accessor for
	 * @param <T> the type parameter
	 * @return an optional containing the accessor if found, empty otherwise
	 */
	@Nonnull
	public <T extends Serializable> Optional<ObjectElementAccessor> getElementAccessor(@Nonnull Class<T> type) {
		return this.elementAccessorCache.computeIfAbsent(
			type,
			t -> findAccessorInHierarchy(
				t,
				c -> {
					// todo lho doesn't this eliminate the need for the hierarchy lookup?
					for (final Map.Entry<Class<?>, ObjectElementAccessor> entry : this.elementAccessors.entrySet()) {
						if (entry.getKey().isAssignableFrom(c)) {
							return entry.getValue();
						}
					}
					return null;
				}
			)
		);
	}

	/**
	 * Searches for an accessor in the type hierarchy. It searches the type super classes and all interfaces hierarchy
	 * breadth-first.
	 *
	 * @param startType the type to search for
	 * @param accessorLookup the lookup function to use for finding a specific accessor in the hierarchy
	 * @return an optional containing the accessor if found
	 */
	@Nonnull
	private static <A> Optional<A> findAccessorInHierarchy(
		@Nonnull Class<?> startType,
		@Nonnull Function<Class<?>, A> accessorLookup
	) {
		final Set<Class<?>> visited = createHashSet(5);
		final Queue<Class<?>> queue = new LinkedList<>();

		queue.add(startType);
		visited.add(startType);

		while (!queue.isEmpty()) {
			final Class<?> current = queue.poll();

			// 1. Check if we have an accessor for this specific class/interface
			final A accessor = accessorLookup.apply(current);
			if (accessor != null) {
				return Optional.of(accessor);
			}

			// 2. Add Superclass to queue (Next Layer)
			Class<?> superclass = current.getSuperclass();
			if (superclass != null && visited.add(superclass)) {
				queue.add(superclass);
			}

			// 3. Add Interfaces to queue (Next Layer)
			for (Class<?> iface : current.getInterfaces()) {
				if (visited.add(iface)) {
					queue.add(iface);
				}
			}
		}

		return Optional.empty(); // No accessor found
	}
}
