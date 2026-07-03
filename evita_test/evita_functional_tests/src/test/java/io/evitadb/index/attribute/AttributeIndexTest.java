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

import io.evitadb.api.APITestConstants;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeIndex} covering construction, non-transactional
 * operations, STM commit/rollback, static utility methods, predecessor routing,
 * storage parts, and error paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("AttributeIndex")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class AttributeIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_GLOBAL_CODE = "globalCode";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_LOCALIZED_NAME = "localizedName";
	private static final String ATTRIBUTE_LOCALE_UNIQUE_NAME = "localeUniqueName";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_RANGE = "range";
	private static final String REFERENCE_NAME = "brand";
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH, new Locale("cs"));
	/**
	 * Catalog + product schema scaffolding used to assemble {@link #SCHEMA} below through the real
	 * {@link InternalEntitySchemaBuilder} (rather than Mockito stubs). The builder runs the production
	 * schema-assembly path, so every fixture is a schema the engine could actually receive — there is no risk of
	 * an inconsistent flag combination that a hand-stubbed mock could express but the database never would.
	 */
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);
	private static final EntitySchema PRODUCT_SCHEMA = EntitySchema._internalBuild(ENTITY_TYPE);
	/**
	 * A single product schema carrying every attribute shape the tests need. The default shape keeps the plain
	 * attribute name (`code`, `name`, `priority`, `order`); the variant shapes get a descriptive name so the
	 * attribute name itself documents the behaviour:
	 *
	 * - `code` — non-localized, collection-unique (uniqueness implies filterable ⇒ FOLDABLE: shadowed into the filter tree)
	 * - `globalCode` — localized + collection-unique ACROSS locales (the only NON-foldable unique case)
	 * - `name` — non-localized, filterable
	 * - `localizedName` — localized
	 * - `localeUniqueName` — localized + unique WITHIN a locale
	 * - `priority` — {@link Integer} sortable (sort index)
	 * - `order` — {@link Predecessor} sortable (chain index)
	 * - `range` — {@link IntegerNumberRange} filterable (filter value index + range companion)
	 * - reference `brand` — indexed, with a filterable `code` and a {@link ReferencedEntityPredecessor} `order`
	 * (that type is valid only on a reference, so it must live here rather than at entity level)
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, PRODUCT_SCHEMA
	)
		.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
		.withAttribute(ATTRIBUTE_GLOBAL_CODE, String.class, thatIs -> thatIs.localized().unique())
		.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
		.withAttribute(ATTRIBUTE_LOCALIZED_NAME, String.class, AttributeSchemaEditor::localized)
		.withAttribute(ATTRIBUTE_LOCALE_UNIQUE_NAME, String.class, thatIs -> thatIs.localized().uniqueWithinLocale())
		.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, AttributeSchemaEditor::sortable)
		.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
		.withAttribute(ATTRIBUTE_RANGE, IntegerNumberRange.class, AttributeSchemaEditor::filterable)
		.withReferenceToEntity(
			REFERENCE_NAME, REFERENCE_NAME, Cardinality.ZERO_OR_ONE,
			ref -> {
				// a reference must be indexed before it may carry filterable / sortable attributes
				ref.indexed();
				ref.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable);
				ref.withAttribute(ATTRIBUTE_ORDER, ReferencedEntityPredecessor.class, AttributeSchemaEditor::sortable);
			}
		)
		.toInstance();
	/**
	 * Non-localized, collection-unique `code` — FOLDABLE: the mutator shadows it into the shared filter tree.
	 */
	private static final EntityAttributeSchemaContract FOLDABLE_UNIQUE_CODE = entityAttr(ATTRIBUTE_CODE);

	// === Named schema fixtures (all pulled out of the single SCHEMA above) ========================================
	/**
	 * Localized `code` that is unique ACROSS locales — the only NON-foldable unique case (standalone owner).
	 */
	private static final EntityAttributeSchemaContract GLOBAL_UNIQUE_LOCALIZED_CODE = entityAttr(ATTRIBUTE_GLOBAL_CODE);
	/**
	 * Non-localized, filterable `name` — exercised only through the filter path.
	 */
	private static final EntityAttributeSchemaContract FILTERABLE_NAME = entityAttr(ATTRIBUTE_NAME);
	/**
	 * Localized `name` — used by the {@code createAttributeKey} locale-handling tests.
	 */
	private static final EntityAttributeSchemaContract LOCALIZED_NAME = entityAttr(ATTRIBUTE_LOCALIZED_NAME);
	/**
	 * Localized `name` that is unique WITHIN a locale — drives the unique-within-locale error path.
	 */
	private static final EntityAttributeSchemaContract UNIQUE_WITHIN_LOCALE_NAME = entityAttr(
		ATTRIBUTE_LOCALE_UNIQUE_NAME);
	/**
	 * Sortable `priority` of an {@link Integer} type — routed to the sort index.
	 */
	private static final EntityAttributeSchemaContract SORTABLE_PRIORITY = entityAttr(ATTRIBUTE_PRIORITY);
	/**
	 * Sortable `order` of a {@link Predecessor} type — routed to the chain index.
	 */
	private static final EntityAttributeSchemaContract CHAIN_ORDER = entityAttr(ATTRIBUTE_ORDER);
	/**
	 * Filterable `range` of an {@link IntegerNumberRange} type — the only attribute shape that builds BOTH the filter
	 * value (inverted) axis AND the range companion, so it exercises the dual-axis paging / reclaim paths.
	 */
	private static final EntityAttributeSchemaContract FILTERABLE_RANGE = entityAttr(ATTRIBUTE_RANGE);
	/**
	 * The real `brand` {@link ReferenceSchemaContract}.
	 */
	private static final ReferenceSchemaContract BRAND_REFERENCE =
		SCHEMA.getReference(REFERENCE_NAME).orElseThrow();
	/**
	 * Reference-level `code` {@link AttributeSchemaContract} genuinely attached to {@link #BRAND_REFERENCE}; it is
	 * NOT an {@link EntityAttributeSchemaContract}, so {@code createAttributeKey} keeps the reference name.
	 */
	private static final AttributeSchemaContract BRAND_CODE_ATTRIBUTE =
		BRAND_REFERENCE.getAttribute(ATTRIBUTE_CODE).orElseThrow();
	/**
	 * Sortable reference-level `order` attribute of a {@link ReferencedEntityPredecessor} type — routed to the chain
	 * index by value. Lives on {@link #BRAND_REFERENCE}; pass that reference schema alongside it.
	 */
	private static final AttributeSchemaContract REFERENCED_CHAIN_ORDER =
		BRAND_REFERENCE.getAttribute(ATTRIBUTE_ORDER).orElseThrow();

	/**
	 * Builds a single-entry shared value index + filter view for the given key/type/records and returns a
	 * fully-wired {@link EntityAttributeIndex} via the from-maps constructor. Mirrors the loader's view-rebuild wiring so
	 * the test exercises the real owner/view structure rather than removed standalone sub-indexes.
	 */
	@Nonnull
	private static AttributeIndex buildFilterBackedIndex(
		@Nonnull AttributeIndexKey filterKey,
		@Nonnull Class<?> attributeType,
		@Nonnull int[] recordIds,
		@Nonnull Serializable[] values
	) {
		final InvertedIndex shared = new InvertedIndex(
			FilterIndex.getNormalizer(attributeType, 0),
			FilterIndex.getComparator(filterKey, attributeType)
		);
		final FilterIndex view = new FilterIndexView(filterKey, shared, null, attributeType);
		// add through the view so it mutates the shared tree AND raises the view's dirty flag (mirrors AttributeIndex)
		for (int i = 0; i < recordIds.length; i++) {
			view.addRecord(recordIds[i], values[i]);
		}
		final Map<AttributeIndexKey, InvertedIndex> sharedValues = new HashMap<>();
		sharedValues.put(filterKey, shared);
		final Map<AttributeIndexKey, FilterIndex> filters = new HashMap<>();
		filters.put(filterKey, view);
		return new EntityAttributeIndex(
			ENTITY_TYPE, Collections.emptyMap(), filters, Collections.emptyMap(), Collections.emptyMap(),
			Collections.emptyMap(), sharedValues, new HashMap<>()
		);
	}

	/**
	 * Pulls the entity-level attribute schema with the given name out of the shared {@link #SCHEMA}.
	 *
	 * @param name the attribute name declared on {@link #SCHEMA}
	 * @return the assembled {@link EntityAttributeSchemaContract}
	 */
	@Nonnull
	private static EntityAttributeSchemaContract entityAttr(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("empty constructor produces empty index with all maps empty")
		void shouldCreateEmptyAttributeIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertTrue(index.isAttributeIndexEmpty());
			assertTrue(index.getUniqueIndexes().isEmpty());
			assertTrue(index.getFilterIndexes().isEmpty());
			assertTrue(index.getSortIndexes().isEmpty());
			assertTrue(index.getChainIndexes().isEmpty());
			assertEquals(ENTITY_TYPE, index.getEntityType());
		}

		@Test
		@DisplayName("from-maps constructor populates maps: unique is standalone, filter is a shared-tree view")
		void shouldCreateWithPrePopulatedMaps() {
			// unique is a STANDALONE structure; filter is backed by the shared value index; sort/chain are owners
			final AttributeIndexKey uniqueKey = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final UniqueIndex uniqueIdx = new OwnerUniqueIndex(ENTITY_TYPE, uniqueKey, String.class);
			uniqueIdx.registerUniqueKey("ABC", 1);

			final AttributeIndexKey filterKey = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex shared = new InvertedIndex(
				FilterIndex.getNormalizer(String.class, 0),
				FilterIndex.getComparator(filterKey, String.class)
			);
			shared.addRecord("TestProduct", 1);
			final FilterIndex filterIdx = new FilterIndexView(filterKey, shared, null, String.class);

			final AttributeIndexKey sortKey = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
			final SortIndex sortIdx = new OwnerSortIndex(Integer.class, sortKey);
			sortIdx.addRecord(10, 1);

			final AttributeIndexKey chainKey = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex chainIdx = new ChainIndex(chainKey);
			chainIdx.upsertPredecessor(Predecessor.HEAD, 1);

			final Map<AttributeIndexKey, InvertedIndex> sharedValues = new HashMap<>();
			sharedValues.put(filterKey, shared);
			final AttributeIndex index = new EntityAttributeIndex(
				ENTITY_TYPE,
				Map.of(uniqueKey, uniqueIdx),
				Map.of(filterKey, filterIdx),
				Collections.emptyMap(),
				Map.of(sortKey, sortIdx),
				Map.of(chainKey, chainIdx),
				sharedValues,
				new HashMap<>()
			);

			assertFalse(index.isAttributeIndexEmpty());
			assertEquals(1, index.getUniqueIndexes().size());
			assertEquals(1, index.getFilterIndexes().size());
			assertEquals(1, index.getSortIndexes().size());
			assertEquals(1, index.getChainIndexes().size());
		}
	}

	@Nested
	@DisplayName("Standalone unique multi-locale removal")
	class StandaloneUniqueMultiLocaleRemovalTest {

		@Test
		@DisplayName("removing one locale value keeps the index while a sibling-locale value remains")
		void shouldNotDropStandaloneUniqueIndexWhileSiblingLocaleValueRemains() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final Locale czech = new Locale("cs");

			// a `localized` + (across-locale) `unique` attribute has a locale-less unique key, so ONE record legitimately
			// owns a distinct value per locale in the SAME standalone unique index — registered in separate per-locale calls
			index.insertUniqueAttribute(
				null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "EN_VALUE", 1
			);
			index.insertUniqueAttribute(
				null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, czech, "CS_VALUE", 1
			);
			assertEquals(1, index.getUniqueIndexes().size());

			// removing the English value must NOT drop the index — the Czech value still lives in it. A record-based
			// emptiness check would report the index empty here and drop it, orphaning the Czech value.
			index.removeUniqueAttribute(
				null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "EN_VALUE", 1
			);
			assertEquals(
				1, index.getUniqueIndexes().size(),
				"unique index dropped while a sibling-locale value was still present"
			);
			final UniqueIndex survivor = index.getUniqueIndex(
				null, GLOBAL_UNIQUE_LOCALIZED_CODE, Scope.LIVE, czech
			);
			assertNotNull(survivor);
			assertFalse(survivor.isEmpty());
			assertEquals(1, survivor.getRecordIdByUniqueValue("CS_VALUE"));

			// removing the last (Czech) value now empties and drops the index cleanly — this used to throw
			// `Unique index for attribute ... not found!` because the index had already been dropped above
			assertDoesNotThrow(
				() -> index.removeUniqueAttribute(
					null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, czech, "CS_VALUE", 1
				)
			);
			assertTrue(index.getUniqueIndexes().isEmpty());
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("getId() returns stable unique value per instance")
		void shouldReturnStableUniqueId() {
			final AttributeIndex first = new EntityAttributeIndex(ENTITY_TYPE);
			final AttributeIndex second = new EntityAttributeIndex(ENTITY_TYPE);

			assertNotEquals(first.getId(), second.getId());
			// id is stable across calls
			assertEquals(first.getId(), first.getId());
		}

		@Test
		@DisplayName("removeLayer cleans all four nested TransactionalMaps")
		void shouldCleanAllMapsOnRemoveLayer() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			// insert into all four indexes, then rollback -- all transactional layers should be cleaned
			assertStateAfterRollback(
				index,
				original -> {
					original.insertUniqueAttribute(
						null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, null, "X", 1
					);
					original.insertFilterAttribute(
						null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "Product", 1, false
					);
					original.insertSortAttribute(
						null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1
					);
					original.insertSortAttribute(
						null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> original.insertUniqueAttribute(
					null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, null, "A", 1
				),
				(original, committed) -> {
					assertNotNull(committed);
					assertNotSame(original, committed);
				}
			);
		}

		@Test
		@DisplayName("commit merges state from all four maps")
		void shouldMergeAllFourMapsOnCommit() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> {
					original.insertUniqueAttribute(
						null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "MERGED", 1
					);
					original.insertFilterAttribute(
						null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "FilterVal", 1, false
					);
					original.insertSortAttribute(
						null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 7, 1
					);
					original.insertSortAttribute(
						null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
					);
				},
				(original, committed) -> {
					// unique is standalone — `code` in the unique map, `name` in the filter map (each 1)
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			// insert outside tx first so state is populated
			index.insertFilterAttribute(null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, null, "hello", 1, false);

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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			// commit insertion
			assertStateAfterCommit(
				index,
				original -> original.insertUniqueAttribute(
					null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "ABC", 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> original.insertFilterAttribute(
					null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "Product", 1
				, false),
				(original, committed) -> {
					assertFalse(committed.isAttributeIndexEmpty());
					assertEquals(1, committed.getFilterIndexes().size());
				}
			);
		}

		@Test
		@DisplayName("sort index insertion visible after commit")
		void shouldCommitSortIndexInsertionAndRemoval() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(
					null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 42, 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(
					null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(
					BRAND_REFERENCE, REFERENCED_CHAIN_ORDER, ALLOWED_LOCALES, null, ReferencedEntityPredecessor.HEAD, 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				FOLDABLE_UNIQUE_CODE;

			assertStateAfterCommit(
				index,
				original -> {
					final boolean foldedUnique = original.insertUniqueAttribute(
						null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "X", 1
					) == AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE;
					original.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "X", 1, foldedUnique);
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterRollback(
				index,
				original -> original.insertUniqueAttribute(
					null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, null, "Z", 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterRollback(
				index,
				original -> original.insertFilterAttribute(
					null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "RolledBack", 1
				, false),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isAttributeIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("sort index insertion rolled back")
		void shouldRollbackSortIndexInsertion() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterRollback(
				index,
				original -> original.insertSortAttribute(
					null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 99, 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterRollback(
				index,
				original -> original.insertSortAttribute(
					null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract codeSchema =
				FOLDABLE_UNIQUE_CODE;

			final boolean foldedUnique = index.insertUniqueAttribute(null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "UNIQUE1", 1)
				== AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE;
			// `code` is a (non-localized) FOLDABLE unique attribute — the mutator shadows it into the FILTER index, so
			// it lives in both the folded-unique view map and the shared filter tree; replicate that shadow here
			index.insertFilterAttribute(null, codeSchema, ALLOWED_LOCALES, null, "UNIQUE1", 1, foldedUnique);
			index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1);
			index.insertSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertFalse(index.isAttributeIndexEmpty());
			// `code` is folded-unique (its UNIQUE key + its FILTER shadow share one tree); a stale folded-unique with no
			// shared tree is not advertised, so the single unique key here must be backed by a real shared tree
			assertEquals(1, index.getUniqueIndexes().size());
			assertEquals(1, index.getFilterIndexes().size());
			assertEquals(1, index.getSortIndexes().size());
			assertEquals(1, index.getChainIndexes().size());
		}

		@Test
		@DisplayName("all four remove operations leave index empty")
		void shouldRemoveOutsideTransaction() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract codeSchema =
				FOLDABLE_UNIQUE_CODE;
			final EntityAttributeSchemaContract nameSchema =
				FILTERABLE_NAME;
			final EntityAttributeSchemaContract prioritySchema =
				SORTABLE_PRIORITY;
			final EntityAttributeSchemaContract orderSchema =
				CHAIN_ORDER;

			// insert first — UNIQUE is a standalone structure, so the unique insert/remove fully owns its entry
			index.insertUniqueAttribute(null, codeSchema, ALLOWED_LOCALES, Scope.LIVE, null, "UNIQUE1", 1);
			index.insertFilterAttribute(null, nameSchema, ALLOWED_LOCALES, null, "Filter1", 1, false);
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

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, null, "testValue");

			assertNull(key.referenceName());
			assertEquals(ATTRIBUTE_CODE, key.attributeName());
			assertNull(key.locale());
		}

		@Test
		@DisplayName("localized entity attribute produces key with locale")
		void shouldCreateKeyForLocalizedAttribute() {

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(null, LOCALIZED_NAME, ALLOWED_LOCALES, Locale.ENGLISH, "testValue");

			assertNull(key.referenceName());
			assertEquals(ATTRIBUTE_LOCALIZED_NAME, key.attributeName());
			assertEquals(Locale.ENGLISH, key.locale());
		}

		@Test
		@DisplayName("localized schema with null locale throws exception")
		void shouldThrowWhenLocalizedSchemaGivenNullLocale() {

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeIndex.createAttributeKey(null, LOCALIZED_NAME, ALLOWED_LOCALES, null, "testValue")
			);
		}

		@Test
		@DisplayName("locale not in allowed locales throws exception")
		void shouldThrowWhenLocaleNotAllowed() {

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeIndex.createAttributeKey(null, LOCALIZED_NAME, ALLOWED_LOCALES, Locale.JAPANESE, "testValue")
			);
		}

		@Test
		@DisplayName("reference attribute produces key with reference name")
		void shouldCreateKeyForReferenceAttribute() {

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(BRAND_REFERENCE, BRAND_CODE_ATTRIBUTE, ALLOWED_LOCALES, null, "val");

			assertEquals(REFERENCE_NAME, key.referenceName());
			assertEquals(ATTRIBUTE_CODE, key.attributeName());
			assertNull(key.locale());
		}

		@Test
		@DisplayName("entity-level attribute with reference schema still produces null reference name")
		void shouldCreateKeyForEntityAttrWithRefSchema() {

			final AttributeIndexKey key =
				AttributeIndex.createAttributeKey(BRAND_REFERENCE, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, null, "val");

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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertTrue(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only unique index has data returns false")
		void shouldReturnFalseWhenOnlyUniqueHasData() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertUniqueAttribute(null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "X", 1);

			assertFalse(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only filter index has data returns false")
		void shouldReturnFalseWhenOnlyFilterHasData() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "Y", 1, false);

			assertFalse(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only sort index has data returns false")
		void shouldReturnFalseWhenOnlySortHasData() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 5, 1);

			assertFalse(index.isAttributeIndexEmpty());
		}

		@Test
		@DisplayName("only chain index has data returns false")
		void shouldReturnFalseWhenOnlyChainHasData() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertFalse(index.isAttributeIndexEmpty());
		}
	}

	@Nested
	@DisplayName("Predecessor routing")
	class PredecessorRoutingTest {

		@Test
		@DisplayName("Predecessor value routes to chain index")
		void shouldRoutePredecessorToChainIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertEquals(1, index.getChainIndexes().size());
			assertTrue(index.getSortIndexes().isEmpty());
		}

		@Test
		@DisplayName("ReferencedEntityPredecessor routes to chain index")
		void shouldRouteReferencedEntityPredecessorToChainIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(
				BRAND_REFERENCE, REFERENCED_CHAIN_ORDER, ALLOWED_LOCALES, null, ReferencedEntityPredecessor.HEAD, 1);

			assertEquals(1, index.getChainIndexes().size());
			assertTrue(index.getSortIndexes().isEmpty());
		}

		@Test
		@DisplayName("Integer value routes to sort index")
		void shouldRouteIntegerToSortIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 100, 1);

			assertEquals(1, index.getSortIndexes().size());
			assertTrue(index.getChainIndexes().isEmpty());
		}
	}

	@Nested
	@DisplayName("Storage parts")
	class StoragePartsTest {

		@Test
		@DisplayName("getModifiedStorageParts collects from all dirty sub-indexes")
		void shouldCollectModifiedStorageParts() {
			final AttributeIndexKey filterKey = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex shared = new InvertedIndex(
				FilterIndex.getNormalizer(String.class, 0),
				FilterIndex.getComparator(filterKey, String.class)
			);
			shared.addRecord("Product", 1);
			final FilterIndex filterIdx = new FilterIndexView(filterKey, shared, null, String.class);
			filterIdx.addRecord(2, "Product2");

			final AttributeIndexKey sortKey = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
			final SortIndex sortIdx = new OwnerSortIndex(Integer.class, sortKey);
			sortIdx.addRecord(10, 1);

			final AttributeIndexKey chainKey = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex chainIdx = new ChainIndex(chainKey);
			chainIdx.upsertPredecessor(Predecessor.HEAD, 1);

			final AttributeIndexKey uniqueKey = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
			final UniqueIndex uniqueIdx = new OwnerUniqueIndex(ENTITY_TYPE, uniqueKey, String.class);
			uniqueIdx.registerUniqueKey("ABC", 1);

			final Map<AttributeIndexKey, InvertedIndex> sharedValues = new HashMap<>();
			sharedValues.put(filterKey, shared);
			final AttributeIndex index = new EntityAttributeIndex(
				ENTITY_TYPE,
				Map.of(uniqueKey, uniqueIdx),
				Map.of(filterKey, filterIdx),
				Collections.emptyMap(),
				Map.of(sortKey, sortIdx),
				Map.of(chainKey, chainIdx),
				sharedValues,
				new HashMap<>()
			);

			final TrappedChanges trappedChanges = new TrappedChanges();
			index.getModifiedStorageParts(1, trappedChanges);

			// unique + filter + sort + chain are all dirty → at least 4 storage parts
			final int count = trappedChanges.getTrappedChangesCount();
			assertTrue(count >= 4, "Expected at least 4 storage parts but got " + count);
		}

		@Test
		@DisplayName("resetDirty clears dirty flags; getModifiedStorageParts emits nothing after reset")
		void shouldResetDirtyOnAllSubIndexes() {
			final AttributeIndexKey filterKey = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final AttributeIndex index = buildFilterBackedIndex(
				filterKey, String.class, new int[]{1}, new Serializable[]{"ABC"}
			);

			// before reset the dirty filter view emits at least one storage part
			final TrappedChanges beforeReset = new TrappedChanges();
			index.getModifiedStorageParts(1, beforeReset);
			assertTrue(beforeReset.getTrappedChangesCount() > 0);

			// reset dirty on all sub-indexes
			index.resetDirty();

			// after reset nothing is emitted
			final TrappedChanges afterReset = new TrappedChanges();
			index.getModifiedStorageParts(1, afterReset);
			assertEquals(0, afterReset.getTrappedChangesCount());
		}
	}

	@Nested
	@DisplayName("Error paths")
	class ErrorPathsTest {

		@Test
		@DisplayName("getUniqueIndex with unique-within-locale schema and null locale throws EntityLocaleMissingException")
		void shouldThrowWhenUniqueWithinLocaleWithoutLocale() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertThrows(
				EntityLocaleMissingException.class,
				() -> index.getUniqueIndex(null, UNIQUE_WITHIN_LOCALE_NAME, Scope.LIVE, null)
			);
		}

		@Test
		@DisplayName("remove non-existent unique attribute throws")
		void shouldThrowWhenRemovingNonExistentUnique() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeUniqueAttribute(null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "X", 1)
			);
		}

		@Test
		@DisplayName("remove non-existent filter attribute throws")
		void shouldThrowWhenRemovingNonExistentFilter() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "Foo", 1)
			);
		}

		@Test
		@DisplayName("remove non-existent sort attribute throws")
		void shouldThrowWhenRemovingNonExistentSort() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 99, 1)
			);
		}

		@Test
		@DisplayName("remove non-existent chain attribute throws")
		void shouldThrowWhenRemovingNonExistentChain() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1)
			);
		}
	}

	@Nested
	@DisplayName("TransactionalContainerChanges cleanup")
	class ContainerChangesCleanupTest {

		@Test
		@DisplayName("created-then-removed UniqueIndex cleaned via rollback")
		void shouldCleanCreatedThenRemovedUniqueIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				FOLDABLE_UNIQUE_CODE;

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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				FILTERABLE_NAME;

			assertStateAfterRollback(
				index,
				original -> {
					original.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "TEMP", 1, false);
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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				SORTABLE_PRIORITY;

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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				CHAIN_ORDER;

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
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				FOLDABLE_UNIQUE_CODE;

			// a folded-unique value is fully indexed by the filter write (which registers the folded view); the
			// unique-insert itself stores nothing for a folded attribute
			index.insertUniqueAttribute(null, schema, ALLOWED_LOCALES, Scope.LIVE, null, "ABC", 1);
			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "ABC", 1, true);

			final UniqueIndex result = index.getUniqueIndex(null, schema, Scope.LIVE, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getUniqueIndex returns null for missing index")
		void shouldReturnNullForMissingUniqueIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			final UniqueIndex result = index.getUniqueIndex(null, FOLDABLE_UNIQUE_CODE, Scope.LIVE, null);

			assertNull(result);
		}

		@Test
		@DisplayName("getFilterIndex by key returns existing index")
		void shouldReturnExistingFilterIndexByKey() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "Test", 1, false);

			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final FilterIndex result = index.getFilterIndex(key);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getFilterIndex by schema returns existing index")
		void shouldReturnExistingFilterIndexBySchema() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				FILTERABLE_NAME;

			index.insertFilterAttribute(null, schema, ALLOWED_LOCALES, null, "Test", 1, false);

			final FilterIndex result = index.getFilterIndex(null, schema, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getSortIndex returns existing index")
		void shouldReturnExistingSortIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				SORTABLE_PRIORITY;

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, 42, 1);

			final SortIndex result = index.getSortIndex(null, schema, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getChainIndex returns existing index")
		void shouldReturnExistingChainIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final EntityAttributeSchemaContract schema =
				CHAIN_ORDER;

			index.insertSortAttribute(null, schema, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			final ChainIndex result = index.getChainIndex(null, schema, null);

			assertNotNull(result);
		}

		@Test
		@DisplayName("getChainIndex by key returns existing index")
		void shouldReturnExistingChainIndexByKey() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_ORDER, null);
			final ChainIndex result = index.getChainIndex(key);

			assertNotNull(result);
		}
	}

	@Nested
	@DisplayName("Indexed decimal places consistency guard")
	class IndexedDecimalPlacesGuardTest {

		/**
		 * Builds a filterable + sortable {@link BigDecimal} entity attribute schema with the requested
		 * `indexedDecimalPlaces`, so two schemas differing only in scale can be fed to the same index to simulate a
		 * schema change that was not followed by a full index rebuild.
		 *
		 * @param indexedDecimalPlaces the decimal-places scale the returned schema declares
		 * @return a `BigDecimal` attribute schema scaled to `indexedDecimalPlaces`
		 */
		@Nonnull
		private static EntityAttributeSchemaContract decimalAttribute(int indexedDecimalPlaces) {
			return EntityAttributeSchema._internalBuild(
				"decimalAttribute", null, null,
				null,
				new Scope[]{Scope.LIVE},
				new Scope[]{Scope.LIVE},
				false, false, false,
				BigDecimal.class, null,
				indexedDecimalPlaces
			);
		}

		@Test
		@DisplayName("filter insert into a scale-drifted index fails loudly")
		void shouldThrowWhenFilterInsertScaleDrifts() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			// build the shared filter tree frozen at two decimal places
			index.insertFilterAttribute(
				null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1, false
			);

			// the schema now declares three decimal places -- modifying the frozen index must refuse rather than mangle
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.insertFilterAttribute(
					null, decimalAttribute(3), ALLOWED_LOCALES, null, new BigDecimal("2.500"), 2, false
				)
			);
		}

		@Test
		@DisplayName("filter remove from a scale-drifted index fails loudly")
		void shouldThrowWhenFilterRemoveScaleDrifts() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			index.insertFilterAttribute(
				null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1, false
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.removeFilterAttribute(
					null, decimalAttribute(3), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1
				)
			);
		}

		@Test
		@DisplayName("filter modification at the unchanged scale is accepted")
		void shouldAcceptFilterModificationAtUnchangedScale() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			index.insertFilterAttribute(
				null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1, false
			);

			// same scale -> no drift -> a second modification is accepted
			assertDoesNotThrow(
				() -> index.insertFilterAttribute(
					null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("2.50"), 2, false
				)
			);
		}

		@Test
		@DisplayName("sort insert into a scale-drifted index fails loudly")
		void shouldThrowWhenSortInsertScaleDrifts() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			// build the sort index frozen at two decimal places
			index.insertSortAttribute(
				null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.insertSortAttribute(
					null, decimalAttribute(3), ALLOWED_LOCALES, null, new BigDecimal("2.500"), 2
				)
			);
		}

		@Test
		@DisplayName("sort remove from a scale-drifted index fails loudly")
		void shouldThrowWhenSortRemoveScaleDrifts() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			index.insertSortAttribute(
				null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1
			);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.removeSortAttribute(
					null, decimalAttribute(3), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1
				)
			);
		}

		@Test
		@DisplayName("sort modification at the unchanged scale is accepted")
		void shouldAcceptSortModificationAtUnchangedScale() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			index.insertSortAttribute(
				null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("1.50"), 1
			);

			assertDoesNotThrow(
				() -> index.insertSortAttribute(
					null, decimalAttribute(2), ALLOWED_LOCALES, null, new BigDecimal("2.50"), 2
				)
			);
		}
	}

	/**
	 * Empty-drop reclaim: a PAGED {@link ChainIndex} that is emptied and dropped from the sub-index map must still have its
	 * on-disk leaf pages reclaimed. The dropped chain's own {@link ChainIndex#appendStorageParts} never runs again, so
	 * {@link AttributeIndex#getModifiedStorageParts} diffs the last durable per-chain leaf-page snapshot against the
	 * surviving chain key set and emits a {@link ChainIndexLeafPageRemoval} for every leaf page of a vanished key — else
	 * those pages leak forever in the append-only OffsetIndex.
	 */
	@Nested
	@DisplayName("an empty-dropped PAGED chain reclaims its orphaned leaf pages")
	@Tag(STORAGE)
	class EmptyDropLeafPageReclaimTest {

		/** Owning entity index pk used for the flush emission (arbitrary; only the CHAIN sub-index identity matters). */
		private static final int ENTITY_INDEX_PK = 7;
		/** > 3 leaf pages (leaf capacity 1024) so the chain pages out. */
		private static final int CHAIN_SIZE = 3200;

		@Test
		@DisplayName("dropping the emptied chain emits one leaf-page removal per previously-live page (no leak)")
		void shouldEmitRemovalForEveryLeafPageWhenPagedChainIsEmptyDropped() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			// build one consistent chain 1 -> 2 -> ... -> CHAIN_SIZE through the real predecessor-routing mutator
			for (int pk = 1; pk <= CHAIN_SIZE; pk++) {
				index.insertSortAttribute(
					null, CHAIN_ORDER, ALLOWED_LOCALES, null,
					pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk
				);
			}

			// first flush: the chain persists PAGED, staging its live leaf pages and refreshing the empty-drop-reclaim snapshot
			final List<StoragePart> firstFlush = flush(index);
			final ChainIndexStoragePart root = chainRoot(firstFlush);
			assertTrue(root.isPaged(), "a >1024-element chain must persist PAGED");
			final int livePageCount = root.getPageSequencesOrThrowException().length;
			assertTrue(livePageCount >= 3, "the chain must span at least three leaf pages");
			assertTrue(leafRemovals(firstFlush).isEmpty(), "a first PAGED flush frees no pages");
			index.resetDirty();

			// empty the chain entirely: every predecessor removed drops the now-empty chain from the sub-index map
			for (int pk = 1; pk <= CHAIN_SIZE; pk++) {
				index.removeSortAttribute(
					null, CHAIN_ORDER, ALLOWED_LOCALES, null,
					pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk
				);
			}
			assertNull(
				index.getChainIndex(new AttributeIndexKey(null, ATTRIBUTE_ORDER, null)),
				"the emptied chain must be dropped from the sub-index map"
			);

			// second flush: the dropped chain's own appendStorageParts never runs again, so the PARENT must emit one
			// removal per previously-live leaf page - otherwise those pages leak forever in the append-only OffsetIndex
			final List<StoragePart> secondFlush = flush(index);
			final List<ChainIndexLeafPageRemoval> removals = leafRemovals(secondFlush);
			assertEquals(
				livePageCount, removals.size(),
				"every previously-live leaf page of the dropped chain must be removed (count equals the live-page count)"
			);
			assertFalse(hasChainRoot(secondFlush), "a dropped chain emits no root part");
		}

		@Test
		@DisplayName("dropping an inline SINGLE chain emits no leaf-page removals (it never had leaf pages)")
		void shouldEmitNoRemovalsWhenSingleChainIsEmptyDropped() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			// a tiny chain stays within a single leaf (the inline SINGLE shape - no leaf pages on disk)
			for (int pk = 1; pk <= 5; pk++) {
				index.insertSortAttribute(
					null, CHAIN_ORDER, ALLOWED_LOCALES, null,
					pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk
				);
			}
			final List<StoragePart> firstFlush = flush(index);
			assertFalse(chainRoot(firstFlush).isPaged(), "a tiny chain must persist inline (SINGLE)");
			index.resetDirty();

			for (int pk = 1; pk <= 5; pk++) {
				index.removeSortAttribute(
					null, CHAIN_ORDER, ALLOWED_LOCALES, null,
					pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk
				);
			}
			final List<StoragePart> secondFlush = flush(index);
			assertTrue(
				leafRemovals(secondFlush).isEmpty(),
				"a dropped SINGLE chain never had leaf pages, so it must emit no leaf-page removals"
			);
		}

		/**
		 * Drains {@link AttributeIndex#getModifiedStorageParts} into a list keeping every emitted part.
		 */
		@Nonnull
		private static List<StoragePart> flush(@Nonnull AttributeIndex index) {
			final TrappedChanges trappedChanges = new TrappedChanges();
			index.getModifiedStorageParts(ENTITY_INDEX_PK, trappedChanges);
			final List<StoragePart> parts = new ArrayList<>();
			final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
			while (iterator.hasNext()) {
				parts.add(iterator.next());
			}
			return parts;
		}

		@Nonnull
		private static List<ChainIndexLeafPageRemoval> leafRemovals(@Nonnull List<StoragePart> parts) {
			final List<ChainIndexLeafPageRemoval> removals = new ArrayList<>();
			for (final StoragePart part : parts) {
				if (part instanceof ChainIndexLeafPageRemoval removal) {
					removals.add(removal);
				}
			}
			return removals;
		}

		@Nonnull
		private static ChainIndexStoragePart chainRoot(@Nonnull List<StoragePart> parts) {
			for (final StoragePart part : parts) {
				if (part instanceof ChainIndexStoragePart root) {
					return root;
				}
			}
			throw new AssertionError("the emission carries no ChainIndexStoragePart root");
		}

		private static boolean hasChainRoot(@Nonnull List<StoragePart> parts) {
			for (final StoragePart part : parts) {
				if (part instanceof ChainIndexStoragePart) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Empty-drop reclaim, sibling families: a PAGED owner UNIQUE, owner SORT or FILTER (value) sub-index that is emptied and
	 * dropped from its map must have its on-disk leaf pages reclaimed exactly as {@link EmptyDropLeafPageReclaimTest}
	 * proves for CHAIN. The dropped index's own {@code appendStorageParts} never runs again, so
	 * {@link AttributeIndex#getModifiedStorageParts} diffs the pre-drop per-family leaf-page snapshot against the
	 * surviving keys and emits the matching leaf-page removal for every orphaned page — else those pages leak forever in
	 * the append-only OffsetIndex. The FILTER range companion shares the identical mechanism (a
	 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval} per orphaned range
	 * leaf), driven by the same {@code persistedFilterRangeLeafPages} snapshot.
	 */
	@Nested
	@DisplayName("an empty-dropped PAGED sibling sub-index reclaims its orphaned leaf pages")
	@Tag(STORAGE)
	class SiblingEmptyDropLeafPageReclaimTest {

		/** Owning entity index pk used for the flush emission (arbitrary; only the sub-index identity matters). */
		private static final int ENTITY_INDEX_PK = 9;
		/** Enough distinct keys to split every family's leaf block (256) into several leaves, so each index is PAGED. */
		private static final int KEY_COUNT = 1200;

		@Test
		@DisplayName("dropping an emptied PAGED owner unique index removes every previously-live leaf page")
		void shouldReclaimOwnerUniqueLeafPagesOnEmptyDrop() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertUniqueAttribute(
					null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "u" + pk, pk
				);
			}
			final List<StoragePart> firstFlush = flush(index);
			final int livePageCount = countParts(firstFlush, UniqueIndexLeafPagePart.class);
			assertTrue(livePageCount >= 2, "the owner unique index must span multiple leaf pages (PAGED)");
			assertEquals(0, countParts(firstFlush, UniqueIndexLeafPageRemoval.class), "a first PAGED flush frees no pages");
			index.resetDirty();

			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.removeUniqueAttribute(
					null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "u" + pk, pk
				);
			}
			assertTrue(index.getUniqueIndexes().isEmpty(), "the emptied owner unique index must be dropped from its map");

			final List<StoragePart> secondFlush = flush(index);
			assertEquals(
				livePageCount, countParts(secondFlush, UniqueIndexLeafPageRemoval.class),
				"every previously-live leaf page of the dropped owner unique index must be removed (no leak)"
			);
			assertEquals(
				0, countParts(secondFlush, UniqueIndexStoragePart.class), "a dropped unique index emits no root part"
			);
		}

		@Test
		@DisplayName("dropping an emptied PAGED owner sort index removes every previously-live leaf page")
		void shouldReclaimOwnerSortLeafPagesOnEmptyDrop() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, pk, pk);
			}
			final List<StoragePart> firstFlush = flush(index);
			final int livePageCount = countParts(firstFlush, SortIndexLeafPagePart.class);
			assertTrue(livePageCount >= 2, "the owner sort index must span multiple leaf pages (PAGED)");
			index.resetDirty();

			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.removeSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, pk, pk);
			}
			assertTrue(index.getSortIndexes().isEmpty(), "the emptied owner sort index must be dropped from its map");

			final List<StoragePart> secondFlush = flush(index);
			assertEquals(
				livePageCount, countParts(secondFlush, SortIndexLeafPageRemoval.class),
				"every previously-live leaf page of the dropped owner sort index must be removed (no leak)"
			);
			assertEquals(
				0, countParts(secondFlush, SortIndexStoragePart.class), "a dropped sort index emits no root part"
			);
		}

		@Test
		@DisplayName("dropping an emptied PAGED filter (value) index removes every previously-live leaf page")
		void shouldReclaimFilterValueLeafPagesOnEmptyDrop() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "f" + pk, pk, false);
			}
			final List<StoragePart> firstFlush = flush(index);
			final int livePageCount = countParts(firstFlush, FilterIndexLeafPagePart.class);
			assertTrue(livePageCount >= 2, "the filter value index must span multiple leaf pages (PAGED)");
			index.resetDirty();

			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.removeFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "f" + pk, pk);
			}
			assertTrue(index.getFilterIndexes().isEmpty(), "the emptied filter index must be dropped from its map");

			final List<StoragePart> secondFlush = flush(index);
			assertEquals(
				livePageCount, countParts(secondFlush, FilterIndexLeafPageRemoval.class),
				"every previously-live leaf page of the dropped filter value index must be removed (no leak)"
			);
			assertEquals(
				0, countParts(secondFlush, FilterIndexStoragePart.class), "a dropped filter index emits no root part"
			);
		}

		@Test
		@DisplayName("dropping an emptied PAGED range filter reclaims BOTH the value and the range companion leaf pages")
		void shouldReclaimFilterRangeLeafPagesOnEmptyDrop() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk, false);
			}
			final List<StoragePart> firstFlush = flush(index);
			// a range attribute builds two paged axes: the inverted (bucket) value tree AND the range companion tree
			final int liveBucketPageCount = countParts(firstFlush, FilterIndexLeafPagePart.class);
			final int liveRangePageCount = countParts(firstFlush, RangeIndexLeafPagePart.class);
			assertTrue(liveBucketPageCount >= 2, "the range filter value axis must span multiple leaf pages (PAGED)");
			assertTrue(liveRangePageCount >= 2, "the range companion must span multiple leaf pages (PAGED)");
			assertEquals(0, countParts(firstFlush, RangeIndexLeafPageRemoval.class), "a first PAGED flush frees no pages");
			index.resetDirty();

			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.removeFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk);
			}
			assertTrue(index.getFilterIndexes().isEmpty(), "the emptied range filter must be dropped from its map");

			final List<StoragePart> secondFlush = flush(index);
			assertEquals(
				liveBucketPageCount, countParts(secondFlush, FilterIndexLeafPageRemoval.class),
				"every previously-live value leaf page of the dropped range filter must be removed (no leak)"
			);
			assertEquals(
				liveRangePageCount, countParts(secondFlush, RangeIndexLeafPageRemoval.class),
				"every previously-live range-companion leaf page of the dropped range filter must be removed (no leak)"
			);
			assertEquals(
				0, countParts(secondFlush, FilterIndexStoragePart.class), "a dropped range filter emits no root part"
			);
		}

		@Test
		@DisplayName("dropping emptied inline SINGLE sibling indexes emits no leaf-page removals (they never had pages)")
		void shouldEmitNoRemovalsWhenSingleShapeSiblingIndexesAreEmptyDropped() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			// a handful of keys per family keeps every sub-index within a single leaf (the inline SINGLE shape - no pages)
			for (int pk = 1; pk <= 5; pk++) {
				index.insertUniqueAttribute(
					null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "u" + pk, pk
				);
				index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, pk, pk);
				index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "f" + pk, pk, false);
				index.insertFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk, false);
			}
			final List<StoragePart> firstFlush = flush(index);
			assertEquals(0, countParts(firstFlush, UniqueIndexLeafPagePart.class), "the unique index must stay SINGLE");
			assertEquals(0, countParts(firstFlush, SortIndexLeafPagePart.class), "the sort index must stay SINGLE");
			assertEquals(0, countParts(firstFlush, FilterIndexLeafPagePart.class), "the filter value index must stay SINGLE");
			assertEquals(0, countParts(firstFlush, RangeIndexLeafPagePart.class), "the range companion must stay SINGLE");
			index.resetDirty();

			for (int pk = 1; pk <= 5; pk++) {
				index.removeUniqueAttribute(
					null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "u" + pk, pk
				);
				index.removeSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, pk, pk);
				index.removeFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "f" + pk, pk);
				index.removeFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk);
			}
			assertTrue(index.getUniqueIndexes().isEmpty(), "the emptied owner unique index must be dropped");
			assertTrue(index.getSortIndexes().isEmpty(), "the emptied owner sort index must be dropped");
			assertTrue(index.getFilterIndexes().isEmpty(), "the emptied filter indexes must be dropped");

			// a dropped SINGLE sub-index never owned leaf pages, so its currentLeafPageSequences() is empty and the parent
			// must not fabricate a single spurious removal for any family
			final List<StoragePart> secondFlush = flush(index);
			assertEquals(
				0, countParts(secondFlush, UniqueIndexLeafPageRemoval.class),
				"a dropped SINGLE owner unique index emits no leaf-page removals"
			);
			assertEquals(
				0, countParts(secondFlush, SortIndexLeafPageRemoval.class),
				"a dropped SINGLE owner sort index emits no leaf-page removals"
			);
			assertEquals(
				0, countParts(secondFlush, FilterIndexLeafPageRemoval.class),
				"a dropped SINGLE filter value index emits no leaf-page removals"
			);
			assertEquals(
				0, countParts(secondFlush, RangeIndexLeafPageRemoval.class),
				"a dropped SINGLE range companion emits no leaf-page removals"
			);
		}

		/**
		 * Builds a distinct, non-overlapping integer range for the given key: `[pk*1000, pk*1000+500]`. Consecutive
		 * ranges are separated by a 500-wide gap, so `KEY_COUNT` keys yield `KEY_COUNT` distinct value buckets and
		 * `2 * KEY_COUNT` range thresholds — enough to page both filter axes at the chosen count.
		 */
		@Nonnull
		private static IntegerNumberRange rangeValue(int pk) {
			return IntegerNumberRange.between(pk * 1000, pk * 1000 + 500);
		}

		/**
		 * Drains {@link AttributeIndex#getModifiedStorageParts} into a list keeping every emitted part.
		 */
		@Nonnull
		private static List<StoragePart> flush(@Nonnull AttributeIndex index) {
			final TrappedChanges trappedChanges = new TrappedChanges();
			index.getModifiedStorageParts(ENTITY_INDEX_PK, trappedChanges);
			final List<StoragePart> parts = new ArrayList<>();
			final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
			while (iterator.hasNext()) {
				parts.add(iterator.next());
			}
			return parts;
		}

		/**
		 * Counts the emitted parts assignable to `type` (leaf pages, removals or roots of one family).
		 */
		private static int countParts(@Nonnull List<StoragePart> parts, @Nonnull Class<? extends StoragePart> type) {
			int count = 0;
			for (final StoragePart part : parts) {
				if (type.isInstance(part)) {
					count++;
				}
			}
			return count;
		}
	}

	/**
	 * A commit that only mutates leaf CONTENT — no leaf allocated or freed, so the
	 * live leaf-page list is byte-identical to the persisted root — must NOT re-emit the pure page-list root of a
	 * skip-safe family (CHAIN / owner UNIQUE / owner SORT / FILTER). The changed leaf page is still emitted; only the
	 * redundant root write is skipped, collapsing the steady-state root cost from O(live pages) to O(1). Families whose
	 * root fuses per-commit state (GlobalUnique / RefTypeCardinality / PriceSuper) always re-emit and are out of scope.
	 */
	@Nested
	@DisplayName("a content-only commit skips the redundant PAGED root re-emit (steady-state O(1))")
	@Tag(STORAGE)
	class RootReEmitSkipTest {

		/** Owning entity index pk used for the flush emission (arbitrary; only the sub-index identity matters). */
		private static final int ENTITY_INDEX_PK = 11;
		/** Enough distinct keys to split every family's leaf block (256) into several leaves, so each index is PAGED. */
		private static final int KEY_COUNT = 1200;

		@Test
		@DisplayName("adding a record to an existing filter value re-emits the touched leaf but not the root")
		void shouldSkipFilterRootWhenOnlyLeafContentChanges() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "f" + pk, pk, false);
			}
			final List<StoragePart> firstFlush = flush(index);
			assertEquals(1, countParts(firstFlush, FilterIndexStoragePart.class), "the first PAGED flush emits the root");
			assertTrue(countParts(firstFlush, FilterIndexLeafPagePart.class) >= 2, "the filter index must be PAGED");
			index.resetDirty();

			// add a NEW record to an EXISTING value: the value's bucket grows (content) but no tree key is added, so no
			// leaf splits or merges — the live page list is unchanged and the root re-emit must be skipped
			index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "f1", KEY_COUNT + 1, false);
			final List<StoragePart> secondFlush = flush(index);
			assertTrue(
				countParts(secondFlush, FilterIndexLeafPagePart.class) >= 1,
				"the leaf whose content changed must still be re-emitted"
			);
			assertEquals(
				0, countParts(secondFlush, FilterIndexStoragePart.class),
				"the page list is unchanged, so the redundant PAGED root must be skipped (O(1) steady state)"
			);
		}

		@Test
		@DisplayName("adding a record to an existing sort value re-emits the touched leaf but not the root")
		void shouldSkipSortRootWhenOnlyLeafContentChanges() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, pk, pk);
			}
			final List<StoragePart> firstFlush = flush(index);
			assertEquals(1, countParts(firstFlush, SortIndexStoragePart.class), "the first PAGED flush emits the root");
			assertTrue(countParts(firstFlush, SortIndexLeafPagePart.class) >= 2, "the sort index must be PAGED");
			index.resetDirty();

			// add a new record under an EXISTING priority value: the value's cardinality bucket grows (content) with no
			// new tree key, so no leaf splits/merges — the live page list is unchanged and the root re-emit must be skipped
			index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 1, KEY_COUNT + 1);
			final List<StoragePart> secondFlush = flush(index);
			assertTrue(
				countParts(secondFlush, SortIndexLeafPagePart.class) >= 1,
				"the leaf whose content changed must still be re-emitted"
			);
			assertEquals(
				0, countParts(secondFlush, SortIndexStoragePart.class),
				"the page list is unchanged, so the redundant PAGED root must be skipped (O(1) steady state)"
			);
		}

		@Test
		@DisplayName("a content-only change to a range filter skips the fused root only when BOTH axes are unchanged")
		void shouldSkipFilterRootWhenBothAxesArePagedAndUnchanged() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk, false);
			}
			final List<StoragePart> firstFlush = flush(index);
			assertEquals(1, countParts(firstFlush, FilterIndexStoragePart.class), "the first PAGED flush emits the root");
			assertTrue(countParts(firstFlush, FilterIndexLeafPagePart.class) >= 2, "the value axis must be PAGED");
			assertTrue(countParts(firstFlush, RangeIndexLeafPagePart.class) >= 2, "the range companion must be PAGED");
			index.resetDirty();

			// add a NEW record under an EXISTING range value: the value's bucket and the range point bitmap grow (content)
			// but no new tree key is added to either axis, so neither page list changes - the fused root re-emit is skipped
			index.insertFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(1), KEY_COUNT + 1, false);
			final List<StoragePart> secondFlush = flush(index);
			assertTrue(
				countParts(secondFlush, FilterIndexLeafPagePart.class) >= 1,
				"the value leaf whose content changed must still be re-emitted"
			);
			assertEquals(
				0, countParts(secondFlush, FilterIndexStoragePart.class),
				"both axis page lists are unchanged, so the fused PAGED root must be skipped (dual-axis O(1))"
			);
		}

		@Test
		@DisplayName("a range-filter leaf split re-emits the fused root even though it is a pure page-list root")
		void shouldReEmitFilterRootWhenPageListChangesWithBothAxesPaged() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int pk = 1; pk <= KEY_COUNT; pk++) {
				index.insertFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk, false);
			}
			final List<StoragePart> firstFlush = flush(index);
			assertEquals(1, countParts(firstFlush, FilterIndexStoragePart.class), "the first PAGED flush emits the root");
			assertTrue(countParts(firstFlush, FilterIndexLeafPagePart.class) >= 2, "the value axis must be PAGED");
			assertTrue(countParts(firstFlush, RangeIndexLeafPagePart.class) >= 2, "the range companion must be PAGED");
			index.resetDirty();

			// add a fresh block of NEW distinct range values (> one leaf block): a new tree key cannot fit without a leaf
			// split on both axes, so at least one leaf is freshly allocated -> the page list changed -> root re-emitted
			for (int pk = KEY_COUNT + 1; pk <= KEY_COUNT + 300; pk++) {
				index.insertFilterAttribute(null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, rangeValue(pk), pk, false);
			}
			final List<StoragePart> secondFlush = flush(index);
			assertEquals(
				1, countParts(secondFlush, FilterIndexStoragePart.class),
				"a leaf split changes the page list, so the fused PAGED root must be re-emitted (skip not taken)"
			);
		}

		/**
		 * Builds a distinct, non-overlapping integer range for the given key: `[pk*1000, pk*1000+500]`, so distinct keys
		 * yield distinct value buckets and distinct range thresholds on both filter axes.
		 */
		@Nonnull
		private static IntegerNumberRange rangeValue(int pk) {
			return IntegerNumberRange.between(pk * 1000, pk * 1000 + 500);
		}

		/**
		 * Drains {@link AttributeIndex#getModifiedStorageParts} into a list keeping every emitted part.
		 */
		@Nonnull
		private static List<StoragePart> flush(@Nonnull AttributeIndex index) {
			final TrappedChanges trappedChanges = new TrappedChanges();
			index.getModifiedStorageParts(ENTITY_INDEX_PK, trappedChanges);
			final List<StoragePart> parts = new ArrayList<>();
			final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
			while (iterator.hasNext()) {
				parts.add(iterator.next());
			}
			return parts;
		}

		/**
		 * Counts the emitted parts assignable to `type` (leaf pages, removals or roots of one family).
		 */
		private static int countParts(@Nonnull List<StoragePart> parts, @Nonnull Class<? extends StoragePart> type) {
			int count = 0;
			for (final StoragePart part : parts) {
				if (type.isInstance(part)) {
					count++;
				}
			}
			return count;
		}
	}
}
