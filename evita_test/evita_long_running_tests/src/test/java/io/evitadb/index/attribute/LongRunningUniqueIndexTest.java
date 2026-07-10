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
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
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
 * Generational randomized stress test for {@link UniqueIndex}. Besides the forward commit proof it also drives the
 * transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback path is
 * exercised by the sibling {@code LongRunningSavepointUniqueIndexTest} (Ref: #1252). All three share the same random
 * register/unregister batch and the same value-comparable {@link #snapshot(UniqueIndex)} oracle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningUniqueIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "UniqueIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> mapToCompare = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(256),
				1,
				new OwnerUniqueIndex(Entities.PRODUCT, new AttributeIndexKey(null, "code", null), String.class)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final UniqueIndex uniqueIndex = new OwnerUniqueIndex(\"code\", String.class);\n")
					.append(mapToCompare.entrySet().stream().map(it -> "uniqueIndex.registerUniqueKey(\"" + it.getKey() + "\"," + it.getValue() + ");").collect(Collectors.joining("\n")));
				codeBuffer.append("\nOps:\n");
				final UniqueIndex transactionalUniqueIndex = testState.initialState();
				final AtomicReference<UniqueIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalUniqueIndex,
					original -> applyRandomBatch(random, original, mapToCompare, currentRecordSet, codeBuffer, initialCount, testState.iteration()),
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						assertArrayEquals(
							expected,
							committed.getRecordIds().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getRecordIds().getArray()) + "\n\n" +
								codeBuffer
						);

						final UniqueIndex.InlineSnapshot snap = committed.inlineSnapshot();
						committedResult.set(
							new OwnerUniqueIndex(
								committed.getEntityType(),
								committed.getAttributeIndexKey(),
								committed.getType(),
								snap.values(),
								snap.recordIds()
							)
						);
					}
				);
				return new TestState(
					new StringBuilder(256),
					testState.iteration() + 1,
					committedResult.get()
				);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies the shared random batch
	 * of register/unregister mutations inside a transaction that is then rolled back, and asserts the base index is
	 * unchanged and no committed value was published.
	 */
	@ParameterizedTest(name = "UniqueIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> mapToCompare = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			1,
			(random, iteration) -> {
				final StringBuilder codeBuffer = new StringBuilder(256);
				// rebuild a fresh base index from the (random-walking) reference model
				final OwnerUniqueIndex uniqueIndex = buildReferenceIndex(mapToCompare, codeBuffer);
				codeBuffer.append("\nOps:\n");
				// value oracle of the base state that the rollback must return to
				final UniqueSnapshot beforeRollback = snapshot(uniqueIndex);

				assertStateAfterRollback(
					uniqueIndex,
					original -> applyRandomBatch(random, original, mapToCompare, currentRecordSet, codeBuffer, initialCount, iteration),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"UniqueIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				return iteration + 1;
			}
		);
	}

	/**
	 * Applies a random batch of register / unregister mutations to `uniqueIndex`, mirroring each mutation into the
	 * `mapToCompare` / `currentRecordSet` reference model so the two stay in lockstep. Shared verbatim by the commit and
	 * rollback proofs, so the commit path keeps the identical random-draw sequence.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull UniqueIndex uniqueIndex,
		@Nonnull Map<String, Integer> mapToCompare,
		@Nonnull Set<Integer> currentRecordSet,
		@Nonnull StringBuilder codeBuffer,
		int initialCount,
		int iteration
	) {
		try {
			final int operationsInTransaction = random.nextInt(100);
			for (int i = 0; i < operationsInTransaction; i++) {
				final int length = uniqueIndex.size();
				if ((random.nextBoolean() || length < 10) && length < 50) {
					// insert new item
					final String newValue = Character.toString(65 + random.nextInt(28)) + "_" + ((iteration * 100) + i);
					int newRecId;
					do {
						newRecId = random.nextInt(initialCount << 1);
					} while (currentRecordSet.contains(newRecId));
					mapToCompare.put(newValue, newRecId);
					currentRecordSet.add(newRecId);

					codeBuffer.append("uniqueIndex.registerUniqueKey(\"").append(newValue).append("\", product, ").append(newRecId).append(");\n");
					uniqueIndex.registerUniqueKey(newValue, newRecId);
				} else {
					// remove existing item
					final Iterator<Entry<String, Integer>> it = mapToCompare.entrySet().iterator();
					Entry<String, Integer> valueToRemove = null;
					for (int j = 0; j < random.nextInt(length) + 1; j++) {
						valueToRemove = it.next();
					}
					it.remove();
					currentRecordSet.remove(valueToRemove.getValue());

					codeBuffer.append("uniqueIndex.unregisterUniqueKey(\"").append(valueToRemove.getKey()).append("\", product,").append(valueToRemove.getValue()).append(");\n");
					uniqueIndex.unregisterUniqueKey(valueToRemove.getKey(), valueToRemove.getValue());
				}
			}
		} catch (Exception ex) {
			fail("\n" + codeBuffer, ex);
		}
	}

	/**
	 * Rebuilds a fresh {@link OwnerUniqueIndex} from the `mapToCompare` reference model by registering every
	 * `(value, recordId)` pair, appending the reconstruction to `codeBuffer` for failure diagnostics. Used by the
	 * rollback proof so each generation starts from a base index that matches the (random-walking) model exactly.
	 */
	@Nonnull
	private static OwnerUniqueIndex buildReferenceIndex(
		@Nonnull Map<String, Integer> mapToCompare,
		@Nonnull StringBuilder codeBuffer
	) {
		final OwnerUniqueIndex uniqueIndex =
			new OwnerUniqueIndex(Entities.PRODUCT, new AttributeIndexKey(null, "code", null), String.class);
		for (final Entry<String, Integer> entry : mapToCompare.entrySet()) {
			codeBuffer.append("uniqueIndex.registerUniqueKey(\"").append(entry.getKey()).append("\", ").append(entry.getValue()).append(");\n");
			uniqueIndex.registerUniqueKey(entry.getKey(), entry.getValue());
		}
		return uniqueIndex;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: the unique value → owning record id
	 * mapping (read off the transaction-aware inline snapshot). Two snapshots taken before and after a rollback can be
	 * compared with `.equals` to prove exact restoration; the same helper doubles as the savepoint oracle reader.
	 */
	@Nonnull
	static UniqueSnapshot snapshot(@Nonnull UniqueIndex index) {
		final UniqueIndex.InlineSnapshot inline = index.inlineSnapshot();
		final Serializable[] values = inline.values();
		final int[] recordIds = inline.recordIds();
		final Map<String, Integer> valueToRecord = new HashMap<>(values.length);
		for (int i = 0; i < values.length; i++) {
			valueToRecord.put(String.valueOf(values[i]), recordIds[i]);
		}
		return new UniqueSnapshot(valueToRecord);
	}

	private record TestState(
		StringBuilder code,
		int iteration,
		UniqueIndex initialState
	) {
	}

	/**
	 * Value-comparable snapshot of a {@link UniqueIndex}: unique value string → owning record id. Record equality gives
	 * deep structural comparison.
	 */
	record UniqueSnapshot(
		@Nonnull Map<String, Integer> valueToRecord
	) {}

}
