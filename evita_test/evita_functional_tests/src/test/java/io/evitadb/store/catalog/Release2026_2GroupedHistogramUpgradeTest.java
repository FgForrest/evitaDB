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
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GroupHistogramEntityIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.upgrade.ReleaseFixtureWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.BRAND;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static io.evitadb.utils.ExceptionUtils.findInCauseChain;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrades grouped histograms actually persisted by release 2026.2.18, inspecting the original storage parts
 * before any migration. Cleanup publishes once, removes the grouped histograms from the group membership indexes
 * without filling any group store, and preserves the ungrouped histogram. An ordinary update withdrawing a grouped
 * value the release recorded is therefore refused; replacing the catalog from the recipe's source data builds the
 * group stores and allows the same update.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Grouped histograms written by the released engine")
@Tag(STORAGE)
@Tag(HISTOGRAM)
@Isolated
class Release2026_2GroupedHistogramUpgradeTest implements EvitaTestSupport {
	/** Name of the release fixture and of the catalog it holds. */
	private static final String FIXTURE = "groupedHistogram";
	/**
	 * Complete public answer of the ungrouped `tag` histogram, captured from the generated image using the 2026.2.18
	 * engine, in the {@link #histogramAnswer} format
	 * `min|max|overallCount|threshold:occurrences:relativeFrequency,...`.
	 */
	private static final String RELEASE_UNGROUPED_ANSWER =
		"5|9|2|5:1:50.00,6:0:0.00,7:0:0.00,8:0:0.00,9:1:50.00";
	/**
	 * System property {@link io.evitadb.index.mutation.local.MissingHistogramValueBehavior} reads once when an engine
	 * starts: unset, a write withdrawing a missing histogram value is refused; `REPORT` logs it and continues.
	 */
	private static final String PROPERTY = "evitadb.referenceHistogram.missingValueBehavior";
	/** The two group membership index kinds whose manifests the release filled with grouped histogram keys. */
	private static final Set<EntityIndexType> LEGACY_GROUP_KINDS =
		Set.of(EntityIndexType.REFERENCED_GROUP_ENTITY, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE);
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);
	/** Fixture copies and replacement catalogs the test created, deleted after it. */
	private final List<TestPaths> paths = new ArrayList<>(2);
	/** Value of {@link #PROPERTY} before the test, restored after it; `null` when it was unset. */
	@Nullable private String originalProperty;

	/** Uses the ordinary refusing policy independently of the invoking environment. */
	@BeforeEach
	void rememberProperty() {
		this.originalProperty = System.getProperty(PROPERTY);
		System.clearProperty(PROPERTY);
	}

	/**
	 * Restores {@link #PROPERTY} to its value before the test, or clears it when it was unset, and deletes all
	 * private fixture copies.
	 */
	@AfterEach
	void cleanUp() {
		if (this.originalProperty == null) {
			System.clearProperty(PROPERTY);
		} else {
			System.setProperty(PROPERTY, this.originalProperty);
		}
		this.paths.forEach(this.harness::cleanup);
	}

	/** Pins the legacy manifests and readable roots in the untouched release image through the storage API. */
	@Test
	@DisplayName("the release persisted histogram keys and roots in both grouped manifest kinds")
	void shouldContainBothLegacyGroupedHistogramKindsBeforeUpgrade() {
		final TestPaths copy = copy();
		final Map<String, OfflineCatalogImages.StoredFile> before = OfflineCatalogImages.storedFiles(
			ReleaseFixtureHarness.catalogDirectory(copy, FIXTURE));
		final LegacyHistograms legacy = readReleaseHistograms(copy);
		assertEquals(LEGACY_GROUP_KINDS, legacy.grouped().stream().map(LegacyRoot::kind).collect(toSet()));
		assertEquals(before, OfflineCatalogImages.storedFiles(
			ReleaseFixtureHarness.catalogDirectory(copy, FIXTURE)), "pre-upgrade inspection must write nothing");
	}

	/** Verifies a single protocol-9 publication removes the original roots and keys without filling any store. */
	@Test
	@DisplayName("one publication removes grouped histograms and reload preserves the ungrouped answer")
	void shouldCleanReleaseHistogramsAndReloadWithEmptyGroupStore() {
		final TestPaths copy = copy();
		final LegacyHistograms legacy = readReleaseHistograms(copy);
		final List<CatalogBootstrap> before = this.harness.bootstrapRecords(copy, FIXTURE);
		// opening the catalog offline is what migrates it from protocol 6 to 9 and publishes the upgrade
		withOfflineCatalog(configuration(copy), FIXTURE, service -> {
			final long version = service.getLastCatalogVersion();
			assertEquals(9, service.getCatalogHeader(version).storageProtocolVersion());
			final CollectionFileReference reference =
				service.getCatalogHeader(version).collectionFileIndex().get(PRODUCT);
			final DefaultEntityCollectionPersistenceService collection = service
				.getOrCreateEntityCollectionPersistenceService(version, PRODUCT, reference.entityTypePrimaryKey());
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			for (final LegacyRoot root : legacy.grouped()) {
				final EntityIndexStoragePart manifest = requireNonNull(parts.getStoragePart(
					version, root.indexId(), EntityIndexStoragePart.class));
				assertTrue(manifest.getHistogramIndexes().isEmpty(),
					root.kind() + " still lists grouped histogram keys: " + manifest.getHistogramIndexes());
				assertNull(parts.getStoragePart(version, root.rootId(), HistogramIndexStoragePart.class),
					root.kind() + " still owns its released root");
				assertNull(parts.getStoragePart(version, root.rootId(), HistogramCardinalityStoragePart.class));
			}
			final LegacyRoot ungrouped = legacy.ungrouped();
			final EntityIndexStoragePart kept = requireNonNull(parts.getStoragePart(
				version, ungrouped.indexId(), EntityIndexStoragePart.class));
			assertEquals(Set.of(ungrouped.key()), kept.getHistogramIndexes());
			assertEquals(legacy.ungroupedValues(), values(requireNonNull(parts.getStoragePart(
				version, ungrouped.rootId(), HistogramIndexStoragePart.class))));
			for (final int indexId : collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()) {
				assertNotEquals(EntityIndexType.REFERENCED_GROUP_HISTOGRAM,
					requireNonNull(parts.getStoragePart(version, indexId, EntityIndexStoragePart.class))
						.getEntityIndexKey().type(), "upgrade must fill no group store");
			}
			return Boolean.TRUE;
		});
		final List<CatalogBootstrap> after = this.harness.bootstrapRecords(copy, FIXTURE);
		assertEquals(before.size() + 1, after.size(), "exactly one upgrade publication");
		assertEquals(before, after.subList(0, before.size()));
		assertEquals(9, after.getLast().storageProtocolVersion());
		for (int boot = 0; boot < 2; boot++) {
			try (Evita evita = this.harness.boot(copy)) {
				ReleaseFixtureHarness.requireAlive(evita, FIXTURE);
				assertEquals(9, ReleaseFixtureHarness.headerStorageProtocol(evita, FIXTURE));
				assertNull(ReleaseFixtureHarness.entityCollection(evita, FIXTURE, PRODUCT)
					.getIndexByKeyIfExists(GroupHistogramEntityIndex.keyOf(BRAND, 10, Scope.LIVE)));
				assertNull(histogram(evita, BRAND, 10));
				final HistogramContract ungrouped = requireNonNull(histogram(evita, "tag", null));
				assertEquals(RELEASE_UNGROUPED_ANSWER, histogramAnswer(ungrouped));
				assertHistogramMatchesValues(ungrouped, legacy.ungroupedValues());
			}
		}
		assertEquals(after, this.harness.bootstrapRecords(copy, FIXTURE), "reload must not republish");
	}

	/**
	 * The upgrade removed the grouped histograms the release recorded and filled no group store, so changing owner
	 * 1's share from 5 to 7 withdraws value 5 from a group store that does not exist. An ordinary update refuses
	 * with the exact missing contribution and leaves the stored share and both histogram answers as the upgrade left
	 * them, also after reload.
	 */
	@Test
	@DisplayName("a missing grouped contribution refuses the update and preserves the stored share")
	void shouldRefuseMissingGroupedWithdrawalAndPreserveStoredValue() {
		final TestPaths copy = copy();
		try (Evita evita = this.harness.boot(copy)) {
			ReleaseFixtureHarness.requireAlive(evita, FIXTURE);
			assertStoredShare(evita, 5);
			final RuntimeException refusal = assertThrows(RuntimeException.class, () -> updateGroupedShare(evita));
			final GenericEvitaInternalError missingContribution =
				findInCauseChain(refusal, GenericEvitaInternalError.class);
			assertNotNull(missingContribution, "the refusal must identify a missing histogram contribution");
			assertEquals(
				"Cannot withdraw histogram value: entity type `product`, owner primary key 1, " +
					"reference brand: 1/1 (internal primary key 1), group 10, histogram `shares`, " +
					"locale null, value 5, histogram index primary key -1, scope LIVE. " +
					"The index, histogram, locale bucket and value-owner pair must exist for withdrawal.",
				missingContribution.getMessage()
			);
			assertStoredShare(evita, 5);
			assertNull(histogram(evita, BRAND, 10));
			assertEquals(RELEASE_UNGROUPED_ANSWER, histogramAnswer(requireNonNull(histogram(evita, "tag", null))));
		}
		try (Evita evita = this.harness.boot(copy)) {
			ReleaseFixtureHarness.requireAlive(evita, FIXTURE);
			assertStoredShare(evita, 5);
			assertNull(histogram(evita, BRAND, 10));
			assertEquals(RELEASE_UNGROUPED_ANSWER, histogramAnswer(requireNonNull(histogram(evita, "tag", null))));
		}
	}

	/**
	 * Replaces the catalog from source in a new catalog directory, not the upgraded copy, and applies the same 5-to-7
	 * update the upgraded copy refuses. The replacement's group store, grouped values and unchanged ungrouped answer
	 * must survive reload.
	 */
	@Test
	@DisplayName("replacing the catalog from source permits the same update and preserves answers after reload")
	void shouldAllowSameUpdateAfterReplacingCatalogFromSource() {
		final TestPaths replacement = createTestPaths("ReleaseGroupedHistogramReplacedFromSource");
		this.paths.add(replacement);
		try (Evita evita = this.harness.boot(replacement)) {
			ReleaseFixtureWriter.replay(evita, ReleaseFixtureHarness.recipe(FIXTURE));
			ReleaseFixtureHarness.requireAlive(evita, FIXTURE);
			assertStoredShare(evita, 5);
			assertHistogramMatchesValues(
				requireNonNull(histogram(evita, BRAND, 10)), Map.of(5, List.of(1), 9, List.of(2)));
			assertDoesNotThrow(() -> updateGroupedShare(evita));
			assertUpdatedSourceCatalog(evita);
		}
		try (Evita evita = this.harness.boot(replacement)) {
			ReleaseFixtureHarness.requireAlive(evita, FIXTURE);
			assertNotNull(ReleaseFixtureHarness.entityCollection(evita, FIXTURE, PRODUCT)
					.getIndexByKeyIfExists(GroupHistogramEntityIndex.keyOf(BRAND, 10, Scope.LIVE)),
				"the catalog replaced from source keeps its group store under this key");
			assertUpdatedSourceCatalog(evita);
		}
	}

	/** Checks the owner, grouped reference and its persisted source value through a separate read session. */
	private static void assertStoredShare(@Nonnull Evita evita, int share) {
		evita.queryCatalog(FIXTURE, session -> {
			final SealedEntity owner = session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow();
			assertEquals(1, owner.getPrimaryKey());
			final ReferenceContract reference = owner.getReference(BRAND, 1).orElseThrow();
			assertEquals(BRAND, reference.getReferenceName());
			assertEquals(1, reference.getReferencedPrimaryKey());
			assertEquals(10, reference.getGroup().orElseThrow().getPrimaryKey());
			assertEquals(share, reference.<Integer>getAttribute("share"));
			return Boolean.TRUE;
		});
	}

	/** Applies the identical grouped share update in both the refusal and source-replacement cells. */
	private static void updateGroupedShare(@Nonnull Evita evita) {
		evita.updateCatalog(FIXTURE, session -> {
			session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.updateReference(BRAND, 1, ref -> ref.setAttribute("share", 7)).upsertVia(session);
		});
	}

	/** Checks the source replacement after the update, including its independent ungrouped histogram. */
	private static void assertUpdatedSourceCatalog(@Nonnull Evita evita) {
		assertStoredShare(evita, 7);
		assertHistogramMatchesValues(requireNonNull(histogram(evita, BRAND, 10)), Map.of(7, List.of(1), 9, List.of(2)));
		assertEquals(RELEASE_UNGROUPED_ANSWER, histogramAnswer(requireNonNull(histogram(evita, "tag", null))));
	}

	/** Copies the generated release image and registers it for cleanup. */
	@Nonnull
	private TestPaths copy() {
		final TestPaths result = this.harness.copy(FIXTURE);
		this.paths.add(result);
		return result;
	}

	/** Provides configuration for offline storage readers using the same private image directories. */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths copy) {
		return newTestEvitaConfigurationBuilder(copy).build();
	}

	/** Reads protocol 6 directly below the migrating catalog service and proves that every listed root exists. */
	@Nonnull
	private LegacyHistograms readReleaseHistograms(@Nonnull TestPaths copy) {
		assertEquals(6, this.harness.lastBootstrapStorageProtocol(copy, FIXTURE));
		return withReleaseCollection(copy, collection -> {
			final long version = this.harness.bootstrapRecords(copy, FIXTURE).getLast().catalogVersion();
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			final List<LegacyRoot> grouped = new ArrayList<>();
			LegacyRoot ungrouped = null;
			Map<Integer, List<Integer>> ungroupedValues = null;
			for (final int indexId : collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()) {
				final EntityIndexStoragePart manifest = requireNonNull(parts.getStoragePart(
					version, indexId, EntityIndexStoragePart.class));
				final EntityIndexType kind = manifest.getEntityIndexKey().type();
				assertNotEquals(EntityIndexType.REFERENCED_GROUP_HISTOGRAM, kind);
				for (final HistogramIndexStorageKey key : manifest.getHistogramIndexes()) {
					final long id = key.computeUniquePartId(indexId, parts.getReadOnlyKeyCompressor());
					final HistogramIndexStoragePart root = requireNonNull(parts.getStoragePart(
						version, id, HistogramIndexStoragePart.class), "release root " + kind + " " + key);
					final LegacyRoot address = new LegacyRoot(indexId, kind, key, id);
					assertEquals("shares", key.histogramName());
					if (LEGACY_GROUP_KINDS.contains(kind)) {
						assertFalse(values(root).isEmpty(), "release grouped histogram must contain values");
						assertNotNull(parts.getStoragePart(version, id, HistogramCardinalityStoragePart.class),
							"release grouped histogram must have its cardinality sibling: " + kind + " " + key);
						grouped.add(address);
					} else if (kind == EntityIndexType.REFERENCED_ENTITY_TYPE &&
						"tag".equals(manifest.getEntityIndexKey().referenceName())) {
						assertNull(ungrouped, "one ungrouped histogram root");
						ungrouped = address;
						ungroupedValues = values(root);
					}
				}
			}
			assertEquals(LEGACY_GROUP_KINDS, grouped.stream().map(LegacyRoot::kind).collect(toSet()),
				"the released engine must write both legacy group manifest kinds");
			assertEquals(Map.of(5, List.of(1), 9, List.of(2)), ungroupedValues);
			return new LegacyHistograms(List.copyOf(grouped), requireNonNull(ungrouped),
				requireNonNull(ungroupedValues));
		});
	}

	/**
	 * Opens the published release catalog and the product collection offset indexes directly, below
	 * `DefaultCatalogPersistenceService`, with the engine stopped, so no migration runs and no part is rewritten.
	 * Unlike the migration, it opens the stored collection header as it is, without reconciling it against the
	 * catalog header.
	 */
	@Nonnull
	private <T> T withReleaseCollection(
		@Nonnull TestPaths copy, @Nonnull Function<DefaultEntityCollectionPersistenceService, T> work
	) {
		final EvitaConfiguration configuration = configuration(copy);
		final StorageSettings settings = new StorageSettings(configuration.storage(), configuration.transaction());
		final CatalogBootstrap bootstrap = this.harness.bootstrapRecords(copy, FIXTURE).getLast();
		final Path directory = ReleaseFixtureHarness.catalogDirectory(copy, FIXTURE);
		final Scheduler scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1));
		final OffsetIndexRecordTypeRegistry registry = new OffsetIndexRecordTypeRegistry();
		try (
			CatalogOffHeapMemoryManager memory = new CatalogOffHeapMemoryManager(FIXTURE, 0, 0, settings);
			ObservableOutputKeeper outputs = new ObservableOutputKeeper(
				FIXTURE, settings.lockTimeoutSeconds(), settings.waitOnCloseSeconds(), scheduler);
			CatalogOffsetIndexStoragePartPersistenceService catalog =
				CatalogOffsetIndexStoragePartPersistenceService.create(
					FIXTURE, directory.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(
						FIXTURE, bootstrap.catalogFileIndex())), settings, bootstrap, registry, memory, outputs,
					DefaultCatalogPersistenceService.VERSIONED_KRYO_FACTORY, null, null, null)
		) {
			final long version = bootstrap.catalogVersion();
			assertEquals(6, catalog.getCatalogHeader(version).storageProtocolVersion());
			final CollectionFileReference reference =
				catalog.getCatalogHeader(version).collectionFileIndex().get(PRODUCT);
			final EntityCollectionFileHeader header = requireNonNull(catalog.getStoragePart(
				version, reference.entityTypePrimaryKey(), EntityCollectionFileHeader.class));
			try (DefaultEntityCollectionPersistenceService collection = new DefaultEntityCollectionPersistenceService(
				version, FIXTURE, directory, header, settings, memory, outputs, registry, null)) {
				return work.apply(collection);
			}
		} finally {
			scheduler.shutdownNow();
			try {
				assertTrue(scheduler.awaitTermination(30, TimeUnit.SECONDS));
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
	}

	/** Extracts each exact numeric value with its owners' primary keys from the release's inline integer histogram. */
	@Nonnull
	private static Map<Integer, List<Integer>> values(@Nonnull HistogramIndexStoragePart root) {
		assertEquals(Integer.class, root.getValueType());
		assertFalse(root.isPaged());
		final Map<Integer, List<Integer>> values = new TreeMap<>();
		for (final ValueToRecordBitmap point : root.getHistogramPoints()) {
			values.put((Integer) point.getValue(), Arrays.stream(point.getRecordIds().getArray()).boxed().toList());
		}
		return values;
	}

	/**
	 * Queries the public reference summary for the histogram of one group of the reference, or for the reference's
	 * non-grouped statistics when `group` is null.
	 */
	@Nullable
	private static HistogramContract histogram(
		@Nonnull Evita evita, @Nonnull String reference, @Nullable Integer group
	) {
		return evita.queryCatalog(FIXTURE, session -> {
			final ReferenceSummary summary = session.query(query(collection(PRODUCT), require(
				referenceSummaryOfReferenceWithHistograms(reference, FacetStatisticsDepth.COUNTS,
					null, null, histogramStatistics(10, "shares")))), EntityReference.class)
				.getExtraResult(ReferenceSummary.class);
			final ReferenceSummary.ReferenceGroupStatistics statistics = group == null ?
				summary.getReferenceGroupStatistics(reference) : summary.getReferenceGroupStatistics(reference, group);
			return statistics == null ? null : statistics.getHistogramStatistics("shares");
		});
	}

	/** Describes every public bucket, including empty buckets and frequencies, in the release probe's format. */
	@Nonnull
	private static String histogramAnswer(@Nonnull HistogramContract histogram) {
		return histogram.getMin() + "|" + histogram.getMax() + "|" + histogram.getOverallCount() + "|" +
			Arrays.stream(histogram.getBuckets()).map(bucket -> bucket.threshold() + ":" +
				bucket.occurrences() + ":" + bucket.relativeFrequency()).collect(joining(","));
	}

	/** Verifies the complete public histogram against the original persisted point values and owner occurrences. */
	private static void assertHistogramMatchesValues(
		@Nonnull HistogramContract histogram, @Nonnull Map<Integer, List<Integer>> values
	) {
		assertEquals(0, BigDecimal.valueOf(Collections.min(values.keySet())).compareTo(histogram.getMin()));
		assertEquals(0, BigDecimal.valueOf(Collections.max(values.keySet())).compareTo(histogram.getMax()));
		assertEquals(values.values().stream().mapToInt(List::size).sum(), histogram.getOverallCount());
		final HistogramContract.Bucket[] buckets = histogram.getBuckets();
		for (int i = 0; i < buckets.length; i++) {
			final BigDecimal lower = buckets[i].threshold();
			final BigDecimal upper = i + 1 == buckets.length ? null : buckets[i + 1].threshold();
			final int expected = values.entrySet().stream().filter(entry -> {
				final BigDecimal value = BigDecimal.valueOf(entry.getKey());
				return value.compareTo(lower) >= 0 && (upper == null || value.compareTo(upper) < 0);
			}).mapToInt(entry -> entry.getValue().size()).sum();
			assertEquals(expected, buckets[i].occurrences(), "bucket at " + lower);
		}
	}

	/**
	 * The original manifest kind, listed key and root address, all obtained from release-written parts.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record LegacyRoot(int indexId, @Nonnull EntityIndexType kind, @Nonnull HistogramIndexStorageKey key,
		long rootId) { }

	/**
	 * Original grouped addresses and the independent ungrouped root's complete value-to-owner map.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record LegacyHistograms(@Nonnull List<LegacyRoot> grouped, @Nonnull LegacyRoot ungrouped,
		@Nonnull Map<Integer, List<Integer>> ungroupedValues) { }
}
