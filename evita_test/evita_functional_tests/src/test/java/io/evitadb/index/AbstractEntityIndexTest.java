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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Abstract base test class for {@link EntityIndex} behavior. Tests in this class verify
 * the common contract inherited by all concrete EntityIndex subtypes: primary key management,
 * language tracking, isEmpty semantics, and dirty flag behavior.
 *
 * Concrete test classes must implement the factory method {@link #createInstance()} to provide
 * a fresh instance of their specific EntityIndex subtype.
 *
 * STM commit/rollback and generational proof tests are left to concrete subclasses since they
 * require type-specific TransactionalLayerProducer parameterization.
 *
 * @param <T> the concrete EntityIndex subtype being tested
 * @author evitaDB
 */
abstract class AbstractEntityIndexTest<T extends EntityIndex> {

	protected static final String ENTITY_TYPE = "Product";

	/**
	 * The index instance under test, recreated before each test method.
	 */
	protected T index;

	/**
	 * Creates a fresh, empty instance of the concrete EntityIndex subtype.
	 *
	 * @return a new empty index instance
	 */
	@Nonnull
	protected abstract T createInstance();

	/**
	 * Creates an {@link EntitySchemaContract} mock with the given allowed locales
	 * and no schema evolution (locales are fixed).
	 *
	 * @param allowedLocales the set of allowed locales
	 * @return a mocked entity schema
	 */
	@Nonnull
	protected EntitySchemaContract createSchema(@Nonnull Set<Locale> allowedLocales) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(allowedLocales);
		when(schema.getEvolutionMode()).thenReturn(EnumSet.noneOf(EvolutionMode.class));
		return schema;
	}

	/**
	 * Creates an {@link EntitySchemaContract} mock that allows adding new locales
	 * via {@link EvolutionMode#ADDING_LOCALES}.
	 *
	 * @return a mocked entity schema permitting locale evolution
	 */
	@Nonnull
	protected EntitySchemaContract createEvolvingSchema() {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getLocales()).thenReturn(Set.of());
		when(schema.getEvolutionMode()).thenReturn(EnumSet.of(EvolutionMode.ADDING_LOCALES));
		return schema;
	}

	/**
	 * Inserts a primary key into the index. Subclasses may override this to use a different
	 * insertion method (e.g., two-arg variant for cardinality-aware indexes).
	 *
	 * @param index           the index to insert into
	 * @param entityPrimaryKey the entity primary key to insert
	 */
	protected void insertPk(@Nonnull T index, int entityPrimaryKey) {
		index.insertPrimaryKeyIfMissing(entityPrimaryKey);
	}

	/**
	 * Removes a primary key from the index. Subclasses may override this to use a different
	 * removal method (e.g., two-arg variant for cardinality-aware indexes).
	 *
	 * @param index           the index to remove from
	 * @param entityPrimaryKey the entity primary key to remove
	 */
	protected void removePk(@Nonnull T index, int entityPrimaryKey) {
		index.removePrimaryKey(entityPrimaryKey);
	}

	@BeforeEach
	void setUp() {
		this.index = createInstance();
	}

	/**
	 * Tests for primary key management in {@link EntityIndex}.
	 */
	@Nested
	@DisplayName("Primary key management")
	class PrimaryKeyManagementTest {

		@Test
		@DisplayName("should add PK to bitmap on first insert")
		void shouldInsertPrimaryKey() {
			insertPk(AbstractEntityIndexTest.this.index, 10);

			assertTrue(AbstractEntityIndexTest.this.index.isPrimaryKeyKnown(10));
			assertTrue(AbstractEntityIndexTest.this.index.getAllPrimaryKeys().contains(10));
		}

		@Test
		@DisplayName("should not duplicate PK on repeated insert")
		void shouldNotDuplicatePkOnRepeatedInsert() {
			insertPk(AbstractEntityIndexTest.this.index, 10);
			insertPk(AbstractEntityIndexTest.this.index, 10);

			assertEquals(1, AbstractEntityIndexTest.this.index.getAllPrimaryKeys().size());
		}

		@Test
		@DisplayName("should remove PK from bitmap on removal")
		void shouldRemovePrimaryKey() {
			insertPk(AbstractEntityIndexTest.this.index, 10);

			removePk(AbstractEntityIndexTest.this.index, 10);

			assertFalse(AbstractEntityIndexTest.this.index.isPrimaryKeyKnown(10));
		}

		@Test
		@DisplayName("should track PK presence through insert-check-remove-check cycle")
		void shouldTrackPrimaryKeyPresence() {
			assertFalse(AbstractEntityIndexTest.this.index.isPrimaryKeyKnown(10));

			insertPk(AbstractEntityIndexTest.this.index, 10);
			assertTrue(AbstractEntityIndexTest.this.index.isPrimaryKeyKnown(10));

			removePk(AbstractEntityIndexTest.this.index, 10);
			assertFalse(AbstractEntityIndexTest.this.index.isPrimaryKeyKnown(10));
		}

		@Test
		@DisplayName("should return bitmap containing all inserted PKs")
		void shouldReturnAllPrimaryKeys() {
			insertPk(AbstractEntityIndexTest.this.index, 10);
			insertPk(AbstractEntityIndexTest.this.index, 20);
			insertPk(AbstractEntityIndexTest.this.index, 30);

			final Bitmap allPks = AbstractEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(3, allPks.size());
			assertTrue(allPks.contains(10));
			assertTrue(allPks.contains(20));
			assertTrue(allPks.contains(30));
		}

		@Test
		@DisplayName("should return EmptyFormula when no PKs exist")
		void shouldReturnEmptyFormulaWhenEmpty() {
			final Formula formula = AbstractEntityIndexTest.this.index.getAllPrimaryKeysFormula();

			assertSame(EmptyFormula.INSTANCE, formula);
		}

		@Test
		@DisplayName("should return ConstantFormula when PKs exist")
		void shouldReturnConstantFormulaWhenNonEmpty() {
			insertPk(AbstractEntityIndexTest.this.index, 10);

			final Formula formula = AbstractEntityIndexTest.this.index.getAllPrimaryKeysFormula();

			assertInstanceOf(ConstantFormula.class, formula);
		}

		@Test
		@DisplayName("should also detect PK via contains method")
		void shouldDetectPkViaContains() {
			assertFalse(AbstractEntityIndexTest.this.index.contains(10));

			insertPk(AbstractEntityIndexTest.this.index, 10);
			assertTrue(AbstractEntityIndexTest.this.index.contains(10));
		}
	}

	/**
	 * Tests for language/locale tracking in {@link EntityIndex}.
	 */
	@Nested
	@DisplayName("Language tracking")
	class LanguageTrackingTest {

		@Test
		@DisplayName("should upsert language and track locale")
		void shouldUpsertLanguage() {
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));

			final boolean added = AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);

			assertTrue(added);
			assertTrue(AbstractEntityIndexTest.this.index.getLanguages().contains(Locale.ENGLISH));
		}

		@Test
		@DisplayName("should return false on duplicate language upsert for same PK")
		void shouldReturnFalseOnDuplicateUpsert() {
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);

			final boolean addedAgain = AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);

			assertFalse(addedAgain);
		}

		@Test
		@DisplayName("should return formula with correct PKs for a locale")
		void shouldReturnLanguageFormula() {
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 20, schema);

			final Formula formula = AbstractEntityIndexTest.this.index.getRecordsWithLanguageFormula(Locale.ENGLISH);

			assertNotSame(EmptyFormula.INSTANCE, formula);
			final Bitmap result = formula.compute();
			assertEquals(2, result.size());
			assertTrue(result.contains(10));
			assertTrue(result.contains(20));
		}

		@Test
		@DisplayName("should return EmptyFormula for unknown locale")
		void shouldReturnEmptyFormulaForUnknownLocale() {
			final Formula formula = AbstractEntityIndexTest.this.index.getRecordsWithLanguageFormula(Locale.CHINESE);

			assertSame(EmptyFormula.INSTANCE, formula);
		}

		@Test
		@DisplayName("should remove language and clean up empty locale entry")
		void shouldRemoveLanguage() {
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);

			AbstractEntityIndexTest.this.index.removeLanguage(Locale.ENGLISH, 10);

			// locale entry should be cleaned up when bitmap becomes empty
			assertFalse(AbstractEntityIndexTest.this.index.getLanguages().contains(Locale.ENGLISH));
		}

		@Test
		@DisplayName("should track multiple locales independently")
		void shouldTrackMultipleLocales() {
			final EntitySchemaContract schema = createSchema(
				Set.of(Locale.ENGLISH, Locale.GERMAN)
			);
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.GERMAN, 20, schema);

			assertEquals(2, AbstractEntityIndexTest.this.index.getLanguages().size());
			assertTrue(AbstractEntityIndexTest.this.index.getLanguages().contains(Locale.ENGLISH));
			assertTrue(AbstractEntityIndexTest.this.index.getLanguages().contains(Locale.GERMAN));

			AbstractEntityIndexTest.this.index.removeLanguage(Locale.ENGLISH, 10);
			assertEquals(1, AbstractEntityIndexTest.this.index.getLanguages().size());
			assertTrue(AbstractEntityIndexTest.this.index.getLanguages().contains(Locale.GERMAN));
		}

		@Test
		@DisplayName("should accept any locale when schema allows evolution")
		void shouldAcceptLocaleWithEvolutionMode() {
			final EntitySchemaContract schema = createEvolvingSchema();

			// Locale.CHINESE is not in allowedLocales but schema allows evolution
			final boolean added = AbstractEntityIndexTest.this.index.upsertLanguage(Locale.CHINESE, 10, schema);

			assertTrue(added);
			assertTrue(AbstractEntityIndexTest.this.index.getLanguages().contains(Locale.CHINESE));
		}

		@Test
		@DisplayName("should reject locale not allowed by schema without evolution")
		void shouldRejectDisallowedLocale() {
			final EntitySchemaContract schema = createSchema(Set.of(Locale.ENGLISH));

			assertThrows(
				Exception.class,
				() -> AbstractEntityIndexTest.this.index.upsertLanguage(Locale.CHINESE, 10, schema)
			);
		}
	}

	/**
	 * Tests for {@link EntityIndex#isEmpty()} semantics.
	 */
	@Nested
	@DisplayName("isEmpty behavior")
	class IsEmptyTest {

		@Test
		@DisplayName("should be empty on creation")
		void shouldBeEmptyOnCreation() {
			assertTrue(AbstractEntityIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when PKs are present")
		void shouldNotBeEmptyWithPrimaryKeys() {
			insertPk(AbstractEntityIndexTest.this.index, 10);

			assertFalse(AbstractEntityIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("should become empty after all PKs removed")
		void shouldBecomeEmptyAfterAllPksRemoved() {
			insertPk(AbstractEntityIndexTest.this.index, 10);
			removePk(AbstractEntityIndexTest.this.index, 10);

			assertTrue(AbstractEntityIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when language data exists")
		void shouldNotBeEmptyWithLanguages() {
			final EntitySchemaContract schema = createEvolvingSchema();
			AbstractEntityIndexTest.this.index.upsertLanguage(Locale.ENGLISH, 10, schema);

			assertFalse(AbstractEntityIndexTest.this.index.isEmpty());
		}
	}

	/**
	 * Tests for version and identity of {@link EntityIndex}.
	 */
	@Nested
	@DisplayName("Identity and versioning")
	class IdentityTest {

		@Test
		@DisplayName("should have version 1 on creation")
		void shouldHaveInitialVersion() {
			assertEquals(1, AbstractEntityIndexTest.this.index.version());
		}

		@Test
		@DisplayName("should have unique ID")
		void shouldHaveUniqueId() {
			final T other = createInstance();
			assertNotEquals(AbstractEntityIndexTest.this.index.getId(), other.getId());
		}

		@Test
		@DisplayName("should return correct index key")
		void shouldReturnIndexKey() {
			assertNotNull(AbstractEntityIndexTest.this.index.getIndexKey());
		}

		@Test
		@DisplayName("should return correct primary key")
		void shouldReturnPrimaryKey() {
			assertTrue(AbstractEntityIndexTest.this.index.getPrimaryKey() > 0);
		}
	}
}
