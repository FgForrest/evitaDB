/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api;

import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

/**
 * Shared utility methods for generational (fuzzy) tests that exercise conditional indexing
 * features across many random mutations. Provides random selection helpers, reference key
 * encoding, and a shared test state record.
 *
 * Implemented by both {@link EvitaConditionalFacetGenerationalTest} and
 * {@link EvitaConditionalBucketGenerationalTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface GenerationalTestSupport {

	/**
	 * Picks a random key from a non-empty map.
	 *
	 * @param random the random number generator
	 * @param map the map to pick from (must not be empty)
	 * @return a randomly selected key
	 */
	@Nonnull
	static <K> K pickRandomKey(@Nonnull Random random, @Nonnull Map<K, ?> map) {
		final int index = random.nextInt(map.size());
		int i = 0;
		for (K key : map.keySet()) {
			if (i == index) {
				return key;
			}
			i++;
		}
		throw new IllegalStateException("Should not happen");
	}

	/**
	 * Picks a random element from a non-empty set.
	 *
	 * @param random the random number generator
	 * @param set the set to pick from (must not be empty)
	 * @return a randomly selected element
	 */
	@Nonnull
	static <T> T pickRandomFromSet(@Nonnull Random random, @Nonnull Set<T> set) {
		final int index = random.nextInt(set.size());
		int i = 0;
		for (T item : set) {
			if (i == index) {
				return item;
			}
			i++;
		}
		throw new IllegalStateException("Should not happen");
	}

	/**
	 * Picks a random (productPK, paramPK) pair from the references map.
	 *
	 * @param random the random number generator
	 * @param productRefs productPK → set of referenced entity PKs
	 * @return `[productPK, paramPK]` or null if no references exist
	 */
	@Nullable
	static int[] pickRandomRef(
		@Nonnull Random random,
		@Nonnull Map<Integer, Set<Integer>> productRefs
	) {
		if (productRefs.isEmpty()) {
			return null;
		}
		final Map<Integer, Set<Integer>> nonEmpty = CollectionUtils.createHashMap(productRefs.size());
		for (Entry<Integer, Set<Integer>> entry : productRefs.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				nonEmpty.put(entry.getKey(), entry.getValue());
			}
		}
		if (nonEmpty.isEmpty()) {
			return null;
		}
		final int productPK = pickRandomKey(random, nonEmpty);
		final Set<Integer> refs = nonEmpty.get(productPK);
		final int paramPK = pickRandomFromSet(random, refs);
		return new int[]{productPK, paramPK};
	}

	/**
	 * Encodes a (productPK, paramPK) pair into a single long for use as a map key.
	 *
	 * @param productPK the product primary key
	 * @param paramPK the parameter primary key
	 * @return the encoded key
	 */
	static long encodeRefKey(int productPK, int paramPK) {
		return ((long) productPK << 32) | (paramPK & 0xFFFFFFFFL);
	}

	/**
	 * Picks a random BigDecimal value from the provided pool, or null with ~10% probability.
	 *
	 * @param random the random number generator
	 * @param valuePool the predefined pool of BigDecimal values
	 * @return a random BigDecimal value, or null
	 */
	@Nullable
	static BigDecimal pickRandomValue(@Nonnull Random random, @Nonnull BigDecimal[] valuePool) {
		if (random.nextInt(10) == 0) {
			return null;
		}
		return valuePool[random.nextInt(valuePool.length)];
	}

	/**
	 * Generational test state tracking the number of completed generations.
	 *
	 * @param generation total count of generations that were correctly created
	 */
	record TestState(int generation) {
	}
}
