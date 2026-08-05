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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.statistics.ActivityStatistics;
import io.evitadb.api.statistics.DurabilityStatistics;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.api.statistics.CommitPipelineStatistics;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.ComponentStatus;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the statistics components that are plain counter reads - sessions, the commit pipeline watermarks, the
 * time-travel window and what is pinning disk, the collection header counters, and the state held in memory rather
 * than on disk.
 *
 * The first two tests are the ones that earn their keep beyond the individual numbers: they request *every*
 * level-appropriate component at once and assert the exact delivered / not-delivered partition. Implementing a
 * component and forgetting to move it out of the catch-all `NOT_SUPPORTED` arm of the dispatch switch compiles
 * cleanly and reports "not supported" forever, and nothing else in the suite would notice.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Cheap scalar statistics components")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class CheapScalarStatisticsTest implements EvitaTestSupport {
	private static final String CATALOG = "cheapScalarStatisticsTest";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_BRAND = "brand";

	/**
	 * The catalog-level components no build of this branch computes yet.
	 *
	 * **Empty since `DURABILITY` landed, and the assertion below is written so that this is a stronger statement
	 * rather than a vacuous one.** With an empty set every catalog-level component must report `DELIVERED`, so a
	 * component left behind in the catch-all arm of the dispatch switch fails here - which is the whole point of the
	 * partition. Had the assertion been phrased as "every member of this set reports NOT_SUPPORTED" it would now
	 * iterate nothing and pass whatever the engine did.
	 */
	private static final Set<CatalogStatisticsComponent> CATALOG_LEVEL_NOT_SUPPORTED =
		EnumSet.noneOf(CatalogStatisticsComponent.class);
	/**
	 * The collection-level components no build of this branch computes yet - the expensive pair, which will keep this
	 * partition discriminating at the collection level until the last stage delivers them.
	 */
	private static final Set<CatalogStatisticsComponent> COLLECTION_LEVEL_NOT_SUPPORTED = EnumSet.of(
		CatalogStatisticsComponent.INDEX_CARDINALITY,
		CatalogStatisticsComponent.MEMORY_FOOTPRINT
	);

	private TestPaths paths;
	private Evita evita;
	/** When this test's engine was created - the lower bound every process-scoped `countingSince` must respect. */
	private OffsetDateTime startedAt;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CheapScalarStatisticsTest");
		this.startedAt = OffsetDateTime.now();
		this.evita = new Evita(getEvitaConfiguration(false));
		buildCatalog(this.evita);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Every catalog-level component either answers or says why it cannot")
	void shouldPartitionCatalogLevelComponentsIntoDeliveredAndNotSupported() {
		goLive();
		final CatalogStatistics statistics = this.evita.management().getCatalogStatistics(
			CATALOG, catalogLevelComponents()
		);

		for (final CatalogStatisticsComponent component : catalogLevelComponents()) {
			final ComponentStatus status = statistics.componentStatus().get(component);
			assertNotNull(status, "Component `" + component + "` was requested but carries no status at all");
			if (CATALOG_LEVEL_NOT_SUPPORTED.contains(component)) {
				assertEquals(
					ComponentAvailability.NOT_SUPPORTED, status.availability(),
					"Component `" + component + "` is not implemented yet and must say so"
				);
			} else {
				assertEquals(
					ComponentAvailability.DELIVERED, status.availability(),
					"Component `" + component + "` is implemented but reported `" + status.availability() +
						"` - most likely it was never moved out of the catch-all arm of the dispatch switch"
				);
			}
		}
	}

	@Test
	@DisplayName("Every collection-level component either answers or says why it cannot")
	void shouldPartitionCollectionLevelComponentsIntoDeliveredAndNotSupported() {
		final EntityCollectionStatistics statistics = this.evita.management().getEntityCollectionStatistics(
			CATALOG, ENTITY_PRODUCT, collectionLevelComponents()
		);

		for (final CatalogStatisticsComponent component : collectionLevelComponents()) {
			final ComponentStatus status = statistics.componentStatus().get(component);
			assertNotNull(status, "Component `" + component + "` was requested but carries no status at all");
			if (COLLECTION_LEVEL_NOT_SUPPORTED.contains(component)) {
				assertEquals(
					ComponentAvailability.NOT_SUPPORTED, status.availability(),
					"Component `" + component + "` is not implemented yet and must say so"
				);
			} else {
				assertEquals(
					ComponentAvailability.DELIVERED, status.availability(),
					"Component `" + component + "` is implemented but reported `" + status.availability() +
						"` - most likely it was never moved out of the catch-all arm of the dispatch switch"
				);
			}
		}
	}

	@Test
	@DisplayName("Open sessions are counted and split by whether they may write")
	void shouldCountOpenSessionsSplitByMode() {
		// two sessions of *different* modes have to be open at the same time: with one session the read-only and the
		// read-write counter cannot be told apart, so a swapped mapping would pass
		goLive();
		assertEquals(new SessionStatistics(0, 0, 0), fetchSessions(), "A quiet catalog has no sessions open");

		try (
			final EvitaSessionContract readOnly = this.evita.createReadOnlySession(CATALOG);
			final EvitaSessionContract readWrite = this.evita.createReadWriteSession(CATALOG)
		) {
			assertTrue(readOnly.isActive() && readWrite.isActive());
			assertEquals(new SessionStatistics(2, 1, 1), fetchSessions());
		}

		assertEquals(
			new SessionStatistics(0, 0, 0), fetchSessions(),
			"Closed sessions must stop being counted - a count that never falls is the symptom this component exists " +
				"to make visible, and it would be meaningless if it were the component's own bug"
		);
	}

	@Test
	@DisplayName("A warming-up catalog declines the commit pipeline rather than reporting an idle one")
	void shouldDeclineTheCommitPipelineWhileWarmingUp() {
		// four zeroes would render as a pipeline with nothing queued anywhere - i.e. perfectly healthy - which is the
		// exact inverse of the truth: in WARM_UP writes bypass the pipeline and none of its watermarks ever move
		final CatalogStatistics warmingUp = this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.COMMIT_PIPELINE)
		);
		assertTrue(warmingUp.commitPipelineIfPresent().isEmpty());
		assertEquals(
			ComponentAvailability.FEATURE_DISABLED,
			warmingUp.componentStatus().get(CatalogStatisticsComponent.COMMIT_PIPELINE).availability()
		);

		goLive();
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 1_000));
			}
		);

		final CatalogStatistics alive = this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.COMMIT_PIPELINE)
		);
		final CommitPipelineStatistics pipeline = alive.commitPipelineIfPresent().orElseThrow();
		assertTrue(pipeline.lastAssignedCatalogVersion() > 0, pipeline.toString());
		// the watermarks are read in REVERSE pipeline order - trailing one first - so no lag may come out negative
		// however the stages interleave between the four reads
		assertTrue(pipeline.writeLag() >= 0, pipeline.toString());
		assertTrue(pipeline.durabilityLag() >= 0, pipeline.toString());
		assertTrue(pipeline.visibilityLag() >= 0, pipeline.toString());
	}

	@Test
	@DisplayName("A warming-up catalog declines the activity counters rather than reporting an idle one")
	void shouldDeclineActivityWhileWarmingUp() {
		// bulk ingestion never enters the pipeline that counts transactions, so every counter would read zero however
		// much data is being written - "idle and healthy" is the exact inverse of the truth here
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 900));
			}
		);

		final CatalogStatistics warmingUp = this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.ACTIVITY)
		);
		assertTrue(warmingUp.activityIfPresent().isEmpty());
		assertEquals(
			ComponentAvailability.FEATURE_DISABLED,
			warmingUp.componentStatus().get(CatalogStatisticsComponent.ACTIVITY).availability()
		);
	}

	@Test
	@DisplayName("Committing a transaction moves the activity counters that describe it")
	void shouldCountCommittedTransactionsMutationsAndWalBytes() {
		goLive();
		final ActivityStatistics before = fetchActivity();

		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 1_000));
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 1_001));
			}
		);

		final ActivityStatistics after = fetchActivity();
		assertTrue(
			after.transactionsCommitted() > before.transactionsCommitted(),
			"A committed transaction has to be counted: " + before + " -> " + after
		);
		assertTrue(
			after.mutationsApplied() >= before.mutationsApplied() + 2,
			"Both upserts have to be counted as mutations: " + before + " -> " + after
		);
		assertTrue(
			after.walBytesAppended() > before.walBytesAppended(),
			"The transaction's bytes reached the WAL, so they have to be counted: " + before + " -> " + after
		);
		// nothing was rolled back or conflicted, and those counters must not move in sympathy with the committed one -
		// which is exactly what a single shared counter wired three ways would do
		assertEquals(before.transactionsRolledBack(), after.transactionsRolledBack());
		assertEquals(before.transactionsConflicted(), after.transactionsConflicted());
		// the counters are process-scoped, so the epoch they are read against must not move underneath them
		assertEquals(before.countingSince(), after.countingSince());
	}

	@Test
	@DisplayName("A rolled-back transaction is counted as rolled back, never as committed")
	void shouldCountARolledBackTransactionApartFromCommittedOnes() {
		goLive();
		final ActivityStatistics before = fetchActivity();

		// a rollback-only session surfaces the discarded work to its caller rather than returning quietly, so the
		// exception is part of the scenario being exercised, not an accident of it
		assertThrows(
			RollbackException.class,
			() -> this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 2_000));
					session.setRollbackOnly();
				}
			)
		);

		final ActivityStatistics after = fetchActivity();
		assertEquals(
			before.transactionsRolledBack() + 1, after.transactionsRolledBack(),
			"The rollback has to be counted: " + before + " -> " + after
		);
		// a rolled-back transaction never reaches the WAL, so counting it as committed would report write throughput
		// the catalog never performed - and would make the two counters sum to more than the transactions that ran
		assertEquals(
			before.transactionsCommitted(), after.transactionsCommitted(),
			"A rolled-back transaction must not be counted as committed: " + before + " -> " + after
		);
		assertEquals(before.walBytesAppended(), after.walBytesAppended());
	}

	@Test
	@DisplayName("The reported pipeline depth agrees with the watermarks the commit pipeline component reports")
	void shouldReportAPipelineDepthConsistentWithTheCommitPipelineWatermarks() {
		goLive();
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, 3_000));
			}
		);

		// both components come from ONE snapshot, so the depth must be exactly the span the watermarks describe.
		// What this pins is the *consistency* of the two: on a catalog that has gone quiet the pipeline has caught
		// up and the span is normally zero, so this cannot by itself distinguish a correctly-derived depth from a
		// hardcoded one. Forcing a non-zero backlog deterministically would mean racing the trunk incorporator, and
		// a flaky test is worth less here than an honest one - the value is that a depth wired to the wrong pair of
		// watermarks, or read at a different moment from the pipeline component, stops agreeing
		final CatalogStatistics statistics = this.evita.management().getCatalogStatistics(
			CATALOG,
			EnumSet.of(CatalogStatisticsComponent.ACTIVITY, CatalogStatisticsComponent.COMMIT_PIPELINE)
		);
		final ActivityStatistics activity = statistics.activityIfPresent().orElseThrow();
		final CommitPipelineStatistics pipeline = statistics.commitPipelineIfPresent().orElseThrow();

		// the watermarks have to have moved at all, or the identity below holds trivially for a pipeline that never ran
		assertTrue(pipeline.lastAssignedCatalogVersion() > 0, pipeline.toString());
		assertTrue(activity.pipelineDepth() >= 0, activity.toString());
		assertEquals(
			pipeline.lastAssignedCatalogVersion() - pipeline.lastFinalizedCatalogVersion(),
			activity.pipelineDepth(),
			"The depth is the same quantity as `writeLag + visibilityLag` and has to agree with it: " +
				activity + " vs " + pipeline
		);
	}

	@Test
	@DisplayName("Without time travel the history window is the current version alone")
	void shouldReportADegenerateHistoryWindowWithoutTimeTravel() {
		goLive();
		for (int i = 0; i < 3; i++) {
			final int pk = 1_000 + i;
			this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, pk));
				}
			);
		}

		final HistoryStatistics history = fetchHistory(this.evita);
		assertFalse(history.timeTravelEnabled());
		// the bootstrap file is never trimmed, so it still *lists* the older versions - but obsolete data files are
		// purged against the current header, so nothing older can actually be read. Reporting the bootstrap's oldest
		// record as the start of the window would promise history that is not there
		assertEquals(
			history.newestCatalogVersion(), history.oldestAvailableCatalogVersion(),
			"With time travel off the readable window is the current version alone: " + history
		);
		assertTrue(history.newestCatalogVersion() > 0, history.toString());
		assertNotNull(history.newestTimestampIfKnown().orElse(null));

		// the write-ahead log is retained in both modes - time travel widens the window rather than creating one
		assertTrue(history.walFileCount() > 0, "The write-ahead log was not counted: " + history);
		assertTrue(history.walBytes() > 0, "The write-ahead log was not measured: " + history);

		// the floor is raised only when the consumers of a version leave, never when one arrives - so it stays `0`
		// until a session has *closed*, and each transactional write above closed one. Asserting `> 0` rather than
		// `>= 0` is what distinguishes a wired-up field from one that is always the "nothing observed yet" default
		assertTrue(history.activeReaderFloor() > 0, "The active reader floor never advanced: " + history);
		assertTrue(history.awaitingDeletionFileCount() >= 0, history.toString());
		assertEquals(
			history.awaitingDeletionBytes(),
			history.blockedByActiveReaderBytes() + history.purgeableBytes(),
			"The blocked/purgeable split must partition the files awaiting deletion: " + history
		);
	}

	@Test
	@DisplayName("With time travel the history window reaches back past the current version")
	void shouldReportAWideHistoryWindowWithTimeTravel() {
		// the only test exercising the other branch of the window - and the one that would catch the window being
		// hard-wired to the current version rather than genuinely read from the bootstrap file
		final TestPaths timeTravelPaths = createTestPaths("CheapScalarStatisticsTest_tt");
		try (final Evita timeTravelling = new Evita(getEvitaConfiguration(timeTravelPaths, true))) {
			buildCatalog(timeTravelling);
			timeTravelling.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			for (int i = 0; i < 3; i++) {
				final int pk = 1_000 + i;
				timeTravelling.updateCatalog(
					CATALOG,
					session -> {
					session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, pk));
				}
				);
			}

			final HistoryStatistics history = fetchHistory(timeTravelling);
			assertTrue(history.timeTravelEnabled());
			assertTrue(
				history.oldestAvailableCatalogVersion() < history.newestCatalogVersion(),
				"With time travel on, the window must reach back past the current version: " + history
			);
			assertNotNull(history.oldestAvailableTimestampIfKnown().orElse(null));
			assertNotNull(history.newestTimestampIfKnown().orElse(null));
		} finally {
			cleanupTestPaths(timeTravelPaths);
		}
	}

	@Test
	@DisplayName("The collection header counters are reported as the header carries them")
	void shouldReportCollectionHeaderCounters() {
		final CollectionHeaderInfo header = fetchHeader(ENTITY_PRODUCT);

		assertTrue(header.entityTypePrimaryKey() > 0, header.toString());
		assertTrue(header.version() > 0, header.toString());
		assertTrue(header.lastKeyId() > 0, header.toString());
		assertTrue(header.lastEntityIndexPrimaryKey() > 0, header.toString());
		// the high-water mark is *largest ever seen*, so all that can be asserted is that something was seen
		assertTrue(header.maxRecordSizeBytes() > 0, header.toString());

		// the entity type primary key is the same surrogate the catalog inventory hands out, which is what makes the
		// two levels addressable by one another
		final CollectionHeaderInfo categoryHeader = fetchHeader(ENTITY_CATEGORY);
		assertNotEquals(
			header.entityTypePrimaryKey(), categoryHeader.entityTypePrimaryKey(),
			"Two collections cannot share one entity type primary key"
		);
	}

	@Test
	@DisplayName("The last primary key tracks the generated-key sequence, not the largest key in use")
	void shouldReportTheGeneratedKeySequenceAsLastPrimaryKey() {
		// this is the trap in `lastPrimaryKey`, and it is worth a test of its own because both the issue and the
		// record's first javadoc read it as "the largest primary key in use", from which "the gap against the record
		// count is the delete volume" follows. It is not: the value is the collection's auto-generated key sequence
		// (`HeaderInfoSupplier#getLastAssignedPrimaryKey` returns `pkSequence.get()`), which a client supplying its
		// own keys never advances at all
		assertEquals(
			0, fetchHeader(ENTITY_PRODUCT).lastPrimaryKey(),
			"50 products exist, but every one of their keys was supplied by the caller, so nothing was generated"
		);
		assertEquals(
			7, fetchHeader(ENTITY_BRAND).lastPrimaryKey(),
			"7 brands were inserted without keys, so the sequence handed out exactly 7"
		);
	}

	@Test
	@DisplayName("The catalog's volatile state is the sum over every one of its data stores")
	void shouldSumVolatileStateAcrossEveryDataStore() {
		final VolatileStateStatistics catalogWide = this.evita.management()
			.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.VOLATILE_STATE))
			.volatileStateIfPresent()
			.orElseThrow();

		long summedCollectionBytes = 0L;
		for (final String entityType : new String[]{ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_BRAND}) {
			final DataStoreVolatileState collectionState = this.evita.management()
				.getEntityCollectionStatistics(
					CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.VOLATILE_STATE)
				)
				.volatileStateIfPresent()
				.orElseThrow();
			assertTrue(
				collectionState.totalSizeIncludingVolatileDataBytes() > 0,
				"Collection `" + entityType + "` holds entities, so its data store cannot be empty: " + collectionState
			);
			summedCollectionBytes += collectionState.totalSizeIncludingVolatileDataBytes();
		}

		// strictly greater, in both directions on purpose: an aggregate that forgot the collections would come out
		// smaller than their sum, and one that forgot the catalog's own data store would come out exactly equal to it
		assertTrue(
			catalogWide.totalSizeIncludingVolatileDataBytes() > summedCollectionBytes,
			"The catalog-wide total (" + catalogWide.totalSizeIncludingVolatileDataBytes() + ") must exceed the sum " +
				"of its collections (" + summedCollectionBytes + "), which does not include the catalog data store"
		);
		assertTrue(catalogWide.nonFlushedRecordCount() >= 0, catalogWide.toString());
		assertTrue(catalogWide.nonFlushedSizeBytes() >= 0, catalogWide.toString());

		// ... and the catalog's own data store is reported apart from that sum, which is what turns "something is
		// holding memory" into "the metadata store is" or "a collection is". It is the exact quantity the aggregate
		// is seeded with, so the difference between them is precisely the collections' contribution
		final DataStoreVolatileState catalogDataStore = catalogWide.catalogDataStore();
		assertTrue(
			catalogDataStore.totalSizeIncludingVolatileDataBytes() > 0,
			"The catalog's own data store holds the schemas and headers, so it cannot be empty: " + catalogDataStore
		);
		assertEquals(
			catalogWide.totalSizeIncludingVolatileDataBytes() - summedCollectionBytes,
			catalogDataStore.totalSizeIncludingVolatileDataBytes(),
			"The catalog-wide total minus its collections must be exactly the catalog data store's own share"
		);
	}

	@Test
	@DisplayName("The catalog's own data store is reported apart from the storage size it is folded into")
	void shouldReportTheCatalogDataStoreSliceOfTheStorageSize() {
		final StorageSizeStatistics storageSize = this.evita.management()
			.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.STORAGE_SIZE))
			.storageSizeIfPresent()
			.orElseThrow();

		// the catalog's own file holds the schemas, the headers and the catalog-level indexes, so it is never empty
		// in a built catalog - and never the whole of it either, since the entities live in collection stores
		assertTrue(storageSize.catalogDataStoreLiveBytes() > 0, storageSize.toString());
		assertTrue(
			storageSize.catalogDataStoreLiveBytes() < storageSize.liveBytes(),
			"The catalog store's slice cannot account for every live byte in the catalog: " + storageSize
		);
		assertTrue(storageSize.catalogDataStoreWasteBytes() >= 0, storageSize.toString());
		assertTrue(
			storageSize.catalogDataStoreWasteBytes() <= storageSize.wasteBytes(),
			"The catalog store's waste cannot exceed the catalog-wide waste it is part of: " + storageSize
		);

		// the identity that makes the aggregate decomposable: the remainder is the sum over every open collection
		long summedCollectionLive = 0L;
		long summedCollectionWaste = 0L;
		for (final String entityType : new String[]{ENTITY_PRODUCT, ENTITY_CATEGORY, ENTITY_BRAND}) {
			final CollectionStorageSize collectionSize = this.evita.management()
				.getEntityCollectionStatistics(
					CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.STORAGE_SIZE)
				)
				.storageSizeIfPresent()
				.orElseThrow();
			summedCollectionLive += collectionSize.liveBytes();
			summedCollectionWaste += collectionSize.wasteBytes();
		}
		assertEquals(
			storageSize.liveBytes(),
			storageSize.catalogDataStoreLiveBytes() + summedCollectionLive,
			"The catalog-wide live bytes are not the catalog store's plus its collections': " + storageSize
		);
		assertEquals(
			storageSize.wasteBytes(),
			storageSize.catalogDataStoreWasteBytes() + summedCollectionWaste,
			"The catalog-wide waste bytes are not the catalog store's plus its collections': " + storageSize
		);
	}

	@Test
	@DisplayName("Both scopes of the catalog-level index are counted, not only the live one")
	void shouldCountTheArchivedCatalogIndexAsWellAsTheLiveOne() {
		// the catalog-level index is one per scope: `LIVE` always exists, `ARCHIVED` is created the first time
		// something globally unique is indexed in that scope. That needs its own catalog, because the shared fixture
		// declares no globally-unique attribute and would never create the second one
		final Scope[] bothScopes = {Scope.LIVE, Scope.ARCHIVED};
		final String archivingCatalog = CATALOG + "Archiving";
		this.evita.defineCatalog(archivingCatalog)
			.withAttribute("globalCode", String.class, thatIs -> thatIs.uniqueGloballyInScope(bothScopes))
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			archivingCatalog,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute("globalCode")
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 1).setAttribute("globalCode", "product-1")
				);
			}
		);

		final long catalogBefore = totalIndexCountOf(archivingCatalog);
		final long collectionsBefore = collectionIndexCountOf(archivingCatalog, ENTITY_PRODUCT);

		this.evita.updateCatalog(
			archivingCatalog,
			session -> {
				// a block body, not an expression: `archiveEntity` returns a boolean, which makes a bare lambda match
				// both the consumer and the function overload of `updateCatalog`
				session.archiveEntity(ENTITY_PRODUCT, 1);
			}
		);

		final long catalogAfter = totalIndexCountOf(archivingCatalog);
		final long collectionsAfter = collectionIndexCountOf(archivingCatalog, ENTITY_PRODUCT);

		// archiving builds the collection's `ARCHIVED` indexes *and* the catalog's own `ARCHIVED` index. The
		// collection half is counted from its index map either way, so it moves under any implementation; the
		// catalog half is exactly what a hard-coded count of one silently dropped. Asserting on the difference of
		// the two deltas rather than on an absolute total keeps this pinned to the bug rather than to how many
		// entity indexes this fixture happens to produce
		assertEquals(
			(collectionsAfter - collectionsBefore) + 1L,
			catalogAfter - catalogBefore,
			"Archiving created the catalog's `ARCHIVED` index, but the catalog-wide count grew only by the " +
				"collection's own indexes - the second scope of the catalog-level index is not being counted"
		);
	}

	/**
	 * Reads the catalog-wide index total.
	 *
	 * @param catalogName catalog to ask
	 * @return the number of indexes it reports across every collection and every scope of its own index
	 */
	private long totalIndexCountOf(@Nonnull String catalogName) {
		return this.evita.management()
			.getCatalogStatistics(catalogName, EnumSet.of(CatalogStatisticsComponent.INDEX_SUMMARY))
			.indexSummaryIfPresent()
			.orElseThrow()
			.totalIndexCount();
	}

	/**
	 * Reads one collection's index total.
	 *
	 * @param catalogName catalog holding the collection
	 * @param entityType  collection to ask
	 * @return the number of indexes that one collection reports
	 */
	private long collectionIndexCountOf(@Nonnull String catalogName, @Nonnull String entityType) {
		return this.evita.management()
			.getEntityCollectionStatistics(
				catalogName, entityType, EnumSet.of(CatalogStatisticsComponent.INDEX_SUMMARY)
			)
			.indexSummaryIfPresent()
			.orElseThrow()
			.totalIndexCount();
	}

	/**
	 * Defines the test catalog and fills it with entities carrying explicit primary keys, so that the header counters
	 * the tests assert on are predictable.
	 *
	 * @param instance the engine instance to build the catalog in
	 */
	private static void buildCatalog(@Nonnull Evita instance) {
		instance.defineCatalog(CATALOG).updateViaNewSession(instance);
		instance.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT);
				session.defineEntitySchema(ENTITY_CATEGORY);
				session.defineEntitySchema(ENTITY_BRAND);
				for (int i = 1; i <= 50; i++) {
					// the attribute is what puts a key into the collection's `KeyCompressor`; without one the header's
					// `lastKeyId` stays 0 and the test could not tell a wired-up field from a hard-coded zero
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i).setAttribute("code", "product-" + i)
					);
				}
				for (int i = 1; i <= 10; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
				}
				// brands deliberately let the engine assign their keys - the header's `lastPrimaryKey` tracks the
				// generated-key sequence, and nothing else in this fixture ever advances it
				for (int i = 1; i <= 7; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_BRAND));
				}
			}
		);
	}

	/**
	 * Transitions the test catalog out of `WARMING_UP` into `ALIVE`, which is what gives it a transactional commit
	 * pipeline and a write-ahead log.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Returns every component that may be asked of a catalog, which is what the partition test requests at once.
	 *
	 * @return all catalog-level components
	 */
	@Nonnull
	private static Set<CatalogStatisticsComponent> catalogLevelComponents() {
		final Set<CatalogStatisticsComponent> components = EnumSet.noneOf(CatalogStatisticsComponent.class);
		for (final CatalogStatisticsComponent component : CatalogStatisticsComponent.values()) {
			if (component.isCatalogLevel()) {
				components.add(component);
			}
		}
		return components;
	}

	/**
	 * Returns every component that may be asked of one entity collection.
	 *
	 * @return all collection-level components
	 */
	@Nonnull
	private static Set<CatalogStatisticsComponent> collectionLevelComponents() {
		final Set<CatalogStatisticsComponent> components = EnumSet.noneOf(CatalogStatisticsComponent.class);
		for (final CatalogStatisticsComponent component : CatalogStatisticsComponent.values()) {
			if (component.isCollectionLevel()) {
				components.add(component);
			}
		}
		return components;
	}

	/**
	 * Reads the header counters of one collection of the test catalog.
	 *
	 * @param entityType name of the collection to read
	 * @return the delivered {@link CollectionHeaderInfo}
	 */
	@Nonnull
	private CollectionHeaderInfo fetchHeader(@Nonnull String entityType) {
		return this.evita.management()
			.getEntityCollectionStatistics(CATALOG, entityType, EnumSet.of(CatalogStatisticsComponent.COLLECTIONS))
			.headerIfPresent()
			.orElseThrow();
	}

	/**
	 * Reads the session component of the test catalog.
	 *
	 * @return the delivered {@link SessionStatistics}
	 */
	@Nonnull
	private SessionStatistics fetchSessions() {
		return this.evita.management()
			.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.SESSIONS))
			.sessionsIfPresent()
			.orElseThrow();
	}

	/**
	 * Reads the write activity component of the test catalog.
	 *
	 * @return the delivered {@link ActivityStatistics}
	 */
	@Nonnull
	@Test
	@DisplayName("Report the checkpoint fence of a catalog that defers its checkpoints")
	void shouldReportTheDeferredCheckpointFence() {
		goLive();
		// the shipped defaults already defer: TransactionOptions.DEFAULT_CHECKPOINT_INTERVAL is 1000 ms and
		// StorageOptions.DEFAULT_SYNC_WRITES is true, so the fence exists without the fixture configuring anything
		this.evita.updateCatalog(CATALOG, session -> {
			// `brand` has its keys assigned by the engine, so this must not supply one
			session.upsertEntity(session.createNewEntity(ENTITY_BRAND));
		});

		final DurabilityStatistics durability = fetchDurability();
		assertTrue(
			durability.checkpointIntervalMillis() > 0L,
			"The configured interval is what fence depth is judged against, so a delivered component must carry it"
		);
		assertNotNull(durability.countingSince());
		// the counters are process-scoped, so `countingSince` cannot predate the engine that owns them
		assertFalse(durability.countingSince().isBefore(this.startedAt));
		// whether a checkpoint has completed by now depends on the ticker, so pinning a count would be flaky. What
		// must hold is that the two agree: `noteCheckpointCompleted` writes the count and the timestamp together, so
		// a reader that took them from different places - or a projection that dropped one - breaks this
		assertEquals(
			durability.checkpointsCompleted() == 0L,
			durability.lastCheckpointAtIfKnown().isEmpty(),
			"A completed checkpoint must carry its timestamp, and an absent timestamp must mean none completed: " +
				durability
		);
	}

	@Test
	@DisplayName("Decline the durability fence when the catalog checkpoints inline")
	void shouldDeclineDurabilityWhenCheckpointingInline() {
		// a second instance is required: the fence exists on the defaults, so the disabled case has to be configured
		// deliberately. Four zeroes here would render as "durability is instant and free" - and when it is sync
		// writes that are off, that reads as the exact inverse of the truth - so the component declines instead
		this.evita.close();
		this.evita = new Evita(getInlineCheckpointEvitaConfiguration(this.paths));
		// a freshly constructed Evita installs UnusableCatalog placeholders and loads catalogs on a background pool,
		// so anything touching the catalog before that finishes legitimately sees BEING_ACTIVATED
		awaitCatalogLoaded();

		final CatalogStatistics statistics = this.evita.management().getCatalogStatistics(
			CATALOG, EnumSet.of(CatalogStatisticsComponent.DURABILITY)
		);

		final ComponentStatus status = statistics.componentStatus()
			.get(CatalogStatisticsComponent.DURABILITY);
		assertNotNull(status);
		assertEquals(ComponentAvailability.FEATURE_DISABLED, status.availability());
		assertNotNull(status.reason());
		assertNull(statistics.durability(), "A declined component must carry no value at all");
	}

	/**
	 * Blocks until the catalog has finished loading.
	 *
	 * Catalogs are loaded on a background pool, and until one finishes it is represented by an `UnusableCatalog`
	 * placeholder - the honest answer while activation is in flight, and not what these tests are measuring.
	 */
	private void awaitCatalogLoaded() {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
					.identity()
					.unusable()
			);
	}

	/**
	 * Reads the durability component of the test catalog.
	 *
	 * @return the delivered {@link DurabilityStatistics}
	 */
	@Nonnull
	private DurabilityStatistics fetchDurability() {
		return this.evita.management()
			.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.DURABILITY))
			.durabilityIfPresent()
			.orElseThrow();
	}

	private ActivityStatistics fetchActivity() {
		return this.evita.management()
			.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.ACTIVITY))
			.activityIfPresent()
			.orElseThrow();
	}

	/**
	 * Reads the history component of the test catalog from the given engine instance.
	 *
	 * @param instance the engine instance holding the catalog
	 * @return the delivered {@link HistoryStatistics}
	 */
	@Nonnull
	private static HistoryStatistics fetchHistory(@Nonnull Evita instance) {
		return instance.management()
			.getCatalogStatistics(CATALOG, EnumSet.of(CatalogStatisticsComponent.HISTORY))
			.historyIfPresent()
			.orElseThrow();
	}

	/**
	 * Builds the configuration of the embedded instance used by this test.
	 *
	 * @param timeTravelEnabled whether the instance retains superseded data files for point-in-time reads
	 * @return configuration pointing at this test's isolated directories
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(boolean timeTravelEnabled) {
		return getEvitaConfiguration(this.paths, timeTravelEnabled);
	}

	/**
	 * Builds the configuration of an embedded instance rooted at the given directories.
	 *
	 * @param testPaths         directories the instance stores its data in
	 * @param timeTravelEnabled whether the instance retains superseded data files for point-in-time reads
	 * @return configuration pointing at those directories
	 */
	/**
	 * Builds a configuration that checkpoints at the end of every round, which is what removes the durability fence.
	 *
	 * Either switch suffices and the two are deliberately orthogonal in the engine: an interval with `syncWrites` off
	 * would defer nothing, because the writes never reach the device on that setting anyway. A zero interval is used
	 * here because it leaves durability itself intact and isolates the absence of *deferral*.
	 *
	 * @param testPaths directories the instance stores its data in
	 * @return configuration with no deferred-checkpoint fence
	 */
	@Nonnull
	private EvitaConfiguration getInlineCheckpointEvitaConfiguration(@Nonnull TestPaths testPaths) {
		return newTestEvitaConfigurationBuilder(testPaths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(testPaths.storage())
					.workDirectory(testPaths.work())
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.checkpointIntervalInMillis(0L)
					.build()
			)
			.build();
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(@Nonnull TestPaths testPaths, boolean timeTravelEnabled) {
		return newTestEvitaConfigurationBuilder(testPaths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(testPaths.storage())
					.workDirectory(testPaths.work())
					.timeTravelEnabled(timeTravelEnabled)
					.build()
			)
			.build();
	}
}
