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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link ReducedGroupEntityIndex} verifying cardinality-aware primary key tracking
 * and filter attribute cardinality logic.
 *
 * The core purpose of `ReducedGroupEntityIndex` is to handle the scenario where a single entity
 * has multiple references (e.g., to different categories) that all share the same group (e.g., the
 * same brand). The cardinality tracking ensures the entity PK is only added to / removed from the
 * bitmap on transitions to/from zero, preventing premature removal when one reference is deleted
 * but others still point to the same group.
 *
 * @author evitaDB
 */
@DisplayName("ReducedGroupEntityIndex cardinality tracking")
class ReducedGroupEntityIndexTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "CATEGORY";
	private static final int INDEX_PK = 1;

	/**
	 * Creates a new {@link ReducedGroupEntityIndex} with the given group primary key.
	 *
	 * @param groupPk the primary key of the group entity (used in the discriminator)
	 * @return a fresh index instance
	 */
	@Nonnull
	private static ReducedGroupEntityIndex createIndex(@SuppressWarnings("SameParameterValue") int groupPk) {
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

		private ReducedGroupEntityIndex index;

		@BeforeEach
		void setUp() {
			this.index = createIndex(100);
		}

		@Test
		@DisplayName("should add PK to bitmap on first reference insertion")
		void shouldAddPkOnFirstInsertion() {
			// entity PK=10 references category PK=1 with group PK=100
			this.index.insertPrimaryKeyIfMissing(10, 1);

			final Bitmap allPks = this.index.getAllPrimaryKeys();
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should not duplicate PK in bitmap when second reference to same group is added")
		void shouldNotDuplicatePkOnSecondInsertion() {
			// entity PK=10 has two references (category 1 and category 2) both sharing group 100
			this.index.insertPrimaryKeyIfMissing(10, 1);
			this.index.insertPrimaryKeyIfMissing(10, 2);

			final Bitmap allPks = this.index.getAllPrimaryKeys();
			// PK should appear exactly once in the bitmap despite cardinality=2
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should retain PK after removing one of two references")
		void shouldRetainPkAfterRemovingOneReference() {
			// entity PK=10 references category 1 and category 2, both in group 100
			this.index.insertPrimaryKeyIfMissing(10, 1);
			this.index.insertPrimaryKeyIfMissing(10, 2);

			// remove only the reference to category 1
			this.index.removePrimaryKey(10, 1);

			final Bitmap allPks = this.index.getAllPrimaryKeys();
			// PK should still be present because cardinality went from 2 to 1 (not zero)
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should remove PK after removing last reference")
		void shouldRemovePkAfterRemovingLastReference() {
			this.index.insertPrimaryKeyIfMissing(10, 1);
			this.index.insertPrimaryKeyIfMissing(10, 2);

			// remove both references
			this.index.removePrimaryKey(10, 1);
			this.index.removePrimaryKey(10, 2);

			final Bitmap allPks = this.index.getAllPrimaryKeys();
			assertTrue(allPks.isEmpty());
		}

		@Test
		@DisplayName("should handle single reference insertion and removal correctly")
		void shouldHandleSingleReferenceLifecycle() {
			this.index.insertPrimaryKeyIfMissing(10, 1);
			assertEquals(1, this.index.getAllPrimaryKeys().size());

			this.index.removePrimaryKey(10, 1);
			assertTrue(this.index.getAllPrimaryKeys().isEmpty());
		}

		@Test
		@DisplayName("should track multiple entities independently")
		void shouldTrackMultipleEntitiesIndependently() {
			// entity 10 -> categories 1 and 2 (cardinality=2 in group)
			this.index.insertPrimaryKeyIfMissing(10, 1);
			this.index.insertPrimaryKeyIfMissing(10, 2);

			// entity 20 -> category 3 (cardinality=1 in group)
			this.index.insertPrimaryKeyIfMissing(20, 3);

			final Bitmap allPks = this.index.getAllPrimaryKeys();
			assertEquals(2, allPks.size());
			assertTrue(allPks.contains(10));
			assertTrue(allPks.contains(20));

			// remove one reference for entity 10 -- entity 10 should remain
			this.index.removePrimaryKey(10, 1);
			assertEquals(2, this.index.getAllPrimaryKeys().size());

			// remove single reference for entity 20 -- entity 20 should disappear
			this.index.removePrimaryKey(20, 3);
			final Bitmap remaining = this.index.getAllPrimaryKeys();
			assertEquals(1, remaining.size());
			assertTrue(remaining.contains(10));
			assertFalse(remaining.contains(20));
		}

		@Test
		@DisplayName("should track three references to same group from same entity")
		void shouldTrackThreeReferences() {
			this.index.insertPrimaryKeyIfMissing(10, 1);
			this.index.insertPrimaryKeyIfMissing(10, 2);
			this.index.insertPrimaryKeyIfMissing(10, 3);

			assertEquals(1, this.index.getAllPrimaryKeys().size());

			// remove two of three -- PK should remain
			this.index.removePrimaryKey(10, 1);
			this.index.removePrimaryKey(10, 2);
			assertEquals(1, this.index.getAllPrimaryKeys().size());

			// remove last one -- PK should disappear
			this.index.removePrimaryKey(10, 3);
			assertTrue(this.index.getAllPrimaryKeys().isEmpty());
		}

		@Test
		@DisplayName("should report non-empty when cardinality data exists even if bitmap is empty")
		void shouldReportNonEmptyWhenCardinalityExists() {
			// empty index should be empty
			assertTrue(this.index.isEmpty());

			this.index.insertPrimaryKeyIfMissing(10, 1);
			assertFalse(this.index.isEmpty());
		}
	}

	@Nested
	@DisplayName("Single-arg method guards")
	class UnsupportedMethodGuardsTest {

		private ReducedGroupEntityIndex index;

		@BeforeEach
		void setUp() {
			this.index = createIndex(100);
		}

		@Test
		@DisplayName("should throw on single-arg insertPrimaryKeyIfMissing")
		void shouldThrowOnSingleArgInsert() {
			assertThrows(
				UnsupportedOperationException.class,
				() -> this.index.insertPrimaryKeyIfMissing(10)
			);
		}

		@Test
		@DisplayName("should throw on single-arg removePrimaryKey")
		void shouldThrowOnSingleArgRemove() {
			assertThrows(
				UnsupportedOperationException.class,
				() -> this.index.removePrimaryKey(10)
			);
		}
	}

	@Nested
	@DisplayName("Remove error handling")
	class RemoveErrorHandlingTest {

		private ReducedGroupEntityIndex index;

		@BeforeEach
		void setUp() {
			this.index = createIndex(100);
		}

		@Test
		@DisplayName("should fail when removing PK for non-existent referenced entity")
		void shouldFailOnRemoveWithNonExistentReferencedPk() {
			// no data inserted -- removing should trigger assertion
			assertThrows(
				Exception.class,
				() -> this.index.removePrimaryKey(10, 999)
			);
		}

		@Test
		@DisplayName("should fail when removing PK that was never inserted")
		void shouldFailOnRemoveWithNonExistentEntityPk() {
			// insert entity 10 via reference to category 1
			this.index.insertPrimaryKeyIfMissing(10, 1);

			// try to remove entity 20 via reference to category 1 -- entity PK 20 has no cardinality
			assertThrows(
				Exception.class,
				() -> this.index.removePrimaryKey(20, 1)
			);
		}
	}

	@Nested
	@DisplayName("Filter attribute cardinality tracking")
	class FilterAttributeCardinalityTest {

		private ReducedGroupEntityIndex index;
		private ReferenceSchemaContract referenceSchema;
		private AttributeSchemaContract stringAttrSchema;

		@BeforeEach
		void setUp() {
			this.index = createIndex(100);
			this.referenceSchema = mock(ReferenceSchemaContract.class);
			this.stringAttrSchema = createFilterableAttributeSchema("code", String.class);
		}

		@Test
		@DisplayName("should add attribute to filter index on first occurrence")
		void shouldAddAttributeOnFirstOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// the filter index should now contain the attribute value
			final Bitmap result = this.index.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}

		@Test
		@DisplayName("should not duplicate attribute in filter index when same value added from second reference")
		void shouldNotDuplicateAttributeOnSecondOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			// same attribute value "ABC" for record 10 from two different references in same group
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// should still have exactly one record
			final Bitmap result = this.index.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should retain attribute in filter index after removing one of two occurrences")
		void shouldRetainAttributeAfterRemovingOneOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			// add same attribute value twice (from two references)
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// remove one occurrence
			this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// attribute should still be present in filter index (cardinality went from 2 to 1)
			final Bitmap result = this.index.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}

		@Test
		@DisplayName("should remove attribute from filter index when last occurrence is removed")
		void shouldRemoveAttributeOnLastOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			// add twice, remove twice
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// filter index should no longer contain this value
			assertNull(this.index.getFilterIndex(new AttributeIndexKey(null, "code", null)));
		}

		@Test
		@DisplayName("should handle array attribute values with cardinality tracking")
		void shouldHandleArrayAttributeValuesWithCardinality() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract arrayAttrSchema = createFilterableAttributeSchema(
				"tags", String[].class
			);

			// first reference adds tags ["A", "B"]
			this.index.insertFilterAttribute(
				this.referenceSchema, arrayAttrSchema, noLocales, null,
				new String[]{"A", "B"}, 10
			);

			// second reference adds tags ["B", "C"] -- "B" already has cardinality 1,
			// only "C" should be newly added to the filter index
			this.index.insertFilterAttribute(
				this.referenceSchema, arrayAttrSchema, noLocales, null,
				new String[]{"B", "C"}, 10
			);

			// all three values should be in the filter index
			final Bitmap resultA = this.index.getFilterIndex(new AttributeIndexKey(null, "tags", null)).getRecordsEqualTo("A");
			final Bitmap resultB = this.index.getFilterIndex(new AttributeIndexKey(null, "tags", null)).getRecordsEqualTo("B");
			final Bitmap resultC = this.index.getFilterIndex(new AttributeIndexKey(null, "tags", null)).getRecordsEqualTo("C");
			assertTrue(resultA.contains(10));
			assertTrue(resultB.contains(10));
			assertTrue(resultC.contains(10));

			// remove first reference's tags ["A", "B"]
			// "A" cardinality 1->0 => removed from filter; "B" cardinality 2->1 => stays
			this.index.removeFilterAttribute(
				this.referenceSchema, arrayAttrSchema, noLocales, null,
				new String[]{"A", "B"}, 10
			);

			// "A" should be gone, "B" and "C" should remain
			assertFalse(
				this.index.getFilterIndex(new AttributeIndexKey(null, "tags", null)).getRecordsEqualTo("A").contains(10)
			);
			assertTrue(
				this.index.getFilterIndex(new AttributeIndexKey(null, "tags", null)).getRecordsEqualTo("B").contains(10)
			);
			assertTrue(
				this.index.getFilterIndex(new AttributeIndexKey(null, "tags", null)).getRecordsEqualTo("C").contains(10)
			);
		}

		@Test
		@DisplayName("should handle different attribute values from different references")
		void shouldHandleDifferentValuesFromDifferentReferences() {
			final Set<Locale> noLocales = Collections.emptySet();

			// reference 1 sets code="ABC" for record 10
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);
			// reference 2 sets code="XYZ" for record 10
			this.index.insertFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "XYZ", 10
			);

			// both values should be in the filter index
			assertTrue(
				this.index.getFilterIndex(new AttributeIndexKey(null, "code", null)).getRecordsEqualTo("ABC").contains(10)
			);
			assertTrue(
				this.index.getFilterIndex(new AttributeIndexKey(null, "code", null)).getRecordsEqualTo("XYZ").contains(10)
			);

			// remove one value
			this.index.removeFilterAttribute(
				this.referenceSchema, this.stringAttrSchema, noLocales, null, "ABC", 10
			);

			// "ABC" should be gone, "XYZ" should remain
			assertFalse(
				this.index.getFilterIndex(new AttributeIndexKey(null, "code", null)).getRecordsEqualTo("ABC").contains(10)
			);
			assertTrue(
				this.index.getFilterIndex(new AttributeIndexKey(null, "code", null)).getRecordsEqualTo("XYZ").contains(10)
			);
		}
	}

	@Nested
	@DisplayName("No-op sort and unique attribute operations")
	class NoOpOperationsTest {

		private ReducedGroupEntityIndex index;

		@BeforeEach
		void setUp() {
			this.index = createIndex(100);
		}

		@Test
		@DisplayName("should not create sort index entries")
		void shouldNotCreateSortIndex() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema("name", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			// these are no-ops and should not throw
			this.index.insertSortAttribute(refSchema, attrSchema, noLocales, null, "value", 10);
			this.index.removeSortAttribute(refSchema, attrSchema, noLocales, null, "value", 10);

			// verify no sort index was created
			assertNull(this.index.getSortIndex(new AttributeIndexKey(null, "name", null)));
		}

		@Test
		@DisplayName("should not create unique index entries")
		void shouldNotCreateUniqueIndex() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema = createFilterableAttributeSchema("code", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			// these are no-ops and should not throw
			this.index.insertUniqueAttribute(
				refSchema, attrSchema, noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);
			this.index.removeUniqueAttribute(
				refSchema, attrSchema, noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);
		}
	}
}
