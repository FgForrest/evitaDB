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

import com.carrotsearch.hppc.ObjectIdentityHashSet;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.Transaction;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

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
 * Changes must create separate copy in {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)}
 * method.
 *
 * All copies created by {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)}
 * must be consumed by registered {@link TransactionalLayerConsumer#collectTransactionalChanges(TransactionalLayerMaintainer)} so
 * that no changes end in the void.
 *
 * Transactional memory is bound to current thread. Single thread may open multiple simultaneous transactions, but accessible
 * is only the last one created. Changes made in one transaction are not visible in other transactions (currently).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
public class TransactionalMemory {
	private static final ThreadLocal<UUID> CURRENT_TRANSACTION = new ThreadLocal<>();
	private static final Map<UUID, TransactionalMemory> TRANSACTIONAL_MEMORY = new ConcurrentHashMap<>(128);
	@Getter private final UUID transactionalId;
	private final TransactionalLayerMaintainer transactionalLayer;
	private final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedCreatorStack = new LinkedList<>();

	/**
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static void bindSession(@Nonnull UUID sessionId, @Nonnull Runnable lambda) {
		try {
			Assert.isPremiseValid(CURRENT_TRANSACTION.get() == null, "You cannot mix calling different sessions within one thread (sessions `" + CURRENT_TRANSACTION.get() + "` and `" + sessionId + "`)!");
			CURRENT_TRANSACTION.set(sessionId);
			lambda.run();
		} finally {
			CURRENT_TRANSACTION.remove();
		}
	}

	/**
	 * Method initializes current session UUID to the thread context and binds transaction for particular session as
	 * the "current" transaction.
	 */
	public static <T> T bindSession(@Nonnull UUID sessionId, @Nonnull Supplier<T> lambda) {
		try {
			final UUID currentValue = CURRENT_TRANSACTION.get();
			Assert.isPremiseValid(currentValue == null || currentValue.equals(sessionId), "You cannot mix calling different sessions within one thread (sessions `" + currentValue + "` and `" + sessionId + "`)!");
			CURRENT_TRANSACTION.set(sessionId);
			return lambda.get();
		} finally {
			CURRENT_TRANSACTION.remove();
		}
	}

	/**
	 * Returns current session id bound to this thread.
	 * @see #bindSession(UUID, Runnable)
	 * @see #bindSession(UUID, Supplier)
	 */
	@Nonnull
	public static UUID getCurrentSessionId() {
		return ofNullable(CURRENT_TRANSACTION.get())
			.orElseThrow(() -> new EvitaInternalError("No session was bound to the transaction!"));
	}

	/**
	 * Prematurely unbinds the current session from the context. Should be called only in {@link EvitaSession#close()}
	 * method.
	 *
	 * @see #bindSession(UUID, Runnable)
	 * @see #bindSession(UUID, Supplier)
	 */
	public static void unbindSessionPrematurely(@Nonnull UUID sessionId) {
		ofNullable(CURRENT_TRANSACTION.get())
			.ifPresent(it -> {
				if (it.equals(sessionId)) {
					CURRENT_TRANSACTION.remove();
				}
			});
	}

	/**
	 * Returns current session id bound to this thread.
	 * @see #bindSession(UUID, Runnable)
	 * @see #bindSession(UUID, Supplier)
	 */
	@Nonnull
	public static UUID getCurrentSessionIdIfExists() {
		return CURRENT_TRANSACTION.get();
	}

	/**
	 * Opens a new layer of transactional states upon {@link TransactionalLayerCreator} object.
	 */
	public static void open(@Nonnull UUID sessionId) {
		TRANSACTIONAL_MEMORY.compute(sessionId, (uuid, transactionalMemory) -> {
			Assert.isPremiseValid(transactionalMemory == null, "Transactional memory is already present!");
			return new TransactionalMemory(sessionId);
		});
	}

	/**
	 * Propagates changes in states made in transactional layer down to real "state" in {@link TransactionalLayerCreator}
	 * which may be stored in longer living state object.
	 */
	public static void commit(@Nonnull UUID sessionId) {
		final TransactionalMemory txMemory = getTransactionalMemory(sessionId);
		try {
			// execute commit - all transactional object can still access their transactional memories during
			// entire commit phase
			txMemory.transactionalLayer.commit();
		} finally {
			// now we remove the transactional memory - no object will see it transactional memory from now on
			TRANSACTIONAL_MEMORY.remove(sessionId);
			CURRENT_TRANSACTION.remove();
		}
	}

	/**
	 * Rollbacks all transactional changes (i.e. dereferences them).
	 */
	@Nullable
	public static TransactionalMemory rollback(@Nonnull UUID sessionId) {
		final TransactionalMemory removedMemory = TRANSACTIONAL_MEMORY.remove(sessionId);
		CURRENT_TRANSACTION.remove();
		return removedMemory;
	}

	/**
	 * Registers transaction commit handler for current transaction. Implementation of {@link TransactionalLayerConsumer}
	 * may withdraw multiple {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)} 4
	 * and use their results to swap certain internal state atomically.
	 *
	 * All withdrawn objects will be considered as committed.
	 */
	public static void addTransactionCommitHandler(@Nonnull UUID sessionId, @Nonnull TransactionalLayerConsumer consumer) {
		final TransactionalMemory txMemory = getTransactionalMemory(sessionId);
		txMemory.transactionalLayer.addLayerConsumer(consumer);
	}

	/**
	 * Returns read-only list of registered transaction commit handlers.
	 */
	@Nonnull
	public static List<TransactionalLayerConsumer> getTransactionCommitHandlers(@Nonnull UUID sessionId) {
		final TransactionalMemory txMemory = getTransactionalMemory(sessionId);
		return Collections.unmodifiableList(txMemory.transactionalLayer.getLayerConsumers());
	}

	/**
	 * Returns true if transactional memory is present and usable.
	 */
	public static boolean isTransactionalMemoryAvailable() {
		return getTransactionalMemoryIfExists(getCurrentSessionIdIfExists()) != null;
	}

	/**
	 * Returns transactional layer for states, that is isolated for this thread.
	 */
	@Nullable
	public static <T> T getTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final TransactionalMemory txMemory = getTransactionalMemoryIfExists(getCurrentSessionIdIfExists());
		if (txMemory != null) {
			return txMemory.transactionalLayer.getTransactionalMemoryLayerIfExists(layerCreator);
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
		final TransactionalMemory txMemory = getTransactionalMemoryIfExists(getCurrentSessionIdIfExists());
		if (txMemory != null) {
			final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> suppressedObjects = txMemory.suppressedCreatorStack;
			if (suppressedObjects.isEmpty() || !suppressedObjects.peek().contains(layerCreator)) {
				return txMemory.transactionalLayer.getTransactionalMemoryLayer(layerCreator);
			} else {
				return null;
			}
		} else {
			return null;
		}
	}

	/**
	 * Returns transactional layer for states, that is isolated for this thread.
	 */
	@Nullable
	public static TransactionalLayerMaintainer getTransactionalMemoryLayer() {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final TransactionalMemory txMemory = getTransactionalMemoryIfExists(getCurrentSessionIdIfExists());
		if (txMemory != null) {
			return txMemory.transactionalLayer;
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
	public static <T, U> U suppressTransactionalMemoryLayerForWithResult(@Nonnull T object, @Nonnull Function<T, U> objectConsumer) {
		Assert.isPremiseValid(object instanceof TransactionalLayerCreator, "Object " + object.getClass() + " doesn't implement TransactionalLayerCreator interface!");
		Assert.isPremiseValid(getTransactionalMemoryLayerIfExists((TransactionalLayerCreator<?>) object) == null, "There already exists transactional memory for passed creator!");
		final TransactionalMemory txMemory = getTransactionalMemory(getCurrentSessionId());
		final Deque<ObjectIdentityHashSet<TransactionalLayerCreator<?>>> deque = txMemory.suppressedCreatorStack;
		try {
			final ObjectIdentityHashSet<TransactionalLayerCreator<?>> suppressedSet = new ObjectIdentityHashSet<>(16, 0.8d);
			suppressedSet.add((TransactionalLayerCreator<?>) object);
			if (object instanceof TransactionalCreatorMaintainer) {
				final Collection<TransactionalLayerCreator<?>> creators = ((TransactionalCreatorMaintainer) object).getMaintainedTransactionalCreators();
				for (TransactionalLayerCreator<?> creator : creators) {
					suppressedSet.add(creator);
				}
			}
			deque.push(suppressedSet);
			return objectConsumer.apply(object);
		} finally {
			deque.pop();
		}
	}

	/**
	 * Removes transactional layer for passed layer creator.
	 */
	@Nullable
	public static <T> T removeTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		// we may safely do this because transactionalLayer is stored in ThreadLocal and
		// thus won't be accessed by multiple threads at once
		final TransactionalMemory txMemory = getTransactionalMemoryIfExists(getCurrentSessionIdIfExists());
		if (txMemory != null) {
			return txMemory.transactionalLayer.removeTransactionalMemoryLayerIfExists(layerCreator);
		} else {
			return null;
		}
	}

	@Nullable
	private static TransactionalMemory getTransactionalMemoryIfExists(@Nullable UUID sessionId) {
		return sessionId == null ? null : TRANSACTIONAL_MEMORY.get(sessionId);
	}

	@Nonnull
	private static TransactionalMemory getTransactionalMemory(@Nonnull UUID sessionId) {
		final TransactionalMemory txMemory = TRANSACTIONAL_MEMORY.get(sessionId);
		Assert.notNull(txMemory, "Transactional memory is unexpectedly null!");
		return txMemory;
	}

	private TransactionalMemory(@Nonnull UUID sessionId) {
		this.transactionalId = sessionId;
		this.transactionalLayer = new TransactionalLayerMaintainer();
	}

}
