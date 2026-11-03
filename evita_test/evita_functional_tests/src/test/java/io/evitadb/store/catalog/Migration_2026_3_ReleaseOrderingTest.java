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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.LegacyDecimalRangeFilterIndexCorrection;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inspects migration output before any entity index is loaded. These release fixtures must be corrected in storage,
 * so a successful engine query alone cannot substitute for checking the published root and its leaf pages.
 */
@DisplayName("Migration_2026_3 — index parts written by 2026.2 are published in the current order (v6→v7)")
@Tag(STORAGE)
@Tag(INDEXING)
class Migration_2026_3_ReleaseOrderingTest implements EvitaTestSupport {
	/** Copies release catalogs into isolated test directories. */
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/** Verifies today-equal sort values are published as one block containing every released record exactly once. */
	@DisplayName("Should publish today-equal sort values as one block holding every released record once")
	@ParameterizedTest
	@ValueSource(strings = {"d1b", "d1b2"})
	void shouldPersistMergedSortBlocks(@Nonnull String fixture) {
		withMigratedFixture(fixture, service -> {
			final long version = service.getLastCatalogVersion();
			final DefaultEntityCollectionPersistenceService collection = product(service, version);
			final int index = Objects.requireNonNull(
				collection.getEntityCollectionHeader().globalEntityIndexPrimaryKey()
			);
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			final SortIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(
				version, AttributeIndexStoragePart.computeUniquePartId(
					index, AttributeIndexType.SORT, new AttributeIndexKey(null, "sortValidity", null),
					parts.getReadOnlyKeyCompressor()
				), SortIndexStoragePart.class
			));
			final int count = fixture.equals("d1b") ? 5 : 4;
			assertEquals(1, root.getSortedRecordsValues().length);
			assertArrayEquals(new int[]{count}, root.getCardinalities());
			assertArrayEquals(IntStream.rangeClosed(1, count).toArray(), root.getSortedRecords());
		});
	}

	/** Verifies the published catalog unique pages ascend in the current range order and retain every owner. */
	@DisplayName("Should publish catalog unique pages in the current range order with every owner")
	@Test
	void shouldPersistGlobalUniquePagesInCurrentOrder() {
		withMigratedFixture("d9", service -> {
			final long version = service.getLastCatalogVersion();
			final CatalogOffsetIndexStoragePartPersistenceService parts =
				service.getStoragePartPersistenceService(version);
			final AttributeKey key = new AttributeKey("period");
			final GlobalUniqueIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(
				version, GlobalUniqueIndexStoragePart.computeUniquePartId(
					Scope.LIVE, key, parts.getReadOnlyKeyCompressor()
				), GlobalUniqueIndexStoragePart.class
			));
			assertTrue(root.isPaged());
			final int stream = parts.getReadOnlyKeyCompressor().getId(new GlobalUniqueLeafStreamKey(Scope.LIVE, key));
			final List<Serializable> values = new ArrayList<>();
			for (final int sequence : root.getLeafPageSequences()) {
				final GlobalUniqueIndexLeafPagePart page = Objects.requireNonNull(parts.getStoragePart(
					version, GlobalUniqueIndexLeafPagePart.computeUniquePartId(stream, sequence),
					GlobalUniqueIndexLeafPagePart.class
				));
				assertEquals(page.getValues().length, page.getPayloads().length);
				values.addAll(List.of(page.getValues()));
			}
			assertRangeOrder(values);
		});
	}

	/** Verifies the localized standalone owner unique pages receive the same correction as catalog unique pages. */
	@DisplayName("Should correct localized owner unique pages as it corrects catalog unique pages")
	@Test
	void shouldPersistOwnerUniquePagesInCurrentOrder() {
		withMigratedFixture("d9o", service -> {
			final long version = service.getLastCatalogVersion();
			final DefaultEntityCollectionPersistenceService collection = product(service, version);
			final int index = Objects.requireNonNull(
				collection.getEntityCollectionHeader().globalEntityIndexPrimaryKey()
			);
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			final AttributeIndexKey key = new AttributeIndexKey(null, "period", null);
			final UniqueIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(
				version, AttributeIndexStoragePart.computeUniquePartId(
					index, AttributeIndexType.UNIQUE, key, parts.getReadOnlyKeyCompressor()
				), UniqueIndexStoragePart.class
			));
			assertTrue(root.isPaged());
			final int stream = parts.getReadOnlyKeyCompressor().getId(
				new LeafStreamKey(index, new AttributeKeyWithIndexType(key, AttributeIndexType.UNIQUE))
			);
			final List<Serializable> values = new ArrayList<>();
			final TreeSet<Integer> owners = new TreeSet<>();
			for (final int sequence : root.getLeafPageSequences()) {
				final UniqueIndexLeafPagePart page = Objects.requireNonNull(parts.getStoragePart(
					version, AbstractLeafPagePart.computeUniquePartId(stream, sequence), UniqueIndexLeafPagePart.class
				));
				values.addAll(List.of(page.getValues()));
				for (final int owner : page.getRecordIds()) {
					assertTrue(owners.add(owner));
				}
			}
			assertEquals(300, owners.size());
			assertRangeOrder(values);
		});
	}

	/** Verifies the decimal-range bucket pages are normalized and strictly ascending before the loader sees them. */
	@DisplayName("Should publish decimal-range bucket pages normalized and strictly ascending")
	@Test
	void shouldPersistDecimalRangeFilterPagesInCurrentOrder() {
		withMigratedFixture("d13", service -> {
			final long version = service.getLastCatalogVersion();
			final DefaultEntityCollectionPersistenceService collection = product(service, version);
			final int index = Objects.requireNonNull(
				collection.getEntityCollectionHeader().globalEntityIndexPrimaryKey()
			);
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			final AttributeIndexKey key = new AttributeIndexKey(null, "range", null);
			final FilterIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(
				version, AttributeIndexStoragePart.computeUniquePartId(
					index, AttributeIndexType.FILTER, key, parts.getReadOnlyKeyCompressor()
				), FilterIndexStoragePart.class
			));
			assertTrue(root.isPaged());
			final int stream = parts.getReadOnlyKeyCompressor().getId(
				new LeafStreamKey(index, new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER))
			);
			BigDecimalNumberRange previous = null;
			final TreeSet<Integer> records = new TreeSet<>();
			for (final int sequence : root.getLeafPageSequences()) {
				final FilterIndexLeafPagePart page = Objects.requireNonNull(parts.getStoragePart(
					version, AbstractLeafPagePart.computeUniquePartId(stream, sequence), FilterIndexLeafPagePart.class
				));
				for (final ValueToRecord bucket : page.getBuckets()) {
					final BigDecimalNumberRange value = (BigDecimalNumberRange) FilterIndex.getNormalizer(
						BigDecimalNumberRange.class, root.getIndexedDecimalPlaces()
					).apply(bucket.getValue());
					assertTrue(previous == null || previous.compareTo(value) < 0, "stored keys must ascend strictly");
					previous = value;
					for (final int record : bucket.getRecordIds().getArray()) {
						assertTrue(records.add(record));
					}
				}
			}
			assertEquals(333, records.size());
			final RangeIndex companion;
			if (root.isRangePaged()) {
				final int rangeStream = parts.getReadOnlyKeyCompressor().getId(new LeafStreamKey(
					index, new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER), LeafStreamKey.StreamKind.RANGE
				));
				final int[] sequences = root.getRangeLeafPageSequences();
				final TransactionalRangePoint[][] points = new TransactionalRangePoint[sequences.length][];
				for (int i = 0; i < sequences.length; i++) {
					points[i] = Objects.requireNonNull(parts.getStoragePart(
						version, AbstractLeafPagePart.computeUniquePartId(rangeStream, sequences[i]),
						RangeIndexLeafPagePart.class
					)).getPoints();
				}
				companion = RangeIndex.fromPersistedPages(
					"decimal range filter", sequences, points, root.getRangeHighWaterPageSequence()
				);
			} else {
				companion = Objects.requireNonNull(root.getRangeIndex());
			}
			assertArrayEquals(new int[]{301, 302, 303},
				companion.getRecordsEnvelopingInclusive(2).compute().getArray());
			assertArrayEquals(new int[]{1, 310, 311, 312, 313},
				companion.getRecordsEnvelopingInclusive(12).compute().getArray());
		});
	}

	/** Verifies bucket merging preserves records and ids, and rebuilds thresholds at the frozen scale. */
	@DisplayName("Should merge rescaled buckets across pages, keep records and ids, and rebuild thresholds")
	@Test
	void shouldMergeRescaledBucketsAcrossPageBoundaries() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "range", null);
		final AttributeKeyWithIndexType streamKey = new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER);
		final FilterIndexStoragePart root = new FilterIndexStoragePart(
			1, key, BigDecimalNumberRange.class, new ValueToRecordBitmap[0], null, 0, true, 9,
			new int[]{3, 9}, true, 11, new int[]{10, 11}, 10, null, 17L
		);
		final FilterIndexLeafPagePart[] pages = {
			new FilterIndexLeafPagePart(1, streamKey, 3, new ValueToRecord[]{
				new ValueToRecordBitmap(range("10", "15"), 1),
				new ValueToRecordBitmap(range("1.2", "9.2"), 2)
			}, new int[]{7, 8}),
			new FilterIndexLeafPagePart(1, streamKey, 9, new ValueToRecord[]{
				new ValueToRecordBitmap(range("1.4", "9.4"), 3)
			}, new int[]{9})
		};
		final List<StoragePart> parts = LegacyDecimalRangeFilterIndexCorrection.rewrite(root, pages);
		final FilterIndexStoragePart rewritten = parts.stream().filter(FilterIndexStoragePart.class::isInstance)
			.map(FilterIndexStoragePart.class::cast).findFirst().orElseThrow();
		final FilterIndexLeafPagePart page = parts.stream().filter(FilterIndexLeafPagePart.class::isInstance)
			.map(FilterIndexLeafPagePart.class::cast).findFirst().orElseThrow();
		assertArrayEquals(new int[]{3}, rewritten.getLeafPageSequences());
		assertEquals(9, rewritten.getHighWaterPageSequence());
		assertFalse(rewritten.isRangePaged());
		assertArrayEquals(new int[0], rewritten.getRangeLeafPageSequences());
		assertArrayEquals(new int[]{2, 3}, Objects.requireNonNull(rewritten.getRangeIndex())
			.getRecordsEnvelopingInclusive(2).compute().getArray());
		assertEquals(2, parts.stream().filter(RangeIndexLeafPageRemoval.class::isInstance).count());
		assertEquals(10, rewritten.getNextValueId());
		assertArrayEquals(new int[]{8, 7}, page.getValueIds());
		assertArrayEquals(new int[]{2, 3}, page.getBuckets()[0].getRecordIds().getArray());
		assertArrayEquals(new int[]{1}, page.getBuckets()[1].getRecordIds().getArray());
		assertEquals(1, parts.stream().filter(FilterIndexLeafPageRemoval.class::isInstance).count());
		assertArrayEquals(new int[]{3, 9}, root.getLeafPageSequences());
		assertArrayEquals(new int[]{2}, pages[0].getBuckets()[1].getRecordIds().getArray());
	}

	/** Verifies a rebuilt range stream never reuses a sequence retired by the same rewrite. */
	@DisplayName("Should place rebuilt range pages above every sequence the rewrite retires")
	@Test
	void shouldAllocateRebuiltRangePagesAbovePersistedHighWater() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "range", null);
		final AttributeKeyWithIndexType streamKey = new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER);
		final FilterIndexStoragePart root = new FilterIndexStoragePart(
			1, key, BigDecimalNumberRange.class, new ValueToRecordBitmap[0], null, 0, true, 9,
			new int[]{3, 9}, true, 19, new int[]{12, 19}, 17L
		);
		final ValueToRecord[] values = IntStream.rangeClosed(1, 400)
			.mapToObj(record -> new ValueToRecordBitmap(
				range(Integer.toString(record * 10), Integer.toString(record * 10 + 5)), record
			)).toArray(ValueToRecord[]::new);
		final FilterIndexLeafPagePart[] pages = {
			new FilterIndexLeafPagePart(1, streamKey, 3, Arrays.copyOfRange(values, 200, 400), null),
			new FilterIndexLeafPagePart(1, streamKey, 9, Arrays.copyOfRange(values, 0, 200), null)
		};
		final List<StoragePart> parts = LegacyDecimalRangeFilterIndexCorrection.rewrite(root, pages);
		final FilterIndexStoragePart rewritten = parts.stream().filter(FilterIndexStoragePart.class::isInstance)
			.map(FilterIndexStoragePart.class::cast).findFirst().orElseThrow();
		assertTrue(rewritten.isRangePaged());
		assertTrue(rewritten.getRangeHighWaterPageSequence() > 19);
		for (final int sequence : rewritten.getRangeLeafPageSequences()) {
			assertTrue(sequence > 19, "new range pages must be above the retired high-water");
			assertTrue(parts.stream().filter(RangeIndexLeafPagePart.class::isInstance)
				.map(RangeIndexLeafPagePart.class::cast).anyMatch(page -> page.getPageSequence() == sequence));
		}
	}

	/** Creates a raw decimal range whose natural scale is carried by its released spelling. */
	@Nonnull
	private static BigDecimalNumberRange range(@Nonnull String from, @Nonnull String to) {
		return BigDecimalNumberRange.between(new BigDecimal(from), new BigDecimal(to));
	}

	/** Opens only the storage services, which run and publish the migration before passing its result to the test. */
	private void withMigratedFixture(
		@Nonnull String fixture, @Nonnull Consumer<DefaultCatalogPersistenceService> body
	) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			OfflineCatalogImages.withOfflineCatalog(
				newTestEvitaConfigurationBuilder(paths).build(), fixture, service -> {
					body.accept(service);
					return Boolean.TRUE;
				}
			);
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** Resolves the fixture's product collection through the published catalog header. */
	@Nonnull
	private static DefaultEntityCollectionPersistenceService product(
		@Nonnull DefaultCatalogPersistenceService service, long version
	) {
		final CollectionFileReference reference = Objects.requireNonNull(
			service.getStoragePartPersistenceService(version).getCatalogHeader(version)
				.collectionFileIndex().get("product")
		);
		return service.getOrCreateEntityCollectionPersistenceService(
			version, "product", reference.entityTypePrimaryKey()
		);
	}

	/** Checks every persisted unique value, including boundaries between leaf pages. */
	private static void assertRangeOrder(@Nonnull List<Serializable> values) {
		assertEquals(300, values.size());
		for (int i = 1; i < values.size(); i++) {
			assertTrue(
				((DateTimeRange) values.get(i - 1)).compareTo((DateTimeRange) values.get(i)) < 0,
				"stored keys must ascend strictly"
			);
		}
	}
}
