/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.dataType.set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LazyHashSet basic Set contract tests")
class LazyHashSetDelegateTest {
	@Test
	@DisplayName("shouldBeEmptyOnCreation")
	void shouldBeEmptyOnCreation() {
		final LazyHashSet<String> set = new LazyHashSet<>(4);
		assertEquals(0, set.size());
		assertTrue(set.isEmpty());
	}

	@Test
	@DisplayName("shouldAddAndContainElements")
	void shouldAddAndContainElements() {
		final LazyHashSet<String> set = new LazyHashSet<>(2);
		assertTrue(set.add("a"));
		assertTrue(set.add("b"));
		assertFalse(set.add("a"));
		assertEquals(2, set.size());
		assertTrue(set.contains("a"));
		assertTrue(set.contains("b"));
		assertFalse(set.contains("c"));
	}

	@Test
	@DisplayName("shouldRemoveElements")
	void shouldRemoveElements() {
		final LazyHashSet<String> set = new LazyHashSet<>(3);
		set.add("a");
		set.add("b");
		set.add("c");
		assertTrue(set.remove("b"));
		assertFalse(set.contains("b"));
		assertEquals(2, set.size());
		assertFalse(set.remove("b"));
	}

	@Test
	@DisplayName("shouldClearSet")
	void shouldClearSet() {
		final LazyHashSet<String> set = new LazyHashSet<>(2);
		set.add("a");
		set.add("b");
		set.clear();
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
	}

	@Test
	@DisplayName("shouldSupportAddAllContainsAllRemoveAllRetainAll")
	void shouldSupportAddAllContainsAllRemoveAllRetainAll() {
		final LazyHashSet<String> set = new LazyHashSet<>(4);
		final Set<String> other = new HashSet<>(Arrays.asList("a", "b", "c"));
		assertTrue(set.addAll(other));
		assertEquals(3, set.size());
		assertTrue(set.containsAll(other));

		final Set<String> toRemove = new HashSet<>(Arrays.asList("a", "x"));
		assertTrue(set.removeAll(toRemove));
		assertFalse(set.contains("a"));
		assertTrue(set.contains("b"));
		assertTrue(set.contains("c"));

		final Set<String> toRetain = new HashSet<>(Arrays.asList("b"));
		assertTrue(set.retainAll(toRetain));
		assertTrue(set.contains("b"));
		assertFalse(set.contains("c"));
	}

	@Test
	@DisplayName("shouldProvideIteratorAndToArray")
	void shouldProvideIteratorAndToArray() {
		final LazyHashSet<Integer> set = new LazyHashSet<>(3);
		set.add(1);
		set.add(2);
		set.add(3);

		final Iterator<Integer> it = set.iterator();
		int count = 0;
		while (it.hasNext()) {
			final Integer v = it.next();
			assertNotNull(v);
			count++;
		}
		assertEquals(3, count);

		final Object[] arr = set.toArray();
		assertEquals(3, arr.length);

		final Integer[] preallocated = new Integer[0];
		final Integer[] arr2 = set.toArray(preallocated);
		assertEquals(3, arr2.length);
	}

	@Test
	@DisplayName("shouldBeEqualToHashSetWithSameElements")
	void shouldBeEqualToHashSetWithSameElements() {
		final LazyHashSet<String> set = new LazyHashSet<>(3);
		set.add("a");
		set.add("b");

		final Set<String> hs = new HashSet<>();
		hs.add("a");
		hs.add("b");

		assertEquals(hs, set);
		assertEquals(set, hs);
		assertEquals(hs.hashCode(), set.hashCode());
	}

	@Test
	@DisplayName("shouldSupportNullElementsLikeHashSet")
	void shouldSupportNullElementsLikeHashSet() {
		final LazyHashSet<String> set = new LazyHashSet<>(2);
		assertTrue(set.add(null));
		assertTrue(set.contains(null));
		assertEquals(1, set.size());
	}

	@Test
	@DisplayName("shouldLazyInitializeOnFirstUseWithoutExceptions")
	void shouldLazyInitializeOnFirstUseWithoutExceptions() {
		final LazyHashSet<String> set = new LazyHashSet<>(1);
		// calling size() on fresh instance should work and not throw
		assertEquals(0, set.size());
		// after first mutating operation, structure behaves as normal HashSet
		assertTrue(set.add("k"));
		assertEquals(1, set.size());
		assertTrue(set.contains("k"));
	}
}
