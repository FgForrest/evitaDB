/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This class verifies behavior of {@link CollectionUtils}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CollectionUtilsTest {

	@Test
	void shouldCombineTwoSets() {
		final Set<Integer> firstNonEmpty = Set.of(1, 3);
		final Set<Integer> secondNonEmpty = Set.of(3, 4);
		// when first set is empty and second not, return first
		assertSame(firstNonEmpty, CollectionUtils.combine(firstNonEmpty, Collections.emptySet()));
		assertSame(firstNonEmpty, CollectionUtils.combine(Collections.emptySet(), firstNonEmpty));
		assertEquals(Set.of(1, 3, 4), CollectionUtils.combine(firstNonEmpty, secondNonEmpty));
	}

	@Test
	void shouldWrapSetToUnmodifiableOne() {
		Set<Integer> modifiableSet = new HashSet<>();
		modifiableSet.add(1);
		modifiableSet.add(2);

		Set<Integer> unmodifiableSet = CollectionUtils.toUnmodifiableSet(modifiableSet);
		assertEquals(modifiableSet, unmodifiableSet);
	}

	@Test
	void shouldKeepAlreadyUnmodifieableSet() {
		Set<Integer> modifiableSet = new HashSet<>();
		modifiableSet.add(1);
		modifiableSet.add(2);

		Set<Integer> unmodifiableSet = Collections.unmodifiableSet(modifiableSet);
		assertSame(unmodifiableSet, CollectionUtils.toUnmodifiableSet(unmodifiableSet));
	}

	@Test
	void shouldKeepAlreadyImmutableSet() {
		Set<Integer> unmodifiableSet = Set.of(1, 2);
		assertSame(unmodifiableSet, CollectionUtils.toUnmodifiableSet(unmodifiableSet));
	}

	@Test
	void shouldWrapMapToUnmodifiableOne() {
		Map<Integer, String> modifiableMap = new HashMap<>();
		modifiableMap.put(1, "one");
		modifiableMap.put(2, "two");

		Map<Integer, String> unmodifiableMap = CollectionUtils.toUnmodifiableMap(modifiableMap);
		assertEquals(modifiableMap, unmodifiableMap);
	}

	@Test
	void shouldKeepAlreadyUnmodifiableMap() {
		Map<Integer, String> modifiableMap = new HashMap<>();
		modifiableMap.put(1, "one");
		modifiableMap.put(2, "two");

		Map<Integer, String> unmodifiableMap = Collections.unmodifiableMap(modifiableMap);
		assertSame(unmodifiableMap, CollectionUtils.toUnmodifiableMap(unmodifiableMap));
	}

	@Test
	void shouldKeepAlreadyImmutableMap() {
		Map<Integer, String> unmodifiableMap = Map.of(1, "one", 2, "two");
		assertSame(unmodifiableMap, CollectionUtils.toUnmodifiableMap(unmodifiableMap));
	}

}
