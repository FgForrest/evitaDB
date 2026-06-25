/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.collection;


import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutations;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutor;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithAssignedPrimaryKeys;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.traffic.TrafficRecordingEngine.MutationApplicationRecord;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.core.transaction.stage.mutation.EntityRemoveMutationWithConflictKeys;
import io.evitadb.core.transaction.stage.mutation.ServerEntityMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.mutation.EntityIndexMutation;
import io.evitadb.index.mutation.IndexImplicitMutations;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService.EntityWithFetchCount;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation.computeLocalMutationsForEntityRemoval;

/**
 * LocalMutationExecutorCollector is responsible for collecting, executing,
 * and managing local mutations within a transactional context.
 *
 * It maintains a list of mutation executors and entity mutations, handling
 * different levels of operations, consistency checks, and potential rollbacks
 * or commits of mutations depending on the success or failure of operations.
 *
 * The `execute()` method processes mutations in two sequential phases:
 *
 * - **container implicit-mutation phase** — the existing `popImplicitMutations()`
 *   loop on the container executor produces local and external mutations (e.g.,
 *   reflected reference synchronization)
 * - **index-trigger phase** — `popIndexImplicitMutations()` on the index executor
 *   produces {@link EntityIndexMutation} envelopes routed to target collections via
 *   {@link EntityCollection#applyIndexMutations(EntityIndexMutation, EvitaSessionContract)};
 *   these are never written to WAL and are regenerated on replay
 *
 * The container implicit-mutation phase completes fully before the index-trigger
 * phase begins, ensuring storage state is consistent when cross-entity triggers
 * evaluate expressions. The consistency check runs between the two phases, so a
 * consistency violation rolls back before any cross-entity index write is dispatched.
 *
 * Index finalization (per-locale language handling, full entity removal and
 * the empty-index sweep) is deferred to {@link #finish()}: it runs once, over
 * all accumulated executors, after the whole (possibly nested) mutation
 * recursion has unwound, and before the storage promote — so a finalization
 * failure routes to rollback rather than leaving a half-committed index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
class LocalMutationExecutorCollector {
	/**
	 * The timestamp when the collector was created.
	 */
	private final OffsetDateTime created = OffsetDateTime.now();
	/**
	 * The catalog instance used to fetch collections for entities.
	 */
	private final Catalog catalog;
	/**
	 * The persistence service used to fetch full entities by their primary key.
	 */
	private final EntityCollectionPersistenceService<StorageDescriptor, EntityCollectionHeader> persistenceService;
	/**
	 * The data store reader used to fetch entity data from the I/O storage.
	 */
	private final DataStoreReader dataStoreReader;
	/**
	 * The list of all involved local mutation executors.
	 */
	private final List<LocalMutationExecutor> executors = new ArrayList<>(16);
	/**
	 * The list of all entity mutations applied within this collector.
	 */
	private final List<EntityMutation> entityMutations = new ArrayList<>(16);
	/**
	 * Reference to the fully fetched entity that is being mutated. This entity is needed for removal / archive / restore
	 * mutations to generate local mutations for all necessary parts of the entity.
	 */
	private EntityWithFetchCount fullEntityBody;
	/**
	 * The current nesting level of the collector.
	 */
	private int level;
	/**
	 * The exception that occurred during the mutation execution process.
	 */
	private RuntimeException exception;
	/**
	 * The transactional-layer maintainer that owns the {@link #savepoint}. Captured when the savepoint is opened so
	 * that the matching commit / rollback is routed to the very same maintainer. {@code null} when no savepoint is
	 * active (warmup / WAL replay / non-atomic mutation).
	 */
	@Nullable private TransactionalLayerMaintainer savepointMaintainer;
	/**
	 * The savepoint bracketing the root entity mutation. While open, every diff layer touched by this mutation
	 * (including reflected-reference and index-trigger cross-collection writes that go through the same maintainer)
	 * has its pre-mutation state captured, so a failure surgically reverts only this entity's changes while the
	 * surrounding
	 * transaction continues. {@code null} when rollback is not atomic — i.e. there is no active transaction
	 * (warm-up, in-place index writes) or the mutation opted out (WAL replay). In those contexts there is no
	 * per-entity rollback: partial changes are intentionally left unreverted and a failed entity must be retried
	 * by rebuilding (warm-up), or the whole in-memory transaction is discarded on failure (WAL replay).
	 */
	@Nullable private Savepoint savepoint;

	/**
	 * Method fetches the full contents of the entity by its primary key from the I/O storage (taking advantage of
	 * modified parts in the {@link TransactionalDataStoreMemoryBuffer}.
	 */
	@Nonnull
	public EntityWithFetchCount getFullEntityContents(@Nonnull ContainerizedLocalMutationExecutor changeCollector) {
		final int entityPrimaryKey = changeCollector.getEntityPrimaryKey();
		final String entityType = changeCollector.getEntityType();
		if (
			this.fullEntityBody == null ||
				!Objects.equals(this.fullEntityBody.entity().getPrimaryKey(), entityPrimaryKey) ||
				!this.fullEntityBody.entity().getType().equals(entityType)
		) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				Query.query(
					collection(entityType),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED)),
					require(entityFetchAll())
				),
				OffsetDateTime.now(),
				Entity.class,
				null
			);
			this.fullEntityBody = this.persistenceService.toEntity(
				this.catalog.getVersion(),
				entityPrimaryKey,
				evitaRequest,
				changeCollector.getEntitySchema(),
				this.dataStoreReader,
				changeCollector.getAllEntityStorageParts()
			);
			Assert.notNull(
				this.fullEntityBody,
				() -> new InvalidMutationException(
					"There is no entity " + entityType + " with primary key " +
						entityPrimaryKey + " present! This means, that you're probably trying to update " +
						"entity that has been already removed!"
				)
			);
		}
		return this.fullEntityBody;
	}

	/**
	 * Executes a given entity mutation within the context of the specified entity schema,
	 * optionally checking consistency and generating implicit mutations.
	 *
	 * @param session                   the active session for query evaluation during index mutation
	 *                                  dispatch (index-trigger phase), may be null during WAL replay
	 * @param entitySchema              the schema of the entity to which the mutation applies
	 * @param entityMutation            the mutation to be applied to the entity
	 * @param checkConsistency          indicates whether consistency checks should be performed
	 * @param atomicRollback            when {@code true} and a transaction is active, the root entity mutation is
	 *                                  bracketed by a savepoint so that a partial failure is surgically reverted
	 *                                  while the transaction continues; when {@code false} (WAL replay) or when no
	 *                                  transaction is active (warmup) no savepoint is opened
	 * @param generateImplicitMutations flags indicating which implicit mutations should be generated
	 * @param changeCollector           executor to collect and apply local mutations
	 * @param entityIndexUpdater        executor to update the entity index with the mutations
	 * @param requestUpdatedEntity      the request specifying how to fetch the updated entity,
	 *                                  or null if no entity body is needed
	 * @return the updated entity with fetch count if {@code requestUpdatedEntity} is non-null and
	 * the entity was updated, or entity reference
	 */
	@Nonnull
	public <T> Optional<T> execute(
		@Nullable EvitaSessionContract session,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityMutation entityMutation,
		boolean checkConsistency,
		boolean atomicRollback,
		@Nonnull EnumSet<ImplicitMutationBehavior> generateImplicitMutations,
		@Nonnull ContainerizedLocalMutationExecutor changeCollector,
		@Nonnull EntityIndexLocalMutationExecutor entityIndexUpdater,
		@Nullable EvitaRequest requestUpdatedEntity,
		@Nonnull Class<T> requestedResultType
	) {
		// first register all mutation applicators and mutations to the internal state
		this.executors.add(entityIndexUpdater);
		this.executors.add(changeCollector);
		final LocalMutationExecutor[] orderedExecutors = {entityIndexUpdater, changeCollector};

		// add the mutation to the list of mutations, but only for root level mutations
		// mutations on lower levels are implicit mutations which should not be written to WAL (considered), because
		// are automatically generated when top level mutation is applied (replayed)
		final MutationApplicationRecord record;
		final boolean addToWAL;
		if (this.level == 0) {
			addToWAL = true;
			// root level changes are applied immediately
			changeCollector.setTrapChanges(false);
			// bracket the whole (possibly nested) root mutation with a savepoint so that a partial failure
			// reverts exactly this entity's diff-layer changes while the surrounding transaction keeps running.
			// Only meaningful when atomic rollback is requested (not WAL replay) AND a transaction is active
			// (warmup writes go in place to the index delegate, not to diff layers — there is nothing to snapshot).
			if (atomicRollback) {
				final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
				if (maintainer != null) {
					this.savepointMaintainer = maintainer;
					this.savepoint = maintainer.openSavepoint();
				}
			}
			// record mutation to the traffic recorder
			record = session == null ?
				null :
				this.catalog.getTrafficRecordingEngine().recordMutation(
					session.getId(),
					this.created,
					entityMutation
			);
		} else {
			addToWAL = false;
			// while implicit mutations are trapped in memory and stored on next flush
			changeCollector.setTrapChanges(true);
			// no record is created for implicit mutations
			record = null;
		}

		// apply mutations using applicators
		EntityWithFetchCount result = null;
		try {
			this.level++;

			final List<? extends LocalMutation<?, ?>> localMutations;
			if (entityMutation instanceof EntityRemoveMutation erm) {
				result = getFullEntityContents(changeCollector);
				localMutations = computeLocalMutationsForEntityRemoval(result.entity());
				// collect conflict keys for removal mutation
				if (this.catalog.hasGranularConflictPolicy()) {
					// we need to wrap the remove mutation to one which takes granular conflict keys into account
					// and this requires to fetch full entity body to compute all conflict keys
					entityMutation = new EntityRemoveMutationWithConflictKeys(
						erm,
						this.catalog.getConflictPolicy(),
						localMutations
					);
				}
			} else if (entityMutation instanceof EntityUpsertMutation) {
				localMutations = entityMutation.getLocalMutations();
				entityIndexUpdater.prepare(localMutations);
			} else {
				throw new GenericEvitaInternalError(
					"Unsupported entity mutation type: " + entityMutation.getClass().getName()
				);
			}
			if (addToWAL) {
				this.entityMutations.add(entityMutation);
			}

			for (final LocalMutation<?, ?> localMutation : localMutations) {
				for (final LocalMutationExecutor executor : orderedExecutors) {
					executor.applyMutation(localMutation);
				}
			}
			for (final LocalMutationExecutor executor : orderedExecutors) {
				executor.finishLocalMutationExecutionPhase();
			}

			if (!generateImplicitMutations.isEmpty()) {
				final ImplicitMutations implicitMutations = changeCollector.popImplicitMutations(
					localMutations, generateImplicitMutations
				);
				// immediately apply all local mutations
				for (final LocalMutation<?, ?> localMutation : implicitMutations.localMutations()) {
					for (final LocalMutationExecutor executor : orderedExecutors) {
						executor.applyMutation(localMutation);
					}
				}
				for (final LocalMutationExecutor executor : orderedExecutors) {
					executor.finishLocalMutationExecutionPhase();
				}

				// and for each external mutation - call external collection to apply it
				for (final EntityMutation externalEntityMutations : implicitMutations.externalMutations()) {
					final ServerEntityMutation serverEntityMutation = (ServerEntityMutation) externalEntityMutations;
					this.catalog.getCollectionForEntityOrThrowException(externalEntityMutations.getEntityType())
						.applyMutations(
							session,
							externalEntityMutations,
							serverEntityMutation.shouldRollbackOnError(),
							serverEntityMutation.shouldVerifyConsistency(),
							null,
							serverEntityMutation.getImplicitMutationsBehavior(),
							this,
							Void.class
						);
				}
			}

			// Verify consistency BEFORE dispatching any cross-entity index trigger mutations (index-trigger phase).
			// The container consistency check is independent of the index-trigger phase, and running it first means a
			// consistency violation rolls back cleanly without having written to other collections'
			// indexes — those writes are applied directly to the target collection (see
			// EntityCollection#applyIndexMutations) and are NOT covered by this collector's rollback.
			if (checkConsistency) {
				changeCollector.verifyConsistency();
			}

			// Index-trigger phase: cross-entity index trigger mutations — runs after the container
			// implicit-mutation phase and the consistency check. Container mutations must finish first
			// so that storage state is fully consistent before cross-entity triggers read it. Index
			// mutations are never written to WAL — they are regenerated deterministically on replay.
			// The dispatch is synchronous and bounded by the number of affected entities.
			final IndexImplicitMutations indexImplicit = entityIndexUpdater.popIndexImplicitMutations(localMutations);
			for (final EntityIndexMutation indexMutation : indexImplicit.indexMutations()) {
				// route each envelope to the target collection's thin dispatcher — bypasses
				// the full ServerEntityMutation pipeline (no storage, no WAL, no schema evolution)
				this.catalog.getCollectionForEntityOrThrowException(indexMutation.entityType())
					.applyIndexMutations(indexMutation, session);
			}

			// finish the record
			if (record != null) {
				record.finish();
			}

		} catch (RuntimeException ex) {
			// we need to catch all exceptions and store them in the exception field
			if (this.exception == null) {
				this.exception = ex;
			} else if (ex != this.exception) {
				this.exception.addSuppressed(ex);
			}
			// finish the record with exception
			if (record != null) {
				record.finishWithException(ex);
			}
		} finally {
			// we finalize this collector only on zero level
			if (--this.level == 0) {
				finish();
			}
		}

		if (this.exception != null) {
			throw this.exception;
		}

		if (requestedResultType.equals(EntityWithFetchCount.class)) {
			Assert.isPremiseValid(
				requestUpdatedEntity != null,
				"Requested result type is EntityWithFetchCount, but requestUpdatedEntity is null!"
			);
			//noinspection unchecked
			return Optional.of((T) (
					result == null ?
						this.persistenceService.toEntity(
							this.catalog.getVersion(),
							changeCollector.getEntityPrimaryKey(),
							requestUpdatedEntity,
							entitySchema,
							this.dataStoreReader,
							changeCollector.getEntityStorageParts()
						) :
						result
				)
			);
		} else if (EntityReferenceContract.class.isAssignableFrom(requestedResultType)) {
			//noinspection unchecked
			return Optional.of(
				(T) new EntityReferenceWithAssignedPrimaryKeys(
					entitySchema.getName(),
					changeCollector.getEntityPrimaryKey(),
					changeCollector.getAssignedPrimaryKeys()
				)
			);
		} else if (Void.class.equals(requestedResultType)) {
			return Optional.empty();
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported requested result type: " + requestedResultType
			);
		}
	}

	/**
	 * Completes the local mutation execution process by determining whether to commit or rollback changes.
	 *
	 * Finalization happens in two steps. First, {@link LocalMutationExecutor#applyChanges()} is invoked on every
	 * accumulated executor (only when no exception has been recorded yet). This is the fallible step — it runs
	 * the index finalization (per-locale language upsert / removal, full entity removal, empty-index sweep) that
	 * used to live in the index executor's {@code commit()}. Because it runs here, before the commit / rollback
	 * decision, a failure is recorded and routes to {@link #rollback()} instead of leaving a half-committed
	 * index. It is performed once, over all executors, after the whole (possibly nested) mutation recursion has
	 * unwound, so the ordering guarantee "all cross-entity index dispatch completes before any index
	 * finalization" is preserved. Second, if still no exception is present, {@link #commit()} performs the pure
	 * promote (storage flush + WAL registration); otherwise {@link #rollback()} reverts the partially applied
	 * changes.
	 */
	private void finish() {
		// fallible finalization step — a throw here routes to rollback rather than a half-commit
		if (this.exception == null) {
			try {
				for (final LocalMutationExecutor executor : this.executors) {
					executor.applyChanges();
				}
			} catch (RuntimeException ex) {
				this.exception = ex;
			}
		}
		if (this.exception == null) {
			commit();
		} else {
			rollback();
		}
	}

	/**
	 * Reverts the partially applied changes of a failed root entity mutation so a caller that swallows the failure
	 * can keep writing in the same transaction.
	 *
	 * When a savepoint is open (the atomic, transaction-bound path) it is rolled back, structurally reverting every
	 * diff layer touched by this root mutation in one shot — the index executor's changes plus any reflected-reference
	 * and index-trigger cross-collection writes that went through the same maintainer. This is the single,
	 * authoritative rollback mechanism; the legacy hand-written per-executor undo actions have been removed.
	 *
	 * When no savepoint is open — the non-transactional warm-up path, or WAL replay (which opts out via
	 * {@code atomicRollback == false}) — there is nothing to revert: warm-up writes go in place to the index
	 * delegate (no diff layer to snapshot) and replay discards the whole in-memory transaction on failure rather
	 * than recovering per-entity. In those contexts a failed entity is intentionally left partially applied and must
	 * be retried by rebuilding.
	 */
	private void rollback() {
		// atomic, transaction-bound path: revert this root mutation's diff-layer changes via the savepoint while the
		// surrounding transaction keeps running. Outside it (warm-up / WAL replay) there is no per-entity rollback.
		if (this.savepoint != null) {
			try {
				this.savepointMaintainer.rollbackSavepoint(this.savepoint);
			} catch (RuntimeException rollbackEx) {
				this.exception.addSuppressed(rollbackEx);
			} finally {
				this.savepoint = null;
				this.savepointMaintainer = null;
			}
		}
	}

	/**
	 * Finalizes the local mutations by committing them through each registered {@code LocalMutationExecutor}.
	 * This method iterates over all executors and invokes their {@code commit} method.
	 *
	 * If all executors successfully commit, the method registers the applied mutations
	 * to the transaction's write-ahead log if a transaction is active.
	 *
	 * In case any {@code RuntimeException} occurs during the commit process, the exception
	 * is caught and wrapped in a {@code TransactionException}, and the transaction is
	 * expected to be rolled back.
	 */
	private void commit() {
		// we do not address the situation where only one applicator fails on commit and the others succeed
		// this is unlikely situation and should cause entire transaction to be rolled back
		try {
			for (final LocalMutationExecutor executor : this.executors) {
				executor.commit();
			}
			// register the mutation to the write ahead log
			Transaction.getTransaction()
				.ifPresent(it -> {
					for (final EntityMutation mutation : this.entityMutations) {
						it.registerMutation(mutation);
					}
				});
			// the root mutation succeeded — accept the savepoint: its bookkeeping is dropped and all changes
			// made while it was open remain part of the transaction (no diff layer is modified)
			if (this.savepoint != null) {
				this.savepointMaintainer.commitSavepoint(this.savepoint);
				this.savepoint = null;
				this.savepointMaintainer = null;
			}
		} catch (RuntimeException ex) {
			this.exception = new TransactionException("Failed to commit local mutations!", ex);
			// the commit failed - the whole transaction will be rolled back; release the still-open savepoint so it
			// does not dangle into the transaction-level finalization (reverting its diff layers here is harmless,
			// they are discarded with the transaction anyway)
			if (this.savepoint != null) {
				try {
					this.savepointMaintainer.rollbackSavepoint(this.savepoint);
				} catch (RuntimeException rollbackEx) {
					this.exception.addSuppressed(rollbackEx);
				} finally {
					this.savepoint = null;
					this.savepointMaintainer = null;
				}
			}
		}
	}

}
