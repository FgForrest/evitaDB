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

package io.evitadb.api.functional.storage;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.catalog.OffsetIndexStoragePartPersistenceService;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-persistence regression guard for the whole-index reclaim path.
 *
 * When a reduced (REFERENCED_ENTITY) {@link EntityIndex} that has already been flushed to disk is later emptied and
 * dropped, `EntityIndexLocalMutationExecutor` removes it from the collection's dirty-index set via
 * `DataStoreChanges.removeIndex`. The flush pipeline (`DataStoreChanges.popTrappedUpdates`) only iterates the dirty
 * set and only there does each index's `getModifiedStorageParts` run — the pull-model seam that emits the reclaim
 * diff (`*LeafPageRemoval`, the `RemovedStoragePart` for the bitmaps, and — implicitly — the index manifest). An
 * index pulled from the dirty set before that flush therefore emits no removal at all, so its `EntityIndexStoragePart`
 * manifest (and every sub-index leaf page it ever wrote) stays in the append-only OffsetIndex live set and is copied
 * forward by every future compaction — the permanent, ever-growing leak this test pins.
 *
 * The scenario deliberately spans a transaction boundary: the index is persisted by the warm-up `goLiveAndClose`
 * flush, then emptied and removed by a *later* transactional commit. An index that is only ever created and emptied
 * inside a single unflushed transaction has nothing on disk and does not leak — that benign case is not what this
 * guards.
 *
 * Compaction cannot rescue the orphan: `OffsetIndex.compact` copies the current live set verbatim
 * (`copySnapshotTo(..., roots.currentVersion())`) with no reachability sweep, so a record only ever leaves the live
 * set through an explicit removal record — exactly the record the drop path fails to emit.
 */
@DisplayName("Removed reference index reclaim")
@Tag(STORAGE)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class RemovedReferenceIndexReclaimTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_CODE = "code";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final int CATEGORY_PK = 1;
	/** Primary keys of the products that reference {@link #CATEGORY_PK}; deleting all of them empties the index. */
	private static final int[] PRODUCT_PKS = {100, 101, 102, 103};
	/** First primary key of the products used by the PAGED-shape variant, kept clear of {@link #PRODUCT_PKS}. */
	private static final int PAGED_PRODUCT_PK_BASE = 1_000;
	/**
	 * Number of distinct `code` values the PAGED-shape variant creates — comfortably above the filter-index block size,
	 * so the resulting bucket tree spans several leaf pages instead of fitting one record.
	 */
	private static final int PAGED_PRODUCT_COUNT = 1_000;
	/**
	 * Package holding every index-scoped storage part. Used as the family-blind discriminator of the orphan sweep, so
	 * that a newly-introduced index storage-part family is covered without touching this test.
	 */
	private static final String INDEX_STORAGE_PART_PACKAGE = EntityIndexStoragePart.class.getPackageName();

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RemovedReferenceIndexReclaim");
		this.evita = new Evita(configuration());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Emptying and dropping a persisted reference index must reclaim its manifest from the on-disk live set")
	void shouldReclaimStoragePartsOfARemovedReferenceIndex() {
		// 1) warm-up: schema with a filterable reference-partitioned attribute + products referencing one category,
		//    flushed to disk on go-live. This persists the reduced REFERENCED_ENTITY index (manifest + sub-index pages).
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
				for (int pk : PRODUCT_PKS) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_CODE, "code-" + pk)
							.setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
					);
				}
				session.goLiveAndClose();
			}
		);

		// capture the reduced index's primary key (== the primary key of its EntityIndexStoragePart) while it is
		// still live, and prove it was actually persisted to disk before we drop it (guards the test's own premise)
		final long indexPartPk = reducedReferenceIndexPrimaryKey();
		assertNotNull(
			fetchOnDiskManifest(indexPartPk),
			"pre-condition: the reduced reference index manifest must be on disk after the warm-up flush"
		);

		// 2) transactional commit that empties the reduced index (deletes its last referencing entities). At the end of
		//    the mutation executor the now-empty index is dropped via removeIndex — the path under test.
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk : PRODUCT_PKS) {
				session.deleteEntity(Entities.PRODUCT, pk);
			}
		}

		// the index must be gone from memory — confirms removeIndex ran (otherwise the test proves nothing)
		assertNull(
			reducedReferenceIndex(),
			"pre-condition: the emptied reduced reference index must have been removed from the collection"
		);

		// 3) close + reopen to force a cold read of the on-disk live set only (no in-memory volatile state)
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		// the dropped index's whole persisted footprint must have been reclaimed from the append-only live set: the
		// manifest, the membership bitmaps, and (asserted via the collection-wide manifest count) that only the
		// surviving GLOBAL index's manifest remains — the reduced index leaves no orphan behind.
		assertNull(
			fetchOnDiskManifest(indexPartPk),
			"a dropped reference index's manifest must be reclaimed from the on-disk live set, not orphaned"
		);
		assertNull(
			fetchOnDiskBitmaps(indexPartPk),
			"a dropped reference index's membership bitmaps must be reclaimed from the on-disk live set, not orphaned"
		);
		assertEquals(
			1, countOnDiskManifests(),
			"only the surviving GLOBAL index's manifest may remain on disk after the reduced index is dropped"
		);
		// and the family-blind invariant: nothing of the dropped index survives anywhere in the live set
		assertNoLiveRecordBelongsToIndex(indexPartPk);
	}

	@Test
	@DisplayName("Emptying one attribute's sub-index must reclaim its root while the owning index survives")
	void shouldReclaimRootOfASubIndexThatVanishedWhileTheIndexSurvives() {
		// 1) warm-up: products referencing one category, each carrying a filterable `code`. Both the GLOBAL index and
		//    the reduced reference index therefore persist a `code` FilterIndex root.
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					// nullable: this test strips the attribute back off every entity to empty its sub-index
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterable().nullable())
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
				for (int pk : PRODUCT_PKS) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_CODE, "code-" + pk)
							.setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
					);
				}
				session.goLiveAndClose();
			}
		);

		assertEquals(
			2, countOnDiskFilterRoots(),
			"pre-condition: the GLOBAL and the reduced reference index must each persist a `code` filter root"
		);

		// 2) strip the `code` attribute from every product, keeping the products and their category references. Both
		//    `code` filter sub-indexes empty out and are dropped from their family maps while the owning entity
		//    indexes survive — the churn-vanish shape. Their roots are stable-keyed, so nothing supersedes them.
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk : PRODUCT_PKS) {
				session.getEntity(Entities.PRODUCT, pk, attributeContentAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_CODE)
					.upsertVia(session);
			}
		}

		// 3) cold reload and verify the vanished roots were reclaimed while the indexes themselves live on
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		assertNotNull(
			reducedReferenceIndex(),
			"the reduced reference index must survive — only its `code` sub-index vanished"
		);
		assertEquals(
			0, countOnDiskFilterRoots(),
			"a sub-index that emptied out must have its root reclaimed, not left orphaned in the live set"
		);
	}

	@Test
	@DisplayName("Dropping a persisted reference index must reclaim the leaf pages of its PAGED sub-indexes")
	void shouldReclaimLeafPagesOfAPagedSubIndexOfARemovedReferenceIndex() {
		// 1) warm-up with enough distinct `code` values that the resulting bucket tree cannot fit a single record, so
		//    the `code` filter sub-index persists in the PAGED shape (a root plus individual leaf pages) rather than the
		//    SINGLE shape the sibling tests cover. Every product references the one category, so the reduced index owns
		//    a paged sub-index of its own.
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
				for (int i = 0; i < PAGED_PRODUCT_COUNT; i++) {
					final int pk = PAGED_PRODUCT_PK_BASE + i;
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_CODE, "code-" + pk)
							.setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
					);
				}
				session.goLiveAndClose();
			}
		);

		final long indexPartPk = reducedReferenceIndexPrimaryKey();
		// guards this test's own premise: without leaf pages on disk it would merely be the SINGLE-shape test again
		assertTrue(
			countOnDiskFilterLeafPages() > 0,
			"pre-condition: the `code` filter sub-index must have persisted in the PAGED shape"
		);

		// 2) delete every referencing product — the reduced index empties out and is dropped whole, taking its paged
		//    sub-index with it
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int i = 0; i < PAGED_PRODUCT_COUNT; i++) {
				session.deleteEntity(Entities.PRODUCT, PAGED_PRODUCT_PK_BASE + i);
			}
		}

		// 3) cold reload and verify nothing of the paged footprint survived. The GLOBAL index empties out too (every
		//    product is gone), so its `code` sub-index vanishes through the churn channel — between them no filter
		//    root and no leaf page may remain.
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		assertNull(
			fetchOnDiskManifest(indexPartPk),
			"a dropped reference index's manifest must be reclaimed even when its sub-indexes were paged"
		);
		assertEquals(
			0, countOnDiskFilterRoots(),
			"the roots of the dropped index's paged sub-indexes must be reclaimed, not left orphaned"
		);
		assertEquals(
			0, countOnDiskFilterLeafPages(),
			"every leaf page of the dropped index's paged sub-indexes must be reclaimed, not left orphaned"
		);
		// and the family-blind invariant, which also covers the paged families this test does not name
		assertNoLiveRecordBelongsToIndex(indexPartPk);
	}

	/**
	 * Builds the per-test Evita configuration wired to the (stable across restarts) test path triplet.
	 *
	 * @return the configuration; never null
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Returns the key of the reduced REFERENCED_ENTITY index partitioned by {@link #CATEGORY_PK}.
	 *
	 * @return the reduced index key; never null
	 */
	@Nonnull
	private static EntityIndexKey reducedReferenceIndexKey() {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY,
			Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, CATEGORY_PK))
		);
	}

	/**
	 * Navigates the currently open {@link Evita} to the live reduced reference index.
	 *
	 * @return the reduced index, or null when it does not exist (e.g. after it was dropped)
	 */
	private EntityIndex reducedReferenceIndex() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return collection.getIndexByKeyIfExists(reducedReferenceIndexKey());
	}

	/**
	 * Resolves the primary key of the live reduced reference index (equal to the primary key of its
	 * {@link EntityIndexStoragePart}).
	 *
	 * @return the index primary key
	 */
	private long reducedReferenceIndexPrimaryKey() {
		final EntityIndex index = reducedReferenceIndex();
		assertNotNull(index, "the reduced reference index must exist after the warm-up insert");
		return index.getPrimaryKey();
	}

	/**
	 * Reads the reduced index's {@link EntityIndexStoragePart} straight from the on-disk live set of the PRODUCT
	 * collection (no open transaction, so the read falls through the buffer to the persistence service).
	 *
	 * @param indexPartPk the storage-part primary key (== the index primary key)
	 * @return the on-disk manifest, or null when it is not present in the live set
	 */
	private EntityIndexStoragePart fetchOnDiskManifest(long indexPartPk) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return collection.getDataStoreReader().fetch(
			catalog.getVersion(), indexPartPk, EntityIndexStoragePart.class
		);
	}

	/**
	 * Reads the reduced index's membership-bitmaps part ({@link EntityIdsStoragePart}) from the PRODUCT collection's
	 * on-disk live set. Keyed, like the manifest, by the index primary key.
	 *
	 * @param indexPartPk the storage-part primary key (== the index primary key)
	 * @return the on-disk bitmaps part, or null when it is not present in the live set
	 */
	private EntityIdsStoragePart fetchOnDiskBitmaps(long indexPartPk) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return collection.getDataStoreReader().fetch(
			catalog.getVersion(), indexPartPk, EntityIdsStoragePart.class
		);
	}

	/**
	 * Counts the {@link EntityIndexStoragePart} manifests present in the PRODUCT collection's on-disk live set — one
	 * per surviving entity index. An orphaned manifest of a dropped index would inflate this count.
	 *
	 * @return the number of index manifests on disk for the PRODUCT collection
	 */
	private int countOnDiskManifests() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return collection.getDataStoreReader().countStorageParts(
			catalog.getVersion(), EntityIndexStoragePart.class
		);
	}

	/**
	 * Counts the {@link FilterIndexStoragePart} roots present in the PRODUCT collection's on-disk live set — one per
	 * filterable attribute sub-index across all surviving entity indexes. A root left behind by a sub-index that
	 * emptied out (churn-vanish) would inflate this count.
	 *
	 * @return the number of filter-index roots on disk for the PRODUCT collection
	 */
	private int countOnDiskFilterRoots() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return collection.getDataStoreReader().countStorageParts(
			catalog.getVersion(), FilterIndexStoragePart.class
		);
	}

	/**
	 * Asserts that the PRODUCT collection's live record set contains NO record belonging to the given entity index —
	 * the general orphan invariant a dropped index must satisfy.
	 *
	 * The per-type counting helpers above are whitelists: they only catch a leak in a storage-part family the test
	 * thought to name, and an index's footprint spans dozens of them. This sweep is family-blind instead: it walks the
	 * raw {@link OffsetIndex} live set and flags any record whose primary key resolves to the dropped index, so a
	 * newly-added index storage-part family is covered the day it is introduced rather than the day someone remembers
	 * to extend a test.
	 *
	 * Index-scoped parts are told apart from entity- and schema-scoped ones by their package rather than by an
	 * enumerated type list (which would reintroduce the very whitelist this replaces). That distinction is load-bearing:
	 * entity parts are keyed by ENTITY primary key, so a product whose primary key happens to equal the index primary
	 * key would otherwise be reported as an orphan.
	 *
	 * Applicable to the whole-drop case only. When merely a sub-index vanishes, the owning index survives and its
	 * primary key legitimately remains all over the live set.
	 *
	 * @param indexPrimaryKey primary key of the index that was dropped
	 */
	private void assertNoLiveRecordBelongsToIndex(long indexPrimaryKey) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final StoragePartPersistenceService<?> persistenceService = collection.getStoragePartPersistenceService();
		assertInstanceOf(
			OffsetIndexStoragePartPersistenceService.class, persistenceService,
			"the live record set can only be enumerated on an OffsetIndex-backed collection"
		);
		final OffsetIndex offsetIndex =
			((OffsetIndexStoragePartPersistenceService) persistenceService).getOffsetIndex();
		final OffsetIndexRecordTypeRegistry recordTypeRegistry = offsetIndex.getRecordTypeRegistry();

		final List<String> orphans = new ArrayList<>();
		for (final Entry<RecordKey, FileLocation> entry : offsetIndex.getEntries(catalog.getVersion())) {
			final RecordKey recordKey = entry.getKey();
			final Class<? extends StoragePart> recordType = recordTypeRegistry.typeFor(recordKey.recordType());
			if (!INDEX_STORAGE_PART_PACKAGE.equals(recordType.getPackageName())) {
				// entity- and schema-scoped parts live in a different primary-key space
				continue;
			}
			// an index-scoped part is keyed either by the bare index primary key (manifest, membership bitmaps,
			// hierarchy) or by `pack(indexPrimaryKey, subIndexId)` — both must be free of the dropped index
			final long partPrimaryKey = recordKey.primaryKey();
			if (partPrimaryKey == indexPrimaryKey || NumberUtils.unpackHigh(partPrimaryKey) == indexPrimaryKey) {
				orphans.add(recordType.getSimpleName() + "#" + partPrimaryKey);
			}
		}

		assertEquals(
			List.of(), orphans,
			"no record of the dropped index may survive in the live set - the append-only store never reclaims " +
				"an orphan, so every one of these would be copied forward by every future compaction"
		);
	}

	/**
	 * Counts the {@link FilterIndexLeafPagePart} leaf pages present in the PRODUCT collection's on-disk live set. Only a
	 * PAGED-shaped filter sub-index persists any, so a non-zero count also proves a bucket tree really did page.
	 *
	 * @return the number of filter-index leaf pages on disk for the PRODUCT collection
	 */
	private int countOnDiskFilterLeafPages() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return collection.getDataStoreReader().countStorageParts(
			catalog.getVersion(), FilterIndexLeafPagePart.class
		);
	}
}
