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

package io.evitadb.index;

import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational property-based stress test for {@link ReferencedTypeEntityIndex}. Runs randomized PK
 * insert/remove pairs with cardinality tracking and filter attributes, comparing committed state
 * against a JDK reference implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReferencedTypeEntityIndex (generational proof)")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(REFERENCE)
class LongRunningReferencedTypeEntityIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "BRAND";

	@Nonnull
	private static ReferencedTypeEntityIndex createInstance() {
		return new ReferencedTypeEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
	}

	/**
	 * Runs the generational proof test with randomized insert/remove operations.
	 */
	@DisplayName("survives generational randomized test")
	@ParameterizedTest(name = "ReferencedTypeEntityIndex should survive generational test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			50_000,
			new GenerationalState(
				new HashMap<>(32),
				createInstance()
			),
			(random, state) -> {
				final ReferencedTypeEntityIndex tested = state.index();
				// deep copy the reference model
				final Map<Integer, Set<Integer>> referenceModel =
					deepCopyModel(state.expectedPkToRefs());
				final AtomicReference<ReferencedTypeEntityIndex> committedRef =
					new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> applyRandomBatch(random, original, referenceModel),
					(original, committed) -> {
						assertNotNull(committed);
						verifyState(committed, referenceModel);
						committedRef.set(committed);
					}
				);

				return new GenerationalState(
					referenceModel, committedRef.get()
				);
			},
			(state, exc) -> {
				System.out.println(
					"Failed state - PK->refs: " +
						state.expectedPkToRefs()
				);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random batch of
	 * insert/remove mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and
	 * no committed value was published.
	 */
	@DisplayName("rollback leaves the base index intact")
	@ParameterizedTest(name = "ReferencedTypeEntityIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		runFor(
			input,
			50_000,
			new RollbackModel(new HashMap<>(32)),
			(random, model) -> {
				// rebuild a fresh base index from the (random-walking) reference model each generation
				final Map<Integer, Set<Integer>> referenceModel =
					deepCopyModel(model.expectedPkToRefs());
				final ReferencedTypeEntityIndex tested = buildIndexFromModel(referenceModel);
				// value oracle of the base state that the rollback must return to
				final ReferencedTypeSnapshot beforeRollback = snapshot(tested);

				assertStateAfterRollback(
					tested,
					original -> applyRandomBatch(random, original, referenceModel),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!"
						);
						assertEquals(beforeRollback, snapshot(original),
							"ReferencedTypeEntityIndex changed after rollback — atomic rollback leaked!"
						);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation rebuilds from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new RollbackModel(referenceModel);
			},
			(model, exc) -> System.out.println(
				"Failed model - PK->refs: " + model.expectedPkToRefs()
			)
		);
	}

	/**
	 * Applies a random batch of 1–5 insert/remove operations to `index`, mirroring each into the reference model so the
	 * two stay in lockstep. Shared by the commit and rollback proofs so both drive the identical random-draw sequence.
	 *
	 * @param random         the random generator
	 * @param index          the index being mutated
	 * @param referenceModel the reference model tracking expected state
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull ReferencedTypeEntityIndex index,
		@Nonnull Map<Integer, Set<Integer>> referenceModel
	) {
		final int ops = random.nextInt(5) + 1;
		for (int i = 0; i < ops; i++) {
			executeRandomOperation(random, index, referenceModel);
		}
	}

	/**
	 * Builds a fresh {@link ReferencedTypeEntityIndex} whose logical content exactly matches the reference model, so a
	 * snapshot taken right after the build equals the model. Used to seed each rollback generation from the walking
	 * model.
	 *
	 * @param referenceModel the reference PK-to-referenced-entity mapping to replay
	 * @return a freshly built index materialising the reference model
	 */
	@Nonnull
	private static ReferencedTypeEntityIndex buildIndexFromModel(
		@Nonnull Map<Integer, Set<Integer>> referenceModel
	) {
		final ReferencedTypeEntityIndex index = createInstance();
		for (final Map.Entry<Integer, Set<Integer>> entry : referenceModel.entrySet()) {
			for (final int refPk : entry.getValue()) {
				index.insertPrimaryKeyIfMissing(entry.getKey(), refPk);
			}
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: the sorted index primary keys plus,
	 * per referenced entity PK, the sorted index PKs that reference it. Two snapshots taken before and after a rollback
	 * can be compared with `.equals` to prove exact restoration.
	 *
	 * @param index the index to snapshot
	 * @return a deeply `.equals`-comparable value snapshot of the index content
	 */
	@Nonnull
	static ReferencedTypeSnapshot snapshot(@Nonnull ReferencedTypeEntityIndex index) {
		final List<Integer> pks = toList(index.getAllPrimaryKeys().getArray());
		final Map<Integer, List<Integer>> refToIndexes = new HashMap<>();
		for (final int refPk : index.getAllReferencedPrimaryKeys().getArray()) {
			refToIndexes.put(refPk, toList(index.getAllReferenceIndexes(refPk)));
		}
		return new ReferencedTypeSnapshot(pks, refToIndexes);
	}

	/**
	 * Converts an int array into an ascending list of its values (a value type with deep `.equals`); the input is copied
	 * before sorting so the caller's array is never mutated.
	 *
	 * @param array the values to convert
	 * @return an ascending list of the values
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull int[] array) {
		final int[] sorted = array.clone();
		Arrays.sort(sorted);
		final List<Integer> list = new ArrayList<>(sorted.length);
		for (final int value : sorted) {
			list.add(value);
		}
		return list;
	}

	/**
	 * Executes a random insert or remove operation on the index and the reference model.
	 *
	 * @param random         the random generator
	 * @param index          the index under test
	 * @param referenceModel the reference model tracking expected state
	 */
	private static void executeRandomOperation(
		@Nonnull Random random,
		@Nonnull ReferencedTypeEntityIndex index,
		@Nonnull Map<Integer, Set<Integer>> referenceModel
	) {
		final int indexPk = random.nextInt(30) + 1;
		final int refPk = random.nextInt(20) + 1;

		if (random.nextBoolean()) {
			// insert -- the production index tracks multiset cardinality; to keep the Set-based
			// model in sync we skip duplicate inserts (they would increment the production
			// cardinality without changing the Set, causing a diverging remove later)
			final Set<Integer> refs =
				referenceModel.computeIfAbsent(indexPk, k -> new HashSet<>(4));
			if (refs.add(refPk)) {
				index.insertPrimaryKeyIfMissing(indexPk, refPk);
			}
		} else {
			// remove -- only if the exact (indexPk, refPk) pair exists
			final Set<Integer> refs = referenceModel.get(indexPk);
			if (refs != null && refs.contains(refPk)) {
				index.removePrimaryKey(indexPk, refPk);
				refs.remove(refPk);
				if (refs.isEmpty()) {
					referenceModel.remove(indexPk);
				}
			}
		}
	}

	/**
	 * Verifies the committed index state matches the reference model.
	 *
	 * @param committed     the committed index instance
	 * @param expectedModel the expected PK-to-referenced-entity mapping
	 */
	private static void verifyState(
		@Nonnull ReferencedTypeEntityIndex committed,
		@Nonnull Map<Integer, Set<Integer>> expectedModel
	) {
		final Bitmap allPks = committed.getAllPrimaryKeys();
		assertEquals(
			expectedModel.size(), allPks.size(),
			"PK count mismatch. Expected: " + expectedModel.keySet() +
				", got bitmap size: " + allPks.size()
		);

		for (Map.Entry<Integer, Set<Integer>> entry :
			expectedModel.entrySet()) {
			final int indexPk = entry.getKey();
			assertTrue(
				allPks.contains(indexPk),
				"Missing index PK: " + indexPk
			);
			// verify referenced entity lookup for each ref PK
			for (int refPk : entry.getValue()) {
				final int[] refIndexes =
					committed.getAllReferenceIndexes(refPk);
				boolean found = false;
				for (int refIndex : refIndexes) {
					if (refIndex == indexPk) {
						found = true;
						break;
					}
				}
				assertTrue(found,
					"Index PK " + indexPk +
						" not found in reference indexes " +
						"for referenced entity PK " + refPk
				);
			}
		}
	}

	/**
	 * Creates a deep copy of the PK-to-references model.
	 *
	 * @param original the original model to copy
	 * @return a deep copy with independent mutable sets
	 */
	@Nonnull
	private static Map<Integer, Set<Integer>> deepCopyModel(
		@Nonnull Map<Integer, Set<Integer>> original
	) {
		final Map<Integer, Set<Integer>> copy =
			CollectionUtils.createHashMap(original.size());
		for (Map.Entry<Integer, Set<Integer>> entry :
			original.entrySet()) {
			copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * State carried between generations in the generational proof test.
	 *
	 * @param expectedPkToRefs mapping of index PK to its set of referenced entity PKs
	 * @param index            the committed index to use in the next generation
	 */
	private record GenerationalState(
		@Nonnull Map<Integer, Set<Integer>> expectedPkToRefs,
		@Nonnull ReferencedTypeEntityIndex index
	) {}

	/**
	 * Reference model carried between generations of the rollback proof: the expected PK-to-referenced-entity mapping.
	 * The base index is rebuilt fresh from this model at the start of each generation.
	 *
	 * @param expectedPkToRefs mapping of index PK to its set of referenced entity PKs
	 */
	private record RollbackModel(
		@Nonnull Map<Integer, Set<Integer>> expectedPkToRefs
	) {}

	/**
	 * Value-comparable snapshot of a {@link ReferencedTypeEntityIndex}: the sorted index primary keys and, per
	 * referenced entity PK, the sorted index PKs that reference it. Record equality gives deep structural comparison.
	 *
	 * @param pks          sorted index primary keys held by the index
	 * @param refToIndexes per referenced entity PK, the sorted referencing index PKs
	 */
	record ReferencedTypeSnapshot(
		@Nonnull List<Integer> pks,
		@Nonnull Map<Integer, List<Integer>> refToIndexes
	) {}
}
