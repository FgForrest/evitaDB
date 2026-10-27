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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.store.catalog.OfflineCatalogImages.StoredFile;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getEntityCollectionDataStoreFileName;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real protocol 6 → 7 migration and restart coverage for sub-millisecond collisions and legacy range ordering.
 * The rewritten trees must survive sibling commits, leaf splits, later writes and a second engine restart.
 */
@DisplayName("Migrated filter trees survive writes and another restart")
@Tag(ATTRIBUTE)
@Tag(FILTER)
@Tag(STORAGE)
class Migration_2026_3_FilterRewritePersistenceTest implements EvitaTestSupport {
	/** The temporal attribute whose filter buckets exercise migration. */
	private static final String MOMENT = "moment";
	/** A sibling attribute used to trigger later writes. */
	private static final String LABEL = "label";
	/** The range attribute used to verify migrated ordering and thresholds. */
	private static final String VALIDITY = "validity";
	/** Pairs of owners share one moment, so the filter index holds this many owners in half as many buckets. */
	private static final int OWNER_COUNT = 600;
	/** The owner written after the legacy catalog was loaded. */
	private static final int LATE_OWNER = 1_000;
	/** The first primary key of the owners written after the load to split the first leaf. */
	private static final int SPLITTING_OWNER_BASE = 2_000;
	/** How many owners, each with its own new moment, are written in front of the first leaf to split it. */
	private static final int SPLITTING_OWNER_COUNT = 200;
	/** The starting instant shared by the temporal fixtures. */
	private static final Instant BASE = Instant.parse("2024-01-01T09:00:00Z");
	/** How many legacy buckets each legacy page holds. */
	private static final int LEGACY_PAGE_SIZE = 150;
	/** A page large enough to list every product of the collection at once. */
	private static final int ALL_PRODUCTS = 10_000;

	private TestPaths paths;
	private Evita evita;
	private boolean migrateOnRestart;

	/** Creates an isolated engine for each migration fixture. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("Migration_2026_3_FilterRewritePersistence");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/** Closes the engine before retiring the fixture directory. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** A canonical re-sort must reveal owners whose equal range keys were separated in released order. */
	@ParameterizedTest(name = "paged={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("refuses folded unique range collisions revealed by canonical re-sorting before publication")
	void shouldRefuseFoldedUniqueRangeCollisionAfterResorting(boolean paged) {
		writeSeparatedRangeCollision(paged, true);
		this.evita.close();
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(this.paths).build();
		final CatalogBootstrap published = OfflineCatalogImages.stamp(configuration, TEST_CATALOG, 6, 6);
		final SortedMap<String, StoredFile> before =
			OfflineCatalogImages.storedFiles(OfflineCatalogImages.catalogFolder(configuration, TEST_CATALOG));

		this.evita = new Evita(configuration);
		final Exception failure = assertThrows(Exception.class, this.evita::waitUntilFullyInitialized);
		assertEquals(CatalogState.CORRUPTED, this.evita.getCatalogState(TEST_CATALOG).orElseThrow());
		final String message = collisionRefusal(failure).getMessage();
		assertTrue(message.contains("1 unique value(s)"), message);
		assertTrue(message.contains("attribute `validity` of entity `" + Entities.PRODUCT + "`"), message);
		assertTrue(
			message.contains("`" + Entities.PRODUCT + "` 1") &&
				message.contains("`" + Entities.PRODUCT + "` 3"), message
		);
		this.evita.close();
		assertEquals(published, OfflineCatalogImages.lastBootstrap(configuration, TEST_CATALOG));
		assertEquals(
			before, OfflineCatalogImages.storedFiles(OfflineCatalogImages.catalogFolder(configuration, TEST_CATALOG)),
			"the read-only refusal must leave the previous published state and all persisted parts unchanged"
		);
	}

	/** A folded unique tree left to the loader must fail as the stale twin it is, not as a unique-value collision. */
	@Test
	@DisplayName("leaves a stale page twin of a folded unique tree to the loader instead of refusing it as a collision")
	void shouldLeaveFoldedUniqueStalePageTwinForTheLoader() {
		final int owners = 2 * LEGACY_PAGE_SIZE;
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute(MOMENT, OffsetDateTime.class, AttributeSchemaEditor::unique).updateVia(session);
			for (int owner = 1; owner <= owners; owner++) {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, owner)
					.setAttribute(MOMENT, BASE.plusMillis(owner).atOffset(ZoneOffset.UTC)));
			}
		});
		restart();
		// the second page opens with the key the first page holds for owner 1, now under another owner - the shape a
		// stale leaf-page twin leaves behind, not two spellings of one value
		final List<ValueToRecord> buckets = new ArrayList<>(owners);
		for (int owner = 1; owner <= owners; owner++) {
			buckets.add(new ValueToRecordBitmap(BASE.plusMillis(owner == LEGACY_PAGE_SIZE + 1 ? 1 : owner), owner));
		}
		writeLegacyFilterIndex(MOMENT, buckets);
		this.evita.close();
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(this.paths).build();
		OfflineCatalogImages.stamp(configuration, TEST_CATALOG, 6, 6);

		this.evita = new Evita(configuration);
		final Exception failure = assertThrows(Exception.class, this.evita::waitUntilFullyInitialized);

		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		assertTrue(cause.getMessage().contains("overlaps its successor leaf-page sequence"), cause.getMessage());
	}

	/** A tree planned after a drained filter may fail, but the bootstrap must keep the previous published state. */
	@Test
	@Tag(HISTOGRAM)
	@DisplayName("aborts publication when planning a later tree fails after an earlier filter rewrite")
	void shouldKeepPublishedStateWhenLaterTreePlanningFails() {
		writeSeparatedRangeCollision(false, false);
		final EntityIndex index = globalIndex();
		final long version = catalog().getVersion();
		final EntityIndexStoragePart manifest = productCollection().getDataStoreReader().fetch(
			version, index.getPrimaryKey(), EntityIndexStoragePart.class
		);
		final Set<HistogramIndexStorageKey> histogramKeys = CollectionUtils.createHashSet(1);
		histogramKeys.add(new HistogramIndexStorageKey(index.getIndexKey(), "missing", null));
		productCollection().getStoragePartPersistenceService().putStoragePart(version, new EntityIndexStoragePart(
			index.getPrimaryKey(), manifest.getVersion(), index.getIndexKey(), manifest.getAttributeIndexes(),
			manifest.getPriceIndexes(), manifest.isHierarchyIndex(), manifest.getFacetIndexes(), histogramKeys,
			manifest.getFulltextIndexes()
		));
		productCollection().getStoragePartPersistenceService().putStoragePart(version,
			new HistogramIndexLeafPagePart(index.getPrimaryKey(), "missing", null, 101,
				new ValueToRecord[]{new ValueToRecordBitmap(BASE, 1)}));
		productCollection().getStoragePartPersistenceService().putStoragePart(version, new HistogramIndexStoragePart(
			index.getPrimaryKey(), "missing", null, OffsetDateTime.class, new ValueToRecordBitmap[0], null, 0,
			true, 102, new int[]{101, 102}, false, -1, new int[0], null
		));
		final EntityCollectionFileHeader productHeader =
			(EntityCollectionFileHeader) productCollection().getEntityCollectionHeader();
		final String productDataFile = getEntityCollectionDataStoreFileName(
			productHeader.entityType(), productHeader.entityTypePrimaryKey(), productHeader.entityTypeFileIndex()
		);
		this.evita.close();
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(this.paths).build();
		final CatalogBootstrap published = OfflineCatalogImages.stamp(configuration, TEST_CATALOG, 6, 6);
		final SortedMap<String, StoredFile> before =
			OfflineCatalogImages.storedFiles(OfflineCatalogImages.catalogFolder(configuration, TEST_CATALOG));

		this.evita = new Evita(configuration);
		final Exception failure = assertThrows(Exception.class, this.evita::waitUntilFullyInitialized);
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		assertTrue(cause.getMessage().contains("Missing histogram leaf 102"), cause.getMessage());
		this.evita.close();
		assertEquals(published, OfflineCatalogImages.lastBootstrap(configuration, TEST_CATALOG));
		final SortedMap<String, StoredFile> after =
			OfflineCatalogImages.storedFiles(OfflineCatalogImages.catalogFolder(configuration, TEST_CATALOG));
		OfflineCatalogImages.assertOnlyAppended("a later planning failure", before, after, true);
		assertTrue(
			after.get(productDataFile).size() > before.get(productDataFile).size(),
			"the earlier filter rewrite must append to the product data file before histogram planning fails"
		);
	}

	/** The same separated range buckets may union their owners when the attribute permits duplicate values. */
	@ParameterizedTest(name = "paged={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("merges non-unique range collisions revealed by canonical re-sorting and survives restart")
	void shouldMergeNonUniqueRangeCollisionAfterResorting(boolean paged) {
		writeSeparatedRangeCollision(paged, false);
		this.migrateOnRestart = true;
		restart();
		assertEquals(List.of(1, 3), ownersWith(attributeEquals(VALIDITY, separatedRangeOf(1))));
		assertEquals(List.of(2), ownersWith(attributeEquals(VALIDITY, separatedRangeOf(2))));
		restart();
		assertEquals(List.of(1, 3), ownersWith(attributeEquals(VALIDITY, separatedRangeOf(3))));
		assertEquals(paged ? 300 : 3, allProducts().size());
	}

	/** Extracts the named protocol refusal through the engine's catalog-load exception wrappers. */
	@Nonnull
	private static ObsoleteStorageProtocolException collisionRefusal(@Nonnull Throwable failure) {
		Throwable cause = failure;
		while (cause != null) {
			if (cause instanceof ObsoleteStorageProtocolException refusal) {
				return refusal;
			}
			cause = cause.getCause();
		}
		return fail("Expected an obsolete-protocol collision refusal", failure);
	}

	/** Seeds distinct placeholders, then replaces only the bucket axis with the released three-range ordering. */
	private void writeSeparatedRangeCollision(boolean paged, boolean unique) {
		final int owners = paged ? 300 : 3;
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute(VALIDITY, DateTimeRange.class, attribute -> {
					if (unique) {
						attribute.unique();
					} else {
						attribute.filterable();
					}
				}).updateVia(session);
			for (int owner = 1; owner <= owners; owner++) {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, owner)
					.setAttribute(VALIDITY, validityOf(owner)));
			}
		});
		restart();
		final List<ValueToRecord> buckets = new ArrayList<>(owners);
		buckets.add(new ValueToRecordBitmap(separatedRangeOf(1), 1));
		// Keep both pages properly populated and put A and C on opposite sides of a leaf boundary.
		if (paged) {
			for (int owner = 4; owner <= 151; owner++) {
				buckets.add(new ValueToRecordBitmap(DateTimeRange.until(
					OffsetDateTime.parse("2026-01-01T02:00:10+02:00").plusSeconds(owner)
				), owner));
			}
		}
		buckets.add(new ValueToRecordBitmap(separatedRangeOf(2), 2));
		buckets.add(new ValueToRecordBitmap(separatedRangeOf(3), 3));
		if (paged) {
			for (int owner = 152; owner <= owners; owner++) {
				buckets.add(new ValueToRecordBitmap(DateTimeRange.until(
					OffsetDateTime.parse("2026-01-01T00:00:10Z").plusSeconds(owner)
				), owner));
			}
			writeLegacyFilterIndex(VALIDITY, buckets);
		} else {
			final FilterIndexStoragePart root = onDiskRoot(VALIDITY);
			productCollection().getStoragePartPersistenceService().putStoragePart(
				catalog().getVersion(), new FilterIndexStoragePart(
					globalIndex().getPrimaryKey(), attributeKey(VALIDITY), DateTimeRange.class,
					buckets.toArray(ValueToRecordBitmap[]::new), root.getRangeIndex(),
					root.getIndexedDecimalPlaces(), null
				)
			);
		}
	}

	/**
	 * Returns open-from ranges A, B and C ending at 02:00:10+02:00, 01:00:05+01:00 and 00:00:10Z respectively.
	 * Released order separates A and C; the millisecond comparator makes them equal at the same instant.
	 */
	@Nonnull
	private static DateTimeRange separatedRangeOf(int owner) {
		return DateTimeRange.until(OffsetDateTime.parse(switch (owner) {
			case 1 -> "2026-01-01T02:00:10+02:00";
			case 2 -> "2026-01-01T01:00:05+01:00";
			case 3 -> "2026-01-01T00:00:10Z";
			default -> throw new IllegalArgumentException("No separated range for owner " + owner);
		}));
	}

	/** A migration may publish new headers while leaving canonical tree records at exactly their old locations. */
	@ParameterizedTest(name = "paged={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("emits no new root or leaf parts for a canonical filter tree")
	void shouldKeepCanonicalTreePartsAtTheirOriginalLocations(boolean paged) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute(MOMENT, OffsetDateTime.class, AttributeSchemaEditor::filterable).updateVia(session);
			for (int owner = 1; owner <= (paged ? OWNER_COUNT : 3); owner++) {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, owner)
					.setAttribute(MOMENT, BASE.plusMillis(owner).atOffset(ZoneOffset.UTC)));
			}
		});
		restart();
		assertEquals(paged, onDiskRoot(MOMENT).isPaged());
		final Map<RecordKey, FileLocation> original = filterPartLocations();
		this.migrateOnRestart = true;
		restart();
		assertEquals(original, filterPartLocations(), "the migration must append no canonical tree parts");
		restart();
		assertEquals(original, filterPartLocations());
	}

	/** Current temporal roots and both page streams must remain physically untouched by the protocol upgrade. */
	@ParameterizedTest(name = "paged={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("emits no replacement parts for canonical millisecond range companions")
	void shouldKeepCanonicalRangePartsAtTheirOriginalLocations(boolean paged) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute(VALIDITY, DateTimeRange.class, AttributeSchemaEditor::filterable).updateVia(session);
			for (int owner = 1; owner <= (paged ? OWNER_COUNT : 3); owner++) {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, owner)
					.setAttribute(VALIDITY, validityOf(owner)));
			}
		});
		restart();
		assertEquals(paged, onDiskRoot(VALIDITY).isRangePaged());
		final Map<RecordKey, FileLocation> original = filterPartLocations();
		this.migrateOnRestart = true;
		restart();
		assertEquals(original, filterPartLocations(), "canonical range roots and leaves must retain their addresses");
		restart();
		assertEquals(original, filterPartLocations());
	}

	/** Reads physical addresses through the store implementation, filtering out unrelated migration writes. */
	@Nonnull
	private Map<RecordKey, FileLocation> filterPartLocations() {
		final OffsetIndexStoragePartPersistenceService service =
			(OffsetIndexStoragePartPersistenceService)
				(StoragePartPersistenceService<?>)
					productCollection().getStoragePartPersistenceService();
		final OffsetIndex store = service.getOffsetIndex();
		final byte rootType = store.getIdForRecordType(FilterIndexStoragePart.class);
		final byte leafType = store.getIdForRecordType(FilterIndexLeafPagePart.class);
		final byte rangeType = store.getIdForRecordType(RangeIndexLeafPagePart.class);
		final Map<RecordKey, FileLocation> locations = CollectionUtils.createHashMap(8);
		for (final Entry<RecordKey, FileLocation> entry : store.getEntries()) {
			if (entry.getKey().recordType() == rootType || entry.getKey().recordType() == leafType ||
				entry.getKey().recordType() == rangeType) {
				locations.put(entry.getKey(), entry.getValue());
			}
		}
		return locations;
	}

	/** Covers every scalar temporal codec with and without collisions in both persisted layouts. */
	@ParameterizedTest(name = "{0}, paged={1}, collision={2}")
	@MethodSource("temporalLayouts")
	@DisplayName("canonicalizes scalar temporal keys on disk before the first query and a restart")
	void shouldMigrateTemporalKeys(
		@Nonnull Class<? extends Serializable> type, boolean paged, boolean collision
	) {
		final Serializable first = declaredMoment(type, 0);
		final Serializable second = declaredMoment(type, collision ? 0 : 1);
		final Serializable third = declaredMoment(type, 2);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute(MOMENT, type, AttributeSchemaEditor::filterable).updateVia(session);
			session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1).setAttribute(MOMENT, first));
			session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 2).setAttribute(MOMENT, second));
			session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 3).setAttribute(MOMENT, third));
		});
		restart();
		final ValueToRecordBitmap[] buckets = {
			new ValueToRecordBitmap(rawKey(type, 0, 1), 1),
			new ValueToRecordBitmap(rawKey(type, collision ? 0 : 1, 999_999), 2),
			new ValueToRecordBitmap(rawKey(type, 2, 1), 3)
		};
		final EntityCollection collection = productCollection();
		final int indexPk = globalIndex().getPrimaryKey();
		final long version = catalog().getVersion();
		final AttributeKeyWithIndexType key = new AttributeKeyWithIndexType(attributeKey(MOMENT),
			AttributeIndexType.FILTER);
		if (paged) {
			collection.getStoragePartPersistenceService().putStoragePart(version, new FilterIndexLeafPagePart(
				indexPk, key, 101, new ValueToRecord[]{buckets[0], buckets[1]}, new int[]{11, 12}
			));
			collection.getStoragePartPersistenceService().putStoragePart(version, new FilterIndexLeafPagePart(
				indexPk, key, 102, new ValueToRecord[]{buckets[2]}, new int[]{13}
			));
		}
		collection.getStoragePartPersistenceService().putStoragePart(version, new FilterIndexStoragePart(
			indexPk, attributeKey(MOMENT), type, paged ? new ValueToRecordBitmap[0] : buckets, null, 0,
			paged, paged ? 102 : -1, paged ? new int[]{101, 102} : new int[0], false, -1, new int[0],
			14, paged ? null : new int[]{11, 12, 13}, null
		));
		this.migrateOnRestart = true;
		restart();
		for (int iteration = 0; iteration < 2; iteration++) {
			assertEquals(collision ? List.of(1, 2) : List.of(1), ownersWith(attributeEquals(MOMENT, first)));
			assertEquals(List.of(3), ownersWith(attributeEquals(MOMENT, third)));
			assertEquals(List.of(1, 2, 3), allProducts());
			final InvertedIndex tree = globalIndex().getFilterIndex(attributeKey(MOMENT)).getInvertedIndex();
			assertEquals(11, tree.getValueId(first));
			assertEquals(13, tree.getValueId(third));
			assertEquals(14, tree.getNextValueId());
			assertTrue(!tree.isDirty());
			final FilterIndexStoragePart root = onDiskRoot(MOMENT);
			if (paged) {
				for (final int sequence : root.getLeafPageSequences()) {
					assertTrue(sequence > 102, "replacement sequences must exceed the persisted high-water");
				}
				final int streamId = productCollection().getStoragePartPersistenceService().getReadOnlyKeyCompressor()
					.getId(new LeafStreamKey(indexPk, key));
				for (final int old : new int[]{101, 102}) {
					assertNull(productCollection().getDataStoreReader().fetch(
						catalog().getVersion(), AbstractLeafPagePart.computeUniquePartId(streamId, old),
						FilterIndexLeafPagePart.class
					));
				}
			} else {
				assertArrayEquals(
					collision ? new int[]{11, 13} : new int[]{11, 12, 13}, root.getInlineValueIds()
				);
			}
			restart();
		}
	}

	/** Tests histogram enumeration through a real published referenced-type index manifest. */
	@ParameterizedTest(name = "paged={0}")
	@ValueSource(booleans = {false, true})
	@Tag(HISTOGRAM)
	@DisplayName("rewrites inline and paged histogram bucket trees and retires replaced pages")
	void shouldMigrateHistogramBuckets(boolean paged) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE,
					thatIs -> thatIs.indexedForFilteringInScope(Scope.LIVE)).updateVia(session);
			for (int owner = 1; owner <= 3; owner++) {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, owner).setReference("brand", 1));
			}
		});
		restart();
		final EntityCollection collection = productCollection();
		final EntityIndexKey indexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand");
		final ReferencedTypeEntityIndex index = (ReferencedTypeEntityIndex) collection.getIndexByKeyIfExists(indexKey);
		assertNotNull(index);
		final int pk = index.getPrimaryKey();
		final long version = catalog().getVersion();
		final EntityIndexStoragePart manifest = collection.getDataStoreReader().fetch(version, pk,
			EntityIndexStoragePart.class);
		final Set<HistogramIndexStorageKey> histogramKeys = CollectionUtils.createHashSet(1);
		histogramKeys.add(new HistogramIndexStorageKey(indexKey, "moments", null));
		collection.getStoragePartPersistenceService().putStoragePart(version, new EntityIndexStoragePart(
			pk, manifest.getVersion(), indexKey, manifest.getAttributeIndexes(), manifest.getPriceIndexes(),
			manifest.isHierarchyIndex(), manifest.getFacetIndexes(), histogramKeys, manifest.getFulltextIndexes()
		));
		final ValueToRecordBitmap[] buckets = {
			new ValueToRecordBitmap(BASE.plusNanos(1), 1),
			new ValueToRecordBitmap(BASE.plusNanos(2), 2),
			new ValueToRecordBitmap(BASE.plusMillis(1).plusNanos(3), 3)
		};
		if (paged) {
			collection.getStoragePartPersistenceService().putStoragePart(version,
				new HistogramIndexLeafPagePart(pk, "moments", null, 101, new ValueToRecord[]{buckets[0]}));
			collection.getStoragePartPersistenceService().putStoragePart(version,
				new HistogramIndexLeafPagePart(pk, "moments", null, 102, new ValueToRecord[]{buckets[1], buckets[2]}));
		}
		collection.getStoragePartPersistenceService().putStoragePart(version, new HistogramIndexStoragePart(
			pk, "moments", null, OffsetDateTime.class, paged ? new ValueToRecordBitmap[0] : buckets, null, 0,
			paged, paged ? 102 : -1, paged ? new int[]{101, 102} : new int[0], false, -1, new int[0], null
		));
		this.migrateOnRestart = true;
		restart();
		for (int iteration = 0; iteration < 2; iteration++) {
			final ReferencedTypeEntityIndex loaded = (ReferencedTypeEntityIndex)
				productCollection().getIndexByKeyIfExists(indexKey);
			final FilterIndex histogram = loaded.getHistogramFilterIndex("moments", null);
			assertNotNull(histogram);
			assertArrayEquals(new int[]{1, 2},
				histogram.getRecordsEqualTo(BASE.atOffset(ZoneOffset.UTC)).getArray());
			assertEquals(2, histogram.getDistinctValueCount());
			assertArrayEquals(new int[]{3},
				histogram.getRecordsEqualTo(BASE.plusMillis(1).atOffset(ZoneOffset.UTC)).getArray());
			assertArrayEquals(
				new int[]{1, 2, 3}, histogram.getAllRecords().getArray()
			);
			assertTrue(!histogram.getInvertedIndex().isDirty());
			final HistogramIndexStoragePart root = productCollection().getDataStoreReader().fetch(
				catalog().getVersion(), new HistogramIndexStorageKey(indexKey, "moments", null).computeUniquePartId(
					pk, productCollection().getStoragePartPersistenceService().getReadOnlyKeyCompressor()
				), HistogramIndexStoragePart.class
			);
			assertNotNull(root);
			if (paged) {
				for (final int sequence : root.getLeafPageSequences()) {
					assertTrue(sequence > 102);
				}
				final int streamId = productCollection().getStoragePartPersistenceService().getReadOnlyKeyCompressor()
					.getId(new HistogramLeafStreamKey(pk, "moments", null, HistogramLeafStreamKey.StreamKind.BUCKET));
				for (final int old : new int[]{101, 102}) {
					assertNull(productCollection().getDataStoreReader().fetch(
						catalog().getVersion(), AbstractLeafPagePart.computeUniquePartId(streamId, old),
						HistogramIndexLeafPagePart.class
					));
				}
			} else {
				assertEquals(BASE, root.getHistogramPoints()[0].getValue());
				assertEquals(BASE.plusMillis(1), root.getHistogramPoints()[1].getValue());
			}
			restart();
		}
	}

	/** Supplies the three type-level functions, both page modes and both collision cases. */
	@Nonnull
	private static Stream<Arguments> temporalLayouts() {
		return Stream.of(OffsetDateTime.class, LocalDateTime.class, LocalTime.class).flatMap(type ->
			Stream.of(false, true).flatMap(paged -> Stream.of(false, true).map(collision ->
				Arguments.of(type, paged, collision))));
	}

	/** Returns a declared-type query/write value in the fixture's millisecond. */
	@Nonnull
	private static Serializable declaredMoment(@Nonnull Class<?> type, int millisecond) {
		final OffsetDateTime moment = BASE.plusMillis(millisecond).atOffset(ZoneOffset.UTC);
		return type == OffsetDateTime.class ? moment :
			type == LocalDateTime.class ? moment.toLocalDateTime() : moment.toLocalTime();
	}

	/** Returns the exact sub-millisecond key a released temporal filter writer stored. */
	@Nonnull
	private static Serializable rawKey(@Nonnull Class<?> type, int millisecond, int nanos) {
		return type == LocalTime.class ? LocalTime.of(9, 0).plusNanos(millisecond * 1_000_000L + nanos) :
			BASE.plusMillis(millisecond).plusNanos(nanos);
	}

	/**
	 * The moment the owner carries: owners `2k - 1` and `2k` share the moment `BASE + k` milliseconds.
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
	 */
	@Nonnull
	private static DateTimeRange validityOf(int owner) {
		final int index = owner - 1;
		final Instant from = BASE.plusSeconds(index / 2).plusMillis(index % 2 == 0 ? 500 : 200);
		final Instant to = BASE.plusSeconds(1_000_000L + index);
		return DateTimeRange.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC));
	}

	/** Verifies persisted migration results through writes and a subsequent restart. */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("keeps a write made after migration collapsed sub-millisecond legacy buckets")
	void shouldKeepWriteMadeAfterMigrationCollapsedLegacyBuckets(@Nonnull CatalogState state) {
		final int[] legacyPages = loadLegacyMomentCatalog(state, false);

		// A write after migration lands in the first corrected page.
		writeLateOwner(MOMENT, momentOf(1));
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "before the restart");

		restart();
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "the late owner must survive the restart");
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(MOMENT).getLeafPageSequences()),
			"the migration must have replaced the legacy page list"
		);
		assertEveryListedPageIsStored(MOMENT);
	}

	/** Verifies persisted migration results through writes and a subsequent restart. */
	@Test
	@DisplayName("keeps a write made after a commit that wrote only a sibling attribute of the same entity index")
	void shouldKeepWriteMadeAfterSiblingAttributeWasCommittedFirst() {
		final int[] legacyPages = loadLegacyMomentCatalog(CatalogState.ALIVE, true);

		// the first commit after the load touches a different attribute of the same entity index, so the entity index
		// is flushed while the migrated filter index itself takes no write at all
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

		// only then the migrated attribute is written - the merge carried its tree across the previous commit
		writeLateOwner(MOMENT, momentOf(1));
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "before the restart");

		restart();
		assertEquals(List.of(1, 2, LATE_OWNER), ownersAt(momentOf(1)), "the late owner must survive the restart");
		assertEquals(List.of(3), ownersWith(attributeEquals(LABEL, "renamed")), "the sibling must survive too");
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(MOMENT).getLeafPageSequences()),
			"the migration must have replaced the legacy page list"
		);
		assertEveryListedPageIsStored(MOMENT);
	}

	/** Verifies persisted migration results through writes and a subsequent restart. */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("opens again after a write that split a leaf of the migrated index")
	void shouldReopenAfterWriteSplitLeafOfMigratedIndex(@Nonnull CatalogState state) {
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
		assertEquals(List.of(1, 2), ownersAt(momentOf(1)), "a migrated bucket must survive the restart");
		assertEquals(
			List.of(OWNER_COUNT - 1, OWNER_COUNT), ownersAt(momentOf(OWNER_COUNT)),
			"a bucket from the last legacy leaf must survive migration and the restart"
		);
		assertEquals(
			List.of(SPLITTING_OWNER_BASE + 1), ownersAt(BASE.minusMillis(1).atOffset(ZoneOffset.UTC)),
			"a splitting owner must survive the restart"
		);
		assertEquals(
			OWNER_COUNT + SPLITTING_OWNER_COUNT, allProducts().size(), "every product must survive the restart"
		);
	}

	/** Verifies persisted migration results through writes and a subsequent restart. */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("keeps a write made after migration re-sorted second-granularity ranges paged on both axes")
	void shouldKeepWriteMadeAfterMigrationResortedSecondGranularityRanges(@Nonnull CatalogState state) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(VALIDITY, DateTimeRange.class, AttributeSchemaEditor::filterable)
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
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(VALIDITY).getLeafPageSequences()),
			"the migration must have replaced the legacy pages"
		);
		assertEquals(
			List.of(1), ownersWith(attributeEquals(VALIDITY, validityOf(1))),
			"the migrated index must answer every legacy record"
		);

		// a write after the load - it joins the bucket of the first owner, whose page the migration replaced
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
			"the migration must have replaced the legacy page list"
		);
	}

	/**
	 * Builds a catalog of {@link #OWNER_COUNT} products carrying a filterable moment (and, when asked, a filterable
	 * label of their own), replaces the persisted filter index with legacy pages that collapse during migration,
	 * and loads the catalog again in the requested state.
	 */
	@Nonnull
	private int[] loadLegacyMomentCatalog(@Nonnull CatalogState state, boolean withLabel) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntitySchemaBuilder schema = session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(MOMENT, OffsetDateTime.class, AttributeSchemaEditor::filterable);
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
		// migration merges back onto one key before the engine loads them
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
		assertNotEquals(
			Arrays.toString(legacyPages), Arrays.toString(onDiskRoot(MOMENT).getLeafPageSequences()),
			"the migration must have replaced the legacy pages"
		);
		assertEquals(List.of(1, 2), ownersAt(momentOf(1)), "the migrated index must answer every legacy record");
		return legacyPages;
	}

	/**
	 * Replaces the persisted bucket axis of the filter index of the attribute with the passed legacy buckets, chunked
	 * into pages of {@link #LEGACY_PAGE_SIZE} buckets. The range axis, when there is one, is left as persisted.
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
		this.migrateOnRestart = true;
		return pages;
	}

	/**
	 * Writes the {@link #LATE_OWNER} carrying the passed value of the attribute.
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
	 */
	private void assertEveryListedPageIsStored(@Nonnull String attributeName) {
		final FilterIndexStoragePart root = onDiskRoot(attributeName);
		final EntityCollection collection = productCollection();
		final long catalogVersion = catalog().getVersion();
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(attributeKey(attributeName), AttributeIndexType.FILTER);
		final int globalIndexPrimaryKey = globalIndex().getPrimaryKey();
		if (root.isPaged()) {
			final int streamId = productCollection().getStoragePartPersistenceService().getReadOnlyKeyCompressor()
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
		if (this.migrateOnRestart) {
			final EvitaConfiguration configuration =
				newTestEvitaConfigurationBuilder(this.paths).build();
			OfflineCatalogImages.stamp(configuration, TEST_CATALOG, 6, 6);
			this.migrateOnRestart = false;
		}
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Returns the products whose moment equals the passed one, by primary key.
	 */
	@Nonnull
	private List<Integer> ownersAt(@Nonnull OffsetDateTime moment) {
		return ownersWith(attributeEquals(MOMENT, moment));
	}

	/**
	 * Returns the owners - including the {@link #LATE_OWNER}, who shares the validity of the first one - whose
	 * validity contains the passed moment, computed from the validities themselves.
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
	 */
	@Nonnull
	private List<Integer> allProducts() {
		return ownersWith(null);
	}

	/**
	 * Returns the products matching the passed filter, by primary key.
	 */
	@Nonnull
	private List<Integer> ownersWith(@Nullable FilterConstraint filter) {
		final Function<EvitaSessionContract, List<Integer>> primaryKeys = session -> session
			.queryListOfEntityReferences(
				query(
					collection(Entities.PRODUCT),
					filter == null ? null : filterBy(filter),
					null,
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

	/** Builds the entity-level identity of the fixture attribute. */
	@Nonnull
	private static AttributeIndexKey attributeKey(@Nonnull String attributeName) {
		return new AttributeIndexKey(null, attributeName, null);
	}

	/** Returns the live catalog whose persisted state the fixture replaces. */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
	}

	/** Returns the collection holding the fixture indexes and stored parts. */
	@Nonnull
	private EntityCollection productCollection() {
		return (EntityCollection) catalog().getCollectionForEntity(Entities.PRODUCT).orElseThrow();
	}

	/** Returns the global live-scope index advertised by the collection header. */
	@Nonnull
	private EntityIndex globalIndex() {
		return Objects.requireNonNull(
			productCollection().getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE))
		);
	}
}
