/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a paged filter index repaired while it is loaded keeps every write made after the load. Two legacy
 * shapes are repaired on load: a catalog written before temporal keys were truncated to whole milliseconds holds two
 * buckets that now collapse onto one key, and a catalog written while `DateTimeRange` compared at whole seconds holds
 * buckets in an order the current comparison reverses. Either repair releases the identity of the pages it changed and
 * flags the index dirty, and the first flush that writes the index must write those pages and a root listing them -
 * whatever made the owning entity index flush, and however the tree changed in the meantime.
 *
 * The legacy pages are written straight into the storage of the collection and published by the next flush of the
 * catalog, so the catalog loaded afterwards is a genuinely legacy one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A filter index repaired on load keeps the writes made after the load")
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(STORAGE)
class FilterIndexLoadRepairPersistenceTest implements EvitaTestSupport {
	private static final String MOMENT = "moment";
	private static final String LABEL = "label";
	private static final String VALIDITY = "validity";
	/** Pairs of owners share one moment, so the filter index holds this many owners in half as many buckets. */
	private static final int OWNER_COUNT = 600;
	/** The owner written after the legacy catalog was loaded. */
	private static final int LATE_OWNER = 1_000;
	/** The first primary key of the owners written after the load to split the first leaf. */
	private static final int SPLITTING_OWNER_BASE = 2_000;
	/** How many owners, each with its own new moment, are written in front of the first leaf to split it. */
	private static final int SPLITTING_OWNER_COUNT = 200;
	private static final Instant BASE = Instant.parse("2024-01-01T09:00:00Z");
	/** How many legacy buckets each legacy page holds. */
	private static final int LEGACY_PAGE_SIZE = 150;
	/** A page large enough to list every product of the collection at once. */
	private static final int ALL_PRODUCTS = 10_000;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FilterIndexLoadRepairPersistence");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * The moment the owner carries: owners `2k - 1` and `2k` share the moment `BASE + k` milliseconds.
	 *
	 * @param owner the owner primary key
	 * @return the moment
	 */
	@Nonnull
	private static OffsetDateTime momentOf(int owner) {
		return BASE.plusMillis((owner + 1) / 2).atOffset(ZoneOffset.UTC);
	}

	/**
	 * The validity the owner carries. Owners `2k + 1` and `2k + 2` open in the same whole second `BASE + k`, the odd
	 * one half a second into it and the even one a fifth of a second into it, and every owner closes a second after
	 * the previous one. Compared at whole seconds - lower bound, then upper bound - the owners are ordered by their
	 * primary key; compared at milliseconds every pair is ordered the other way round.
	 *
	 * @param owner the owner primary key, `1` to {@link #OWNER_COUNT}
	 * @return the validity
	 */
	@Nonnull
	private static DateTimeRange validityOf(int owner) {
		final int index = owner - 1;
		final Instant from = BASE.plusSeconds(index / 2).plusMillis(index % 2 == 0 ? 500 : 200);
		final Instant to = BASE.plusSeconds(1_000_000L + index);
		return DateTimeRange.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC));
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("keeps a write made after a load that collapsed sub-millisecond legacy buckets")
	void shouldKeepWriteMadeAfterLoadThatCollapsedLegacyBuckets(@Nonnull CatalogState state) {
		final int[] legacyPages = loadLegacyMomentCatalog(state, false);

		// a write after the load - it lands in the first page, which the repair changed
		writeLateOwner(MOMENT, momentOf(1));
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "before the restart");

		restart();
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "the late owner must survive the restart");
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(MOMENT).getLeafPageSequences()),
			"the flush after the load must have replaced the legacy page list"
		);
		assertEveryListedPageIsStored(MOMENT);
	}

	@Test
	@DisplayName("keeps a write made after a commit that wrote only a sibling attribute of the same entity index")
	void shouldKeepWriteMadeAfterSiblingAttributeWasCommittedFirst() {
		final int[] legacyPages = loadLegacyMomentCatalog(CatalogState.ALIVE, true);

		// the first commit after the load touches a different attribute of the same entity index, so the entity index
		// is flushed while the repaired filter index itself takes no write at all
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 3, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(LABEL, "renamed")
					.upsertVia(session);
			}
		);
		assertEquals(List.of(3), ownersWith(attributeEquals(LABEL, "renamed")), "the sibling write must be visible");

		// only then the repaired attribute is written - the merge carried its tree across the previous commit
		writeLateOwner(MOMENT, momentOf(1));
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "before the restart");

		restart();
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "the late owner must survive the restart");
		assertEquals(List.of(3), ownersWith(attributeEquals(LABEL, "renamed")), "the sibling must survive too");
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(MOMENT).getLeafPageSequences()),
			"a flush after the load must have replaced the legacy page list"
		);
		assertEveryListedPageIsStored(MOMENT);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("opens again after a write that split a leaf of the repaired index")
	void shouldReopenAfterWriteSplitLeafOfRepairedIndex(@Nonnull CatalogState state) {
		loadLegacyMomentCatalog(state, false);

		// every splitting owner takes a moment of its own in front of the first one, so all of them land in the
		// first leaf and grow it past the capacity of a leaf - the split changes the page list, so the root is
		// written again and must list only pages that were written too
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (int i = 1; i <= SPLITTING_OWNER_COUNT; i++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, SPLITTING_OWNER_BASE + i)
							.setAttribute(MOMENT, BASE.minusMillis(i).atOffset(ZoneOffset.UTC))
					);
				}
			}
		);

		restart();
		assertEveryListedPageIsStored(MOMENT);
		assertEquals(List.of(1, 2), ownersAt(momentOf(1)), "a repaired bucket must survive the restart");
		assertEquals(
			List.of(OWNER_COUNT - 1, OWNER_COUNT), ownersAt(momentOf(OWNER_COUNT)),
			"a bucket of the last, untouched leaf must survive the restart"
		);
		assertEquals(
			List.of(SPLITTING_OWNER_BASE + 1), ownersAt(BASE.minusMillis(1).atOffset(ZoneOffset.UTC)),
			"a splitting owner must survive the restart"
		);
		assertEquals(
			OWNER_COUNT + SPLITTING_OWNER_COUNT, allProducts().size(), "every product must survive the restart"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("keeps a write made after a load that re-sorted second-granularity ranges paged on both axes")
	void shouldKeepWriteMadeAfterLoadThatResortedSecondGranularityRanges(@Nonnull CatalogState state) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(VALIDITY, DateTimeRange.class, thatIs -> thatIs.filterable())
					.updateVia(session);
				for (int owner = 1; owner <= OWNER_COUNT; owner++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, owner).setAttribute(VALIDITY, validityOf(owner))
					);
				}
			}
		);
		restart();
		final FilterIndexStoragePart currentRoot = onDiskRoot(VALIDITY);
		assertTrue(currentRoot.isRangePaged(), "the range axis must span several leaf pages");
		// the legacy buckets are ordered as a second-granularity release ordered them - by the primary key
		final List<ValueToRecord> legacyBuckets = new ArrayList<>(OWNER_COUNT);
		for (int owner = 1; owner <= OWNER_COUNT; owner++) {
			legacyBuckets.add(new ValueToRecordBitmap(validityOf(owner), owner));
		}
		final int[] legacyPages = writeLegacyFilterIndex(VALIDITY, legacyBuckets);
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		restart();
		assertEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(VALIDITY).getLeafPageSequences()),
			"the catalog must hold the legacy pages"
		);
		assertEquals(
			List.of(1), ownersWith(attributeEquals(VALIDITY, validityOf(1))),
			"the repaired index must answer from the legacy pages"
		);

		// a write after the load - it joins the bucket of the first owner, whose page the re-sort gave up
		writeLateOwner(VALIDITY, validityOf(1));
		final OffsetDateTime moment = BASE.plusSeconds(100).plusMillis(300).atOffset(ZoneOffset.UTC);
		final List<Integer> expectedInRange = ownersValidAt(moment);
		assertEquals(
			List.of(1, LATE_OWNER), ownersWith(attributeEquals(VALIDITY, validityOf(1))), "before the restart"
		);
		assertEquals(expectedInRange, ownersWith(attributeInRange(VALIDITY, moment)), "before the restart");

		restart();
		assertEveryListedPageIsStored(VALIDITY);
		assertEquals(
			List.of(1, LATE_OWNER), ownersWith(attributeEquals(VALIDITY, validityOf(1))),
			"the late owner must survive the restart"
		);
		assertEquals(
			expectedInRange, ownersWith(attributeInRange(VALIDITY, moment)),
			"the range axis must answer the same after the restart"
		);
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(VALIDITY).getLeafPageSequences()),
			"the flush after the load must have replaced the legacy page list"
		);
	}

	/**
	 * Builds a catalog of {@link #OWNER_COUNT} products carrying a filterable moment (and, when asked, a filterable
	 * label of their own), replaces the persisted filter index of the moment with legacy pages that collapse on load,
	 * and loads the catalog again in the requested state.
	 *
	 * @param state     the state the legacy catalog is loaded in
	 * @param withLabel whether the products carry a second filterable attribute in the same entity index
	 * @return the legacy page list the root lists
	 */
	@Nonnull
	private int[] loadLegacyMomentCatalog(@Nonnull CatalogState state, boolean withLabel) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntitySchemaBuilder schema = session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(MOMENT, OffsetDateTime.class, thatIs -> thatIs.filterable());
				if (withLabel) {
					schema.withAttribute(LABEL, String.class, thatIs -> thatIs.filterable().nullable());
				}
				schema.updateVia(session);
				for (int owner = 1; owner <= OWNER_COUNT; owner++) {
					session.upsertEntity(
						withLabel
							? session.createNewEntity(Entities.PRODUCT, owner)
								.setAttribute(MOMENT, momentOf(owner))
								.setAttribute(LABEL, "label" + owner)
							: session.createNewEntity(Entities.PRODUCT, owner).setAttribute(MOMENT, momentOf(owner))
					);
				}
			}
		);
		restart();
		// every shared moment is split into two buckets, the second one written half a millisecond later, which the
		// current engine collapses back onto one key when it loads them
		final List<ValueToRecord> legacyBuckets = new ArrayList<>(OWNER_COUNT);
		for (int owner = 1; owner <= OWNER_COUNT; owner += 2) {
			final Instant moment = momentOf(owner).toInstant();
			legacyBuckets.add(new ValueToRecordBitmap(moment, owner));
			legacyBuckets.add(new ValueToRecordBitmap(moment.plusNanos(500_000), owner + 1));
		}
		final int[] legacyPages = writeLegacyFilterIndex(MOMENT, legacyBuckets);
		if (state == CatalogState.ALIVE) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		restart();
		assertEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(MOMENT).getLeafPageSequences()),
			"the catalog must hold the legacy pages"
		);
		assertEquals(List.of(1, 2), ownersAt(momentOf(1)), "the repaired index must answer from the legacy pages");
		return legacyPages;
	}

	/**
	 * Replaces the persisted bucket axis of the filter index of the attribute with the passed legacy buckets, chunked
	 * into pages of {@link #LEGACY_PAGE_SIZE} buckets. The range axis, when there is one, is left as persisted.
	 *
	 * @param attributeName the attribute whose filter index is replaced
	 * @param legacyBuckets the legacy buckets in the order the legacy pages list them
	 * @return the legacy page list the root lists
	 */
	@Nonnull
	private int[] writeLegacyFilterIndex(@Nonnull String attributeName, @Nonnull List<ValueToRecord> legacyBuckets) {
		final EntityCollection collection = productCollection();
		final EntityIndex globalIndex = globalIndex();
		final FilterIndexStoragePart currentRoot = onDiskRoot(attributeName);
		assertTrue(currentRoot.isPaged(), "the filter index must span several leaf pages");
		assertEquals(
			ValueIdAllocator.UNASSIGNED_VALUE_ID, currentRoot.getNextValueId(),
			"the tree is expected to carry no value ids, as a legacy one does not"
		);
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(attributeKey(attributeName), AttributeIndexType.FILTER);
		final int firstSequence = currentRoot.getHighWaterPageSequence() + 1;
		final int pageCount = legacyBuckets.size() / LEGACY_PAGE_SIZE;
		final int[] pages = new int[pageCount];
		final long catalogVersion = catalog().getVersion();
		for (int page = 0; page < pageCount; page++) {
			pages[page] = firstSequence + page;
			collection.getStoragePartPersistenceService().putStoragePart(
				catalogVersion,
				new FilterIndexLeafPagePart(
					globalIndex.getPrimaryKey(), streamKey, pages[page],
					legacyBuckets.subList(page * LEGACY_PAGE_SIZE, (page + 1) * LEGACY_PAGE_SIZE)
						.toArray(ValueToRecord[]::new),
					null
				)
			);
		}
		collection.getStoragePartPersistenceService().putStoragePart(
			catalogVersion,
			new FilterIndexStoragePart(
				globalIndex.getPrimaryKey(), attributeKey(attributeName), currentRoot.getAttributeType(),
				new ValueToRecordBitmap[0], currentRoot.getRangeIndex(), currentRoot.getIndexedDecimalPlaces(),
				true, pages[pageCount - 1], pages,
				currentRoot.isRangePaged(), currentRoot.getRangeHighWaterPageSequence(),
				currentRoot.getRangeLeafPageSequences(), null
			)
		);
		return pages;
	}

	/**
	 * Writes the {@link #LATE_OWNER} carrying the passed value of the attribute.
	 *
	 * @param attributeName the attribute
	 * @param value         the value
	 */
	private void writeLateOwner(@Nonnull String attributeName, @Nonnull Serializable value) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, LATE_OWNER).setAttribute(attributeName, value)
				);
			}
		);
	}

	/**
	 * Asserts that every leaf page the persisted root of the filter index of the attribute lists - on both axes - is a
	 * record the storage actually holds: a root listing a page that was never written stops the catalog from loading.
	 *
	 * @param attributeName the attribute whose filter index is checked
	 */
	private void assertEveryListedPageIsStored(@Nonnull String attributeName) {
		final FilterIndexStoragePart root = onDiskRoot(attributeName);
		final EntityCollection collection = productCollection();
		final long catalogVersion = catalog().getVersion();
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(attributeKey(attributeName), AttributeIndexType.FILTER);
		final int globalIndexPrimaryKey = globalIndex().getPrimaryKey();
		if (root.isPaged()) {
			final int streamId = collection.getStoragePartPersistenceService().getReadOnlyKeyCompressor()
				.getId(new LeafStreamKey(globalIndexPrimaryKey, streamKey));
			for (final int pageSequence : root.getLeafPageSequences()) {
				assertNotNull(
					collection.getDataStoreReader().fetch(
						catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
						FilterIndexLeafPagePart.class
					),
					"the root lists the bucket page " + pageSequence + ", which the storage does not hold"
				);
			}
		}
		if (root.isRangePaged()) {
			final int rangeStreamId = collection.getStoragePartPersistenceService().getReadOnlyKeyCompressor()
				.getId(new LeafStreamKey(globalIndexPrimaryKey, streamKey, StreamKind.RANGE));
			for (final int pageSequence : root.getRangeLeafPageSequences()) {
				assertNotNull(
					collection.getDataStoreReader().fetch(
						catalogVersion, AbstractLeafPagePart.computeUniquePartId(rangeStreamId, pageSequence),
						RangeIndexLeafPagePart.class
					),
					"the root lists the range page " + pageSequence + ", which the storage does not hold"
				);
			}
		}
	}

	/**
	 * Closes the engine and opens it again over the same directory, waiting until the catalog is loaded.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Returns the products whose moment equals the passed one, by primary key.
	 *
	 * @param moment the moment
	 * @return the primary keys
	 */
	@Nonnull
	private List<Integer> ownersAt(@Nonnull OffsetDateTime moment) {
		return ownersWith(attributeEquals(MOMENT, moment));
	}

	/**
	 * Returns the owners - including the {@link #LATE_OWNER}, who shares the validity of the first one - whose
	 * validity contains the passed moment, computed from the validities themselves.
	 *
	 * @param moment the moment
	 * @return the primary keys in ascending order
	 */
	@Nonnull
	private static List<Integer> ownersValidAt(@Nonnull OffsetDateTime moment) {
		final List<Integer> owners = new ArrayList<>(OWNER_COUNT + 1);
		for (int owner = 1; owner <= OWNER_COUNT; owner++) {
			if (validityOf(owner).isValidFor(moment)) {
				owners.add(owner);
			}
		}
		if (validityOf(1).isValidFor(moment)) {
			owners.add(LATE_OWNER);
		}
		return owners;
	}

	/**
	 * Returns every product of the collection, by primary key.
	 *
	 * @return the primary keys
	 */
	@Nonnull
	private List<Integer> allProducts() {
		return ownersWith(null);
	}

	/**
	 * Returns the products matching the passed filter, by primary key.
	 *
	 * @param filter the filter, or `null` for every product
	 * @return the primary keys in ascending order
	 */
	@Nonnull
	private List<Integer> ownersWith(@Nullable FilterConstraint filter) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys = session -> session
			.queryListOfEntityReferences(
				query(
					collection(Entities.PRODUCT),
					filter == null ? null : filterBy(filter),
					(OrderBy) null,
					require(page(1, ALL_PRODUCTS))
				)
			)
			.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.sorted()
			.toList();
		return this.evita.queryCatalog(TEST_CATALOG, primaryKeys);
	}

	/**
	 * Reads the persisted root of the filter index of the attribute.
	 *
	 * @param attributeName the attribute
	 * @return the root part
	 */
	@Nonnull
	private FilterIndexStoragePart onDiskRoot(@Nonnull String attributeName) {
		final EntityCollection collection = productCollection();
		final long rootPrimaryKey = AttributeIndexStoragePart.computeUniquePartId(
			globalIndex().getPrimaryKey(), AttributeIndexType.FILTER, attributeKey(attributeName),
			collection.getStoragePartPersistenceService().getReadOnlyKeyCompressor()
		);
		return Objects.requireNonNull(
			collection.getDataStoreReader().fetch(catalog().getVersion(), rootPrimaryKey, FilterIndexStoragePart.class)
		);
	}

	@Nonnull
	private static AttributeIndexKey attributeKey(@Nonnull String attributeName) {
		return new AttributeIndexKey(null, attributeName, null);
	}

	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
	}

	@Nonnull
	private EntityCollection productCollection() {
		return (EntityCollection) catalog().getCollectionForEntity(Entities.PRODUCT).orElseThrow();
	}

	@Nonnull
	private EntityIndex globalIndex() {
		return Objects.requireNonNull(
			productCollection().getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE))
		);
	}
}
