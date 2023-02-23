/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.TransactionContract;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.exception.UnexpectedRollbackException;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.transactionalMemory.TransactionalLayerConsumer;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalMemory;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.DeferredStorageOperation;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.PersistenceService;
import io.evitadb.store.spi.model.CatalogBootstrap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * {@inheritDoc TransactionContract}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@Slf4j
public final class Transaction implements TransactionContract {
	public static final String ERROR_MESSAGE_TIMEOUT = "Failed to commit transaction within timeout!";
	private static final ReentrantLock LOCK = new ReentrantLock(true);
	/**
	 * Contains unique transactional id that gets incremented with each transaction opened in the catalog. Latest
	 * committed transaction id gets printed into the {@link CatalogBootstrap} and is restored
	 * when catalog is loaded. Transaction ids are sequential and transaction with higher id is guaranteed to be
	 * committed later than the transaction with lower id.
	 *
	 * TOBEDONE JNO - this should be changed - there should be another id that is connected with the commit phase and
	 * should be assigned when transaction is accepted and ordered for the commit
	 */
	@Getter private final long id;
	/**
	 * The {@link EvitaSession#getId()} this transaction is bound to.
	 */
	private final UUID sessionId;
	/**
	 * Atomic reference, that will obtain pointer to a new version of catalog once it's created.
	 */
	private final AtomicReference<CatalogContract> updatedCatalog = new AtomicReference<>();
	/**
	 * Consumer that will be called back when transaction is committed and new catalog reference is created.
	 * The callback obtains reference to the new immutable version of the catalog.
	 */
	private final Consumer<CatalogContract> updatedCatalogCallback;
	/**
	 * List of {@link StoragePart} items that got modified in transaction and needs to be persisted.
	 */
	private final CommitUpdateInstructionSet updateInstructions = new CommitUpdateInstructionSet();
	/**
	 * Rollback only flag.
	 */
	@Getter private boolean rollbackOnly;
	/**
	 * Flag that marks this instance closed and unusable. Once closed it can never be opened again.
	 */
	@Getter private boolean closed;

	/**
	 * Creates new transaction with snapshot isolation.
	 *
	 * @param currentCatalog         reference to the current version of the catalog the transaction begins with
	 * @param updatedCatalogCallback consumer that will be called back when transaction is committed and new catalog
	 *                               reference is created. The callback obtains reference to the new immutable version
	 *                               of the catalog.
	 */
	public Transaction(@Nonnull UUID sessionId, @Nonnull CatalogContract currentCatalog, @Nonnull BiConsumer<CatalogContract, CatalogContract> updatedCatalogCallback) {
		if (currentCatalog instanceof Catalog theCatalog) {
			this.id = theCatalog.getNextTransactionId();
			this.sessionId = sessionId;
			this.updatedCatalogCallback = updatedCatalog -> updatedCatalogCallback.accept(currentCatalog, updatedCatalog);
			TransactionalMemory.open(sessionId);
			TransactionalMemory.addTransactionCommitHandler(
				sessionId,
				transactionalLayer -> {
					final List<? extends TransactionalLayerConsumer> layerConsumers = transactionalLayer.getLayerConsumers();
					if (!layerConsumers.isEmpty()) {
						try {
							if (LOCK.tryLock(5, TimeUnit.SECONDS)) {
								updatedCatalog.set(
									onCommit(theCatalog, transactionalLayer)
								);
							} else {
								log.error(ERROR_MESSAGE_TIMEOUT);
								throw new RollbackException(ERROR_MESSAGE_TIMEOUT);
							}
						} catch (InterruptedException e) {
							log.error(ERROR_MESSAGE_TIMEOUT);
							Thread.currentThread().interrupt();
							throw new RollbackException(ERROR_MESSAGE_TIMEOUT);
						}
					}
				});
		} else {
			throw new CatalogCorruptedException((CorruptedCatalog) currentCatalog);
		}
	}

	/**
	 * Method is executed when commit is executed.
	 */
	@Nonnull
	public Catalog onCommit(@Nonnull Catalog currentCatalog, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		try {
			// init new catalog with the same collections as previous one
			final Catalog newCatalog = transactionalLayer.getStateCopyWithCommittedChanges(currentCatalog, this);
			// now let's flush the catalog on the disk
			newCatalog.flushTransaction(id, updateInstructions);
			// and return reference to a new catalog
			return newCatalog;
		} catch (Throwable throwable) {
			throw new UnexpectedRollbackException("Unexpected exception while committing!", throwable);
		} finally {
			Transaction.LOCK.unlock();
		}
	}

	@Override
	public void setRollbackOnly() {
		this.rollbackOnly = true;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}

		closed = true;

		if (isRollbackOnly()) {
			TransactionalMemory.rollback(sessionId);
		} else {
			TransactionalMemory.commit(sessionId);
			final CatalogContract newCatalog = updatedCatalog.get();
			Assert.isPremiseValid(
				newCatalog != null,
				"New version of catalog was not created as expected!"
			);
			updatedCatalogCallback.accept(newCatalog);
		}
	}

	/**
	 * Registers deferred I/O related catalog operation that should be performed when the transaction is committed.
	 */
	public void register(@Nonnull DeferredStorageOperation<?> deferredStorageOperation) {
		Assert.isPremiseValid(
			CatalogPersistenceService.class.equals(deferredStorageOperation.getRequiredPersistenceServiceType()) ||
			PersistenceService.class.equals(deferredStorageOperation.getRequiredPersistenceServiceType()),
			() -> new EvitaInternalError("It's not allowed to register deferred operation for catalog that targets entity collection!")
		);
		this.updateInstructions.register(deferredStorageOperation);
	}

	/**
	 * Registers deferred I/O related entity collection operation that should be performed when the transaction is committed.
	 */
	public void register(@Nonnull String entityType, @Nonnull DeferredStorageOperation<?> deferredStorageOperation) {
		Assert.isPremiseValid(
			EntityCollectionPersistenceService.class.equals(deferredStorageOperation.getRequiredPersistenceServiceType()) ||
				PersistenceService.class.equals(deferredStorageOperation.getRequiredPersistenceServiceType()),
			() -> new EvitaInternalError("It's not allowed to register deferred operation for entity collection that targets catalog!")
		);
		this.updateInstructions.register(entityType, deferredStorageOperation);
	}

	/**
	 * DTO class wrapping collections of pending updates to the persistent storage collected from {@link Catalog} and
	 * its {@link EntityCollection collections}. Updates represent the modified indexes that are stored on transaction
	 * commit only (on the contrary to entity bodies which are persisted immediately).
	 */
	public static class CommitUpdateInstructionSet {
		/**
		 * Collection of updates from {@link Catalog} itself.
		 */
		@Getter @Nonnull private final List<DeferredStorageOperation<?>> catalogUpdates = new LinkedList<>();
		/**
		 * Collection of updates from all {@link EntityCollection entity collections} of the catalog.
		 */
		@Nonnull private final Map<String, List<DeferredStorageOperation<?>>> entityCollectionUpdates = new HashMap<>(16);

		/**
		 * Registers deferred I/O related catalog operation that should be performed when the transaction is committed.
		 */
		public void register(@Nonnull DeferredStorageOperation<?> deferredStorageOperation) {
			catalogUpdates.add(deferredStorageOperation);
		}

		/**
		 * Registers deferred I/O related entity collection operation that should be performed when the transaction is committed.
		 */
		public void register(@Nonnull String entityType, @Nonnull DeferredStorageOperation<?> deferredStorageOperation) {
			this.entityCollectionUpdates.computeIfAbsent(entityType, et -> new LinkedList<>())
				.add(deferredStorageOperation);
		}

		/**
		 * Returns IO deferred operations registered for particular entity collection that needs to be executed.
		 */
		@Nonnull
		public List<DeferredStorageOperation<?>> getEntityCollectionUpdates(@Nonnull String entityType) {
			return ofNullable(entityCollectionUpdates.get(entityType))
				.orElse(Collections.emptyList());
		}

	}

}
