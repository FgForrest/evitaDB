/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
	 * Creates new {@link LinkedHashMap} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by Guava <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/Maps.java">Guava</a>.
	 * Thanks!
	 */
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
	 * Creates new {@link ConcurrentHashMap} instance that is pre-allocated to size that would absorb `expectedCapacity` elements
	 * without rehashing itself.
	 *
	 * Inspired by Guava <a href="https://github.com/google/guava/blob/master/guava/src/com/google/common/collect/Maps.java">Guava</a>.
	 * Thanks!
	 */
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

}
