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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Generational property-based stress test for {@link ReducedEntityIndex}.
 * Runs randomized operations (PK insert/remove, locale upsert/remove) over multiple
 * generations, comparing committed state against JDK reference implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReducedEntityIndex generational proof")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class LongRunningReducedEntityIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int REFERENCED_PK = 1;

	private static final Locale[] TEST_LOCALES = {
		Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("cs")
	};

	@Nonnull
	static ReducedEntityIndex createInstance() {
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
		);
		return new ReducedEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
		);
	}

	@Nonnull
	static EntitySchemaContract createEvolvingSchema() {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(Set.of());
		when(schema.getEvolutionMode()).thenReturn(EnumSet.of(EvolutionMode.ADDING_LOCALES));
		return schema;
	}

	@DisplayName("survives generational randomized test")
	@ParameterizedTest(name = "ReducedEntityIndex should survive generational randomized test")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final EntitySchemaContract schema = createEvolvingSchema();

		runFor(
			input,
			50_000,
			new GenerationalState(
				new HashSet<>(),
				new HashMap<>(),
				createInstance()
			),
			(random, state) -> {
				final ReducedEntityIndex tested = state.index();
				final Set<Integer> referencePks = new HashSet<>(state.expectedPks());
				final Map<Locale, Set<Integer>> referenceLocales =
					deepCopyLocaleMap(state.expectedLocales());
				final AtomicReference<ReducedEntityIndex> committedRef = new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> applyRandomBatch(
						random, original, referencePks, referenceLocales, schema
					),
					(original, committed) -> {
						assertNotNull(committed);
						final ReducedEntityIndex typed =
							(ReducedEntityIndex) committed;
						verifyState(typed, referencePks, referenceLocales);
						committedRef.set(typed);
					}
				);

				return new GenerationalState(
					referencePks, referenceLocales, committedRef.get()
				);
			},
			(state, exc) -> {
				System.out.println("Failed state - PKs: " + state.expectedPks());
				System.out.println("Failed state - Locales: " + state.expectedLocales());
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random batch of
	 * PK / locale add-remove mutations inside a transaction that is then rolled back, and asserts the base index is
	 * unchanged and no committed value was published.
	 */
	@DisplayName("rollback discards every in-transaction mutation and leaves the base intact")
	@ParameterizedTest(
		name = "ReducedEntityIndex rollback discards every in-transaction mutation and leaves the base intact"
	)
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		final EntitySchemaContract schema = createEvolvingSchema();

		runFor(
			input,
			50_000,
			new GenerationalState(
				new HashSet<>(),
				new HashMap<>(),
				createInstance()
			),
			(random, state) -> {
				// the model random-walks across generations; use it in place (no defensive copy) so the
				// attempted (rolled-back) batch keeps exploring fresh base indexes on the next generation
				final Set<Integer> referencePks = state.expectedPks();
				final Map<Locale, Set<Integer>> referenceLocales = state.expectedLocales();
				// rebuild a fresh base index from the current reference model
				final ReducedEntityIndex index = buildIndex(referencePks, referenceLocales, schema);
				// value oracle of the base state that the rollback must return to
				final ReducedIndexSnapshot beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(
						random, original, referencePks, referenceLocales, schema
					),
					(original, committed) -> {
						assertNull(
							committed,
							"A rolled-back transaction must not publish a committed value!"
						);
						assertEquals(
							beforeRollback, snapshot(original),
							"ReducedEntityIndex changed after rollback — atomic rollback leaked!"
						);
					}
				);

				return new GenerationalState(referencePks, referenceLocales, index);
			},
			(state, exc) -> {
				System.out.println("Failed state - PKs: " + state.expectedPks());
				System.out.println("Failed state - Locales: " + state.expectedLocales());
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 add/remove PK / locale mutations to `index`, mirroring each mutation into the
	 * `referencePks` / `referenceLocales` reference model so the two stay in lockstep. Shared by the commit and
	 * rollback proofs so both drive the identical random-draw sequence.
	 *
	 * @param random           source of randomness
	 * @param index            the index being mutated
	 * @param referencePks     the reference model for primary keys
	 * @param referenceLocales the reference model for locale-to-PK mapping
	 * @param schema           the entity schema allowing locale evolution
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull ReducedEntityIndex index,
		@Nonnull Set<Integer> referencePks,
		@Nonnull Map<Locale, Set<Integer>> referenceLocales,
		@Nonnull EntitySchemaContract schema
	) {
		final int ops = random.nextInt(5) + 1;
		for (int i = 0; i < ops; i++) {
			executeRandomOperation(random, index, referencePks, referenceLocales, schema);
		}
	}

	/**
	 * Executes a random operation on both the index under test and the reference model.
	 *
	 * @param random           source of randomness
	 * @param index            the index being tested
	 * @param referencePks     the reference model for primary keys
	 * @param referenceLocales the reference model for locale-to-PK mapping
	 * @param schema           the entity schema allowing locale evolution
	 */
	private static void executeRandomOperation(
		@Nonnull Random random,
		@Nonnull ReducedEntityIndex index,
		@Nonnull Set<Integer> referencePks,
		@Nonnull Map<Locale, Set<Integer>> referenceLocales,
		@Nonnull EntitySchemaContract schema
	) {
		final int operation = random.nextInt(4);
		final int pk = random.nextInt(50) + 1;

		switch (operation) {
			case 0 -> {
				// insert PK
				index.insertPrimaryKeyIfMissing(pk);
				referencePks.add(pk);
			}
			case 1 -> {
				// remove PK (only if exists in reference)
				if (!referencePks.isEmpty()) {
					final int targetPk = referencePks.iterator().next();
					index.removePrimaryKey(targetPk);
					referencePks.remove(targetPk);
				}
			}
			case 2 -> {
				// upsert language
				final Locale locale = TEST_LOCALES[random.nextInt(TEST_LOCALES.length)];
				index.upsertLanguage(locale, pk, schema);
				referenceLocales.computeIfAbsent(locale, l -> new HashSet<>()).add(pk);
			}
			case 3 -> {
				// remove language (only if exists)
				if (!referenceLocales.isEmpty()) {
					final Locale locale = referenceLocales.keySet().iterator().next();
					final Set<Integer> pksForLocale = referenceLocales.get(locale);
					if (!pksForLocale.isEmpty()) {
						final int targetPk = pksForLocale.iterator().next();
						index.removeLanguage(locale, targetPk);
						pksForLocale.remove(targetPk);
						if (pksForLocale.isEmpty()) {
							referenceLocales.remove(locale);
						}
					}
				}
			}
		}
	}

	/**
	 * Verifies that the committed index state matches the reference model.
	 *
	 * @param committed       the committed index after STM transaction
	 * @param expectedPks     the expected set of primary keys
	 * @param expectedLocales the expected locale-to-PK mapping
	 */
	private static void verifyState(
		@Nonnull ReducedEntityIndex committed,
		@Nonnull Set<Integer> expectedPks,
		@Nonnull Map<Locale, Set<Integer>> expectedLocales
	) {
		// verify PKs
		final Bitmap allPks = committed.getAllPrimaryKeys();
		assertEquals(
			expectedPks.size(), allPks.size(),
			"PK count mismatch. Expected: " + expectedPks + ", got bitmap size: " + allPks.size()
		);
		for (int pk : expectedPks) {
			assertTrue(allPks.contains(pk), "Missing PK: " + pk);
		}

		// verify locales
		assertEquals(
			expectedLocales.size(), committed.getLanguages().size(),
			"Locale count mismatch"
		);
		for (Map.Entry<Locale, Set<Integer>> entry : expectedLocales.entrySet()) {
			final Locale locale = entry.getKey();
			final Set<Integer> expectedPksForLocale = entry.getValue();
			final Bitmap localeBitmap =
				committed.getRecordsWithLanguageFormula(locale).compute();
			assertEquals(
				expectedPksForLocale.size(), localeBitmap.size(),
				"PK count mismatch for locale " + locale
			);
			for (int pk : expectedPksForLocale) {
				assertTrue(
					localeBitmap.contains(pk),
					"Missing PK " + pk + " for locale " + locale
				);
			}
		}
	}

	/**
	 * Creates a deep copy of the locale map to avoid shared mutable state between generations.
	 *
	 * @param original the map to copy
	 * @return a new map with independent sets for each locale
	 */
	@Nonnull
	private static Map<Locale, Set<Integer>> deepCopyLocaleMap(
		@Nonnull Map<Locale, Set<Integer>> original
	) {
		final Map<Locale, Set<Integer>> copy = new HashMap<>(original.size());
		for (Map.Entry<Locale, Set<Integer>> entry : original.entrySet()) {
			copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}
		return copy;
	}

	/**
	 * Rebuilds a fresh {@link ReducedEntityIndex} from the reference model outside any transaction, replaying every
	 * primary key and locale-to-PK entry. Used by the rollback proof to reconstruct the base index the rollback must
	 * return to.
	 *
	 * @param referencePks     the primary keys to insert
	 * @param referenceLocales the locale-to-PK mapping to upsert
	 * @param schema           the entity schema allowing locale evolution
	 * @return a freshly built index matching the reference model
	 */
	@Nonnull
	private static ReducedEntityIndex buildIndex(
		@Nonnull Set<Integer> referencePks,
		@Nonnull Map<Locale, Set<Integer>> referenceLocales,
		@Nonnull EntitySchemaContract schema
	) {
		final ReducedEntityIndex index = createInstance();
		for (final int pk : referencePks) {
			index.insertPrimaryKeyIfMissing(pk);
		}
		for (final Map.Entry<Locale, Set<Integer>> entry : referenceLocales.entrySet()) {
			for (final int pk : entry.getValue()) {
				index.upsertLanguage(entry.getKey(), pk, schema);
			}
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (all primary keys plus, per
	 * language tag, the sorted primary keys carrying that locale), so two snapshots taken before and after a rollback
	 * can be compared with `.equals` to prove exact restoration. Shared as the oracle reader by the sibling savepoint
	 * test.
	 *
	 * @param index the index to snapshot
	 * @return a deeply `.equals`-comparable snapshot of the index content
	 */
	@Nonnull
	static ReducedIndexSnapshot snapshot(@Nonnull ReducedEntityIndex index) {
		final List<Integer> primaryKeys = toList(index.getAllPrimaryKeys());
		final Map<String, List<Integer>> localePks = new HashMap<>();
		for (final Locale locale : index.getLanguages()) {
			localePks.put(
				locale.toLanguageTag(),
				toList(index.getRecordsWithLanguageFormula(locale).compute())
			);
		}
		return new ReducedIndexSnapshot(primaryKeys, localePks);
	}

	/**
	 * Converts a bitmap into an ascending list of its record ids (a value type with deep `.equals`).
	 *
	 * @param bitmap the bitmap to convert
	 * @return the bitmap's record ids in ascending order
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

	/**
	 * State carried between generations in the generational proof test.
	 *
	 * @param expectedPks     the expected set of primary keys
	 * @param expectedLocales the expected locale-to-PK mapping
	 * @param index           the committed index to use in the next generation
	 */
	private record GenerationalState(
		@Nonnull Set<Integer> expectedPks,
		@Nonnull Map<Locale, Set<Integer>> expectedLocales,
		@Nonnull ReducedEntityIndex index
	) {}

	/**
	 * Value-comparable snapshot of a {@link ReducedEntityIndex}: all primary keys (sorted) and the per-language-tag
	 * sorted primary keys carrying that locale. Record equality gives deep structural comparison.
	 *
	 * @param primaryKeys all indexed primary keys in ascending order
	 * @param localePks   language tag → sorted primary keys carrying that locale
	 */
	record ReducedIndexSnapshot(
		@Nonnull List<Integer> primaryKeys,
		@Nonnull Map<String, List<Integer>> localePks
	) {}
}
