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

package io.evitadb.core.transaction;

import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.conflict.AssociatedDataConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.EntityConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests pinning the MVCC concurrency-window mathematics of {@link TransactionManager#identifyConflicts}.
 *
 * The contract under test: two transactions are successors when the later transaction's snapshot
 * (session) catalog version is greater than or equal to the earlier transaction's assigned commit
 * version; they are concurrent — and must be examined for write-write conflicts — exactly when the
 * earlier transaction's commit version is greater than the later transaction's snapshot version.
 * The conflict ring buffer therefore registers every accepted transaction's keys under its
 * **commit** version, never under its snapshot version.
 *
 * These tests drive a real {@link TransactionManager} (with a real conflict ring buffer) through the
 * same call sequence the conflict-resolution stage performs, with the living catalog pinned at the
 * bootstrap version so the "committed to WAL but not yet visible in the live view" window stays open.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("MVCC conflict window mathematics in TransactionManager")
@Tag(ENGINE)
@Tag(TRANSACTION)
class TransactionManagerConflictWindowTest {
	private static final String CATALOG_NAME = "conflictWindowCatalog";
	private static final String ENTITY_TYPE = "Product";
	private static final long INITIAL_VERSION = 10L;
	private static final AttributeKey QUANTITY = new AttributeKey("quantity");
	private static final IntegerNumberRange ALLOWED_RANGE = IntegerNumberRange.between(0, 100);

	private TransactionManager transactionManager;

	/**
	 * Wraps the passed conflict keys in an insertion-ordered set the same way the isolated WAL
	 * persistence service hands them to the commit pipeline.
	 */
	@Nonnull
	private static Set<ConflictKey> keys(@Nonnull ConflictKey... conflictKeys) {
		return new LinkedHashSet<>(Arrays.asList(conflictKeys));
	}

	/**
	 * Creates a range-constrained commutative delta key on the shared `quantity` attribute of entity #1.
	 */
	@Nonnull
	private static AttributeDeltaConflictKey quantityDelta(int delta) {
		return quantityDeltaForEntity(1, delta);
	}

	/**
	 * Creates a range-constrained commutative delta key on the shared `quantity` attribute of the entity
	 * identified by the given primary key.
	 */
	@Nonnull
	private static AttributeDeltaConflictKey quantityDeltaForEntity(int entityPrimaryKey, int delta) {
		return new AttributeDeltaConflictKey(ENTITY_TYPE, entityPrimaryKey, QUANTITY, delta, ALLOWED_RANGE, false);
	}

	@BeforeEach
	void setUp() {
		final SealedCatalogSchema catalogSchema = mock(SealedCatalogSchema.class);
		when(catalogSchema.version()).thenReturn(1);
		when(catalogSchema.getConflictResolution()).thenReturn(Optional.empty());

		// the delta resolvers look the attribute base value up in the living catalog; the storage part
		// is absent here, so the accumulated value consists of the deltas alone
		final EntityCollection entityCollection = mock(EntityCollection.class);
		when(entityCollection.fetch(anyLong(), any(), any(), any())).thenReturn(null);

		// the living catalog stays pinned at the bootstrap version for the whole test - every commit
		// accepted below is durable in the WAL but not yet visible in the live view, which is exactly
		// the window the conflict ring buffer must cover
		final Catalog catalog = mock(Catalog.class);
		when(catalog.getName()).thenReturn(CATALOG_NAME);
		when(catalog.getVersion()).thenReturn(INITIAL_VERSION);
		when(catalog.getSchema()).thenReturn(catalogSchema);
		when(catalog.getLastCatalogVersionInMutationStream()).thenReturn(INITIAL_VERSION);
		when(catalog.getFirstCatalogVersionInMutationStream()).thenReturn(INITIAL_VERSION);
		when(catalog.getEntitySchema(anyString())).thenReturn(Optional.empty());
		when(catalog.getCollectionForEntityOrThrowException(ENTITY_TYPE)).thenReturn(entityCollection);

		final EvitaConfiguration configuration = EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.changeDataCapture(ChangeDataCaptureOptions.builder().enabled(false).build())
					.build()
			)
			.build();
		final Evita evita = mock(Evita.class);
		when(evita.getConfiguration()).thenReturn(configuration);

		final ObservableExecutorService synchronousExecutor = mock(ObservableExecutorService.class);
		doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(synchronousExecutor).execute(any(Runnable.class));

		this.transactionManager = new TransactionManager(
			catalog,
			evita,
			mock(Scheduler.class),
			synchronousExecutor,
			synchronousExecutor,
			newCatalog -> {
			},
			INITIAL_VERSION
		);
	}

	/**
	 * Mimics the accept path of the conflict-resolution stage for a transaction that passed the
	 * conflict check: reserve the next catalog version for the conflict-key registration, assign it,
	 * and mark it written to the WAL (which also advances the ring buffer's visibility watermark).
	 *
	 * @return the catalog version the transaction committed at
	 */
	private long acceptTransaction(long sessionCatalogVersion, @Nonnull ConflictKey... conflictKeys) {
		final long reservedCatalogVersion = this.transactionManager.getLastAssignedCatalogVersion() + 1;
		this.transactionManager.identifyConflicts(
			sessionCatalogVersion, reservedCatalogVersion, OffsetDateTime.now(), keys(conflictKeys)
		);
		final long assignedCatalogVersion = this.transactionManager.getNextCatalogVersionToAssign();
		this.transactionManager.updateLastWrittenCatalogVersion(assignedCatalogVersion);
		return assignedCatalogVersion;
	}

	/**
	 * The long-running-writer interleaving: transaction A opens its snapshot at version 10, other
	 * transactions meanwhile commit versions 11 and 12, a competing transaction D opens its snapshot
	 * at 12, and only then A commits (to version 13) a write on entity #42. D's snapshot (12) is older
	 * than A's commit version (13), so a subsequent write of D to the same entity is concurrent with A
	 * and must be rejected — regardless of the fact that A's *snapshot* (10) predates D's.
	 */
	@Test
	@DisplayName("commit of a long-running transaction must conflict with a snapshot opened before it")
	void shouldDetectConflictOfLongRunningTransactionCommittedAfterNewerSnapshotOpened() {
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 1));
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 2));
		// the long-running transaction commits last, receiving version 13
		final long longRunningCommitVersion = acceptTransaction(
			INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 42)
		);
		assertEquals(13L, longRunningCommitVersion);

		// snapshot 12 < commit 13 → concurrent → the overlapping write must be rejected
		final ConflictingCatalogMutationException conflict = assertThrows(
			ConflictingCatalogMutationException.class,
			() -> this.transactionManager.identifyConflicts(
				12L, 14L, OffsetDateTime.now(), keys(new EntityConflictKey(ENTITY_TYPE, 42))
			)
		);
		// the diagnostics must report the conflicting transaction's commit version, not its snapshot
		assertEquals(longRunningCommitVersion, conflict.getCatalogVersion());
	}

	/**
	 * The successor boundary of the window: a transaction whose snapshot version equals the earlier
	 * transaction's commit version saw its changes and must not conflict with it.
	 */
	@Test
	@DisplayName("snapshot equal to the earlier commit version is a successor and must not conflict")
	void shouldTreatDirectSuccessorAsNonConflicting() {
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 1));
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 2));
		final long commitVersion = acceptTransaction(
			INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 42)
		);

		// snapshot 13 == commit 13 → successor → same-entity write passes the check
		assertDoesNotThrow(
			() -> this.transactionManager.identifyConflicts(
				commitVersion, commitVersion + 1, OffsetDateTime.now(),
				keys(new EntityConflictKey(ENTITY_TYPE, 42))
			)
		);
	}

	/**
	 * A rejected transaction's rollback must remove only the keys registered under its own reserved
	 * catalog version — the committed transactions' keys must survive, so an equally old snapshot
	 * attempting the same overlapping write right after the rollback is still rejected.
	 */
	@Test
	@DisplayName("rollback of a rejected transaction must not release committed conflict keys")
	void shouldNotWipeCommittedConflictKeysWhenRejectedTransactionRollsBack() {
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 1));
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 2));
		acceptTransaction(INITIAL_VERSION, new EntityConflictKey(ENTITY_TYPE, 42));

		// first attempt conflicts; the stage then rolls back the reservation
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> this.transactionManager.identifyConflicts(
				12L, 14L, OffsetDateTime.now(), keys(new EntityConflictKey(ENTITY_TYPE, 42))
			)
		);
		this.transactionManager.rollbackConflictKeys(14L);

		// the rollback must not have cleared the ring buffer - the same attempt must fail again
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> this.transactionManager.identifyConflicts(
				12L, 14L, OffsetDateTime.now(), keys(new EntityConflictKey(ENTITY_TYPE, 42))
			)
		);
		// while a non-overlapping write from the same snapshot passes
		assertDoesNotThrow(
			() -> this.transactionManager.identifyConflicts(
				12L, 14L, OffsetDateTime.now(), keys(new EntityConflictKey(ENTITY_TYPE, 7))
			)
		);
	}

	/**
	 * Range-constrained commutative deltas must accumulate every delta committed after the living
	 * catalog version (i.e. durable in the WAL but not yet visible in the live view) before admitting
	 * the incoming delta. With the living catalog pinned at version 10 and a committed `+60` delta at
	 * version 11, an incoming `+60` overflows the allowed `[0, 100]` range and must be rejected, while
	 * an incoming `+40` lands exactly on the boundary and passes.
	 */
	@Test
	@DisplayName("committed in-flight deltas must count against an incoming delta's allowed range")
	void shouldAccumulateCommittedDeltasAheadOfLivingCatalogAgainstIncomingRange() {
		// committed at version 11, invisible in the living catalog which stays at version 10
		acceptTransaction(INITIAL_VERSION, quantityDelta(60));

		// 60 (committed, in-flight) + 60 (incoming) = 120 > 100 → rejected
		assertThrows(
			ConflictingCatalogCommutativeMutationException.class,
			() -> this.transactionManager.identifyConflicts(
				INITIAL_VERSION, 12L, OffsetDateTime.now(), keys(quantityDelta(60))
			)
		);
		this.transactionManager.rollbackConflictKeys(12L);

		// 60 (committed, in-flight) + 40 (incoming) = 100 → still within the range
		assertDoesNotThrow(
			() -> this.transactionManager.identifyConflicts(
				INITIAL_VERSION, 12L, OffsetDateTime.now(), keys(quantityDelta(40))
			)
		);
	}

	/**
	 * Range-constrained delta accumulation is partitioned by the entity primary key: a delta committed on
	 * one entity must never count against an incoming delta on a *different* entity of the same type and
	 * attribute. This is what keeps two concurrently-created entities' identically range-guarded deltas in
	 * separate accumulation slots — {@link AttributeDeltaConflictKey#aggregationKey()} carries the resolved
	 * primary key precisely so the slots cannot collide. With a committed `+60` on entity #1, an incoming
	 * `+60` on entity #2 stays at its own `60` (within `[0, 100]`) and must pass, even though the very same
	 * `+60` applied again to entity #1 accumulates to `120` and is rejected.
	 */
	@Test
	@DisplayName("range-constrained deltas accumulate per entity primary key, not across entities")
	void shouldAccumulateDeltasPerEntityPrimaryKeyNotAcrossEntities() {
		// committed at version 11 on entity #1, invisible in the living catalog pinned at version 10
		acceptTransaction(INITIAL_VERSION, quantityDeltaForEntity(1, 60));

		// entity #2's slot holds only its own 60 (0 base + 60) → within [0, 100] → must pass, proving the
		// committed +60 on entity #1 does not bleed into a different entity's accumulation slot
		assertDoesNotThrow(
			() -> this.transactionManager.identifyConflicts(
				INITIAL_VERSION, 12L, OffsetDateTime.now(), keys(quantityDeltaForEntity(2, 60))
			)
		);
		this.transactionManager.rollbackConflictKeys(12L);

		// same-entity contrast: entity #1's slot already holds 60, so an incoming +60 overflows to 120
		assertThrows(
			ConflictingCatalogCommutativeMutationException.class,
			() -> this.transactionManager.identifyConflicts(
				INITIAL_VERSION, 12L, OffsetDateTime.now(), keys(quantityDeltaForEntity(1, 60))
			)
		);
	}

	/**
	 * Drives {@link TransactionManager#identifyConflicts} through the historical recompute path
	 * ({@code identifyConflictsInOldCommittedTransactions}) rather than the conflict ring buffer: the
	 * committing transaction's snapshot version is older than the ring buffer's effective start (seeded
	 * from the living catalog's version at construction time), so the very first scan throws the ring
	 * buffer's {@code OutsideScopeException} and the manager falls back to recomputing conflict keys from
	 * {@link Catalog#getCommittedLiveMutationStream(long, long)}. This is the same code path a real,
	 * genuinely aged-out ring-buffer entry would take; forcing it via the snapshot/effective-start gap
	 * avoids depending on the ring buffer's internal eviction/capacity bookkeeping.
	 *
	 * The engine default is configured with {@link GranularConflictPolicy#ASSOCIATED_DATA} carved out, so
	 * the recomputed historical mutation and the incoming granular write can disagree on scope exactly as
	 * the write-time path would.
	 */
	@Nested
	@DisplayName("Recompute path for aged-out entries")
	class RecomputePath {
		private static final long RECOMPUTE_LIVING_VERSION = 10L;
		private static final long AGED_OUT_SESSION_VERSION = 5L;

		/**
		 * Builds a {@link TransactionManager} whose ring buffer's effective start already sits ahead of
		 * {@link #AGED_OUT_SESSION_VERSION}, so any {@code identifyConflicts} call with that snapshot
		 * immediately falls back to the recompute path, replaying the given committed mutation.
		 */
		@Nonnull
		private TransactionManager recomputeTransactionManagerFor(@Nonnull CatalogBoundMutation committedMutation) {
			final SealedCatalogSchema catalogSchema = mock(SealedCatalogSchema.class);
			when(catalogSchema.version()).thenReturn(1);
			when(catalogSchema.getConflictResolution()).thenReturn(Optional.empty());

			final Catalog catalog = mock(Catalog.class);
			when(catalog.getName()).thenReturn(CATALOG_NAME);
			when(catalog.getVersion()).thenReturn(RECOMPUTE_LIVING_VERSION);
			when(catalog.getSchema()).thenReturn(catalogSchema);
			when(catalog.getLastCatalogVersionInMutationStream()).thenReturn(RECOMPUTE_LIVING_VERSION);
			when(catalog.getFirstCatalogVersionInMutationStream()).thenReturn(RECOMPUTE_LIVING_VERSION);
			when(catalog.getEntitySchema(anyString())).thenReturn(Optional.empty());
			when(catalog.getCommittedLiveMutationStream(anyLong(), anyLong()))
				.thenReturn(Stream.of(committedMutation));

			final EvitaConfiguration configuration = EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.changeDataCapture(ChangeDataCaptureOptions.builder().enabled(false).build())
						.build()
				)
				.transaction(
					TransactionOptions.builder()
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ASSOCIATED_DATA)
						.build()
				)
				.build();
			final Evita evita = mock(Evita.class);
			when(evita.getConfiguration()).thenReturn(configuration);

			final ObservableExecutorService synchronousExecutor = mock(ObservableExecutorService.class);
			doAnswer(invocation -> {
				((Runnable) invocation.getArgument(0)).run();
				return null;
			}).when(synchronousExecutor).execute(any(Runnable.class));

			return new TransactionManager(
				catalog, evita, mock(Scheduler.class), synchronousExecutor, synchronousExecutor,
				newCatalog -> {
				},
				RECOMPUTE_LIVING_VERSION
			);
		}

		@Test
		@DisplayName("aged-out coarse writer recomputes to the residual key, which does not conflict with an incoming granular write")
		void shouldNotConflictAgedOutResidualWriterVsIncomingGranularWrite() {
			// the historical mutation only touches a plain, non-carved-out attribute: it recomputes to
			// EntityResidualConflictKey, a sibling of - not an ancestor of - the incoming granular key
			final TransactionManager manager = recomputeTransactionManagerFor(
				new EntityUpsertMutation(
					ENTITY_TYPE, 1, EntityExistence.MAY_EXIST, new UpsertAttributeMutation("name", "foo")
				)
			);
			assertDoesNotThrow(
				() -> manager.identifyConflicts(
					AGED_OUT_SESSION_VERSION, RECOMPUTE_LIVING_VERSION + 1, OffsetDateTime.now(),
					keys(new AssociatedDataConflictKey(ENTITY_TYPE, 1, "feed-heureka"))
				)
			);
		}

		@Test
		@DisplayName("aged-out removal recomputes to the full entity key, which conflicts with an incoming granular write")
		void shouldConflictAgedOutRemovalVsIncomingGranularWrite() {
			// the historical mutation removed the entity: it recomputes to the full EntityConflictKey, which
			// contains every carved-out item, including the incoming granular associated-data write
			final TransactionManager manager = recomputeTransactionManagerFor(new EntityRemoveMutation(ENTITY_TYPE, 1));
			assertThrows(
				ConflictingCatalogMutationException.class,
				() -> manager.identifyConflicts(
					AGED_OUT_SESSION_VERSION, RECOMPUTE_LIVING_VERSION + 1, OffsetDateTime.now(),
					keys(new AssociatedDataConflictKey(ENTITY_TYPE, 1, "feed-heureka"))
				)
			);
		}
	}

}
