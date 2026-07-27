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
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-persistence end-to-end round-trip for the granular (PAGED) FilterIndex storage.
 *
 * A filterable attribute with more than 256 distinct values forces the backing {@link io.evitadb.index.invertedIndex.InvertedIndex}
 * root to become internal, which in turn switches the FilterIndex on-disk representation from the legacy SINGLE
 * monolithic part to the PAGED leaf-page representation. This test exercises the whole stack through the actual
 * {@link io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService}/OffsetIndex:
 *
 * 1. a warm-up bulk insert of more than 256 distinct values (flushed by `goLiveAndClose`), then
 * 2. a transactional commit adding more distinct values (driving the merge + page publish handshake), then
 * 3. a close + reopen of the whole Evita instance, asserting the reloaded FilterIndex is byte-for-result identical
 *    to the pre-restart one (every distinct value still resolves to its exact primary key) and is still PAGED.
 */
@DisplayName("Paged FilterIndex persistence round-trip")
@Tag(STORAGE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class FilterIndexPagedPersistenceTest implements EvitaTestSupport {

	private static final String ATTRIBUTE_CODE = "code";
	/**
	 * A filterable {@link IntegerNumberRange} attribute. Each entity gets one distinct, non-overlapping range, so with
	 * > 256 warm-up + transactional entities the backing {@link io.evitadb.index.range.RangeIndex} threshold tree (two
	 * thresholds per range, block size 512) becomes multi-leaf → range-PAGED.
	 */
	private static final String ATTRIBUTE_RANGE = "validity";
	/**
	 * A filterable {@link OffsetDateTime} attribute, one distinct strictly-ascending value per entity — the
	 * near-unique timestamp shape a re-indexing job writes. Every bucket therefore holds a single record, so deleting
	 * an entity drops its bucket outright and can shrink a leaf below its minimum occupancy into a merge.
	 */
	private static final String ATTRIBUTE_TIMESTAMP = "published";
	/** Base of the ascending {@link #ATTRIBUTE_TIMESTAMP} stream. */
	private static final OffsetDateTime BASE_TIMESTAMP = OffsetDateTime.of(
		2026, 7, 16, 18, 20, 0, 0, ZoneOffset.UTC
	);
	/**
	 * Distinct timestamps inserted during warm-up. With valueBlockSize=256 (split at 128) an ascending stream of 513
	 * values lays the bucket tree out as four leaves — [1..128], [129..256], [257..384], [385..513] — which is the
	 * smallest layout that can lose a leaf to a merge and still stay PAGED afterwards.
	 */
	private static final int TIMESTAMP_COUNT = 513;
	/** The primary keys deleted to force the leaf merge; their timestamps must resolve to nothing afterwards. */
	private static final Set<Integer> DELETED_TIMESTAMP_PKS = Set.of(1, 2, 129);
	/** distinct values inserted during warm-up; > 256 so the InvertedIndex root becomes internal → PAGED. */
	private static final int WARMUP_COUNT = 300;
	/** additional distinct values inserted transactionally after go-live to exercise the merge/page-publish path. */
	private static final int TRANSACTIONAL_COUNT = 50;
	private static final int TOTAL_COUNT = WARMUP_COUNT + TRANSACTIONAL_COUNT;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FilterIndexPagedPersistence");
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
	@DisplayName("Persist a >256-value filterable attribute, reopen the catalog, and read it back through the PAGED path")
	void shouldPersistAndReloadPagedFilterIndex() {
		// 1) warm-up bulk insert: schema with a filterable code attribute + > 256 distinct values, flushed on go-live
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_CODE, code(pk))
					);
				}
				session.goLiveAndClose();
			}
		);

		// 2) transactional commit (ALIVE catalog): add more distinct values; WAIT_FOR_CHANGES_VISIBLE makes the commit
		//    durable + visible before we tear the instance down, so the page-publish handshake has fully run
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk = WARMUP_COUNT + 1; pk <= TOTAL_COUNT; pk++) {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, pk)
						.setAttribute(ATTRIBUTE_CODE, code(pk))
				);
			}
		}

		// the filter index must have crossed into the PAGED representation before the restart
		assertTrue(
			isFilterIndexPaged(),
			"Pre-restart filter index should be PAGED (more than 256 distinct values were inserted)!"
		);
		assertAllCodesResolveToTheirPrimaryKey();

		// 3) close + reopen the whole Evita instance, forcing a cold load of the PAGED leaf pages from disk
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		// the reloaded index must still be PAGED and resolve every distinct value to the exact same primary key
		assertTrue(
			isFilterIndexPaged(),
			"Reloaded filter index should be PAGED after a cold load of the leaf pages!"
		);
		assertAllCodesResolveToTheirPrimaryKey();

		// 4) shrink the index hard: delete the warm-up entities so the filter index merges leaves (and collapses back to
		//    the inline SINGLE shape), driving the freed-leaf-page removal path through the real persistence drain
		//    (the freed-page reclaim path). A wrong resolved key or a throwing removal would fail this commit.
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
				session.deleteEntity(Entities.PRODUCT, pk);
			}
		}
		// the surviving transactional values must still resolve; the deleted warm-up values must be gone
		assertSurvivingCodesResolve();

		// 5) reopen once more: the now-smaller live leaf set (or collapsed SINGLE root) must reload cleanly — proving the
		//    freed pages were actually removed and nothing dangles
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
		assertSurvivingCodesResolve();
	}

	@Test
	@DisplayName("Persist a >256-range filterable attribute, reopen the catalog, and read it back through the range-PAGED path")
	void shouldPersistAndReloadPagedRangeIndex() {
		// 1) warm-up bulk insert: schema with a filterable IntegerNumberRange attribute + > 256 distinct, non-overlapping
		//    ranges → the RangeIndex threshold tree spans many leaves (two thresholds per range) → range-PAGED
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_RANGE, IntegerNumberRange.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_RANGE, rangeValue(pk))
					);
				}
				session.goLiveAndClose();
			}
		);

		// 2) transactional commit: add more distinct ranges, driving the range merge + page-publish handshake durably
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk = WARMUP_COUNT + 1; pk <= TOTAL_COUNT; pk++) {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, pk)
						.setAttribute(ATTRIBUTE_RANGE, rangeValue(pk))
				);
			}
		}

		assertTrue(isRangeIndexPaged(), "Pre-restart range index should be range-PAGED (> 512 thresholds)!");
		assertAllRangesResolveToTheirPrimaryKey();

		// 3) close + reopen: cold load of the range leaf pages from disk (range stream id resolved with StreamKind.RANGE)
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		assertTrue(isRangeIndexPaged(), "Reloaded range index should be range-PAGED after a cold load of the leaf pages!");
		assertAllRangesResolveToTheirPrimaryKey();

		// 4) shrink hard: delete the warm-up entities so the range tree merges leaves, driving the freed range-leaf-page
		//    removal path through the real persistence drain (RangeIndexLeafPageRemoval → DeferredRemovalStoragePart)
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
				session.deleteEntity(Entities.PRODUCT, pk);
			}
		}
		assertSurvivingRangesResolve();

		// 5) reopen once more: the now-smaller live range-leaf set (or collapsed inline range) must reload cleanly
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
		assertSurvivingRangesResolve();
	}

	@Test
	@DisplayName("A leaf merge in a warm-up flush must not leave the dropped leaf page listed on the persisted root")
	void shouldReloadAfterAWarmUpFlushMergedALeaf() {
		// 1) FIRST warm-up flush: closing a warming-up session flushes synchronously (Catalog.flush ->
		//    EntityCollection.createFlushFuture -> popTrappedChanges), so this lands pages 0..3 + the root on disk.
		//    Ascending timestamps fill the leaves left to right, giving the deterministic layout asserted below.
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_TIMESTAMP, OffsetDateTime.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				for (int pk = 1; pk <= TIMESTAMP_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_TIMESTAMP, timestamp(pk))
					);
				}
			}
		);
		assertTrue(isTimestampIndexPaged(), "The timestamp index must be PAGED before the merge!");
		assertEquals(
			4, timestampIndexLeafPageCount(),
			"The first warm-up flush must lay the bucket tree out as four leaf pages — the layout the deletes below " +
				"are calibrated against!"
		);

		// 2) SECOND warm-up flush: delete exactly enough distinct values to force leaf 0 to merge its right sibling.
		//    Each timestamp is unique, so its bucket holds a single record and deleting the entity drops the bucket
		//    outright — the shrink that a low-cardinality attribute practically never sees. With valueBlockSize=256 /
		//    minValueBlockSize=127 the leaves start out [1..128], [129..256], [257..384], [385..513]:
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// leaf 1: 128 -> 127, so it sits exactly at the minimum and can no longer donate a key
				session.deleteEntity(Entities.PRODUCT, 129);
				// leaf 0: 128 -> 127 (still at the minimum, no underflow yet)
				session.deleteEntity(Entities.PRODUCT, 1);
				// leaf 0: 127 -> 126 -> underflow. It has no left sibling, the right sibling cannot donate
				// (127 > 127 is false) and 127 + 126 = 253 < 256, so consolidate takes `mergeWithRight`: leaf 0
				// absorbs leaf 1 IN PLACE — it keeps page 0 and is marked dirty, while leaf 1 is detached and its
				// page 1 must be freed. No leaf is born, so nothing is allocated.
				session.deleteEntity(Entities.PRODUCT, 2);
			}
		);
		// pin that a leaf really was merged away: without this the test would still pass if the block-size constants
		// ever drifted so the deletes no longer underflow a leaf — the tree would simply stay intact, reload cleanly
		// and never exercise the defect at all
		assertEquals(
			3, timestampIndexLeafPageCount(),
			"The deletes must have merged a leaf away (four leaf pages -> three); if this still reports four, no " +
				"merge happened and the test is not exercising the freed-page path!"
		);
		// the tree must still span several leaves: had it collapsed to the inline SINGLE shape, that path force-emits
		// the root (listChanged=true) and would mask the defect under test
		assertTrue(isTimestampIndexPaged(), "The timestamp index must stay PAGED after the merge!");

		// 3) reopen: the persisted root must no longer list the page the merge dropped, and that page's record must be
		//    gone. Otherwise the cold load assembles leaf 0 (holding the absorbed keys) followed by the stale leaf 1,
		//    whose first key now sorts *before* leaf 0's last key -> the cross-page overlap check fires.
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		assertTrue(isTimestampIndexPaged(), "The reloaded timestamp index must still be PAGED!");
		assertSurvivingTimestampsResolve();
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
	 * Produces a distinct, strictly ascending timestamp for the given primary key, mirroring the near-monotonic
	 * near-unique timestamp stream a re-indexing job writes.
	 *
	 * @param pk the primary key (1-based)
	 * @return a distinct timestamp; never null
	 */
	@Nonnull
	private static OffsetDateTime timestamp(int pk) {
		return BASE_TIMESTAMP.plusSeconds(pk);
	}

	/**
	 * Reports whether the {@link io.evitadb.index.invertedIndex.InvertedIndex} backing {@link #ATTRIBUTE_TIMESTAMP}
	 * currently uses the PAGED (granular) representation.
	 *
	 * @return {@code true} when the backing bucket tree is multi-leaf (PAGED)
	 */
	private boolean isTimestampIndexPaged() {
		return timestampInvertedIndex().isPaged();
	}

	/**
	 * Returns how many leaf pages the {@link #ATTRIBUTE_TIMESTAMP} bucket tree currently occupies on disk (the set the
	 * last flush staged). A drop across a flush is the observable fingerprint of a leaf merge.
	 *
	 * @return the current on-disk leaf-page count
	 */
	private int timestampIndexLeafPageCount() {
		return timestampInvertedIndex().currentLeafPageSequences().length;
	}

	/**
	 * Resolves the {@link InvertedIndex} backing {@link #ATTRIBUTE_TIMESTAMP} in the global entity index.
	 *
	 * @return the backing inverted index; never null
	 */
	@Nonnull
	private InvertedIndex timestampInvertedIndex() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "Global entity index must exist!");
		final FilterIndex filterIndex = globalIndex.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_TIMESTAMP, null));
		assertNotNull(filterIndex, "Filter index for the timestamp attribute must exist!");
		return filterIndex.getInvertedIndex();
	}

	/**
	 * Asserts that every timestamp that was not deleted still resolves to exactly the primary key it was written with,
	 * and that the deleted ones resolve to nothing — the round-trip check across the merged leaf boundary.
	 */
	private void assertSurvivingTimestampsResolve() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= TIMESTAMP_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_TIMESTAMP, timestamp(pk)))
						)
					);
					if (DELETED_TIMESTAMP_PKS.contains(pk)) {
						assertEquals(0, matches.size(), "Deleted timestamp of pk " + pk + " must match nothing!");
					} else {
						assertEquals(1, matches.size(), "Exactly one entity should match timestamp of pk " + pk);
						assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for timestamp of pk " + pk);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Produces a distinct, lexicographically ordered attribute value for the given primary key.
	 *
	 * @param pk the primary key (1-based)
	 * @return a distinct attribute value; never null
	 */
	@Nonnull
	private static String code(int pk) {
		return String.format("code_%05d", pk);
	}

	/**
	 * Produces a distinct, non-overlapping integer range for the given primary key: `[pk*1000, pk*1000+500]`. Consecutive
	 * ranges are separated by a 500-wide gap, so the point {@link #rangePoint(int)} falls inside exactly one entity's
	 * range.
	 *
	 * @param pk the primary key (1-based)
	 * @return the distinct range; never null
	 */
	@Nonnull
	private static IntegerNumberRange rangeValue(int pk) {
		return IntegerNumberRange.between(pk * 1000, pk * 1000 + 500);
	}

	/**
	 * A probe point that falls strictly inside (only) entity `pk`'s range.
	 *
	 * @param pk the primary key (1-based)
	 * @return the probe point
	 */
	private static int rangePoint(int pk) {
		return pk * 1000 + 250;
	}

	/**
	 * Reports whether the {@link io.evitadb.index.range.RangeIndex} backing {@link #ATTRIBUTE_RANGE} currently uses the
	 * range-PAGED (granular) representation.
	 *
	 * @return {@code true} when the backing range threshold tree is multi-leaf (range-PAGED)
	 */
	private boolean isRangeIndexPaged() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "Global entity index must exist!");
		final FilterIndex filterIndex = globalIndex.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_RANGE, null));
		assertNotNull(filterIndex, "Filter index for the range attribute must exist!");
		assertNotNull(filterIndex.getRangeIndex(), "Range index companion must exist for a Range attribute!");
		return filterIndex.getRangeIndex().isPaged();
	}

	/**
	 * Asserts that every persisted range resolves, through a real `attributeInRange` query at a point inside it, to
	 * exactly the one primary key it was written with — the strong round-trip check for the range-PAGED path.
	 */
	private void assertAllRangesResolveToTheirPrimaryKey() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= TOTAL_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeInRange(ATTRIBUTE_RANGE, rangePoint(pk)))
						)
					);
					assertEquals(1, matches.size(), "Exactly one entity should match range point " + rangePoint(pk));
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for range point " + rangePoint(pk));
				}
				return null;
			}
		);
	}

	/**
	 * Asserts that after the warm-up entities were deleted, their range points match nothing while every surviving
	 * (transactional) range still resolves to its exact primary key. Run both before and after the post-shrink reopen to
	 * prove the freed range leaf pages were removed without corrupting the surviving leaf set.
	 */
	private void assertSurvivingRangesResolve() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
					assertEquals(
						0,
						session.queryListOfEntityReferences(
							query(collection(Entities.PRODUCT), filterBy(attributeInRange(ATTRIBUTE_RANGE, rangePoint(pk))))
						).size(),
						"Deleted range must not match at point: " + rangePoint(pk)
					);
				}
				for (int pk = WARMUP_COUNT + 1; pk <= TOTAL_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(collection(Entities.PRODUCT), filterBy(attributeInRange(ATTRIBUTE_RANGE, rangePoint(pk))))
					);
					assertEquals(1, matches.size(), "Exactly one entity should match surviving range point " + rangePoint(pk));
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for surviving range point " + rangePoint(pk));
				}
				return null;
			}
		);
	}

	/**
	 * Reaches into the live global entity index of the {@link Entities#PRODUCT} collection and reports whether the
	 * filter index backing {@link #ATTRIBUTE_CODE} currently uses the PAGED (granular) representation.
	 *
	 * @return {@code true} when the backing inverted-index root is internal (PAGED), {@code false} otherwise
	 */
	private boolean isFilterIndexPaged() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "Global entity index must exist!");
		final FilterIndex filterIndex = globalIndex.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_CODE, null));
		assertNotNull(filterIndex, "Filter index for the code attribute must exist!");
		return filterIndex.getInvertedIndex().isPaged();
	}

	/**
	 * Asserts that every distinct value persisted by the test resolves, through a real query, to exactly the one
	 * primary key it was written with. This is the strong round-trip check: it passes only when the full set of
	 * leaf-page buckets was correctly persisted and reconstructed.
	 */
	private void assertAllCodesResolveToTheirPrimaryKey() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= TOTAL_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_CODE, code(pk)))
						)
					);
					assertEquals(1, matches.size(), "Exactly one entity should match " + code(pk));
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for " + code(pk));
				}
				return null;
			}
		);
	}

	/**
	 * Asserts that after the warm-up entities (primary keys 1..{@link #WARMUP_COUNT}) were deleted, the deleted values no
	 * longer match anything while every surviving (transactional) value still resolves to its exact primary key. Run
	 * both before and after the post-shrink reopen to prove the freed leaf pages were removed without corrupting the
	 * surviving leaf set.
	 */
	private void assertSurvivingCodesResolve() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
					assertEquals(
						0,
						session.queryListOfEntityReferences(
							query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_CODE, code(pk))))
						).size(),
						"Deleted value must not match: " + code(pk)
					);
				}
				for (int pk = WARMUP_COUNT + 1; pk <= TOTAL_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_CODE, code(pk))))
					);
					assertEquals(1, matches.size(), "Exactly one entity should match surviving " + code(pk));
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for surviving " + code(pk));
				}
				return null;
			}
		);
	}

}
