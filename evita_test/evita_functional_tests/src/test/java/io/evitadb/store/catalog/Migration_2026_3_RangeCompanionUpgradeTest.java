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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.migrateOffline;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises protocol-6 range companions through the migration planner, publication and a second offline open.
 * Synthetic parts model inherited decimal thresholds separately from native release thresholds. Aggregate rows
 * carry partition ids in type indexes and referenced primary keys in reduced group indexes, never owner ids.
 */
@Tag(STORAGE)
@Tag(INDEXING)
@DisplayName("Migration_2026_3 — range companions reconstructed from stored bucket membership")
class Migration_2026_3_RangeCompanionUpgradeTest implements EvitaTestSupport {
	/** Opens isolated engines used only to seed real schemas and index manifests. */
	@Nonnull
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/**
	 * Tests the released decimal encodings with strictly ascending raw and normalized keys, before any index load.
	 * The inherited companion retains the bounds' own scales; the native control uses the frozen index scale.
	 */
	@ParameterizedTest
	@CsvSource({"true,false", "true,true", "false,false", "false,true"})
	@DisplayName("Should publish decimal thresholds at the frozen scale even when bucket keys already ascend")
	void shouldPublishAscendingDecimalThresholds(boolean inherited, boolean paged) {
		final List<ValueToRecordBitmap> buckets = new ArrayList<>();
		buckets.add(new ValueToRecordBitmap(decimal("1", "2"), 1));
		final BigDecimalNumberRange supplied = decimal("3.0", "4.0");
		assertEquals(30, supplied.getFrom());
		final BigDecimalNumberRange nativeKey = (BigDecimalNumberRange)
			FilterIndex.getNormalizer(BigDecimalNumberRange.class, 0).apply(supplied);
		buckets.add(new ValueToRecordBitmap(inherited ? supplied : nativeKey, 2));
		if (paged) {
			for (int i = 0; i < 400; i++) {
				buckets.add(new ValueToRecordBitmap(decimal("" + (100 + 10 * i), "" + (105 + 10 * i)), i + 4));
			}
		}
		buckets.add(new ValueToRecordBitmap(decimal("10000", "10005"), 3));
		final RangeIndex companion = new RangeIndex();
		BigDecimalNumberRange previousRaw = null;
		BigDecimalNumberRange previousNormalized = null;
		for (final ValueToRecordBitmap bucket : buckets) {
			final BigDecimalNumberRange raw = (BigDecimalNumberRange) bucket.getValue();
			final BigDecimalNumberRange normalized = (BigDecimalNumberRange)
				FilterIndex.getNormalizer(BigDecimalNumberRange.class, 0).apply(raw);
			assertTrue(previousRaw == null || previousRaw.compareTo(raw) < 0);
			assertTrue(previousNormalized == null || previousNormalized.compareTo(normalized) < 0);
			previousRaw = raw;
			previousNormalized = normalized;
			final BigDecimalNumberRange encoded = inherited ? raw : normalized;
			companion.addRecord(encoded.getFrom(), encoded.getTo(), bucket.getRecordIds().getFirst());
		}
		assertEquals(inherited ? 30 : 3, ((BigDecimalNumberRange) buckets.get(1).getValue()).getFrom());
		assertArrayEquals(inherited ? new int[0] : new int[]{2}, at(companion, 4));
		assertArrayEquals(inherited ? new int[]{2} : new int[0], at(companion, 35));
		assertEquals(paged, companion.isPaged());
		withImage(EntityIndexType.GLOBAL, Scope.LIVE, BigDecimalNumberRange.class, false, null,
			buckets, companion, paged, paged, image -> {
				image.read(root -> {
					assertEquals(paged, root.root().isPaged());
					assertEquals(paged, root.root().isRangePaged());
					assertArrayEquals(inherited ? new int[0] : new int[]{2}, at(root.companion(), 4));
					assertArrayEquals(inherited ? new int[]{2} : new int[0], at(root.companion(), 35));
					root.assertBucketsUnchanged(false);
				});
				image.migrateAndInspect(root -> {
					assertArrayEquals(new int[]{2}, at(root.companion(), 4),
						"The published companion must find the inherited decimal interval at scale zero");
					assertArrayEquals(new int[]{2},
						root.companion().getRecordsWithRangesOverlapping(3, 4).compute().getArray());
					assertArrayEquals(new int[]{1}, at(root.companion(), 1));
					assertArrayEquals(new int[0], at(root.companion(), 50));
					root.assertBucketsUnchanged(inherited);
				});
			});
	}

	/**
	 * Covers every range subtype, affected index family and independent bucket/range axis combination. Alternate
	 * cases carry array declarations, localized keys and archived scope without changing the stored record identity.
	 */
	@ParameterizedTest
	@MethodSource("aggregateCases")
	@DisplayName("Should consolidate each aggregate record's intervals while preserving its bucket axis")
	void shouldConsolidateAggregateIntervals(
		@Nonnull EntityIndexType family, @Nonnull Bounds bounds, boolean bucketPaged, boolean rangePaged
	) {
		final int record = family == EntityIndexType.REFERENCED_GROUP_ENTITY ? 17 : 701;
		final List<ValueToRecordBitmap> buckets = new ArrayList<>();
		for (final int[] interval : new int[][]{{0, 20}, {0, 30}, {25, 40}, {40, 45}, {60, 70}}) {
			buckets.add(new ValueToRecordBitmap(bounds.range(interval[0], interval[1]), record));
		}
		buckets.get(0).addRecord(record + 1);
		buckets.add(new ValueToRecordBitmap(bounds.range(5, 30), record + 2));
		buckets.add(new ValueToRecordBitmap(bounds.range(10, 30), record + 2));
		if (rangePaged && bounds != Bounds.BYTE) {
			for (int i = 0; i < 400; i++) {
				buckets.add(new ValueToRecordBitmap(bounds.range(100 + 10 * i, 105 + 10 * i), record + 100 + i));
			}
		}
		buckets.sort(Comparator.comparingLong((ValueToRecordBitmap bucket) -> ((Range<?>) bucket.getValue()).getFrom())
			.thenComparingLong(bucket -> ((Range<?>) bucket.getValue()).getTo()));
		final RangeIndex companion = new RangeIndex();
		for (final ValueToRecordBitmap bucket : buckets) {
			final Range<?> range = (Range<?>) bucket.getValue();
			for (final int id : bucket.getRecordIds().getArray()) {
				companion.addRecord(range.getFrom(), range.getTo(), id);
			}
		}
		final boolean array = bucketPaged;
		final Scope scope = rangePaged ? Scope.ARCHIVED : Scope.LIVE;
		withImage(family, scope, bounds.type(), array, bucketPaged ? Locale.ENGLISH : null,
			buckets, companion, bucketPaged, rangePaged, image -> {
				image.migrateAndInspect(root -> {
					assertArrayEquals(new int[]{record}, at(root.companion(), bounds.threshold(35)),
						"Overlapping contributions of one aggregate record must retain their complete union");
					assertArrayEquals(new int[]{record, record + 1, record + 2},
						at(root.companion(), bounds.threshold(10)));
					assertArrayEquals(new int[]{record}, at(root.companion(), bounds.threshold(65)));
					assertArrayEquals(new int[0], at(root.companion(), bounds.threshold(50)));
					assertArrayEquals(new int[0], at(root.companion(), bounds.threshold(75)));
					assertEndpoints(root.companion(), record,
						new long[]{bounds.threshold(0), bounds.threshold(60)},
						new long[]{bounds.threshold(45), bounds.threshold(70)});
					assertEndpoints(root.companion(), record + 2, new long[]{bounds.threshold(5)},
						new long[]{bounds.threshold(30)});
					assertEquals(rangePaged && bounds != Bounds.BYTE, root.root().isRangePaged());
					root.assertBucketsUnchanged(true);
				});
			});
	}

	/** Checks released shared-start rows and temporal seconds provenance before index load. */
	@ParameterizedTest
	@ValueSource(strings = {"span", "validity"})
	@DisplayName("Should publish the longer shared-start interval from surviving released bucket rows")
	void shouldPublishReleasedSharedStartIntervals(@Nonnull String attribute) {
		final TestPaths paths = this.harness.copy("dRng");
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			for (int open = 0; open < 2; open++) {
				withOfflineCatalog(configuration, "dRng", service -> {
					final long version = service.getLastCatalogVersion();
					final var reference = Objects.requireNonNull(service.getStoragePartPersistenceService(version)
						.getCatalogHeader(version).collectionFileIndex().get("product"));
					final var collection = service.getOrCreateEntityCollectionPersistenceService(version, "product",
						reference.entityTypePrimaryKey());
					final var parts = collection.getStoragePartPersistenceService();
					final EntityIndexStoragePart manifest = collection.getEntityCollectionHeader()
						.usedEntityIndexPrimaryKeys().stream().map(id -> parts.getStoragePart(version, id,
							EntityIndexStoragePart.class)).filter(Objects::nonNull)
						.filter(it -> it.getEntityIndexKey().type() == EntityIndexType.REFERENCED_ENTITY_TYPE)
						.findFirst().orElseThrow();
					final AttributeIndexKey key = manifest.getAttributeIndexes().stream()
						.filter(it -> it.indexType() == AttributeIndexType.FILTER)
						.map(AttributeIndexStorageKey::attribute).filter(it -> it.attributeName().equals(attribute))
						.findFirst().orElseThrow();
					final FilterIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(version,
						AttributeIndexStoragePart.computeUniquePartId(
							manifest.getPrimaryKey(), AttributeIndexType.FILTER,
							key, parts.getReadOnlyKeyCompressor()), FilterIndexStoragePart.class));
					assertFalse(root.isPaged());
					assertEquals(2, root.getHistogramPoints().length);
					final int record = root.getHistogramPoints()[0].getRecordIds().getFirst();
					assertArrayEquals(new int[]{record}, root.getHistogramPoints()[1].getRecordIds().getArray());
					final Image image = new Image(configuration, manifest.getPrimaryKey(), root,
						root.getHistogramPoints(), new int[0]);
					final long point = attribute.equals("span") ? 25 :
						OffsetDateTime.parse("2026-01-01T00:00:02.500Z").toInstant().toEpochMilli();
					assertArrayEquals(new int[]{record}, at(new Stored(image, parts, version, root).companion(), point),
						"The published release companion must retain the longer interval of its partition");
					assertEndpoints(new Stored(image, parts, version, root).companion(), record,
						new long[]{((Range<?>) root.getHistogramPoints()[0].getValue()).getFrom()},
						new long[]{Arrays.stream(root.getHistogramPoints())
							.mapToLong(bucket -> ((Range<?>) bucket.getValue()).getTo()).max().orElseThrow()});
					assertFalse(root.isSecondGranularityRangeThresholds());
					return Boolean.TRUE;
				});
			}
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** Supplies all independent persisted axes without assuming either tree's storage mode determines the other's. */
	@Nonnull
	private static Stream<Arguments> aggregateCases() {
		return Stream.of(EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE,
			EntityIndexType.REFERENCED_GROUP_ENTITY).flatMap(family -> Arrays.stream(Bounds.values())
			.flatMap(bounds -> Stream.of(false, true).flatMap(bucket -> Stream.of(false, true)
				.map(range -> Arguments.of(family, bounds, bucket, range)))));
	}

	/** Builds a decimal value without discarding the precision supplied by the released caller. */
	@Nonnull
	private static BigDecimalNumberRange decimal(@Nonnull String from, @Nonnull String to) {
		return BigDecimalNumberRange.between(new BigDecimal(from), new BigDecimal(to));
	}

	/** Evaluates the companion directly, avoiding any query alternative that fetches entity attributes. */
	@Nonnull
	private static int[] at(@Nonnull RangeIndex companion, long threshold) {
		return companion.getRecordsEnvelopingInclusive(threshold).compute().getArray();
	}

	/** Requires exactly the consolidated starts and ends for one stored record, including disjoint intervals. */
	private static void assertEndpoints(
		@Nonnull RangeIndex companion, int record, @Nonnull long[] expectedStarts, @Nonnull long[] expectedEnds
	) {
		final List<Long> starts = new ArrayList<>();
		final List<Long> ends = new ArrayList<>();
		companion.rangesIterator().forEachRemaining(point -> {
			if (point.getStarts().contains(record)) {
				starts.add(point.getThreshold());
			}
			if (point.getEnds().contains(record)) {
				ends.add(point.getThreshold());
			}
		});
		assertArrayEquals(expectedStarts, starts.stream().mapToLong(Long::longValue).toArray());
		assertArrayEquals(expectedEnds, ends.stream().mapToLong(Long::longValue).toArray());
	}

	/** Creates a real grouped-reference catalog, then replaces only the selected persisted filter and manifest. */
	private void withImage(
		@Nonnull EntityIndexType family, @Nonnull Scope scope, @Nonnull Class<?> type, boolean array,
		@Nullable Locale locale, @Nonnull List<ValueToRecordBitmap> buckets,
		@Nonnull RangeIndex companion, boolean bucketPaged, boolean rangePaged, @Nonnull Consumer<Image> test
	) {
		final TestPaths paths = createTestPaths("companion_" + family + scope + type.getSimpleName() +
			array + bucketPaged + rangePaged);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try {
			try (final Evita evita = this.harness.boot(paths)) {
				evita.defineCatalog("ranges");
				try (final EvitaSessionContract session = evita.createReadWriteSession("ranges")) {
					session.defineEntitySchema("brand").updateVia(session);
					session.defineEntitySchema("group").updateVia(session);
					session.defineEntitySchema("product")
						.withAttribute("range", BigDecimalNumberRange.class,
							it -> it.filterable().indexDecimalPlaces(0))
						.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, it -> it
							.withGroupTypeRelatedToEntity("group").indexedForFilteringAndPartitioning()
							.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
							.withAttribute("span", IntegerNumberRange.class, attr -> attr.filterable()))
						.updateVia(session);
					session.createNewEntity("brand", 17).upsertVia(session);
					session.createNewEntity("group", 10).upsertVia(session);
					for (int owner = 1; owner <= 2; owner++) {
						session.createNewEntity("product", owner).setAttribute("range", decimal("1", "2"))
							.setReference("brand", 17, ref -> ref.setGroup(10)
								.setAttribute("span", IntegerNumberRange.between(0, 20)))
							.upsertVia(session);
					}
					session.goLiveAndClose();
				}
			}
			final Image image = withOfflineCatalog(configuration, "ranges", service -> {
				final long version = service.getLastCatalogVersion();
				final var catalogParts = service.getStoragePartPersistenceService(version);
				final var header = catalogParts.getCatalogHeader(version);
				final CollectionFileReference reference =
					Objects.requireNonNull(header.collectionFileIndex().get("product"));
				final var collection = service.getOrCreateEntityCollectionPersistenceService(version, "product",
					reference.entityTypePrimaryKey());
				final var parts = collection.getStoragePartPersistenceService();
				final List<Integer> ids =
					new ArrayList<>(collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys());
				ids.add(Objects.requireNonNull(collection.getEntityCollectionHeader().globalEntityIndexPrimaryKey()));
				final EntityIndexStoragePart manifest = ids.stream().map(id -> parts.getStoragePart(version, id,
					EntityIndexStoragePart.class)).filter(Objects::nonNull)
					.filter(it -> it.getEntityIndexKey().type() == family).findFirst().orElseThrow();
				final int index = manifest.getPrimaryKey();
				final AttributeIndexKey key = new AttributeIndexKey(null, family == EntityIndexType.GLOBAL ?
					"range" : "span", locale);
				final AttributeKeyWithIndexType streamKey =
					new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER);
				parts.putStoragePart(version, new EntityIndexStoragePart(index, manifest.getVersion(),
					new EntityIndexKey(family, scope, manifest.getEntityIndexKey().discriminator()),
					Set.of(new AttributeIndexStorageKey(new EntityIndexKey(family, scope,
						manifest.getEntityIndexKey().discriminator()), AttributeIndexType.FILTER, key)),
					manifest.getPriceIndexes(),
					manifest.isHierarchyIndex(), manifest.getFacetIndexes(), manifest.getHistogramIndexes()));
				final int[] bucketSequences = bucketPaged ? new int[]{3, 9} : new int[0];
				final int[] valueIds = java.util.stream.IntStream.range(100, 100 + buckets.size()).toArray();
				final ValueToRecordBitmap[] rows = buckets.toArray(ValueToRecordBitmap[]::new);
				if (bucketPaged) {
					final int middle = rows.length / 2;
					parts.putStoragePart(version, new FilterIndexLeafPagePart(index, streamKey, 3,
						Arrays.copyOfRange(rows, 0, middle), Arrays.copyOfRange(valueIds, 0, middle)));
					parts.putStoragePart(version, new FilterIndexLeafPagePart(index, streamKey, 9,
						Arrays.copyOfRange(rows, middle, rows.length),
						Arrays.copyOfRange(valueIds, middle, rows.length)));
				}
				final int[] rangeSequences;
				if (rangePaged) {
					final List<TransactionalRangePoint> points = new ArrayList<>();
					companion.collectChangedPages().changedPages()
						.forEach(page -> points.addAll(List.of(page.points())));
					final int middle = points.size() / 2;
					parts.putStoragePart(version, new RangeIndexLeafPagePart(index, streamKey, 12,
						points.subList(0, middle).toArray(TransactionalRangePoint[]::new)));
					parts.putStoragePart(version, new RangeIndexLeafPagePart(index, streamKey, 19,
						points.subList(middle, points.size()).toArray(TransactionalRangePoint[]::new)));
					rangeSequences = new int[]{12, 19};
				} else {
					rangeSequences = new int[0];
				}
				final Class<?> declaredType = array ? Array.newInstance(type, 0).getClass() : type;
				final FilterIndexStoragePart root = new FilterIndexStoragePart(index, key, declaredType,
					bucketPaged ? new ValueToRecordBitmap[0] : rows, rangePaged ? null : companion, 0,
					bucketPaged, bucketPaged ? 25 : -1, bucketSequences, rangePaged, rangePaged ? 37 : -1,
					rangeSequences, 1000, bucketPaged ? null : valueIds, null);
				parts.putStoragePart(version, root);
				final var descriptor = collection.flush(version,
					new NoChangeHeaderInfoSupplier(collection.getEntityCollectionHeader()));
				catalogParts.putStoragePart(version, collection.getEntityCollectionHeader());
				final var references = new HashMap<>(header.collectionFileIndex());
				references.put("product", new CollectionFileReference("product", reference.entityTypePrimaryKey(),
					reference.fileIndex(), descriptor.fileLocation()));
				catalogParts.writeCatalogHeader(
					header.storageProtocolVersion(), version, service.getCatalogStoragePath(),
					header.walFileReference(), references, header.catalogId(), header.catalogName(),
					header.catalogState(),
					header.lastEntityCollectionPrimaryKey());
				service.recordBootstrap(version, "ranges",
					lastBootstrap(configuration, "ranges").catalogFileIndex(), null);
				return new Image(configuration, index, root, rows, valueIds);
			});
			test.accept(image);
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** A synthetic persisted filter and the real catalog services that migrate and publish it. */
	private record Image(
		@Nonnull EvitaConfiguration configuration, int index, @Nonnull FilterIndexStoragePart original,
		@Nonnull ValueToRecordBitmap[] rows, @Nonnull int[] valueIds
	) {
		/** Verifies failure before publication preserves the old chain, then publishes and rereads the replacement. */
		private void migrateAndInspect(@Nonnull Consumer<Stored> inspect) {
			final RangeIndex originalRange = originalCompanion();
			final var before = lastBootstrap(this.configuration, "ranges");
			assertThrows(InterruptedPublication.class, () -> migrateOffline(this.configuration, "ranges", header -> {
				throw new InterruptedPublication();
			}, new HashMap<>()));
			assertEquals(before, lastBootstrap(this.configuration, "ranges"));
			read(stored -> {
				assertArrayEquals(this.original.getRangeLeafPageSequences(), stored.root().getRangeLeafPageSequences());
				assertArrayEquals(at(originalRange, 35), at(stored.companion(), 35));
			});
			stamp(this.configuration, "ranges", 6, 6);
			read(inspect);
			final var published = lastBootstrap(this.configuration, "ranges");
			assertEquals(7, published.storageProtocolVersion());
			read(inspect);
			assertEquals(published, lastBootstrap(this.configuration, "ranges"));
		}

		/** Reopens the original companion through its still-published root after an unpublished attempt. */
		@Nonnull
		private RangeIndex originalCompanion() {
			final RangeIndex[] result = new RangeIndex[1];
			read(stored -> result[0] = stored.companion());
			return Objects.requireNonNull(result[0]);
		}

		/** Reads only manifests and filter parts through the currently published collection reference. */
		private void read(@Nonnull Consumer<Stored> inspect) {
			withOfflineCatalog(this.configuration, "ranges", service -> {
				final long version = service.getLastCatalogVersion();
				final var reference = service.getStoragePartPersistenceService(version).getCatalogHeader(version)
					.collectionFileIndex().get("product");
				final var parts = service.getOrCreateEntityCollectionPersistenceService(version, "product",
					Objects.requireNonNull(reference).entityTypePrimaryKey()).getStoragePartPersistenceService();
				final FilterIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(version,
					AttributeIndexStoragePart.computeUniquePartId(this.index, AttributeIndexType.FILTER,
						this.original.getAttributeIndexKey(), parts.getReadOnlyKeyCompressor()),
					FilterIndexStoragePart.class));
				inspect.accept(new Stored(this, parts, version, root));
				return Boolean.TRUE;
			});
		}
	}

	/** Provides direct assertions against the published root, its bucket pages and its reconstructed companion. */
	private record Stored(
		@Nonnull Image image, @Nonnull OffsetIndexStoragePartPersistenceService parts, long version,
		@Nonnull FilterIndexStoragePart root
	) {
		/** Loads the companion alone so no entity-body fallback can conceal incorrect thresholds. */
		@Nonnull
		private RangeIndex companion() {
			if (!this.root.isRangePaged()) {
				return Objects.requireNonNull(this.root.getRangeIndex());
			}
			final int stream = this.parts.getReadOnlyKeyCompressor().getId(new LeafStreamKey(this.image.index(),
				new AttributeKeyWithIndexType(this.root.getAttributeIndexKey(), AttributeIndexType.FILTER),
				LeafStreamKey.StreamKind.RANGE));
			final int[] sequences = this.root.getRangeLeafPageSequences();
			final TransactionalRangePoint[][] points = new TransactionalRangePoint[sequences.length][];
			for (int i = 0; i < sequences.length; i++) {
				points[i] = Objects.requireNonNull(this.parts.getStoragePart(this.version,
					AbstractLeafPagePart.computeUniquePartId(stream, sequences[i]),
					RangeIndexLeafPagePart.class)).getPoints();
			}
			return RangeIndex.fromPersistedPages("published range companion", sequences, points,
				this.root.getRangeHighWaterPageSequence());
		}

		/** Checks all bucket metadata and memberships, plus retirement and freshness of the independent range axis. */
		private void assertBucketsUnchanged(boolean companionRewritten) {
			final FilterIndexStoragePart original = this.image.original();
			assertEquals(original.getStoragePartPK(), this.root.getStoragePartPK());
			assertEquals(original.getAttributeIndexKey(), this.root.getAttributeIndexKey());
			assertEquals(original.getAttributeType(), this.root.getAttributeType());
			assertEquals(original.isPaged(), this.root.isPaged());
			assertEquals(original.getHighWaterPageSequence(), this.root.getHighWaterPageSequence());
			assertEquals(original.getNextValueId(), this.root.getNextValueId());
			assertEquals(original.getIndexedDecimalPlaces(), this.root.getIndexedDecimalPlaces());
			assertArrayEquals(original.getLeafPageSequences(), this.root.getLeafPageSequences());
			assertArrayEquals(original.getInlineValueIds(), this.root.getInlineValueIds());
			assertFalse(this.root.isSecondGranularityRangeThresholds());
			final List<ValueToRecord> actual = new ArrayList<>();
			final List<Integer> ids = new ArrayList<>();
			if (this.root.isPaged()) {
				final int stream = this.parts.getReadOnlyKeyCompressor().getId(new LeafStreamKey(this.image.index(),
					new AttributeKeyWithIndexType(this.root.getAttributeIndexKey(), AttributeIndexType.FILTER)));
				for (final int sequence : this.root.getLeafPageSequences()) {
					final FilterIndexLeafPagePart page = Objects.requireNonNull(this.parts.getStoragePart(this.version,
						AbstractLeafPagePart.computeUniquePartId(stream, sequence), FilterIndexLeafPagePart.class));
					actual.addAll(List.of(page.getBuckets()));
					Arrays.stream(Objects.requireNonNull(page.getValueIds())).forEach(ids::add);
				}
				assertArrayEquals(this.image.valueIds(), ids.stream().mapToInt(Integer::intValue).toArray());
			} else {
				actual.addAll(List.of(this.root.getHistogramPoints()));
			}
			assertEquals(this.image.rows().length, actual.size());
			for (int i = 0; i < actual.size(); i++) {
				assertEquals(this.image.rows()[i].getValue(), actual.get(i).getValue());
				assertArrayEquals(this.image.rows()[i].getRecordIds().getArray(),
					actual.get(i).getRecordIds().getArray());
			}
			if (companionRewritten && original.isRangePaged()) {
				final int stream = this.parts.getReadOnlyKeyCompressor().getId(new LeafStreamKey(this.image.index(),
					new AttributeKeyWithIndexType(this.root.getAttributeIndexKey(), AttributeIndexType.FILTER),
					LeafStreamKey.StreamKind.RANGE));
				for (final int sequence : original.getRangeLeafPageSequences()) {
					assertNull(this.parts.getStoragePart(this.version, AbstractLeafPagePart.computeUniquePartId(stream,
						sequence), RangeIndexLeafPagePart.class), "Old range pages must leave the new offset index");
				}
				for (final int sequence : this.root.getRangeLeafPageSequences()) {
					assertTrue(sequence > original.getRangeHighWaterPageSequence());
				}
			}
		}
	}

	/** Signals a failure at the existing bootstrap callback, after collection appends are already possible. */
	private static final class InterruptedPublication extends RuntimeException {
		/** Identifies this test-only exception if it is serialized by a test runner. */
		@Serial private static final long serialVersionUID = 1L;
	}

	/** Supplies every scalar range subtype independently of the root's array declaration. */
	private enum Bounds {
		/** Byte endpoints. */
		BYTE,
		/** Short endpoints. */
		SHORT,
		/** Integer endpoints. */
		INTEGER,
		/** Long endpoints. */
		LONG,
		/** Decimal endpoints whose supplied precision differs from the index's frozen scale. */
		DECIMAL,
		/** Temporal endpoints decoded as current millisecond values. */
		TIME;

		/** Constructs one interval from the precise values stored in a bucket. */
		@Nonnull
		private Range<?> range(int from, int to) {
			return switch (this) {
				case BYTE -> ByteNumberRange.between((byte) from, (byte) to);
				case SHORT -> ShortNumberRange.between((short) from, (short) to);
				case INTEGER -> IntegerNumberRange.between(from, to);
				case LONG -> LongNumberRange.between((long) from, (long) to);
				case DECIMAL -> decimal(from + ".0", to + ".0");
				case TIME -> DateTimeRange.between(OffsetDateTime.parse("2026-01-01T00:00:00Z").plusSeconds(from),
					OffsetDateTime.parse("2026-01-01T00:00:00Z").plusSeconds(to));
			};
		}

		/** Returns the scalar declared type carried by the root. */
		@Nonnull
		private Class<?> type() {
			return range(0, 1).getClass();
		}

		/** Encodes a query point in the current frozen threshold space. */
		private long threshold(int point) {
			return this == TIME ? OffsetDateTime.parse("2026-01-01T00:00:00Z").plusSeconds(point)
				.toInstant().toEpochMilli() : point;
		}
	}
}
