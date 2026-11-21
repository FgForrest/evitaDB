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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.component.loader.VirtualReferencePartitionLoader;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.upgrade.ReleaseFixtureOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.pks;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.probe;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.removeBrand;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.setAttribute;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.update;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.BRAND;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.inRange;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.orderedInEnglish;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.*;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks publication and lossless query comparisons for catalogs written by released 2026.2 and 2026.1 engines.
 * Successful loading alone does not promise query parity for potentially lossy fields. Each scenario uses a fixture
 * under `testData/release_upgrade_fixtures`, written during the build by the released engines from a recipe in
 * `io.evitadb.test.upgrade`; {@link ReleaseFixtureHarness} opens a copy of it and replays the recipe into a fresh
 * catalog for comparison.
 *
 * Every fixture is checked to arrive at the storage protocol its origin wrote. Accepted loads and lossless
 * comparisons live here and in `Release2026_2IndexUpgradeTest`; deliberate refusals and conditional warnings live
 * in the migration preflight and report tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalogs written by released engines behave correctly after the upgrade")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class Release2026_2UpgradeDefectsTest implements EvitaTestSupport {
	private static final String SHARED_PREDECESSOR = "sharedReferencedEntityPredecessor";
	private static final String PARTITIONED_PREDECESSOR = "partitionedReferencedEntityPredecessor";
	private static final String ORDERED_BRANDS = "orderedBrands";
	private static final AttributeIndexKey BRAND_POSITION = new AttributeIndexKey(BRAND, "position", null);

	/** A moment inside both open-bound twins. */
	private static final OffsetDateTime MID_2025 = BASE.minusMonths(7);

	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/**
	 * Returns every fixture name.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> allFixtures() {
		return Stream.of(
			"d1a", "d1b", "d1b2", "d1c", "d2", "d3", "d3p", "d4", "d4c", "d4t", "d4f", "d4cp", "d5f", "d5s", "dF1p",
			"dS1p", "dX", "dv", "dN1h", "d14", "d2p", "dDel", "d6", "d7", "d8", "d16", "d16n", "d17", "d17a", "d5r",
			"dK1h", "dGrp", "groupedHistogram", "dUw", "drf", "dRR", "dRng", "d9", "d9o", "d10", "d10s", "d11", "d11t",
			"dU", "d6c", "d12", "d12b", "d13", "d15", SHARED_PREDECESSOR, PARTITIONED_PREDECESSOR
		);
	}

	/**
	 * Returns the fixtures the current engine loads after the upgrade, among those the upgrade migrates rather than
	 * refuses.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> loadingFixtures() {
		return Stream.of(
			"d1a", "d1c", "d2", "d3", "d3p", "d4", "d4c", "d4t", "d4f", "d5f", "d5s", "dF1p", "dS1p", "dX", "dv",
			"dN1h", "d14", "d2p", "dDel", "d16", "d16n", "d5r", "dK1h", "dGrp", "groupedHistogram", "dUw", "drf",
			"dRR", "dRng", "dU",
			"d12", "d12b", "d15", "d4cp", "d7", "d17", "d17a", "d10s", SHARED_PREDECESSOR, PARTITIONED_PREDECESSOR
		);
	}

	/**
	 * Returns the fixtures whose upgraded catalog already answers every probe of its recipe as a fresh current engine
	 * fed the same recipe does. The comparison checks only primary keys and discards histogram extra results, so the
	 * grouped-histogram fixture stays excluded: its upgraded catalog answers the grouped histogram with none where a
	 * fresh engine has one, which {@link Release2026_2GroupedHistogramUpgradeTest} asserts.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> freshEquivalentFixtures() {
		return Stream.of(
			"d1a", "d1c", "d3p", "d4t", "dF1p", "dS1p", "dv", "dN1h", "dDel", "d16n", "dK1h", "dU",
			"d3", "d4c", "d4cp", "dRng", "d10s"
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allFixtures")
	@DisplayName("every fixture arrives with the storage protocol its origin wrote")
	void shouldCarryReleaseStorageProtocolWhenFixtureIsCopied(@Nonnull String fixture) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			final int expected = ReleaseFixtureHarness.recipe(fixture).origin() == ReleaseFixtureOrigin.RELEASE_2026_1
				? 4 : 6;
			assertEquals(expected, this.harness.lastBootstrapStorageProtocol(paths, fixture));
		} finally {
			this.harness.cleanup(paths);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("loadingFixtures")
	@DisplayName("the fixture loads after the upgrade with its header at storage protocol 9")
	void shouldLoadWhenFixtureIsUpgraded(@Nonnull String fixture) {
		this.harness.withUpgradedCatalog(
			fixture, evita -> assertEquals(9, ReleaseFixtureHarness.headerStorageProtocol(evita, fixture))
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("freshEquivalentFixtures")
	@DisplayName("every probe of the upgraded fixture answers as on a fresh engine fed the same recipe")
	void shouldAnswerProbesAsFreshEngineWhenFixtureIsUpgraded(@Nonnull String fixture) {
		final AtomicReference<List<String>> fresh = new AtomicReference<>();
		this.harness.withFreshCatalog(fixture, evita -> fresh.set(probe(evita, fixture)));
		this.harness.withUpgradedCatalog(fixture, evita -> assertEquals(fresh.get(), probe(evita, fixture)));
	}

	/**
	 * Moves owners of a release-era chain across scopes, reloads the archive, then promotes and demotes its partition.
	 */
	@Test
	@Tag(REFERENCE)
	@DisplayName("Can archive and restore the owners of a release-era shared predecessor")
	void shouldMoveOwnersAndRebuildTheirPartitionAfterUpgrade() {
		this.harness.withFreshCatalog(SHARED_PREDECESSOR, evita -> {
			assertBrandOrder(evita, Scope.LIVE, 2, List.of(3, 1));
			assertReferenceOrder(evita, Scope.LIVE, 1, ORDERED_BRANDS, List.of(2, 1));
		});
		final TestPaths paths = this.harness.copy(SHARED_PREDECESSOR);
		try {
			assertEquals(6, this.harness.lastBootstrapStorageProtocol(paths, SHARED_PREDECESSOR));
			final List<PersistedChain> legacy = this.harness.readPublishedCollection(
				paths, SHARED_PREDECESSOR, PRODUCT, (version, collection) -> {
					final List<PersistedChain> chains = persistedChains(version, collection);
					assertTrue(chains.stream().anyMatch(chain -> chain.key().equals(sharedBrandKey(Scope.LIVE))));
					assertTrue(chains.stream().anyMatch(chain -> ORDERED_BRANDS.equals(chain.key().referenceName())));
					assertTrue(chains.stream().allMatch(
						chain -> chain.key().type() == EntityIndexType.REFERENCED_ENTITY
					));
					return chains;
				}
			);
			OfflineCatalogImages.withOfflineCatalog(
				newTestEvitaConfigurationBuilder(paths).build(), SHARED_PREDECESSOR, service -> Boolean.TRUE
			);
			this.harness.readPublishedCollection(paths, SHARED_PREDECESSOR, PRODUCT, (version, collection) -> {
				assertChainsRemoved(version, collection, legacy, 2);
				return Boolean.TRUE;
			});
			try (final Evita evita = this.harness.boot(paths)) {
				ReleaseFixtureHarness.requireAlive(evita, SHARED_PREDECESSOR);
				final EntityIndex original = sharedBrandPartition(evita, Scope.LIVE);
				assertNotNull(original);
				assertNull(original.getChainIndex(BRAND_POSITION));
				assertBrandOrder(evita, Scope.LIVE, 2, List.of(3, 1));
				assertReferenceOrder(evita, Scope.LIVE, 1, ORDERED_BRANDS, List.of(2, 1));
				evita.updateCatalog(SHARED_PREDECESSOR, session -> {
					assertTrue(session.archiveEntity(PRODUCT, 1));
				});
				assertNull(sharedBrandPartition(evita, Scope.LIVE));
				assertBrandOrder(evita, Scope.LIVE, 2, List.of(3, 1));
				evita.updateCatalog(SHARED_PREDECESSOR, session -> {
					assertTrue(session.archiveEntity(PRODUCT, 2));
				});
			}
			try (final Evita evita = this.harness.boot(paths)) {
				ReleaseFixtureHarness.requireAlive(evita, SHARED_PREDECESSOR);
				assertBrandOrder(evita, Scope.ARCHIVED, 1, List.of(1, 2));
				assertBrandOrder(evita, Scope.ARCHIVED, 2, List.of(3, 1));
				evita.updateCatalog(SHARED_PREDECESSOR, session -> {
					assertTrue(session.restoreEntity(PRODUCT, 1));
					assertTrue(session.restoreEntity(PRODUCT, 2));
				});
				final EntityIndex promoted = sharedBrandPartition(evita, Scope.LIVE);
				assertNotNull(promoted);
				assertNull(promoted.getChainIndex(BRAND_POSITION));
				evita.updateCatalog(SHARED_PREDECESSOR, session -> {
					assertTrue(session.archiveEntity(PRODUCT, 1));
				});
				assertNull(sharedBrandPartition(evita, Scope.LIVE));
				final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) productCollection(evita)
					.getIndexByKeyIfExists(
						new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, BRAND)
					);
				assertNotNull(typeIndex);
				assertTrue(typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(promoted.getPrimaryKey())
					.isPresent());
				assertTrue(typeIndex.getVirtualReferencePartitionIndex().getDeclaredChainKeys().isEmpty());
				assertBrandOrder(evita, Scope.LIVE, 2, List.of(3, 1));
			}
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** Verifies the upgrade drops the ownerless partition and keeps the partition with an owner. */
	@Test
	@Tag(REFERENCE)
	@DisplayName("Upgrading retires a partition whose only remaining content was a reference predecessor chain")
	void shouldRetireOwnerlessPartitionAfterUpgrade() {
		final TestPaths paths = this.harness.copy(PARTITIONED_PREDECESSOR);
		try {
			final List<PersistedChain> legacy = this.harness.readPublishedCollection(
				paths, PARTITIONED_PREDECESSOR, PRODUCT, (version, collection) -> {
					final List<PersistedChain> chains = persistedChains(version, collection);
					assertEquals(2, chains.size());
					assertEquals(sharedBrandKey(Scope.LIVE), chains.getFirst().key());
					final EntityIdsStoragePart membership = collection.getStoragePartPersistenceService()
						.getStoragePart(version, chains.getFirst().indexId(), EntityIdsStoragePart.class);
					assertTrue(membership == null || membership.getEntityIds().isEmpty());
					return chains;
				}
			);
			OfflineCatalogImages.withOfflineCatalog(
				newTestEvitaConfigurationBuilder(paths).build(), PARTITIONED_PREDECESSOR, service -> Boolean.TRUE
			);
			this.harness.readPublishedCollection(paths, PARTITIONED_PREDECESSOR, PRODUCT, (version, collection) -> {
				final int orphan = legacy.getFirst().indexId();
				final List<Integer> indexes = collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys();
				assertFalse(indexes.contains(orphan), "the ownerless partition must leave the published header");
				assertTrue(indexes.contains(legacy.getLast().indexId()));
				final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
				assertNull(parts.getStoragePart(version, orphan, EntityIndexStoragePart.class));
				assertNull(parts.getStoragePart(version, orphan, EntityIdsStoragePart.class));
				assertChainsRemoved(version, collection, legacy, 0);
				return Boolean.TRUE;
			});
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/**
	 * Reads every chain manifest entry in the product collection and verifies that each names an existing root.
	 *
	 * @param version published catalog version
	 * @param collection product collection
	 * @return the persisted chains, ordered by index key
	 */
	@Nonnull
	private static List<PersistedChain> persistedChains(
		final long version, @Nonnull final DefaultEntityCollectionPersistenceService collection
	) {
		final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
		final List<PersistedChain> chains = new ArrayList<>();
		for (final int indexId : collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()) {
			final EntityIndexStoragePart manifest = requireNonNull(
				parts.getStoragePart(version, indexId, EntityIndexStoragePart.class)
			);
			for (final AttributeIndexStorageKey entry : manifest.getAttributeIndexes()) {
				if (entry.indexType() == AttributeIndexType.CHAIN) {
					final long rootId = AttributeIndexStoragePart.computeUniquePartId(
						indexId, AttributeIndexType.CHAIN, entry.attribute(), parts.getReadOnlyKeyCompressor()
					);
					assertNotNull(parts.getStoragePart(version, rootId, ChainIndexStoragePart.class));
					chains.add(new PersistedChain(indexId, manifest.getEntityIndexKey(), rootId));
				}
			}
		}
		chains.sort(Comparator.comparing(PersistedChain::key));
		return chains;
	}

	/**
	 * Checks that the published offset index has neither chain manifests and roots nor chain slots.
	 *
	 * @param version published catalog version
	 * @param collection product collection
	 * @param legacy the chains listed in the release image
	 * @param expectedVirtualPartitions expected number of virtual partitions of the live brand reference
	 */
	private static void assertChainsRemoved(
		final long version, @Nonnull final DefaultEntityCollectionPersistenceService collection,
		@Nonnull final List<PersistedChain> legacy, final int expectedVirtualPartitions
	) {
		assertEquals(List.of(), persistedChains(version, collection));
		final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
		for (final PersistedChain chain : legacy) {
			assertNull(parts.getStoragePart(version, chain.rootId(), ChainIndexStoragePart.class), chain.toString());
		}
		int brandPartitions = -1;
		for (final int indexId : collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()) {
			final EntityIndexStoragePart manifest = requireNonNull(
				parts.getStoragePart(version, indexId, EntityIndexStoragePart.class)
			);
			final EntityIndexKey key = manifest.getEntityIndexKey();
			if (key.type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
				final VirtualReferencePartitionIndex registry = VirtualReferencePartitionLoader.loadIndex(
					parts, version, indexId, requireNonNull(key.referenceName())
				);
				assertTrue(registry.getDeclaredChainKeys().isEmpty(), key.toString());
				if (BRAND.equals(key.referenceName()) && key.scope() == Scope.LIVE) {
					brandPartitions = registry.countRegisteredPartitions();
				}
			}
		}
		assertEquals(expectedVirtualPartitions, brandPartitions);
	}

	/**
	 * Identifies the partition for brand 1.
	 *
	 * @param scope scope of the partition
	 * @return partition index key
	 */
	@Nonnull
	private static EntityIndexKey sharedBrandKey(@Nonnull final Scope scope) {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, scope, new RepresentativeReferenceKey(new ReferenceKey(BRAND, 1))
		);
	}

	/**
	 * The published address of a chain root.
	 *
	 * @param indexId owning index primary key
	 * @param key owning index key
	 * @param rootId chain root storage part primary key
	 */
	private record PersistedChain(int indexId, @Nonnull EntityIndexKey key, long rootId) {
	}

	/**
	 * Finds the materialized partition for the brand both owners reference.
	 *
	 * @param evita engine with the upgraded catalog
	 * @param scope scope of the owners
	 * @return partition index, or null when the partition has no index object
	 */
	@Nullable
	private static EntityIndex sharedBrandPartition(@Nonnull Evita evita, @Nonnull Scope scope) {
		return productCollection(evita).getIndexByKeyIfExists(sharedBrandKey(scope));
	}

	/**
	 * Returns the product collection carrying the reference lists.
	 *
	 * @param evita engine with the upgraded catalog
	 * @return product collection
	 */
	@Nonnull
	private static EntityCollection productCollection(@Nonnull Evita evita) {
		return ((Catalog) evita.getCatalogInstance(SHARED_PREDECESSOR).orElseThrow())
			.getCollectionForEntityInternal(PRODUCT).orElseThrow();
	}

	/**
	 * Checks the order of an owner's brands after a scope change or restart.
	 *
	 * @param evita engine with the upgraded catalog
	 * @param scope scope of the owner
	 * @param owner owner primary key
	 * @param expected ordered brand keys
	 */
	private static void assertBrandOrder(
		@Nonnull Evita evita, @Nonnull Scope scope, int owner, @Nonnull List<Integer> expected
	) {
		assertReferenceOrder(evita, scope, owner, BRAND, expected);
	}

	/**
	 * Checks the order of an owner's references in either the fresh or the upgraded catalog.
	 *
	 * @param evita engine holding the catalog
	 * @param scope scope of the owner
	 * @param owner owner primary key
	 * @param reference reference to fetch
	 * @param expected ordered target keys
	 */
	private static void assertReferenceOrder(
		@Nonnull final Evita evita, @Nonnull final Scope scope, final int owner, @Nonnull final String reference,
		@Nonnull final List<Integer> expected
	) {
		final List<SealedEntity> owners = evita.queryCatalog(
			SHARED_PREDECESSOR, (EvitaSessionContract session) -> session.queryListOfSealedEntities(query(
				collection(PRODUCT), filterBy(scope(scope), entityPrimaryKeyInSet(owner)),
				require(entityFetch(referenceContent(reference, orderBy(attributeNatural("position")))))
			))
		);
		assertEquals(expected, owners.getFirst().getReferences(reference).stream()
			.map(ReferenceContract::getReferencedPrimaryKey).toList());
	}

	/** Loads and probes the release catalogs whose stored index order the upgrade rewrites. */
	@Nested
	@DisplayName("Stored index ordering")
	class StoredIndexOrdering {

		/** Verifies each catalog publishes the current storage protocol and loads successfully. */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"d1b", "d1b2", "d9", "d9o", "d13"})
		void shouldLoadWhenFixtureIsUpgraded(@Nonnull String fixture) {
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				fixture, evita -> assertEquals(9, ReleaseFixtureHarness.headerStorageProtocol(evita, fixture))
			);
		}

		/** Verifies the upgraded indexes answer every recipe probe as a fresh replay does. */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"d1b", "d1b2", "d9", "d9o", "d13"})
		void shouldAnswerProbesAsFreshEngineWhenFixtureIsUpgraded(@Nonnull String fixture) {
			final AtomicReference<List<String>> fresh = new AtomicReference<>();
			Release2026_2UpgradeDefectsTest.this.harness.withFreshCatalog(
				fixture, evita -> fresh.set(probe(evita, fixture))
			);
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				fixture, evita -> assertEquals(fresh.get(), probe(evita, fixture))
			);
		}
	}

	@Nested
	@DisplayName("Storage protocol and publication")
	class StorageProtocol {

		@Test
		@DisplayName("a native 2026.2 catalog is republished at protocol 9 on top of its release bootstrap records")
		void shouldStampProtocolNineWhenNativeCatalogIsUpgraded() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final TestPaths paths = harness.copy("dU");
			try {
				final List<CatalogBootstrap> before = harness.bootstrapRecords(paths, "dU");
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, "dU");
					assertEquals(9, ReleaseFixtureHarness.headerStorageProtocol(evita, "dU"));
				}
				final List<CatalogBootstrap> after = harness.bootstrapRecords(paths, "dU");
				assertTrue(after.size() > before.size(), "the upgrade must publish a new bootstrap record");
				assertEquals(before, after.subList(0, before.size()), "release bootstrap records stay as they were");
				assertEquals(9, harness.lastBootstrapStorageProtocol(paths, "dU"));
			} finally {
				harness.cleanup(paths);
			}
		}

		@Test
		@DisplayName("a 2026.1 catalog opened by 2026.2 carries both releases' bootstrap stamps before the upgrade")
		void shouldCarryBothReleaseStampsWhenCatalogWasOpenedByRelease2026_2() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final TestPaths paths = harness.copy("d12");
			try {
				final List<Integer> protocols = harness.bootstrapRecords(paths, "d12")
					.stream()
					.map(CatalogBootstrap::storageProtocolVersion)
					.distinct()
					.toList();
				assertEquals(List.of(4, 6), protocols);
			} finally {
				harness.cleanup(paths);
			}
		}

		@Test
		@DisplayName("the second boot of an upgraded healthy catalog leaves every file of the catalog byte-identical")
		void shouldAppendNothingWhenUpgradedCatalogBootsAgain() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final TestPaths paths = harness.copy("dU");
			try {
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, "dU");
				}
				final Path catalogDirectory = ReleaseFixtureHarness.catalogDirectory(paths, "dU");
				final Map<String, Long> lengths = ReleaseFixtureHarness.fileLengths(catalogDirectory);
				final Map<String, String> digests = ReleaseFixtureHarness.fileDigests(catalogDirectory);
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, "dU");
				}
				assertEquals(lengths, ReleaseFixtureHarness.fileLengths(catalogDirectory));
				ReleaseFixtureHarness.assertByteIdentical(digests, catalogDirectory);
			} finally {
				harness.cleanup(paths);
			}
		}
	}

	/** Tests shared counters whose released keys become equal. */
	@Nested
	@DisplayName("Reference counters keyed by open-bound twins at different offsets")
	@Tag(REFERENCE)
	class OpenBoundCounters {

		/** Verifies reference removal preserves the remaining contributions. */
		@Test
		@DisplayName("removing one owner's reference keeps the other owner findable through the shared counter")
		void shouldKeepSecondOwnerFindableWhenFirstOwnerLeavesCollidingCounter() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1a", evita -> {
				final Query byValidity = query(
					collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeInRange("validity", MID_2025)))
				);
				assertEquals(List.of(1, 2), pks(evita, "d1a", byValidity));
				update(evita, "d1a", session -> removeBrand(session, 1, 1));
				assertEquals(List.of(2), pks(evita, "d1a", byValidity));
			});
		}

		/** Verifies reference removal preserves the remaining contributions. */
		@Test
		@DisplayName("after one owner leaves, the other owner is still found by an exact match on its value")
		void shouldKeepSecondOwnerFindableByEqualityWhenFirstOwnerLeaves() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1a", evita -> {
				update(evita, "d1a", session -> removeBrand(session, 1, 1));
				assertEquals(
					List.of(2),
					pks(
						evita, "d1a",
						query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("validity", U2))))
					)
				);
			});
		}

		/** Verifies reference removal preserves the remaining contributions. */
		@Test
		@DisplayName("both owners can drop their colliding reference one after another")
		void shouldRemoveBothCollidingReferencesWhenOwnersLeaveInTurn() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1a", evita -> {
				update(evita, "d1a", session -> removeBrand(session, 1, 1));
				update(evita, "d1a", session -> removeBrand(session, 2, 1));
				assertEquals(
					List.of(),
					pks(
						evita, "d1a",
						query(
							collection(PRODUCT),
							filterBy(referenceHaving(BRAND, attributeInRange("validity", MID_2025)))
						)
					)
				);
			});
		}
	}


	@Nested
	@DisplayName("a FILTER holding the open-bound twins until(10:00+01:00) and until(11:00+02:00)")
	class OpenBoundSentinel {

		@Test
		@DisplayName("the FILTER holding both twins in separate buckets survives the removal of one owner")
		void shouldKeepSecondOwnerInFilterBucketWhenFirstOwnerChanges() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1c", evita -> {
				final Query byValidity = inRange("validity", BASE.minusMonths(7));
				assertEquals(List.of(1, 2), pks(evita, "d1c", byValidity));
				update(evita, "d1c", session -> setAttribute(session, 1, "validity", LATER));
				assertEquals(List.of(2), pks(evita, "d1c", byValidity));
				update(evita, "d1c", session -> setAttribute(session, 2, "validity", LATER));
				assertEquals(List.of(), pks(evita, "d1c", byValidity));
			});
		}
	}

	@Nested
	@DisplayName("DateTimeRange owner SORT ordered by whole seconds in the release")
	class DateTimeRangeSortOrder {

		@Test
		@DisplayName("inline: updating A after the upgrade succeeds and leaves the order consistent")
		void shouldKeepOrderConsistentWhenInlineSortIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d3", evita -> {
				update(evita, "d3", session -> setAttribute(session, 1, "sortValidity", LATER));
				update(evita, "d3", session -> setAttribute(
					session, 2, "sortValidity", DateTimeRange.between(BASE.plusYears(2), BASE.plusYears(2).plusDays(1))
				));
				assertEquals(List.of(3, 1, 2), pks(evita, "d3", sortedByValidity(3)));
			});
		}

		@Test
		@DisplayName("paged: every B_i sorts before its A_i")
		void shouldOrderPagedSortByMillisecondBounds() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(
				"d3p", evita -> assertEquals(List.of(2, 1, 4, 3, 6, 5), pks(evita, "d3p", sortedByValidity(6)))
			);
		}

		@Test
		@DisplayName("paged: updating A_0 after the upgrade succeeds")
		void shouldKeepOrderConsistentWhenPagedSortIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d3p", evita -> {
				update(evita, "d3p", session -> setAttribute(session, 1, "sortValidity", LATER));
				assertEquals(List.of(2, 4, 3), pks(evita, "d3p", sortedByValidity(3)));
			});
		}

		/**
		 * Returns a query for the first products ordered by `sortValidity`.
		 *
		 * @param count how many products
		 * @return the query
		 */
		@Nonnull
		private static Query sortedByValidity(int count) {
			return query(collection(PRODUCT), orderBy(attributeNatural("sortValidity")), require(page(1, count)));
		}
	}

	@Nested
	@DisplayName("localized String compounds whose release order ignored the tie-break")
	class LocalizedTieBreak {

		@Test
		@DisplayName("compound (label, code): updating a record after the upgrade keeps the order consistent")
		void shouldKeepOrderConsistentWhenNonTemporalLocalizedCompoundIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d4c", evita -> {
				update(evita, "d4c", session -> setAttribute(session, 2, "code", 0));
				assertEquals(List.of(3, 2, 1), pks(evita, "d4c", orderedInEnglish("labelCode")));
				update(evita, "d4c", session -> setAttribute(session, 1, "code", 9));
				assertEquals(List.of(3, 2, 1), pks(evita, "d4c", orderedInEnglish("labelCode")));
			});
		}

		@Test
		@DisplayName("compound (label, moment): ordered by the tie-break before the moment")
		void shouldOrderTemporalLocalizedCompoundByTieBreak() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(
				"d4t", evita -> assertEquals(List.of(3, 2, 1), pks(evita, "d4t", orderedInEnglish("labelMoment")))
			);
		}

		@Test
		@DisplayName("compound (label, moment): updating a record after the upgrade succeeds")
		void shouldKeepOrderConsistentWhenTemporalLocalizedCompoundIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d4t", evita -> {
				update(evita, "d4t", session -> setAttribute(session, 2, "moment", BASE.minusDays(1)));
				assertEquals(List.of(3, 2, 1), pks(evita, "d4t", orderedInEnglish("labelMoment")));
			});
		}
	}

	@Nested
	@DisplayName("Reference attributes and partitions")
	@Tag(REFERENCE)
	class ReferenceShapes {

		@Test
		@DisplayName("The remaining owner of a numeric reference range is found by a point inside it")
		void shouldFindRemainingOwnerWhenRangeReferenceAttributeOfOtherOwnerWasRemoved() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d16n", evita -> assertEquals(
				List.of(2),
				pks(evita, "d16n", query(
					collection(PRODUCT),
					filterBy(referenceHaving(BRAND, attributeInRange("size", new BigDecimal("5"))))
				))
			));
		}

		@Test
		@DisplayName("group partitions: updating the attribute and removing both references answers as a fresh engine")
		void shouldAnswerAsFreshEngineWhenGroupPartitionOwnerChanges() {
			final List<Consumer<EvitaSessionContract>> steps = List.of(
				session -> setAttribute(session, 1, "validity", LATER),
				session -> removeBrand(session, 1, 1),
				session -> removeBrand(session, 1, 2)
			);
			final AtomicReference<List<List<String>>> fresh = new AtomicReference<>();
			Release2026_2UpgradeDefectsTest.this.harness.withFreshCatalog(
				"dGrp", evita -> fresh.set(applySteps(evita, steps))
			);
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				"dGrp", evita -> assertEquals(fresh.get(), applySteps(evita, steps))
			);
		}

		@Test
		@DisplayName("a group-only unique reference attribute with a withdrawn release key loads every value intact")
		void shouldKeepEveryValueWhenWithdrawnUniqueRepresentativeIsHealthy() {
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog("dUw", evita -> {
				final List<String> references = evita.queryCatalog(
					"dUw",
					session -> {
						return session.queryList(
								query(collection(PRODUCT), require(entityFetch(referenceContentAllWithAttributes()))),
								SealedEntity.class
							)
							.stream()
							.flatMap(
								product -> product.getReferences(BRAND).stream().map(
									reference -> product.getPrimaryKey() + "->" + reference.getReferencedPrimaryKey() +
										"/" + reference.getGroup().orElseThrow().getPrimaryKey() + " " +
										reference.getAttribute("validity")
								)
							)
							.toList();
					}
				);
				assertEquals(
					List.of(
						"2->2/10 " + UNTIL_900_PLUS_ONE,
						"3->3/20 " + UNTIL_100_PLUS_TWO
					),
					references
				);
			});
		}

		/**
		 * Commits each step in `dGrp` and records the fixture's probe answers after it.
		 *
		 * @param evita the engine
		 * @param steps the transactions
		 * @return the probe answers after each step
		 */
		@Nonnull
		private static List<List<String>> applySteps(
			@Nonnull Evita evita,
			@Nonnull List<Consumer<EvitaSessionContract>> steps
		) {
			return steps.stream()
				.map(step -> {
					update(evita, "dGrp", step);
					return probe(evita, "dGrp");
				})
				.toList();
		}
	}

}
