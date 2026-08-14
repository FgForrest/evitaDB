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
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexTypeCount;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.api.statistics.CommitPipelineStatistics;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.ComponentStatus;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.api.statistics.CatalogIndexCardinality.GlobalUniqueIndexCardinality;
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
 * level-appropriate component at once and assert what each one is required to answer. A component that is implemented
 * but still reports a refusal - because the arm that declines it was never removed - compiles cleanly and goes on
 * saying "unavailable" forever, and nothing else in the suite would notice.
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
	 * The three values the indexed fixture's `availability` attribute cycles through - few enough that its filter
	 * index cannot narrow anything down, which is exactly what `INDEX_CARDINALITY` has to make visible.
	 */
	private static final String[] AVAILABILITIES = {"IN_STOCK", "OUT_OF_STOCK", "PRE_ORDER"};

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

	/**
	 * Every catalog-level component of this build answers on a healthy catalog - there is no partition into
	 * implemented and not-yet-implemented, and this asserts exactly that. A component left behind in the catch-all arm
	 * of the dispatch switch reports something other than `DELIVERED` and fails here, which is what the assertion is
	 * for; the two non-delivered outcomes that do exist (`CATALOG_UNUSABLE`, `FEATURE_DISABLED`) both need a catalog
	 * this fixture deliberately does not produce.
	 */
	@Test
	@DisplayName("Every catalog-level component answers on a healthy catalog")
	void shouldDeliverEveryCatalogLevelComponent() {
		goLive();
		final CatalogStatistics statistics = this.evita.management().getCatalogStatistics(
			CATALOG, catalogLevelComponents()
		);

		for (final CatalogStatisticsComponent component : catalogLevelComponents()) {
			final ComponentStatus status = statistics.componentStatus().get(component);
			assertNotNull(status, "Component `" + component + "` was requested but carries no status at all");
			assertEquals(
				ComponentAvailability.DELIVERED, status.availability(),
				"Component `" + component + "` is implemented but reported `" + status.availability() +
					"` - most likely it was never moved out of the catch-all arm of the dispatch switch"
			);
		}
	}

	/**
	 * A collection-level component has exactly one legal outcome: it delivers. There is no set of exceptions to
	 * consult here, unlike at the catalog level, because `EntityCollectionStatistics.Builder` offers no way to record
	 * a refusal - a catalog can be warming up, corrupted or configured with a feature switched off while still owing
	 * an answer, and a collection of a catalog in any of those states cannot be reached to be asked in the first
	 * place. This assertion is what holds that invariant: adding a component that declines at this level fails here.
	 */
	@Test
	@DisplayName("Every collection-level component answers")
	void shouldDeliverEveryCollectionLevelComponent() {
		final EntityCollectionStatistics statistics = this.evita.management().getEntityCollectionStatistics(
			CATALOG, ENTITY_PRODUCT, collectionLevelComponents()
		);

		for (final CatalogStatisticsComponent component : collectionLevelComponents()) {
			final ComponentStatus status = statistics.componentStatus().get(component);
			assertNotNull(status, "Component `" + component + "` was requested but carries no status at all");
			assertEquals(
				ComponentAvailability.DELIVERED, status.availability(),
				"Component `" + component + "` reported `" + status.availability() + "` - no collection-level " +
					"component may answer with anything but a value"
			);
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
	@DisplayName("A low-selectivity index is distinguishable from a discriminating one")
	void shouldReportDistinctValuesApartFromTheRecordsTheyCover() {
		// the shared fixture indexes nothing, so this needs its own catalog: `code` is unique per product and
		// `availability` deliberately holds three values across all fifty. Both are filterable, so a reading that
		// confused distinct values with records covered would report the same pair twice
		final String indexedCatalog = CATALOG + "Indexed";
		buildIndexedCatalog(indexedCatalog);

		final CollectionIndexCardinality cardinality = cardinalityOf(indexedCatalog, ENTITY_PRODUCT);
		final AttributeCardinality code = filterCardinalityOf(cardinality, "code");
		final AttributeCardinality availability = filterCardinalityOf(cardinality, "availability");

		assertEquals(
			50, code.recordsCovered(),
			"The filter index over `code` covers every product; `recordsCovered` is not being read from the index"
		);
		assertEquals(
			50, code.distinctValueCount(),
			"`code` is unique per product, so its distinct-value count must equal the records it covers"
		);
		assertEquals(
			50, availability.recordsCovered(),
			"Every product carries an `availability`, so its filter index covers all fifty as well"
		);
		// the whole point of the component: two indexes covering the identical record set, one of which cannot
		// narrow anything down. A `distinctValueCount` wired to the same counter as `recordsCovered` reports 50 here
		assertEquals(
			3, availability.distinctValueCount(),
			"`availability` holds three values across fifty products - the low-selectivity case this component " +
				"exists to expose - but its distinct-value count does not say so"
		);
	}

	@Test
	@DisplayName("Data-bounded indexes are counted rather than described")
	void shouldOmitThePerReferencedEntityIndexesFromTheCardinalityReport() {
		final String indexedCatalog = CATALOG + "Indexed";
		buildIndexedCatalog(indexedCatalog);

		final CollectionIndexCardinality cardinality = cardinalityOf(indexedCatalog, ENTITY_PRODUCT);

		// every described index is schema-bounded: the global one plus one per reference schema. Asserting the kinds
		// rather than a count keeps this pinned to the partition instead of to how many indexes the fixture makes
		for (final IndexCardinality index : cardinality.indexes()) {
			assertTrue(
				index.indexType() == EntityIndexType.GLOBAL ||
					index.indexType() == EntityIndexType.REFERENCED_ENTITY_TYPE ||
					index.indexType() == EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE,
				"Index kind `" + index.indexType() + "` grows with the data and must not be described one by one"
			);
		}
		// ten categories referenced by the products produce ten `REFERENCED_ENTITY` indexes. Without the omission
		// they would each be described, and the response would grow with the catalog's contents
		assertTrue(
			cardinality.omittedIndexCount() > 0,
			"The fixture references ten categories, so per-referenced-entity indexes exist and must be counted as " +
				"omitted rather than silently dropped: " + cardinality
		);
		// cross-checked against `INDEX_SUMMARY`, which reaches the same number by a different route: it *walks* every
		// index key and classifies each by kind, while `INDEX_CARDINALITY` never visits the omitted ones at all and
		// derives their number by subtracting what its targeted lookups found from the map size. Asserting
		// `described + omitted == total` instead would be a tautology under that derivation, and would pass even if
		// every targeted lookup silently missed
		assertEquals(
			dataBoundedIndexCountOf(indexedCatalog, ENTITY_PRODUCT),
			cardinality.omittedIndexCount(),
			"The indexes counted as omitted do not match the data-bounded indexes the summary walks - a targeted " +
				"lookup missed a schema-bounded index and it was silently folded into the omitted count"
		);
		// the reference index is where the reference cardinality lives; the global index has no reference dimension
		// at all, and reporting `0` for it would read as "this collection references nothing"
		final IndexCardinality global = indexOfKind(cardinality, EntityIndexType.GLOBAL);
		assertTrue(
			global.referencedEntityCountIfKnown().isEmpty(),
			"The global index tracks no references, but it reported a reference cardinality: " + global
		);
		final IndexCardinality referenced = indexOfKind(cardinality, EntityIndexType.REFERENCED_ENTITY_TYPE);
		assertEquals(
			10, referenced.referencedEntityCountIfKnown().orElseThrow(),
			"The reference index tracks the ten referenced categories"
		);
		assertEquals(
			"categories", referenced.discriminator(),
			"A reference index is discriminated by its reference name; without it siblings are indistinguishable"
		);
	}

	@Test
	@DisplayName("Indexes a committed transaction created are counted")
	void shouldCountIndexesCreatedByACommittedTransaction() {
		final String indexedCatalog = CATALOG + "Indexed";
		buildIndexedCatalog(indexedCatalog);
		this.evita.updateCatalog(indexedCatalog, EvitaSessionContract::goLiveAndClose);

		final long before = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);
		final int dataBoundedBefore = dataBoundedIndexCountOf(indexedCatalog, ENTITY_PRODUCT);

		// a category nothing references yet: referencing it is what makes the engine create one more
		// per-referenced-entity index, which is the population change this test is about
		this.evita.updateCatalog(
			indexedCatalog,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 500));
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 500)
						.setAttribute("code", "product-500")
						.setAttribute("availability", AVAILABILITIES[0])
						.setReference("categories", 500)
				);
			}
		);

		final long after = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);
		assertTrue(
			after > before,
			"Referencing a category nothing referenced before creates at least one index, but the maintained count " +
				"did not move: " + before + " -> " + after
		);
		assertTrue(
			dataBoundedIndexCountOf(indexedCatalog, ENTITY_PRODUCT) > dataBoundedBefore,
			"The indexes created are per-referenced-entity ones, so the data-bounded count has to carry the growth"
		);

		// The independent oracle, and the reason this test does not assert a fixed delta: reopening the engine rebuilds
		// the population from disk through the load path, which counts every index it attaches and shares no code with
		// the commit-time delta. Agreement between the two therefore catches over- AND under-counting, while a
		// hard-coded `before + 1` would only pin how many indexes this particular fixture happens to produce.
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration(false));
		awaitCatalogLoaded(indexedCatalog);

		assertEquals(
			after, collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT),
			"The maintained count disagrees with what a reload counts from disk - the commit-time delta and the " +
				"index set have drifted apart"
		);
	}

	@Test
	@DisplayName("Index counts stay accurate while the catalog is still warming up")
	void shouldMaintainIndexCountsDuringWarmUp() {
		// deliberately NOT taken live: a WARMING_UP catalog opens no transaction, so its index creations and removals
		// are counted inline rather than derived at commit. That is the other half of the maintenance and nothing
		// else in this class exercises it - both other counter tests go live first
		final String indexedCatalog = CATALOG + "Indexed";
		buildIndexedCatalog(indexedCatalog);

		final long afterBuild = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);

		this.evita.updateCatalog(
			indexedCatalog,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 500));
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 500)
						.setAttribute("code", "product-500")
						.setAttribute("availability", AVAILABILITIES[0])
						.setReference("categories", 500)
				);
			}
		);
		final long afterCreate = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);
		assertTrue(
			afterCreate > afterBuild,
			"A bulk-loaded index creation was not counted: " + afterBuild + " -> " + afterCreate
		);

		// removing the only entity referencing that category takes its index with it, which is the inline decrement
		this.evita.updateCatalog(
			indexedCatalog,
			session -> {
				session.deleteEntity(ENTITY_PRODUCT, 500);
			}
		);
		final long afterDelete = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);
		assertTrue(
			afterDelete < afterCreate,
			"Removing the only entity referencing that category did not drop its index, so this test proves nothing " +
				"about the inline decrement: " + afterCreate + " -> " + afterDelete
		);

		// the same independent oracle the committed-transaction test uses, and it is what covers the removal: had the
		// drop failed to decrement, the maintained count would stand above what a reload counts from disk
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration(false));
		awaitCatalogLoaded(indexedCatalog);

		assertEquals(
			afterDelete, collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT),
			"The count maintained during warm-up disagrees with what a reload counts from disk"
		);
	}

	@Test
	@DisplayName("Indexes a committed transaction dropped stop being counted")
	void shouldStopCountingIndexesDroppedByACommittedTransaction() {
		// the mirror of the creation case, and the only test that drives the removal arm of the commit-time delta.
		// The warm-up test covers dropping an index too, but through the inline path - the two share no code, so
		// neither stands in for the other
		final String indexedCatalog = CATALOG + "Indexed";
		buildIndexedCatalog(indexedCatalog);
		this.evita.updateCatalog(indexedCatalog, EvitaSessionContract::goLiveAndClose);

		// created in its own committed transaction, so the drop below removes an index this catalog version holds
		// rather than one that was never published
		this.evita.updateCatalog(
			indexedCatalog,
			session -> {
				session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 500));
				session.upsertEntity(
					session.createNewEntity(ENTITY_PRODUCT, 500)
						.setAttribute("code", "product-500")
						.setAttribute("availability", AVAILABILITIES[0])
						.setReference("categories", 500)
				);
			}
		);
		final long afterCreate = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);

		this.evita.updateCatalog(
			indexedCatalog,
			session -> {
				// a block body, not an expression: `deleteEntity` returns a boolean, which makes a bare lambda match
				// both the consumer and the function overload of `updateCatalog`
				session.deleteEntity(ENTITY_PRODUCT, 500);
			}
		);
		final long afterDelete = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);
		assertTrue(
			afterDelete < afterCreate,
			"The committed transaction dropped no index at all, so this test proves nothing about the removal arm " +
				"of the commit-time delta: " + afterCreate + " -> " + afterDelete
		);

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration(false));
		awaitCatalogLoaded(indexedCatalog);

		assertEquals(
			afterDelete, collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT),
			"The maintained count disagrees with what a reload counts from disk - the dropped index is still being " +
				"counted"
		);
	}

	@Test
	@DisplayName("Indexes a rolled-back transaction created are not counted")
	void shouldNotCountIndexesCreatedByARolledBackTransaction() {
		final String indexedCatalog = CATALOG + "Indexed";
		buildIndexedCatalog(indexedCatalog);
		this.evita.updateCatalog(indexedCatalog, EvitaSessionContract::goLiveAndClose);

		final long before = collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT);

		// the same work as the committed case above, discarded. This is the case a counter incremented where the
		// index is *created* silently gets wrong: the map write lands in a diff layer the rollback throws away, but
		// the increment would already have happened and would never be undone
		assertThrows(
			RollbackException.class,
			() -> this.evita.updateCatalog(
				indexedCatalog,
				session -> {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, 600));
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, 600)
							.setAttribute("code", "product-600")
							.setAttribute("availability", AVAILABILITIES[0])
							.setReference("categories", 600)
					);
					session.setRollbackOnly();
				}
			)
		);

		assertEquals(
			before, collectionIndexCountOf(indexedCatalog, ENTITY_PRODUCT),
			"A rolled-back transaction moved the index count - the count is being maintained where the index is " +
				"created rather than where the transaction commits, and is now permanently wrong"
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

	@Test
	@DisplayName("Catalog-level index cardinality describes the global unique index of every scope")
	void shouldReportCardinalityOfTheCatalogsGlobalUniqueIndexes() {
		// the catalog level of INDEX_CARDINALITY reports the *catalog* index - the global unique indexes backing
		// entity-type-less unique lookups - and never the collections' own indexes. It needs its own catalog for the
		// same reason the archiving test does: the shared fixture declares no globally-unique attribute at all
		final Scope[] bothScopes = {Scope.LIVE, Scope.ARCHIVED};
		final String globalCatalog = CATALOG + "GlobalUnique";
		this.evita.defineCatalog(globalCatalog)
			.withAttribute("globalCode", String.class, thatIs -> thatIs.uniqueGloballyInScope(bothScopes))
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			globalCatalog,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute("globalCode")
					.updateVia(session);
				for (int pk = 1; pk <= 3; pk++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, pk).setAttribute("globalCode", "product-" + pk)
					);
				}
			}
		);

		final GlobalUniqueIndexCardinality[] live = globalUniqueCardinalityOf(globalCatalog);
		assertEquals(1, live.length, "Exactly one global unique index exists before anything is archived");
		assertEquals("globalCode", live[0].attributeName());
		assertNull(
			live[0].locale(),
			"`globalCode` is unique globally across every locale, so its index is not locale-bound"
		);
		assertEquals(Scope.LIVE, live[0].scope());
		assertEquals(
			3, live[0].distinctValueCount(),
			"Three globally-unique values were written, and a globally-unique value covers exactly one record"
		);

		// archiving one entity moves its value out of the LIVE index and creates the ARCHIVED one, so the component
		// must now describe both scopes. A projection that only ever looked at `Scope.LIVE` still passes everything
		// above and fails here
		this.evita.updateCatalog(
			globalCatalog,
			session -> {
				// a block body, not an expression: `archiveEntity` returns a boolean, which makes a bare lambda match
				// both the consumer and the function overload of `updateCatalog`
				session.archiveEntity(ENTITY_PRODUCT, 1);
			}
		);

		final GlobalUniqueIndexCardinality[] both = globalUniqueCardinalityOf(globalCatalog);
		assertEquals(2, both.length, "Archiving created the catalog's `ARCHIVED` index, which must be described too");
		assertEquals(
			2, cardinalityInScope(both, Scope.LIVE),
			"The archived entity's value must have left the `LIVE` global unique index"
		);
		assertEquals(
			1, cardinalityInScope(both, Scope.ARCHIVED),
			"The archived entity's value must have arrived in the `ARCHIVED` global unique index"
		);
	}

	/**
	 * Reads the catalog-level index cardinality component.
	 *
	 * @param catalogName catalog to ask
	 * @return the described global unique indexes
	 */
	@Nonnull
	private GlobalUniqueIndexCardinality[] globalUniqueCardinalityOf(@Nonnull String catalogName) {
		return this.evita.management()
			.getCatalogStatistics(catalogName, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY))
			.indexCardinalityIfPresent()
			.orElseThrow()
			.globalUniqueIndexes();
	}

	/**
	 * Finds the distinct value count of the single global unique index reported in one scope.
	 *
	 * @param indexes the described global unique indexes
	 * @param scope   scope to look up
	 * @return the distinct value count of that scope's index
	 */
	private static int cardinalityInScope(
		@Nonnull GlobalUniqueIndexCardinality[] indexes,
		@Nonnull Scope scope
	) {
		for (final GlobalUniqueIndexCardinality index : indexes) {
			if (index.scope() == scope) {
				return index.distinctValueCount();
			}
		}
		throw new AssertionError("No global unique index was described in scope `" + scope + "`!");
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
	 * Builds a catalog whose product collection is actually indexed - the shared fixture indexes nothing at all, and
	 * an unindexed collection reports an empty cardinality for every index it holds.
	 *
	 * `code` is filterable and unique per product; `availability` is filterable and deliberately holds only three
	 * values across all fifty, which is the low-selectivity case `INDEX_CARDINALITY` exists to expose. Each product
	 * references one of ten categories, which is what creates both the schema-bounded reference index and the ten
	 * data-bounded per-referenced-entity indexes the report must omit.
	 *
	 * @param catalogName name of the catalog to create
	 */
	private void buildIndexedCatalog(@Nonnull String catalogName) {
		this.evita.defineCatalog(catalogName).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(ENTITY_CATEGORY).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
					.withAttribute("availability", String.class, AttributeSchemaEditor::filterable)
					// `withReferenceToEntity`, not `withReferenceTo` - the latter declares an *unmanaged* reference and
					// the schema is rejected when a collection of that name actually exists. Indexing is what
					// creates the reference indexes this test is about
					.withReferenceToEntity(
						"categories", ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				for (int i = 1; i <= 10; i++) {
					session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, i));
				}
				for (int i = 1; i <= 50; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i)
							.setAttribute("code", "product-" + i)
							.setAttribute("availability", AVAILABILITIES[i % AVAILABILITIES.length])
							.setReference("categories", (i % 10) + 1)
					);
				}
			}
		);
	}

	/**
	 * Counts the collection's data-bounded indexes - the per-referenced-entity and per-group-entity ones - by walking
	 * the `INDEX_SUMMARY` breakdown, which reaches them by a different route than `INDEX_CARDINALITY` does.
	 *
	 * @param catalogName catalog holding the collection
	 * @param entityType  collection to ask
	 * @return how many of its indexes grow with the data rather than with the schema
	 */
	private int dataBoundedIndexCountOf(@Nonnull String catalogName, @Nonnull String entityType) {
		final CollectionIndexSummary summary = this.evita.management()
			.getEntityCollectionStatistics(
				catalogName, entityType, EnumSet.of(CatalogStatisticsComponent.INDEX_SUMMARY)
			)
			.indexSummaryIfPresent()
			.orElseThrow();
		int dataBounded = 0;
		for (final IndexTypeCount typeCount : summary.byTypeAndScope()) {
			if (typeCount.indexType() == EntityIndexType.REFERENCED_ENTITY ||
				typeCount.indexType() == EntityIndexType.REFERENCED_GROUP_ENTITY) {
				dataBounded += typeCount.count();
			}
		}
		return dataBounded;
	}

	/**
	 * Reads one collection's index cardinality readings.
	 *
	 * @param catalogName catalog holding the collection
	 * @param entityType  collection to ask
	 * @return the delivered component
	 */
	@Nonnull
	private CollectionIndexCardinality cardinalityOf(@Nonnull String catalogName, @Nonnull String entityType) {
		return this.evita.management()
			.getEntityCollectionStatistics(
				catalogName, entityType, EnumSet.of(CatalogStatisticsComponent.INDEX_CARDINALITY)
			)
			.indexCardinalityIfPresent()
			.orElseThrow();
	}

	/**
	 * Picks the readings of one filter index out of the global index's attributes.
	 *
	 * @param cardinality   the delivered component
	 * @param attributeName attribute whose filter index is wanted
	 * @return its readings
	 */
	@Nonnull
	private static AttributeCardinality filterCardinalityOf(
		@Nonnull CollectionIndexCardinality cardinality,
		@Nonnull String attributeName
	) {
		final IndexCardinality global = indexOfKind(cardinality, EntityIndexType.GLOBAL);
		for (final AttributeCardinality attribute : global.attributes()) {
			if (attribute.indexType() == AttributeIndexType.FILTER && attributeName.equals(attribute.attributeName())) {
				return attribute;
			}
		}
		throw new AssertionError(
			"The global index reports no filter index over `" + attributeName + "`: " + global
		);
	}

	/**
	 * Picks the single described index of one kind.
	 *
	 * @param cardinality the delivered component
	 * @param indexType   kind to look for
	 * @return the one index of that kind
	 */
	@Nonnull
	private static IndexCardinality indexOfKind(
		@Nonnull CollectionIndexCardinality cardinality,
		@Nonnull EntityIndexType indexType
	) {
		for (final IndexCardinality index : cardinality.indexes()) {
			if (index.indexType() == indexType) {
				return index;
			}
		}
		throw new AssertionError("No index of kind `" + indexType + "` was described: " + cardinality);
	}

	/**
	 * Transitions the test catalog out of `WARMING_UP` into `ALIVE`, which is what gives it a transactional commit
	 * pipeline and a write-ahead log.
	 */
	private void goLive() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Returns every component that may be asked of a catalog, which is what the partition test requests at once. Every
	 * component has a catalog-level form, so this is the whole enum.
	 *
	 * @return all catalog-level components
	 */
	@Nonnull
	private static Set<CatalogStatisticsComponent> catalogLevelComponents() {
		return EnumSet.allOf(CatalogStatisticsComponent.class);
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
		awaitCatalogLoaded(CATALOG);
	}

	/**
	 * Blocks until the named catalog has finished loading.
	 *
	 * @param catalogName catalog to wait for
	 */
	private void awaitCatalogLoaded(@Nonnull String catalogName) {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(
				() -> !this.evita.management()
					.getCatalogStatistics(catalogName, EnumSet.of(CatalogStatisticsComponent.IDENTITY))
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
