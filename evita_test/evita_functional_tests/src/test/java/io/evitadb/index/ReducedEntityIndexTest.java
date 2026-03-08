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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReducedEntityIndex}. Extends {@link AbstractReducedEntityIndexTest} to inherit
 * common reduced entity index behavior tests (reference key resolution, hierarchy guards,
 * partitioning assertions, locale removal) and adds tests specific to ReducedEntityIndex:
 * constructor type validation, STM commit/rollback, and generational property-based stress testing.
 *
 * @author evitaDB
 */
@DisplayName("ReducedEntityIndex")
class ReducedEntityIndexTest extends AbstractReducedEntityIndexTest<ReducedEntityIndex>
	implements TimeBoundedTestSupport {

	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int REFERENCED_PK = 1;

	@Nonnull
	@Override
	protected ReducedEntityIndex createInstance() {
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
		);
		return new ReducedEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
		);
	}

	/**
	 * Tests that the constructor rejects invalid {@link EntityIndexType} values and accepts only
	 * {@link EntityIndexType#REFERENCED_ENTITY}.
	 */
	@Nested
	@DisplayName("Constructor type validation")
	class ConstructorTypeValidationTest {

		@Test
		@DisplayName("should accept REFERENCED_ENTITY type")
		void shouldAcceptReferencedEntityType() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
			);
			final ReducedEntityIndex created = new ReducedEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
			);

			assertNotNull(created);
			assertEquals(EntityIndexType.REFERENCED_ENTITY, created.getIndexKey().type());
		}

		@Test
		@DisplayName("should reject GLOBAL type")
		void shouldRejectGlobalType() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_ENTITY"),
				"Error message should mention the expected type"
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY_TYPE type")
		void shouldRejectReferencedEntityTypeType() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "CATEGORY")
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_ENTITY"),
				"Error message should mention the expected type"
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_GROUP_ENTITY type")
		void shouldRejectReferencedGroupEntityType() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, REFERENCED_PK)
			);
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_ENTITY"),
				"Error message should mention the expected type"
			);
		}
	}

	/**
	 * Tests for STM transactional commit behavior specific to {@link ReducedEntityIndex}.
	 */
	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("should commit PK insert and preserve in new instance")
		void shouldCommitPkInsert() {
			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10);
					original.insertPrimaryKeyIfMissing(20);
				},
				(original, committed) -> {
					// original should still be empty (no PKs were committed to it)
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					// committed should have the PKs
					assertNotNull(committed);
					assertTrue(committed.getAllPrimaryKeys().contains(10));
					assertTrue(committed.getAllPrimaryKeys().contains(20));
				}
			);
		}

		@Test
		@DisplayName("should commit PK removal")
		void shouldCommitPkRemoval() {
			// pre-populate outside transaction
			ReducedEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10);

			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> original.removePrimaryKey(10),
				(original, committed) -> {
					// original still has PK 10
					assertTrue(original.getAllPrimaryKeys().contains(10));
					// committed should not
					assertNotNull(committed);
					assertFalse(committed.getAllPrimaryKeys().contains(10));
				}
			);
		}

		@Test
		@DisplayName("should increment version when dirty")
		void shouldIncrementVersionWhenDirty() {
			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> original.insertPrimaryKeyIfMissing(10),
				(original, committed) -> {
					assertEquals(1, original.version());
					assertNotNull(committed);
					assertEquals(2, committed.version());
				}
			);
		}

		@Test
		@DisplayName("should not increment version when clean")
		void shouldNotIncrementVersionWhenClean() {
			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> {
					// no mutations
				},
				(original, committed) -> {
					assertEquals(1, original.version());
					assertNotNull(committed);
					assertEquals(1, committed.version());
				}
			);
		}

		@Test
		@DisplayName("should commit language changes")
		void shouldCommitLanguageChanges() {
			final EntitySchemaContract schema = createEvolvingSchema();

			assertStateAfterCommit(
				ReducedEntityIndexTest.this.index,
				original -> original.upsertLanguage(Locale.ENGLISH, 10, schema),
				(original, committed) -> {
					assertFalse(original.getLanguages().contains(Locale.ENGLISH));
					assertNotNull(committed);
					assertTrue(committed.getLanguages().contains(Locale.ENGLISH));
				}
			);
		}
	}

	/**
	 * Tests for STM transactional rollback behavior specific to {@link ReducedEntityIndex}.
	 */
	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("should discard PK insert on rollback")
		void shouldDiscardPkInsertOnRollback() {
			assertStateAfterRollback(
				ReducedEntityIndexTest.this.index,
				original -> original.insertPrimaryKeyIfMissing(10),
				(original, committed) -> {
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("should discard language upsert on rollback")
		void shouldDiscardLanguageOnRollback() {
			final EntitySchemaContract schema = createEvolvingSchema();

			assertStateAfterRollback(
				ReducedEntityIndexTest.this.index,
				original -> original.upsertLanguage(Locale.ENGLISH, 10, schema),
				(original, committed) -> {
					assertFalse(original.getLanguages().contains(Locale.ENGLISH));
					assertNull(committed);
				}
			);
		}
	}

	/**
	 * Tests for {@link ReducedEntityIndex#toString()} output format.
	 */
	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should return descriptive string containing class name and index key")
		void shouldReturnDescriptiveToString() {
			final String result = ReducedEntityIndexTest.this.index.toString();

			assertNotNull(result);
			assertTrue(
				result.startsWith("ReducedEntityIndex"),
				"toString should start with class name, but was: " + result
			);
		}
	}

	/**
	 * Generational property-based stress test for {@link ReducedEntityIndex}.
	 * Runs randomized operations (PK insert/remove, locale upsert/remove) over multiple
	 * generations, comparing committed state against JDK reference implementations.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		private static final Locale[] TEST_LOCALES = {
			Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("cs")
		};

		@DisplayName("survives generational randomized test")
		@ParameterizedTest(name = "ReducedEntityIndex should survive generational randomized test")
		@Tag(LONG_RUNNING_TEST)
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
						original -> {
							final int ops = random.nextInt(5) + 1;
							for (int i = 0; i < ops; i++) {
								executeRandomOperation(
									random, original, referencePks, referenceLocales, schema
								);
							}
						},
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
}
