/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collections utils contains shared utility method for working with Java Collections.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CollectionUtils {
	/**
	 * The largest power of two that can be represented as an {@code int}.
	 */
	public static final int MAX_POWER_OF_TWO = 1 << (Integer.SIZE - 2);

	/**
	 * Creates new {@link HashMap} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/Maps.java">Guava</a>.
	 * Thanks!
	 */
	@Nonnull
	public static <K, V> HashMap<K, V> createHashMap(int expectedCapacity) {
		if (expectedCapacity <= 0) {
			return new HashMap<>();
		} else if (expectedCapacity < 3) {
			return new HashMap<>(expectedCapacity + 1);
		}
		if (expectedCapacity < MAX_POWER_OF_TWO) {
			// This is the calculation used in JDK8 to resize when a putAll
			// happens; it seems to be the most conservative calculation we
			// can make.  0.75 is the default load factor.
			return new HashMap<>((int) ((float) expectedCapacity / 0.75F + 1.0F));
		}
		return new HashMap<>(Integer.MAX_VALUE); // any large value
	}

	/**
	 * Creates new {@link HashMap} instance with predefined contents.
	 */
	@SafeVarargs
	@Nonnull
	public static <K, V> HashMap<K, V> createHashMap(@Nonnull Property<K, V>... properties) {
		final HashMap<K, V> hashMap = createHashMap(properties.length);
		for (Property<K, V> property : properties) {
			hashMap.put(property.name(), property.value());
		}
		return hashMap;
	}

	/**
	 * Creates new {@link LinkedHashMap} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by Guava <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/Maps.java">Guava</a>.
	 * Thanks!
	 */
	@Nonnull
	public static <K, V> LinkedHashMap<K, V> createLinkedHashMap(int expectedCapacity) {
		if (expectedCapacity <= 0) {
			return new LinkedHashMap<>();
		} else if (expectedCapacity < 3) {
			return new LinkedHashMap<>(expectedCapacity + 1);
		}
		if (expectedCapacity < MAX_POWER_OF_TWO) {
			// This is the calculation used in JDK8 to resize when a putAll
			// happens; it seems to be the most conservative calculation we
			// can make.  0.75 is the default load factor.
			return new LinkedHashMap<>((int) ((float) expectedCapacity / 0.75F + 1.0F));
		}
		return new LinkedHashMap<>(Integer.MAX_VALUE); // any large value
	}

	/**
	 * Creates new {@link HashMap} instance with predefined contents.
	 */
	@SafeVarargs
	@Nonnull
	public static <K, V> LinkedHashMap<K, V> createLinkedHashMap(@Nonnull Property<K, V>... properties) {
		final LinkedHashMap<K, V> hashMap = createLinkedHashMap(properties.length);
		for (Property<K, V> property : properties) {
			hashMap.put(property.name(), property.value());
		}
		return hashMap;
	}

	/**
	 * Creates new {@link ConcurrentHashMap} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by Guava <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/Maps.java">Guava</a>.
	 * Thanks!
	 */
	@Nonnull
	public static <K, V> ConcurrentHashMap<K, V> createConcurrentHashMap(int expectedCapacity) {
		if (expectedCapacity <= 0) {
			return new ConcurrentHashMap<>();
		} else if (expectedCapacity < 3) {
			return new ConcurrentHashMap<>(expectedCapacity + 1);
		}
		if (expectedCapacity < MAX_POWER_OF_TWO) {
			// This is the calculation used in JDK8 to resize when a putAll
			// happens; it seems to be the most conservative calculation we
			// can make.  0.75 is the default load factor.
			return new ConcurrentHashMap<>((int) (expectedCapacity / 0.75F + 1.0F));
		}
		return new ConcurrentHashMap<>(Integer.MAX_VALUE); // any large value
	}

	/**
	 * Creates new {@link HashSet} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by Guava <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/.java">Guava</a>.
	 * Thanks!
	 */
	@Nonnull
	public static <K> HashSet<K> createHashSet(int expectedCapacity) {
		if (expectedCapacity <= 0) {
			return new HashSet<>();
		} else if (expectedCapacity < 3) {
			return new HashSet<>(expectedCapacity + 1);
		}
		if (expectedCapacity < MAX_POWER_OF_TWO) {
			// This is the calculation used in JDK8 to resize when a putAll
			// happens; it seems to be the most conservative calculation we
			// can make.  0.75 is the default load factor.
			return new HashSet<>((int) (expectedCapacity / 0.75F + 1.0F));
		}
		return new HashSet<>(Integer.MAX_VALUE); // any large value
	}

	/**
	 * Creates new {@link LinkedHashSet} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by Guava <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/Sets.java">Guava</a>.
	 * Thanks!
	 */
	@Nonnull
	public static <K> LinkedHashSet<K> createLinkedHashSet(int expectedCapacity) {
		if (expectedCapacity <= 0) {
			return new LinkedHashSet<>();
		} else if (expectedCapacity < 3) {
			return new LinkedHashSet<>(expectedCapacity + 1);
		}
		if (expectedCapacity < MAX_POWER_OF_TWO) {
			// This is the calculation used in JDK8 to resize when a putAll
			// happens; it seems to be the most conservative calculation we
			// can make.  0.75 is the default load factor.
			return new LinkedHashSet<>((int) ((float) expectedCapacity / 0.75F + 1.0F));
		}
		return new LinkedHashSet<>(Integer.MAX_VALUE); // any large value
	}

	/**
	 * Method combines all values of two sets into a new set.
	 */
	@Nonnull
	public static <T> Set<T> combine(@Nonnull Set<T> first, Set<T> second) {
		if (first.isEmpty()) {
			return second;
		} else if (second.isEmpty()) {
			return first;
		} else {
			final HashSet<T> combinationResult = CollectionUtils.createHashSet(first.size() + second.size());
			combinationResult.addAll(first);
			combinationResult.addAll(second);
			return combinationResult;
		}
	}

	/**
	 * Factory method for property.
	 */
	@Nonnull
	public static <K, V> Property<K, V> property(@Nonnull K name, @Nonnull V value) {
		return new Property<>(name, value);
	}

	/**
	 * Converts a modifiable set to an unmodifiable set. If the input set is already unmodifiable,
	 * it returns the same set, otherwise it wraps the set in an unmodifiable set.
	 *
	 * @param set the set to be converted to an unmodifiable set, must not be null
	 * @return an unmodifiable view of the specified set
	 */
	@Nonnull
	public static <V> Set<V> toUnmodifiableSet(@Nonnull Set<V> set) {
		if (set.getClass().getName().startsWith("java.util.ImmutableCollections$") ||
				set.getClass().getName().equals("java.util.Collections$UnmodifiableSet")) {
			return set;
		} else {
			return Collections.unmodifiableSet(set);
		}
	}

	/**
	 * Converts the given modifiable map to an unmodifiable map. If the input map
	 * is already unmodifiable, it returns the same map; otherwise, it wraps the
	 * map in an unmodifiable map.
	 *
	 * @param uniquenessTypeInScopes the map to be converted to an unmodifiable map, must not be null
	 * @return an unmodifiable view of the specified map
	 */
	@Nonnull
	public static <K, V> Map<K, V> toUnmodifiableMap(@Nonnull Map<K, V> uniquenessTypeInScopes) {
		if (uniquenessTypeInScopes.getClass().getName().startsWith("java.util.ImmutableCollections$") ||
				uniquenessTypeInScopes.getClass().getName().equals("java.util.Collections$UnmodifiableMap")) {
			return uniquenessTypeInScopes;
		} else {
			return Collections.unmodifiableMap(uniquenessTypeInScopes);
		}
	}

	/**
	 * DTO that can be used for bootstrapping a new map.
	 */
	public record Property<K, V>(
		@Nonnull K name,
		@Nonnull V value
	) {
	}

}
