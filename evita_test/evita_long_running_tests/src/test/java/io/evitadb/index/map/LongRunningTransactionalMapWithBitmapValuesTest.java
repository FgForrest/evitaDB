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

package io.evitadb.index.map;

import io.evitadb.core.exception.StaleTransactionMemoryException;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.AssertionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link TransactionalMap} parameterised with values that are themselves
 * {@link TransactionalBitmap} ({@link TransactionalLayerProducer}s) — the exact shape used in production by
 * `EntityIndex#entityIdsByLanguage` (`new TransactionalMap<>(map, TransactionalBitmap.class, TransactionalBitmap::new)`).
 *
 * Unlike {@link LongRunningTransactionalMapTest}, which holds plain {@link Integer} values, this sentinel mutates the
 * inner state of producer values inside a transaction (opening an `ALIVE` nested diff layer) and then removes the key
 * — the modify-then-delete pattern that orphans the value's transactional layer if the container does not release it.
 * Each generation runs inside {@link AssertionUtils#assertStateAfterCommit}, which verifies the full
 * transactional-memory layer was swept on commit; a leaked layer surfaces as a
 * {@link StaleTransactionMemoryException} and fails the run. This guards the
 * STM invariants INV-10 / INV-12 across thousands of chained commit cycles.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("TransactionalMap with producer (bitmap) values (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalMapWithBitmapValuesTest implements TimeBoundedTestSupport {

	/**
	 * Upper bound (exclusive) of the record id space stored in the bitmap values. Kept small on purpose so that the
	 * randomized add/remove operations frequently collide and exercise real bitmap churn.
	 */
	private static final int VALUE_BOUND = 50;

	@DisplayName("survives generational randomized modify-then-delete of producer values")
	@ParameterizedTest(name = "TransactionalMap<String, TransactionalBitmap> should survive generational randomized modify-then-delete")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 60;
		final Map<String, int[]> initialState =
			generateRandomInitialContents(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(256), initialState),
			(random, testState) -> {
				// rebuild the transactional map and the JDK reference model from the previously committed contents
				final Map<String, TransactionalBitmap> delegate = new HashMap<>(testState.committedContents().size());
				final Map<String, TreeSet<Integer>> reference = new HashMap<>(testState.committedContents().size());
				for (Entry<String, int[]> entry : testState.committedContents().entrySet()) {
					delegate.put(entry.getKey(), new TransactionalBitmap(entry.getValue()));
					reference.put(entry.getKey(), toSet(entry.getValue()));
				}
				final TransactionalMap<String, TransactionalBitmap> transactionalMap =
					new TransactionalMap<>(delegate, TransactionalBitmap.class, TransactionalBitmap::new);

				// keys that existed at transaction start are the only ones eligible for whole-key removal — this keeps
				// every modify-then-delete on a *committed* producer value (the path the container is contracted to
				// sweep) and avoids the separate fresh-create-then-delete-in-same-txn edge
				final List<String> removableKeys = new ArrayList<>(reference.keySet());

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);
				codeBuffer.append("\nSTART (").append(reference.size()).append(" keys)\n");

				assertStateAfterCommit(
					transactionalMap,
					original -> {
						final int operationsInTransaction = random.nextInt(8);
						for (int i = 0; i < operationsInTransaction; i++) {
							applyRandomOperation(random, transactionalMap, reference, removableKeys, codeBuffer);
						}
						codeBuffer.append("\n");
						// the transactional view must already reflect the reference model before commit
						assertMatches(reference, transactionalMap, codeBuffer);
					},
					(original, committed) -> assertCommittedMatches(reference, committed, codeBuffer)
				);

				return new TestState(codeBuffer, toContentMap(reference));
			}
		);
	}

	/**
	 * Applies a single random operation to both the {@link TransactionalMap} under test and the JDK reference model,
	 * keeping them in lock-step. All operations that open an inner producer layer target either a value that survives
	 * the commit (swept via the merge) or a committed key that is subsequently removed (swept via the removal branch).
	 */
	private static void applyRandomOperation(
		@Nonnull Random random,
		@Nonnull TransactionalMap<String, TransactionalBitmap> transactionalMap,
		@Nonnull Map<String, TreeSet<Integer>> reference,
		@Nonnull List<String> removableKeys,
		@Nonnull StringBuilder codeBuffer
	) {
		final int operation = random.nextInt(10);
		if (operation <= 1 || reference.isEmpty()) {
			// (a) insert a brand-new key with a fresh, non-mutated bitmap value
			final String key = randomKey(random);
			if (reference.containsKey(key)) {
				// avoid replacing an existing instance — fall back to mutating it instead
				mutateExisting(random, transactionalMap, reference, key, codeBuffer);
				return;
			}
			final int[] values = generateRandomArray(random, 1 + random.nextInt(8));
			transactionalMap.put(key, new TransactionalBitmap(values));
			reference.put(key, toSet(values));
			codeBuffer.append("+").append(key).append(":").append(reference.get(key)).append(" ");
		} else if (operation <= 6) {
			// (b) mutate the inner bitmap of an existing key (opens an ALIVE nested layer)
			final String key = pickPresentKey(random, reference);
			if (key != null) {
				mutateExisting(random, transactionalMap, reference, key, codeBuffer);
			}
		} else if (operation <= 8) {
			// (c) modify-then-delete a committed key in the same transaction — the orphaned-layer reproduction
			final String key = pickRemovableKey(random, reference, removableKeys);
			if (key != null) {
				final int value = random.nextInt(VALUE_BOUND);
				transactionalMap.get(key).add(value);
				reference.get(key).add(value);
				transactionalMap.remove(key);
				reference.remove(key);
				removableKeys.remove(key);
				codeBuffer.append("!-").append(key).append(" ");
			}
		} else {
			// (d) plain removal of a committed key
			final String key = pickRemovableKey(random, reference, removableKeys);
			if (key != null) {
				transactionalMap.remove(key);
				reference.remove(key);
				removableKeys.remove(key);
				codeBuffer.append("-").append(key).append(" ");
			}
		}
	}

	/**
	 * Mutates the bitmap stored under the given key by either adding or removing a single record id, mirroring the
	 * change into the reference model. The key always remains present (an emptied bitmap keeps its slot).
	 */
	private static void mutateExisting(
		@Nonnull Random random,
		@Nonnull TransactionalMap<String, TransactionalBitmap> transactionalMap,
		@Nonnull Map<String, TreeSet<Integer>> reference,
		@Nonnull String key,
		@Nonnull StringBuilder codeBuffer
	) {
		final TransactionalBitmap bitmap = transactionalMap.get(key);
		final TreeSet<Integer> referenceValues = reference.get(key);
		if (random.nextBoolean() || referenceValues.isEmpty()) {
			final int value = random.nextInt(VALUE_BOUND);
			bitmap.add(value);
			referenceValues.add(value);
			codeBuffer.append("~").append(key).append("+").append(value).append(" ");
		} else {
			final int index = random.nextInt(referenceValues.size());
			int counter = 0;
			int toRemove = -1;
			for (int candidate : referenceValues) {
				if (counter++ == index) {
					toRemove = candidate;
					break;
				}
			}
			bitmap.remove(toRemove);
			referenceValues.remove(toRemove);
			codeBuffer.append("~").append(key).append("-").append(toRemove).append(" ");
		}
	}

	/**
	 * Asserts that the live transactional view of the map matches the reference model.
	 */
	private static void assertMatches(
		@Nonnull Map<String, TreeSet<Integer>> reference,
		@Nonnull TransactionalMap<String, TransactionalBitmap> transactionalMap,
		@Nonnull StringBuilder codeBuffer
	) {
		assertEquals(reference.size(), transactionalMap.size(), codeBuffer::toString);
		for (Entry<String, TreeSet<Integer>> entry : reference.entrySet()) {
			assertTrue(transactionalMap.containsKey(entry.getKey()), codeBuffer::toString);
			assertArrayEquals(
				toArray(entry.getValue()),
				transactionalMap.get(entry.getKey()).getArray(),
				codeBuffer::toString
			);
		}
	}

	/**
	 * Asserts that the committed map (the merged transactional state) matches the reference model. Reaching this point
	 * also implies the transactional-memory sweep performed by `assertStateAfterCommit` found no orphaned layers.
	 */
	private static void assertCommittedMatches(
		@Nonnull Map<String, TreeSet<Integer>> reference,
		@Nonnull Map<String, TransactionalBitmap> committed,
		@Nonnull StringBuilder codeBuffer
	) {
		assertEquals(reference.size(), committed.size(), codeBuffer::toString);
		for (Entry<String, TreeSet<Integer>> entry : reference.entrySet()) {
			final TransactionalBitmap committedValue = committed.get(entry.getKey());
			assertNotNull(committedValue, codeBuffer::toString);
			assertArrayEquals(toArray(entry.getValue()), committedValue.getArray(), codeBuffer::toString);
		}
	}

	/**
	 * Picks a random key that is currently present in the reference model, or {@code null} if it is empty.
	 */
	@Nullable
	private static String pickPresentKey(@Nonnull Random random, @Nonnull Map<String, TreeSet<Integer>> reference) {
		if (reference.isEmpty()) {
			return null;
		}
		final int index = random.nextInt(reference.size());
		int counter = 0;
		for (String key : reference.keySet()) {
			if (counter++ == index) {
				return key;
			}
		}
		return null;
	}

	/**
	 * Picks a random key that existed at transaction start and is still present, or {@code null} if none qualifies.
	 */
	@Nullable
	private static String pickRemovableKey(
		@Nonnull Random random,
		@Nonnull Map<String, TreeSet<Integer>> reference,
		@Nonnull List<String> removableKeys
	) {
		while (!removableKeys.isEmpty()) {
			final int index = random.nextInt(removableKeys.size());
			final String key = removableKeys.get(index);
			if (reference.containsKey(key)) {
				return key;
			}
			// stale candidate (already removed by another operation) — drop it and retry
			removableKeys.remove(index);
		}
		return null;
	}

	@Nonnull
	private static String randomKey(@Nonnull Random random) {
		return String.valueOf((char) (40 + random.nextInt(80)));
	}

	@Nonnull
	private static Map<String, int[]> generateRandomInitialContents(@Nonnull Random rnd, int count) {
		final Map<String, int[]> initialContents = new HashMap<>(count);
		for (int i = 0; i < count; i++) {
			initialContents.put(randomKey(rnd), generateRandomArray(rnd, 1 + rnd.nextInt(8)));
		}
		return initialContents;
	}

	@Nonnull
	private static int[] generateRandomArray(@Nonnull Random rnd, int count) {
		final TreeSet<Integer> uniqueSet = new TreeSet<>();
		for (int i = 0; i < count; i++) {
			uniqueSet.add(rnd.nextInt(VALUE_BOUND));
		}
		return toArray(uniqueSet);
	}

	@Nonnull
	private static TreeSet<Integer> toSet(@Nonnull int[] values) {
		final TreeSet<Integer> set = new TreeSet<>();
		for (int value : values) {
			set.add(value);
		}
		return set;
	}

	@Nonnull
	private static int[] toArray(@Nonnull TreeSet<Integer> set) {
		final int[] array = new int[set.size()];
		int index = 0;
		for (int value : set) {
			array[index++] = value;
		}
		return array;
	}

	@Nonnull
	private static Map<String, int[]> toContentMap(@Nonnull Map<String, TreeSet<Integer>> reference) {
		final Map<String, int[]> contents = new HashMap<>(reference.size());
		for (Entry<String, TreeSet<Integer>> entry : reference.entrySet()) {
			contents.put(entry.getKey(), toArray(entry.getValue()));
		}
		return contents;
	}

	/**
	 * Holds the state carried between generational test iterations — the committed map contents (per-key sorted record
	 * ids) become the starting point of the next generation.
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<String, int[]> committedContents
	) {
	}

}
