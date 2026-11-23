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
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.ObsoleteStorageProtocolException;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.LocalizedHistogramIndex;
import io.evitadb.index.SimpleHistogramIndex;
import io.evitadb.index.component.loader.VirtualReferencePartitionLoader;
import io.evitadb.index.virtual.CatalogReloadTestSupport;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.*;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.index.mutation.local.HistogramWithdrawalLogCapture.captureReports;
import static io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind.*;
import static io.evitadb.store.catalog.OfflineCatalogImages.*;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Objects.requireNonNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds complete protocol-6 images through storage parts: grouped manifests own their legacy histogram parts,
 * the collection header omits every store, and references use physical partitions without registry rows.
 * The upgrade removes grouped histograms without filling stores and publishes the entire migration once.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Grouped histogram cleanup during the released storage upgrade")
@Tag(STORAGE)
@Tag(HISTOGRAM)
@Isolated
class Migration_2026_3_GroupedHistogramCleanupTest implements EvitaTestSupport {
	private static final String CATALOG = "groupedCleanup";
	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";
	private static final String GROUP = "brandGroup";
	private static final String TAG = "tag";
	private static final String SHARE = "share";
	private static final String RANK = "rank";
	private static final String HISTOGRAM_NAME = "cleanupShares";
	private static final int PROTOCOL = 9;
	private static final String PROPERTY = "evitadb.referenceHistogram.missingValueBehavior";
	private static final TransactionOptions DEFERRED = TransactionOptions.builder()
		.checkpointIntervalInMillis(3_600_000L).build();
	private final List<TestPaths> paths = new ArrayList<>(4);

	/** Removes every generated image after its engine has closed. */
	@AfterEach
	void cleanUp() {
		this.paths.forEach(this::cleanupTestPaths);
	}

	/** Verifies one publication, the surviving ungrouped histogram and the default policy on a grouped update. */
	@Test
	@DisplayName("one publication removes grouped roots, counters and both leaf streams and keeps ungrouped values")
	void shouldCleanGroupedHistogramsWithOnePublication() {
		final EvitaConfiguration configuration = writeImage("complete");
		final LegacyImage image = makeLegacyImage(configuration, null);
		stamp(configuration, CATALOG, 6, 6);
		final int before = bootstrapRecords(configuration, CATALOG).size();
		assertCleaned(configuration, image);
		assertEquals(before + 1, bootstrapRecords(configuration, CATALOG).size());
		assertEquals(PROTOCOL, lastBootstrap(configuration, CATALOG).storageProtocolVersion());

		try (Evita evita = boot(configuration, null)) {
			evita.waitUntilFullyInitialized();
			assertNull(readHistogram(evita, BRAND, 1));
			assertHistogram(readHistogram(evita, TAG, null), 5, 9, 2, "the kept ungrouped histogram");
			final List<String> reports = evita.updateCatalog(CATALOG, session -> {
				return captureReports(HISTOGRAM_NAME, () -> updateShare(session, 1, 7));
			});
			assertEquals(1, reports.size(), reports::toString);
			assertTrue(reports.getFirst().startsWith("Cannot withdraw histogram value"), reports::toString);
			assertHistogram(readHistogram(evita, BRAND, 1), 7, 7, 1, "the updated grouped histogram");
			assertHistogram(readHistogram(evita, TAG, null), 5, 9, 2, "the kept ungrouped histogram");
		}
		final EvitaConfiguration rebuilt = writeImage("rebuilt");
		try (Evita evita = boot(rebuilt, null)) {
			evita.waitUntilFullyInitialized();
			assertHistogram(readHistogram(evita, BRAND, 1), 5, 9, 2, "the rebuilt grouped oracle");
		}
	}

	/** A protocol-6 store is a development representation and must fail before publication or any data write. */
	@Test
	@DisplayName("a store manifest in a released-protocol image is refused without changing any byte")
	void shouldRefuseStoreInReleasedImageBeforeWriting() {
		final EvitaConfiguration configuration = writeImage("store");
		stamp(configuration, CATALOG, 6, 6);
		final Map<String, StoredFile> before = storedFiles(catalogFolder(configuration, CATALOG));
		final RuntimeException refusal = assertThrows(RuntimeException.class,
			() -> withOfflineCatalog(configuration, CATALOG, service -> Boolean.TRUE));
		assertTrue(messages(refusal).contains("store manifest"), messages(refusal));
		assertEquals(before, storedFiles(catalogFolder(configuration, CATALOG)));
	}

	/** The explicit operator override still permits live writes after the cleanup, reporting the absent value. */
	@Test
	@DisplayName("an explicitly reporting live engine permits a grouped update after cleanup")
	void shouldReportLiveWithdrawalWhenExplicitlyConfigured() {
		final EvitaConfiguration configuration = writeImage("report");
		makeLegacyImage(configuration, null);
		stamp(configuration, CATALOG, 6, 6);
		withOfflineCatalog(configuration, CATALOG, service -> Boolean.TRUE);
		try (final Evita evita = boot(configuration, "REPORT")) {
			evita.waitUntilFullyInitialized();
			evita.updateCatalog(CATALOG, session -> {
				updateShare(session, 1, 7);
			});
			assertEquals(7, requireNonNull(readHistogram(evita, BRAND, 1)).getMin().intValue());
		}
	}

	/** Current-protocol images carrying either legacy manifest kind must identify the offending histogram keys. */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = EntityIndexType.class, names = {"REFERENCED_GROUP_ENTITY", "REFERENCED_GROUP_ENTITY_TYPE"})
	@DisplayName("current-protocol reload refuses histogram keys in a grouped membership manifest")
	void shouldRefuseLegacyHistogramKeysDuringReload(@Nonnull EntityIndexType kind) {
		final EvitaConfiguration configuration = writeImage("reload_" + kind);
		makeLegacyImage(configuration, kind);
		stamp(configuration, CATALOG, PROTOCOL, PROTOCOL);
		try (Evita evita = boot(configuration, null)) {
			final CompletionException refusal =
				assertThrows(CompletionException.class, evita::waitUntilFullyInitialized);
			assertTrue(messages(refusal).contains("histogram keys"), messages(refusal));
			assertTrue(messages(refusal).contains(HISTOGRAM_NAME), messages(refusal));
		}
	}

	/**
	 * Takes a crash image of a committed but unpublished grouped update. Cleanup empties the grouped histograms,
	 * then startup replay reports and reaches the tail under the refusing policy. The next session write still refuses.
	 */
	@Test
	@Tag(WAL)
	@DisplayName("Startup reports an unpublished grouped update and the next session write refuses")
	void shouldReportGroupedUpdateDuringStartupReplayAndRefuseSessionWrite() {
		final TestPaths source = allocate("tailSource");
		final TestPaths crash = allocate("tailImage");
		final EvitaConfiguration sourceConfiguration = configuration(source);
		try (Evita evita = boot(sourceConfiguration, null)) {
			populate(evita);
		}
		final long tailVersion;
		try (Evita evita = boot(sourceConfiguration, null)) {
			evita.waitUntilFullyInitialized();
			evita.updateCatalog(CATALOG, session -> {
				updateShare(session, 1, 7);
			});
			await().atMost(30, TimeUnit.SECONDS).until(() -> {
				final Catalog catalog = catalog(evita);
				return catalog.getVersion() == catalog.getLastCatalogVersionInMutationStream();
			});
			final Catalog catalog = catalog(evita);
			tailVersion = catalog.getVersion();
			assertTrue(catalog.getLastPersistedCatalogVersion() < tailVersion);
			final Map<String, String> before = imageFiles(source.storage());
			CatalogReloadTestSupport.copyRecursively(source.storage(), crash.storage());
			assertEquals(before, imageFiles(source.storage()));
			assertEquals(before, imageFiles(crash.storage()));
		}
		final EvitaConfiguration configuration = configuration(crash);
		makeLegacyImage(configuration, null);
		stamp(configuration, CATALOG, 6, 6);
		final List<String> reports = captureReports(HISTOGRAM_NAME, () -> {
			try (final Evita evita = boot(configuration, "REFUSE")) {
				evita.waitUntilFullyInitialized();
				assertEquals(CatalogState.ALIVE, evita.getCatalogState(CATALOG).orElseThrow());
				assertEquals(tailVersion, catalog(evita).getVersion());
				// the cleanup emptied the grouped histogram, so only the replayed value of owner 1 is in it
				assertHistogram(readHistogram(evita, BRAND, 1), 7, 7, 1, "the grouped histogram after replay");
				final RuntimeException refusal = assertThrows(RuntimeException.class, () ->
					evita.updateCatalog(CATALOG, session -> {
						updateShare(session, 2, 11);
					})
				);
				assertTrue(messages(refusal).contains("Cannot withdraw histogram value"), messages(refusal));
			}
		});
		assertTrue(
			reports.stream().anyMatch(line -> line.startsWith("Cannot withdraw histogram value")), reports::toString
		);
	}

	/**
	 * Boots the engine with the selected policy and restores the system property after construction.
	 *
	 * @param configuration the engine configuration
	 * @param behavior the explicit policy name, or null for the default
	 * @return the engine over the configured image
	 */
	@Nonnull
	private static Evita boot(@Nonnull EvitaConfiguration configuration, @Nullable String behavior) {
		final String originalProperty = System.getProperty(PROPERTY);
		try {
			if (behavior == null) {
				System.clearProperty(PROPERTY);
			} else {
				System.setProperty(PROPERTY, behavior);
			}
			return new Evita(configuration);
		} finally {
			if (originalProperty == null) {
				System.clearProperty(PROPERTY);
			} else {
				System.setProperty(PROPERTY, originalProperty);
			}
		}
	}

	/** Opens the image through the real migration and inspects manifests and the exact legacy footprint. */
	private static void assertCleaned(@Nonnull EvitaConfiguration configuration, @Nonnull LegacyImage image) {
		withOfflineCatalog(configuration, CATALOG, service -> {
			final long version = service.getLastCatalogVersion();
			assertEquals(PROTOCOL, service.getCatalogHeader(version).storageProtocolVersion());
			final DefaultEntityCollectionPersistenceService collection = productCollection(service, version);
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			for (int indexId : image.groupedIndexIds()) {
				final EntityIndexStoragePart manifest = requireNonNull(parts.getStoragePart(
					version, indexId, EntityIndexStoragePart.class));
				assertEquals(Set.of(), manifest.getHistogramIndexes());
				assertNull(manifest.getEntityIds());
				assertNull(manifest.getEntityIdsByLanguage());
			}
			assertTrue(image.parts().stream().anyMatch(part -> part.type() == HistogramIndexLeafPagePart.class));
			assertTrue(image.parts().stream().anyMatch(part -> part.type() == HistogramRangeIndexLeafPagePart.class));
			for (PartAddress address : image.parts()) {
				assertNull(parts.getStoragePart(version, address.id(), address.type()), address.toString());
			}
			for (int indexId : collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()) {
				assertNotEquals(EntityIndexType.REFERENCED_GROUP_HISTOGRAM,
					requireNonNull(parts.getStoragePart(version, indexId, EntityIndexStoragePart.class))
						.getEntityIndexKey().type());
			}
			return Boolean.TRUE;
		});
	}

	/**
	 * Replaces each store with full legacy histograms under every selected grouped manifest and rewrites both
	 * collection and catalog headers so no header lists a removed store. No binary records are patched.
	 */
	@Nonnull
	private static LegacyImage makeLegacyImage(
		@Nonnull EvitaConfiguration configuration, @Nullable EntityIndexType onlyKind
	) {
		final int catalogFileIndex = lastBootstrap(configuration, CATALOG).catalogFileIndex();
		return withOfflineCatalog(configuration, CATALOG, service -> {
			final long version = service.getLastCatalogVersion();
			final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
				service.getStoragePartPersistenceService(version);
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
				catalogParts.getCatalogHeader(version);
			final DefaultEntityCollectionPersistenceService collection = productCollection(service, version);
			final EntityCollectionFileHeader collectionHeader = collection.getEntityCollectionHeader();
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			final List<Integer> retainedIds = new ArrayList<>(collectionHeader.usedEntityIndexPrimaryKeys());
			final List<Integer> groupedIds = new ArrayList<>(3);
			final List<PartAddress> footprint = new ArrayList<>(32);
			int removedStores = 0;
			for (int indexId : collectionHeader.usedEntityIndexPrimaryKeys()) {
				final EntityIndexStoragePart manifest = requireNonNull(
					parts.getStoragePart(version, indexId, EntityIndexStoragePart.class));
				final EntityIndexType kind = manifest.getEntityIndexKey().type();
				if (kind == EntityIndexType.REFERENCED_ENTITY_TYPE) {
					assertTrue(VirtualReferencePartitionLoader.loadIndex(parts, version, indexId,
							manifest.getEntityIndexKey().referenceName()).isEmpty(),
						"a released image has no registry rows");
				}
				if (kind == EntityIndexType.REFERENCED_GROUP_HISTOGRAM) {
					removeStoreParts(version, parts, manifest);
					parts.removeStoragePart(version, indexId, EntityIndexStoragePart.class);
					parts.removeStoragePart(version, indexId, EntityIdsStoragePart.class);
					assertTrue(retainedIds.remove(Integer.valueOf(indexId)));
					removedStores++;
				} else if ((kind == EntityIndexType.REFERENCED_GROUP_ENTITY ||
					kind == EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE) && (onlyKind == null || onlyKind == kind)) {
					groupedIds.add(indexId);
					final Set<HistogramIndexStorageKey> keys = new HashSet<>();
					final SimpleHistogramIndex shares =
						new SimpleHistogramIndex(HISTOGRAM_NAME, BRAND, Integer.class, 0);
					shares.insertValue(null, 5, 1);
					shares.insertValue(null, 9, 2);
					writeHistogram(version, parts, manifest, shares, keys, footprint);
					final SimpleHistogramIndex ranges = new SimpleHistogramIndex(
						"legacyRanges", BRAND, IntegerNumberRange.class, 0);
					for (int value = 1; value <= 3200; value++) {
						ranges.insertValue(null, IntegerNumberRange.between(value * 3, value * 3 + 1), value);
					}
					writeHistogram(version, parts, manifest, ranges, keys, footprint);
					final LocalizedHistogramIndex localized = new LocalizedHistogramIndex(
						"legacyLocalized", BRAND, Integer.class, 0);
					localized.insertValue(Locale.ENGLISH, 5, 1);
					localized.insertValue(Locale.GERMAN, 9, 2);
					writeHistogram(version, parts, manifest, localized, keys, footprint);
					parts.putStoragePart(version, copyManifest(manifest, keys));
				}
			}
			assertEquals(1, removedStores, "the engine-written image must contain exactly one store");
			assertFalse(groupedIds.isEmpty());
			collection.flush(version, new LegacyHeaderSupplier(collectionHeader, retainedIds));
			assertEquals(retainedIds, collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys());
			catalogParts.putStoragePart(version, collection.getEntityCollectionHeader());
			final Map<String, CollectionFileReference> references = new HashMap<>(header.collectionFileIndex());
			references.compute(PRODUCT, (name, reference) -> new CollectionFileReference(
				PRODUCT, reference.entityTypePrimaryKey(), reference.fileIndex(),
				collection.getEntityCollectionHeader().fileLocation()));
			catalogParts.writeCatalogHeader(PersistenceService.STORAGE_PROTOCOL_VERSION, version,
				service.getCatalogStoragePath(), header.walFileReference(), references, header.catalogId(),
				header.catalogName(), header.catalogState(), header.lastEntityCollectionPrimaryKey());
			service.recordBootstrap(version, CATALOG, catalogFileIndex, null);
			return new LegacyImage(List.copyOf(groupedIds), List.copyOf(footprint));
		});
	}

	/** Writes a new histogram under its legacy owner and records every persisted part for the reclaim oracle. */
	private static void writeHistogram(
		long version, @Nonnull OffsetIndexStoragePartPersistenceService parts,
		@Nonnull EntityIndexStoragePart manifest, @Nonnull HistogramIndex histogram,
		@Nonnull Set<HistogramIndexStorageKey> keys, @Nonnull List<PartAddress> footprint
	) {
		histogram.collectStorageKeys(manifest.getEntityIndexKey(), keys);
		final TrappedChanges changes = new TrappedChanges();
		histogram.getModifiedStorageParts(manifest.getPrimaryKey(), changes);
		final Iterator<StoragePart> iterator = changes.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			final StoragePart part = iterator.next();
			assertFalse(part instanceof DeferredRemovalStoragePart);
			final long id = parts.putStoragePart(version, part);
			footprint.add(new PartAddress(id, part.getClass()));
			requireNonNull(parts.getStoragePart(version, id, part.getClass()));
		}
	}

	/** Removes the actual store footprint using the root's persisted leaf lists, before dropping the manifest. */
	private static void removeStoreParts(
		long version, @Nonnull OffsetIndexStoragePartPersistenceService parts,
		@Nonnull EntityIndexStoragePart manifest
	) {
		for (HistogramIndexStorageKey key : manifest.getHistogramIndexes()) {
			final long id = key.computeUniquePartId(manifest.getPrimaryKey(), parts.getReadOnlyKeyCompressor());
			final HistogramIndexStoragePart root = requireNonNull(parts.getStoragePart(
				version, id, HistogramIndexStoragePart.class));
			for (int page : root.getLeafPageSequences()) {
				final int stream = parts.getReadOnlyKeyCompressor().getId(new HistogramLeafStreamKey(
					manifest.getPrimaryKey(), key.histogramName(), key.locale(), BUCKET));
				parts.removeStoragePart(version,
					HistogramIndexLeafPagePart.computeUniquePartId(stream, page), HistogramIndexLeafPagePart.class);
			}
			for (int page : root.getRangeLeafPageSequences()) {
				final int stream = parts.getReadOnlyKeyCompressor().getId(new HistogramLeafStreamKey(
					manifest.getPrimaryKey(), key.histogramName(), key.locale(), RANGE));
				parts.removeStoragePart(version, HistogramRangeIndexLeafPagePart.computeUniquePartId(stream, page),
					HistogramRangeIndexLeafPagePart.class);
			}
			parts.removeStoragePart(version, id, HistogramIndexStoragePart.class);
			parts.removeStoragePart(version, id, HistogramCardinalityStoragePart.class);
		}
	}

	/** Copies the bitmap-less manifest, replacing only its histogram keys. */
	@Nonnull
	private static EntityIndexStoragePart copyManifest(
		@Nonnull EntityIndexStoragePart manifest, @Nonnull Set<HistogramIndexStorageKey> keys
	) {
		assertNull(manifest.getEntityIds());
		assertNull(manifest.getEntityIdsByLanguage());
		return new EntityIndexStoragePart(manifest.getPrimaryKey(), manifest.getVersion(), manifest.getEntityIndexKey(),
			manifest.getAttributeIndexes(), manifest.getPriceIndexes(), manifest.isHierarchyIndex(),
			manifest.getFacetIndexes(), keys);
	}

	/** Resolves the collection using the catalog's published file reference. */
	@Nonnull
	private static DefaultEntityCollectionPersistenceService productCollection(
		@Nonnull DefaultCatalogPersistenceService service, long version
	) {
		final CollectionFileReference reference = service.getCatalogHeader(version).collectionFileIndex().get(PRODUCT);
		requireNonNull(reference);
		return service.getOrCreateEntityCollectionPersistenceService(
			version, PRODUCT, reference.entityTypePrimaryKey()
		);
	}

	/** Writes the engine-side starting image and closes it so the initial live state is published. */
	@Nonnull
	private EvitaConfiguration writeImage(@Nonnull String name) {
		final EvitaConfiguration configuration = configuration(allocate(name));
		try (Evita evita = boot(configuration, null)) {
			populate(evita);
		}
		return configuration;
	}

	/** Defines physical reference partitions and two owners contributing different grouped and ungrouped values. */
	private static void populate(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG);
		evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema(GROUP).withoutGeneratedPrimaryKey().updateVia(session);
			session.createNewEntity(GROUP, 1).upsertVia(session);
			for (int brandPk : new int[]{10, 11}) {
				session.createNewEntity(BRAND, brandPk).upsertVia(session);
			}
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey()
				.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
					whichIs -> whichIs.withGroupTypeRelatedToEntity(GROUP)
						.indexedForFilteringAndPartitioning().faceted()
						.indexedWithComponentsInScope(Scope.LIVE, ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
						.withAttribute(RANK, Integer.class, thatIs -> thatIs.filterable().representative())
						.withAttribute(SHARE, Integer.class, thatIs -> thatIs.filterable())
						.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME,
							ExpressionFactory.parse("$reference.attributes['share']"),
							ExpressionFactory.parse("$reference.attributes['rank'] > 0")))
				.withReferenceToEntity(TAG, BRAND, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
						.withAttribute(SHARE, Integer.class, thatIs -> thatIs.filterable())
						.bucketed(HISTOGRAM_NAME, ExpressionFactory.parse("$reference.attributes['share']")))
				.updateVia(session);
			for (int ownerPk = 1; ownerPk <= 2; ownerPk++) {
				final int owner = ownerPk;
				final int share = owner == 1 ? 5 : 9;
				session.createNewEntity(PRODUCT, owner)
					.setOrUpdateReference(BRAND, 9 + owner, reference -> false, whichIs -> whichIs.setGroup(GROUP, 1)
						.setAttribute(RANK, owner).setAttribute(SHARE, share))
					.setReference(TAG, 9 + owner, whichIs -> whichIs.setAttribute(SHARE, share))
					.upsertVia(session);
			}
			session.goLiveAndClose();
		});
	}

	/** Applies a reference-attribute change to one existing owner without replacing the reference. */
	private static void updateShare(@Nonnull EvitaSessionContract session, int owner, int value) {
		final ReferenceKey key = session.getEntity(PRODUCT, owner, entityFetchAllContent()).orElseThrow()
			.getReferences(BRAND).iterator().next().getReferenceKey();
		session.upsertEntity(new EntityUpsertMutation(
			PRODUCT, owner, EntityExistence.MUST_EXIST,
			new ReferenceAttributeMutation(key, new UpsertAttributeMutation(SHARE, value))
		));
	}

	/** Queries the grouped or ungrouped reference histogram from the public summary. */
	@Nullable
	private static HistogramContract readHistogram(
		@Nonnull Evita evita, @Nonnull String reference, @Nullable Integer group
	) {
		return evita.queryCatalog(CATALOG, session -> {
			final ReferenceSummary summary = session.query(query(collection(PRODUCT),
				require(referenceSummaryOfReferenceWithHistograms(reference, FacetStatisticsDepth.COUNTS,
					null, null, histogramStatistics(10, HISTOGRAM_NAME)))), EntityReference.class)
				.getExtraResult(ReferenceSummary.class);
			final ReferenceSummary.ReferenceGroupStatistics statistics = group == null ?
				summary.getReferenceGroupStatistics(reference) : summary.getReferenceGroupStatistics(reference, group);
			return statistics == null ? null : statistics.getHistogramStatistics(HISTOGRAM_NAME);
		});
	}

	/**
	 * Asserts that the histogram exists and spans exactly the given values, counting one occurrence per owner value,
	 * so a histogram that survived with wrong content fails as well as a missing one.
	 */
	private static void assertHistogram(
		@Nullable HistogramContract histogram, int min, int max, int occurrences, @Nonnull String description
	) {
		final HistogramContract present = requireNonNull(histogram, description);
		assertEquals(min, present.getMin().intValue(), description);
		assertEquals(max, present.getMax().intValue(), description);
		int counted = 0;
		for (final HistogramContract.Bucket bucket : present.getBuckets()) {
			counted += bucket.occurrences();
		}
		assertEquals(occurrences, counted, description);
	}

	/** Captures every file recursively using relative paths and content, so two image roots can be compared. */
	@Nonnull
	private static Map<String, String> imageFiles(@Nonnull Path folder) {
		try (Stream<Path> paths = Files.walk(folder)) {
			final List<Path> files = paths.filter(Files::isRegularFile).toList();
			final Map<String, String> result = createHashMap(files.size());
			for (Path file : files) {
				final StoredFile stored = StoredFile.of(file);
				result.put(folder.relativize(file).toString(), stored.size() + ":" + stored.digest());
			}
			return result;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Returns the loaded catalog's current version. */
	@Nonnull
	private static Catalog catalog(@Nonnull Evita evita) {
		return (Catalog) evita.getCatalogInstance(CATALOG).orElseThrow();
	}

	/** Creates deferred-checkpoint configuration so the WAL-tail fixture retains a committed unpublished update. */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths testPaths) {
		return newTestEvitaConfigurationBuilder(testPaths).transaction(DEFERRED).build();
	}

	/** Allocates and remembers one image's directories. */
	@Nonnull
	private TestPaths allocate(@Nonnull String name) {
		final TestPaths result = createTestPaths("groupedCleanup_" + name);
		this.paths.add(result);
		return result;
	}

	/** Joins nested diagnoses so a refusal must identify the legacy input rather than an incidental load error. */
	@Nonnull
	private static String messages(@Nonnull Throwable failure) {
		final StringBuilder result = new StringBuilder(256);
		for (Throwable current = failure; current != null; current = current.getCause()) {
			result.append(current.getMessage()).append('\n');
			if (current instanceof ObsoleteStorageProtocolException obsolete) {
				result.append(obsolete.getPrivateMessage()).append('\n');
			}
		}
		return result.toString();
	}

	/** The exact key and concrete record type of a persisted legacy part. */
	private record PartAddress(long id, @Nonnull Class<? extends StoragePart> type) { }

	/** The manifests and all root, cardinality and leaf parts the cleanup must remove. */
	private record LegacyImage(@Nonnull List<Integer> groupedIndexIds, @Nonnull List<PartAddress> parts) { }

	/** Supplies the complete collection header with only removed histogram stores omitted. */
	private static final class LegacyHeaderSupplier extends NoChangeHeaderInfoSupplier {
		private final List<Integer> indexIds;

		/** Creates the header callback over the retained legacy indexes. */
		private LegacyHeaderSupplier(@Nonnull EntityCollectionFileHeader header, @Nonnull List<Integer> indexIds) {
			super(header);
			this.indexIds = List.copyOf(indexIds);
		}

		/** Returns every legacy index and omits every deleted store. */
		@Nonnull
		@Override
		public List<Integer> getIndexPrimaryKeys() {
			return this.indexIds;
		}
	}
}
