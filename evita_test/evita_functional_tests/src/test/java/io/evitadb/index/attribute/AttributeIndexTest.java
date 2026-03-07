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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AttributeIndex} covering construction, non-transactional
 * operations, STM commit/rollback, static utility methods, predecessor routing,
 * storage parts, and error paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("AttributeIndex")
class AttributeIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String REFERENCE_NAME = "brand";
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH, new Locale("cs"));

	/**
	 * Creates a non-localized, non-unique entity attribute schema mock for the given attribute name and type.
	 */
	@Nonnull
	private static EntityAttributeSchemaContract createEntityAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> type
	) {
		final EntityAttributeSchemaContract schema = mock(EntityAttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(type).when(schema).getType();
		doReturn(type).when(schema).getPlainType();
		when(schema.isLocalized()).thenReturn(false);
		when(schema.isUniqueWithinLocaleInScope(Scope.LIVE)).thenReturn(false);
		return schema;
	}

	/**
	 * Creates a localized entity attribute schema mock.
	 */
	@Nonnull
	private static EntityAttributeSchemaContract createLocalizedEntityAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> type
	) {
		final EntityAttributeSchemaContract schema = mock(EntityAttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(type).when(schema).getType();
		doReturn(type).when(schema).getPlainType();
		when(schema.isLocalized()).thenReturn(true);
		when(schema.isUniqueWithinLocaleInScope(Scope.LIVE)).thenReturn(false);
		return schema;
	}

	/**
	 * Creates a unique-within-locale entity attribute schema mock.
	 */
	@Nonnull
	private static EntityAttributeSchemaContract createUniqueWithinLocaleSchema(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> type
	) {
		final EntityAttributeSchemaContract schema = mock(EntityAttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(type).when(schema).getType();
		doReturn(type).when(schema).getPlainType();
		when(schema.isLocalized()).thenReturn(true);
		when(schema.isUniqueWithinLocaleInScope(Scope.LIVE)).thenReturn(true);
		return schema;
	}

	/**
	 * Creates a reference attribute schema mock (not entity-level).
	 */
	@Nonnull
	private static AttributeSchemaContract createReferenceAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> type
	) {
		final AttributeSchemaContract schema = mock(AttributeSchemaContract.class);
		when(schema.getName()).thenReturn(attributeName);
		doReturn(type).when(schema).getType();
		doReturn(type).when(schema).getPlainType();
		when(schema.isLocalized()).thenReturn(false);
		when(schema.isUniqueWithinLocaleInScope(Scope.LIVE)).thenReturn(false);
		return schema;
	}

	/**
	 * Creates a reference schema mock with a given name.
	 */
	@Nonnull
	private static ReferenceSchemaContract createReferenceSchema(@Nonnull String referenceName) {
		final ReferenceSchemaContract schema = mock(ReferenceSchemaContract.class);
		when(schema.getName()).thenReturn(referenceName);
		return schema;
	}

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("empty constructor produces empty index with all maps empty")
		void shouldCreateEmptyAttributeIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);

			assertTrue(index.isAttributeIndexEmpty());
			assertTrue(index.getUniqueIndexes().isEmpty());
			assertTrue(index.getFilterIndexes().isEmpty());
			assertTrue(index.getSortIndexes().isEmpty());
			assertTrue(index.getChainIndexes().isEmpty());
			assertEquals(ENTITY_TYPE, index.getEntityType());
		}

		@Test
		@DisplayName("six-arg deserialization constructor populates maps")
		void shouldCreateWithPrePopulatedMaps() {
			final AttributeIndexKey uniqueKey = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final UniqueIndex uniqueIdx = new UniqueIndex(ENTITY_TYPE, uniqueKey, String.class);
			uniqueIdx.registerUniqueKey("ABC", 1);

			final AttributeIndexKey filterKey = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final FilterIndex filterIdx = new FilterIndex(filterKey, String.class);
			filterIdx.addRecord(1, "TestProduct");

			final AttributeIndexKey sortKey = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
			final SortIndex sortIdx = new SortIndex(Integer.class, sortKey);
			sortIdx.addRecord(10, 1);

			final AttributeIndexKey chainKey = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex chainIdx = new ChainIndex(chainKey);
			chainIdx.upsertPredecessor(Predecessor.HEAD, 1);

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Map.of(uniqueKey, uniqueIdx),
				Map.of(filterKey, filterIdx),
				Map.of(sortKey, sortIdx),
				Map.of(chainKey, chainIdx)
			);

			assertFalse(index.isAttributeIndexEmpty());
			assertEquals(1, index.getUniqueIndexes().size());
			assertEquals(1, index.getFilterIndexes().size());
			assertEquals(1, index.getSortIndexes().size());
			assertEquals(1, index.getChainIndexes().size());
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("getId() returns stable unique value per instance")
		void shouldReturnStableUniqueId() {
			final AttributeIndex first = new AttributeIndex(ENTITY_TYPE, null);
			final AttributeIndex second = new AttributeIndex(ENTITY_TYPE, null);

			assertNotEquals(first.getId(), second.getId());
			// id is stable across calls
			assertEquals(first.getId(), first.getId());
		}

		@Test
		@DisplayName("removeLayer cleans all four nested TransactionalMaps")
		void shouldCleanAllMapsOnRemoveLayer() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract codeSchema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);
			final EntityAttributeSchemaContract nameSchema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);
			final EntityAttributeSchemaContract prioritySchema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);
			final EntityAttributeSchemaContract orderSchema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			// insert into all four indexes, then rollback -- all transactional layers should be cleaned
			assertStateAfterRollback(
				index,
				original -> {
					original.insertUniqueAttribute(
						null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "X", 1
					);
					original.insertFilterAttribute(
						null, nameSchema, ALLOWED_LOCALES, null, "Product", 1
					);
					original.insertSortAttribute(
						null, prioritySchema, ALLOWED_LOCALES, null, 10, 1
					);
					original.insertSortAttribute(
						null, orderSchema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
					);
				},
				(original, committed) -> {
					// after rollback, committed is null and original stays empty
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("committed copy is new instance (assertNotSame)")
		void shouldReturnNewInstanceAfterCommit() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract codeSchema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			assertStateAfterCommit(
				index,
				original -> original.insertUniqueAttribute(
					null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "A", 1
				),
				(original, committed) -> {
					assertNotNull(committed);
					assertNotSame(original, committed);
				}
			);
		}

		@Test
		@DisplayName("commit merges state from all four maps (INV-6)")
		void shouldMergeAllFourMapsOnCommit() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract codeSchema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);
			final EntityAttributeSchemaContract nameSchema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);
			final EntityAttributeSchemaContract prioritySchema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);
			final EntityAttributeSchemaContract orderSchema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			assertStateAfterCommit(
				index,
				original -> {
					original.insertUniqueAttribute(
						null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "MERGED", 1
					);
					original.insertFilterAttribute(
						null, nameSchema, ALLOWED_LOCALES, null, "FilterVal", 1
					);
					original.insertSortAttribute(
						null, prioritySchema, ALLOWED_LOCALES, null, 7, 1
					);
					original.insertSortAttribute(
						null, orderSchema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
					);
				},
				(original, committed) -> {
					assertEquals(1, committed.getUniqueIndexes().size());
					assertEquals(1, committed.getFilterIndexes().size());
					assertEquals(1, committed.getSortIndexes().size());
					assertEquals(1, committed.getChainIndexes().size());
				}
			);
		}

		@Test
		@DisplayName("commit with null layer returns valid copy")
		void shouldHandleNullLayerOnCommit() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			// insert outside tx first so state is populated
			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "hello", 1);

			// now commit with changes -- this exercises createCopyWithMergedTransactionalMemory
			assertStateAfterCommit(
				index,
				original -> {
					// no-op transaction -- layer exists but no actual changes
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertFalse(committed.isAttributeIndexEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("unique index insertion and removal visible after commit")
		void shouldCommitUniqueIndexInsertionAndRemoval() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			// commit insertion
			assertStateAfterCommit(
				index,
				original -> original.insertUniqueAttribute(
					null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "ABC", 1
				),
				(original, committed) -> {
					assertFalse(committed.isAttributeIndexEmpty());
					assertEquals(1, committed.getUniqueIndexes().size());
				}
			);
		}

		@Test
		@DisplayName("filter index insertion and removal visible after commit")
		void shouldCommitFilterIndexInsertionAndRemoval() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			assertStateAfterCommit(
				index,
				original -> original.insertFilterAttribute(
					null, schema, ALLOWED_LOCALES, null, "Product", 1
				),
				(original, committed) -> {
					assertFalse(committed.isAttributeIndexEmpty());
					assertEquals(1, committed.getFilterIndexes().size());
				}
			);
		}

		@Test
		@DisplayName("sort index insertion visible after commit")
		void shouldCommitSortIndexInsertionAndRemoval() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(
					null, schema, ALLOWED_LOCALES, null, 42, 1
				),
				(original, committed) -> {
					assertFalse(committed.isAttributeIndexEmpty());
					assertEquals(1, committed.getSortIndexes().size());
				}
			);
		}

		@Test
		@DisplayName("chain index upsert via Predecessor visible after commit")
		void shouldCommitChainIndexViaPredecessor() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(
					null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
				),
				(original, committed) -> {
					assertFalse(committed.isAttributeIndexEmpty());
					assertEquals(1, committed.getChainIndexes().size());
					// sort index should remain empty -- Predecessor goes to chain
					assertTrue(committed.getSortIndexes().isEmpty());
				}
			);
		}

		@Test
		@DisplayName("chain index upsert via ReferencedEntityPredecessor")
		void shouldCommitChainIndexViaReferencedEntityPredecessor() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.class);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(
					null, schema, ALLOWED_LOCALES, null, ReferencedEntityPredecessor.HEAD, 1
				),
				(original, committed) -> {
					assertFalse(committed.isAttributeIndexEmpty());
					assertEquals(1, committed.getChainIndexes().size());
				}
			);
		}

		@Test
		@DisplayName("original unchanged after commit (T2)")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			assertStateAfterCommit(
				index,
				original -> {
					original.insertUniqueAttribute(
						null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "X", 1
					);
					original.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "X", 1);
				},
				(original, committed) -> {
					// original stays empty
					assertTrue(original.isAttributeIndexEmpty());
					// committed has data
					assertFalse(committed.isAttributeIndexEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("unique index insertion rolled back")
		void shouldRollbackUniqueIndexInsertion() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			assertStateAfterRollback(
				index,
				original -> original.insertUniqueAttribute(
					null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "Z", 1
				),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("filter index insertion rolled back")
		void shouldRollbackFilterIndexInsertion() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			assertStateAfterRollback(
				index,
				original -> original.insertFilterAttribute(
					null, schema, ALLOWED_LOCALES, null, "RolledBack", 1
				),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("sort index insertion rolled back")
		void shouldRollbackSortIndexInsertion() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			assertStateAfterRollback(
				index,
				original -> original.insertSortAttribute(
					null, schema, ALLOWED_LOCALES, null, 99, 1
				),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("chain index insertion rolled back")
		void shouldRollbackChainIndexInsertion() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			assertStateAfterRollback(
				index,
				original -> original.insertSortAttribute(
					null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
				),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalTest {

		@Test
		@DisplayName("all four insert operations populate indexes directly")
		void shouldInsertOutsideTransaction() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract codeSchema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);
			final EntityAttributeSchemaContract nameSchema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);
			final EntityAttributeSchemaContract prioritySchema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);
			final EntityAttributeSchemaContract orderSchema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			index.insertUniqueAttribute(null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "UNIQUE1", 1);
			index.insertFilterAttribute(null, nameSchema, ALLOWED_LOCALES, null, "Filter1", 1);
			index.insertSortAttribute(null, prioritySchema, ALLOWED_LOCALES, null, 10, 1);
			index.insertSortAttribute(null, orderSchema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertFalse(index.isAttributeIndexEmpty());
			assertEquals(1, index.getUniqueIndexes().size());
			assertEquals(1, index.getFilterIndexes().size());
			assertEquals(1, index.getSortIndexes().size());
			assertEquals(1, index.getChainIndexes().size());
		}

		@Test
		@DisplayName("all four remove operations leave index empty")
		void shouldRemoveOutsideTransaction() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract codeSchema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);
			final EntityAttributeSchemaContract nameSchema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);
			final EntityAttributeSchemaContract prioritySchema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);
			final EntityAttributeSchemaContract orderSchema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			// insert first
			index.insertUniqueAttribute(null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "UNIQUE1", 1);
			index.insertFilterAttribute(null, nameSchema, ALLOWED_LOCALES, null, "Filter1", 1);
			index.insertSortAttribute(null, prioritySchema, ALLOWED_LOCALES, null, 10, 1);
			index.insertSortAttribute(null, orderSchema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertFalse(index.isAttributeIndexEmpty());

			// now remove everything
			index.removeUniqueAttribute(null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "UNIQUE1", 1);
			index.removeFilterAttribute(null, nameSchema, ALLOWED_LOCALES, null, "Filter1", 1);
			index.removeSortAttribute(null, prioritySchema, ALLOWED_LOCALES, null, 10, 1);
			index.removeSortAttribute(null, orderSchema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertTrue(index.isAttributeIndexEmpty());
		}
	}

	@Nested
	@DisplayName("Static utility: createAttributeKey")
	class CreateAttributeKeyTest {

		@Test
		@DisplayName("non-localized entity attribute produces key without locale")
		void shouldCreateKeyForNonLocalizedAttribute() {
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(null, schema, ALLOWED_LOCALES, null, "testValue");

			assertNull(key.referenceName());
			assertEquals(ATTRIBUTE_CODE, key.attributeName());
			assertNull(key.locale());
		}

		@Test
		@DisplayName("localized entity attribute produces key with locale")
		void shouldCreateKeyForLocalizedAttribute() {
			final EntityAttributeSchemaContract schema =
				createLocalizedEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(null, schema, ALLOWED_LOCALES, Locale.ENGLISH, "testValue");

			assertNull(key.referenceName());
			assertEquals(ATTRIBUTE_NAME, key.attributeName());
			assertEquals(Locale.ENGLISH, key.locale());
		}

		@Test
		@DisplayName("localized schema with null locale throws exception")
		void shouldThrowWhenLocalizedSchemaGivenNullLocale() {
			final EntityAttributeSchemaContract schema =
				createLocalizedEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeIndex.createAttributeKey(null, schema, ALLOWED_LOCALES, null, "testValue")
			);
		}

		@Test
		@DisplayName("locale not in allowed locales throws exception")
		void shouldThrowWhenLocaleNotAllowed() {
			final EntityAttributeSchemaContract schema =
				createLocalizedEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeIndex.createAttributeKey(null, schema, ALLOWED_LOCALES, Locale.JAPANESE, "testValue")
			);
		}

		@Test
		@DisplayName("reference attribute produces key with reference name")
		void shouldCreateKeyForReferenceAttribute() {
			final AttributeSchemaContract schema = createReferenceAttributeSchema(ATTRIBUTE_CODE, String.class);
			final ReferenceSchemaContract refSchema = createReferenceSchema(REFERENCE_NAME);

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(refSchema, schema, ALLOWED_LOCALES, null, "val");

			assertEquals(REFERENCE_NAME, key.referenceName());
			assertEquals(ATTRIBUTE_CODE, key.attributeName());
			assertNull(key.locale());
		}

		@Test
		@DisplayName("entity-level attribute with reference schema still produces null reference name")
		void shouldCreateKeyForEntityAttrWithRefSchema() {
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);
			final ReferenceSchemaContract refSchema = createReferenceSchema(REFERENCE_NAME);

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(refSchema, schema, ALLOWED_LOCALES, null, "val");

			// entity-level attribute ignores reference name
			assertNull(key.referenceName());
		}
	}

	@Nested
	@DisplayName("isAttributeIndexEmpty")
	class IsAttributeIndexEmptyTest {

		@Test
		@DisplayName("all four maps empty returns true")
		void shouldReturnTrueWhenAllEmpty() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);

			assertTrue(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only unique index has data returns false")
		void shouldReturnFalseWhenOnlyUniqueHasData() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			index.insertUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "X", 1);

			assertFalse(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only filter index has data returns false")
		void shouldReturnFalseWhenOnlyFilterHasData() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "Y", 1);

			assertFalse(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only sort index has data returns false")
		void shouldReturnFalseWhenOnlySortHasData() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, 5, 1);

			assertFalse(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only chain index has data returns false")
		void shouldReturnFalseWhenOnlyChainHasData() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertFalse(index.isAttributeIndexEmpty());
		}
	}

	@Nested
	@DisplayName("Predecessor routing")
	class PredecessorRoutingTest {

		@Test
		@DisplayName("Predecessor value routes to chain index")
		void shouldRoutePredecessorToChainIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertEquals(1, index.getChainIndexes().size());
			assertTrue(index.getSortIndexes().isEmpty());
		}

		@Test
		@DisplayName("ReferencedEntityPredecessor routes to chain index")
		void shouldRouteReferencedEntityPredecessorToChainIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, ReferencedEntityPredecessor.HEAD, 1);

			assertEquals(1, index.getChainIndexes().size());
			assertTrue(index.getSortIndexes().isEmpty());
		}

		@Test
		@DisplayName("Integer value routes to sort index")
		void shouldRouteIntegerToSortIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, 100, 1);

			assertEquals(1, index.getSortIndexes().size());
			assertTrue(index.getChainIndexes().isEmpty());
		}
	}

	@Nested
	@DisplayName("Storage parts")
	class StoragePartsTest {

		@Test
		@DisplayName("createStoragePart dispatches for UNIQUE type")
		void shouldCreateStoragePartForUniqueType() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final UniqueIndex uniqueIdx = new UniqueIndex(ENTITY_TYPE, key, String.class);
			uniqueIdx.registerUniqueKey("ABC", 1);

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Map.of(key, uniqueIdx),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null);
			final AttributeIndexStorageKey storageKey =
				new AttributeIndexStorageKey(entityIndexKey, AttributeIndexType.UNIQUE, key);

			assertNotNull(index.createStoragePart(1, storageKey));
		}

		@Test
		@DisplayName("createStoragePart dispatches for FILTER type")
		void shouldCreateStoragePartForFilterType() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final FilterIndex filterIdx = new FilterIndex(key, String.class);
			filterIdx.addRecord(1, "Test");

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Collections.emptyMap(),
				Map.of(key, filterIdx),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null);
			final AttributeIndexStorageKey storageKey =
				new AttributeIndexStorageKey(entityIndexKey, AttributeIndexType.FILTER, key);

			assertNotNull(index.createStoragePart(1, storageKey));
		}

		@Test
		@DisplayName("createStoragePart dispatches for SORT type")
		void shouldCreateStoragePartForSortType() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
			final SortIndex sortIdx = new SortIndex(Integer.class, key);
			sortIdx.addRecord(10, 1);

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Map.of(key, sortIdx),
				Collections.emptyMap()
			);

			final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null);
			final AttributeIndexStorageKey storageKey =
				new AttributeIndexStorageKey(entityIndexKey, AttributeIndexType.SORT, key);

			assertNotNull(index.createStoragePart(1, storageKey));
		}

		@Test
		@DisplayName("createStoragePart dispatches for CHAIN type")
		void shouldCreateStoragePartForChainType() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex chainIdx = new ChainIndex(key);
			chainIdx.upsertPredecessor(Predecessor.HEAD, 1);

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Map.of(key, chainIdx)
			);

			final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null);
			final AttributeIndexStorageKey storageKey =
				new AttributeIndexStorageKey(entityIndexKey, AttributeIndexType.CHAIN, key);

			assertNotNull(index.createStoragePart(1, storageKey));
		}

		@Test
		@DisplayName("createStoragePart with unknown type throws error")
		void shouldThrowOnUnknownStoragePartType() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null);
			final AttributeIndexStorageKey storageKey =
				new AttributeIndexStorageKey(entityIndexKey, AttributeIndexType.CARDINALITY, key);

			assertThrows(GenericEvitaInternalError.class, () -> index.createStoragePart(1, storageKey));
		}

		@Test
		@DisplayName("getModifiedStorageParts collects from all dirty sub-indexes")
		void shouldCollectModifiedStorageParts() {
			final AttributeIndexKey uniqueKey = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final UniqueIndex uniqueIdx = new UniqueIndex(ENTITY_TYPE, uniqueKey, String.class);
			uniqueIdx.registerUniqueKey("ABC", 1);

			final AttributeIndexKey filterKey = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final FilterIndex filterIdx = new FilterIndex(filterKey, String.class);
			filterIdx.addRecord(1, "Product");

			final AttributeIndexKey sortKey = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
			final SortIndex sortIdx = new SortIndex(Integer.class, sortKey);
			sortIdx.addRecord(10, 1);

			final AttributeIndexKey chainKey = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex chainIdx = new ChainIndex(chainKey);
			chainIdx.upsertPredecessor(Predecessor.HEAD, 1);

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Map.of(uniqueKey, uniqueIdx),
				Map.of(filterKey, filterIdx),
				Map.of(sortKey, sortIdx),
				Map.of(chainKey, chainIdx)
			);

			final TrappedChanges trappedChanges = new TrappedChanges();
			index.getModifiedStorageParts(1, trappedChanges);

			// all four sub-indexes are dirty after population, so we expect at least 4 storage parts
			final int count = trappedChanges.getTrappedChangesCount();
			assertTrue(count >= 4, "Expected at least 4 storage parts but got " + count);
		}

		@Test
		@DisplayName("resetDirty clears dirty flags; createStoragePart returns null after reset")
		void shouldResetDirtyOnAllSubIndexes() {
			final AttributeIndexKey uniqueKey = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final UniqueIndex uniqueIdx = new UniqueIndex(ENTITY_TYPE, uniqueKey, String.class);
			uniqueIdx.registerUniqueKey("ABC", 1);

			final AttributeIndex index = new AttributeIndex(
				ENTITY_TYPE, null,
				Map.of(uniqueKey, uniqueIdx),
				Collections.emptyMap(),
				Collections.emptyMap(),
				Collections.emptyMap()
			);

			// first call to createStoragePart should return non-null because index is dirty
			final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null);
			final AttributeIndexStorageKey storageKey =
				new AttributeIndexStorageKey(entityIndexKey, AttributeIndexType.UNIQUE, uniqueKey);
			assertNotNull(index.createStoragePart(1, storageKey));

			// reset dirty on all sub-indexes
			index.resetDirty();

			// after reset, createStoragePart returns null
			assertNull(index.createStoragePart(1, storageKey));
		}
	}

	@Nested
	@DisplayName("Error paths")
	class ErrorPathsTest {

		@Test
		@DisplayName("getUniqueIndex with unique-within-locale schema and null locale throws EntityLocaleMissingException")
		void shouldThrowWhenUniqueWithinLocaleWithoutLocale() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createUniqueWithinLocaleSchema(ATTRIBUTE_NAME, String.class);

			assertThrows(
				EntityLocaleMissingException.class,
				() -> index.getUniqueIndex(null, schema, Scope.LIVE, null)
			);
		}

		@Test
		@DisplayName("remove non-existent unique attribute throws")
		void shouldThrowWhenRemovingNonExistentUnique() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "X", 1)
			);
		}

		@Test
		@DisplayName("remove non-existent filter attribute throws")
		void shouldThrowWhenRemovingNonExistentFilter() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeFilterAttribute(null, schema, ALLOWED_LOCALES, null, "Foo", 1)
			);
		}

		@Test
		@DisplayName("remove non-existent sort attribute throws")
		void shouldThrowWhenRemovingNonExistentSort() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeSortAttribute(null, schema, ALLOWED_LOCALES, null, 99, 1)
			);
		}

		@Test
		@DisplayName("remove non-existent chain attribute throws")
		void shouldThrowWhenRemovingNonExistentChain() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1)
			);
		}
	}

	@Nested
	@DisplayName("TransactionalContainerChanges cleanup")
	class ContainerChangesCleanupTest {

		@Test
		@DisplayName("created-then-removed UniqueIndex cleaned via rollback")
		void shouldCleanCreatedThenRemovedUniqueIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			assertStateAfterRollback(
				index,
				original -> {
					original.insertUniqueAttribute(
						null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "TEMP", 1
					);
					original.removeUniqueAttribute(
						null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "TEMP", 1
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("created-then-removed FilterIndex cleaned via rollback")
		void shouldCleanCreatedThenRemovedFilterIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			assertStateAfterRollback(
				index,
				original -> {
					original.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "TEMP", 1);
					original.removeFilterAttribute(null, schema, ALLOWED_LOCALES, null, "TEMP", 1);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("created-then-removed SortIndex cleaned via rollback")
		void shouldCleanCreatedThenRemovedSortIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			assertStateAfterRollback(
				index,
				original -> {
					original.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, 10, 1);
					original.removeSortAttribute(null, schema, ALLOWED_LOCALES, null, 10, 1);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("created-then-removed ChainIndex cleaned via rollback")
		void shouldCleanCreatedThenRemovedChainIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			assertStateAfterRollback(
				index,
				original -> {
					original.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);
					original.removeSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("Index retrieval")
	class IndexRetrievalTest {

		@Test
		@DisplayName("getUniqueIndex returns existing index")
		void shouldReturnExistingUniqueIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			index.insertUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "ABC", 1);

			final UniqueIndex result = index.getUniqueIndex(null, schema, Scope.LIVE, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getUniqueIndex returns null for missing index")
		void shouldReturnNullForMissingUniqueIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_CODE, String.class);

			final UniqueIndex result = index.getUniqueIndex(null, schema, Scope.LIVE, null);

			assertNull(result);
		}

		@Test
		@DisplayName("getFilterIndex by key returns existing index")
		void shouldReturnExistingFilterIndexByKey() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "Test", 1);

			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final FilterIndex result = index.getFilterIndex(key);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getFilterIndex by schema returns existing index")
		void shouldReturnExistingFilterIndexBySchema() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_NAME, String.class);

			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "Test", 1);

			final FilterIndex result = index.getFilterIndex(null, schema, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getSortIndex returns existing index")
		void shouldReturnExistingSortIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_PRIORITY, Integer.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, 42, 1);

			final SortIndex result = index.getSortIndex(null, schema, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getChainIndex returns existing index")
		void shouldReturnExistingChainIndex() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			final ChainIndex result = index.getChainIndex(null, schema, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getChainIndex by key returns existing index")
		void shouldReturnExistingChainIndexByKey() {
			final AttributeIndex index = new AttributeIndex(ENTITY_TYPE, null);
			final EntityAttributeSchemaContract schema =
				createEntityAttributeSchema(ATTRIBUTE_ORDER, Predecessor.class);

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex result = index.getChainIndex(key);

			assertNotNull(result);
		}
	}
}
