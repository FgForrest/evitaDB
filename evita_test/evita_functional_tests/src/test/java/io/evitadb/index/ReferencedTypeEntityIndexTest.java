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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.roaringbitmap.RoaringBitmap;

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
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ReferencedTypeEntityIndex} verifying cardinality-aware primary key tracking,
 * reference lookup operations, filter attribute cardinality, sort attribute no-ops, throwing stub
 * proxy creation, STM transactional behavior, and generational randomized proof.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ReferencedTypeEntityIndex")
class ReferencedTypeEntityIndexTest extends AbstractEntityIndexTest<ReferencedTypeEntityIndex>
	implements TimeBoundedTestSupport {

	private static final int INDEX_PK = 1;
	private static final String REFERENCE_NAME = "BRAND";

	@Nonnull
	@Override
	protected ReferencedTypeEntityIndex createInstance() {
		return new ReferencedTypeEntityIndex(
			INDEX_PK,
			ENTITY_TYPE,
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
		);
	}

	@Override
	protected void insertPk(@Nonnull ReferencedTypeEntityIndex index, int entityPrimaryKey) {
		// use 2-arg variant with a synthetic referenced PK to satisfy the cardinality tracking
		index.insertPrimaryKeyIfMissing(entityPrimaryKey, entityPrimaryKey);
	}

	@Override
	protected void removePk(@Nonnull ReferencedTypeEntityIndex index, int entityPrimaryKey) {
		index.removePrimaryKey(entityPrimaryKey, entityPrimaryKey);
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

	/**
	 * Tests for basic construction and identity of {@link ReferencedTypeEntityIndex}.
	 */
	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("should use REFERENCED_ENTITY_TYPE index type")
		void shouldUseReferencedEntityTypeIndexType() {
			final EntityIndexKey key = ReferencedTypeEntityIndexTest.this.index.getIndexKey();

			assertEquals(EntityIndexType.REFERENCED_ENTITY_TYPE, key.type());
		}

		@Test
		@DisplayName("should return reference name from discriminator")
		void shouldReturnReferenceName() {
			final String referenceName = ReferencedTypeEntityIndexTest.this.index.getReferenceName();

			assertEquals(REFERENCE_NAME, referenceName);
		}

		@Test
		@DisplayName("should have String discriminator in index key")
		void shouldHaveStringDiscriminator() {
			final Serializable discriminator = ReferencedTypeEntityIndexTest.this.index.getIndexKey().discriminator();

			assertInstanceOf(String.class, discriminator);
			assertEquals(REFERENCE_NAME, discriminator);
		}
	}

	/**
	 * Tests that single-arg PK methods throw {@link UnsupportedOperationException}.
	 */
	@Nested
	@DisplayName("Single-arg method guards")
	class SingleArgMethodGuardsTest {

		@Test
		@DisplayName("should throw on single-arg insertPrimaryKeyIfMissing")
		void shouldThrowOnSingleArgInsert() {
			final UnsupportedOperationException ex = assertThrows(
				UnsupportedOperationException.class,
				() -> ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10)
			);

			assertTrue(ex.getMessage().contains("insertPrimaryKeyIfMissing"));
		}

		@Test
		@DisplayName("should throw on single-arg removePrimaryKey")
		void shouldThrowOnSingleArgRemove() {
			final UnsupportedOperationException ex = assertThrows(
				UnsupportedOperationException.class,
				() -> ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10)
			);

			assertTrue(ex.getMessage().contains("removePrimaryKey"));
		}
	}

	/**
	 * Tests for cardinality-aware primary key tracking via
	 * {@link ReferencedTypeEntityIndex#insertPrimaryKeyIfMissing(int, int)} and
	 * {@link ReferencedTypeEntityIndex#removePrimaryKey(int, int)}.
	 */
	@Nested
	@DisplayName("Primary key cardinality tracking")
	class PrimaryKeyCardinalityTrackingTest {

		@Test
		@DisplayName("should add index PK to bitmap on first reference insertion")
		void shouldAddPkOnFirstInsertion() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			final Bitmap allPks = ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should not duplicate PK when second reference is added")
		void shouldNotDuplicatePkOnSecondReference() {
			// index PK 10 referenced via entity 1 and entity 2
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			final Bitmap allPks = ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should retain PK after removing one of two references")
		void shouldRetainPkAfterRemovingOneReference() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 1);

			final Bitmap allPks = ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(1, allPks.size());
			assertTrue(allPks.contains(10));
		}

		@Test
		@DisplayName("should remove PK after removing last reference")
		void shouldRemovePkAfterLastReference() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 1);
			ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 2);

			assertTrue(ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys().isEmpty());
		}

		@Test
		@DisplayName("should track multiple entities independently")
		void shouldTrackMultipleEntitiesIndependently() {
			// entity index PK 10 -> referenced entities 1,2
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);
			// entity index PK 20 -> referenced entity 3
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 3);

			final Bitmap allPks = ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(2, allPks.size());
			assertTrue(allPks.contains(10));
			assertTrue(allPks.contains(20));

			// remove one reference for PK 10 -- should remain
			ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 1);
			assertEquals(2, ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys().size());

			// remove the only reference for PK 20 -- should disappear
			ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(20, 3);
			final Bitmap remaining = ReferencedTypeEntityIndexTest.this.index.getAllPrimaryKeys();
			assertEquals(1, remaining.size());
			assertTrue(remaining.contains(10));
			assertFalse(remaining.contains(20));
		}

		@Test
		@DisplayName("should always return true from insertPrimaryKeyIfMissing")
		void shouldAlwaysReturnTrueFromInsert() {
			final boolean first = ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			final boolean second = ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			assertTrue(first);
			assertTrue(second);
		}

		@Test
		@DisplayName("should always return true from removePrimaryKey")
		void shouldAlwaysReturnTrueFromRemove() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 2);

			final boolean first = ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 1);
			final boolean second = ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 2);

			assertTrue(first);
			assertTrue(second);
		}
	}

	/**
	 * Tests for reference lookup methods:
	 * {@link ReferencedTypeEntityIndex#getAllReferenceIndexes(int)},
	 * {@link ReferencedTypeEntityIndex#getReferencedPrimaryKeysForIndexPks(Bitmap)},
	 * and {@link ReferencedTypeEntityIndex#getIndexPrimaryKeys(RoaringBitmap)}.
	 */
	@Nested
	@DisplayName("Reference lookup")
	class ReferenceLookupTest {

		@Test
		@DisplayName("should return all index PKs for a given referenced entity PK")
		void shouldReturnAllReferenceIndexes() {
			// index PK 10 and 20 both reference entity 5
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 5);

			final int[] indexes = ReferencedTypeEntityIndexTest.this.index.getAllReferenceIndexes(5);

			assertEquals(2, indexes.length);
			assertArrayEquals(new int[]{10, 20}, indexes);
		}

		@Test
		@DisplayName("should return empty array for unknown referenced entity PK")
		void shouldReturnEmptyForUnknownReferencedPk() {
			final int[] indexes = ReferencedTypeEntityIndexTest.this.index.getAllReferenceIndexes(999);

			assertEquals(0, indexes.length);
		}

		@Test
		@DisplayName("should return referenced PKs for given index PKs")
		void shouldReturnReferencedPrimaryKeysForIndexPks() {
			// index PK 10 -> referenced entity 5
			// index PK 20 -> referenced entity 6
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 6);

			final Bitmap result = ReferencedTypeEntityIndexTest.this.index.getReferencedPrimaryKeysForIndexPks(
				new BaseBitmap(10, 20)
			);

			assertEquals(2, result.size());
			assertTrue(result.contains(5));
			assertTrue(result.contains(6));
		}

		@Test
		@DisplayName("should return empty bitmap when no index PKs match")
		void shouldReturnEmptyBitmapForNonMatchingIndexPks() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);

			final Bitmap result = ReferencedTypeEntityIndexTest.this.index.getReferencedPrimaryKeysForIndexPks(
				new BaseBitmap(999)
			);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should return empty bitmap for empty input")
		void shouldReturnEmptyBitmapForEmptyInput() {
			final Bitmap result = ReferencedTypeEntityIndexTest.this.index.getReferencedPrimaryKeysForIndexPks(
				EmptyBitmap.INSTANCE
			);

			assertSame(EmptyBitmap.INSTANCE, result);
		}

		@Test
		@DisplayName("should return index PKs for given referenced entity PKs")
		void shouldReturnIndexPrimaryKeys() {
			// index PK 10 references entity 5
			// index PK 20 references entity 6
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 6);

			final RoaringBitmap referencedPks = RoaringBitmap.bitmapOf(5, 6);
			final Bitmap result = ReferencedTypeEntityIndexTest.this.index.getIndexPrimaryKeys(referencedPks);

			assertEquals(2, result.size());
			assertTrue(result.contains(10));
			assertTrue(result.contains(20));
		}

		@Test
		@DisplayName("should return empty bitmap for empty referenced PK input")
		void shouldReturnEmptyForEmptyReferencedPkInput() {
			final Bitmap result = ReferencedTypeEntityIndexTest.this.index.getIndexPrimaryKeys(new RoaringBitmap());

			assertSame(EmptyBitmap.INSTANCE, result);
		}

		@Test
		@DisplayName("should return partial result for partially matching referenced PKs")
		void shouldReturnPartialResultForPartialMatch() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 5);
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(20, 6);

			// only ask for referenced entity 5
			final Bitmap result = ReferencedTypeEntityIndexTest.this.index.getIndexPrimaryKeys(
				RoaringBitmap.bitmapOf(5)
			);

			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}
	}

	/**
	 * Tests that sort attribute insert/remove and compound sort attribute insert/remove
	 * are no-ops.
	 */
	@Nested
	@DisplayName("Sort attribute no-ops")
	class SortAttributeNoOpsTest {

		@Test
		@DisplayName("should not create sort index from insertSortAttribute")
		void shouldNotCreateSortIndex() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema =
				createFilterableAttributeSchema("name", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			ReferencedTypeEntityIndexTest.this.index.insertSortAttribute(
				refSchema, attrSchema, noLocales, null, "value", 10
			);

			assertNull(
				ReferencedTypeEntityIndexTest.this.index.getSortIndex(new AttributeIndexKey(null, "name", null))
			);
		}

		@Test
		@DisplayName("should not throw from removeSortAttribute on empty index")
		void shouldNotThrowFromRemoveSortAttribute() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema =
				createFilterableAttributeSchema("name", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			assertDoesNotThrow(
				() -> ReferencedTypeEntityIndexTest.this.index.removeSortAttribute(
					refSchema, attrSchema, noLocales, null, "value", 10
				)
			);
		}

		@Test
		@DisplayName("should not create sort index from insertSortAttributeCompound")
		void shouldNotCreateSortIndexCompound() {
			final EntitySchemaContract entitySchema = mock(EntitySchemaContract.class);
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final SortableAttributeCompoundSchemaContract compoundSchema =
				mock(SortableAttributeCompoundSchemaContract.class);

			assertDoesNotThrow(
				() -> ReferencedTypeEntityIndexTest.this.index.insertSortAttributeCompound(
					entitySchema, refSchema, compoundSchema,
					name -> String.class, null,
					new Serializable[]{"a", "b"}, 10
				)
			);
		}

		@Test
		@DisplayName("should not throw from removeSortAttributeCompound")
		void shouldNotThrowFromRemoveSortAttributeCompound() {
			final EntitySchemaContract entitySchema = mock(EntitySchemaContract.class);
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final SortableAttributeCompoundSchemaContract compoundSchema =
				mock(SortableAttributeCompoundSchemaContract.class);

			assertDoesNotThrow(
				() -> ReferencedTypeEntityIndexTest.this.index.removeSortAttributeCompound(
					entitySchema, refSchema, compoundSchema,
					null, new Serializable[]{"a", "b"}, 10
				)
			);
		}
	}

	/**
	 * Tests for filter attribute cardinality tracking via
	 * {@link ReferencedTypeEntityIndex#insertFilterAttribute} and
	 * {@link ReferencedTypeEntityIndex#removeFilterAttribute}.
	 */
	@Nested
	@DisplayName("Filter attribute cardinality tracking")
	class FilterAttributeCardinalityTest {

		private ReferenceSchemaContract referenceSchema;
		private AttributeSchemaContract stringAttrSchema;

		/**
		 * Lazily creates the reference schema mock.
		 *
		 * @return a mock {@link ReferenceSchemaContract}
		 */
		@Nonnull
		private ReferenceSchemaContract getReferenceSchema() {
			if (this.referenceSchema == null) {
				this.referenceSchema = mock(ReferenceSchemaContract.class);
			}
			return this.referenceSchema;
		}

		/**
		 * Lazily creates the string attribute schema.
		 *
		 * @return a filterable String attribute schema named "code"
		 */
		@Nonnull
		private AttributeSchemaContract getStringAttrSchema() {
			if (this.stringAttrSchema == null) {
				this.stringAttrSchema =
					createFilterableAttributeSchema("code", String.class);
			}
			return this.stringAttrSchema;
		}

		@Test
		@DisplayName("should add attribute to filter index on first occurrence")
		void shouldAddAttributeOnFirstOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			final Bitmap result = ReferencedTypeEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}

		@Test
		@DisplayName("should not duplicate attribute on second occurrence with same value")
		void shouldNotDuplicateAttributeOnSecondOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);
			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			final Bitmap result = ReferencedTypeEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should retain attribute after removing one of two occurrences")
		void shouldRetainAttributeAfterRemovingOneOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);
			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			ReferencedTypeEntityIndexTest.this.index.removeFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			final Bitmap result = ReferencedTypeEntityIndexTest.this.index
				.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getRecordsEqualTo("ABC");
			assertEquals(1, result.size());
			assertTrue(result.contains(10));
		}

		@Test
		@DisplayName("should remove attribute from filter index when last occurrence removed")
		void shouldRemoveAttributeOnLastOccurrence() {
			final Set<Locale> noLocales = Collections.emptySet();

			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);
			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			ReferencedTypeEntityIndexTest.this.index.removeFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);
			ReferencedTypeEntityIndexTest.this.index.removeFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			assertNull(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(new AttributeIndexKey(null, "code", null))
			);
		}

		@Test
		@DisplayName("should handle array attribute values with cardinality delta tracking")
		void shouldHandleArrayAttributeValuesWithCardinality() {
			final Set<Locale> noLocales = Collections.emptySet();
			final AttributeSchemaContract arrayAttrSchema =
				createFilterableAttributeSchema("tags", String[].class);

			// first reference adds tags ["A", "B"]
			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), arrayAttrSchema, noLocales, null,
				new String[]{"A", "B"}, 10
			);

			// second reference adds tags ["B", "C"] -- "B" already has cardinality,
			// only "C" should be newly added to the filter index
			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), arrayAttrSchema, noLocales, null,
				new String[]{"B", "C"}, 10
			);

			final AttributeIndexKey tagKey =
				new AttributeIndexKey(null, "tags", null);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(tagKey).getRecordsEqualTo("A").contains(10)
			);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(tagKey).getRecordsEqualTo("B").contains(10)
			);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(tagKey).getRecordsEqualTo("C").contains(10)
			);

			// remove first reference's tags ["A", "B"]
			// "A" cardinality 1->0 => removed; "B" cardinality 2->1 => stays
			ReferencedTypeEntityIndexTest.this.index.removeFilterAttribute(
				getReferenceSchema(), arrayAttrSchema, noLocales, null,
				new String[]{"A", "B"}, 10
			);

			assertFalse(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(tagKey).getRecordsEqualTo("A").contains(10)
			);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(tagKey).getRecordsEqualTo("B").contains(10)
			);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(tagKey).getRecordsEqualTo("C").contains(10)
			);
		}

		@Test
		@DisplayName("should handle different attribute values from different references")
		void shouldHandleDifferentValuesFromDifferentReferences() {
			final Set<Locale> noLocales = Collections.emptySet();

			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);
			ReferencedTypeEntityIndexTest.this.index.insertFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "XYZ", 10
			);

			final AttributeIndexKey codeKey =
				new AttributeIndexKey(null, "code", null);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(codeKey).getRecordsEqualTo("ABC").contains(10)
			);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(codeKey).getRecordsEqualTo("XYZ").contains(10)
			);

			ReferencedTypeEntityIndexTest.this.index.removeFilterAttribute(
				getReferenceSchema(), getStringAttrSchema(),
				noLocales, null, "ABC", 10
			);

			assertFalse(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(codeKey).getRecordsEqualTo("ABC").contains(10)
			);
			assertTrue(
				ReferencedTypeEntityIndexTest.this.index.getFilterIndex(codeKey).getRecordsEqualTo("XYZ").contains(10)
			);
		}
	}

	/**
	 * Tests for {@link ReferencedTypeEntityIndex#isEmpty()} including
	 * cardinality index emptiness check.
	 */
	@Nested
	@DisplayName("isEmpty with cardinality index")
	class IsEmptyTest {

		@Test
		@DisplayName("should be empty when freshly created")
		void shouldBeEmptyWhenFreshlyCreated() {
			assertTrue(ReferencedTypeEntityIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("should not be empty after inserting a PK")
		void shouldNotBeEmptyAfterInsert() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			assertFalse(ReferencedTypeEntityIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("should become empty after removing all PKs")
		void shouldBecomeEmptyAfterRemovingAll() {
			ReferencedTypeEntityIndexTest.this.index.insertPrimaryKeyIfMissing(10, 1);

			ReferencedTypeEntityIndexTest.this.index.removePrimaryKey(10, 1);

			assertTrue(ReferencedTypeEntityIndexTest.this.index.isEmpty());
		}
	}

	/**
	 * Tests for the ByteBuddy proxy stub created by
	 * {@link ReferencedTypeEntityIndex#createThrowingStub(EntitySchemaContract, EntityIndexKey)}.
	 */
	@Nested
	@DisplayName("Throwing stub proxy")
	class ThrowingStubProxyTest {

		/**
		 * Creates a throwing stub proxy instance for testing.
		 *
		 * @return a proxy {@link ReferencedTypeEntityIndex} that throws for most methods
		 */
		@Nonnull
		private static ReferencedTypeEntityIndex createStub() {
			final EntitySchemaContract schema = mock(EntitySchemaContract.class);
			when(schema.getName()).thenReturn(ENTITY_TYPE);
			final EntityIndexKey key = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME
			);
			return ReferencedTypeEntityIndex.createThrowingStub(schema, key);
		}

		@Test
		@DisplayName("should create non-null proxy instance")
		void shouldCreateProxy() {
			final ReferencedTypeEntityIndex stub = createStub();

			assertNotNull(stub);
			assertInstanceOf(ReferencedTypeEntityIndex.class, stub);
		}

		@Test
		@DisplayName("should return 0 as ID")
		void shouldReturnZeroId() {
			final ReferencedTypeEntityIndex stub = createStub();

			assertEquals(0L, stub.getId());
		}

		@Test
		@DisplayName("should return correct index key")
		void shouldReturnIndexKey() {
			final EntityIndexKey expectedKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME
			);
			final EntitySchemaContract schema = mock(EntitySchemaContract.class);
			when(schema.getName()).thenReturn(ENTITY_TYPE);
			final ReferencedTypeEntityIndex stub =
				ReferencedTypeEntityIndex.createThrowingStub(schema, expectedKey);

			assertEquals(expectedKey, stub.getIndexKey());
		}

		@Test
		@DisplayName("should throw ReferenceNotIndexedException for insertPrimaryKeyIfMissing")
		void shouldThrowForInsert() {
			final ReferencedTypeEntityIndex stub = createStub();

			assertThrows(
				ReferenceNotIndexedException.class,
				() -> stub.insertPrimaryKeyIfMissing(10, 1)
			);
		}

		@Test
		@DisplayName("should throw ReferenceNotIndexedException for getAllPrimaryKeys")
		void shouldThrowForGetAllPrimaryKeys() {
			final ReferencedTypeEntityIndex stub = createStub();

			assertThrows(
				ReferenceNotIndexedException.class,
				stub::getAllPrimaryKeys
			);
		}

		@Test
		@DisplayName("should not throw for Object methods (toString, hashCode, equals)")
		void shouldNotThrowForObjectMethods() {
			final ReferencedTypeEntityIndex stub = createStub();

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
		@DisplayName("should commit PK insert with cardinality and preserve in new instance")
		void shouldCommitPkInsertWithCardinality() {
			final AtomicReference<ReferencedTypeEntityIndex> committedRef =
				new AtomicReference<>();

			assertStateAfterCommit(
				ReferencedTypeEntityIndexTest.this.index,
				original -> {
					original.insertPrimaryKeyIfMissing(10, 1);
					original.insertPrimaryKeyIfMissing(10, 2);
					original.insertPrimaryKeyIfMissing(20, 3);
				},
				(original, committed) -> {
					// original should still be empty
					assertTrue(original.getAllPrimaryKeys().isEmpty());

					// committed should have the PKs
					assertNotNull(committed);
					assertTrue(committed.getAllPrimaryKeys().contains(10));
					assertTrue(committed.getAllPrimaryKeys().contains(20));
					assertEquals(2, committed.getAllPrimaryKeys().size());

					committedRef.set(committed);
				}
			);

			// verify cardinality survived commit by checking reference lookup
			final ReferencedTypeEntityIndex committed = committedRef.get();
			assertNotNull(committed);
			final int[] refIndexes = committed.getAllReferenceIndexes(1);
			assertEquals(1, refIndexes.length);
			assertEquals(10, refIndexes[0]);
		}

		@Test
		@DisplayName("should commit filter attribute changes")
		void shouldCommitFilterAttributeChanges() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema =
				createFilterableAttributeSchema("code", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			assertStateAfterCommit(
				ReferencedTypeEntityIndexTest.this.index,
				original -> original.insertFilterAttribute(
					refSchema, attrSchema, noLocales, null, "ABC", 10
				),
				(original, committed) -> {
					// original should not have the attribute
					assertNull(
						original.getFilterIndex(
							new AttributeIndexKey(null, "code", null)
						)
					);

					// committed should have it
					assertNotNull(committed);
					final Bitmap result = committed
						.getFilterIndex(
							new AttributeIndexKey(null, "code", null)
						)
						.getRecordsEqualTo("ABC");
					assertEquals(1, result.size());
					assertTrue(result.contains(10));
				}
			);
		}

		@Test
		@DisplayName("should increment version when dirty")
		void shouldIncrementVersionWhenDirty() {
			assertStateAfterCommit(
				ReferencedTypeEntityIndexTest.this.index,
				original -> original.insertPrimaryKeyIfMissing(10, 1),
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
				ReferencedTypeEntityIndexTest.this.index,
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
	 * Tests for STM transactional rollback behavior.
	 */
	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("should discard PK insert on rollback")
		void shouldDiscardPkInsertOnRollback() {
			assertStateAfterRollback(
				ReferencedTypeEntityIndexTest.this.index,
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
		@DisplayName("should discard filter attribute changes on rollback")
		void shouldDiscardFilterAttributeOnRollback() {
			final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
			final AttributeSchemaContract attrSchema =
				createFilterableAttributeSchema("code", String.class);
			final Set<Locale> noLocales = Collections.emptySet();

			assertStateAfterRollback(
				ReferencedTypeEntityIndexTest.this.index,
				original -> original.insertFilterAttribute(
					refSchema, attrSchema, noLocales, null, "ABC", 10
				),
				(original, committed) -> {
					assertNull(
						original.getFilterIndex(
							new AttributeIndexKey(null, "code", null)
						)
					);
					assertNull(committed);
				}
			);
		}
	}

	/**
	 * Generational property-based stress test for {@link ReferencedTypeEntityIndex}.
	 * Runs randomized PK insert/remove pairs with cardinality tracking and filter
	 * attributes, comparing committed state against a JDK reference implementation.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		/**
		 * Runs the generational proof test with randomized insert/remove operations.
		 */
		@DisplayName("survives generational randomized test")
		@ParameterizedTest(name = "ReferencedTypeEntityIndex should survive generational test")
		@Tag(LONG_RUNNING_TEST)
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
						original -> {
							final int ops = random.nextInt(5) + 1;
							for (int i = 0; i < ops; i++) {
								executeRandomOperation(
									random, original, referenceModel
								);
							}
						},
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
		 * Executes a random insert or remove operation on the index and
		 * the reference model.
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
				// insert
				index.insertPrimaryKeyIfMissing(indexPk, refPk);
				referenceModel
					.computeIfAbsent(indexPk, k -> new HashSet<>(4))
					.add(refPk);
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
}
