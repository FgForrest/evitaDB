/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.TransactionContract;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.metric.event.transaction.TransactionFinishedEvent;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.TransactionWalFinalizer;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainerFinalizer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalMemory;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * {@inheritDoc TransactionContract}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@Slf4j
public final class Transaction implements TransactionContract {
	/**
	 * TOBEDONE JNO - this should be migrated to ScopedValue in Java 21
	 */
	private static final ThreadLocal<Transaction> CURRENT_TRANSACTION = new ThreadLocal<>();

	/**
	 * Contains unique transactional id that uniquely represents the transaction.
	 * We don't actively check the uniqueness of the transaction id and rely on the fact that UUID is unique enough.
	 */
	@Getter private final UUID transactionId;
	/**
	 * Contains reference to the transactional memory that keeps the difference layer for this transaction.
	 */
	@Getter private final TransactionalMemory transactionalMemory;
	/**
	 * This class provides methods for handling transaction = registering mutations and interpreting commit / rollback
	 * actions.
	 */
	private final TransactionHandler transactionHandler;
	/**
	 * Flag that marks this transaction as replay transaction - i.e. when the transaction is being incorporated in
	 * the trunk and is then replayed for the second time (now finally in correct sequence order).
	 */
	@Getter private final boolean replay;
	/**
	 * Rollback only flag.
	 */
	@Getter private boolean rollbackOnly;
	/**
	 * Exception that caused the rollback.
	 */
	@Getter private Throwable rollbackCause;
	/**
	 * Date and time when this this instance was created.
	 */
	@Getter private final OffsetDateTime created;
	/**
	 * Date and time when this this instance was closed and marked unusable. Once closed it can never be opened again.
	 */
	@Getter private OffsetDateTime closed;
	/**
	 * Event that is fired when a transaction is finished (either committed or rolled back).
	 */
	@Setter @Getter private TransactionFinishedEvent finalizationEvent;

	/**
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static void executeInTransactionIfProvided(@Nullable Transaction transaction, @Nonnull Runnable lambda) {
		executeInTransactionIfProvided(transaction, lambda, true);
	}

	/**
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static void executeInTransactionIfProvided(@Nullable Transaction transaction, @Nonnull Runnable lambda, boolean rollbackOnException) {
		if (transaction == null) {
			lambda.run();
		} else {
			boolean bound = false;
			try {
				bound = transaction.bindTransactionToThread();
				lambda.run();
			} catch (Throwable ex) {
				if (rollbackOnException) {
					transaction.setRollbackOnlyWithException(ex);
				}
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
		return executeInTransactionIfProvided(transaction, lambda, true);
	}

	/**
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static <T> T executeInTransactionIfProvided(@Nullable Transaction transaction, @Nonnull Supplier<T> lambda, boolean rollbackOnException) {
		if (transaction == null) {
			return lambda.get();
		} else {
			boolean bound = false;
			try {
				bound = transaction.bindTransactionToThread();
				return lambda.get();
			} catch (TransactionException ex) {
				// always rollback transaction - this is unexpected critical error we cannot recover from
				transaction.setRollbackOnlyWithException(ex);
				throw ex;
			} catch (Throwable ex) {
				if (rollbackOnException) {
					transaction.setRollbackOnlyWithException(ex);
				}
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
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	@Nullable
	public static TransactionalLayerMaintainer getTransactionalLayerMaintainer() {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			return transaction.transactionalMemory.getTransactionalLayerMaintainer();
		} else {
			return null;
		}
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
	public static <T> T getOrCreateTransactionalMemoryLayer(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null) {
			return transaction.transactionalMemory.getOrCreateTransactionalMemoryLayer(layerCreator);
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
	@Nullable
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
	 * Creates a transactional persistence service based on the given storage part persistence service.
	 * If a transaction is active, it creates a transactional service using the current transaction identifier.
	 * Registers the transactional service with the transaction's finalizer for cleanup.
	 * Returns the transactional service if a transaction is active, otherwise returns the original storage part
	 * persistence service which writes all the changes in the "trunk".
	 *
	 * @param storagePartPersistenceService The storage part persistence service.
	 * @return The transactional persistence service if a transaction is active, otherwise the original storage part
	 * persistence service.
	 * @throws EvitaInternalError if the finalizer type is unexpected.
	 */
	public static StoragePartPersistenceService createTransactionalPersistenceService(
		@Nonnull StoragePartPersistenceService storagePartPersistenceService
	) {
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null && !transaction.isReplay()) {
			final StoragePartPersistenceService transactionalService = storagePartPersistenceService.createTransactionalService(
				transaction.getTransactionId()
			);
			registerCloseable(transaction, transactionalService);
			return transactionalService;
		} else {
			return storagePartPersistenceService;
		}
	}

	/**
	 * Registers a {@link Closeable} instance with the finalizer of the given {@link Transaction}.
	 * If the finalizer of the transaction is of type {@link TransactionWalFinalizer}, the closeable
	 * instance will be registered for cleanup during transaction finalization.
	 *
	 * @param closeableInstance The closeable instance that needs to be managed and finalized with the transaction.
	 */
	public static void registerCloseable(@Nonnull Closeable closeableInstance) {
		final Transaction transaction = CURRENT_TRANSACTION.get();
		if (transaction != null && !transaction.isReplay()) {
			registerCloseable(transaction, closeableInstance);
		}
	}

	/**
	 * Registers a {@link Closeable} instance with the finalizer of the given {@link Transaction}.
	 * If the finalizer of the transaction is of type {@link TransactionWalFinalizer}, the closeable
	 * instance will be registered for cleanup during transaction finalization.
	 *
	 * @param transaction       The transaction instance in which the closeable instance is to be registered.
	 * @param closeableInstance The closeable instance that needs to be managed and finalized with the transaction.
	 */
	private static void registerCloseable(@Nonnull Transaction transaction, @Nonnull Closeable closeableInstance) {
		final TransactionalLayerMaintainerFinalizer finalizer = transaction.getTransactionalMemory().getTransactionalLayerMaintainerFinalizer();
		if (finalizer instanceof TransactionWalFinalizer transactionWalFinalizer) {
			transactionWalFinalizer.registerCloseable(closeableInstance);
		}
	}

	/**
	 * Creates new transaction.
	 */
	public <S, X, T extends TransactionalLayerProducer<X, S>> Transaction(@Nonnull T txRoot) {
		this.transactionId = UUID.randomUUID();
		this.transactionHandler = null;
		this.transactionalMemory = new TransactionalMemory(
			new IsolatedTransactionalLayerMaintainerFinalizer<>(txRoot)
		);
		this.created = OffsetDateTime.now();
		this.replay = false;
	}

	/**
	 * Creates new transaction.
	 *
	 * @param transactionId      unique id of the transaction
	 * @param transactionHandler handler that takes care about mutation persistence, commit and rollback behaviour
	 */
	public Transaction(
		@Nonnull UUID transactionId,
		@Nonnull TransactionHandler transactionHandler,
		boolean replay
	) {
		this.transactionId = transactionId;
		this.transactionHandler = transactionHandler;
		this.transactionalMemory = new TransactionalMemory(transactionHandler);
		this.replay = replay;
		this.created = OffsetDateTime.now();
	}

	/**
	 * Creates new transaction.
	 *
	 * @param transactionId      unique id of the transaction
	 * @param transactionHandler handler that takes care about mutation persistence, commit and rollback behaviour
	 */
	public Transaction(
		@Nonnull UUID transactionId,
		@Nonnull TransactionHandler transactionHandler,
		@Nonnull TransactionalMemory transactionalMemory,
		boolean replay
	) {
		Assert.isPremiseValid(
			transactionHandler == transactionalMemory.getFinalizer(),
			"Transaction handler and transactional memory finalizer must be the same instance!"
		);
		this.transactionId = transactionId;
		this.transactionHandler = transactionHandler;
		this.transactionalMemory = transactionalMemory;
		this.transactionalMemory.extendTransaction();
		this.replay = replay;
		this.created = OffsetDateTime.now();
	}

	@Override
	public void setRollbackOnly() {
		this.rollbackOnly = true;
	}

	public void setRollbackOnlyWithException(@Nonnull Throwable cause) {
		this.rollbackOnly = true;
		this.rollbackCause = cause;
	}

	@Override
	public boolean isClosed() {
		return this.closed != null;
	}

	@Override
	public void close() {
		if (this.closed != null) {
			return;
		} else {
			this.closed = OffsetDateTime.now();
		}

		try {
			if (isRollbackOnly()) {
				if (this.rollbackCause != null) {
					log.debug("Rolling back transaction `" + this.transactionId + "` with exception.", this.rollbackCause);
				} else {
					log.debug("Rolling back transaction `{}`.", this.transactionId);
				}
				this.transactionalMemory.rollback(this.rollbackCause);
			} else {
				log.debug("Committing transaction `{}`.", this.transactionId);
				this.transactionalMemory.commit();
			}
		} finally {
			// now we remove the transactional memory - no object will see it transactional memory from now on
			CURRENT_TRANSACTION.remove();
		}
	}

	/**
	 * Binds this transaction to current thread.
	 */
	public boolean bindTransactionToThread() {
		final Transaction currentValue = CURRENT_TRANSACTION.get();
		Assert.isPremiseValid(
			currentValue == null || currentValue == this,
			() -> "You cannot mix calling different sessions within one thread (sessions `" + (currentValue == null ? "NULL" : currentValue.transactionId) + "` and `" + this.transactionId + "`)!");
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
	 * Retrieves the committed state of the isolated transactional layer, if available.
	 * This method will attempt to return the committed state managed by the transactional memory finalizer.
	 * If the finalizer is not of the expected type, an exception is thrown.
	 *
	 * @param <S> the type of the committed state returned
	 * @return the committed state of type S, or null if not available
	 * @throws GenericEvitaInternalError if the finalizer type is unexpected
	 */
	@Nullable
	public <S> S getCommitedState() {
		if (this.transactionalMemory.getFinalizer() instanceof IsolatedTransactionalLayerMaintainerFinalizer<?,?,?> finalizer) {
			//noinspection unchecked
			return (S) finalizer.getCommitted();
		} else {
			throw new GenericEvitaInternalError("Unexpected finalizer type!");
		}
	}

	/**
	 * All mutation operations that occur within the transaction must be registered here in order they get recorded
	 * in the WAL and participate in conflict resolution logic.
	 *
	 * @param mutation mutation to be registered
	 */
	public void registerMutation(@Nonnull Mutation mutation) {
		Assert.isPremiseValid(this.transactionHandler != null, "Transaction handler must be set!");
		this.transactionHandler.registerMutation(mutation);
	}

	@Override
	public String toString() {
		return this.transactionId + " (replay=" + this.replay + ", rollbackOnly=" + this.rollbackOnly + '}';
	}

	/**
	 * This class represents a finalizer for the isolated transactional layer maintainer.
	 * It encapsulates the root of the transactional object tree and defines the logic for
	 * commit and rollback operations in a transactional context.
	 *
	 * @param <S> the type of the state object being managed
	 * @param <X> the type of the transactional layer's intermediate representation
	 * @param <T> the type of the transactional layer producer
	 */
	@Slf4j
	private static class IsolatedTransactionalLayerMaintainerFinalizer<S, X, T extends TransactionalLayerProducer<X, S>>
		implements TransactionalLayerMaintainerFinalizer {
		/**
		 * Root of the transactional object tree. Changes of this object will be subject to commit / rollback.
		 */
		private final T txRoot;
		/**
		 * Result of the possible commit operation.
		 */
		@Getter private S committed;

		public IsolatedTransactionalLayerMaintainerFinalizer(T txRoot) {
			this.txRoot = txRoot;
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = transactionalLayer.getStateCopyWithCommittedChanges(this.txRoot);
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			// just log the problem
			log.error("Rollback of transactional layer failed.", cause);
		}
	}
}
