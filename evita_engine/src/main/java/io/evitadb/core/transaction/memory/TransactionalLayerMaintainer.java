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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.exception.StaleTransactionMemoryException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Optional.ofNullable;

/**
 * Transactional layer is a temporary storage for storing {@link TransactionalLayerCreator#createLayer()} objects.
 * These object contain mutable difference against immutable state. Transactional layer represents set of changes
 * performed in current transaction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
public class TransactionalLayerMaintainer {
	/**
	 * Finalizer that are able to process transactional layers and merge them with original state producing
	 * brand-new state in return.
	 */
	@Nonnull private final TransactionalLayerMaintainerFinalizer finalizer;
	/**
	 * This field may refer to the upper transaction. Currently, it's not used but it's the preferred way of implementing
	 * nested transaction if they're going to be supported in the future.
	 */
	private final TransactionalLayerMaintainer parent;
	/**
	 * Index of all transactional layer memories of all {@link io.evitadb.index.array.TransactionalObject} that work
	 * with isolated transactional memory.
	 */
	private final Map<TransactionalLayerCreatorKey, TransactionalLayerWrapper<?>> transactionalLayer;
	/**
	 * Internal flag that allows to avoid marking the used transactional layer as {@link TransactionalLayerState#DISCARDED}.
	 * It's used when we need merged transactional state within the current transaction leaving the modification state
	 * intact for further {@link #commit()}.
	 */
	private final AtomicBoolean avoidDiscardingState = new AtomicBoolean();
	/**
	 * This flag is set to FALSE when transaction is committed. From this moment on no transactional memory layer is
	 * allowed to be created.
	 */
	private boolean allowTransactionalLayerCreation = true;

	TransactionalLayerMaintainer(
		@Nonnull TransactionalLayerMaintainerFinalizer finalizer
	) {
		this.finalizer = finalizer;
		this.parent = null;
		this.transactionalLayer = new HashMap<>(4096);
	}

	TransactionalLayerMaintainer(
		@Nonnull TransactionalLayerMaintainerFinalizer finalizer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		this.finalizer = finalizer;
		this.parent = transactionalLayer;
		this.transactionalLayer = new HashMap<>(4096);
	}

	/**
	 * Returns {@link TransactionalLayerMaintainerFinalizer} that take care of application diffs from transactional memory.
	 */
	@Nonnull
	public TransactionalLayerMaintainerFinalizer getFinalizer() {
		return this.finalizer;
	}

	/**
	 * Method removes existing transactional diff for passed layer creator.
	 *
	 * @throws IllegalArgumentException when layer creator has no diff present
	 */
	@Nonnull
	public <T> T removeTransactionalMemoryLayer(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerCreator);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> removedValue = (TransactionalLayerWrapper<T>) this.transactionalLayer.remove(key);
		Assert.notNull(removedValue, "Value should have been removed but was not!");
		return removedValue.getItem();
	}

	/**
	 * Method removes existing transactional diff for passed layer creator if it exists (never throws exception).
	 */
	@Nullable
	public <T> T removeTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerCreator);
		//noinspection unchecked
		return (T) ofNullable(this.transactionalLayer.remove(key))
			.map(TransactionalLayerWrapper::getItem)
			.orElse(null);
	}

	/**
	 * Returns existing transactional memory for passed {@link TransactionalLayerCreator}. If no transactional memory
	 * diff piece exists for this creator, it is asked to create new one and the result is registered to this
	 * TransactionalLayerMaintainer before returning.
	 *
	 * @return NULL value only when {@link TransactionalLayerCreator} produces Void as its layer
	 */
	@Nullable
	public <T> T getOrCreateTransactionalMemoryLayer(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerCreator);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemoryWrapper = (TransactionalLayerWrapper<T>) this.transactionalLayer.get(key);
		if (transactionalMemoryWrapper != null) {
			return transactionalMemoryWrapper.getItem();
		}
		if (this.parent != null) {
			return this.parent.getTransactionalMemoryLayerIfExists(layerCreator);
		}

		final T transactionalMemory;
		Assert.isPremiseValid(
			this.allowTransactionalLayerCreation,
			"Transaction is already committed / rolled back, no new transactional memory layer may be created at this time!"
		);

		transactionalMemory = layerCreator.createLayer();
		if (transactionalMemory != null) {
			this.transactionalLayer.put(key, new TransactionalLayerWrapper<>(transactionalMemory));
		}

		return transactionalMemory;
	}

	/**
	 * Returns existing transactional memory for passed {@link TransactionalLayerCreator}. If no transactional memory
	 * diff piece exists NULL is returned.
	 *
	 * @return NULL value when no diff piece is found, new diff piece is never created by this method
	 */
	@Nullable
	public <T> T getTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerProvider) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerProvider);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemory = (TransactionalLayerWrapper<T>) this.transactionalLayer.get(key);
		if (transactionalMemory == null && this.parent != null) {
			return this.parent.getTransactionalMemoryLayerIfExists(layerProvider);
		}
		return transactionalMemory == null ? null : transactionalMemory.getItem();
	}

	/**
	 * This method will retrieve transactional layer for passed producer, asks it for creating copy of the producer
	 * with applying transactional change. When copy is successfully created transactional memory item is NOT removed
	 * from the transaction.
	 *
	 * Method returns NULL if no transactional changes were made to the object, and it may remain same.
	 */
	@Nonnull
	public <S, T> S getStateCopyWithCommittedChangesWithoutDiscardingState(
		@Nonnull TransactionalLayerProducer<T, S> transactionalLayerProducer
	) {
		try {
			Assert.isTrue(
				this.avoidDiscardingState.compareAndSet(false, true),
				"Calling getStateCopyWithCommittedChangesWithoutDiscardingState in nested way is not allowed (we don't maintain stack)!"
			);
			return getStateCopyWithCommittedChanges(transactionalLayerProducer);
		} finally {
			this.avoidDiscardingState.set(false);
		}
	}

	/**
	 * This method will retrieve transactional layer for passed producer, asks it for creating copy of the producer
	 * with applying transactional change. When copy is successfully created transactional memory item is removed from
	 * the transaction.
	 */
	@Nonnull
	public <S, T> S getStateCopyWithCommittedChanges(@Nonnull TransactionalLayerProducer<T, S> transactionalLayerProducer) {
		final TransactionalLayerWrapper<T> transactionalLayerForItem = getTransactionalMemoryLayerItemWrapperIfExists(transactionalLayerProducer);
		final S copyWithCommittedChanges = transactionalLayerProducer.createCopyWithMergedTransactionalMemory(
			ofNullable(transactionalLayerForItem)
				.map(TransactionalLayerWrapper::getItem)
				.orElse(null),
			this
		);
		if (!this.avoidDiscardingState.get() && transactionalLayerForItem != null) {
			transactionalLayerForItem.discard();
		}
		return copyWithCommittedChanges;
	}

	/**
	 * Verifies that all layers in the transactional memory have been fully processed and there is no single diff piece
	 * that was not integrated into a new version.
	 *
	 * @throws StaleTransactionMemoryException when there are diff pieces left that no consumer has handled, this would
	 *                                         mean that part of the changes would get lost, which is unacceptable
	 */
	public void verifyLayerWasFullySwept() throws StaleTransactionMemoryException {
		// collect all data that has not been processed and discarded by the consumers and connect them with their creators
		final List<TransactionalLayerCreator<?>> uncommittedData = new LinkedList<>();
		for (Entry<TransactionalLayerCreatorKey, TransactionalLayerWrapper<?>> entry : this.transactionalLayer.entrySet()) {
			if (entry.getValue().getState() == TransactionalLayerState.ALIVE) {
				final TransactionalLayerCreatorKey key = entry.getKey();
				final TransactionalLayerCreator<?> transactionalLayerCreator = key.transactionalLayerCreator();
				uncommittedData.add(transactionalLayerCreator);
			}
		}
		// if any stale uncommitted data found, report exception
		if (!uncommittedData.isEmpty()) {
			uncommittedData.sort(Comparator.comparingLong(TransactionalLayerCreator::getId));
			throw new StaleTransactionMemoryException(uncommittedData);
		}
	}

	/**
	 * This method allows to continue with memory of already committed or rolled back transaction. It's used when
	 * the system replays more than single transaction in a row.
	 */
	public void extendTransaction() {
		this.allowTransactionalLayerCreation = true;
	}

	/**
	 * Method uses {@link #finalizer} to collect new objects that combine original state and diff in transactional
	 * memory. Method doesn't handle propagation of newly created object to the `currently used state`.
	 * Consumers should build up new internal state and then `old state` should be swapped with `new state` in single
	 * reference change so that all transactional changes are applied atomically.
	 *
	 * @throws StaleTransactionMemoryException when there are diff pieces left that no consumer has handled, this would
	 *                                         mean that part of the changes would get lost, which is unacceptable
	 */
	void commit() {
		// no new transactional memories may happen
		this.allowTransactionalLayerCreation = false;

		// let's process all the transactional memory consumers - it's their responsibility to process all transactional
		// memory containers and if finalizer returns true, check that entire transactional memory was cleaned up
		this.finalizer.commit(this);
	}

	/**
	 * Rolls back the changes made in a transactional layer and frees related {@link Closeable} resources.
	 *
	 * @param exception the cause of the rollback
	 */
	void rollback(@Nullable Throwable exception) {
		// no new transactional memories may happen
		this.allowTransactionalLayerCreation = false;

		// let's process all the transactional memory consumers - it's their responsibility to process all transactional
		// memory containers
		this.finalizer.rollback(this, exception);
	}

	/**
	 * Returns existing transactional memory for passed {@link TransactionalLayerCreator}. If no transactional memory
	 * diff piece exists NULL is returned.
	 *
	 * @return NULL value when no diff piece is found, new diff piece is never created by this method
	 */
	@Nullable
	private <T> TransactionalLayerWrapper<T> getTransactionalMemoryLayerItemWrapperIfExists(@Nonnull TransactionalLayerCreator<T> layerProvider) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerProvider);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemory = (TransactionalLayerWrapper<T>) this.transactionalLayer.get(key);
		if (transactionalMemory == null && this.parent != null) {
			return this.parent.getTransactionalMemoryLayerItemWrapperIfExists(layerProvider);
		}
		return transactionalMemory;
	}

	/**
	 * Class represents caching key for the diff piece created by {@link TransactionalLayerCreator#createLayer()}.
	 * Equals and hash logic uses {@link TransactionalLayerCreator#getId()} and {@link TransactionalLayerCreator} class.
	 */
	private record TransactionalLayerCreatorKey(
		@Nonnull TransactionalLayerCreator<?> transactionalLayerCreator,
		long transactionalLayerProviderId
	) {

		TransactionalLayerCreatorKey(@Nonnull TransactionalLayerCreator<?> transactionalLayerCreator) {
			this(transactionalLayerCreator, transactionalLayerCreator.getId());
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			TransactionalLayerCreatorKey that = (TransactionalLayerCreatorKey) o;

			return this.transactionalLayerProviderId == that.transactionalLayerProviderId &&
				this.transactionalLayerCreator.getClass().equals(that.transactionalLayerCreator.getClass());
		}

		@Override
		public int hashCode() {
			int result = this.transactionalLayerCreator.getClass().hashCode();
			result = 31 * result + Long.hashCode(this.transactionalLayerProviderId);
			return result;
		}

	}

}
