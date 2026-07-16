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

import io.evitadb.core.exception.DataStructureCorruptedException;
import io.evitadb.core.exception.StaleTransactionMemoryException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
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
	/**
	 * Sentinel memento value recorded for a layer that was first created *inside* the active savepoint. On
	 * {@link #rollbackSavepoint(Savepoint)} such a layer is removed entirely (rather than restored), because it did
	 * not exist when the savepoint was opened.
	 */
	private static final Object CREATED_IN_SAVEPOINT = new Object();
	/**
	 * The currently open savepoint, or {@code null} when none is active. A single active savepoint is sufficient: a
	 * savepoint brackets exactly one root entity mutation (including its nested cross-entity mutations), and entity
	 * mutations are processed one at a time on the (single-threaded) transaction. While a savepoint is open, the
	 * {@link #getOrCreateTransactionalMemoryLayer} hook records each touched layer's pre-mutation state so the
	 * savepoint can be rolled back independently of the surrounding transaction.
	 */
	@Nullable private Savepoint currentSavepoint;
	/**
	 * Per-transaction dirty-scope registry feeding the pre-commit (pre-WAL) and post-replay (merge-time) structural
	 * integrity validation. Maps each participant (a {@link DirtyScopeValidator}) to the identity set of opaque tokens
	 * it registered at its invariant-changing mutation seams during this transaction. Both axes are identity-keyed and
	 * the whole structure is lazily instantiated on first registration (a transaction that touches no participant
	 * pays nothing). Registration feeds both the pre-commit and post-replay validation passes, which each run
	 * unconditionally whenever at least one participant was dirtied.
	 */
	@Nullable private Map<DirtyScopeValidator, Set<Object>> dirtyScopes;

	TransactionalLayerMaintainer(
		@Nonnull TransactionalLayerMaintainerFinalizer finalizer
	) {
		this.finalizer = finalizer;
		this.transactionalLayer = new HashMap<>(4096);
	}

	/**
	 * Registers an opaque scope token as dirtied by the given participant during this transaction, so the pre-commit
	 * (pre-WAL) and post-replay (merge-time) validation can re-derive its invariants at commit. Called unconditionally
	 * from the participant's invariant-changing mutation seams; the registered object is used only as a scope token
	 * (see {@link DirtyScopeValidator}).
	 *
	 * @param owner      the participant the token belongs to
	 * @param scopeToken the dirtied scope token
	 */
	public void registerDirtyScopeToken(@Nonnull DirtyScopeValidator owner, @Nonnull Object scopeToken) {
		if (this.dirtyScopes == null) {
			this.dirtyScopes = new IdentityHashMap<>(64);
		}
		this.dirtyScopes
			.computeIfAbsent(owner, it -> Collections.newSetFromMap(new IdentityHashMap<>(64)))
			.add(scopeToken);
	}

	/**
	 * Returns the scope tokens registered for the given participant during this transaction, or an empty set when it
	 * dirtied none. Used by the participant's post-replay validation to relocate each token in the freshly merged
	 * copy.
	 *
	 * @param owner the participant whose registered dirty scope tokens are requested
	 * @return the registered scope tokens for the participant; never {@code null}
	 */
	@Nonnull
	public Set<Object> getDirtyScopeTokens(@Nonnull DirtyScopeValidator owner) {
		if (this.dirtyScopes == null) {
			return Collections.emptySet();
		}
		final Set<Object> tokens = this.dirtyScopes.get(owner);
		return tokens == null ? Collections.emptySet() : tokens;
	}

	/**
	 * Runs the pre-commit (pre-WAL) dirty-scope validation: for every participant that dirtied at least one scope
	 * token, re-derives its invariants over the registered scope against the still-live transactional view. Runs
	 * unconditionally; a no-op only when no participant was touched. A violation surfaces as
	 * {@link DataStructureCorruptedException}, which the caller must translate into an exceptional commit completion
	 * so the shared WAL never receives the transaction.
	 */
	public void validateDirtyScopesBeforeCommit() {
		if (this.dirtyScopes == null) {
			return;
		}
		for (final Entry<DirtyScopeValidator, Set<Object>> entry : this.dirtyScopes.entrySet()) {
			entry.getKey().validateDirtyScope(entry.getValue());
		}
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
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> removedValue = (TransactionalLayerWrapper<T>) this.transactionalLayer.get(key);
		Assert.notNull(removedValue, "Value should have been removed but was not!");
		// when a savepoint is open the removal must be reversible (see recordSavepointRemovalIfNeeded)
		if (this.currentSavepoint != null) {
			recordSavepointRemovalIfNeeded(key, removedValue);
		}
		this.transactionalLayer.remove(key);
		return removedValue.getItem();
	}

	/**
	 * Method removes existing transactional diff for passed layer creator if it exists (never throws exception).
	 */
	@Nullable
	public <T> T removeTransactionalMemoryLayerIfExists(@Nonnull TransactionalLayerCreator<T> layerCreator) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerCreator);
		final TransactionalLayerWrapper<?> wrapper = this.transactionalLayer.get(key);
		if (wrapper == null) {
			return null;
		}
		// when a savepoint is open the removal must be reversible (see recordSavepointRemovalIfNeeded)
		if (this.currentSavepoint != null) {
			recordSavepointRemovalIfNeeded(key, wrapper);
		}
		this.transactionalLayer.remove(key);
		//noinspection unchecked
		return (T) wrapper.getItem();
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
			// the caller is about to mutate this existing layer — if a savepoint is open, capture its
			// pre-mutation state on first touch so it can be restored on rollbackSavepoint
			recordSavepointSnapshotIfNeeded(key, transactionalMemoryWrapper.getItem());
			return transactionalMemoryWrapper.getItem();
		}

		final T transactionalMemory;
		Assert.isPremiseValid(
			this.allowTransactionalLayerCreation,
			"Transaction is already committed / rolled back, no new transactional memory layer may be created at this time!"
		);

		transactionalMemory = layerCreator.createLayer();
		if (transactionalMemory != null) {
			this.transactionalLayer.put(key, new TransactionalLayerWrapper<>(transactionalMemory));
			// the layer was created within an open savepoint — mark it so rollbackSavepoint drops it entirely
			recordSavepointCreationIfNeeded(key);
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
		if (transactionalMemory == null) {
			return null;
		}
		// READ-ONLY fast path: no savepoint snapshot is taken here. This is the hot path used by every transactional
		// data structure's read methods, so snapshotting on every read would capture far more layers than the rollback
		// ever needs. Callers that intend to MUTATE an already-existing layer through this
		// fast path (rather than via getOrCreateTransactionalMemoryLayer) must instead use
		// getTransactionalMemoryLayerForWriteIfExists so the pre-mutation state is captured for per-entity rollback.
		return transactionalMemory.getItem();
	}

	/**
	 * Returns the existing transactional memory diff layer for the passed {@link TransactionalLayerCreator}, or
	 * {@code null} when none exists (never creates one). Unlike {@link #getTransactionalMemoryLayerIfExists}, this
	 * variant records the layer's pre-mutation state into the open savepoint on first touch, so it MUST be used by
	 * callers that mutate an already-existing layer through this fast path instead of via
	 * {@link #getOrCreateTransactionalMemoryLayer} (e.g. {@code TransactionalBitmap#addAll} / {@code #removeAll}).
	 * Failing to do so would leave a layer created before the savepoint and mutated inside it unreverted on rollback.
	 *
	 * @return NULL value when no diff piece is found, new diff piece is never created by this method
	 */
	@Nullable
	public <T> T getTransactionalMemoryLayerForWriteIfExists(@Nonnull TransactionalLayerCreator<T> layerProvider) {
		final TransactionalLayerCreatorKey key = new TransactionalLayerCreatorKey(layerProvider);
		@SuppressWarnings("unchecked") final TransactionalLayerWrapper<T> transactionalMemory = (TransactionalLayerWrapper<T>) this.transactionalLayer.get(key);
		if (transactionalMemory == null) {
			return null;
		}
		// the caller is about to mutate this existing layer through the fast path — capture its pre-mutation state on
		// first touch so it can be restored on rollbackSavepoint
		recordSavepointSnapshotIfNeeded(key, transactionalMemory.getItem());
		return transactionalMemory.getItem();
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
	 * Structurally a no-op on the diff path: the immutable baseline objects were never mutated in place, so the diff
	 * layer is simply discarded — there is nothing to undo. Only the {@link Closeable} resources are released.
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
	 * Opens a savepoint over this maintainer. While the returned savepoint is open, every diff layer that is touched
	 * for writing via {@link #getOrCreateTransactionalMemoryLayer} has its pre-mutation state captured on first touch
	 * (or is marked as created-within-the-savepoint), so that {@link #rollbackSavepoint(Savepoint)} can revert exactly
	 * the changes made since this call — independently of the surrounding transaction, which keeps running.
	 *
	 * Only one savepoint may be open at a time (nested savepoints are not supported); the caller must pair this with
	 * exactly one {@link #commitSavepoint(Savepoint)} or {@link #rollbackSavepoint(Savepoint)}.
	 *
	 * @return the opened savepoint handle to pass back to commit / rollback
	 */
	@Nonnull
	public Savepoint openSavepoint() {
		Assert.isPremiseValid(
			this.currentSavepoint == null,
			"A savepoint is already open - nested savepoints are not supported!"
		);
		final Savepoint savepoint = new Savepoint();
		this.currentSavepoint = savepoint;
		return savepoint;
	}

	/**
	 * Commits (accepts) the given savepoint: the captured pre-mutation state is discarded and all changes made while
	 * the savepoint was open remain part of the transaction. This merely drops the savepoint bookkeeping; no diff
	 * layer is modified.
	 *
	 * @param savepoint the savepoint previously returned by {@link #openSavepoint()}
	 */
	public void commitSavepoint(@Nonnull Savepoint savepoint) {
		Assert.isPremiseValid(
			this.currentSavepoint == savepoint,
			"The committed savepoint is not the currently open one!"
		);
		// deactivate the savepoint first so the releaseMemento() operations below do not re-record into it
		this.currentSavepoint = null;
		for (final Entry<TransactionalLayerCreatorKey, Object> entry : savepoint.mementos.entrySet()) {
			final Object memento = entry.getValue();
			if (memento == CREATED_IN_SAVEPOINT || memento instanceof RemovedLayer) {
				// a layer created inside the savepoint was never snapshotted (nothing to release); a layer removed
				// inside the savepoint is already detached from the transactional memory - neither keeps drainable
				// per-savepoint scratch state on a still-attached layer
				continue;
			}
			// a plain memento is only ever recorded for a layer that still exists (a removal upgrades it to a
			// RemovedLayer), so the wrapper must be present - a missing one would be a programming error
			final TransactionalLayerWrapper<?> wrapper = this.transactionalLayer.get(entry.getKey());
			Assert.isPremiseValid(
				wrapper != null,
				"A snapshotted layer disappeared from the transactional memory without being recorded as removed!"
			);
			releaseLayerMemento(wrapper.getItem(), memento);
		}
	}

	/**
	 * Rolls the given savepoint back: every diff layer touched while the savepoint was open is reverted to the state
	 * captured at first touch (via {@link Snapshotable#restore(Object)}), and every layer that was created within the
	 * savepoint is removed entirely. The surrounding transaction is left intact and may continue.
	 *
	 * @param savepoint the savepoint previously returned by {@link #openSavepoint()}
	 */
	public void rollbackSavepoint(@Nonnull Savepoint savepoint) {
		Assert.isPremiseValid(
			this.currentSavepoint == savepoint,
			"The rolled-back savepoint is not the currently open one!"
		);
		// deactivate the savepoint first so the restore() operations below do not re-record into it
		this.currentSavepoint = null;
		for (final Entry<TransactionalLayerCreatorKey, Object> entry : savepoint.mementos.entrySet()) {
			final Object memento = entry.getValue();
			if (memento == CREATED_IN_SAVEPOINT) {
				// the layer did not exist when the savepoint opened - drop it together with all its changes
				this.transactionalLayer.remove(entry.getKey());
			} else if (memento instanceof final RemovedLayer removed) {
				// the layer existed when the savepoint opened but was removed inside it (e.g. a B+ tree node
				// dropped during a split/merge) - re-attach the original wrapper and restore its pre-savepoint state
				this.transactionalLayer.put(entry.getKey(), removed.wrapper());
				restoreLayer(removed.wrapper().getItem(), removed.memento());
				// the savepoint is closed - let the layer drop its per-savepoint scratch state (the restore above has
				// already rewound it), so post-rollback mutations stop paying the savepoint bookkeeping cost
				releaseLayerMemento(removed.wrapper().getItem(), removed.memento());
			} else {
				final TransactionalLayerWrapper<?> wrapper = this.transactionalLayer.get(entry.getKey());
				// a plain memento is only ever recorded for a layer that still exists - a removal upgrades the
				// memento to a RemovedLayer (see recordSavepointRemovalIfNeeded), so the wrapper must be present;
				// a missing one would be a silent partial-rollback gap and is therefore a programming error
				Assert.isPremiseValid(
					wrapper != null,
					"A snapshotted layer disappeared from the transactional memory without being recorded as removed!"
				);
				restoreLayer(wrapper.getItem(), memento);
				// the savepoint is closed - let the layer drop its per-savepoint scratch state (the restore above has
				// already rewound it), so post-rollback mutations stop paying the savepoint bookkeeping cost
				releaseLayerMemento(wrapper.getItem(), memento);
			}
		}
	}

	/**
	 * Restores a single layer item to the given memento. The item was recorded in the savepoint only because it
	 * implements {@link Snapshotable} (see the snapshot / removal hooks), so the cast is safe.
	 *
	 * @param item    the diff-layer item to restore
	 * @param memento the memento previously captured for it
	 */
	private static void restoreLayer(@Nonnull Object item, @Nonnull Object memento) {
		@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) item;
		snapshotable.restore(memento);
	}

	/**
	 * Releases a single layer's memento on savepoint commit (see {@link Snapshotable#releaseMemento(Object)}). The item
	 * was recorded in the savepoint only because it implements {@link Snapshotable}, so the cast is safe.
	 *
	 * @param item    the diff-layer item whose committed memento is released
	 * @param memento the memento previously captured for it
	 */
	private static void releaseLayerMemento(@Nonnull Object item, @Nonnull Object memento) {
		@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) item;
		snapshotable.releaseMemento(memento);
	}

	/**
	 * Records the pre-mutation state of an existing layer into the open savepoint on first touch. No-op when no
	 * savepoint is open or the layer was already recorded. A layer modified inside a savepoint that does not implement
	 * {@link Snapshotable} cannot be rolled back, so this is treated as a programming error rather than silently
	 * skipped (which would leave a partial-rollback gap).
	 *
	 * @param key   the key of the layer being modified
	 * @param layer the existing (non-null) diff layer about to be mutated
	 */
	private void recordSavepointSnapshotIfNeeded(@Nonnull TransactionalLayerCreatorKey key, @Nonnull Object layer) {
		final Savepoint savepoint = this.currentSavepoint;
		if (savepoint != null && !savepoint.mementos.containsKey(key)) {
			if (layer instanceof final Snapshotable<?> snapshotable) {
				savepoint.mementos.put(key, snapshotable.snapshot());
			} else {
				throw new GenericEvitaInternalError(
					"Transactional layer " + layer.getClass().getName() + " is modified inside a savepoint but does " +
						"not implement Snapshotable - its changes could not be reverted on a per-entity rollback. " +
						"Make this layer implement Snapshotable.",
					"A transactional layer modified inside a savepoint does not support snapshotting."
				);
			}
		}
	}

	/**
	 * Records that a layer was created inside the open savepoint, so {@link #rollbackSavepoint(Savepoint)} drops it
	 * entirely. No-op when no savepoint is open or the key was already recorded.
	 *
	 * @param key the key of the newly created layer
	 */
	private void recordSavepointCreationIfNeeded(@Nonnull TransactionalLayerCreatorKey key) {
		final Savepoint savepoint = this.currentSavepoint;
		if (savepoint != null && !savepoint.mementos.containsKey(key)) {
			savepoint.mementos.put(key, CREATED_IN_SAVEPOINT);
		}
	}

	/**
	 * Records that an existing layer is about to be removed while a savepoint is open, so the removal can be undone on
	 * {@link #rollbackSavepoint(Savepoint)} by re-attaching the captured wrapper and restoring its pre-savepoint state.
	 * A layer that was created inside this savepoint is left marked {@link #CREATED_IN_SAVEPOINT} (removing it now plus
	 * dropping it on rollback are both correct, so no extra bookkeeping is needed). A layer modified inside a savepoint
	 * that does not implement {@link Snapshotable} cannot be reverted — treated as a programming error rather than a
	 * silent partial-rollback gap.
	 *
	 * @param key     the key of the layer being removed
	 * @param wrapper the wrapper of the layer being removed
	 */
	private void recordSavepointRemovalIfNeeded(@Nonnull TransactionalLayerCreatorKey key, @Nonnull TransactionalLayerWrapper<?> wrapper) {
		final Savepoint savepoint = this.currentSavepoint;
		if (savepoint == null) {
			return;
		}
		final Object existing = savepoint.mementos.get(key);
		if (existing == CREATED_IN_SAVEPOINT || existing instanceof RemovedLayer) {
			// created-then-removed inside this savepoint (stays dropped on rollback), or already recorded as removed
			return;
		}
		final Object memento;
		if (existing == null) {
			// the removal is the first touch of this layer inside the savepoint — its current state is the
			// pre-savepoint state we must be able to restore
			final Object item = wrapper.getItem();
			if (item instanceof final Snapshotable<?> snapshotable) {
				memento = snapshotable.snapshot();
			} else {
				throw new GenericEvitaInternalError(
					"Transactional layer " + item.getClass().getName() + " is removed inside a savepoint but does " +
						"not implement Snapshotable - its removal could not be reverted on a per-entity rollback. " +
						"Make this layer implement Snapshotable.",
					"A transactional layer removed inside a savepoint does not support snapshotting."
				);
			}
		} else {
			// the layer was already snapshotted earlier in this savepoint — reuse that pre-mutation memento
			memento = existing;
		}
		savepoint.mementos.put(key, new RemovedLayer(wrapper, memento));
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

	/**
	 * Opaque handle for a savepoint opened via {@link #openSavepoint()}. It holds, per touched layer, the memento
	 * captured at first touch (or the {@link #CREATED_IN_SAVEPOINT} sentinel for layers created while the savepoint
	 * was open). Instances are created only by the maintainer and are meant to be passed back verbatim to
	 * {@link #commitSavepoint(Savepoint)} / {@link #rollbackSavepoint(Savepoint)}.
	 */
	public static final class Savepoint {
		/**
		 * Per-layer mementos captured while this savepoint is open. The value is one of: a memento produced by
		 * {@link Snapshotable#snapshot()} (layer present at savepoint open and touched), the
		 * {@link #CREATED_IN_SAVEPOINT} sentinel (layer first created inside the savepoint → dropped on rollback), or a
		 * {@link RemovedLayer} (layer present at savepoint open but removed inside it → re-attached + restored on
		 * rollback).
		 */
		private final Map<TransactionalLayerCreatorKey, Object> mementos = new HashMap<>(64);

		private Savepoint() {
		}
	}

	/**
	 * Savepoint bookkeeping for a layer that existed when the savepoint was opened but was removed while it was open.
	 * On {@link #rollbackSavepoint(Savepoint)} the {@link #wrapper} is re-attached to the maintainer and its item is
	 * restored to {@link #memento} (the pre-savepoint state).
	 *
	 * @param wrapper the wrapper that was removed
	 * @param memento the pre-savepoint state of the wrapper's item
	 */
	private record RemovedLayer(@Nonnull TransactionalLayerWrapper<?> wrapper, @Nonnull Object memento) {
	}

}
