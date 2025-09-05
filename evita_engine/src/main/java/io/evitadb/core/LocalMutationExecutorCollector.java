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

package io.evitadb.core;


import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutations;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutor;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.traffic.TrafficRecordingEngine.MutationApplicationRecord;
import io.evitadb.core.transaction.stage.mutation.ServerEntityMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService.EntityWithFetchCount;
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
	private final EntityCollectionPersistenceService persistenceService;
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
	 * @param entitySchema              The schema of the entity to which the mutation applies.
	 * @param entityMutation            The mutation to be applied to the entity.
	 * @param checkConsistency          Indicates whether consistency checks should be performed.
	 * @param generateImplicitMutations Flags indicating which implicit mutations should be generated.
	 * @param changeCollector           Executor to collect and apply local mutations.
	 * @param entityIndexUpdater        Executor to update the entity index with the mutations.
	 * @param requestUpdatedEntity      Indicates whether to return the updated entity after mutation.
	 * @return The updated entity with fetch count if {@code returnUpdatedEntity} is true and
	 * the entity was updated, or entity reference
	 */
	@Nonnull
	public <T> Optional<T> execute(
		@Nullable EvitaSessionContract session,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityMutation entityMutation,
		boolean checkConsistency,
		@Nonnull EnumSet<ImplicitMutationBehavior> generateImplicitMutations,
		@Nonnull ContainerizedLocalMutationExecutor changeCollector,
		@Nonnull EntityIndexLocalMutationExecutor entityIndexUpdater,
		@Nullable EvitaRequest requestUpdatedEntity,
		@Nonnull Class<T> requestedResultType
	) {
		// first register all mutation applicators and mutations to the internal state
		this.executors.add(entityIndexUpdater);
		this.executors.add(changeCollector);
		// add the mutation to the list of mutations, but only for root level mutations
		// mutations on lower levels are implicit mutations which should not be written to WAL (considered), because
		// are automatically generated when top level mutation is applied (replayed)
		final MutationApplicationRecord record;
		if (this.level == 0) {
			this.entityMutations.add(entityMutation);
			// root level changes are applied immediately
			changeCollector.setTrapChanges(false);
			// record mutation to the traffic recorder
			record = session == null ?
				null :
				this.catalog.getTrafficRecordingEngine().recordMutation(
					session.getId(),
					this.created,
					entityMutation
			);
		} else {
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
			if (entityMutation instanceof EntityRemoveMutation) {
				result = getFullEntityContents(changeCollector);
				localMutations = computeLocalMutationsForEntityRemoval(result.entity());
			} else if (entityMutation instanceof EntityUpsertMutation eum) {
				entityIndexUpdater.prepare(eum.getEntityPrimaryKey(), eum.expects());
				changeCollector.prepare(eum.getEntityPrimaryKey(), eum.expects());
				localMutations = entityMutation.getLocalMutations();
			} else {
				localMutations = entityMutation.getLocalMutations();
			}

			for (LocalMutation<?, ?> localMutation : localMutations) {
				entityIndexUpdater.applyMutation(localMutation);
				changeCollector.applyMutation(localMutation);
			}

			if (!generateImplicitMutations.isEmpty()) {
				final ImplicitMutations implicitMutations = changeCollector.popImplicitMutations(
					localMutations, generateImplicitMutations
				);
				// immediately apply all local mutations
				for (LocalMutation<?, ?> localMutation : implicitMutations.localMutations()) {
					entityIndexUpdater.applyMutation(localMutation);
					changeCollector.applyMutation(localMutation);
				}
				// and for each external mutation - call external collection to apply it
				for (EntityMutation externalEntityMutations : implicitMutations.externalMutations()) {
					final ServerEntityMutation serverEntityMutation = (ServerEntityMutation) externalEntityMutations;
					this.catalog.getCollectionForEntityOrThrowException(externalEntityMutations.getEntityType())
						.applyMutations(
							session,
							externalEntityMutations,
							serverEntityMutation.shouldApplyUndoOnError(),
							serverEntityMutation.shouldVerifyConsistency(),
							null,
							serverEntityMutation.getImplicitMutationsBehavior(),
							this,
							Void.class
						);
				}
			}
			if (checkConsistency) {
				changeCollector.verifyConsistency();
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
		} else if (requestedResultType.equals(EntityReference.class)) {
			//noinspection unchecked
			return Optional.of(
				(T) new EntityReference(
					entitySchema.getName(), changeCollector.getEntityPrimaryKey()
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
	 * If the {@code exception} field is null, it calls the {@code commit} method to finalize changes.
	 * Otherwise, it calls the {@code rollback} method to revert changes.
	 */
	private void finish() {
		if (this.exception == null) {
			commit();
		} else {
			rollback();
		}
	}

	/**
	 * Rolls back all changes in memory if any operation has failed. This method ensures each operation behaves atomically
	 * by either applying all local mutations or none of them. It iterates over all registered executor instances and
	 * calls their rollback methods to clean up partially applied changes in isolated indexes.
	 * If a rollback operation itself throws a {@code RuntimeException}, the exception is suppressed and added
	 * to the primary exception list.
	 */
	private void rollback() {
		// rollback all changes in memory if anything failed
		// the exception might be caught by a caller and he could continue with the transaction ... in this case
		// we need to clean partially applied changes in his isolated indexes so that he doesn't see them
		// each operation must behave atomically - either all local mutations are applied or none of them - it's
		// something like a transaction within a transaction
		for (LocalMutationExecutor executor : this.executors) {
			try {
				executor.rollback();
			} catch (RuntimeException rollbackEx) {
				this.exception.addSuppressed(rollbackEx);
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
			for (LocalMutationExecutor executor : this.executors) {
				executor.commit();
			}
			// register the mutation to the write ahead log
			Transaction.getTransaction()
				.ifPresent(it -> {
					for (EntityMutation mutation : this.entityMutations) {
						it.registerMutation(mutation);
					}
				});
		} catch (RuntimeException ex) {
			this.exception = new TransactionException("Failed to commit local mutations!", ex);
		}
	}
}
