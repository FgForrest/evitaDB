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

package io.evitadb.core.transaction;

import io.evitadb.api.TransactionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Represents a finalizer for a transaction that handles commit, rollback, and mutation registration.
 * This implementation migrates SNAPSHOT isolated changes from {@link IsolatedWalPersistenceService}
 * into a shared catalog write ahead log. The conflict resolution and next catalog version taken from
 * the shared sequence happens during the migration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public class TransactionWalFinalizer implements TransactionHandler {
	/**
	 * The transactionId uniquely identifies a transaction.
	 *
	 * @see TransactionContract#getTransactionId()
	 */
	@Nonnull private final UUID transactionId;
	/**
	 * Contains commit behaviour for this transaction.
	 *
	 * @see CommitBehavior
	 */
	@Getter private final CommitBehavior commitBehaviour;
	/**
	 * Contains reference to the {@link Catalog} which represents the SNAPSHOT version this transaction
	 * builds on.
	 */
	@Nonnull private final Catalog catalog;
	/**
	 * The closeables list maintains a collection of objects that implement the {@link Closeable} interface
	 * and are associated with this transaction. These objects are be closed in a deterministic order when
	 * transaction is finished, hence ensuring that resources are properly released.
	 */
	@Nonnull private final LinkedList<Closeable> closeables = new LinkedList<>();
	/**
	 * Represents a factory for creating instances of {@link IsolatedWalPersistenceService} based on a given UUID
	 * in lazy manner. If no mutation is recorded in the transaction, the factory is not called and no overhead
	 * is incurred.
	 *
	 * @since Date of creation
	 */
	@Nonnull private final Function<UUID, IsolatedWalPersistenceService> walPersistenceServiceFactory;
	/**
	 * A CompletableFuture representing that needs to be "completed" when the transaction reaches the stage
	 * requested by the {@link CommitBehavior} of this transaction.
	 *
	 * @see CompletableFuture
	 */
	@Nonnull private final CompletableFuture<Long> transactionFinalizationFuture;
	/**
	 * Represents a reference to the IsolatedWalPersistenceService, which is responsible for storing
	 * and retrieving data using Write-Ahead Logging (WAL) in isolation from other transaction.
	 * The service is instantiated on demand when the first mutation is registered.
	 *
	 * @see IsolatedWalPersistenceService
	 */
	@Nullable private IsolatedWalPersistenceService walPersistenceService;

	public TransactionWalFinalizer(
		@Nonnull Catalog catalog,
		@Nonnull UUID transactionId,
		@Nonnull CommitBehavior commitBehaviour,
		@Nonnull Function<UUID, IsolatedWalPersistenceService> walPersistenceServiceFactory,
		@Nonnull CompletableFuture<Long> transactionFinalizationFuture
	) {
		this.catalog = catalog;
		this.transactionId = transactionId;
		this.commitBehaviour = commitBehaviour;
		this.walPersistenceServiceFactory = walPersistenceServiceFactory;
		this.transactionFinalizationFuture = transactionFinalizationFuture;
	}

	/**
	 * Registers a Closeable object to be closed when the transaction is finished.
	 *
	 * @param objectToClose the Closeable object to register
	 */
	public void registerCloseable(@Nonnull Closeable objectToClose) {
		closeables.add(objectToClose);
	}

	@Override
	public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		try {
			// here we close all registered closeables - i.e. transactional OffsetIndexes along with their data
			// (i.e. OffHeapManager regions and temporary files) - we will not need them anymore, they were used only
			// for the client's transaction that is being closed right now
			closeRegisteredCloseables();
			// now we need to handle the isolated WAL - if there are any mutations, we need to issue instruction for
			// copying them to the shared WAL
			if (walPersistenceService != null) {
				// this invokes the asynchronous action of copying the isolated WAL to the shared one
				this.catalog.commitWal(
					transactionId,
					commitBehaviour,
					walPersistenceService,
					transactionFinalizationFuture
				);
			} else {
				// if there are no mutations, we can complete the future immediately
				transactionFinalizationFuture.complete(catalog.getVersion());
			}
		} finally {
			// discard the WAL persistence service
			if (this.walPersistenceService != null) {
				// we must not call the `this.walPersistenceService.close()` method here, because the we need to keep
				// the references to OffHeapManager / file alive so that the transaction pipeline can take it and copy
				// the data to the shared WAL
				this.walPersistenceService = null;
			}
		}
	}

	@Override
	public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
		try {
			// here we close all registered closeables - i.e. transactional OffsetIndexes along with their data
			// (i.e. OffHeapManager regions and temporary files) - we will not need them anymore, they were used only
			// for the client's transaction that is being closed right now
			closeRegisteredCloseables();
		} finally {
			// here we discard the WAL persistence service and and delete the isolated WAL file (either in memory or
			// on disk) - the contents of the WAL will not be used anymore
			if (this.walPersistenceService != null) {
				this.walPersistenceService.close();
				this.walPersistenceService = null;
			}
			// we must complete the future with the exception or the original catalog version (if the rollback was
			// not caused by an exception)
			if (cause != null) {
				this.transactionFinalizationFuture.completeExceptionally(
					new RollbackException(
						"Transaction changes have been rolled back due to previous exception.",
						cause
					)
				);
			} else {
				this.transactionFinalizationFuture.complete(catalog.getVersion());
			}
		}
	}

	@Override
	public void registerMutation(@Nonnull Mutation mutation) {
		if (this.walPersistenceService == null) {
			this.walPersistenceService = walPersistenceServiceFactory.apply(transactionId);
		}
		this.walPersistenceService.write(catalog.getVersion(), mutation);
	}

	/**
	 * Closes all registered Closeable objects.
	 */
	private void closeRegisteredCloseables() {
		for (Closeable closeable : closeables) {
			try {
				closeable.close();
			} catch (Exception ex) {
				log.error("Error closing object {}", closeable, ex);
			}
		}
	}
}
