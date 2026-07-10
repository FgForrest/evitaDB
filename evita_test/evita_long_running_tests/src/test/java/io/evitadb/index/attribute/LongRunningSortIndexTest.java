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

package io.evitadb.index.attribute;

import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized stress test for {@link SortIndex}. Verifies the contract under random
 * sequences of add/remove operations applied within transactional commit boundaries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningSortIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "SortIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Random rnd = new Random();
		final int initialCount = 100;
		final TreeSet<ValueRecord> setToCompare = new TreeSet<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(256),
				new OwnerSortIndex(String.class, new AttributeIndexKey(null, "whatever", null))
			),
			(random, testState) -> {
				final StringBuilder ops = testState.code();
				ops.append("final SortIndex sortIndex = new OwnerSortIndex(String.class);\n")
					.append(
						setToCompare.stream()
							.map(it -> "sortIndex.addRecord(\"" + it.value() + "\"," + it.recordId() + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");

				final SortIndex sortIndex = testState.sortIndex();
				final AtomicReference<SortIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					sortIndex,
					original -> applyRandomBatch(rnd, sortIndex, setToCompare, currentRecordSet, ops, initialCount),
					(original, committed) -> {
						final int[] expected = setToCompare.stream().mapToInt(ValueRecord::recordId).toArray();
						assertArrayEquals(
							expected,
							committed.getAscendingOrderRecordsSupplier().getSortedRecordIds(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(
								committed.getAscendingOrderRecordsSupplier().getSortedRecordIds()
							) + "\n\n" + ops
						);

						// rebuild the (array + sparse-cardinality-map) form from the consolidated value→cardinality tree;
						// the (array, map) constructor follows the legacy convention where cardinality 1 is implied, so
						// only entries greater than one go into the map
						final Serializable[] committedValues = committed.getSortedRecordValues();
						final Map<Serializable, Integer> committedCardinalities = new HashMap<>();
						for (final Serializable value : committedValues) {
							final int cardinality = committed.getValueCardinality(value);
							if (cardinality > 1) {
								committedCardinalities.put(value, cardinality);
							}
						}
						committedResult.set(
							new OwnerSortIndex(
								committed.comparatorBase,
								null,
								committed.getAttributeIndexKey(),
								committed.sortedRecords.getArray(),
								committedValues,
								committedCardinalities
							)
						);
					}
				);

				return new TestState(
					new StringBuilder(512),
					committedResult.get()
				);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * {@link SortIndex} byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a
	 * fresh index from the (random-walking) reference model, captures a value oracle of that base, applies the same
	 * random add/remove batch the commit proof uses inside a transaction that is then rolled back, and asserts the base
	 * index is unchanged and no committed value was published.
	 *
	 * @param input input for the test
	 */
	@ParameterizedTest(name = "SortIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(@Nonnull GenerationalTestInput input) {
		final int initialCount = 100;
		final TreeSet<ValueRecord> setToCompare = new TreeSet<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new StringBuilder(256),
			(random, ops) -> {
				// rebuild a fresh index from the (random-walking) reference model that the rollback must return to
				final SortIndex sortIndex = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "whatever", null));
				for (final ValueRecord record : setToCompare) {
					sortIndex.addRecord(record.value(), record.recordId());
				}
				// value oracle of the base state that the rollback must restore
				final SortSnapshot beforeRollback = snapshot(sortIndex);

				assertStateAfterRollback(
					sortIndex,
					original -> applyRandomBatch(random, original, setToCompare, currentRecordSet, ops, initialCount),
					(original, committed) -> {
						assertNull(
							committed,
							"A rolled-back transaction must not publish a committed value!\n" + ops
						);
						assertEquals(
							beforeRollback, snapshot(original),
							"SortIndex changed after rollback — atomic rollback leaked!\n" + ops
						);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new StringBuilder(256);
			}
		);
	}

	/**
	 * Applies a random batch of add/remove operations to `sortIndex`, mirroring each mutation into the `setToCompare` /
	 * `currentRecordSet` reference model so the two stay in lockstep. Shared by the commit and rollback proofs so both
	 * drive the identical workload.
	 *
	 * @param rnd              the randomness source
	 * @param sortIndex        the index the batch mutates
	 * @param setToCompare     the ordered reference model of `value → recordId` pairs
	 * @param currentRecordSet the reference set of live record ids (kept unique on insert)
	 * @param ops              the reproduction-code buffer appended to for every mutation
	 * @param initialCount     the seed count bounding the random record-id range
	 */
	private static void applyRandomBatch(
		@Nonnull Random rnd,
		@Nonnull SortIndex sortIndex,
		@Nonnull TreeSet<ValueRecord> setToCompare,
		@Nonnull Set<Integer> currentRecordSet,
		@Nonnull StringBuilder ops,
		int initialCount
	) {
		try {
			final int operationsInTransaction = rnd.nextInt(100);
			for (int i = 0; i < operationsInTransaction; i++) {
				final int length = sortIndex.size();
				if ((rnd.nextBoolean() || length < 10) && length < 50) {
					// insert new item
					final String newValue = Character.toString(65 + rnd.nextInt(28));
					int newRecId;
					do {
						newRecId = rnd.nextInt(initialCount * 2);
					} while (currentRecordSet.contains(newRecId));
					setToCompare.add(new ValueRecord(newValue, newRecId));
					currentRecordSet.add(newRecId);

					ops.append("sortIndex.addRecord(\"")
						.append(newValue).append("\",")
						.append(newRecId).append(");\n");
					sortIndex.addRecord(newValue, newRecId);
				} else {
					// remove existing item
					final Iterator<ValueRecord> it = setToCompare.iterator();
					ValueRecord valueToRemove = null;
					for (int j = 0; j < rnd.nextInt(length) + 1; j++) {
						valueToRemove = it.next();
					}
					it.remove();
					currentRecordSet.remove(valueToRemove.recordId());

					ops.append("sortIndex.removeRecord(\"")
						.append(valueToRemove.value()).append("\",")
						.append(valueToRemove.recordId()).append(");\n");
					sortIndex.removeRecord(valueToRemove.value(), valueToRemove.recordId());
				}
			}
		} catch (Exception ex) {
			fail("\n" + ops, ex);
		}
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: the ascending sorted record ids,
	 * the distinct sorted attribute values, and each value's cardinality — so two snapshots taken before and after a
	 * rollback can be compared with `.equals` to prove exact restoration of both the record ordering and the internal
	 * value-cardinality structure.
	 *
	 * @param index the sort index to snapshot
	 * @return a value-comparable snapshot of the index content
	 */
	@Nonnull
	static SortSnapshot snapshot(@Nonnull SortIndex index) {
		final int[] ascending = index.getAscendingOrderRecordsSupplier().getSortedRecordIds();
		final List<Integer> ascendingIds = new ArrayList<>(ascending.length);
		for (final int recordId : ascending) {
			ascendingIds.add(recordId);
		}
		final Serializable[] values = index.getSortedRecordValues();
		final List<Serializable> sortedValues = new ArrayList<>(values.length);
		final Map<Serializable, Integer> cardinalities = new HashMap<>(values.length);
		for (final Serializable value : values) {
			sortedValues.add(value);
			cardinalities.put(value, index.getValueCardinality(value));
		}
		return new SortSnapshot(ascendingIds, sortedValues, cardinalities);
	}

	/**
	 * Value-comparable snapshot of a {@link SortIndex}: the ascending sorted record ids, the distinct sorted attribute
	 * values, and each value's cardinality. Record equality gives deep structural comparison.
	 *
	 * @param ascending     the ascending sorted record ids
	 * @param values        the distinct attribute values in sorted order
	 * @param cardinalities the number of records held by each value
	 */
	record SortSnapshot(
		@Nonnull List<Integer> ascending,
		@Nonnull List<Serializable> values,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {}

	private record TestState(
		StringBuilder code,
		SortIndex sortIndex
	) {

	}

	private record ValueRecord(String value, int recordId) implements Comparable<ValueRecord> {
		@Override
		public int compareTo(ValueRecord o) {
			final int cmp1 = this.value.compareTo(o.value);
			return cmp1 == 0 ? Integer.compare(this.recordId, o.recordId) : cmp1;
		}

	}
}
