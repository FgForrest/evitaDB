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

package io.evitadb.index.transactionalMemory;

import io.evitadb.core.Transaction;
import io.evitadb.index.transactionalMemory.exception.StaleTransactionMemoryException;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
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
	 * This field may refer to the upper transaction. Currently, it's not used but it's the preferred way of implementing
	 * nested transactions if they're going to be supported in the future.
	 */
	private final TransactionalLayerMaintainer parent;
	/**
	 * Index of all transactional layer memories of all {@link io.evitadb.index.array.TransactionalObject} that work
	 * with isolated transactional memory.
	 */
	private final Map<TransactionalLayerCreatorKey, TransactionalLayerWrapper<?>> transactionalLayer;
	/**
	 * List of all consumers that are able to process transactional layers and merge them with original state producing
	 * brand-new state in return.
	 */
	private final List<TransactionalLayerConsumer> layerConsumers = new LinkedList<>();
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

	TransactionalLayerMaintainer() {
		parent = null;
		transactionalLayer = new HashMap<>(1024);
	}

	TransactionalLayerMaintainer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.parent = transactionalLayer;
		this.transactionalLayer = new HashMap<>(1024);
	}

	/**
	 * Returns list of all {@link TransactionalLayerConsumer} that take care of application diffs from transactional memory.
	 */
	@Nonnull
	public List<? extends TransactionalLayerConsumer> getLayerConsumers() {
		return this.layerConsumers;
	}

	/**
	 * Method removes existing transactional diff for passed layer creator.
	 *
	 * @throws IllegalArgumentException when layer creator has no diff present
	 */
	@Nonnull
	public <T> T removeTransactionalMemoryLayer(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerCreator);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> removedValue = (TransactionalLayerWrapper<T>) transactionalLayer.remove(key);
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
		return (T) ofNullable(transactionalLayer.remove(key))
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
	public <T> T getTransactionalMemoryLayer(TransactionalLayerCreator<T> layerCreator) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerCreator);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemoryWrapper = (TransactionalLayerWrapper<T>) transactionalLayer.get(key);
		if (transactionalMemoryWrapper != null) {
			return transactionalMemoryWrapper.getItem();
		}
		if (parent != null) {
			return parent.getTransactionalMemoryLayerIfExists(layerCreator);
		}

		final T transactionalMemory;
		if (allowTransactionalLayerCreation) {
			transactionalMemory = layerCreator.createLayer();
			if (transactionalMemory != null) {
				transactionalLayer.put(key, new TransactionalLayerWrapper<>(transactionalMemory));
			}
		} else {
			transactionalMemory = null;
		}

		return transactionalMemory;
	}

	/**
	 * Returns existing transactional memory for passed {@link TransactionalLayerCreator}. If no transactional memory
	 * diff piece exists NULL is returned.
	 *
	 * @return NULL value when no diff piece is found, new diff piece is never created by this method
	 */
	public <T> T getTransactionalMemoryLayerIfExists(TransactionalLayerCreator<T> layerProvider) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerProvider);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemory = (TransactionalLayerWrapper<T>) transactionalLayer.get(key);
		if (transactionalMemory == null && parent != null) {
			return parent.getTransactionalMemoryLayerIfExists(layerProvider);
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
	public <S, T> S getStateCopyWithCommittedChangesWithoutDiscardingState(TransactionalLayerProducer<T, S> transactionalLayerProducer, Transaction transaction) {
		try {
			Assert.isTrue(
				avoidDiscardingState.compareAndSet(false, true),
				"Calling getStateCopyWithCommittedChangesWithoutDiscardingState in nested way is not allowed (we don't maintain stack)!"
			);
			return getStateCopyWithCommittedChanges(transactionalLayerProducer, transaction);
		} finally {
			avoidDiscardingState.set(false);
		}
	}

	/**
	 * This method will retrieve transactional layer for passed producer, asks it for creating copy of the producer
	 * with applying transactional change. When copy is successfully created transactional memory item is removed from
	 * the transaction.
	 *
	 * Method returns NULL if no transactional changes were made to the object, and it may remain same.
	 */
	@Nonnull
	public <S, T> S getStateCopyWithCommittedChanges(@Nonnull TransactionalLayerProducer<T, S> transactionalLayerProducer, @Nullable Transaction transaction) {
		final TransactionalLayerWrapper<T> transactionalLayerForItem = getTransactionalMemoryLayerItemWrapperIfExists(transactionalLayerProducer);
		final S copyWithCommittedChanges = transactionalLayerProducer.createCopyWithMergedTransactionalMemory(
			ofNullable(transactionalLayerForItem)
				.map(TransactionalLayerWrapper::getItem)
				.orElse(null),
			this, transaction
		);
		if (!avoidDiscardingState.get() && transactionalLayerForItem != null) {
			transactionalLayerForItem.discard();
		}
		return copyWithCommittedChanges;
	}

	/**
	 * Method registers new {@link TransactionalLayerConsumer} to the set of implementations that take care of application
	 * diffs from transactional memory.
	 *
	 * @see TransactionalLayerConsumer
	 */
	void addLayerConsumer(@Nonnull TransactionalLayerConsumer consumer) {
		this.layerConsumers.add(consumer);
	}

	/**
	 * Method traverses through all {@link #layerConsumers} and collects new objects that combines original state and
	 * diff in transactional memory. Method doesn't handle propagation of newly created object to the `currently used state`.
	 * Consumers should build up new internal state and then `old state` should be swapped with `new state` in single
	 * reference change so that all transactional changes are applied atomically.
	 *
	 * @throws StaleTransactionMemoryException when there are diff pieces left that no consumer has handled, this would
	 *                                         mean that part of the changes would get lost, which is unacceptable
	 */
	void commit() {
		// no new transactional memories may happen
		allowTransactionalLayerCreation = false;

		// let's process all the transactional memory consumers - it's their responsibility to process all transactional
		// memory containers
		for (TransactionalLayerConsumer layerConsumer : layerConsumers) {
			layerConsumer.collectTransactionalChanges(this);
		}
		// collect all data that has not been processed and discarded by the consumers and connect them with their creators
		final List<TransactionalLayerCreator<?>> uncommittedData = new LinkedList<>();
		for (Entry<TransactionalLayerCreatorKey, TransactionalLayerWrapper<?>> entry : transactionalLayer.entrySet()) {
			if (entry.getValue().getState() == TransactionalLayerState.ALIVE) {
				final TransactionalLayerCreatorKey key = entry.getKey();
				final TransactionalLayerCreator<?> transactionalLayerCreator = key.getTransactionalLayerCreator();
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
	 * Returns existing transactional memory for passed {@link TransactionalLayerCreator}. If no transactional memory
	 * diff piece exists NULL is returned.
	 *
	 * @return NULL value when no diff piece is found, new diff piece is never created by this method
	 */
	@Nullable
	private <T> TransactionalLayerWrapper<T> getTransactionalMemoryLayerItemWrapperIfExists(TransactionalLayerCreator<T> layerProvider) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerProvider);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemory = (TransactionalLayerWrapper<T>) transactionalLayer.get(key);
		if (transactionalMemory == null && parent != null) {
			return parent.getTransactionalMemoryLayerItemWrapperIfExists(layerProvider);
		}
		return transactionalMemory;
	}

	/**
	 * Class represents caching key for the diff piece created by {@link TransactionalLayerCreator#createLayer()}.
	 * Equals and hash logic uses {@link TransactionalLayerCreator#getId()} and {@link TransactionalLayerCreator#getClass()}.
	 */
	private static class TransactionalLayerCreatorKey {
		@Getter private final TransactionalLayerCreator<?> transactionalLayerCreator;
		@Getter private final Long transactionalLayerProviderId;

		TransactionalLayerCreatorKey(TransactionalLayerCreator<?> transactionalLayerCreator) {
			this.transactionalLayerCreator = transactionalLayerCreator;
			this.transactionalLayerProviderId = transactionalLayerCreator.getId();
		}

		@Override
		public int hashCode() {
			int result = transactionalLayerCreator != null ? transactionalLayerCreator.getClass().hashCode() : 0;
			result = 31 * result + (transactionalLayerProviderId != null ? transactionalLayerProviderId.hashCode() : 0);
			return result;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			TransactionalLayerCreatorKey that = (TransactionalLayerCreatorKey) o;

			if (transactionalLayerCreator != null ? !transactionalLayerCreator.getClass().equals(that.transactionalLayerCreator.getClass()) : that.transactionalLayerCreator != null)
				return false;
			return transactionalLayerProviderId != null ? transactionalLayerProviderId.equals(that.transactionalLayerProviderId) : that.transactionalLayerProviderId == null;
		}

	}

}
