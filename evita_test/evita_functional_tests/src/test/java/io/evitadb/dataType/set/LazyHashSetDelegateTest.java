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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LazyHashSet} verifying that it correctly
 * implements the {@link Set} contract with lazy initialization
 * of the underlying {@link HashSet} delegate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("LazyHashSet Set contract tests")
class LazyHashSetDelegateTest {

	@Nested
	@DisplayName("Construction and initial state")
	class ConstructionAndInitialStateTest {

		@Test
		@DisplayName(
			"Should be empty when newly created"
		)
		void shouldBeEmptyWhenNewlyCreated() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			assertEquals(0, set.size());
			assertTrue(set.isEmpty());
		}

		@Test
		@DisplayName(
			"Should return empty string representation "
				+ "when uninitialized"
		)
		void shouldReturnEmptyStringRepresentationWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			assertEquals("[]", set.toString());
		}

		@Test
		@DisplayName(
			"Should not initialize delegate "
				+ "on read operations"
		)
		void shouldNotInitializeDelegateOnReadOperations() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// all read-only operations should work without
			// initializing the delegate
			assertEquals(0, set.size());
			assertTrue(set.isEmpty());
			assertFalse(set.contains("x"));
			assertFalse(set.iterator().hasNext());
			assertEquals(0, set.toArray().length);
			assertEquals("[]", set.toString());

			// set should still report as empty after reads
			assertTrue(set.isEmpty());
		}
	}

	@Nested
	@DisplayName("Element addition")
	class ElementAdditionTest {

		@Test
		@DisplayName("Should add a single element")
		void shouldAddSingleElement() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			final boolean added = set.add("alpha");

			assertTrue(added);
			assertEquals(1, set.size());
			assertFalse(set.isEmpty());
			assertTrue(set.contains("alpha"));
		}

		@Test
		@DisplayName(
			"Should return false when adding duplicate"
		)
		void shouldReturnFalseWhenAddingDuplicate() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("alpha");

			final boolean addedAgain = set.add("alpha");

			assertFalse(addedAgain);
			assertEquals(1, set.size());
		}

		@Test
		@DisplayName("Should add null element")
		void shouldAddNullElement() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(2);

			final boolean added = set.add(null);

			assertTrue(added);
			assertTrue(set.contains(null));
			assertEquals(1, set.size());
		}

		@Test
		@DisplayName(
			"Should add all elements from a collection"
		)
		void shouldAddAllFromCollection() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			final Set<String> other =
				new HashSet<>(Arrays.asList("a", "b", "c"));

			final boolean changed = set.addAll(other);

			assertTrue(changed);
			assertEquals(3, set.size());
			assertTrue(set.containsAll(other));
		}

		@Test
		@DisplayName(
			"Should return false when adding "
				+ "empty collection"
		)
		void shouldReturnFalseWhenAddingEmptyCollection() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// addAll with empty collection initializes delegate
			// unnecessarily but returns false since nothing changed
			final boolean changed =
				set.addAll(Collections.emptySet());

			assertFalse(changed);
			assertEquals(0, set.size());
		}
	}

	@Nested
	@DisplayName("Element removal")
	class ElementRemovalTest {

		@Test
		@DisplayName("Should remove an existing element")
		void shouldRemoveExistingElement() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");
			set.add("c");

			final boolean removed = set.remove("b");

			assertTrue(removed);
			assertFalse(set.contains("b"));
			assertEquals(2, set.size());
		}

		@Test
		@DisplayName(
			"Should return false when removing "
				+ "from uninitialized set"
		)
		void shouldReturnFalseWhenRemovingFromUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			final boolean removed = set.remove("x");

			assertFalse(removed);
		}

		@Test
		@DisplayName(
			"Should remove all matching elements"
		)
		void shouldRemoveAllMatchingElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");
			set.add("c");

			final boolean changed =
				set.removeAll(Arrays.asList("a", "c", "x"));

			assertTrue(changed);
			assertEquals(1, set.size());
			assertTrue(set.contains("b"));
			assertFalse(set.contains("a"));
			assertFalse(set.contains("c"));
		}

		@Test
		@DisplayName(
			"Should return false for removeAll "
				+ "on uninitialized set"
		)
		void shouldReturnFalseWhenRemoveAllOnUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			final boolean changed =
				set.removeAll(Arrays.asList("a", "b"));

			assertFalse(changed);
		}

		@Test
		@DisplayName(
			"Should retain only specified elements"
		)
		void shouldRetainOnlySpecifiedElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");
			set.add("c");

			final boolean changed =
				set.retainAll(Arrays.asList("b"));

			assertTrue(changed);
			assertEquals(1, set.size());
			assertTrue(set.contains("b"));
			assertFalse(set.contains("a"));
			assertFalse(set.contains("c"));
		}

		@Test
		@DisplayName(
			"Should return false for retainAll "
				+ "on uninitialized set"
		)
		void shouldReturnFalseWhenRetainAllOnUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			final boolean changed =
				set.retainAll(Arrays.asList("a", "b"));

			assertFalse(changed);
		}
	}

	@Nested
	@DisplayName("Containment checks")
	class ContainmentChecksTest {

		@Test
		@DisplayName(
			"Should return false for contains "
				+ "on uninitialized set"
		)
		void shouldReturnFalseForContainsOnUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			assertFalse(set.contains("anything"));
			assertFalse(set.contains(null));
		}

		@Test
		@DisplayName(
			"Should return true for contained element"
		)
		void shouldReturnTrueForContainedElement() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("hello");

			assertTrue(set.contains("hello"));
			assertFalse(set.contains("world"));
		}

		@Test
		@DisplayName(
			"Should return true for containsAll "
				+ "with empty collection on uninitialized set"
		)
		void shouldReturnTrueForContainsAllWithEmptyCollectionOnUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// containsAll with empty collection returns true
			// per the Set contract
			assertTrue(
				set.containsAll(Collections.emptySet())
			);
		}

		@Test
		@DisplayName(
			"Should return false for containsAll "
				+ "with non-empty collection "
				+ "on uninitialized set"
		)
		void shouldReturnFalseForContainsAllWithNonEmptyCollectionOnUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			assertFalse(
				set.containsAll(Arrays.asList("a", "b"))
			);
		}
	}

	@Nested
	@DisplayName("Iteration and conversion")
	class IterationAndConversionTest {

		@Test
		@DisplayName(
			"Should return empty iterator "
				+ "when uninitialized"
		)
		void shouldReturnEmptyIteratorWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			final Iterator<String> iterator = set.iterator();

			assertFalse(iterator.hasNext());
			assertThrows(
				NoSuchElementException.class,
				iterator::next
			);
		}

		@Test
		@DisplayName(
			"Should iterate over all elements"
		)
		void shouldIterateOverAllElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");
			set.add("c");

			final Set<String> collected = new HashSet<>();
			final Iterator<String> iterator = set.iterator();
			while (iterator.hasNext()) {
				collected.add(iterator.next());
			}

			assertEquals(
				new HashSet<>(Arrays.asList("a", "b", "c")),
				collected
			);
		}

		@Test
		@DisplayName(
			"Should return empty array when uninitialized"
		)
		void shouldReturnEmptyArrayWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			final Object[] array = set.toArray();

			assertEquals(0, array.length);
		}

		@Test
		@DisplayName(
			"Should return array with all elements"
		)
		void shouldReturnArrayWithAllElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			final Object[] array = set.toArray();

			assertEquals(2, array.length);
			final Set<Object> asSet =
				new HashSet<>(Arrays.asList(array));
			assertTrue(asSet.contains("a"));
			assertTrue(asSet.contains("b"));
		}

		@Test
		@DisplayName(
			"Should null-terminate typed array "
				+ "when uninitialized with larger array"
		)
		void shouldNullTerminateTypedArrayWhenUninitializedWithLargerArray() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// pre-fill array with data
			final String[] input =
				new String[]{"leftover1", "leftover2"};
			final String[] result = set.toArray(input);

			// per Collection.toArray(T[]) contract,
			// a[0] should be null when the collection
			// is empty and the array is larger
			assertNull(result[0]);
		}

		@Test
		@DisplayName(
			"Should return typed array with all elements"
		)
		void shouldReturnTypedArrayWithAllElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");
			set.add("c");

			final String[] result =
				set.toArray(new String[0]);

			assertEquals(3, result.length);
			final Set<String> resultSet =
				new HashSet<>(Arrays.asList(result));
			assertEquals(
				new HashSet<>(Arrays.asList("a", "b", "c")),
				resultSet
			);
		}
	}

	@Nested
	@DisplayName("Clear operation")
	class ClearOperationTest {

		@Test
		@DisplayName("Should clear an initialized set")
		void shouldClearInitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			set.clear();

			assertTrue(set.isEmpty());
			assertEquals(0, set.size());
			assertFalse(set.contains("a"));
		}

		@Test
		@DisplayName(
			"Should not throw when clearing "
				+ "uninitialized set"
		)
		void shouldNotThrowWhenClearingUninitializedSet() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			assertDoesNotThrow(set::clear);
			assertTrue(set.isEmpty());
		}
	}

	@Nested
	@DisplayName("Equals contract")
	class EqualsContractTest {

		@Test
		@DisplayName(
			"Should equal HashSet with same elements"
		)
		void shouldEqualHashSetWithSameElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			final Set<String> hashSet = new HashSet<>();
			hashSet.add("a");
			hashSet.add("b");

			assertEquals(hashSet, set);
			assertEquals(set, hashSet);
		}

		@Test
		@DisplayName(
			"Should not equal HashSet "
				+ "with different elements"
		)
		void shouldNotEqualHashSetWithDifferentElements() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			final Set<String> hashSet = new HashSet<>();
			hashSet.add("x");
			hashSet.add("y");

			assertNotEquals(hashSet, set);
			assertNotEquals(set, hashSet);
		}

		@Test
		@DisplayName(
			"Should equal self when initialized"
		)
		void shouldEqualSelfWhenInitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");

			assertTrue(set.equals(set));
		}

		@Test
		@DisplayName(
			"Should be symmetric with HashSet "
				+ "when initialized"
		)
		void shouldBeSymmetricWithHashSetWhenInitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			final Set<String> hashSet = new HashSet<>();
			hashSet.add("a");
			hashSet.add("b");

			// both directions should agree
			final boolean lazyEqualsHash = set.equals(hashSet);
			final boolean hashEqualsLazy = hashSet.equals(set);
			assertEquals(lazyEqualsHash, hashEqualsLazy);
		}

		@Test
		@DisplayName(
			"Should equal self when uninitialized"
		)
		void shouldEqualSelfWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// reflexivity: every object must equal itself
			assertTrue(set.equals(set));
		}

		@Test
		@DisplayName(
			"Should not equal null when uninitialized"
		)
		void shouldNotEqualNullWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// no object should ever equal null
			assertFalse(set.equals(null));
		}

		@Test
		@DisplayName(
			"Should equal empty HashSet "
				+ "when uninitialized"
		)
		void shouldEqualEmptyHashSetWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			final Set<String> emptyHashSet = new HashSet<>();

			// uninitialized LazyHashSet is logically empty
			// and should equal an empty HashSet
			assertTrue(set.equals(emptyHashSet));
		}

		@Test
		@DisplayName(
			"Should equal another empty LazyHashSet "
				+ "when both are uninitialized"
		)
		void shouldEqualAnotherEmptyLazyHashSetWhenUninitialized() {
			final LazyHashSet<String> set1 =
				new LazyHashSet<>(4);
			final LazyHashSet<String> set2 =
				new LazyHashSet<>(4);

			// two uninitialized (logically empty)
			// sets should be equal
			assertTrue(set1.equals(set2));
		}
	}

	@Nested
	@DisplayName("HashCode contract")
	class HashCodeContractTest {

		@Test
		@DisplayName(
			"Should return same hash code as HashSet "
				+ "when initialized"
		)
		void shouldReturnSameHashCodeAsHashSetWhenInitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			final Set<String> hashSet = new HashSet<>();
			hashSet.add("a");
			hashSet.add("b");

			assertEquals(hashSet.hashCode(), set.hashCode());
		}

		@Test
		@DisplayName(
			"Should return consistent hash code"
		)
		void shouldReturnConsistentHashCode() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("a");
			set.add("b");

			final int hashCode1 = set.hashCode();
			final int hashCode2 = set.hashCode();

			assertEquals(hashCode1, hashCode2);
		}

		@Test
		@DisplayName(
			"Should return zero hash code "
				+ "when uninitialized"
		)
		void shouldReturnZeroHashCodeWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			// per the Set contract, an empty set's
			// hashCode is 0 (sum of element hashes)
			assertEquals(0, set.hashCode());
		}

		@Test
		@DisplayName(
			"Should return same hash code for "
				+ "different expected sizes "
				+ "when uninitialized"
		)
		void shouldReturnSameHashCodeForDifferentExpectedSizes() {
			final LazyHashSet<String> set4 =
				new LazyHashSet<>(4);
			final LazyHashSet<String> set8 =
				new LazyHashSet<>(8);

			// both are logically empty, so both should
			// return 0 per the Set contract
			assertEquals(
				set4.hashCode(), set8.hashCode()
			);
		}
	}

	@Nested
	@DisplayName("ToString")
	class ToStringTest {

		@Test
		@DisplayName(
			"Should return empty brackets "
				+ "when uninitialized"
		)
		void shouldReturnEmptyBracketsWhenUninitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);

			assertEquals("[]", set.toString());
		}

		@Test
		@DisplayName(
			"Should return elements string "
				+ "when initialized"
		)
		void shouldReturnElementsStringWhenInitialized() {
			final LazyHashSet<String> set =
				new LazyHashSet<>(4);
			set.add("only");

			// single-element set has deterministic order
			assertEquals("[only]", set.toString());
		}
	}
}
