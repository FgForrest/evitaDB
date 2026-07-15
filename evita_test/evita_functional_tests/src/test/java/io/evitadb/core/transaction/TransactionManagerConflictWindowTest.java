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
import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.EntityConflictKey;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

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
		return new AttributeDeltaConflictKey(ENTITY_TYPE, 1, QUANTITY, delta, ALLOWED_RANGE);
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

}
