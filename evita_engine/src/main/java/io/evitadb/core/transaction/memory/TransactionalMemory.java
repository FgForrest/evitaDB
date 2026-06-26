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

import com.carrotsearch.hppc.ObjectIdentityHashSet;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Central object holding the per-transaction diff layer for all {@link TransactionalLayerCreator} implementations.
 * Changes made through it are visible only inside the owning transaction; accesses from outside (e.g. other threads
 * reading the same baseline state objects) never observe them until they are committed and a new state instance is
 * published.
 *
 * The lifecycle is not driven through this class directly. A single {@link io.evitadb.core.transaction.Transaction}
 * owns one instance and drives it via {@link io.evitadb.core.transaction.Transaction#close()}, which calls
 * {@link #commit()} by default or {@link #rollback(Throwable)} when the transaction was marked rollback-only. Both
 * are instance methods that delegate to the underlying {@link TransactionalLayerMaintainer}. A thread holds at most
 * one active transaction at a time (bound through the `CURRENT_TRANSACTION` thread local); there is no notion of
 * multiple simultaneous transactions on the same thread.
 *
 * All changes made by objects participating in a transaction (each must implement {@link TransactionalLayerCreator}
 * or {@link TransactionalLayerProducer}) are captured in separate diff objects and must never mutate the original
 * immutable baseline. New state is materialized only at commit time via
 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}.
 *
 * All copies produced by `createCopyWithMergedTransactionalMemory` must be consumed by the registered
 * {@link TransactionalLayerMaintainerFinalizer#commit(TransactionalLayerMaintainer)} so that no change is lost.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
public class TransactionalMemory {
	/**
	 * Maintainer holding this transaction's diff layer; merges it with the immutable baseline at commit time.
	 */
	private final TransactionalLayerMaintainer transactionalLayerMaintainer;
	/**
	 * Stack of suppression frames, one per active {@link #suppressTransactionalMemoryLayerForWithResult} scope; while
	 * a creator sits in the top frame, no diff layer is created or returned for it.
	 */
	private final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedCreatorStack = new ArrayDeque<>(64);

	public TransactionalMemory(@Nonnull TransactionalLayerMaintainerFinalizer finalizer) {
		this.transactionalLayerMaintainer = new TransactionalLayerMaintainer(finalizer);
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
		return this.transactionalLayerMaintainer.getFinalizer();
	}

	/**
	 * Propagates changes in states made in transactional layer down to real "state" in {@link TransactionalLayerCreator}
	 * which may be stored in longer living state object.
	 */
	public void commit() {
		// execute commit - all transactional object can still access their transactional memories during
		// entire commit phase
		this.transactionalLayerMaintainer.commit();
	}

	/**
	 * Rolls back the transaction and frees resources held by it (e.g. WAL files, off-heap regions).
	 *
	 * On the diff-layer path this is structurally a no-op: the immutable baseline objects are never mutated in
	 * place, so discarding this transaction's diff layer is enough — there is nothing to structurally undo. Only
	 * the registered {@link java.io.Closeable} resources need cleaning up.
	 */
	public void rollback(@Nullable Throwable cause) {
		// execute rollback - some transactional objects may want to react and clean-up resources
		this.transactionalLayerMaintainer.rollback(cause);
	}

	/**
	 * Returns transactional layer for states, that is isolated for this thread.
	 */
	@Nonnull
	public TransactionalLayerMaintainer getTransactionalLayerMaintainer() {
		return this.transactionalLayerMaintainer;
	}

	/**
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	@Nullable
	public <T> T getOrCreateTransactionalMemoryLayer(TransactionalLayerCreator<T> layerCreator) {
		final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedObjects = this.suppressedCreatorStack;
		if (suppressedObjects.isEmpty() || !suppressedObjects.peek().contains(layerCreator)) {
			return this.transactionalLayerMaintainer.getOrCreateTransactionalMemoryLayer(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Returns transactional states for passed layer creator object, that is isolated for this thread.
	 */
	@Nullable
	public <T> T getTransactionalMemoryLayerIfExists(TransactionalLayerCreator<T> layerCreator) {
		final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedObjects = this.suppressedCreatorStack;
		if (suppressedObjects.isEmpty() || !suppressedObjects.peek().contains(layerCreator)) {
			return this.transactionalLayerMaintainer.getTransactionalMemoryLayerIfExists(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Returns the existing transactional layer for the passed creator (never creates one), capturing its pre-mutation
	 * state into an open per-entity savepoint on first touch. Use when mutating an already-existing layer through the
	 * fast path.
	 *
	 * @param layerCreator the creator whose existing diff layer is requested
	 * @return the existing diff layer, or NULL when none exists (or the creator is currently suppressed)
	 */
	@Nullable
	public <T> T getTransactionalMemoryLayerForWriteIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedObjects = this.suppressedCreatorStack;
		if (suppressedObjects.isEmpty() || !suppressedObjects.peek().contains(layerCreator)) {
			return this.transactionalLayerMaintainer.getTransactionalMemoryLayerForWriteIfExists(layerCreator);
		} else {
			return null;
		}
	}

	/**
	 * Returns registered transaction finalizer.
	 */
	@Nonnull
	public TransactionalLayerMaintainerFinalizer getTransactionalLayerMaintainerFinalizer() {
		return this.transactionalLayerMaintainer.getFinalizer();
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
		return this.transactionalLayerMaintainer.removeTransactionalMemoryLayerIfExists(layerCreator);
	}

	/**
	 * This method allows to continue with memory of already committed or rolled back transaction. It's used when
	 * the system replays more than single transaction in a row.
	 */
	public void extendTransaction() {
		this.transactionalLayerMaintainer.extendTransaction();
	}
}
