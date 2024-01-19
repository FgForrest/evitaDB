/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import com.carrotsearch.hppc.ObjectIdentityHashSet;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TransactionalMemory is central object for all {@link TransactionalLayerCreator} implementations. Transactional memory
 * allows to make changes to state objects that are visible only in the transaction. All accesses outside the transaction
 * (ie. from other threads to the same state objects) don't see the changes until they are committed.
 *
 * The work with transaction is expected in following form:
 *
 * ``` java
 * TransactionalMemory.open()
 * try {
 * TransactionalMemory.commit()
 * // do some work
 * } catch (Exception ex) {
 * TransactionalMemory.rollback()
 * }
 * ```
 *
 * All changes made with objects participating in transaction (all must implement {@link TransactionalLayerCreator} or
 * {@link TransactionalLayerProducer} interface) must be captured in change objects and must not affect original state.
 * Changes must create separate copy in {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
 * method.
 *
 * All copies created by {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
 * must be consumed by registered {@link TransactionalLayerMaintainerFinalizer#commit(TransactionalLayerMaintainer)} so
 * that no changes end in the void.
 *
 * Transactional memory is bound to current thread. Single thread may open multiple simultaneous transactions, but accessible
 * is only the last one created. Changes made in one transaction are not visible in other transactions (currently).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
public class TransactionalMemory {
	private final TransactionalLayerMaintainer transactionalLayer;
	private final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedCreatorStack = new LinkedList<>();

	public TransactionalMemory(@Nonnull TransactionalLayerMaintainerFinalizer finalizer) {
		this.transactionalLayer = new TransactionalLayerMaintainer(finalizer);
	}

	/**
	 * Retrieves the {@link TransactionalLayerMaintainerFinalizer} that is responsible for applying
	 * changes from the transactional memory to the immutable state and creating new instances that incorporate
	 * the changes.
	 *
	 * @return the transaction finalizer
	 */
	@Nonnull
	public TransactionalLayerMaintainerFinalizer getFinalizer() {
		return transactionalLayer.getFinalizer();
	}

	/**
	 * Propagates changes in states made in transactional layer down to real "state" in {@link TransactionalLayerCreator}
	 * which may be stored in longer living state object.
	 */
	public void commit() {
		// execute commit - all transactional object can still access their transactional memories during
		// entire commit phase
		transactionalLayer.commit();
	}

	/**
	 * Rolls back the transaction, cleans up all resources connected with them.
	 */
	public void rollback() {
		// execute rollback - some transactional objects may want to react and clean-up resources
		transactionalLayer.rollback();
	}

	/**
	 * Returns transactional layer for states, that is isolated for this thread.
	 */
	@Nullable
	public TransactionalLayerMaintainer getTransactionalMemoryLayer() {
		return this.transactionalLayer;
	}

	/**
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	public <T> T getTransactionalMemoryLayer(TransactionalLayerCreator<T> layerCreator) {
		final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedObjects = this.suppressedCreatorStack;
		if (suppressedObjects.isEmpty() || !suppressedObjects.peek().contains(layerCreator)) {
			return transactionalLayer.getTransactionalMemoryLayer(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	public <T> T getTransactionalMemoryLayerIfExists(TransactionalLayerCreator<T> layerCreator) {
		final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedObjects = this.suppressedCreatorStack;
		if (suppressedObjects.isEmpty() || !suppressedObjects.peek().contains(layerCreator)) {
			return transactionalLayer.getTransactionalMemoryLayerIfExists(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Returns registered transaction finalizer.
	 */
	@Nonnull
	public TransactionalLayerMaintainerFinalizer getTransactionalLayerMaintainerFinalizer() {
		return transactionalLayer.getFinalizer();
	}

	/**
	 * This method will suppress creation of new transactional layer for passed `object` when it is asked for inside
	 * the `objectConsumer` lambda. This makes the object effectively transactional-less for the scope of the lambda
	 * function.
	 */
	public <T> void suppressTransactionalMemoryLayerFor(@Nonnull T object, @Nonnull Consumer<T> objectConsumer) {
		suppressTransactionalMemoryLayerForWithResult(
			object, it -> {
				objectConsumer.accept(it);
				return null;
			});
	}

	/**
	 * This method will suppress creation of new transactional layer for passed `object` when it is asked for inside
	 * the `objectConsumer` lambda. This makes the object effectively transactional-less for the scope of the lambda
	 * function.
	 */
	public <T, U> U suppressTransactionalMemoryLayerForWithResult(@Nonnull T object, @Nonnull Function<T, U> objectConsumer) {
		Assert.isPremiseValid(object instanceof TransactionalLayerCreator, "Object " + object.getClass() + " doesn't implement TransactionalLayerCreator interface!");
		Assert.isPremiseValid(getTransactionalMemoryLayerIfExists((TransactionalLayerCreator<?>) object) == null, "There already exists transactional memory for passed creator!");
		try {
			final ObjectIdentityHashSet<TransactionalLayerCreator<?>> suppressedSet = new ObjectIdentityHashSet<>(16, 0.8d);
			suppressedSet.add((TransactionalLayerCreator<?>) object);
			if (object instanceof TransactionalCreatorMaintainer) {
				final Collection<TransactionalLayerCreator<?>> creators = ((TransactionalCreatorMaintainer) object).getMaintainedTransactionalCreators();
				for (TransactionalLayerCreator<?> creator : creators) {
					suppressedSet.add(creator);
				}
			}
			this.suppressedCreatorStack.push(suppressedSet);
			return objectConsumer.apply(object);
		} finally {
			this.suppressedCreatorStack.pop();
		}
	}

	/**
	 * Removes transactional layer for passed layer creator.
	 */
	@Nullable
	public <T> T removeTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		return this.transactionalLayer.removeTransactionalMemoryLayerIfExists(layerCreator);
	}

}
