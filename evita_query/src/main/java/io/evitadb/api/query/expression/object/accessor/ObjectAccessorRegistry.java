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

package io.evitadb.api.query.expression.object.accessor;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

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

	/**
	 * Holder that defers construction to first access. Class initialization is serialized by the JVM, so the
	 * instance is published safely without locking on every lookup - a plain lazily assigned static field is
	 * neither atomic nor visible across threads, and a racing reader would build a second registry and overwrite
	 * the first, silently discarding accessors registered on it in the meantime.
	 */
	private static final class Holder {
		private static final ObjectAccessorRegistry INSTANCE = new ObjectAccessorRegistry();
	}

	/**
	 * Map of property accessors keyed by the exact type they are registered for.
	 */
	private final Map<Class<?>, ObjectPropertyAccessor> propertyAccessors;

	/**
	 * Map of element accessors keyed by the exact type they are registered for.
	 */
	private final Map<Class<?>, ObjectElementAccessor> elementAccessors;

	/**
	 * Map of method accessors keyed by the exact type they are registered for.
	 */
	private final Map<Class<?>, ObjectMethodAccessor> methodAccessors;

	/**
	 * Cache for property accessor lookups that includes type hierarchy resolution.
	 */
	private final Map<Class<?>, Optional<ObjectPropertyAccessor>> propertyAccessorCache;

	/**
	 * Cache for element accessor lookups that includes type hierarchy resolution.
	 */
	private final Map<Class<?>, Optional<ObjectElementAccessor>> elementAccessorCache;

	/**
	 * Cache for method accessor lookups that includes type hierarchy resolution.
	 */
	private final Map<Class<?>, Optional<ObjectMethodAccessor>> methodAccessorCache;

	/**
	 * Returns the singleton instance of the registry.
	 *
	 * @return the singleton instance
	 */
	@Nonnull
	public static ObjectAccessorRegistry getInstance() {
		return Holder.INSTANCE;
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
		final List<ObjectMethodAccessor> foundMethodAccessors = ServiceLoader.load(ObjectMethodAccessor.class)
			.stream()
			.map(Provider::get)
			.toList();

		this.propertyAccessors = createConcurrentHashMap(foundPropertyAccessors.size());
		this.elementAccessors = createConcurrentHashMap(foundElementAccessors.size());
		this.methodAccessors = createConcurrentHashMap(foundMethodAccessors.size());
		this.propertyAccessorCache = createConcurrentHashMap(Math.round(foundPropertyAccessors.size() * 1.5f));
		this.elementAccessorCache = createConcurrentHashMap(Math.round(foundElementAccessors.size() * 1.5f));
		this.methodAccessorCache = createConcurrentHashMap(Math.round(foundMethodAccessors.size() * 1.5f));

		foundPropertyAccessors.forEach(this::registerPropertyAccessor);
		foundElementAccessors.forEach(this::registerElementAccessor);
		foundMethodAccessors.forEach(this::registerMethodAccessor);
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
	 * Registers a method accessor for the specified type. Only one accessor can be registered per type -
	 * attempting to register a duplicate will result in an error.
	 *
	 * @param accessor the accessor to register
	 * @throws IllegalStateException if an accessor is already registered for the given type
	 */
	public void registerMethodAccessor(@Nonnull ObjectMethodAccessor accessor) {
		for (final Class<?> supportedType : accessor.getSupportedTypes()) {
			final ObjectMethodAccessor existing = this.methodAccessors.putIfAbsent(supportedType, accessor);
			Assert.isTrue(
				existing == null,
				"MethodAccessor already registered for type `" + supportedType.getName() + "`."
			);
		}
		// invalidate cache as new accessor may affect lookups
		this.methodAccessorCache.clear();
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
			t -> {
				for (final Map.Entry<Class<?>, ObjectPropertyAccessor> entry : this.propertyAccessors.entrySet()) {
					if (entry.getKey().isAssignableFrom(t)) {
						return Optional.of(entry.getValue());
					}
				}
				return Optional.empty();
			}
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
			t -> {
				for (final Map.Entry<Class<?>, ObjectElementAccessor> entry : this.elementAccessors.entrySet()) {
					if (entry.getKey().isAssignableFrom(t)) {
						return Optional.of(entry.getValue());
					}
				}
				return Optional.empty();
			}
		);
	}

	/**
	 * Gets the method accessor for the specified type. If no accessor is registered for the exact type,
	 * the registry will search through the type hierarchy (superclasses and interfaces).
	 *
	 * @param type the type to get the accessor for
	 * @param <T> the type parameter
	 * @return an optional containing the accessor if found, empty otherwise
	 */
	@Nonnull
	public <T extends Serializable> Optional<ObjectMethodAccessor> getMethodAccessor(@Nonnull Class<T> type) {
		return this.methodAccessorCache.computeIfAbsent(
			type,
			t -> {
				for (final Map.Entry<Class<?>, ObjectMethodAccessor> entry : this.methodAccessors.entrySet()) {
					if (entry.getKey().isAssignableFrom(t)) {
						return Optional.of(entry.getValue());
					}
				}
				return Optional.empty();
			}
		);
	}
}
