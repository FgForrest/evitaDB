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

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * Tests for {@link ReducedGroupEntityIndex} verifying cardinality-aware primary key tracking
 * and filter attribute cardinality logic. Extends {@link AbstractReducedEntityIndexTest} to inherit
 * common reduced entity index behavior tests (reference key resolution, hierarchy guards,
 * partitioning assertions, locale removal) and adds tests specific to ReducedGroupEntityIndex:
 * cardinality tracking, single-arg method guards, filter attribute cardinality, constructor
 * validation, STM commit/rollback, and generational property-based stress testing.
 *
 * The core purpose of `ReducedGroupEntityIndex` is to handle the scenario where a single entity
 * has multiple references (e.g., to different categories) that all share the same group (e.g., the
 * same brand). The cardinality tracking ensures the entity PK is only added to / removed from the
 * bitmap on transitions to/from zero, preventing premature removal when one reference is deleted
 * but others still point to the same group.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReducedGroupEntityIndex cardinality tracking")
@Tag(INDEXING)
@Tag(MANAGEMENT)
class ReducedGroupEntityIndexTest
	extends AbstractReducedEntityIndexTest<ReducedGroupEntityIndex> {

	private static final String REFERENCE_NAME = "CATEGORY";
	private static final String HISTOGRAM_NAME = "priceHistogram";
	private static final int INDEX_PK = 1;

	/**
	 * Monotonically increasing counter used to generate unique referenced entity PKs
	 * in the two-arg {@link #insertPk} and {@link #removePk} hooks. This ensures each
	 * call to {@code insertPk} uses a distinct referenced entity PK so cardinality is
	 * always incremented by one (no duplicate reference collisions).
	 */
	private int referencedPkCounter = 1000;

	/**
	 * Tracks which referenced entity PK was assigned to each entity PK insertion,
	 * so the matching {@link #removePk} call can supply the correct referenced PK.
	 * Key = entity PK, Value = referenced entity PK used in the most recent insert.
	 */
	private final Map<Integer, Integer> lastReferencedPkForEntity = new HashMap<>(16);

	@Nonnull
	@Override
	protected ReducedGroupEntityIndex createInstance() {
		return createIndex(100);
	}

	@Override
	protected void insertPk(@Nonnull ReducedGroupEntityIndex index, int entityPrimaryKey) {
		final int referencedPk = this.referencedPkCounter++;
		this.lastReferencedPkForEntity.put(entityPrimaryKey, referencedPk);
		index.insertPrimaryKeyIfMissing(entityPrimaryKey, referencedPk);
	}

	@Override
	protected void removePk(@Nonnull ReducedGroupEntityIndex index, int entityPrimaryKey) {
		final Integer referencedPk = this.lastReferencedPkForEntity.get(entityPrimaryKey);
		assertNotNull(referencedPk, "No referenced PK tracked for entity PK " + entityPrimaryKey);
		index.removePrimaryKey(entityPrimaryKey, referencedPk);
	}

	/**
	 * Creates a new {@link ReducedGroupEntityIndex} with the given group primary key.
	 *
	 * @param groupPk the primary key of the group entity (used in the discriminator)
	 * @return a fresh index instance
	 */
	@Nonnull
	@SuppressWarnings("SameParameterValue")
	private static ReducedGroupEntityIndex createIndex(int groupPk) {
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, groupPk)
		);
		final EntityIndexKey key = new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk
		);
		return new ReducedGroupEntityIndex(INDEX_PK, ENTITY_TYPE, key);
	}

	/**
	 * Creates a non-localized, filterable {@link AttributeSchemaContract} stub for testing.
	 *
	 * @param name the attribute name
	 * @param type the attribute value type
	 * @return a new attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract createFilterableAttributeSchema(
		@Nonnull String name, @Nonnull Class<? extends Serializable> type
	) {
		return AttributeSchema._internalBuild(
			name,
			null,
			new Scope[]{Scope.LIVE},
			null,
			false, false, false,
			type, null
		);
	}

	@Nested
	@DisplayName("Primary key cardinality tracking")
	class PrimaryKeyCardinalityTest {

		@Test
		@DisplayName("should add PK to bitmap on first reference insertion")
		void shouldAddPkOnFirstInsertion() {
			// entity PK=10 references category PK=1 with group PK=100
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			final Bitmap allPks = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should not duplicate PK in bitmap when second reference to same group is added")
		void shouldNotDuplicatePkOnSecondInsertion() {
			// entity PK=10 has two references (category 1 and category 2) both sharing group 100
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			final Bitmap allPks = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			// PK should appear exactly once in the bitmap despite cardinality=2
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should retain PK after removing one of two references")
		void shouldRetainPkAfterRemovingOneReference() {
			// entity PK=10 references category 1 and category 2, both in group 100
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			// remove only the reference to category 1
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);

			final Bitmap allPks = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			// PK should still be present because cardinality went from 2 to 1 (not zero)
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should remove PK after removing last reference")
		void shouldRemovePkAfterRemovingLastReference() {
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			// remove both references
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 2);

			final Bitmap allPks = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			assertTrue(allPks.isEmpty());
		}

		@Test
		@DisplayName("should handle single reference insertion and removal correctly")
		void shouldHandleSingleReferenceLifecycle() {
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			assertEquals(1, ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().size());

			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);
			assertTrue(ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().isEmpty());
		}

		@Test
		@DisplayName("should track multiple entities independently")
		void shouldTrackMultipleEntitiesIndependently() {
			// entity 10 -> categories 1 and 2 (cardinality=2 in group)
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			// entity 20 -> category 3 (cardinality=1 in group)
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 3);

			final Bitmap allPks = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(2, allPks.size());
			assertTrue(allPks.contains(10));
			assertTrue(allPks.contains(20));

			// remove one reference for entity 10 -- entity 10 should remain
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);
			assertEquals(2, ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().size());

			// remove single reference for entity 20 -- entity 20 should disappear
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(20, 3);
			final Bitmap remaining = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(1, remaining.size());
			assertTrue(remaining.contains(10));
			assertFalse(remaining.contains(20));
		}

		@Test
		@DisplayName("should track three references to same group from same entity")
		void shouldTrackThreeReferences() {
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 3);

			assertEquals(1, ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().size());

			// remove two of three -- PK should remain
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 2);
			assertEquals(1, ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().size());

			// remove last one -- PK should disappear
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 3);
			assertTrue(ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().isEmpty());
		}

		@Test
		@DisplayName("should report non-empty when cardinality data exists even if bitmap is empty")
		void shouldReportNonEmptyWhenCardinalityExists() {
			// empty index should be empty
			assertTrue(ReducedGroupEntityIndexTest.this.index.isEmpty());

			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			assertFalse(ReducedGroupEntityIndexTest.this.index.isEmpty());
		}
	}

	/**
	 * Tests that the single-arg {@link ReducedGroupEntityIndex#insertPrimaryKeyIfMissing(int)}
	 * and {@link ReducedGroupEntityIndex#removePrimaryKey(int)} throw
	 * {@link UnsupportedOperationException}.
	 */
	@Nested
	@DisplayName("Single-arg method guards")
	class UnsupportedMethodGuardsTest {

		@Test
		@DisplayName("should throw on single-arg insertPrimaryKeyIfMissing")
		void shouldThrowOnSingleArgInsert() {
			assertThrows(
				UnsupportedOperationException.class,
				() -> ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10)
			);
		}

		@Test
		@DisplayName("should throw on single-arg removePrimaryKey")
		void shouldThrowOnSingleArgRemove() {
			assertThrows(
				UnsupportedOperationException.class,
				() -> ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10)
			);
		}
	}

	/**
	 * Tests for error handling when removing primary keys with non-existent references.
	 */
	@Nested
	@DisplayName("Remove error handling")
	class RemoveErrorHandlingTest {

		@Test
		@DisplayName("should throw GenericEvitaInternalError when removing a reference from an empty index")
		void shouldThrowInternalErrorWhenRemovingReferenceFromEmptyIndex() {
			// no data inserted — the internal cardinality assertion must trip with the precise
			// exception type rather than a generic RuntimeException; keeping the assertion tight
			// catches regressions that downgrade the error to a vaguer type.
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 999)
			);
		}

		@Test
		@DisplayName("should throw GenericEvitaInternalError when removing an entity PK that was never inserted for a known facet")
		void shouldThrowInternalErrorWhenRemovingEntityPkThatWasNeverInserted() {
			// pre-populate facet 1 so the (entity 20, facet 1) removal hits the "facet exists but
			// has no cardinality for this entity" branch — distinct from the empty-index path above.
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			// precise exception type pinned to catch regressions that would downgrade it to a vaguer
			// RuntimeException or swallow the mismatch silently.
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReducedGroupEntityIndexTest.this.index.removePrimaryKey(20, 1)
			);
		}
	}

	/**
	 * Tests for cardinality-aware filter attribute tracking specific to
	 * {@link ReducedGroupEntityIndex}.
	 */
	@Nested
	@DisplayName("Filter attribute cardinality tracking")
	class FilterAttributeCardinalityTest {

		private ReferenceSchemaContract referenceSchema;
		private AttributeSchemaContract stringAttrSchema;

		@BeforeEach
		void setUpAttributes() {
			this.referenceSchema = mock(ReferenceSchemaContract.class);
			this.stringAttrSchema = createFilterableAttributeSchema("code", String.class);
		}

		@Test
		@DisplayName("should add attribute to filter index on first occurrence")
		void shouldAddAttributeOnFirstOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// the filter index should now contain the attribute value
			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}

		@Test
		@DisplayName("should not duplicate attribute on second reference")
		void shouldNotDuplicateAttributeOnSecondOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			// same attribute value "ABC" for record 10 from two different references in same group
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// should still have exactly one record
			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should retain attribute in filter index after removing one of two occurrences")
		void shouldRetainAttributeAfterRemovingOneOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			// add same attribute value twice (from two references)
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// remove one occurrence
			ReducedGroupEntityIndexTest.this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// attribute should still be present in filter index (cardinality went from 2 to 1)
			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}

		@Test
		@DisplayName("should remove attribute from filter index when last occurrence is removed")
		void shouldRemoveAttributeOnLastOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			// add twice, remove twice
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			ReducedGroupEntityIndexTest.this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			ReducedGroupEntityIndexTest.this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// filter index should no longer contain this value
			assertNull(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "code", null))
			);
		}

		@Test
		@DisplayName("should handle array attribute values with cardinality tracking")
		void shouldHandleArrayAttributeValuesWithCardinality() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract arrayAttrSchema = createFilterableAttributeSchema(
				"tags", String[].class
			);

			// first reference adds tags ["A", "B"]
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, arrayAttrSchema, noLocales, null,
				new String[]{"A", "B"}, 10
			);

			// second reference adds tags ["B", "C"] -- "B" already has cardinality 1,
			// only "C" should be newly added to the filter index
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, arrayAttrSchema, noLocales, null,
				new String[]{"B", "C"}, 10
			);

			// all three values should be in the filter index
			final Bitmap resultA = ReducedGroupEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "tags", null))
				.getRecordsEqualTo("A");
			final Bitmap resultB = ReducedGroupEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "tags", null))
				.getRecordsEqualTo("B");
			final Bitmap resultC = ReducedGroupEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "tags", null))
				.getRecordsEqualTo("C");
			assertTrue(resultA.contains(10));
			assertTrue(resultB.contains(10));
			assertTrue(resultC.contains(10));

			// remove first reference's tags ["A", "B"]
			// "A" cardinality 1->0 => removed from filter; "B" cardinality 2->1 => stays
			ReducedGroupEntityIndexTest.this.index.removeFilterAttribute(
				this.referenceSchema, arrayAttrSchema, noLocales, null,
				new String[]{"A", "B"}, 10
			);

			// "A" should be gone, "B" and "C" should remain
			assertFalse(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "tags", null))
					.getRecordsEqualTo("A").contains(10)
			);
			assertTrue(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "tags", null))
					.getRecordsEqualTo("B").contains(10)
			);
			assertTrue(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "tags", null))
					.getRecordsEqualTo("C").contains(10)
			);
		}

		@Test
		@DisplayName("should handle different attribute values from different references")
		void shouldHandleDifferentValuesFromDifferentReferences() {
			final Set<Locale> noLocales = Collections.emptySet();

			// reference 1 sets code="ABC" for record 10
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			// reference 2 sets code="XYZ" for record 10
			ReducedGroupEntityIndexTest.this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "XYZ", 10
			);

			// both values should be in the filter index
			assertTrue(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "code", null))
					.getRecordsEqualTo("ABC").contains(10)
			);
			assertTrue(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "code", null))
					.getRecordsEqualTo("XYZ").contains(10)
			);

			// remove one value
			ReducedGroupEntityIndexTest.this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// "ABC" should be gone, "XYZ" should remain
			assertFalse(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "code", null))
					.getRecordsEqualTo("ABC").contains(10)
			);
			assertTrue(
				ReducedGroupEntityIndexTest.this.index
					.getFilterIndex(new AttributeIndexKey(null, "code", null))
					.getRecordsEqualTo("XYZ").contains(10)
			);
		}
	}

	/**
	 * Tests that sort and unique attribute operations are no-ops for
	 * {@link ReducedGroupEntityIndex}.
	 */
	@Nested
	@DisplayName("No-op sort and unique attribute operations")
	class NoOpOperationsTest {

		@Test
		@DisplayName("should not create sort index entries")
		void shouldNotCreateSortIndex() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
				"name", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();

			// these are no-ops and should not throw
			ReducedGroupEntityIndexTest.this.index.insertSortAttribute(
				refSchema, attrSchema, noLocales, null, "value", 10
			);
			ReducedGroupEntityIndexTest.this.index.removeSortAttribute(
				refSchema, attrSchema, noLocales, null, "value", 10
			);

			// verify no sort index was created
			assertNull(
				ReducedGroupEntityIndexTest.this.index
					.getSortIndex(new AttributeIndexKey(null, "name", null))
			);
		}

		@Test
		@DisplayName("should not create unique index entries")
		void shouldNotCreateUniqueIndex() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
				"code", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();

			// these are no-ops and should not throw
			ReducedGroupEntityIndexTest.this.index.insertUniqueAttribute(
				refSchema, attrSchema, noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);
			ReducedGroupEntityIndexTest.this.index.removeUniqueAttribute(
				refSchema, attrSchema, noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);

			// verify no unique index was created
			assertNull(
				ReducedGroupEntityIndexTest.this.index.getUniqueIndex(
					null, attrSchema, null
				)
			);
		}
	}

	/**
	 * Tests that the constructor rejects invalid {@link EntityIndexType} values and accepts only
	 * {@link EntityIndexType#REFERENCED_GROUP_ENTITY}.
	 */
	@Nested
	@DisplayName("Constructor type validation")
	class ConstructorTypeValidationTest {

		@Test
		@DisplayName("should accept REFERENCED_GROUP_ENTITY type")
		void shouldAcceptReferencedGroupEntityType() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, 100)
			);
			final ReducedGroupEntityIndex created = new ReducedGroupEntityIndex(
				INDEX_PK,
				ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE, rrk)
			);

			assertNotNull(created);
			assertEquals(
				EntityIndexType.REFERENCED_GROUP_ENTITY, created.getIndexKey().type()
			);
		}

		@Test
		@DisplayName("should reject GLOBAL type")
		void shouldRejectGlobalType() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedGroupEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_GROUP_ENTITY"),
				"Error message should mention the expected type"
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY type")
		void shouldRejectReferencedEntityType() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(REFERENCE_NAME, 100)
			);
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedGroupEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, rrk)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_GROUP_ENTITY"),
				"Error message should mention the expected type"
			);
		}

		@Test
		@DisplayName("should reject REFERENCED_ENTITY_TYPE type")
		void shouldRejectReferencedEntityTypeType() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> new ReducedGroupEntityIndex(
					INDEX_PK,
					ENTITY_TYPE,
					new EntityIndexKey(
						EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "CATEGORY"
					)
				)
			);
			assertTrue(
				exception.getMessage().contains("REFERENCED_GROUP_ENTITY"),
				"Error message should mention the expected type"
			);
		}
	}

	/**
	 * Tests for {@link ReducedGroupEntityIndex#toString()} output format.
	 */
	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should return descriptive string containing class name and index key")
		void shouldReturnDescriptiveToString() {
			final String result = ReducedGroupEntityIndexTest.this.index.toString();

			assertNotNull(result);
			assertTrue(
				result.startsWith("ReducedGroupEntityIndex"),
				"toString should start with class name, but was: " + result
			);
		}
	}

	/**
	 * Tests for STM transactional commit behavior specific to
	 * {@link ReducedGroupEntityIndex} cardinality tracking.
	 */
	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("should commit PK insertions and preserve original unchanged")
		void shouldCommitPkInsertions() {
			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10, 1);
					original.insertPrimaryKeyIfMissing(20, 2);
				},
				(original, committed) -> {
					// original should still be empty
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					// committed should have the PKs
					assertNotNull(committed);
					assertTrue(committed.getAllPrimaryKeys().contains(10));
					assertTrue(committed.getAllPrimaryKeys().contains(20));
					assertEquals(2, committed.getAllPrimaryKeys().size());
				}
			);
		}

		@Test
		@DisplayName("should commit filter attribute and preserve original unchanged")
		void shouldCommitFilterAttribute() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
				"code", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();

			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
				original -> original.insertFilterAttribute(
					refSchema, attrSchema, noLocales, null, "ABC", 10
				),
				(original, committed) -> {
					// original should have no filter index for "code"
					assertNull(
						original.getFilterIndex(new AttributeIndexKey(null, "code", null))
					);
					// committed should have the attribute
					assertNotNull(committed);
					final Bitmap result = committed
						.getFilterIndex(new AttributeIndexKey(null, "code", null))
						.getRecordsEqualTo("ABC");
					assertEquals(1, result.size());
					assertTrue(result.contains(10));
				}
			);
		}

		@Test
		@DisplayName("should commit atomic multi-operation: 3 PKs + 2 filter attributes")
		void shouldCommitAtomicMultiOperation() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
				"code", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();

			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10, 1);
					original.insertPrimaryKeyIfMissing(20, 2);
					original.insertPrimaryKeyIfMissing(30, 3);
					original.insertFilterAttribute(
						refSchema, attrSchema, noLocales, null, "ABC", 10
					);
					original.insertFilterAttribute(
						refSchema, attrSchema, noLocales, null, "XYZ", 20
					);
				},
				(original, committed) -> {
					// original unchanged
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					// committed has all operations
					assertNotNull(committed);
					assertEquals(3, committed.getAllPrimaryKeys().size());
					assertTrue(committed.getAllPrimaryKeys().contains(10));
					assertTrue(committed.getAllPrimaryKeys().contains(20));
					assertTrue(committed.getAllPrimaryKeys().contains(30));
					assertTrue(
						committed.getFilterIndex(new AttributeIndexKey(null, "code", null))
							.getRecordsEqualTo("ABC").contains(10)
					);
					assertTrue(
						committed.getFilterIndex(new AttributeIndexKey(null, "code", null))
							.getRecordsEqualTo("XYZ").contains(20)
					);
				}
			);
		}

		@Test
		@DisplayName("should increment version when dirty, not when clean")
		void shouldIncrementVersionWhenDirty() {
			// dirty commit
			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
				original -> original.insertPrimaryKeyIfMissing(10, 1),
				(original, committed) -> {
					assertEquals(1, original.version());
					assertNotNull(committed);
					assertEquals(2, committed.version());
				}
			);
		}

		@Test
		@DisplayName("should not increment version when no mutations performed")
		void shouldNotIncrementVersionWhenClean() {
			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
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
	}

	/**
	 * Tests for STM transactional rollback behavior specific to
	 * {@link ReducedGroupEntityIndex} cardinality tracking.
	 */
	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("should discard PK insertions on rollback")
		void shouldDiscardPkInsertions() {
			assertStateAfterRollback(
				ReducedGroupEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10, 1);
					original.insertPrimaryKeyIfMissing(20, 2);
				},
				(original, committed) -> {
					assertTrue(original.getAllPrimaryKeys().isEmpty());
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("should discard filter attribute insertions on rollback")
		void shouldDiscardFilterAttributeInsertions() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
				"code", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();

			assertStateAfterRollback(
				ReducedGroupEntityIndexTest.this.index,
				original -> original.insertFilterAttribute(
					refSchema, attrSchema, noLocales, null, "ABC", 10
				),
				(original, committed) -> {
					assertNull(
						original.getFilterIndex(new AttributeIndexKey(null, "code", null))
					);
					assertNull(committed);
				}
			);
		}
	}

	/**
	 * Tests for {@link ReducedGroupEntityIndex#isEmpty()} edge cases, including
	 * the cardinality-aware override that checks `pkCardinalities`.
	 */
	@Nested
	@DisplayName("isEmpty edge cases")
	class IsEmptyEdgeCasesTest {

		@Test
		@DisplayName("should be empty when freshly created")
		void shouldBeEmptyWhenFresh() {
			final ReducedGroupEntityIndex freshIndex = createIndex(100);

			assertTrue(freshIndex.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when PKs are present")
		void shouldNotBeEmptyWithPks() {
			final ReducedGroupEntityIndex idx = createIndex(100);

			idx.insertPrimaryKeyIfMissing(10, 1);

			assertFalse(idx.isEmpty());
		}

		@Test
		@DisplayName("should be empty after all PKs are removed")
		void shouldBeEmptyAfterAllPksRemoved() {
			final ReducedGroupEntityIndex idx = createIndex(100);

			idx.insertPrimaryKeyIfMissing(10, 1);
			idx.removePrimaryKey(10, 1);

			assertTrue(idx.isEmpty());
		}
	}

	/**
	 * Tests for {@link ReducedGroupEntityIndex#resetDirty()} which resets both the
	 * base dirty flag and the cardinality dirty flag.
	 */
	@Nested
	@DisplayName("resetDirty")
	class ResetDirtyTest {

		@Test
		@DisplayName("should clear dirty state after PK insertion and resetDirty")
		void shouldClearDirtyStateAfterResetDirty() {
			final ReducedGroupEntityIndex idx = createIndex(100);

			idx.insertPrimaryKeyIfMissing(10, 1);
			idx.resetDirty();

			// after resetDirty, a commit with no new mutations should not increment version
			assertStateAfterCommit(
				idx,
				original -> {
					// no new mutations after resetDirty
				},
				(original, committed) -> {
					assertNotNull(committed);
					// version should not increment since dirty was reset and no new mutations
					assertEquals(original.version(), committed.version());
				}
			);
		}
	}

	/**
	 * Tests for the referenced primary key index tracking in
	 * {@link ReducedGroupEntityIndex}, which maps each referenced entity PK to
	 * a bitmap of entity PKs that reference it within this group.
	 */
	@Nested
	@DisplayName("Referenced PK index tracking")
	class ReferencedPkIndexTrackingTest {

		@Test
		@DisplayName("should create and populate bitmap per referenced PK")
		void shouldCreateBitmapPerReferencedPk() {
			// entity 10 references category 1, entity 20 references category 1
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 1);

			// both entity PKs should be in the index
			final Bitmap allPks = ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(2, allPks.size());
			assertTrue(allPks.contains(10));
			assertTrue(allPks.contains(20));
		}

		@Test
		@DisplayName("should clean up empty bitmap when last entity PK is removed")
		void shouldCleanUpEmptyBitmapWhenLastEntityPkRemoved() {
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);

			// after removal the index should be completely empty
			assertTrue(ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().isEmpty());
			assertTrue(ReducedGroupEntityIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("should track multiple referenced PKs independently")
		void shouldTrackMultipleReferencedPksIndependently() {
			// entity 10 via ref to category 1, entity 20 via ref to category 2
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 2);

			assertEquals(2, ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().size());

			// remove entity 10's reference to category 1 -- entity 20 stays
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);
			assertEquals(1, ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().size());
			assertTrue(ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().contains(20));
			assertFalse(ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().contains(10));

			// remove entity 20's reference to category 2 -- index empty
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(20, 2);
			assertTrue(ReducedGroupEntityIndexTest.this.index.getAllPrimaryKeys().isEmpty());
		}
	}

	/**
	 * Tests for the referenced-entity accessor methods
	 * {@link ReducedGroupEntityIndex#getReferencedEntityPrimaryKeys()} and
	 * {@link ReducedGroupEntityIndex#getOwnerPKsForReferencedEntity(int)}.
	 */
	@Nested
	@DisplayName("Referenced entity accessor methods")
	class ReferencedEntityAccessorTest {

		@Test
		@DisplayName("should return empty set for a freshly created index")
		void shouldReturnEmptySetForFreshIndex() {
			final Set<Integer> referencedPks = ReducedGroupEntityIndexTest.this.index.getReferencedEntityPrimaryKeys();
			assertNotNull(referencedPks);
			assertTrue(referencedPks.isEmpty());
		}

		@Test
		@DisplayName("should return all facet PKs present in the group")
		void shouldReturnAllFacetPKsInGroup() {
			// entity PK=10 references category PK=1, entity PK=20 references category PK=2
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 2);

			final Set<Integer> referencedPks = ReducedGroupEntityIndexTest.this.index.getReferencedEntityPrimaryKeys();
			assertEquals(2, referencedPks.size());
			assertTrue(referencedPks.contains(1));
			assertTrue(referencedPks.contains(2));
		}

		@Test
		@DisplayName("should return correct owner PK bitmap for a given referenced entity PK")
		void shouldReturnCorrectOwnerPKBitmap() {
			// two entities (PK=10, PK=20) both reference the same category PK=1
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 1);

			final Bitmap ownerBitmap = ReducedGroupEntityIndexTest.this.index.getOwnerPKsForReferencedEntity(1);
			assertNotNull(ownerBitmap);
			assertEquals(2, ownerBitmap.size());
			assertTrue(ownerBitmap.contains(10));
			assertTrue(ownerBitmap.contains(20));
		}

		@Test
		@DisplayName("should return null for an unknown referenced entity PK")
		void shouldReturnNullForUnknownFacetPK() {
			assertNull(ReducedGroupEntityIndexTest.this.index.getOwnerPKsForReferencedEntity(999));
		}

		@Test
		@DisplayName("should reflect insertions and removals in referenced entity PK set")
		void shouldReflectInsertionsAndRemovals() {
			// insert entity PK=10 referencing category PK=1
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			final Set<Integer> afterInsert = ReducedGroupEntityIndexTest.this.index.getReferencedEntityPrimaryKeys();
			assertTrue(afterInsert.contains(1));

			// remove the only reference — referenced PK=1 should disappear
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);

			final Set<Integer> afterRemoval = ReducedGroupEntityIndexTest.this.index.getReferencedEntityPrimaryKeys();
			assertFalse(afterRemoval.contains(1));
		}

		@Test
		@DisplayName("getAllReferencedPrimaryKeys should return an empty non-null bitmap for a freshly created index")
		void shouldReturnEmptyBitmapFromGetAllReferencedPrimaryKeysWhenIndexIsFresh() {
			final Bitmap result = ReducedGroupEntityIndexTest.this.index.getAllReferencedPrimaryKeys();

			assertNotNull(result);
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("getAllReferencedPrimaryKeys should collapse duplicate references and return each referenced PK once")
		void shouldCollapseDuplicateReferencesInGetAllReferencedPrimaryKeys() {
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 2);
			// a third entity (30) points at the already-referenced facet PK=1 — the returned bitmap
			// must still contain a single entry for facet 1 (set semantics, not multiset).
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(30, 1);

			final Bitmap result = ReducedGroupEntityIndexTest.this.index.getAllReferencedPrimaryKeys();

			assertEquals(2, result.size());
			assertTrue(result.contains(1));
			assertTrue(result.contains(2));
		}

		@Test
		@DisplayName("getAllReferencedPrimaryKeys should drop a facet PK once its last referencing entity is removed")
		void shouldDropFacetPkFromGetAllReferencedPrimaryKeysWhenLastReferenceIsRemoved() {
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 2);
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 1);

			final Bitmap result = ReducedGroupEntityIndexTest.this.index.getAllReferencedPrimaryKeys();

			assertEquals(1, result.size());
			assertFalse(result.contains(1));
			assertTrue(result.contains(2));
		}

	}

	/**
	 * Thin delegation test verifying that RGEI convenience methods correctly delegate to
	 * {@link HistogramIndex}. Full histogram transactional lifecycle is tested in
	 * {@link HistogramIndexTest}.
	 */
	@Nested
	@DisplayName("Histogram delegation")
	class HistogramDelegationTest {

		@Test
		@DisplayName("should delegate histogram insert and retrieval to HistogramIndex")
		void shouldDelegateHistogramInsertAndRetrieval() {
			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10, 1);
					original.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class);

					final FilterIndex filter = original.getHistogramFilterIndex(HISTOGRAM_NAME, null);
					assertNotNull(filter, "Filter index should be accessible via convenience method");
					assertTrue(filter.getRecordsEqualTo(42).contains(10));
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					final ReducedGroupEntityIndex committedIndex = (ReducedGroupEntityIndex) committed;
					assertNotNull(
						committedIndex.getHistogramFilterIndex(HISTOGRAM_NAME, null),
						"Committed copy should have histogram data"
					);
				}
			);
		}

		@Test
		@DisplayName("should report non-empty when only histogram data exists")
		void shouldReportNonEmptyWhenOnlyHistogramDataExists() {
			assertStateAfterCommit(
				ReducedGroupEntityIndexTest.this.index,
				original -> {
					original.insertHistogramValue(HISTOGRAM_NAME, null, 99, 10, Integer.class);
					assertFalse(original.isEmpty(), "Index with histogram data should not be empty");
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					final ReducedGroupEntityIndex committedIndex = (ReducedGroupEntityIndex) committed;
					assertFalse(committedIndex.isEmpty(), "Committed index with histogram data should not be empty");
				}
			);
		}

		@Test
		@DisplayName("should isolate histogram filter indexes per locale so values from one locale never leak into another")
		void shouldIsolateHistogramFilterIndexesPerLocale() {
			ReducedGroupEntityIndexTest.this.index.insertHistogramValue(
				HISTOGRAM_NAME, Locale.ENGLISH, 42, 10, Integer.class
			);
			ReducedGroupEntityIndexTest.this.index.insertHistogramValue(
				HISTOGRAM_NAME, Locale.GERMAN, 43, 11, Integer.class
			);

			final FilterIndex englishFilter = ReducedGroupEntityIndexTest.this.index
				.getHistogramFilterIndex(HISTOGRAM_NAME, Locale.ENGLISH);
			final FilterIndex germanFilter = ReducedGroupEntityIndexTest.this.index
				.getHistogramFilterIndex(HISTOGRAM_NAME, Locale.GERMAN);

			assertNotNull(englishFilter, "English filter must be present");
			assertNotNull(germanFilter, "German filter must be present");
			assertTrue(englishFilter.getRecordsEqualTo(42).contains(10));
			assertFalse(englishFilter.getRecordsEqualTo(43).contains(11),
				"English filter must not carry German values");
			assertTrue(germanFilter.getRecordsEqualTo(43).contains(11));
		}

		@Test
		@DisplayName("should dispose the histogram filter index once the last value under a given name/locale is removed")
		void shouldDisposeHistogramFilterIndexWhenLastValueRemoved() {
			ReducedGroupEntityIndexTest.this.index.insertHistogramValue(
				HISTOGRAM_NAME, null, 42, 10, Integer.class
			);
			assertNotNull(
				ReducedGroupEntityIndexTest.this.index.getHistogramFilterIndex(HISTOGRAM_NAME, null)
			);

			ReducedGroupEntityIndexTest.this.index.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10);

			// removing the last value must reclaim the filter-index slot itself (not just empty it) —
			// downstream accumulators use `null` as the signal that the histogram has no buckets.
			assertNull(
				ReducedGroupEntityIndexTest.this.index.getHistogramFilterIndex(HISTOGRAM_NAME, null),
				"Filter index must be gone after last value removal"
			);
		}

		@Test
		@DisplayName("should return null from getHistogramFilterIndex when the histogram name is unknown")
		void shouldReturnNullFromGetHistogramFilterIndexWhenHistogramNameIsUnknown() {
			assertNull(
				ReducedGroupEntityIndexTest.this.index.getHistogramFilterIndex("unknownHist", null)
			);
		}
	}

	/**
	 * Pins the all-or-nothing rollback contract for a batch of histogram mutations. A rollback
	 * must revert every change made inside the transactional block — regardless of how many
	 * histogram names or locales were touched — leaving no partial state behind. This matters
	 * because the histogram index manages its own internal map of filter indexes keyed by
	 * name/locale; without proper STM wiring a partial rollback could orphan filter indexes.
	 */
	@Nested
	@DisplayName("STM rollback — atomic histogram batch across multiple names and locales")
	class StmRollbackHistogramTest {

		@Test
		@DisplayName("should revert all histogram inserts across multiple names and locales when the transaction is rolled back")
		void shouldRevertAllHistogramInsertsAcrossMultipleNamesAndLocalesOnRollback() {
			assertStateAfterRollback(
				ReducedGroupEntityIndexTest.this.index,
				original -> {
					original.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class);
					original.insertHistogramValue(HISTOGRAM_NAME, null, 43, 11, Integer.class);
					original.insertHistogramValue(
						"other", Locale.ENGLISH, 99, 12, Integer.class
					);
				},
				(original, committed) -> {
					assertNull(
						original.getHistogramFilterIndex(HISTOGRAM_NAME, null),
						"Original must have no histogram data after rollback"
					);
					assertNull(
						original.getHistogramFilterIndex("other", Locale.ENGLISH),
						"Original must have no other-histogram data after rollback"
					);
					assertNull(committed, "Rollback must leave committed state null");
				}
			);
		}
	}

	/**
	 * Exercises `ReducedGroupEntityIndex#getReferencedPrimaryKeysForIndexPks(Bitmap)`, the
	 * reverse lookup that filters a supplied bitmap down to those PKs currently tracked as
	 * referenced entity PKs in this group. The method mirrors the equivalent on
	 * `ReferenceTypeCardinalityIndex` and is used by histogram boundary resolution for
	 * `REFERENCE_ATTRIBUTE` source histograms: because the recordId stored in the
	 * reference-attribute FilterIndex on RGEI is the referenced entity PK (swapped at insert
	 * time via `ReferenceIndexMutator#executeWithDifferentPrimaryKeyToIndex`), this method is
	 * what the accumulator uses to narrow histogram boundaries to the currently-relevant PKs.
	 * The `EmptyBitmap.INSTANCE` singleton contract is load-bearing for downstream identity
	 * comparisons that take the allocation-free fast path.
	 */
	@Nested
	@DisplayName("Reverse referenced-PK lookup — getReferencedPrimaryKeysForIndexPks contract")
	class ReverseReferencedPkLookupTest {

		@Test
		@DisplayName("should return EmptyBitmap.INSTANCE when the input bitmap is empty (singleton fast path)")
		void shouldReturnEmptyBitmapSingletonWhenInputIsEmpty() {
			// seed the index so the early-return we want to verify is "input is empty", not
			// "nothing is tracked yet" — the two paths live in different branches.
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 6);

			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getReferencedPrimaryKeysForIndexPks(EmptyBitmap.INSTANCE);

			assertSame(EmptyBitmap.INSTANCE, result,
				"Empty input must short-circuit to the singleton EmptyBitmap");
		}

		@Test
		@DisplayName("should return an empty bitmap when the index has no tracked referenced PKs regardless of input contents")
		void shouldReturnEmptyBitmapWhenIndexHasNoTrackedReferencedPks() {
			// fresh index — nothing inserted, so the "no tracking populated" branch fires
			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getReferencedPrimaryKeysForIndexPks(new BaseBitmap(1, 2, 3));

			assertTrue(result.isEmpty(),
				"Empty referenced-PK tracking must yield an empty result regardless of input");
		}

		@Test
		@DisplayName("should return only the input PKs that match tracked referenced PKs, excluding tracked PKs absent from input")
		void shouldReturnIntersectionOfInputAndTrackedReferencedPks() {
			// entity 10 → referenced entity 5, entity 20 → referenced entity 6, entity 30 → referenced 7
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 6);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(30, 7);

			// mix of matching (5, 6) and non-matching (99, 100) PKs
			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getReferencedPrimaryKeysForIndexPks(new BaseBitmap(5, 6, 99, 100));

			assertEquals(2, result.size(),
				"Result must contain only the tracked referenced PKs present in the input");
			assertTrue(result.contains(5));
			assertTrue(result.contains(6));
			assertFalse(result.contains(7),
				"PK 7 is tracked but not present in the input — must be excluded");
			assertFalse(result.contains(99));
			assertFalse(result.contains(100));
		}

		@Test
		@DisplayName("should return the singleton EmptyBitmap.INSTANCE (not a fresh empty instance) when input is disjoint from tracked PKs")
		void shouldReturnEmptyBitmapSingletonWhenInputIsDisjointFromTrackedPks() {
			// populate the index with referenced PKs 5 and 6, then probe with a fully disjoint input
			// (100, 200). The contract says the method returns the singleton `EmptyBitmap.INSTANCE`
			// when the result is empty, not a freshly allocated empty bitmap — this pins down the
			// allocation-free path that downstream callers rely on for identity comparisons.
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 6);

			final Bitmap result = ReducedGroupEntityIndexTest.this.index
				.getReferencedPrimaryKeysForIndexPks(new BaseBitmap(100, 200));

			assertSame(EmptyBitmap.INSTANCE, result,
				"Disjoint probe must return the singleton EmptyBitmap, not a new empty instance");
		}

		@Test
		@DisplayName("should drop a referenced PK from the reverse lookup once its last referencing entity is removed")
		void shouldDropReferencedPkFromReverseLookupWhenLastReferencingEntityRemoved() {
			// entity 10 and entity 20 both reference entity 5; entity 30 references entity 6
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 5);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(30, 6);

			// both referenced PKs are tracked initially
			final Bitmap before = ReducedGroupEntityIndexTest.this.index
				.getReferencedPrimaryKeysForIndexPks(new BaseBitmap(5, 6));
			assertEquals(2, before.size());
			assertTrue(before.contains(5));
			assertTrue(before.contains(6));

			// remove all references pointing at entity 5 (both 10 → 5 and 20 → 5)
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 5);
			ReducedGroupEntityIndexTest.this.index.removePrimaryKey(20, 5);

			// entity 5 must have been evicted from the referenced-PK tracking since the last owner
			// reference was removed
			final Bitmap after = ReducedGroupEntityIndexTest.this.index
				.getReferencedPrimaryKeysForIndexPks(new BaseBitmap(5, 6));
			assertEquals(1, after.size());
			assertFalse(after.contains(5),
				"Referenced PK 5 must drop from the reverse lookup after all its references are removed");
			assertTrue(after.contains(6));
		}
	}

	/**
	 * Verifies that the catalog re-attachment copy preserves the per-owner-PK cardinality
	 * tracking. The copy is produced by {@link ReducedGroupEntityIndex#createCopyForNewCatalogAttachment}
	 * which hands the live `pkCardinalities` map straight to the preserve-originals constructor,
	 * so the copy must report the same tracked primary keys and keep the boundary-crossing
	 * semantics intact: an owner PK reachable through two references stays in the index until the
	 * second reference is removed.
	 */
	@Nested
	@DisplayName("Catalog re-attachment")
	class CatalogReattachmentTest {

		@Test
		@DisplayName("should preserve tracked primary keys in the re-attachment copy")
		void shouldPreserveTrackedPrimaryKeysInCopy() {
			// entity 10 is reachable through two references (categories 1 and 2) within the group,
			// so its cardinality is 2; entity 20 is reachable through a single reference (category 3)
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 3);

			final ReducedGroupEntityIndex copy =
				ReducedGroupEntityIndexTest.this.index.createCopyForNewCatalogAttachment(
					CatalogState.ALIVE
				);

			assertNotSame(ReducedGroupEntityIndexTest.this.index, copy);
			// the copy must expose the same owner PKs and referenced (facet) PKs as the source
			final Bitmap copyPks = copy.getAllPrimaryKeys();
			assertEquals(2, copyPks.size());
			assertTrue(copyPks.contains(10));
			assertTrue(copyPks.contains(20));
			assertEquals(
				Set.of(1, 2, 3),
				copy.getReferencedEntityPrimaryKeys()
			);
		}

		@Test
		@DisplayName("should keep boundary-crossing semantics on the re-attachment copy")
		void shouldKeepBoundaryCrossingSemanticsOnCopy() {
			// entity 10 reachable through two references -> cardinality 2 in the source group
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			final ReducedGroupEntityIndex copy =
				ReducedGroupEntityIndexTest.this.index.createCopyForNewCatalogAttachment(
					CatalogState.ALIVE
				);

			// removing the first reference must NOT evict the owner PK: cardinality drops 2 -> 1
			assertEquals(
				CardinalityChange.NO_BOUNDARY_CROSSING,
				copy.removePrimaryKey(10, 1)
			);
			assertTrue(copy.getAllPrimaryKeys().contains(10),
				"Owner PK must survive while a second reference still points at the group");

			// removing the last reference crosses the 1 -> 0 boundary and evicts the owner PK
			assertEquals(
				CardinalityChange.BOUNDARY_CROSSED,
				copy.removePrimaryKey(10, 2)
			);
			assertTrue(copy.getAllPrimaryKeys().isEmpty(),
				"Owner PK must be evicted once its last reference is removed");
		}
	}

}
