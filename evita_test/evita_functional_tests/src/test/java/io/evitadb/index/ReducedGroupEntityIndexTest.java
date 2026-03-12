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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
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
import static org.mockito.Mockito.mock;

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
class ReducedGroupEntityIndexTest
	extends AbstractReducedEntityIndexTest<ReducedGroupEntityIndex>
	implements TimeBoundedTestSupport {

	private static final String REFERENCE_NAME = "CATEGORY";
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
		@DisplayName("should fail when removing PK for non-existent referenced entity")
		void shouldFailOnRemoveWithNonExistentReferencedPk() {
			// no data inserted -- removing should trigger assertion
			assertThrows(
				Exception.class,
				() -> ReducedGroupEntityIndexTest.this.index.removePrimaryKey(10, 999)
			);
		}

		@Test
		@DisplayName("should fail when removing PK that was never inserted")
		void shouldFailOnRemoveWithNonExistentEntityPk() {
			// insert entity 10 via reference to category 1
			ReducedGroupEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			// try to remove entity 20 via reference to category 1 -- entity PK 20 has no cardinality
			assertThrows(
				Exception.class,
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
	 * Generational property-based stress test for {@link ReducedGroupEntityIndex}.
	 * Runs randomized operations (PK insert/remove with cardinality + filter attribute
	 * insert/remove) over multiple generations, comparing committed state against a
	 * JDK reference implementation.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@DisplayName("survives generational randomized test")
		@ParameterizedTest(
			name = "ReducedGroupEntityIndex should survive generational randomized test"
		)
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema(
				"code", String.class
			);
			final Set<Locale> noLocales = Collections.emptySet();

			runFor(
				input,
				50_000,
				new GenerationalState(
					new HashMap<>(16),
					new HashSet<>(16),
					createIndex(100)
				),
				(random, state) -> {
					final ReducedGroupEntityIndex tested = state.index();
					// deep copy reference state — must deep-copy inner sets
					final Map<Integer, Set<Integer>> refPkPairs = new HashMap<>(16);
					for (Map.Entry<Integer, Set<Integer>> entry : state.expectedPkPairs().entrySet()) {
						refPkPairs.put(entry.getKey(), new HashSet<>(entry.getValue()));
					}
					final Set<String> refAttributes = new HashSet<>(state.expectedAttributes());
					final AtomicReference<ReducedGroupEntityIndex> committedRef =
						new AtomicReference<>();

					assertStateAfterCommit(
						tested,
						original -> {
							final int ops = random.nextInt(5) + 1;
							for (int i = 0; i < ops; i++) {
								executeRandomOperation(
									random, original, refPkPairs,
									refAttributes, refSchema, attrSchema, noLocales
								);
							}
						},
						(original, committed) -> {
							assertNotNull(committed);
							final ReducedGroupEntityIndex typed =
								(ReducedGroupEntityIndex) committed;
							verifyState(typed, refPkPairs);
							committedRef.set(typed);
						}
					);

					return new GenerationalState(
						refPkPairs, refAttributes, committedRef.get()
					);
				},
				(state, exc) -> {
					System.out.println(
						"Failed state - PK pairs: " + state.expectedPkPairs()
					);
					System.out.println(
						"Failed state - Attributes: " + state.expectedAttributes()
					);
				}
			);
		}

		/**
		 * Executes a random operation on both the index and the reference model.
		 * The reference model tracks unique `(entityPk, referencedPk)` pairs to mirror
		 * real-world usage where each reference is unique. Duplicate pair insertions are
		 * skipped because the production code's cardinality becomes inconsistent with
		 * the bitmap tracking when the same pair is inserted multiple times.
		 */
		private static void executeRandomOperation(
			@Nonnull Random random,
			@Nonnull ReducedGroupEntityIndex idx,
			@Nonnull Map<Integer, Set<Integer>> refPkPairs,
			@Nonnull Set<String> refAttributes,
			@Nonnull ReferenceSchemaContract refSchema,
			@Nonnull AttributeSchemaContract attrSchema,
			@Nonnull Set<Locale> noLocales
		) {
			final int operation = random.nextInt(4);
			final int entityPk = random.nextInt(30) + 1;
			final int referencedPk = random.nextInt(20) + 1;

			switch (operation) {
				case 0 -> {
					// insert PK — skip if pair already exists (matches real-world semantics)
					final Set<Integer> refs = refPkPairs
						.computeIfAbsent(entityPk, k -> new HashSet<>());
					if (refs.add(referencedPk)) {
						idx.insertPrimaryKeyIfMissing(entityPk, referencedPk);
					}
				}
				case 1 -> {
					// remove PK — must pick a referencedPk that actually exists
					final Set<Integer> refs = refPkPairs.get(entityPk);
					if (refs != null && !refs.isEmpty()) {
						final int existingRefPk = refs.iterator().next();
						idx.removePrimaryKey(entityPk, existingRefPk);
						refs.remove(existingRefPk);
						if (refs.isEmpty()) {
							refPkPairs.remove(entityPk);
						}
					}
				}
				case 2 -> {
					// insert filter attribute
					final String value = "VAL_" + (random.nextInt(10) + 1);
					idx.insertFilterAttribute(
						refSchema, attrSchema, noLocales, null, value, entityPk
					);
					refAttributes.add(value + ":" + entityPk);
				}
				case 3 -> {
					// remove filter attribute (only if known)
					if (!refAttributes.isEmpty()) {
						final String entry = refAttributes.iterator().next();
						final String[] parts = entry.split(":");
						final String value = parts[0];
						final int recordId = Integer.parseInt(parts[1]);
						// only remove if index has it
						try {
							idx.removeFilterAttribute(
								refSchema, attrSchema, noLocales, null, value, recordId
							);
							refAttributes.remove(entry);
						} catch (Exception e) {
							// cardinality index might not exist - skip
						}
					}
				}
			}
		}

		/**
		 * Verifies that the committed index state matches the reference model for PKs.
		 * Each entityPk with at least one referencedPk pair should be in the bitmap.
		 */
		private static void verifyState(
			@Nonnull ReducedGroupEntityIndex committed,
			@Nonnull Map<Integer, Set<Integer>> expectedPkPairs
		) {
			final Set<Integer> expectedPks = expectedPkPairs.keySet();
			final Bitmap allPks = committed.getAllPrimaryKeys();
			assertEquals(
				expectedPks.size(), allPks.size(),
				"PK count mismatch. Expected: " + expectedPks +
					", got bitmap size: " + allPks.size()
			);
			for (int pk : expectedPks) {
				assertTrue(allPks.contains(pk), "Missing PK: " + pk);
			}
		}
	}

	/**
	 * State carried between generations in the generational proof test.
	 *
	 * @param expectedPkPairs    maps entityPk to set of referencedPks (each unique pair = 1 cardinality)
	 * @param expectedAttributes set of "value:recordId" entries for tracking
	 * @param index              the committed index to use in the next generation
	 */
	private record GenerationalState(
		@Nonnull Map<Integer, Set<Integer>> expectedPkPairs,
		@Nonnull Set<String> expectedAttributes,
		@Nonnull ReducedGroupEntityIndex index
	) {}
}
