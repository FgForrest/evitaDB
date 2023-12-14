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
import io.evitadb.index.transactionalMemory.TransactionalLayerCreator;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalMemory;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.DeferredStorageOperation;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.PersistenceService;
import io.evitadb.utils.Assert;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
	 * TOBEDONE JNO - this should be migrated to ScopedValue in Java 21
	 */
	private static final ThreadLocal<Transaction> CURRENT_TRANSACTION = new ThreadLocal<>();

	/**
	 * Contains unique transactional id that uniquely represents the transaction.
	 * We don't actively check the uniqueness of the transaction id and rely on the fact that UUID is unique enough.
	 */
	@Getter private final UUID transactionId = UUIDUtil.randomUUID();
	/**
	 * Contains reference to the transactional memory that keeps the difference layer for this transaction.
	 */
	@Getter private final TransactionalMemory transactionalMemory;
	/**
	 * Reference to catalog that is valid for this transaction. The transaction keeps SNAPSHOT isolation contract,
	 * so that this reference cannot be changed.
	 */
	private final CatalogContract originalCatalog;
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
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static void executeInTransactionIfProvided(@Nullable Transaction transaction, @Nonnull Runnable lambda) {
		if (transaction == null) {
			lambda.run();
		} else {
			boolean bound = false;
			try {
				bound = transaction.bindTransactionToThread();
				lambda.run();
			} catch (Throwable ex) {
				transaction.setRollbackOnly();
				throw ex;
			} finally {
				if (bound) {
					transaction.unbindTransactionFromThread();
				}
			}
		}
	}

	/**
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static <T> T executeInTransactionIfProvided(@Nullable Transaction transaction, @Nonnull Supplier<T> lambda) {
		if (transaction == null) {
			return lambda.get();
		} else {
			boolean bound = false;
			try {
				bound = transaction.bindTransactionToThread();
				return lambda.get();
			} catch (Throwable ex) {
				transaction.setRollbackOnly();
				throw ex;
			} finally {
				if (bound) {
					transaction.unbindTransactionFromThread();
				}
			}
		}
	}

	/**
	 * Returns true if transaction is present and usable.
	 */
	public static boolean isTransactionAvailable() {
		return CURRENT_TRANSACTION.get() != null;
	}

	/**
	 * Returns transactional layer for states, that is isolated for this thread.
	 */
	@Nullable
	public static <T> T getTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			return transaction.transactionalMemory.getTransactionalMemoryLayerIfExists(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	@Nullable
	public static TransactionalLayerMaintainer getTransactionalMemoryLayer() {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			return transaction.transactionalMemory.getTransactionalMemoryLayer();
		} else {
			return null;
		}
	}

	/**
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	@Nullable
	public static <T> T getTransactionalMemoryLayer(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			return transaction.transactionalMemory.getTransactionalMemoryLayer(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Removes transactional layer for passed layer creator.
	 */
	@Nullable
	public static <T> T removeTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			// we may safely do this because transactionalLayer is stored in ThreadLocal and
			// thus won't be accessed by multiple threads at once
			return transaction.transactionalMemory.removeTransactionalMemoryLayerIfExists(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * This method will suppress creation of new transactional layer for passed `object` when it is asked for inside
	 * the `objectConsumer` lambda. This makes the object effectively transactional-less for the scope of the lambda
	 * function.
	 */
	public static <T, U> U suppressTransactionalMemoryLayerForWithResult(@Nonnull T object, @Nonnull Function<T, U> objectConsumer) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			// we may safely do this because transactionalLayer is stored in ThreadLocal and
			// thus won't be accessed by multiple threads at once
			return transaction.transactionalMemory.suppressTransactionalMemoryLayerForWithResult(object, objectConsumer);
		} else {
			return null;
		}
	}

	/**
	 * This method will suppress creation of new transactional layer for passed `object` when it is asked for inside
	 * the `objectConsumer` lambda. This makes the object effectively transactional-less for the scope of the lambda
	 * function.
	 */
	public static <T> void suppressTransactionalMemoryLayerFor(@Nonnull T object, @Nonnull Consumer<T> objectConsumer) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction txMemory = CURRENT_TRANSACTION.get();
		if (txMemory != null) {
			// we may safely do this because transactionalLayer is stored in ThreadLocal and
			// thus won't be accessed by multiple threads at once
			txMemory.transactionalMemory.suppressTransactionalMemoryLayerFor(object, objectConsumer);
		}
	}

	/**
	 * Returns transactional layer for states, that is isolated for this thread.
	 */
	@Nonnull
	public static Optional<Transaction> getTransaction() {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		return ofNullable(CURRENT_TRANSACTION.get());
	}

	/**
	 * Do not use this method outside the tests!
	 */
	@Nonnull
	public static Transaction createMockTransactionForTests() {
		return new Transaction();
	}

	/**
	 * This constructor should be used only in tests.
	 */
	private Transaction() {
		this.originalCatalog = null;
		this.transactionalMemory = new TransactionalMemory(this.transactionId);
		this.updatedCatalogCallback = updatedCatalog -> {};
	}

	/**
	 * Creates new transaction with snapshot isolation.
	 *
	 * @param currentCatalog         reference to the current version of the catalog the transaction begins with
	 * @param updatedCatalogCallback consumer that will be called back when transaction is committed and new catalog
	 *                               reference is created. The callback obtains reference to the new immutable version
	 *                               of the catalog.
	 */
	public Transaction(
		@Nonnull CatalogContract currentCatalog,
		@Nonnull BiConsumer<CatalogContract, CatalogContract> updatedCatalogCallback
	) {
		if (currentCatalog instanceof Catalog theCatalog) {
			this.originalCatalog = currentCatalog;
			this.transactionalMemory = new TransactionalMemory(this.transactionId);
			this.updatedCatalogCallback = updatedCatalog -> updatedCatalogCallback.accept(currentCatalog, updatedCatalog);
			transactionalMemory.addTransactionCommitHandler(
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
			final Catalog newCatalog = executeInTransactionIfProvided(
				this,
				() -> transactionalLayer.getStateCopyWithCommittedChanges(currentCatalog, this)
			);
			// now let's flush the catalog on the disk
			newCatalog.flushTransaction(updateInstructions);
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

		final CatalogContract newCatalog;
		try {
			if (isRollbackOnly()) {
				newCatalog = originalCatalog;
			} else {
				transactionalMemory.commit();
				newCatalog = updatedCatalog.get();
				/* TODO JNO - toto bude vyžadovat přemyšlení */
				/*Assert.isPremiseValid(
					transactionId == 0L || newCatalog != null,
					"New version of catalog was not created as expected!"
				);*/
			}
		} finally {
			// now we remove the transactional memory - no object will see it transactional memory from now on
			CURRENT_TRANSACTION.remove();
			closed = true;
		}

		updatedCatalogCallback.accept(newCatalog);
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
	 * Registers transaction commit handler for current transaction. Implementation of {@link TransactionalLayerConsumer}
	 * may withdraw multiple {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)} 4
	 * and use their results to swap certain internal state atomically.
	 *
	 * All withdrawn objects will be considered as committed.
	 */
	public void addTransactionCommitHandler(@Nonnull TransactionalLayerConsumer consumer) {
		this.transactionalMemory.addTransactionCommitHandler(consumer);
	}

	/**
	 * Binds this transaction to current thread.
	 */
	public boolean bindTransactionToThread() {
		final Transaction currentValue = CURRENT_TRANSACTION.get();
		Assert.isPremiseValid(
			currentValue == null || currentValue == this,
			() -> "You cannot mix calling different sessions within one thread (sessions `" + currentValue.transactionId + "` and `" + this.transactionId + "`)!");
		if (currentValue == null) {
			CURRENT_TRANSACTION.set(this);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Unbinds transaction from current thread.
	 */
	public void unbindTransactionFromThread() {
		CURRENT_TRANSACTION.remove();
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
