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

import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
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
import java.util.List;
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
 * Tests for {@link GlobalEntityIndex}. Extends {@link AbstractEntityIndexTest} to inherit
 * common EntityIndex behavior tests, and adds tests specific to GlobalEntityIndex:
 * STM commit/rollback, proxy stub creation, PriceSuperIndex isEmpty integration,
 * and generational property-based stress testing.
 *
 * @author evitaDB
 */
@DisplayName("GlobalEntityIndex")
class GlobalEntityIndexTest extends AbstractEntityIndexTest<GlobalEntityIndex>
	implements TimeBoundedTestSupport {

	private static final int INDEX_PK = 1;

	@Nonnull
	@Override
	protected GlobalEntityIndex createInstance() {
		return new GlobalEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
	}

	/**
	 * Tests for GlobalEntityIndex-specific isEmpty behavior including PriceSuperIndex.
	 */
	@Nested
	@DisplayName("GlobalEntityIndex isEmpty")
	class GlobalIsEmptyTest {

		@Test
		@DisplayName("should check priceIndex in isEmpty")
		void shouldCheckPriceIndexInIsEmpty() {
			// the GlobalEntityIndex.isEmpty() additionally checks priceIndex.isPriceIndexEmpty()
			// on a fresh index, both parent and price index are empty
			assertTrue(GlobalEntityIndexTest.this.index.isEmpty());

			// adding a PK makes it non-empty (parent check)
			GlobalEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10);
			assertFalse(GlobalEntityIndexTest.this.index.isEmpty());
		}
	}

	/**
	 * Tests for the ByteBuddy proxy stub created by {@link GlobalEntityIndex#createThrowingStub}.
	 */
	@Nested
	@DisplayName("Throwing stub proxy")
	class ThrowingStubTest {

		@Test
		@DisplayName("should create non-null proxy instance")
		void shouldCreateProxy() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of(1, 2, 3)
			);

			assertNotNull(stub);
		}

		@Test
		@DisplayName("should return 0 as ID")
		void shouldReturnZeroId() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of()
			);

			assertEquals(0L, stub.getId());
		}

		@Test
		@DisplayName("should return correct index key")
		void shouldReturnIndexKey() {
			final EntityIndexKey key = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE, key, List.of()
			);

			assertEquals(key, stub.getIndexKey());
		}

		@Test
		@DisplayName("should return bitmap from provided collection")
		void shouldReturnAllPrimaryKeys() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of(10, 20, 30)
			);

			final Bitmap bitmap = stub.getAllPrimaryKeys();
			assertEquals(3, bitmap.size());
			assertTrue(bitmap.contains(10));
			assertTrue(bitmap.contains(20));
			assertTrue(bitmap.contains(30));
		}

		@Test
		@DisplayName("should return correct formula from provided collection")
		void shouldReturnAllPrimaryKeysFormula() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of(10, 20)
			);

			final Formula formula = stub.getAllPrimaryKeysFormula();
			assertInstanceOf(ConstantFormula.class, formula);
			final Bitmap computed = formula.compute();
			assertEquals(2, computed.size());
		}

		@Test
		@DisplayName("should return EmptyBitmap for empty collection")
		void shouldReturnEmptyBitmapForEmptyCollection() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of()
			);

			assertSame(EmptyBitmap.INSTANCE, stub.getAllPrimaryKeys());
		}

		@Test
		@DisplayName("should return EmptyFormula for empty collection")
		void shouldReturnEmptyFormulaForEmptyCollection() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of()
			);

			assertSame(EmptyFormula.INSTANCE, stub.getAllPrimaryKeysFormula());
		}

		@Test
		@DisplayName("should throw EntityNotManagedException for other methods")
		void shouldThrowForOtherMethods() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of()
			);

			assertThrows(
				EntityNotManagedException.class,
				() -> stub.insertPrimaryKeyIfMissing(1)
			);
		}

		@Test
		@DisplayName("should not throw for Object methods (toString, hashCode, equals)")
		void shouldNotThrowForObjectMethods() {
			final GlobalEntityIndex stub = GlobalEntityIndex.createThrowingStub(
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
				List.of()
			);

			assertDoesNotThrow(stub::toString);
			assertDoesNotThrow(stub::hashCode);
			assertDoesNotThrow(() -> stub.equals(stub));
		}
	}

	/**
	 * Tests for STM transactional commit behavior.
	 */
	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("should commit PK insert and preserve in new instance")
		void shouldCommitPkInsert() {
			assertStateAfterCommit(
				GlobalEntityIndexTest.this.index,
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
			// pre-populate
			GlobalEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10);

			assertStateAfterCommit(
				GlobalEntityIndexTest.this.index,
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
				GlobalEntityIndexTest.this.index,
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
				GlobalEntityIndexTest.this.index,
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
				GlobalEntityIndexTest.this.index,
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
	 * Tests for STM transactional rollback behavior.
	 */
	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("should discard PK insert on rollback")
		void shouldDiscardPkInsertOnRollback() {
			assertStateAfterRollback(
				GlobalEntityIndexTest.this.index,
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
				GlobalEntityIndexTest.this.index,
				original -> original.upsertLanguage(Locale.ENGLISH, 10, schema),
				(original, committed) -> {
					assertFalse(original.getLanguages().contains(Locale.ENGLISH));
					assertNull(committed);
				}
			);
		}
	}

	/**
	 * Generational property-based stress test for {@link GlobalEntityIndex}.
	 * Runs randomized operations (PK insert/remove, locale upsert/remove) over multiple
	 * generations, comparing committed state against a JDK reference implementation.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		private static final Locale[] TEST_LOCALES = {
			Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("cs")
		};

		@DisplayName("survives generational randomized test")
		@ParameterizedTest(name = "GlobalEntityIndex should survive generational randomized test")
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
					new GlobalEntityIndex(
						INDEX_PK, ENTITY_TYPE,
						new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
					)
				),
				(random, state) -> {
					final GlobalEntityIndex tested = state.index();
					final Set<Integer> referencePks = new HashSet<>(state.expectedPks());
					final Map<Locale, Set<Integer>> referenceLocales = deepCopyLocaleMap(state.expectedLocales());
					final AtomicReference<GlobalEntityIndex> committedRef = new AtomicReference<>();

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
							verifyState(committed, referencePks, referenceLocales);
							committedRef.set(committed);
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
		 * Executes a random operation on both the index and the reference model.
		 *
		 * @param random           source of randomness
		 * @param index            the index being tested
		 * @param referencePks     the reference model for primary keys
		 * @param referenceLocales the reference model for locale-to-PK mapping
		 * @param schema           the entity schema allowing locale evolution
		 */
		private static void executeRandomOperation(
			@Nonnull Random random,
			@Nonnull GlobalEntityIndex index,
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
			@Nonnull GlobalEntityIndex committed,
			@Nonnull Set<Integer> expectedPks,
			@Nonnull Map<Locale, Set<Integer>> expectedLocales
		) {
			// verify PKs
			final Bitmap allPks = committed.getAllPrimaryKeys();
			assertEquals(expectedPks.size(), allPks.size(),
				"PK count mismatch. Expected: " + expectedPks + ", got bitmap size: " + allPks.size()
			);
			for (int pk : expectedPks) {
				assertTrue(allPks.contains(pk), "Missing PK: " + pk);
			}

			// verify locales
			assertEquals(expectedLocales.size(), committed.getLanguages().size(),
				"Locale count mismatch"
			);
			for (Map.Entry<Locale, Set<Integer>> entry : expectedLocales.entrySet()) {
				final Locale locale = entry.getKey();
				final Set<Integer> expectedPksForLocale = entry.getValue();
				final Bitmap localeBitmap = committed.getRecordsWithLanguageFormula(locale).compute();
				assertEquals(expectedPksForLocale.size(), localeBitmap.size(),
					"PK count mismatch for locale " + locale
				);
				for (int pk : expectedPksForLocale) {
					assertTrue(localeBitmap.contains(pk),
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
		@Nonnull GlobalEntityIndex index
	) {}
}
