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

package io.evitadb.core;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.FragmentationStatistics;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static org.awaitility.Awaitility.await;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the `FRAGMENTATION` component - the live/waste split of the data stores, the verdict on whether any of them
 * is due for compaction, and the forecast of when the next one will be.
 *
 * The projection arithmetic itself is pinned in `CompactionCadenceGateTest`, next to the predicate it extrapolates;
 * what is verified here is everything that arithmetic cannot see: that the numbers are wired to the engine's real
 * state, that the catalog-level answer folds in every data store rather than a subset, and that the eligibility flag
 * follows the configured thresholds rather than being decided in the engine.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Compaction forecast and fragmentation")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CompactionForecastTest implements EvitaTestSupport {
	private static final String CATALOG = "compactionForecastTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final int PRODUCT_COUNT = 50;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CompactionForecastTest");
		this.evita = new Evita(getEvitaConfiguration(defaultStorage()));
		buildCatalog(this.evita);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("The reported share is exactly the ratio of the reported bytes, at both levels")
	void shouldDeriveTheActiveShareFromTheBytesItReports() {
		final FragmentationStatistics catalogLevel = fetchCatalogFragmentation();
		assertTrue(catalogLevel.liveBytes() > 0, catalogLevel.toString());
		// the share must be reproducible from the two numbers printed next to it - a client drawing a gauge from
		// them has no other way to check that it is looking at one measurement rather than two
		assertEquals(
			(double) catalogLevel.liveBytes() / (double) (catalogLevel.liveBytes() + catalogLevel.wasteBytes()),
			catalogLevel.activeRecordShare(),
			1e-9d,
			"The catalog share is not the ratio of the bytes reported with it: " + catalogLevel
		);

		final DataStoreFragmentation collectionLevel = fetchDataStoreFragmentation(ENTITY_PRODUCT);
		assertTrue(collectionLevel.liveBytes() > 0, collectionLevel.toString());
		assertEquals(
			(double) collectionLevel.liveBytes()
				/ (double) (collectionLevel.liveBytes() + collectionLevel.wasteBytes()),
			collectionLevel.activeRecordShare(),
			1e-9d,
			"The collection share is not the ratio of the bytes reported with it: " + collectionLevel
		);
	}

	@Test
	@DisplayName("The configured thresholds are echoed, and only at the catalog level")
	void shouldEchoTheConfiguredThresholds() {
		final FragmentationStatistics fragmentation = fetchCatalogFragmentation();
		final StorageOptions configured = defaultStorage();

		// the whole point of shipping them is that a client can draw the measurement and the line it is heading for
		// on one gauge without a second call, so they have to be *this* instance's configuration
		assertEquals(configured.fileSizeCompactionThresholdBytes(), fragmentation.fileSizeCompactionThresholdBytes());
		assertEquals(configured.minimalActiveRecordShare(), fragmentation.minimalActiveRecordShare());
		assertEquals(configured.maxWasteActiveShare(), fragmentation.maxWasteActiveShare());
		assertEquals(
			configured.minCompactionIntervalMilliseconds(), fragmentation.minCompactionIntervalMilliseconds()
		);
	}

	@Test
	@DisplayName("Rewriting entities strands bytes that the waste counter and the waste bytes both see")
	void shouldCountTheBytesStrandedByRewrites() {
		// the assertion is on the *increase*, because building the catalog already rewrites schemas and headers and
		// therefore starts both numbers above zero - the same reason the storage-size waste test works this way
		final FragmentationStatistics before = fetchCatalogFragmentation();

		rewriteProducts(5);

		final FragmentationStatistics after = fetchCatalogFragmentation();
		assertTrue(
			after.wasteBytesGenerated() > before.wasteBytesGenerated(),
			"Rewriting every entity five times stranded no bytes according to the counter: " +
				before.wasteBytesGenerated() + " -> " + after.wasteBytesGenerated()
		);
		assertTrue(
			after.wasteBytes() > before.wasteBytes(),
			"The superseded records did not show up in the measured waste: " +
				before.wasteBytes() + " -> " + after.wasteBytes()
		);
		// more waste against unchanged live data is a lower share, which is the reading the component exists for
		assertTrue(
			after.activeRecordShare() < before.activeRecordShare(),
			"The active share did not fall although the waste grew: " + before + " -> " + after
		);
	}

	@Test
	@DisplayName("The catalog-level forecast folds in its own data store as well as every collection's")
	void shouldSumTheForecastAcrossEveryDataStore() {
		rewriteProducts(5);

		final FragmentationStatistics catalogLevel = fetchCatalogFragmentation();
		final long collectionsGenerated = fetchDataStoreFragmentation(ENTITY_PRODUCT).wasteBytesGenerated()
			+ fetchDataStoreFragmentation(ENTITY_CATEGORY).wasteBytesGenerated();

		assertTrue(collectionsGenerated > 0, "The rewritten collection stranded nothing at all");
		// strictly greater, not `>=`: the catalog's own data store holds the schemas and the headers, which the
		// writes above rewrote too. Equality here means the fold started from the collections and forgot it
		assertTrue(
			catalogLevel.wasteBytesGenerated() > collectionsGenerated,
			"The catalog's own data store is missing from the fold: catalog reports " +
				catalogLevel.wasteBytesGenerated() + ", its collections sum to " + collectionsGenerated
		);
	}

	@Test
	@DisplayName("The catalog's own data store is reported apart from the collections it is folded in with")
	void shouldReportTheCatalogDataStoreSliceOfTheAggregate() {
		rewriteProducts(5);

		final FragmentationStatistics catalogLevel = fetchCatalogFragmentation();
		final DataStoreFragmentation catalogDataStore = catalogLevel.catalogDataStore();

		// the catalog's own store holds the schemas and the headers, so it is never empty in a built catalog - and it
		// is never the whole of the catalog either, since the products live in a collection store
		assertTrue(catalogDataStore.liveBytes() > 0, "The catalog's own data store reports no live bytes at all");
		assertTrue(
			catalogDataStore.liveBytes() < catalogLevel.liveBytes(),
			"The catalog's own data store cannot account for every live byte in the catalog: " + catalogDataStore
		);

		// the identity that makes the aggregate decomposable: within one response the catalog-wide figure is this
		// slice plus every open collection's. Nothing is written between these calls, so no version skew can hide a
		// slice that was silently double-counted or dropped
		final DataStoreFragmentation product = fetchDataStoreFragmentation(ENTITY_PRODUCT);
		final DataStoreFragmentation category = fetchDataStoreFragmentation(ENTITY_CATEGORY);
		assertEquals(
			catalogLevel.liveBytes(),
			catalogDataStore.liveBytes() + product.liveBytes() + category.liveBytes(),
			"The catalog-wide live bytes are not the catalog store's plus its collections': " + catalogLevel
		);
		assertEquals(
			catalogLevel.wasteBytes(),
			catalogDataStore.wasteBytes() + product.wasteBytes() + category.wasteBytes(),
			"The catalog-wide waste bytes are not the catalog store's plus its collections': " + catalogLevel
		);

		// the share is derived from this record's own two figures, exactly as the catalog-wide one is from its own
		assertEquals(
			(double) catalogDataStore.liveBytes()
				/ (double) (catalogDataStore.liveBytes() + catalogDataStore.wasteBytes()),
			catalogDataStore.activeRecordShare(),
			1e-9d,
			"The catalog store's share is not the ratio of the bytes reported with it: " + catalogDataStore
		);
	}

	@Test
	@DisplayName("The catalog data store's own counters are its own, not a copy of the aggregate")
	void shouldNotMirrorTheAggregateIntoTheCatalogDataStoreSlice() {
		rewriteProducts(5);

		final FragmentationStatistics catalogLevel = fetchCatalogFragmentation();
		final DataStoreFragmentation catalogDataStore = catalogLevel.catalogDataStore();

		// rewriting products strands bytes in the *collection* store, which the catalog-wide counter folds in and this
		// one must not. Equality here is the signature of a slice filled from the enclosing record
		assertTrue(
			catalogDataStore.wasteBytesGenerated() < catalogLevel.wasteBytesGenerated(),
			"The catalog store's production counter equals the catalog-wide fold: " + catalogDataStore
		);
	}

	@Test
	@DisplayName("Nothing is due for compaction while no file reaches the configured size threshold")
	void shouldReportNothingDueUnderTheDefaultThresholds() {
		rewriteProducts(5);

		// the shipped `fileSizeCompactionThresholdBytes` is far above anything this fixture writes, so however
		// wasteful the stores get, the predicate cannot hold - and a store that is not due carries no forecast of
		// being due either
		final FragmentationStatistics catalogLevel = fetchCatalogFragmentation();
		assertFalse(catalogLevel.compactionEligibleNow(), catalogLevel.toString());
		assertFalse(fetchDataStoreFragmentation(ENTITY_PRODUCT).compactionEligibleNow());

		assertEquals(
			catalogLevel.estimatedCompactionAt() == null,
			catalogLevel.estimatedCompactionAtIfKnown().isEmpty(),
			"The accessor and the field must agree on whether a projection exists"
		);
		final OffsetDateTime projected = catalogLevel.estimatedCompactionAt();
		if (projected != null) {
			assertTrue(
				projected.isAfter(OffsetDateTime.now().minusMinutes(1)),
				"A projection is a future crossing, never a past one: " + projected
			);
		}
	}

	@Test
	@DisplayName("A store the configured thresholds condemn is reported as due for compaction")
	void shouldReportAStoreAsDueWhenTheThresholdsSayItIs() {
		rewriteProducts(5);

		// reopen the very same directory under thresholds that condemn any file holding any waste at all. Nothing is
		// written after the reopen, so no flush - and therefore no compaction - can run in between, and the verdict
		// is read off exactly the state the previous instance left behind. This is the assertion that tells the flag
		// apart from a hard-coded `false`: nothing about the data changed, only the configuration it is judged by
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration(condemningStorage()));
		awaitCatalogLoaded();

		final FragmentationStatistics fragmentation = fetchCatalogFragmentation();
		assertTrue(
			fragmentation.compactionEligibleNow(),
			"No data store was reported as due although every threshold condemns it: " + fragmentation
		);
		assertEquals(1L, fragmentation.fileSizeCompactionThresholdBytes());
		// and the raised flag is attributable: under thresholds that condemn every file, the catalog's own store is
		// due as well, which is the reading that separates "compact that collection" from "the metadata store is
		// churning". A catalog-wide disjunction alone cannot say which
		assertTrue(
			fragmentation.catalogDataStore().compactionEligibleNow(),
			"The catalog's own data store is not reported as due although every threshold condemns it: " +
				fragmentation.catalogDataStore()
		);
		// a store that is already due needs no forecast - the boolean is the answer for it
		assertTrue(
			fetchDataStoreFragmentation(ENTITY_PRODUCT).compactionEligibleNow(),
			"The collection's own data store must reach the same verdict as the catalog-wide disjunction"
		);
		assertTrue(fetchDataStoreFragmentation(ENTITY_PRODUCT).estimatedCompactionAtIfKnown().isEmpty());
	}

	/**
	 * Blocks until the catalog has finished loading.
	 *
	 * Catalogs are loaded on a background pool, and until one finishes it is represented by an `UnusableCatalog`
	 * placeholder that declines every component it cannot answer from file names alone - which is the honest answer
	 * while it is still being activated, and not what this test is measuring. Nothing here waits on the *forecast*;
	 * the assertions that follow read a catalog that is fully open.
	 */
	private void awaitCatalogLoaded() {
		await()
			.atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(20))
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/**
	 * Rewrites every product the fixture holds, the given number of times.
	 *
	 * The data store is append-only, so each round leaves the previous version of every entity behind as dead bytes -
	 * which is precisely what the waste counter is supposed to see.
	 *
	 * @param rounds how many times to rewrite the whole collection
	 */
	private void rewriteProducts(int rounds) {
		for (int round = 0; round < rounds; round++) {
			final int currentRound = round;
			this.evita.updateCatalog(
				CATALOG,
				session -> {
					for (int i = 1; i <= PRODUCT_COUNT; i++) {
						// the attributes have to be fetched for the builder to accept an overwrite - creating the
						// entity afresh is rejected as a duplicate rather than stranding the previous version
						session.upsertEntity(
							session.getEntity(ENTITY_PRODUCT, i, attributeContentAll())
								.orElseThrow()
								.openForWrite()
								.setAttribute("rewrittenIn", currentRound)
						);
					}
				}
			);
		}
	}

	/**
	 * Reads the catalog-level fragmentation component.
	 *
	 * @return the component as this instance reports it
	 */
	@Nonnull
	private FragmentationStatistics fetchCatalogFragmentation() {
		final CatalogStatistics statistics = this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.FRAGMENTATION)
		);
		return statistics.fragmentationIfPresent().orElseThrow(
			() -> new AssertionError(
				"The catalog declined to compute the component: " +
					statistics.statusOf(CatalogStatisticsComponent.FRAGMENTATION).orElse(null)
			)
		);
	}

	/**
	 * Reads one collection's fragmentation component.
	 *
	 * @param entityType the collection to ask about
	 * @return the component as this instance reports it
	 */
	@Nonnull
	private DataStoreFragmentation fetchDataStoreFragmentation(@Nonnull String entityType) {
		final EntityCollectionStatistics statistics = this.evita.management().getEntityCollectionStatistics(
			CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.FRAGMENTATION)
		);
		return statistics.fragmentationIfPresent().orElseThrow(
			() -> new AssertionError(
				"The collection declined to compute the component: " +
					statistics.statusOf(CatalogStatisticsComponent.FRAGMENTATION).orElse(null)
			)
		);
	}

	/**
	 * Creates the catalog this test measures - two collections, one of them populated.
	 *
	 * @param instance the embedded instance to build it in
	 */
	private static void buildCatalog(@Nonnull Evita instance) {
		instance.defineCatalog(CATALOG).updateViaNewSession(instance);
		instance.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT);
				session.defineEntitySchema(ENTITY_CATEGORY);
				for (int i = 1; i <= PRODUCT_COUNT; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i).setAttribute("code", "product-" + i)
					);
				}
				for (int i = 1; i <= 5; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_CATEGORY, i).setAttribute("code", "category-" + i)
					);
				}
			}
		);
	}

	/**
	 * Returns the shipped compaction thresholds, under which this fixture can never become eligible.
	 *
	 * @return the default storage configuration rooted at this test's directories
	 */
	@Nonnull
	private StorageOptions defaultStorage() {
		return StorageOptions.builder()
			.storageDirectory(this.paths.storage())
			.workDirectory(this.paths.work())
			.build();
	}

	/**
	 * Returns thresholds under which any data file holding any waste at all is due for compaction.
	 *
	 * @return a storage configuration rooted at this test's directories that condemns everything
	 */
	@Nonnull
	private StorageOptions condemningStorage() {
		return StorageOptions.builder()
			.storageDirectory(this.paths.storage())
			.workDirectory(this.paths.work())
			.fileSizeCompactionThresholdBytes(1L)
			.minimalActiveRecordShare(0.99d)
			.maxWasteActiveShare(0.99d)
			.minCompactionIntervalMilliseconds(0L)
			.build();
	}

	/**
	 * Builds the configuration of an embedded instance using the given storage options.
	 *
	 * @param storageOptions the storage configuration to run with
	 * @return configuration pointing at this test's directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(@Nonnull StorageOptions storageOptions) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(storageOptions)
			.build();
	}
}
