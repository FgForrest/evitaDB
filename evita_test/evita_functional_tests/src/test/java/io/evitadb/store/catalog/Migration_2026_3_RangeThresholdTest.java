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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.RangePoint;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/** Real legacy root versions must convert ranges once before protocol 7 loads or demotes their entity indexes. */
@DisplayName("Legacy range companions migrate before production loading")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
@Tag(HISTOGRAM)
class Migration_2026_3_RangeThresholdTest implements EvitaTestSupport {
	/** Filter identity shared by every fixture. */
	private static final AttributeIndexKey VALIDITY = new AttributeIndexKey(null, "validity", null);
	/** A persisted range high-water deliberately larger than every live page sequence. */
	private static final int HIGH_WATER = 100;
	/** Old pages split the sentinels and the payload across three records. */
	private static final int[] OLD_PAGES = {11, 17, 23};
	/** Concrete millisecond thresholds make a second multiplication observable. */
	private static final long[] EXPECTED = {Long.MIN_VALUE, 10_000L, 20_000L, 21_000L, Long.MAX_VALUE};

	private TestPaths paths;
	private Evita evita;

	/** Starts an isolated catalog engine for each persisted-format scenario. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("Migration_2026_3_RangeThreshold");
		this.evita = new Evita(configuration());
	}

	/** Closes all file handles before deleting the fixture directory. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** Exercises both root families, both independent axes and a combined bucket-order correction. */
	private static Stream<Arguments> layouts() {
		return Stream.of(false, true).flatMap(histogram -> Stream.of(false, true).flatMap(rangePaged ->
			Stream.of(false, true).flatMap(bucketPaged -> Stream.of(false, true).map(correctBuckets ->
				Arguments.of(histogram, rangePaged, bucketPaged, correctBuckets)))));
	}

	/** Every listed legacy range page is replaced above high-water, while inline readers convert only once. */
	@ParameterizedTest(name = "histogram={0}, rangePaged={1}, bucketPaged={2}, correctBuckets={3}")
	@MethodSource("layouts")
	@DisplayName("converts once through the protocol upgrade and another restart")
	void shouldConvertRangeCompanionsExactlyOnce(
		boolean histogram, boolean rangePaged, boolean bucketPaged, boolean correctBuckets
	) {
		buildCatalog(false);
		final EntityIndexKey key = new EntityIndexKey(
			histogram ? EntityIndexType.REFERENCED_ENTITY_TYPE : EntityIndexType.GLOBAL,
			Scope.LIVE, histogram ? "brand" : null
		);
		final int indexPk = collection().getIndexByKeyIfExists(key).getPrimaryKey();
		this.evita.close();
		writeLegacyState(key, indexPk, histogram, rangePaged, bucketPaged, correctBuckets, false);
		openMigrated();
		for (int restart = 0; restart < 2; restart++) {
			final EntityIndex index = collection().getIndexByKeyIfExists(key);
			final FilterIndex filter = histogram
				? ((ReferencedTypeEntityIndex) index).getHistogramFilterIndex("ranges", null)
				: index.getFilterIndex(VALIDITY);
			assertNotNull(filter);
			assertArrayEquals(new int[]{1}, filter.getRecordsEqualTo(validity(1, correctBuckets)).getArray());
			assertArrayEquals(new int[]{2}, filter.getRecordsEqualTo(validity(2, correctBuckets)).getArray());
			assertArrayEquals(EXPECTED, thresholds(filter.getRangeIndex()));
			assertArrayEquals(new int[]{1, 2}, filter.getRecordsValidInFormula(15_000L).compute().getArray());
			assertArrayEquals(new int[0], filter.getRecordsValidInFormula(15L).compute().getArray());
			assertArrayEquals(new int[0], filter.getRecordsValidInFormula(22_000L).compute().getArray());
			assertPersistedRange(key, indexPk, histogram, rangePaged);
			this.evita.close();
			this.evita = new Evita(configuration());
			this.evita.waitUntilFullyInitialized();
		}
		assertEquals(7, lastBootstrap(configuration(), TEST_CATALOG).storageProtocolVersion());
	}

	/** A converted range tree spanning multiple new leaves must retain probes on its first and last pages. */
	@ParameterizedTest(name = "histogram={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("converts and republishes every point of a multi-leaf temporal range tree")
	void shouldConvertAllPointsOfLargePagedCompanions(boolean histogram) {
		buildCatalog(false, true);
		final EntityIndexKey key = new EntityIndexKey(
			histogram ? EntityIndexType.REFERENCED_ENTITY_TYPE : EntityIndexType.GLOBAL,
			Scope.LIVE, histogram ? "brand" : null);
		final int indexPk = collection().getIndexByKeyIfExists(key).getPrimaryKey();
		this.evita.close();
		writeLegacyState(key, indexPk, histogram, true, false, false, false, true);
		openMigrated();
		final long[] expected = new long[602];
		expected[0] = Long.MIN_VALUE;
		for (int owner = 1; owner <= 300; owner++) {
			expected[owner * 2 - 1] = 1_700_000_000_000L + 3_600_000L * owner;
			expected[owner * 2] = 1_700_001_800_000L + 3_600_000L * owner;
		}
		expected[601] = Long.MAX_VALUE;
		for (int restart = 0; restart < 2; restart++) {
			final EntityIndex index = collection().getIndexByKeyIfExists(key);
			final FilterIndex filter = histogram
				? ((ReferencedTypeEntityIndex) index).getHistogramFilterIndex("ranges", null)
				: index.getFilterIndex(VALIDITY);
			assertTrue(filter.getRangeIndex().isPaged(), "the converted tree must span several new leaves");
			assertArrayEquals(expected, thresholds(filter.getRangeIndex()));
			assertArrayEquals(new int[]{1}, filter.getRecordsValidInFormula(1_700_003_601_000L).compute().getArray());
			assertArrayEquals(new int[]{300}, filter.getRecordsValidInFormula(1_701_080_001_000L).compute().getArray());
			assertArrayEquals(new int[0], filter.getRecordsValidInFormula(1_700_005_401_000L).compute().getArray());
			assertArrayEquals(new int[0], filter.getRecordsValidInFormula(1_700_003_601L).compute().getArray());
			assertPersistedRange(key, indexPk, histogram, true);
			this.evita.close();
			this.evita = new Evita(configuration());
			this.evita.waitUntilFullyInitialized();
		}
	}

	/** Offset-dependent open sentinels on different old pages must union their postings at the new border. */
	@ParameterizedTest(name = "histogram={0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("merges paged open-bound thresholds across old page boundaries")
	void shouldMergePagedOpenBounds(boolean histogram) {
		buildCatalog(false);
		final EntityIndexKey key = new EntityIndexKey(
			histogram ? EntityIndexType.REFERENCED_ENTITY_TYPE : EntityIndexType.GLOBAL,
			Scope.LIVE, histogram ? "brand" : null);
		final int indexPk = collection().getIndexByKeyIfExists(key).getPrimaryKey();
		this.evita.close();
		writeLegacyState(key, indexPk, histogram, true, false, false, true);
		openMigrated();
		for (int restart = 0; restart < 2; restart++) {
			final EntityIndex index = collection().getIndexByKeyIfExists(key);
			final FilterIndex filter = histogram
				? ((ReferencedTypeEntityIndex) index).getHistogramFilterIndex("ranges", null)
				: index.getFilterIndex(VALIDITY);
			final RangeIndex range = filter.getRangeIndex();
			assertArrayEquals(new long[]{Long.MIN_VALUE, 10_000L, 20_000L, Long.MAX_VALUE}, thresholds(range));
			assertArrayEquals(new int[]{1, 2}, range.getRanges()[3].getEnds().getArray());
			assertArrayEquals(new int[]{1, 2}, filter.getRecordsValidInFormula(1_000_000L).compute().getArray());
			assertArrayEquals(new int[0], filter.getRecordsValidInFormula(9_999L).compute().getArray());
			assertPersistedRange(key, indexPk, histogram, true);
			this.evita.close();
			this.evita = new Evita(configuration());
			this.evita.waitUntilFullyInitialized();
		}
	}

	/** Virtual conversion loads full partitions; their paged companions must be migrated before demotion reads them. */
	@Test
	@DisplayName("converts paged ranges before a single-owner partition becomes a registry row")
	void shouldConvertRangesBeforeVirtualPartitionDemotion() {
		buildCatalog(true);
		final EntityIndexKey key = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey("brand", 1)));
		final int indexPk = collection().getIndexByKeyIfExists(key).getPrimaryKey();
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withReferenceTo("brand", "brand", Cardinality.ZERO_OR_MORE, ReferenceSchemaEditor::indexedForFiltering)
				.updateVia(session);
		});
		this.evita.close();
		writeLegacyState(key, indexPk, false, true, false, false, false);
		openMigrated();
		assertDemotedPartition(key, indexPk);
		assertEquals(7, lastBootstrap(configuration(), TEST_CATALOG).storageProtocolVersion());
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
		assertDemotedPartition(key, indexPk);
	}

	/** Demotion must preserve the partition identity as a registry row after removing its physical index object. */
	private void assertDemotedPartition(EntityIndexKey key, int indexPk) {
		assertNull(collection().getIndexByKeyIfExists(key), "the single-owner object must have been demoted");
		final ReferencedTypeEntityIndex type = (ReferencedTypeEntityIndex) collection().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand"));
		assertNotNull(type);
		assertEquals(indexPk, ReferencePartitions.findRegisteredForWrite(type,
			(RepresentativeReferenceKey) key.discriminator()).getPrimaryKey());
	}

	/** Uses the compact two-owner fixture unless a test explicitly requests a multi-leaf range tree. */
	private void buildCatalog(boolean singleOwner) {
		buildCatalog(singleOwner, false);
	}

	/** Builds shared partitions for ordinary tests or one physical single-owner partition for demotion. */
	private void buildCatalog(boolean singleOwner, boolean dense) {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT).withoutGeneratedPrimaryKey()
				.withAttribute("validity", DateTimeRange.class, AttributeSchemaEditor::filterable)
				.withReferenceTo("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> {
					if (singleOwner) {
						ref.indexedForFilteringAndPartitioning();
					} else {
						ref.indexedForFiltering();
					}
				}).updateVia(session);
			for (int owner = 1; owner <= (singleOwner ? 1 : dense ? 300 : 2); owner++) {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, owner)
					.setAttribute("validity", dense ? denseValidity(owner) : validity(owner, false))
					.setReference("brand", 1));
			}
		});
	}

	/** Writes the compact legacy fixture, including independent bucket and range layouts. */
	private void writeLegacyState(
		EntityIndexKey key, int indexPk, boolean histogram, boolean rangePaged, boolean bucketPaged,
		boolean correctBuckets, boolean openEnded
	) {
		writeLegacyState(key, indexPk, histogram, rangePaged, bucketPaged, correctBuckets, openEnded, false);
	}

	/** Writes actual released root versions, flushes their header addresses, then stamps the protocol for migration. */
	private void writeLegacyState(
		EntityIndexKey key, int indexPk, boolean histogram, boolean rangePaged, boolean bucketPaged,
		boolean correctBuckets, boolean openEnded, boolean dense
	) {
		final EvitaConfiguration config = configuration();
		final int catalogFile = lastBootstrap(config, TEST_CATALOG).catalogFileIndex();
		withOfflineCatalog(config, TEST_CATALOG, service -> {
			final long version = service.getLastCatalogVersion();
			final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
				service.getStoragePartPersistenceService(version);
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
				catalogParts.getCatalogHeader(version);
			final CollectionFileReference reference = header.collectionFileIndex().get(Entities.PRODUCT);
			final EntityCollectionFileHeader collectionHeader = EntityCollectionHeaderReconciler.reconcile(
				TEST_CATALOG, reference, catalogParts.getStoragePart(version, reference.entityTypePrimaryKey(),
					EntityCollectionFileHeader.class));
			final CollectionFileReference replacement = LegacyRangeIndexImages.withLegacyRanges(
				config, TEST_CATALOG, version, service.getCatalogStoragePath(), collectionHeader, collectionService -> {
					writeParts(collectionService.getStoragePartPersistenceService(), version, key, indexPk,
						histogram, rangePaged, bucketPaged, correctBuckets, openEnded, dense);
					final OffsetIndexDescriptor descriptor = collectionService.flush(version,
						new NoChangeHeaderInfoSupplier(collectionService.getEntityCollectionHeader()));
					catalogParts.putStoragePart(version, collectionService.getEntityCollectionHeader());
					return new CollectionFileReference(Entities.PRODUCT, reference.entityTypePrimaryKey(),
						reference.fileIndex(), descriptor.fileLocation());
				});
			final Map<String, CollectionFileReference> references =
				CollectionUtils.createHashMap(header.collectionFileIndex().size());
			references.putAll(header.collectionFileIndex());
			references.put(Entities.PRODUCT, replacement);
			catalogParts.writeCatalogHeader(
				6, version, service.getCatalogStoragePath(), header.walFileReference(), references,
				header.catalogId(), header.catalogName(), header.catalogState(), header.lastEntityCollectionPrimaryKey()
			);
			service.recordBootstrap(version, TEST_CATALOG, catalogFile, null);
			return null;
		});
	}

	/** Seeds independent bucket and range streams with separate page identities and released second thresholds. */
	private static void writeParts(
		OffsetIndexStoragePartPersistenceService parts, long version, EntityIndexKey key, int indexPk,
		boolean histogram, boolean rangePaged, boolean bucketPaged, boolean correctBuckets,
		boolean openEnded, boolean dense
	) {
		final ValueToRecordBitmap[] buckets = dense ? denseBuckets() : key.type() == EntityIndexType.REFERENCED_ENTITY
			? new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity(1, false), 1)}
			: new ValueToRecordBitmap[]{
			new ValueToRecordBitmap(openEnded ? DateTimeRange.since(OffsetDateTime.parse("1970-01-01T00:00:10Z"))
				: validity(1, correctBuckets), 1),
			new ValueToRecordBitmap(openEnded ? DateTimeRange.since(OffsetDateTime.parse("1970-01-01T00:00:20Z"))
				: validity(2, correctBuckets), 2)
		};
		final TransactionalRangePoint[] points = dense ? densePoints() : key.type() == EntityIndexType.REFERENCED_ENTITY
			? new TransactionalRangePoint[]{
				new TransactionalRangePoint(Long.MIN_VALUE),
				new TransactionalRangePoint(10L, new int[]{1}, new int[0]),
				new TransactionalRangePoint(20L, new int[0], new int[]{1}),
				new TransactionalRangePoint(Long.MAX_VALUE)
			} : openEnded ? new TransactionalRangePoint[]{
			new TransactionalRangePoint(Long.MIN_VALUE),
			new TransactionalRangePoint(10L, new int[]{1}, new int[0]),
			new TransactionalRangePoint(20L, new int[]{2}, new int[0]),
			new TransactionalRangePoint(LocalDateTime.MAX.atOffset(ZoneOffset.ofHours(2)).toEpochSecond(),
				new int[0], new int[]{2}),
			new TransactionalRangePoint(LocalDateTime.MAX.atOffset(ZoneOffset.UTC).toEpochSecond(),
				new int[0], new int[]{1}),
			new TransactionalRangePoint(Long.MAX_VALUE)
		} : new TransactionalRangePoint[]{
			new TransactionalRangePoint(Long.MIN_VALUE),
			new TransactionalRangePoint(10L, new int[]{1, 2}, new int[0]),
			new TransactionalRangePoint(20L, new int[0], new int[]{1}),
			new TransactionalRangePoint(21L, new int[0], new int[]{2}),
			new TransactionalRangePoint(Long.MAX_VALUE)
		};
		final AttributeKeyWithIndexType filterKey = new AttributeKeyWithIndexType(VALIDITY, AttributeIndexType.FILTER);
		if (rangePaged) {
			for (int i = 0; i < OLD_PAGES.length; i++) {
				final int from = i * points.length / OLD_PAGES.length;
				final int to = (i + 1) * points.length / OLD_PAGES.length;
				final TransactionalRangePoint[] page = Arrays.copyOfRange(points, from, to);
				parts.putStoragePart(version, histogram
					? new HistogramRangeIndexLeafPagePart(indexPk, "ranges", null, OLD_PAGES[i], page)
					: new RangeIndexLeafPagePart(indexPk, filterKey, OLD_PAGES[i], page));
			}
		}
		if (bucketPaged) {
			for (int i = 0; i < buckets.length; i++) {
				parts.putStoragePart(version, histogram
					? new HistogramIndexLeafPagePart(
						indexPk, "ranges", null, i + 1, new ValueToRecordBitmap[]{buckets[i]})
					: new FilterIndexLeafPagePart(
						indexPk, filterKey, i + 1, new ValueToRecordBitmap[]{buckets[i]}, null));
			}
		}
		final ValueToRecordBitmap[] inlineBuckets = bucketPaged ? new ValueToRecordBitmap[0] : buckets;
		final RangeIndex inline = rangePaged ? null : new RangeIndex(points);
		final int[] rangePages = rangePaged ? OLD_PAGES : new int[0];
		final int[] bucketPages = bucketPaged ? new int[]{1, 2} : new int[0];
		if (histogram) {
			final EntityIndexStoragePart manifest =
				parts.getStoragePart(version, indexPk, EntityIndexStoragePart.class);
			final Set<HistogramIndexStorageKey> histograms = Set.of(new HistogramIndexStorageKey(key, "ranges", null));
			parts.putStoragePart(version, new EntityIndexStoragePart(indexPk, manifest.getVersion(), key,
				manifest.getAttributeIndexes(), manifest.getPriceIndexes(), manifest.isHierarchyIndex(),
				manifest.getFacetIndexes(), histograms, manifest.getFulltextIndexes()));
			parts.putStoragePart(version, new HistogramIndexStoragePart(indexPk, "ranges", null, DateTimeRange.class,
				inlineBuckets, inline, 0, bucketPaged, bucketPaged ? 2 : -1, bucketPages,
				rangePaged, rangePaged ? HIGH_WATER : -1, rangePages, null));
		} else {
			parts.putStoragePart(version, new FilterIndexStoragePart(indexPk, VALIDITY, DateTimeRange.class,
				inlineBuckets, inline, 0, bucketPaged, bucketPaged ? 2 : -1, bucketPages,
				rangePaged, rangePaged ? HIGH_WATER : -1, rangePages, null));
		}
	}

	/** Verifies real persisted points, fresh page allocation and retirement of every old page after migration. */
	private void assertPersistedRange(EntityIndexKey key, int indexPk, boolean histogram, boolean rangePaged) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final long version = catalog.getVersion();
		final FilterIndexStoragePart filter = histogram ? null : collection().getDataStoreReader().fetch(version,
			AttributeIndexStoragePart.computeUniquePartId(indexPk, AttributeIndexType.FILTER, VALIDITY,
				collection().getStoragePartPersistenceService().getReadOnlyKeyCompressor()),
				FilterIndexStoragePart.class);
		final HistogramIndexStoragePart hist = histogram ? collection().getDataStoreReader().fetch(version,
			new HistogramIndexStorageKey(key, "ranges", null).computeUniquePartId(indexPk,
				collection().getStoragePartPersistenceService().getReadOnlyKeyCompressor()),
			HistogramIndexStoragePart.class) : null;
		assertFalse(histogram ? hist.isSecondGranularityRangePages() : filter.isSecondGranularityRangePages());
		if (rangePaged) {
			final int[] pages = histogram ? hist.getRangeLeafPageSequences() : filter.getRangeLeafPageSequences();
			assertTrue(pages.length > 0);
			for (int page : pages) {
				assertTrue(page > HIGH_WATER, "every replacement must exceed the persisted high-water");
			}
			final int streamId = histogram
				? collection().getStoragePartPersistenceService().getReadOnlyKeyCompressor().getId(
					new HistogramLeafStreamKey(indexPk, "ranges", null, HistogramLeafStreamKey.StreamKind.RANGE))
				: collection().getStoragePartPersistenceService().getReadOnlyKeyCompressor().getId(
					new LeafStreamKey(indexPk, new AttributeKeyWithIndexType(VALIDITY, AttributeIndexType.FILTER),
						LeafStreamKey.StreamKind.RANGE));
			for (int old : OLD_PAGES) {
				final long pk = AbstractLeafPagePart.computeUniquePartId(streamId, old);
				assertNull(histogram
					? collection().getDataStoreReader().fetch(version, pk, HistogramRangeIndexLeafPagePart.class)
					: collection().getDataStoreReader().fetch(version, pk, RangeIndexLeafPagePart.class));
			}
		} else {
			assertArrayEquals(EXPECTED, thresholds(histogram ? hist.getRangeIndex() : filter.getRangeIndex()));
		}
	}

	/** Builds ascending whole-second validity buckets for every owner of the multi-leaf fixture. */
	private static ValueToRecordBitmap[] denseBuckets() {
		final ValueToRecordBitmap[] buckets = new ValueToRecordBitmap[300];
		for (int owner = 1; owner <= 300; owner++) {
			buckets[owner - 1] = new ValueToRecordBitmap(denseValidity(owner), owner);
		}
		return buckets;
	}

	/** Reproduces the released second thresholds for 300 disjoint validities and both border points. */
	private static TransactionalRangePoint[] densePoints() {
		final TransactionalRangePoint[] points = new TransactionalRangePoint[602];
		points[0] = new TransactionalRangePoint(Long.MIN_VALUE);
		for (int owner = 1; owner <= 300; owner++) {
			points[owner * 2 - 1] = new TransactionalRangePoint(1_700_000_000L + 3_600L * owner,
				new int[]{owner}, new int[0]);
			points[owner * 2] = new TransactionalRangePoint(1_700_001_800L + 3_600L * owner,
				new int[0], new int[]{owner});
		}
		points[601] = new TransactionalRangePoint(Long.MAX_VALUE);
		return points;
	}

	/** Keeps a thirty-minute range halfway through each successive hour, leaving an observable gap. */
	private static DateTimeRange denseValidity(int owner) {
		final OffsetDateTime from = OffsetDateTime.parse("2023-11-14T22:13:20Z").plusSeconds(3_600L * owner);
		return DateTimeRange.between(from, from.plusSeconds(1_800L));
	}

	/** A current comparator reverses the first pair only in the combined-correction fixture. */
	private static DateTimeRange validity(int owner, boolean invert) {
		return DateTimeRange.between(OffsetDateTime.parse("1970-01-01T00:00:10Z")
			.plusNanos(invert ? (owner == 1 ? 500_000_000L : 200_000_000L) : 0),
			OffsetDateTime.parse("1970-01-01T00:00:19Z").plusSeconds(owner));
	}

	/** Extracts concrete thresholds rather than comparing against another conversion implementation. */
	private static long[] thresholds(RangeIndex index) {
		return Arrays.stream(index.getRanges()).mapToLong(RangePoint::getThreshold).toArray();
	}

	/** Opens the protocol-6 catalog through the ordinary engine migration entry point. */
	private void openMigrated() {
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	/** Returns this fixture's storage configuration. */
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/** Resolves the loaded collection through the real catalog. */
	private EntityCollection collection() {
		return (EntityCollection) ((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
	}
}
