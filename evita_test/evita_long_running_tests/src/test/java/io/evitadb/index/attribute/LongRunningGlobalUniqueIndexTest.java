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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
 * Generational randomized stress test for {@link GlobalUniqueIndex}. Besides the forward commit proof it also drives the
 * transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback path is
 * exercised by the sibling {@code LongRunningSavepointGlobalUniqueIndexTest} (Ref: #1252). All three share the same
 * random register/unregister batch and the same value-comparable {@link #snapshot(GlobalUniqueIndex)} oracle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningGlobalUniqueIndexTest implements TimeBoundedTestSupport {
	/**
	 * Mock catalog backing {@link #CLASSIFIER_RESOLVER}; resolves {@link Entities#PRODUCT} to its primary key `1`.
	 */
	private static final Catalog CLASSIFIER_CATALOG = createClassifierCatalog();

	/**
	 * Translates between entity type name and primary key by delegating to {@link #CLASSIFIER_CATALOG}. Threaded into
	 * every global-unique operation that now requires it; shared statically so the {@link #snapshot} oracle (used as a
	 * method reference by the sibling savepoint test) can reach it.
	 */
	private static final EntityTypeClassifierResolver CLASSIFIER_RESOLVER = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return CLASSIFIER_CATALOG.getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return CLASSIFIER_CATALOG.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
		}
	};

	/**
	 * Builds the mock catalog that {@link #CLASSIFIER_RESOLVER} delegates to, mapping {@link Entities#PRODUCT} to
	 * primary key `1` in both directions.
	 */
	@Nonnull
	private static Catalog createClassifierCatalog() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		final Catalog catalog = Mockito.mock(Catalog.class);
		Mockito.when(catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
		return catalog;
	}

	@ParameterizedTest(name = "GlobalUniqueIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> mapToCompare = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final GlobalUniqueIndex initialUniqueIndex = new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class);

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(256),
				1,
				initialUniqueIndex
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final Classifier product = new Classifier(Entities.PRODUCT);\nfinal GlobalUniqueIndex uniqueIndex = new GlobalUniqueIndex(\"code\", String.class);\n")
					.append(
						mapToCompare.entrySet()
							.stream()
							.map(it -> "uniqueIndex.registerUniqueKey(\"" + it.getKey() + "\"," + it.getValue() + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");
				final GlobalUniqueIndex transactionalUniqueIndex = testState.initialState();
				final AtomicReference<GlobalUniqueIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalUniqueIndex,
					original -> applyRandomBatch(random, original, mapToCompare, currentRecordSet, codeBuffer, initialCount, testState.iteration()),
					(original, committed) -> {
						final EntityReference[] expected = currentRecordSet.stream()
							.map(it -> new EntityReference(Entities.PRODUCT, it))
							.sorted()
							.toArray(EntityReference[]::new);

						assertArrayEquals(
							expected,
							committed.getEntityReferences(CLASSIFIER_RESOLVER),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getEntityReferences(CLASSIFIER_RESOLVER)) + "\n\n" +
								codeBuffer
						);

						final GlobalUniqueIndex.InlineSnapshot snapshot = committed.inlineSnapshot();
						final GlobalUniqueIndex newGlobalUniqueIndex = new GlobalUniqueIndex(
							Scope.LIVE,
							committed.getAttributeKey(),
							committed.getType(),
							snapshot.values(),
							snapshot.payloads(),
							new HashMap<>(committed.getLocaleIndex())
						);
						committedResult.set(newGlobalUniqueIndex);
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
	@ParameterizedTest(name = "GlobalUniqueIndex rollback discards every in-transaction mutation and leaves the base intact")
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
				final GlobalUniqueIndex uniqueIndex = buildReferenceIndex(mapToCompare, codeBuffer);
				codeBuffer.append("\nOps:\n");
				// value oracle of the base state that the rollback must return to
				final GlobalUniqueSnapshot beforeRollback = snapshot(uniqueIndex);

				assertStateAfterRollback(
					uniqueIndex,
					original -> applyRandomBatch(random, original, mapToCompare, currentRecordSet, codeBuffer, initialCount, iteration),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"GlobalUniqueIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
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
		@Nonnull GlobalUniqueIndex uniqueIndex,
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
						newRecId = random.nextInt(initialCount * 2);
					} while (currentRecordSet.contains(newRecId));
					mapToCompare.put(newValue, newRecId);
					currentRecordSet.add(newRecId);

					codeBuffer.append("uniqueIndex.registerUniqueKey(\"").append(newValue).append("\", product, ").append(newRecId).append(");\n");
					uniqueIndex.registerUniqueKey(newValue, Entities.PRODUCT, null, newRecId, CLASSIFIER_RESOLVER);
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
					uniqueIndex.unregisterUniqueKey(valueToRemove.getKey(), Entities.PRODUCT, null, valueToRemove.getValue(), CLASSIFIER_RESOLVER);
				}
			}
		} catch (Exception ex) {
			fail("\n" + codeBuffer, ex);
		}
	}

	/**
	 * Rebuilds a fresh {@link GlobalUniqueIndex} (attached to the mocked catalog) from the `mapToCompare` reference model
	 * by registering every `(value, recordId)` pair, appending the reconstruction to `codeBuffer` for failure
	 * diagnostics. Used by the rollback proof so each generation starts from a base index that matches the
	 * (random-walking) model exactly.
	 */
	@Nonnull
	private GlobalUniqueIndex buildReferenceIndex(
		@Nonnull Map<String, Integer> mapToCompare,
		@Nonnull StringBuilder codeBuffer
	) {
		final GlobalUniqueIndex uniqueIndex = new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class);
		for (final Entry<String, Integer> entry : mapToCompare.entrySet()) {
			codeBuffer.append("uniqueIndex.registerUniqueKey(\"").append(entry.getKey()).append("\", ").append(entry.getValue()).append(");\n");
			uniqueIndex.registerUniqueKey(entry.getKey(), Entities.PRODUCT, null, entry.getValue(), CLASSIFIER_RESOLVER);
		}
		return uniqueIndex;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: the unique value → packed entity
	 * tuple mapping (read off the transaction-aware inline snapshot) plus the per-entity-type record-id bitmap for
	 * {@link Entities#PRODUCT} (a separate transactional structure). Packed payloads are stable within a transaction —
	 * the entity-type id and locale id assignments do not change — so two snapshots taken before and after a rollback
	 * can be compared with `.equals` to prove exact restoration. The same helper doubles as the savepoint oracle reader.
	 */
	@Nonnull
	static GlobalUniqueSnapshot snapshot(@Nonnull GlobalUniqueIndex index) {
		final GlobalUniqueIndex.InlineSnapshot inline = index.inlineSnapshot();
		final Serializable[] values = inline.values();
		final long[] payloads = inline.payloads();
		final Map<String, Long> valueToPayload = new HashMap<>(values.length);
		for (int i = 0; i < values.length; i++) {
			valueToPayload.put(String.valueOf(values[i]), payloads[i]);
		}
		return new GlobalUniqueSnapshot(valueToPayload, toList(index.getRecordIds(Entities.PRODUCT, CLASSIFIER_RESOLVER)));
	}

	/**
	 * Converts a bitmap into an ascending list of its record ids (a value type with deep `.equals`).
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull Bitmap bitmap) {
		final int[] array = bitmap.getArray();
		final List<Integer> list = new ArrayList<>(array.length);
		for (final int value : array) {
			list.add(value);
		}
		return list;
	}

	private record TestState(
		StringBuilder code,
		int iteration,
		GlobalUniqueIndex initialState
	) {}

	/**
	 * Value-comparable snapshot of a {@link GlobalUniqueIndex}: unique value string → packed entity tuple, plus the
	 * sorted record ids registered for {@link Entities#PRODUCT}. Record equality gives deep structural comparison.
	 */
	record GlobalUniqueSnapshot(
		@Nonnull Map<String, Long> valueToPayload,
		@Nonnull List<Integer> productRecords
	) {}

}
