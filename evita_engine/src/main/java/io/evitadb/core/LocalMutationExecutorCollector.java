/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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


import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutations;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutor;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.transaction.stage.mutation.ServerEntityMutation;
import io.evitadb.function.IntBiFunction;
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.store.entity.serializer.EntitySchemaContext;
import io.evitadb.store.spi.EntityCollectionPersistenceService.EntityWithFetchCount;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.require;

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
	 * The catalog instance used to fetch collections for entities.
	 */
	private final Catalog catalog;
	/**
	 * The list of all involved local mutation executors.
	 */
	private final List<LocalMutationExecutor> executors = new ArrayList<>(16);
	/**
	 * The list of all entity mutations applied within this collector.
	 */
	private final List<EntityMutation> entityMutations = new ArrayList<>(16);
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
	@Nullable
	private static EntityWithFetchCount getFullEntityById(
		int primaryKey,
		@Nonnull EntitySchema entitySchema,
		@Nonnull IntBiFunction<EvitaRequest, EntityWithFetchCount> entityFetcher
	) {
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entitySchema.getName()),
				require(entityFetchAll())
			),
			OffsetDateTime.now(),
			Entity.class,
			null,
			EvitaRequest.CONVERSION_NOT_SUPPORTED
		);
		return entityFetcher.apply(primaryKey, evitaRequest);
	}

	/**
	 * Executes a given entity mutation within the context of the specified entity schema,
	 * optionally checking consistency and generating implicit mutations.
	 *
	 * @param entitySchema The schema of the entity to which the mutation applies.
	 * @param entityMutation The mutation to be applied to the entity.
	 * @param checkConsistency Indicates whether consistency checks should be performed.
	 * @param generateImplicitMutations Flags indicating which implicit mutations should be generated.
	 * @param entityFetcher Function used to fetch the full entity by its primary key.
	 * @param changeCollector Executor to collect and apply local mutations.
	 * @param entityIndexUpdater Executor to update the entity index with the mutations.
	 * @param returnUpdatedEntity Indicates whether to return the updated entity after mutation.
	 *
	 * @return The updated entity with fetch count if {@code returnUpdatedEntity} is true and
	 *         the entity was updated, otherwise null.
	 */
	@Nullable
	public EntityWithFetchCount execute(
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityMutation entityMutation,
		boolean checkConsistency,
		@Nonnull EnumSet<ImplicitMutationBehavior> generateImplicitMutations,
		@Nonnull IntBiFunction<EvitaRequest, EntityWithFetchCount> entityFetcher,
		@Nonnull ContainerizedLocalMutationExecutor changeCollector,
		@Nonnull EntityIndexLocalMutationExecutor entityIndexUpdater,
		boolean returnUpdatedEntity
	) {
		// first register all mutation applicators and mutations to the internal state
		this.executors.add(changeCollector);
		this.executors.add(entityIndexUpdater);
		this.entityMutations.add(entityMutation);

		// apply mutations using applicators
		return EntitySchemaContext.executeWithSchemaContext(
			entitySchema,
			() -> {
				EntityWithFetchCount result = null;
				try {
					this.level++;

					final List<? extends LocalMutation<?, ?>> localMutations;
					if (entityMutation instanceof EntityRemoveMutation erm) {
						result = getFullEntityById(entityMutation.getEntityPrimaryKey(), entitySchema, entityFetcher);
						localMutations = erm.computeLocalMutationsForEntityRemoval(
							Objects.requireNonNull(result.entity())
						);
					} else {
						localMutations = entityMutation.getLocalMutations();
					}

					for (LocalMutation<?, ?> localMutation : localMutations) {
						changeCollector.applyMutation(localMutation);
						entityIndexUpdater.applyMutation(localMutation);
					}

					if (!generateImplicitMutations.isEmpty()) {
						final ImplicitMutations implicitMutations = changeCollector.popImplicitMutations(
							localMutations, generateImplicitMutations
						);
						// immediately apply all local mutations
						for (LocalMutation<?, ?> localMutation : implicitMutations.localMutations()) {
							changeCollector.applyMutation(localMutation);
							entityIndexUpdater.applyMutation(localMutation);
						}
						// and for each external mutation - call external collection to apply it
						for (EntityMutation externaEntityMutations : implicitMutations.externalMutations()) {
							final ServerEntityMutation serverEntityMutation = (ServerEntityMutation) externaEntityMutations;
							catalog.getCollectionForEntityOrThrowException(externaEntityMutations.getEntityType())
								.applyMutations(
									externaEntityMutations,
									serverEntityMutation.shouldApplyUndoOnError(),
									serverEntityMutation.shouldVerifyConsistency(),
									false,
									serverEntityMutation.getImplicitMutationsBehavior(),
									this
								);
						}
					}
					if (checkConsistency) {
						changeCollector.verifyConsistency();
					}

				} catch (RuntimeException ex) {
					// we need to catch all exceptions and store them in the exception field
					if (exception == null) {
						exception = ex;
					} else {
						exception.addSuppressed(ex);
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

				if (returnUpdatedEntity) {
					return result == null ? changeCollector.getEntityWithFetchCount() : result;
				} else {
					return null;
				}
			}
		);
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
		for (LocalMutationExecutor executor : executors) {
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
