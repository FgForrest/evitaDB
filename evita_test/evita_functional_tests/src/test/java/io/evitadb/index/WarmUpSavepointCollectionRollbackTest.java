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

package io.evitadb.index;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.map.PersistentTransactionalProducerMap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.set.TransactionalSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that the collection-backed index structures — {@link TransactionalMap}, {@link PersistentTransactionalMap}
 * and its producer-valued subclass, {@link TransactionalSet} and {@link TransactionalList} — rewind their
 * non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint} bracketing them is rolled back, and keep them
 * when it is committed.
 *
 * These four journal PER OPERATION rather than capturing a first-touch pre-image, because their pre-image is the whole
 * accumulated delegate and copying it once per entity is the rollback cliff the journal strategy exists to avoid. Two
 * properties follow, and both are asserted throughout:
 *
 * - the same slot may be written several times inside one savepoint and must still come back to its PRE-savepoint
 *   value, which only holds if each inverse is an absolute restore rather than a counter-operation;
 * - every path that reaches the delegate has to journal, including the ones that never touch a mutator of the
 *   decorator itself — a removal through a view's iterator, an {@link Entry#setValue}, a `removeIf`, a `replaceAll`.
 *   Those are what the dedicated view / iterator tests below exist for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepointScalarAndArrayRollbackTest for the structures that capture a first-touch pre-image instead
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of map, set and list structures")
class WarmUpSavepointCollectionRollbackTest {

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	/**
	 * Materializes a map's current content into a plain, comparison-friendly copy.
	 *
	 * @param map the map to read
	 * @param <K> key type
	 * @param <V> value type
	 * @return an independent copy of the map's entries
	 */
	@Nonnull
	private static <K, V> Map<K, V> contentOf(@Nonnull Map<K, V> map) {
		final Map<K, V> copy = new HashMap<>(map.size());
		map.forEach(copy::put);
		return copy;
	}

	@Nested
	@DisplayName("TransactionalMap - own mutators")
	class Maps {

		@Test
		@DisplayName("Rollback restores the map through put, remove, putAll and repeated overwrites")
		void shouldRestoreMapAfterMixedMutations() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.put("a", 10);
			// the same key written again - only an ABSOLUTE inverse brings back 1 rather than 10
			map.put("a", 100);
			map.remove("b");
			map.putAll(Map.of("c", 3, "d", 4));
			assertEquals(Map.of("a", 100, "c", 3, "d", 4), contentOf(map), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(Map.of("a", 1, "b", 2), contentOf(map), "Rollback must restore the pre-savepoint map.");
		}

		@Test
		@DisplayName("Rollback restores a map that was cleared inside the savepoint")
		void shouldRestoreClearedMap() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.clear();
			map.put("c", 3);
			savepoint.rollback();

			assertEquals(Map.of("a", 1, "b", 2), contentOf(map), "Rollback must restore a cleared map wholesale.");
		}

		@Test
		@DisplayName("Rollback restores the iteration order of an order-bearing delegate")
		void shouldRestoreIterationOrderOfLinkedDelegate() {
			final Map<String, Integer> delegate = new LinkedHashMap<>();
			delegate.put("first", 1);
			delegate.put("second", 2);
			delegate.put("third", 3);
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.clear();
			savepoint.rollback();

			assertEquals(
				List.of("first", "second", "third"), new ArrayList<>(delegate.keySet()),
				"A wholesale restore must put the entries back in the order the delegate had them."
			);
		}

		@Test
		@DisplayName("Rollback restores writes made through the inherited compute / merge defaults")
		void shouldRestoreWritesMadeThroughMapDefaults() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.computeIfAbsent("b", key -> 2);
			map.merge("a", 5, Integer::sum);
			map.putIfAbsent("c", 3);
			map.compute("a", (key, value) -> value + 1);
			assertEquals(Map.of("a", 7, "b", 2, "c", 3), contentOf(map), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				Map.of("a", 1), contentOf(map),
				"The Map defaults are built on get/put/remove, so journaling those must cover them."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepMapOnCommit() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(new HashMap<>(Map.of("a", 1)));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.put("b", 2);
			map.remove("a");
			savepoint.commit();

			assertEquals(Map.of("b", 2), contentOf(map), "Commit must keep the savepoint's writes.");
		}

		@Test
		@DisplayName("Only the maps actually written inside the savepoint are rewound")
		void shouldLeaveUntouchedMapsAlone() {
			final TransactionalMap<String, Integer> touched = new TransactionalMap<>(new HashMap<>(Map.of("a", 1)));
			final TransactionalMap<String, Integer> untouched = new TransactionalMap<>(new HashMap<>(Map.of("z", 9)));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touched.put("b", 2);
			savepoint.rollback();

			assertEquals(Map.of("a", 1), contentOf(touched), "The written map must be rewound.");
			assertEquals(Map.of("z", 9), contentOf(untouched), "A map nobody wrote must be left as it was.");
		}
	}

	@Nested
	@DisplayName("TransactionalMap - collection views")
	class MapViews {

		@Test
		@DisplayName("Rollback restores entries removed through the key-set iterator, remove and removeIf")
		void shouldRestoreRemovalsThroughKeySet() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2, "c", 3, "d", 4))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			final Set<String> keys = map.keySet();
			keys.remove("a");
			final Iterator<String> it = keys.iterator();
			while (it.hasNext()) {
				if ("b".equals(it.next())) {
					it.remove();
				}
			}
			keys.removeIf("c"::equals);
			assertEquals(Map.of("d", 4), contentOf(map), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				Map.of("a", 1, "b", 2, "c", 3, "d", 4), contentOf(map),
				"Every removal path a key set exposes must be journaled."
			);
		}

		@Test
		@DisplayName("Rollback restores entries removed through the values view")
		void shouldRestoreRemovalsThroughValues() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2, "c", 3))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.values().remove(1);
			map.values().removeIf(value -> value == 2);
			assertEquals(Map.of("c", 3), contentOf(map), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(Map.of("a", 1, "b", 2, "c", 3), contentOf(map), "Values-view removals must be journaled.");
		}

		@Test
		@DisplayName("Rollback restores entries removed and overwritten through the entry set")
		void shouldRestoreMutationsThroughEntrySet() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2, "c", 3))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (final Entry<String, Integer> entry : map.entrySet()) {
				if ("a".equals(entry.getKey())) {
					entry.setValue(100);
				}
			}
			final Iterator<Entry<String, Integer>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				if ("b".equals(it.next().getKey())) {
					it.remove();
				}
			}
			assertEquals(Map.of("a", 100, "c", 3), contentOf(map), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				Map.of("a", 1, "b", 2, "c", 3), contentOf(map),
				"Both the in-place setValue and the iterator removal must be journaled."
			);
		}

		@Test
		@DisplayName("Rollback restores a map rewritten through replaceAll")
		void shouldRestoreReplaceAll() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.replaceAll((key, value) -> value * 10);
			assertEquals(Map.of("a", 10, "b", 20), contentOf(map), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				Map.of("a", 1, "b", 2), contentOf(map),
				"replaceAll writes through the entry set, which is exactly why that view is wrapped."
			);
		}

		@Test
		@DisplayName("Rollback restores a map cleared through a view")
		void shouldRestoreClearThroughView() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.keySet().clear();
			savepoint.rollback();

			assertEquals(Map.of("a", 1, "b", 2), contentOf(map), "A view clear must be journaled like map.clear().");
		}

		@Test
		@DisplayName("Commit keeps view removals made inside the savepoint")
		void shouldKeepViewRemovalsOnCommit() {
			final TransactionalMap<String, Integer> map = new TransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.keySet().remove("a");
			savepoint.commit();

			assertEquals(Map.of("b", 2), contentOf(map), "Commit must keep the savepoint's writes.");
		}
	}

	@Nested
	@DisplayName("PersistentTransactionalMap")
	class PersistentMaps {

		@Test
		@DisplayName("Rollback restores a thawed map through put, remove, putAll and repeated overwrites")
		void shouldRestoreThawedMapAfterMixedMutations() {
			final PersistentTransactionalMap<String, Integer> map = new PersistentTransactionalMap<>(
				Map.of("a", 1, "b", 2)
			);
			// force the thawed representation by writing once outside the savepoint
			map.put("seed", 0);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.put("a", 10);
			map.put("a", 100);
			map.remove("b");
			map.putAll(Map.of("c", 3));
			savepoint.rollback();

			assertEquals(
				Map.of("a", 1, "b", 2, "seed", 0), contentOf(map),
				"Rollback must restore the pre-savepoint content of the thawed buffer."
			);
		}

		@Test
		@DisplayName("Rollback restores a sealed map that a write inside the savepoint thawed")
		void shouldRestoreSealedMapThawedInsideSavepoint() {
			final PersistentTransactionalMap<String, Integer> map = new PersistentTransactionalMap<>(
				Map.of("a", 1, "b", 2)
			);
			// publish the immutable snapshot, so the first write inside the savepoint has to thaw it
			map.sealed();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.put("c", 3);
			map.remove("a");
			savepoint.rollback();

			assertEquals(
				Map.of("a", 1, "b", 2), contentOf(map),
				"Putting the sealed snapshot's reference back restores every entry at once."
			);
		}

		@Test
		@DisplayName("Rollback restores a map cleared inside the savepoint")
		void shouldRestoreClearedMap() {
			final PersistentTransactionalMap<String, Integer> map = new PersistentTransactionalMap<>(
				Map.of("a", 1, "b", 2)
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.clear();
			map.put("c", 3);
			savepoint.rollback();

			assertEquals(Map.of("a", 1, "b", 2), contentOf(map), "The clear swaps the buffer and must be rewound.");
		}

		@Test
		@DisplayName("Rollback restores entries removed through a thawed map's views")
		void shouldRestoreRemovalsThroughViews() {
			final PersistentTransactionalMap<String, Integer> map = new PersistentTransactionalMap<>(
				new HashMap<>(Map.of("a", 1, "b", 2, "c", 3))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.keySet().remove("a");
			map.values().removeIf(value -> value == 2);
			savepoint.rollback();

			assertEquals(Map.of("a", 1, "b", 2, "c", 3), contentOf(map), "View removals must be journaled.");
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepMapOnCommit() {
			final PersistentTransactionalMap<String, Integer> map = new PersistentTransactionalMap<>(Map.of("a", 1));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.put("b", 2);
			savepoint.commit();

			assertEquals(Map.of("a", 1, "b", 2), contentOf(map), "Commit must keep the savepoint's writes.");
		}

		@Test
		@DisplayName("The producer-valued subclass inherits the same warm-up journaling")
		void shouldRestoreProducerValuedMap() {
			final TransactionalBoolean original = new TransactionalBoolean(true);
			final PersistentTransactionalProducerMap<String, TransactionalBoolean> map =
				PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(
					new HashMap<>(Map.of("a", original)), mergedState -> (TransactionalBoolean) mergedState
				);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			map.put("b", new TransactionalBoolean(false));
			map.remove("a");
			savepoint.rollback();

			assertEquals(
				Map.of("a", original), contentOf(map),
				"The subclass overrides no write method, so the inherited journaling must cover it."
			);
		}
	}

	@Nested
	@DisplayName("TransactionalSet")
	class Sets {

		@Test
		@DisplayName("Rollback restores the set through every mutator kind")
		void shouldRestoreSetAfterMixedMutations() {
			final TransactionalSet<String> set = new TransactionalSet<>(
				new HashSet<>(Set.of("a", "b", "c", "d"))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			set.add("e");
			// removed and re-added inside the savepoint - only an ABSOLUTE inverse still ends at "present"
			set.remove("a");
			set.add("a");
			set.addAll(Set.of("f", "g"));
			set.removeAll(Set.of("b", "f"));
			set.retainAll(Set.of("a", "c", "d", "e"));
			assertEquals(Set.of("a", "c", "d", "e"), new HashSet<>(set), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				Set.of("a", "b", "c", "d"), new HashSet<>(set), "Rollback must restore the pre-savepoint set."
			);
		}

		@Test
		@DisplayName("Rollback restores elements removed through the iterator and removeIf")
		void shouldRestoreRemovalsThroughIterator() {
			final TransactionalSet<String> set = new TransactionalSet<>(new HashSet<>(Set.of("a", "b", "c")));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			final Iterator<String> it = set.iterator();
			while (it.hasNext()) {
				if ("a".equals(it.next())) {
					it.remove();
				}
			}
			set.removeIf("b"::equals);
			assertEquals(Set.of("c"), new HashSet<>(set), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				Set.of("a", "b", "c"), new HashSet<>(set),
				"Removing through the iterator reaches the delegate directly and must be journaled."
			);
		}

		@Test
		@DisplayName("Rollback restores a set that was cleared inside the savepoint")
		void shouldRestoreClearedSet() {
			final Set<String> delegate = new LinkedHashSet<>(List.of("first", "second", "third"));
			final TransactionalSet<String> set = new TransactionalSet<>(delegate);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			set.clear();
			set.add("late");
			savepoint.rollback();

			assertEquals(
				List.of("first", "second", "third"), new ArrayList<>(delegate),
				"A wholesale restore must put the elements back in the order the delegate had them."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepSetOnCommit() {
			final TransactionalSet<String> set = new TransactionalSet<>(new HashSet<>(Set.of("a")));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			set.add("b");
			set.remove("a");
			savepoint.commit();

			assertEquals(Set.of("b"), new HashSet<>(set), "Commit must keep the savepoint's writes.");
		}
	}

	@Nested
	@DisplayName("TransactionalList")
	class Lists {

		@Test
		@DisplayName("Rollback restores the list through every positional mutator")
		void shouldRestoreListAfterMixedMutations() {
			final TransactionalList<String> list = new TransactionalList<>(
				new ArrayList<>(List.of("a", "b", "c"))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			list.add("d");
			list.add(0, "z");
			list.set(2, "B");
			list.remove("c");
			list.remove(0);
			list.addAll(List.of("e", "f"));
			assertEquals(List.of("a", "B", "d", "e", "f"), new ArrayList<>(list), "self-check on in-savepoint state");
			savepoint.rollback();

			assertEquals(
				List.of("a", "b", "c"), new ArrayList<>(list),
				"Reverse replay must un-shift the positional inverses back to the pre-savepoint list."
			);
		}

		@Test
		@DisplayName("Rollback restores the list after bulk removals driven by the iterator")
		void shouldRestoreBulkRemovals() {
			final TransactionalList<String> list = new TransactionalList<>(
				new ArrayList<>(List.of("a", "b", "c", "d", "e"))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			list.removeAll(List.of("a", "c"));
			list.retainAll(List.of("b", "d"));
			assertEquals(List.of("b", "d"), new ArrayList<>(list), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				List.of("a", "b", "c", "d", "e"), new ArrayList<>(list),
				"removeAll and retainAll run through the iterator, which must journal every removal."
			);
		}

		@Test
		@DisplayName("Rollback restores writes made through the list iterator")
		void shouldRestoreListIteratorWrites() {
			final TransactionalList<String> list = new TransactionalList<>(
				new ArrayList<>(List.of("a", "b", "c"))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			final ListIterator<String> it = list.listIterator();
			it.next();
			it.set("A");
			it.add("inserted");
			it.next();
			it.remove();
			assertEquals(List.of("A", "inserted", "c"), new ArrayList<>(list), "self-check on in-savepoint state");
			savepoint.rollback();

			assertEquals(
				List.of("a", "b", "c"), new ArrayList<>(list),
				"set, add and remove on a list iterator all reach the delegate and must be journaled."
			);
		}

		@Test
		@DisplayName("Rollback restores a list rewritten through replaceAll and sort")
		void shouldRestoreReplaceAllAndSort() {
			final TransactionalList<String> list = new TransactionalList<>(
				new ArrayList<>(List.of("c", "a", "b"))
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			list.replaceAll(String::toUpperCase);
			list.sort(Comparator.naturalOrder());
			assertEquals(List.of("A", "B", "C"), new ArrayList<>(list), "self-check on the in-savepoint state");
			savepoint.rollback();

			assertEquals(
				List.of("c", "a", "b"), new ArrayList<>(list),
				"Both defaults write through the list iterator, which is exactly why it is wrapped."
			);
		}

		@Test
		@DisplayName("Rollback restores a list that was cleared inside the savepoint")
		void shouldRestoreClearedList() {
			final TransactionalList<String> list = new TransactionalList<>(new ArrayList<>(List.of("a", "b")));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			list.clear();
			list.add("c");
			savepoint.rollback();

			assertEquals(List.of("a", "b"), new ArrayList<>(list), "Rollback must restore a cleared list wholesale.");
		}

		@Test
		@DisplayName("Removing an element the list does not hold changes nothing to rewind")
		void shouldReportMissingElementRemovalFaithfully() {
			final TransactionalList<String> list = new TransactionalList<>(new ArrayList<>(List.of("a")));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			assertFalse(list.remove("missing"), "A removal of an absent element must still report false.");
			savepoint.rollback();

			assertEquals(List.of("a"), new ArrayList<>(list), "Nothing changed, so nothing may be rewound either.");
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepListOnCommit() {
			final TransactionalList<String> list = new TransactionalList<>(new ArrayList<>(List.of("a")));

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			list.add("b");
			list.remove("a");
			savepoint.commit();

			assertEquals(List.of("b"), new ArrayList<>(list), "Commit must keep the savepoint's writes.");
		}
	}

}
