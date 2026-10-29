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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.upgrade.ReleaseFixtureWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.BASE;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.FAR;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.U1;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.U2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies range unique repairs and refusals through storage publication, reopen and subsequent writes. */
@Tag(STORAGE)
@Tag(INDEXING)
@DisplayName("Migration_2026_3 — range unique keys across upgrade and reopen")
class Migration_2026_3_RangeUniqueUpgradeRoundTripTest implements EvitaTestSupport {
	/** Supplies isolated copies of release catalogs. */
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/** Different owners of equal current values must be diagnosed before publication. */
	@ParameterizedTest
	@ValueSource(strings = {"d10", "d11", "d11t"})
	@DisplayName("Should refuse equal unique values of different owners before publication")
	void shouldRefuseEqualUniqueValuesBeforePublication(@Nonnull String fixture) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			final var before = this.harness.bootstrapRecords(paths, fixture);
			final var files = ReleaseFixtureHarness.fileDigests(ReleaseFixtureHarness.catalogDirectory(paths, fixture));
			final RuntimeException failure = assertThrows(RuntimeException.class, () ->
				OfflineCatalogImages.withOfflineCatalog(newTestEvitaConfigurationBuilder(paths).build(), fixture,
					service -> Boolean.TRUE), "Unique collisions must refuse before publication");
			final String message = LegacyRepresentativeKeyPreflightTest.refusal(failure).getMessage();
			for (final String detail : List.of(fixture, "period", "product", "`product` 1", "`product` 2",
				"scope LIVE", "Nothing has been published")) {
				assertTrue(message.contains(detail), message);
			}
			if (!fixture.equals("d11t")) {
				assertTrue(message.contains(U1.toString()), message);
				assertTrue(message.contains(U2.toString()), message);
			} else {
				assertTrue(message.contains("2026-01-01T00:00:00.000100Z"), message);
				assertTrue(message.contains("2026-01-01T00:00:00.000500Z"), message);
			}
			assertEquals(before, this.harness.bootstrapRecords(paths, fixture));
			assertEquals(6, this.harness.lastBootstrapStorageProtocol(paths, fixture));
			ReleaseFixtureHarness.assertByteIdentical(files, ReleaseFixtureHarness.catalogDirectory(paths, fixture));
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** An aggregate bucket can retain a withdrawn spelling without identifying a second unique owner. */
	@Test
	@DisplayName("Should accept withdrawn representatives in aggregate reference indexes")
	void shouldAcceptWithdrawnRepresentativesInAggregateReferenceIndexes() {
		this.harness.withUpgradedCatalog("dUw", evita -> { });
	}

	/** Range collision detection spans nonadjacent buckets and pages, and ignores legitimate repeated ownership. */
	@ParameterizedTest
	@CsvSource({"false,true,false,true", "true,true,false,true", "false,true,true,true", "true,true,true,true",
		"false,false,false,true", "true,false,false,true", "false,true,false,false", "true,true,false,false"})
	@DisplayName("Should inspect complete unique range filter trees without rejecting healthy ownership")
	void shouldInspectCompleteUniqueRangeFilterTrees(boolean paged, boolean unique, boolean sameOwner, boolean twins) {
		final TestPaths paths = createTestPaths("RangeFilter_" + paged + unique + sameOwner + twins);
		final String catalog = "ranges";
		try {
			try (final Evita evita = this.harness.boot(paths)) {
				evita.defineCatalog(catalog);
				try (final EvitaSessionContract session = evita.createReadWriteSession(catalog)) {
					session.defineEntitySchema("product").withAttribute("period", DateTimeRange.class,
						attribute -> {
							if (unique) {
								attribute.unique();
							} else {
								attribute.filterable();
							}
						})
						.updateVia(session);
					session.createNewEntity("product", 1).setAttribute("period", U1).upsertVia(session);
					session.createNewEntity("product", 2).setAttribute("period", FAR).upsertVia(session);
					session.goLiveAndClose();
				}
			}
			final var configuration = newTestEvitaConfigurationBuilder(paths).build();
			final var before = OfflineCatalogImages.lastBootstrap(configuration, catalog);
			OfflineCatalogImages.withOfflineCatalog(configuration, catalog, service -> {
				final long version = service.getLastCatalogVersion();
				final var catalogParts = service.getStoragePartPersistenceService(version);
				final var header = catalogParts.getCatalogHeader(version);
				final var reference = header.collectionFileIndex().get("product");
				final var collectionService = service.createEntityCollectionPersistenceService(Objects.requireNonNull(
					catalogParts.getStoragePart(version, reference.entityTypePrimaryKey(),
						EntityCollectionFileHeader.class)));
				final var parts = collectionService.getStoragePartPersistenceService();
				final int index = Objects.requireNonNull(collectionService.getEntityCollectionHeader()
					.globalEntityIndexPrimaryKey());
				final AttributeIndexKey key = new AttributeIndexKey(null, "period", null);
				final FilterIndexStoragePart old = Objects.requireNonNull(parts.getStoragePart(version,
					AttributeIndexStoragePart.computeUniquePartId(index, AttributeIndexType.FILTER, key,
						parts.getReadOnlyKeyCompressor()), FilterIndexStoragePart.class));
				final DateTimeRange distinct = DateTimeRange.between(
					BASE.plusDays(5),
					BASE.plusDays(6));
				final DateTimeRange separator = DateTimeRange.until(
					BASE.plusHours(20).withOffsetSameInstant(ZoneOffset.ofHoursMinutes(1, 30)));
				final ValueToRecordBitmap[] buckets = {
					new ValueToRecordBitmap(U2, new BaseBitmap(1)),
					new ValueToRecordBitmap(separator, new BaseBitmap(2)),
					new ValueToRecordBitmap(twins ? U1 : distinct, new BaseBitmap(sameOwner ? 1 : 3))
				};
				if (paged) {
					for (int i = 0; i < buckets.length; i++) {
						parts.putStoragePart(version, new FilterIndexLeafPagePart(index,
							new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER), i,
							new ValueToRecord[]{buckets[i]}, null));
					}
				}
				parts.putStoragePart(version, new FilterIndexStoragePart(index, key, DateTimeRange.class,
					paged ? new ValueToRecordBitmap[0] : buckets, old.getRangeIndex(), 0, paged, paged ? 2 : -1,
					paged ? new int[]{0, 1, 2} : new int[0], false, -1, new int[0], old.getStoragePartPK()));
				final AtomicBoolean published = new AtomicBoolean();
				final Runnable migration = () -> Migration_2026_3.upgradeFromStorageProtocolVersion_6_to_7(
					header, catalogParts, ignored -> collectionService, ignored -> published.set(true));
				if (unique && twins && !sameOwner) {
					final String message = LegacyRepresentativeKeyPreflightTest.refusal(
						assertThrows(RuntimeException.class, migration::run,
							"Equal range keys across filter parts must refuse before publication")).getMessage();
					assertTrue(message.contains(U1.toString()) && message.contains(U2.toString()), message);
					assertTrue(!published.get(), "A collision must not invoke the publication callback");
				} else {
					migration.run();
					assertTrue(published.get(), "Healthy ownership must reach the publication callback");
				}
				assertTrue(collectionService.isClosed(), "The refused or successful planner must close its service");
				return Boolean.TRUE;
			});
			assertEquals(before, OfflineCatalogImages.lastBootstrap(configuration, catalog));
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** Cross-page duplicates need the same complete-payload decision as inline roots. */
	@ParameterizedTest
	@CsvSource({"false,false", "false,true", "true,false", "true,true"})
	@DisplayName("Should plan range twins across inline roots and separate leaf pages")
	void shouldPlanRangeTwinsAcrossStorageParts(boolean paged, boolean sameEntry) {
		final TestPaths paths = createTestPaths("RangeUnique_" + paged + "_" + sameEntry);
		final String catalog = "d10s";
		try {
			try (final Evita evita = this.harness.boot(paths)) {
				ReleaseFixtureWriter.replay(evita, ReleaseFixtureHarness.recipe(catalog));
			}
			final var configuration = newTestEvitaConfigurationBuilder(paths).build();
			final int fileIndex = OfflineCatalogImages.lastBootstrap(configuration, catalog).catalogFileIndex();
			final CatalogBootstrap legacyBootstrap = OfflineCatalogImages.withOfflineCatalog(
				configuration, catalog, service -> {
				final long version = service.getLastCatalogVersion();
				final var parts = service.getStoragePartPersistenceService(version);
				final var header = parts.getCatalogHeader(version);
				final AttributeKey key = new AttributeKey("periods");
				final GlobalUniqueIndexStoragePart old = Objects.requireNonNull(parts.getStoragePart(
					version, GlobalUniqueIndexStoragePart.computeUniquePartId(
						Scope.LIVE, key, parts.getReadOnlyKeyCompressor()), GlobalUniqueIndexStoragePart.class
				));
				final Serializable[] original = Objects.requireNonNull(old.getValues());
				final long[] payloads = Objects.requireNonNull(old.getPayloads());
				final long first = payloads[original[0].equals(U1) ? 0 : 1];
				final long far = payloads[original[0].equals(FAR) ? 0 : 1];
				final Serializable[] values = {U2, U1, FAR};
				final long[] owners = {first, sameEntry ? first : far, far};
				if (paged) {
					for (int i = 0; i < values.length; i++) {
						parts.putStoragePart(version, new GlobalUniqueIndexLeafPagePart(
							Scope.LIVE, key, i, new Serializable[]{values[i]}, new long[]{owners[i]}));
					}
					parts.putStoragePart(version, GlobalUniqueIndexStoragePart.paged(
						Scope.LIVE, key, old.getType(), 2, new int[]{0, 1, 2},
						old.getLocaleIndex(), old.getStoragePartPK()));
				} else {
					parts.putStoragePart(version, new GlobalUniqueIndexStoragePart(
						Scope.LIVE, key, old.getType(), values, owners, old.getLocaleIndex(), old.getStoragePartPK()));
				}
				parts.writeCatalogHeader(6, version, service.getCatalogStoragePath(), header.walFileReference(),
					header.collectionFileIndex(), header.catalogId(), catalog, header.catalogState(),
					header.lastEntityCollectionPrimaryKey());
				return service.recordBootstrap(version, catalog, fileIndex, null);
			});
			OfflineCatalogImages.appendBootstrapRecord(configuration, catalog, new CatalogBootstrap(
				6, legacyBootstrap.catalogVersion(), legacyBootstrap.catalogFileIndex(), legacyBootstrap.timestamp(),
				legacyBootstrap.fileLocation()));
			final var before = this.harness.bootstrapRecords(paths, catalog);
			if (sameEntry) {
				try (final Evita evita = this.harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, catalog);
					for (final DateTimeRange twin : List.of(U1, U2)) {
						assertEquals(List.of(1), ReleaseFixtureHarness.pks(evita, catalog,
							query(collection("product"), filterBy(attributeEquals("periods", twin)))));
					}
				}
				OfflineCatalogImages.withOfflineCatalog(configuration, catalog, service -> {
					final long version = service.getLastCatalogVersion();
					assertEquals(PersistenceService.STORAGE_PROTOCOL_VERSION,
						service.getStoragePartPersistenceService(version).getCatalogHeader(version)
							.storageProtocolVersion());
					return Boolean.TRUE;
				});
			} else {
				final RuntimeException failure = assertThrows(RuntimeException.class, () ->
					OfflineCatalogImages.withOfflineCatalog(configuration, catalog, service -> Boolean.TRUE),
					"Cross-part unique collisions must refuse before publication");
				final String message = LegacyRepresentativeKeyPreflightTest.refusal(failure).getMessage();
				assertTrue(message.contains(U1.toString()) && message.contains(U2.toString()), message);
				assertEquals(before, this.harness.bootstrapRecords(paths, catalog));
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** The index relation folds once, survives reopen, and releases the key once on replacement and removal. */
	@Test
	@DisplayName("Should persist one complete owner for equal range array values")
	void shouldPersistOneCompleteOwnerForEqualRangeArrayValues() {
		final TestPaths paths = this.harness.copy("d10s");
		try {
			OfflineCatalogImages.withOfflineCatalog(
				newTestEvitaConfigurationBuilder(paths).build(), "d10s", service -> {
				final long version = service.getLastCatalogVersion();
				final var parts = service.getStoragePartPersistenceService(version);
				final GlobalUniqueIndexStoragePart root = Objects.requireNonNull(parts.getStoragePart(
					version, GlobalUniqueIndexStoragePart.computeUniquePartId(
						Scope.LIVE, new AttributeKey("periods"), parts.getReadOnlyKeyCompressor()
					), GlobalUniqueIndexStoragePart.class
				));
				assertEquals(2, Objects.requireNonNull(root.getValues()).length,
					"Equal range spellings of one complete owner must be persisted as one key");
				final long[] payloads = Objects.requireNonNull(root.getPayloads());
				for (int i = 0; i < root.getValues().length; i++) {
					assertEquals(root.getValues()[i].equals(U1) ? 1 : 2, (int) payloads[i]);
				}
				return Boolean.TRUE;
			});
			try (final Evita evita = this.harness.boot(paths)) {
				ReleaseFixtureHarness.requireAlive(evita, "d10s");
				for (final DateTimeRange twin : List.of(U1, U2)) {
					assertEquals(List.of(1), ReleaseFixtureHarness.pks(evita, "d10s",
						query(collection("product"), filterBy(attributeEquals("periods", twin)))));
				}
				evita.updateCatalog("d10s", session -> {
					session.getEntity("product", 1, attributeContentAll())
						.orElseThrow().openForWrite().setAttribute("periods", new DateTimeRange[]{DateTimeRange.between(
							BASE.plusDays(20), BASE.plusDays(21))}).upsertVia(session);
					session.createNewEntity("product", 3)
						.setAttribute("periods", new DateTimeRange[]{U2}).upsertVia(session);
					session.deleteEntity("product", 1);
					session.createNewEntity("product", 4)
						.setAttribute("periods", new DateTimeRange[]{DateTimeRange.between(
						BASE.plusDays(20), BASE.plusDays(21))}).upsertVia(session);
				});
			}
			try (final Evita evita = this.harness.boot(paths)) {
				ReleaseFixtureHarness.requireAlive(evita, "d10s");
				assertEquals(List.of(3), ReleaseFixtureHarness.pks(evita, "d10s",
					query(collection("product"), filterBy(attributeEquals("periods", U1)))));
				assertEquals(List.of(2), ReleaseFixtureHarness.pks(evita, "d10s",
					query(collection("product"), filterBy(attributeEquals("periods", FAR)))));
				assertEquals(List.of(4), ReleaseFixtureHarness.pks(evita, "d10s", query(collection("product"),
					filterBy(attributeEquals("periods",
						DateTimeRange.between(BASE.plusDays(20), BASE.plusDays(21)))))));
			}
		} finally {
			this.harness.cleanup(paths);
		}
	}
}
