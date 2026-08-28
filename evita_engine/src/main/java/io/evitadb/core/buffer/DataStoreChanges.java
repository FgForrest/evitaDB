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

package io.evitadb.core.buffer;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.UndoJournal;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.core.transaction.memory.WarmUpTouchStamped;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePartKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.IntFunction;

import static java.util.Optional.ofNullable;

/**
 * This class is used as transactional memory of the {@link DataStoreChanges} and stores the changes of the storage
 * keys directly to the target {@link StoragePartPersistenceService}, but traps the changes in the indexes in the memory
 * buffer. It provides methods to get, create, remove, and track modifications to indexes. The changes are cached in
 * memory and can be persisted to the storage using the {@link #popTrappedUpdates()} method.
 *
 * This mechanism allows to buffer frequent changes in indexes whose persistence is costly and flush the changes once in
 * a while to the persistent storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see DataStoreMemoryBuffer
 */
@NotThreadSafe
public class DataStoreChanges
	implements Snapshotable<DataStoreChanges.DataStoreChangesMemento>, WarmUpTouchStamped {
	/**
	 * This structure's first-touch mark for the warm-up savepoint mechanism: the stamp of the
	 * {@link WarmUpSavepoint} that most recently captured its pre-image. {@link WarmUpTouchStamped}
	 * carries the requirements the field has to meet, and why breaking one of them corrupts a
	 * rollback rather than merely slowing it down.
	 */
	@Getter @Setter private long warmUpTouchStamp;
	/**
	 * This map contains index of "dirty" entity indexes - i.e. subset of {@link EntityCollection indexes} that were
	 * modified and not yet persisted.
	 */
	private Map<IndexKey, Index<? extends IndexKey>> dirtyEntityIndexes = new HashMap<>(64);
	private IntObjectMap<Index<? extends IndexKey>> dirtyEntityIndexesByPk = new IntObjectHashMap<>(64);
	/**
	 * Snapshot of the {@link IndexKey}s that were dirty (acquired for modification) at the most recent
	 * {@link #popTrappedUpdates()}, captured before that method resets {@link #dirtyEntityIndexes}. The commit-time
	 * flush drains the dirty set before the trunk merge runs, so the merge can no longer read
	 * {@link #dirtyEntityIndexes} directly; this snapshot lets the merge partition the index map into genuinely-changed
	 * indexes (rebuilt) and unchanged ones (carried by reference) — see
	 * {@code EntityCollection#createCopyWithMergedTransactionalMemory}. Ground truth for "which indexes changed this
	 * transaction": it is the very set the flush persists.
	 *
	 * Only maintained when {@link #capturesDirtyIndexKeys} and only meaningful while
	 * {@link #dirtyIndexKeysSnapshotAvailable} — an empty set is otherwise indistinguishable from "no flush has run",
	 * which is exactly the state the merge must not silently prune against.
	 */
	private Set<IndexKey> lastCommittedDirtyIndexKeys = Set.of();
	/**
	 * Whether {@link #lastCommittedDirtyIndexKeys} currently describes a completed flush that no trunk merge has
	 * consumed yet. This is the positive validity marker the snapshot itself cannot carry: an empty snapshot is a
	 * legitimate outcome (a transaction may change a collection's schema without touching a single index), so the
	 * merge cannot tell "nothing was dirty" from "the flush never ran" by looking at the set alone. Guarding the read
	 * in {@link #popLastCommittedDirtyIndexKeys()} turns a broken flush-before-merge ordering into a named failure at
	 * the point of misuse, instead of a downstream {@code StaleTransactionMemoryException} from the orphaned layers a
	 * wrongly all-clean prune would leave behind.
	 */
	private boolean dirtyIndexKeysSnapshotAvailable;
	/**
	 * This map contains index of "dirty" storage parts - i.e. subset of {@link StoragePart storage parts} that were
	 * modified and not yet persisted. Usually the storage parts are stored directly in the persistent storage but
	 * the <a href="https://github.com/FgForrest/evitaDB/issues/689">issue #689</a> revealed that it's beneficial to
	 * store some of them in memory and flush them once in a while to the persistent storage.
	 */
	@Nullable private Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> trappedChanges;
	/**
	 * Deferred-removal parts accumulated when a whole {@link EntityIndex} is dropped via {@link #removeIndex}: one
	 * removal per persisted sub-index root and per persisted leaf page of that index. Unlike {@link #trappedChanges},
	 * these are {@link DeferredRemovalStoragePart}s whose primary
	 * key is resolved store-side at drain time, so they carry no resolvable key here and cannot ride the per-type
	 * primary-key map. They are drained into the {@link TrappedChanges} returned by {@link #popTrappedUpdates()} and
	 * truncated back on a savepoint rollback through the {@link #undoJournal} (mirroring how a removed dirty index is
	 * reinstated). Lazily allocated; {@code null} until the first index drop.
	 */
	@Nullable private List<StoragePart> pendingIndexRemovalParts;
	/**
	 * Contains reference to the I/O service, that allows reading/writing records to the persistent storage.
	 */
	@Nonnull private StoragePartPersistenceService<StorageDescriptor> persistenceService;
	/**
	 * Undo journal recording the inverse of every dirty-index mutation ({@link #dirtyEntityIndexes} /
	 * {@link #dirtyEntityIndexesByPk}) while a savepoint is open, so {@link #snapshot()} is `O(1)` (a journal mark)
	 * instead of deep-copying the whole catalog/collection-wide accumulated dirty-index maps per per-entity savepoint —
	 * the rollback cliff. Lazily allocated on the first {@link #snapshot()} (null for non-savepoint mutations, which pay
	 * nothing) and drained back to null when the savepoint commits (see {@link #releaseMemento(DataStoreChangesMemento)}).
	 */
	@Nullable private UndoJournal undoJournal;
	/**
	 * Whether this instance's flush feeds a merge that PRUNES on the dirty-index-key snapshot, and must therefore
	 * capture {@link #lastCommittedDirtyIndexKeys}. True only for an {@code EntityCollection} diff layer — the shared,
	 * long-lived buffer behind a warm-up or non-transactional flush is never merged at all, and the catalog-level diff
	 * layer is merged whole. Capturing anywhere else would copy the entire dirty-index key set on every flush (six
	 * figures of entries on a bulk load) for a snapshot nobody ever reads. A consumer that appears without its
	 * producer being flipped on fails loudly in {@link #popLastCommittedDirtyIndexKeys()} rather than silently
	 * pruning against an empty set.
	 */
	private final boolean capturesDirtyIndexKeys;
	/**
	 * Supplies the {@link EntitySchema} that deserializing THIS changeset's storage parts needs, or `null` when the
	 * changeset holds catalog-level parts, which carry neither references nor prices and therefore need no schema.
	 *
	 * Only {@link #journalPersistedChange} uses it. Every other read on this class is reached through
	 * `EntityCollection`'s reader, which establishes the context itself for each fetch (see the
	 * `EntitySchemaContext.executeWithSchemaContext` calls there); the savepoint's pre-image read is the one read on a
	 * WRITE path, where no such wrapper exists.
	 */
	@Nullable private final Supplier<EntitySchema> entitySchemaSupplier;

	/**
	 * Creates a buffer whose flush captures no dirty-index-key snapshot — the shared warm-up / non-transactional
	 * buffer, and any diff layer whose merge does not prune. See {@link #capturesDirtyIndexKeys}.
	 *
	 * @param persistenceService the I/O service records are read from / written to
	 */
	public DataStoreChanges(@Nonnull StoragePartPersistenceService<StorageDescriptor> persistenceService) {
		this(persistenceService, false, null);
	}

	/**
	 * Creates a data store change buffer.
	 *
	 * @param persistenceService     the I/O service records are read from / written to
	 * @param capturesDirtyIndexKeys `true` when this instance's flush feeds a pruning merge and must hand it the keys
	 *                               of the indexes it persisted (see {@link #capturesDirtyIndexKeys})
	 */
	public DataStoreChanges(
		@Nonnull StoragePartPersistenceService<StorageDescriptor> persistenceService,
		boolean capturesDirtyIndexKeys
	) {
		this(persistenceService, capturesDirtyIndexKeys, null);
	}

	/**
	 * Creates a data store change buffer that can deserialize its own storage parts while a savepoint is open.
	 *
	 * @param persistenceService     the I/O service records are read from / written to
	 * @param capturesDirtyIndexKeys `true` when this instance's flush feeds a pruning merge and must hand it the keys
	 *                               of the indexes it persisted (see {@link #capturesDirtyIndexKeys})
	 * @param entitySchemaSupplier   supplies the schema the savepoint's pre-image read deserializes against, or `null`
	 *                               for a catalog-level changeset whose parts need none (see
	 *                               {@link #entitySchemaSupplier})
	 */
	public DataStoreChanges(
		@Nonnull StoragePartPersistenceService<StorageDescriptor> persistenceService,
		boolean capturesDirtyIndexKeys,
		@Nullable Supplier<EntitySchema> entitySchemaSupplier
	) {
		this.persistenceService = persistenceService;
		this.capturesDirtyIndexKeys = capturesDirtyIndexKeys;
		this.entitySchemaSupplier = entitySchemaSupplier;
	}

	/**
	 * Allows exchanging the persistence service for this memory buffer in case of internal store compaction.
	 *
	 * @param persistenceService the persistence service to be used for storing data
	 */
	public void setPersistenceService(@Nonnull StoragePartPersistenceService<StorageDescriptor> persistenceService) {
		this.persistenceService = persistenceService;
	}

	/**
	 * Captures the revertable in-memory state of this layer for a per-entity savepoint — the transactional one (see
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerMaintainer#openSavepoint()}) and, when this instance
	 * is the warm-up buffer's plain (non-diff) changeset, the non-transactional
	 * {@link io.evitadb.core.transaction.memory.WarmUpSavepoint} that brackets a bulk-indexing entity mutation.
	 *
	 * Only the dirty-index tracking and the trapped storage-part cache are captured — the IN-MEMORY state a single
	 * entity mutation can touch while the savepoint is open: the index executor marks indexes dirty through
	 * {@link #getOrCreateIndexForModification} / {@link #getIndexForModification} on every modification, and both
	 * storage-part entry points maintain the trapped cache.
	 *
	 * Writes that reach the {@link #persistenceService} directly ({@link #putStoragePart} /
	 * {@link #removeStoragePart}) are deliberately NOT part of this memento, and are not exempt from rollback either.
	 * They are issued by the storage executor from its `commit()`, which the collector runs while the savepoint is
	 * still open and which can fail part-way through an entity that writes several parts — leaving the parts written
	 * before the failure changed in the trunk. Their pre-image is a STORED RECORD rather than in-memory state, so it
	 * cannot be rewound by replaying this layer's journal; each one is captured and journalled into the open warm-up
	 * savepoint at the point of the write instead (see {@link #journalPersistedChange}).
	 *
	 * The {@link #persistenceService} reference itself is intentionally not part of the memento (it changes only on
	 * store compaction, never inside a mutation).
	 *
	 * Nothing is copied: BOTH the dirty-index tracking and the trapped storage-part cache are rewound by replaying the
	 * journal down to the captured mark, so the memento is an `int` and a snapshot costs nothing that grows with the
	 * accumulated state. The {@link Index} values are shared by reference on purpose: their own transactional layers
	 * are snapshotted independently, so the memento only needs to remember *which* indexes were dirty, not their
	 * contents.
	 */
	@Nonnull
	@Override
	public DataStoreChangesMemento snapshot() {
		if (this.undoJournal == null) {
			this.undoJournal = new UndoJournal();
		}
		return new DataStoreChangesMemento(this.undoJournal.mark());
	}

	/**
	 * Restores the revertable in-memory state captured by {@link #snapshot()}, discarding any dirty-index tracking and
	 * trapped storage-part changes made since the snapshot was taken, by replaying the journal's inverse operations in
	 * strict reverse down to the captured mark. Because every one of them is an absolute restore of the slot its own
	 * mutation touched, restoring twice from the same memento yields the same state as restoring once — the second
	 * replay finds the journal already rewound to the mark and does nothing.
	 *
	 * @param memento the state previously captured by {@link #snapshot()}
	 */
	@Override
	public void restore(@Nonnull DataStoreChangesMemento memento) {
		UndoJournal.assertRestorable(this.undoJournal, memento.mark());
		if (this.undoJournal != null) {
			this.undoJournal.rollbackTo(memento.mark());
		}
	}

	/**
	 * Releases a closed savepoint's memento (see {@link Snapshotable#releaseMemento(Object)}) - on commit the changes
	 * are kept, so the journal entries recorded since the mark are discarded (never replayed). When the journal drains
	 * empty it is nulled out, restoring the allocation-free fast path for the rest of the transaction.
	 *
	 * @param memento the committed memento previously produced by {@link #snapshot()}
	 */
	@Override
	public void releaseMemento(@Nonnull DataStoreChangesMemento memento) {
		if (this.undoJournal != null) {
			this.undoJournal.releaseFrom(memento.mark());
			if (this.undoJournal.isEmpty()) {
				this.undoJournal = null;
			}
		}
	}

	/**
	 * Registers this layer with the warm-up savepoint bracketing the current root entity mutation, if one is open, so
	 * that a failed mutation can be reverted on the non-transactional bulk-indexing path (see {@link WarmUpSavepoint}).
	 * The registration is idempotent — only the first touch inside a savepoint captures a memento, every later one is
	 * an `O(1)` no-op.
	 *
	 * Must be called at the ENTRY of a mutating method, before it changes anything: the {@link #snapshot()} the first
	 * touch triggers is also what allocates {@link #undoJournal}, and the mutators' own journal pushes are silent until
	 * it exists.
	 *
	 * Called from every method that mutates the state {@link #snapshot()} captures — the dirty-index tracking and the
	 * trapped storage-part cache. Deliberately NOT called from {@link #setPersistenceService} (not part of the memento;
	 * it changes only on store compaction, never inside an entity mutation) nor from {@link #popTrappedUpdates()} (the
	 * flush, which runs between entity mutations and drains this layer wholesale rather than mutating it as part of
	 * one).
	 *
	 * On the transactional path this costs a single {@link ThreadLocal} read returning `null`: a warm-up savepoint is
	 * only ever opened when no transaction is active.
	 *
	 * The savepoint is handed back rather than kept private so that the two methods writing THROUGH this layer into the
	 * persistence service can journal their own record-level inverses off the same read — the pre-image of a stored
	 * record is not in-memory state and is therefore not covered by the memento this touch captures.
	 *
	 * @return the savepoint bracketing the current root entity mutation, or `null` when there is none
	 */
	@Nullable
	private WarmUpSavepoint recordWarmUpSavepointTouch() {
		final WarmUpSavepoint warmUpSavepoint = WarmUpSavepoint.getIfOpen();
		if (warmUpSavepoint != null) {
			warmUpSavepoint.recordFirstTouch(this);
		}
		return warmUpSavepoint;
	}

	/**
	 * Captures the pre-image of ONE `(container type, primary key)` record of the {@link #persistenceService} and
	 * pushes the inverse restoring it into the open warm-up savepoint. Must be called BEFORE the write it guards.
	 *
	 * **Why this is not part of {@link #snapshot()}.** The memento rewinds this layer's own in-memory state; the record
	 * this method guards lives in the store, and its pre-image can only be obtained by reading it. The read is
	 * therefore done here, per write, and the inverse goes straight into the savepoint's journal.
	 *
	 * The inverse is an ABSOLUTE restore either way — re-put what was there, or remove what was not — which is what
	 * makes several writes to the same record inside one savepoint replay correctly: under the journal's strict-reverse
	 * replay the earliest-pushed inverse for a record runs LAST and wins, so the record ends at its pre-savepoint
	 * value. Removing a record that was already absent is a no-op in the store, so the absent-pre-image inverse is
	 * total for the removal path as well as for the write path.
	 *
	 * **Cost.** Nothing at all when no savepoint is open — the caller has already established that from the single
	 * {@link ThreadLocal} read it needed anyway. With one open it is one storage read per direct write; for the bulk
	 * insert of a NEW entity every such read misses in the offset index and never deserializes anything, so the price
	 * of a full pre-image is paid only where an existing record is genuinely being overwritten.
	 *
	 * @param savepoint      the savepoint bracketing the current root entity mutation
	 * @param catalogVersion the catalog version the record is read from and restored into
	 * @param primaryKey     the primary key of the record about to change
	 * @param containerType  the storage-part type of the record about to change
	 */
	private void journalPersistedChange(
		@Nonnull WarmUpSavepoint savepoint,
		long catalogVersion,
		long primaryKey,
		@Nonnull Class<? extends StoragePart> containerType
	) {
		// deserializing a reference or price part resolves its schema from EntitySchemaContext, and THIS read happens
		// on the write/commit path, which - unlike EntityCollection's reader - establishes no such context. Without
		// the wrapper the read throws "Entity schema was not initialized in EntitySchemaContext!" for every entity
		// that carries references or prices, which is every non-trivial corpus.
		final Supplier<EntitySchema> schemaSupplier = this.entitySchemaSupplier;
		final StoragePart previous = schemaSupplier == null ?
			this.persistenceService.getStoragePart(catalogVersion, primaryKey, containerType) :
			EntitySchemaContext.executeWithSchemaContext(
				schemaSupplier.get(),
				() -> this.persistenceService.getStoragePart(catalogVersion, primaryKey, containerType)
			);
		if (previous == null) {
			savepoint.push(
				() -> this.persistenceService.removeStoragePart(catalogVersion, primaryKey, containerType)
			);
		} else {
			// the loaded part carries its primary key, so re-putting it files it back under the very same key
			savepoint.push(() -> this.persistenceService.putStoragePart(catalogVersion, previous));
		}
	}

	/**
	 * Records the inverse of a pending change to ONE `(container type, primary key)` slot of {@link #trappedChanges},
	 * so a savepoint rollback puts exactly that slot back. No-op unless a savepoint is open. Must be called BEFORE the
	 * change, and before the lazily-allocated containers the change may create are allocated.
	 *
	 * **Why per slot rather than a copy of the cache.** A first-touch copy of the map-of-maps is `O(accumulated trapped
	 * parts)` per entity, and warm-up traps parts continuously between flushes — so a copy taken once per entity
	 * mutation is the `O(N²)` rollback cliff the journal strategy exists to avoid, on the very path this mechanism was
	 * built for. The slot capture is `O(1)`.
	 *
	 * The inverse restores all three levels of lazy structure absolutely: the outer map's reference (which is `null`
	 * until the first part is trapped and is nulled again by every flush), the presence of the per-type inner map, and
	 * the slot's own presence and value. That is what lets several writes to the same type — or to a type first seen
	 * inside the savepoint — replay correctly newest-first, each undoing exactly what it created.
	 *
	 * @param containerType the storage-part type whose per-type cache is about to change
	 * @param primaryKey    the primary key whose slot is about to change
	 */
	private void journalTrappedChange(@Nonnull Class<? extends StoragePart> containerType, long primaryKey) {
		if (this.undoJournal == null) {
			return;
		}
		final Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> outer = this.trappedChanges;
		final LongObjectMap<StoragePart> inner = outer == null ? null : outer.get(containerType);
		final boolean present = inner != null && inner.containsKey(primaryKey);
		final StoragePart previous = present ? inner.get(primaryKey) : null;
		this.undoJournal.push(() -> {
			this.trappedChanges = outer;
			if (outer != null) {
				if (inner == null) {
					// the per-type cache did not exist before this write - drop the one the write created
					outer.remove(containerType);
				} else if (present) {
					inner.put(primaryKey, previous);
				} else {
					inner.remove(primaryKey);
				}
			}
		});
	}

	/**
	 * Returns set containing {@link StoragePartKey keys} that lead to the data structures in memory that were modified
	 * (are dirty) and needs to be persisted into the persistent storage. This is performance optimization that minimizes
	 * I/O operations for frequently changed data structures such as indexes and these are stored once in a while in
	 * the moments when it has a sense.
	 */
	@Nonnull
	public TrappedChanges popTrappedUpdates() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		final Map<IndexKey, Index<? extends IndexKey>> theDirtyEntityIndexes = this.dirtyEntityIndexes;
		// snapshot the dirty index keys before resetting, so the trunk merge (which runs AFTER this flush) can tell
		// which indexes genuinely changed and carry the rest across the catalog version by reference. Only a layer
		// whose merge actually prunes pays the copy - see capturesDirtyIndexKeys.
		if (this.capturesDirtyIndexKeys) {
			captureDirtyIndexKeys(theDirtyEntityIndexes.keySet());
		}
		this.dirtyEntityIndexes = new HashMap<>(64);
		this.dirtyEntityIndexesByPk = new IntObjectHashMap<>(64);

		final Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> theTrappedChanges = this.trappedChanges;
		this.trappedChanges = null;

		for (Index<? extends IndexKey> index : theDirtyEntityIndexes.values()) {
			index.getModifiedStorageParts(trappedChanges);
			// advance the index's change-detection baseline to the state we have just collected for this
			// commit (pop == committed); keeps getModifiedStorageParts a pure read while still closing the
			// warm-up -> transactional baseline-staleness gap on reused index instances
			index.notifyFlushed();
		}
		if (theTrappedChanges != null) {
			for (LongObjectMap<StoragePart> changesIndex : theTrappedChanges.values()) {
				final ObjectContainer<StoragePart> values = changesIndex.values();
				trappedChanges.addIterator(new LongObjectIterator<>(values.iterator()), values.size());
			}
		}

		// deferred removals staged by dropping whole indexes (sub-index roots + leaf pages) — resolved store-side at drain
		if (this.pendingIndexRemovalParts != null) {
			final List<StoragePart> removals = this.pendingIndexRemovalParts;
			this.pendingIndexRemovalParts = null;
			for (final StoragePart removal : removals) {
				trappedChanges.addChangeToStore(removal);
			}
		}

		return trappedChanges;
	}

	/**
	 * Records the keys of the indexes this flush persisted into {@link #lastCommittedDirtyIndexKeys}, marking the
	 * snapshot available for the trunk merge to consume.
	 *
	 * A second flush arriving before the merge consumed the first one UNIONS rather than replaces: by the time it runs
	 * {@link #dirtyEntityIndexes} has already been reset, so a plain replacement would narrow the snapshot to the keys
	 * of the second flush alone and the merge would then carry — and thereby orphan the diff layers of — every index
	 * the first flush had persisted.
	 *
	 * @param dirtyIndexKeys keys of the indexes that were dirty at this flush; only read, never retained
	 */
	private void captureDirtyIndexKeys(@Nonnull Set<IndexKey> dirtyIndexKeys) {
		if (!this.dirtyIndexKeysSnapshotAvailable) {
			this.lastCommittedDirtyIndexKeys = dirtyIndexKeys.isEmpty() ?
				Set.of() : new HashSet<>(dirtyIndexKeys);
			this.dirtyIndexKeysSnapshotAvailable = true;
		} else if (!dirtyIndexKeys.isEmpty()) {
			final Set<IndexKey> union = new HashSet<>(this.lastCommittedDirtyIndexKeys);
			union.addAll(dirtyIndexKeys);
			this.lastCommittedDirtyIndexKeys = union;
		}
	}

	/**
	 * Consumes the snapshot of {@link IndexKey}s that were dirty at the most recent {@link #popTrappedUpdates()} — i.e.
	 * the indexes changed by the transaction whose flush just ran. The commit-time trunk merge reads this to carry
	 * unchanged indexes across the catalog version by reference instead of rebuilding them. The returned set is empty
	 * when the transaction genuinely dirtied no index (a schema-only change, for instance).
	 *
	 * The read is ONE-SHOT and asserted: the snapshot must have been taken by a flush on this very diff layer. That
	 * guard is what makes the ordering coupling observable — an empty set cannot otherwise be told apart from a
	 * snapshot that was never taken, and pruning the merge against a missing snapshot would treat every index as
	 * unchanged and silently orphan the diff layers of the ones that did change.
	 *
	 * @return snapshot of this transaction's dirty index keys, never `null`
	 * @throws io.evitadb.exception.GenericEvitaInternalError when no flush has taken a snapshot on this layer, or when
	 *                                                        the snapshot was already consumed
	 */
	@Nonnull
	public Set<IndexKey> popLastCommittedDirtyIndexKeys() {
		Assert.isPremiseValid(
			this.dirtyIndexKeysSnapshotAvailable,
			"No dirty index key snapshot is available on this transactional layer - the commit-time flush must run " +
				"on it before the trunk merge consumes it, and exactly once per merge!"
		);
		final Set<IndexKey> snapshot = this.lastCommittedDirtyIndexKeys;
		this.lastCommittedDirtyIndexKeys = Set.of();
		this.dirtyIndexKeysSnapshotAvailable = false;
		return snapshot;
	}

	/**
	 * Returns a KeyCompressor that contains indexes of keys assigned to key-comparable objects which are expensive
	 * to store redundantly during serialization.
	 *
	 * @return a read-only KeyCompressor instance to be used for key compress.
	 */
	@Nonnull
	public KeyCompressor getReadOnlyKeyCompressor() {
		return this.persistenceService.getReadOnlyKeyCompressor();
	}

	/**
	 * Retrieves a storage part from the local trapped changes cache if available, otherwise fetches it from the persistence service.
	 *
	 * @param catalogVersion the current version of the catalog to read from
	 * @param primaryKey primary key of the storage part to retrieve
	 * @param containerType class type of the storage part container
	 * @param <T> type of the storage part container
	 * @return the storage part if found, otherwise null
	 */
	@Nullable
	public <T extends StoragePart> T getStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		if (this.trappedChanges != null) {
			final LongObjectMap<StoragePart> trappedChanges = this.trappedChanges.get(containerType);
			if (trappedChanges != null) {
				final StoragePart storagePart = trappedChanges.get(primaryKey);
				if (storagePart != null) {
					return storagePart instanceof RemovedStoragePart ?
						null :
						containerType.cast(storagePart);
				}
			}
		}
		return this.persistenceService.getStoragePart(catalogVersion, primaryKey, containerType);
	}

	/**
	 * Retrieves a storage part as a binary array. The storage part is first searched for in the local trapped changes
	 * cache. If found, it is serialized and returned unless it is a {@link RemovedStoragePart}; in which case, null is returned.
	 * If not found in the cache, it fetches the storage part from the persistence service and returns it as a binary array.
	 *
	 * @param catalogVersion the current version of the catalog to read from
	 * @param primaryKey primary key of the storage part to retrieve
	 * @param containerType class type of the storage part container
	 * @param <T> type of the storage part container
	 * @return byte array representing the storage part if found, otherwise null
	 */
	@Nullable
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		if (this.trappedChanges != null) {
			final LongObjectMap<StoragePart> trappedChanges = this.trappedChanges.get(containerType);
			if (trappedChanges != null) {
				final StoragePart storagePart = trappedChanges.get(primaryKey);
				if (storagePart != null) {
					return storagePart instanceof RemovedStoragePart ?
						null :
						this.persistenceService.serializeStoragePart(storagePart);
				}
			}
		}
		return this.persistenceService.getStoragePartAsBinary(catalogVersion, primaryKey, containerType);
	}

	/**
	 * Removes a storage part identified by the given catalog version, primary key, and entity class.
	 *
	 * Both halves of what this changes are revertable by an open warm-up savepoint: the trapped-cache slot through the
	 * layer's own journal, and the stored record through the record-level inverse pushed into the savepoint (see
	 * {@link #journalPersistedChange}).
	 *
	 * @param catalogVersion the version of the catalog to modify
	 * @param primaryKey the primary key of the storage part to remove
	 * @param entityClass the class type of the storage part to remove
	 * @param <T> the type of the storage part
	 * @return true if the storage part was successfully removed, false otherwise
	 */
	public <T extends StoragePart> boolean removeStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		final WarmUpSavepoint savepoint = recordWarmUpSavepointTouch();
		if (this.trappedChanges != null) {
			final LongObjectMap<StoragePart> containerChanges = this.trappedChanges.get(entityClass);
			if (containerChanges != null) {
				journalTrappedChange(entityClass, primaryKey);
				containerChanges.remove(primaryKey);
			}
		}
		if (savepoint != null) {
			journalPersistedChange(savepoint, catalogVersion, primaryKey, entityClass);
		}
		return this.persistenceService.removeStoragePart(catalogVersion, primaryKey, entityClass);
	}

	public <T extends StoragePart> boolean trapRemoveStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		recordWarmUpSavepointTouch();
		journalTrappedChange(entityClass, primaryKey);
		this.trappedChanges = this.trappedChanges == null ? new HashMap<>(64) : this.trappedChanges;
		if (this.persistenceService.containsStoragePart(catalogVersion, primaryKey, entityClass)) {
			this.trappedChanges.computeIfAbsent(entityClass, aClass -> new LongObjectHashMap<>(256))
				.put(
					primaryKey,
					new RemovedStoragePart(entityClass, primaryKey)
				);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Stores the provided storage part and manages any trapped changes related to it.
	 *
	 * Both halves of what this changes are revertable by an open warm-up savepoint: the trapped-cache slot through the
	 * layer's own journal, and the stored record through the record-level inverse pushed into the savepoint (see
	 * {@link #journalPersistedChange}).
	 *
	 * @param catalogVersion the current version of the catalog to write to
	 * @param value the storage part to store, must not be null
	 * @param <T> the type of the storage part
	 */
	public <T extends StoragePart> void putStoragePart(long catalogVersion, @Nonnull T value) {
		final WarmUpSavepoint savepoint = recordWarmUpSavepointTouch();
		if (this.trappedChanges != null) {
			final LongObjectMap<StoragePart> containerChanges = this.trappedChanges.get(value.getClass());
			if (containerChanges != null) {
				// the primary key is resolved only inside this branch, because a part the cache never held is not
				// required to have one and asking for it would throw
				final long primaryKey = value.getStoragePartPKOrElseThrowException();
				journalTrappedChange(value.getClass(), primaryKey);
				containerChanges.remove(primaryKey);
			}
		}
		if (savepoint != null) {
			final Class<? extends StoragePart> containerType = value.getClass();
			final Long assignedPrimaryKey = value.getStoragePartPK();
			if (assignedPrimaryKey == null) {
				// A part whose primary key is still unassigned has never been read out of the store - on this write
				// path every such instance is the fallback constructed after a storage miss - so its pre-image is
				// "absent" by construction and the inverse is to drop whatever record the write files. The key it
				// gets filed under is computed INSIDE the write, by
				// StoragePart#computeUniquePartIdAndSet, which sets it on this very instance; the inverse therefore
				// reads it off the part at rollback time rather than closing over a value that does not exist yet.
				// A write that failed before assigning one has filed nothing and leaves the inverse a no-op
				savepoint.push(() -> {
					final Long createdPrimaryKey = value.getStoragePartPK();
					if (createdPrimaryKey != null) {
						this.persistenceService.removeStoragePart(catalogVersion, createdPrimaryKey, containerType);
					}
				});
			} else {
				journalPersistedChange(savepoint, catalogVersion, assignedPrimaryKey, containerType);
			}
		}
		this.persistenceService.putStoragePart(catalogVersion, value);
	}

	/**
	 * Adds the specified storage part to the local trapped changes cache.
	 *
	 * @param <T> the type of the storage part
	 * @param value the storage part to be added, must not be null
	 */
	public <T extends StoragePart> void trapPutStoragePart(@Nonnull T value) {
		recordWarmUpSavepointTouch();
		final long storagePartPK = value.getStoragePartPKOrElseThrowException();
		final Class<? extends StoragePart> containerType = value.getClass();
		journalTrappedChange(containerType, storagePartPK);
		this.trappedChanges = this.trappedChanges == null ? new HashMap<>(64) : this.trappedChanges;
		this.trappedChanges.computeIfAbsent(containerType, aClass -> new LongObjectHashMap<>(256))
			.put(storagePartPK, value);
	}

	/**
	 * Counts the total number of storage parts of a specific type in a catalog version,
	 * accounting for trapped changes such as insertions and removals.
	 *
	 * @param catalogVersion the version of the catalog to count storage parts from
	 * @param containerType the class type of the storage part containers to count
	 * @return the total number of storage parts, adjusted for trapped changes
	 */
	public int countStorageParts(long catalogVersion, Class<? extends StoragePart> containerType) {
		final int storedCount = this.persistenceService.countStorageParts(catalogVersion, containerType);
		if (this.trappedChanges == null || this.trappedChanges.isEmpty()) {
			return storedCount;
		} else {
			final LongObjectMap<StoragePart> trappedChanges = this.trappedChanges.get(containerType);
			if (trappedChanges == null) {
				return storedCount;
			} else {
				int inserts = 0;
				int removals = 0;
				for (LongObjectCursor<StoragePart> trappedChange : trappedChanges) {
					if (trappedChange.value instanceof RemovedStoragePart) {
						removals++;
					} else if (!this.persistenceService.containsStoragePart(catalogVersion, trappedChange.key, containerType)) {
						inserts++;
					}
				}
				return storedCount + inserts - removals;
			}
		}
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` lambda and stores into the "dirty" memory before returning.
	 */
	@Nonnull
	public <IK extends IndexKey, I extends Index<IK>> I getOrCreateIndexForModification(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		//noinspection unchecked
		final I existingIndex = (I) this.dirtyEntityIndexes.get(indexKey);
		if (existingIndex != null) {
			// the index is already registered dirty - this call mutates nothing, so nothing has to be captured
			return existingIndex;
		}
		recordWarmUpSavepointTouch();
		final I createdIndex = accessorWhenMissing.apply(indexKey);
		Assert.isPremiseValid(
			createdIndex != null,
			() -> "Index for key " + indexKey + " was not found in the persistent storage and cannot be registered for modification."
		);
		if (this.undoJournal != null) {
			// capture the exact pre-mutation state of both maps ABSOLUTELY: the by-pk entry may already exist even
			// though the by-key one does not (removeIndex drops only the by-key entry), and the put below overwrites
			// it - a plain by-pk remove on rollback would then lose the pre-savepoint entry
			final boolean touchesByPk = createdIndex instanceof EntityIndex;
			final int pk = createdIndex instanceof EntityIndex entityIndex
				? entityIndex.getPrimaryKey()
				: Integer.MIN_VALUE;
			final Index<? extends IndexKey> previousByPk = touchesByPk
				? this.dirtyEntityIndexesByPk.get(pk)
				: null;
			this.undoJournal.push(() -> {
				this.dirtyEntityIndexes.remove(indexKey);
				if (touchesByPk) {
					if (previousByPk == null) {
						this.dirtyEntityIndexesByPk.remove(pk);
					} else {
						this.dirtyEntityIndexesByPk.put(pk, previousByPk);
					}
				}
			});
		}
		this.dirtyEntityIndexes.put(indexKey, createdIndex);
		if (createdIndex instanceof EntityIndex entityIndex) {
			this.dirtyEntityIndexesByPk.put(entityIndex.getPrimaryKey(), entityIndex);
		}
		return createdIndex;
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory by its storage primary key.
	 * If it isn't there, it's fetched using `accessorWhenMissing` and — unlike
	 * {@link #getIndexIfExists(int, IntFunction)} — stored into the "dirty" memory before returning.
	 * This ensures that any subsequent modifications to the returned index will be captured by
	 * {@link #popTrappedUpdates()}.
	 */
	@Nonnull
	public <IK extends IndexKey, I extends Index<IK>> I getIndexForModification(int indexPrimaryKey, @Nonnull IntFunction<I> accessorWhenMissing) {
		//noinspection unchecked
		final I existing = (I) this.dirtyEntityIndexesByPk.get(indexPrimaryKey);
		if (existing != null) {
			// the index is already registered dirty - this call mutates nothing, so nothing has to be captured
			return existing;
		}
		recordWarmUpSavepointTouch();
		final I index = accessorWhenMissing.apply(indexPrimaryKey);
		Assert.isPremiseValid(
			index != null,
			() -> "Index with primary key " + indexPrimaryKey + " was not found in the persistent storage and cannot be registered for modification."
		);
		final IndexKey addedKey = index.getIndexKey();
		if (this.undoJournal != null) {
			// capture the exact pre-mutation entries of both maps for the keys this put touches, so a savepoint rollback
			// restores them absolutely (handles the rare case where a mapping already existed under either key)
			final Index<? extends IndexKey> previousByKey = this.dirtyEntityIndexes.get(addedKey);
			final int pk = index instanceof EntityIndex entityIndex ? entityIndex.getPrimaryKey() : Integer.MIN_VALUE;
			final boolean touchesByPk = index instanceof EntityIndex;
			final Index<? extends IndexKey> previousByPk = touchesByPk ? this.dirtyEntityIndexesByPk.get(pk) : null;
			this.undoJournal.push(() -> {
				if (previousByKey == null) {
					this.dirtyEntityIndexes.remove(addedKey);
				} else {
					this.dirtyEntityIndexes.put(addedKey, previousByKey);
				}
				if (touchesByPk) {
					if (previousByPk == null) {
						this.dirtyEntityIndexesByPk.remove(pk);
					} else {
						this.dirtyEntityIndexesByPk.put(pk, previousByPk);
					}
				}
			});
		}
		this.dirtyEntityIndexes.put(addedKey, index);
		if (index instanceof EntityIndex entityIndex) {
			this.dirtyEntityIndexesByPk.put(entityIndex.getPrimaryKey(), index);
		}
		return index;
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` and returned without adding to "dirty" memory.
	 */
	@Nullable
	public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		//noinspection unchecked
		return ofNullable((I) this.dirtyEntityIndexes.get(indexKey))
			.orElseGet(() -> accessorWhenMissing.apply(indexKey));
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` and returned without adding to "dirty" memory.
	 */
	@Nullable
	public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(int indexPrimaryKey, @Nonnull IntFunction<I> accessorWhenMissing) {
		//noinspection unchecked
		return ofNullable((I) this.dirtyEntityIndexesByPk.get(indexPrimaryKey))
			.orElseGet(() -> accessorWhenMissing.apply(indexPrimaryKey));
	}

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 *
	 * @return the removed index — the dirty one when it was in the change set, otherwise whatever
	 * `removalPropagation` yielded from the origin collection; `null` when the index was found in neither, which
	 * callers must treat as "nothing was removed"
	 */
	@Nullable
	public <IK extends IndexKey, I extends Index<IK>> I removeIndex(
		long catalogVersion,
		@Nonnull IK entityIndexKey,
		@Nonnull Function<IK, I> removalPropagation
	) {
		recordWarmUpSavepointTouch();
		//noinspection unchecked
		final I dirtyIndexesRemoval = (I) this.dirtyEntityIndexes.remove(entityIndexKey);
		if (dirtyIndexesRemoval != null && this.undoJournal != null) {
			// removeIndex only drops the by-key entry (never the by-pk one); a savepoint rollback re-inserts exactly it.
			// Re-inserting the SAME instance is what keeps the reclaim symmetric on rollback: the reclaim baselines
			// (the persisted leaf-page sets and the `original*` manifest sets) live on the index object itself, and the
			// surrounding diff-layer savepoint reverts that object's content in lockstep — so a rolled-back drop rewinds
			// index state and reclaim state together, and the next flush diffs against the pre-drop baseline.
			this.undoJournal.push(() -> this.dirtyEntityIndexes.put(entityIndexKey, dirtyIndexesRemoval));
		}
		final I baseIndexesRemoval = removalPropagation.apply(entityIndexKey);
		final I removed = ofNullable(dirtyIndexesRemoval).orElse(baseIndexesRemoval);
		if (removed instanceof EntityIndex entityIndex) {
			// The append-only store reclaims a record only when it is superseded or explicitly removed. A dropped
			// index is never re-flushed, so its manifest, membership bitmaps, every sub-index root and every leaf
			// page it persisted would be copied forward by every compaction forever. Emit the removals here, while
			// the index's persisted baselines are still intact and before the caller discards its transactional
			// layers.
			reclaimRemovedIndexFootprint(catalogVersion, entityIndex);
		} else if (removed != null) {
			// every present-day caller drops an EntityIndex; any other index type would silently reintroduce the
			// permanent-orphan leak the branch above exists to prevent, so it must be taught to reclaim its own
			// persisted footprint before it may be routed here
			throw new GenericEvitaInternalError(
				"Removal of index type `" + removed.getClass().getName() + "` does not reclaim its persisted " +
					"footprint - implement the reclaim before removing indexes of this type!"
			);
		}
		return removed;
	}

	/**
	 * Emits the removal instructions that reclaim, from the append-only storage, the complete persisted footprint of a
	 * dropped {@link EntityIndex}. The sub-index roots and leaf pages are {@link DeferredRemovalStoragePart}s
	 * (store-side primary-key resolution) collected into {@link #pendingIndexRemovalParts}; the manifest and membership
	 * bitmaps are plain primary-key removals routed through {@link #trapRemoveStoragePart} (which self-skips a
	 * never-persisted part via its {@code containsStoragePart} gate and is reverted by a savepoint rollback).
	 *
	 * @param catalogVersion the version whose existence view gates the manifest/bitmaps removal
	 * @param entityIndex    the index being dropped
	 */
	private void reclaimRemovedIndexFootprint(long catalogVersion, @Nonnull EntityIndex entityIndex) {
		final TrappedChanges footprint = new TrappedChanges();
		entityIndex.emitFootprintRemovals(footprint);
		final int count = footprint.getTrappedChangesCount();
		if (count > 0) {
			if (this.pendingIndexRemovalParts == null) {
				this.pendingIndexRemovalParts = new ArrayList<>(count);
			}
			final List<StoragePart> pending = this.pendingIndexRemovalParts;
			final int mark = pending.size();
			final Iterator<StoragePart> it = footprint.getTrappedChangesIterator();
			while (it.hasNext()) {
				pending.add(it.next());
			}
			if (this.undoJournal != null) {
				// a savepoint rollback must drop exactly the removals this drop staged (mirrors the dirty-index reinstate)
				this.undoJournal.push(() -> pending.subList(mark, pending.size()).clear());
			}
		}
		final long indexPrimaryKey = entityIndex.getPrimaryKey();
		trapRemoveStoragePart(catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class);
		trapRemoveStoragePart(catalogVersion, indexPrimaryKey, EntityIdsStoragePart.class);
	}

	/**
	 * RemovedStoragePart is a specific implementation of the StoragePart interface which represents a part of storage
	 * that should be removed.
	 *
	 * @param containerType the type of the container that was removed
	 * @param storagePartPK the primary key of the storage part that was removed
	 */
	public record RemovedStoragePart(
		@Nonnull Class<? extends StoragePart> containerType,
		long storagePartPK
	) implements StoragePart {
		@Serial private static final long serialVersionUID = -3939591252705809288L;

		@Nonnull
		@Override
		public Long getStoragePartPK() {
			return this.storagePartPK;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.storagePartPK;
		}
	}

	/**
	 * An iterator implementation for iterating over elements of type T, backed by an iterator
	 * of {@link ObjectCursor} objects for efficient traversal.
	 *
	 * @param <T> the type of elements returned by this iterator
	 */
	@RequiredArgsConstructor
	private static class LongObjectIterator<T> implements Iterator<T> {
		private final Iterator<ObjectCursor<T>> iterator;

		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}

		@Override
		public T next() {
			return this.iterator.next().value;
		}

	}

	/**
	 * Immutable, `O(1)` marker of the revertable in-memory state of {@link DataStoreChanges} at a single point in time,
	 * captured by {@link #snapshot()} and reinstated by {@link #restore(DataStoreChangesMemento)} on a per-entity
	 * savepoint rollback. Both the dirty-index maps and the trapped storage-part cache are rewound via the
	 * {@link #undoJournal}, so the memento carries nothing but the position to rewind to. See {@link #snapshot()} for
	 * what is and is not captured.
	 *
	 * @param mark the {@link UndoJournal#mark()} to rewind this layer to on restore
	 */
	public record DataStoreChangesMemento(
		int mark
	) {
	}
}
