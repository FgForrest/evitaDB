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

package io.evitadb.spi.store.catalog.persistence.storageParts.compressor;

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This implementation of {@link KeyCompressor} is used for accessing and creating new mappings between keys and integer
 * ids that are used in persisted (serialized) form to minimize space occupied by the evitaDB records.
 *
 * **This class is deliberately not annotated `@ThreadSafe`, because it is not thread-safe on its own.** It is only
 * as safe as the discipline of the class that owns it, and that discipline lives in `OffsetIndex`. Splitting the API
 * by who guarantees what:
 *
 * - **Self-synchronized.** {@link #getId(Comparable)}, {@link #getAtomicSnapshot()} and
 *   {@link #executeWithWriteAccess(Supplier)} coordinate through the internal {@link ReentrantReadWriteLock} and need
 *   no external locking. In particular a snapshot reader never observes the backing map mid-mutation.
 * - **Caller-guaranteed.** {@link #getKeys()}, {@link #getIdIfExists(Comparable)}, {@link #getKeyForId(int)} and
 *   {@link #getKeyForIdIfExists(int)} touch the backing `HashMap`s with no lock at all. They are safe only while no
 *   writer can be minting concurrently. A `HashMap.get` racing an insert that resizes may report a key that is
 *   present as missing, which surfaces either as a spurious `There is no key for id N!` or - through
 *   `AggregatedKeyCompressor`, which falls through to the next compressor on an empty result - as a silently wrong
 *   id.
 *
 * Both halves of that bargain are currently honoured, which is why the caller-guaranteed methods can stay lock-free
 * on what is a hot path:
 *
 * - every mutation originates inside an {@link #executeWithWriteAccess(Supplier)} session opened by `OffsetIndex`
 *   (`put` and `copySnapshotTo`), so `getId(...)` bursts driven by Kryo serialization are reentrant on the write
 *   lock and pay only a holdCount bump rather than a full acquire;
 * - every reader reaches the lock-free methods through `ReadOnlyKeyCompressorView`, which
 *   `OffsetIndex.getReadOnlyKeyCompressor()` hands out, and every such reader today runs either while the owning
 *   `OffsetIndex` has no writer at all (catalog and entity-index loading, which happens once during the
 *   load-from-disk constructors and has no lazy query-time counterpart) or on the writing thread itself, between
 *   its own `putStoragePart` calls (the `DeferredRemovalStoragePart` resolution in the flush drains).
 *
 * Nothing enforces the second bullet. Handing the view to a path that can run alongside a mint - a query-time index
 * materialization, say - reintroduces the race silently, and the switch to `ConcurrentHashMap` becomes necessary at
 * that point. It is deliberately not made pre-emptively, because lock-free reads on unsynchronized `HashMap`s are
 * what makes these methods cheap today.
 *
 * @see ReadOnlyKeyCompressorView the read-only wrapper that delegates all four caller-guaranteed methods straight
 * through to this class
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReadWriteKeyCompressor implements KeyCompressor {
	@Serial private static final long serialVersionUID = -791089303429347949L;

	/**
	 * Contains key index extracted from {@link KeyCompressor} that is necessary for
	 * bootstraping {@link KeyCompressor} used for file offset index deserialization.
	 */
	@Getter private final Map<Integer, Object> idToKeyIndex;
	/**
	 * Reverse lookup index to {@link #idToKeyIndex}
	 */
	private final Map<Object, Integer> keyToIdIndex;
	/**
	 * Sequence used for generating new monotonic ids for registered keys.
	 */
	private final AtomicInteger sequence;
	/**
	 * Contains TRUE when there are new keys registered in this instance.
	 */
	private final AtomicBoolean dirty = new AtomicBoolean();
	/**
	 * Lazily-built immutable view of {@link #idToKeyIndex} served by {@link #getAtomicSnapshot()}.
	 *
	 * The write compressor is typically quiescent after the initial schema / content phase, so the
	 * snapshot is built once and reused across many calls until the next mutation. Each new key
	 * inserted by {@link #getId(Comparable)} clears this field; the next snapshot reader rebuilds
	 * it via `Map.copyOf`. Cheap consumers that don't retain the map past their lock window should
	 * call {@link #getKeys()} instead and pay zero allocation.
	 *
	 * `volatile` provides the publish/visibility guarantee for the snapshot reference — readers
	 * observing a non-null reference are guaranteed to see a fully-initialized immutable map
	 * (immutables provide their own safe publication, but the field reference itself still needs
	 * visibility).
	 */
	@Nullable private volatile Map<Integer, Object> immutableKeysSnapshot;
	/**
	 * Coordinates snapshot readers with writers. Snapshot iteration over the underlying `HashMap`
	 * would race against a concurrent `getId(...)` mutation and throw
	 * `ConcurrentModificationException`; the read/write lock prevents that while still allowing
	 * many readers to inspect a quiescent compressor concurrently.
	 */
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	public ReadWriteKeyCompressor(@Nonnull Map<Integer, Object> keys) {
		int peek = 0;
		this.idToKeyIndex = createHashMap(Math.min(256, keys.size()));
		this.keyToIdIndex = createHashMap(Math.min(256, keys.size()));
		for (Entry<Integer, Object> entry : keys.entrySet()) {
			this.idToKeyIndex.put(entry.getKey(), entry.getValue());
			this.keyToIdIndex.put(entry.getValue(), entry.getKey());
			if (entry.getKey() > peek) {
				peek = entry.getKey();
			}
		}
		this.sequence = new AtomicInteger(peek);
	}

	/**
	 * Method returns TRUE if there were any changes in this instance since last reset or creation.
	 */
	public boolean resetDirtyFlag() {
		return this.dirty.getAndSet(false);
	}

	/**
	 * Returns the highest id ever assigned by this compressor. Because ids are allocated from a monotonic
	 * sequence and keys are append-only, this value equals `max(idToKeyIndex.keySet())` without iterating the map.
	 */
	public int getPeakId() {
		return this.sequence.get();
	}

	/**
	 * Returns the **live** id → key index. Cheap (no allocation) but unsafe to retain past the
	 * caller's lock window: any subsequent {@link #getId(Comparable)} that inserts a new entry
	 * mutates the underlying `HashMap`, and an in-flight iterator would throw
	 * `ConcurrentModificationException`.
	 *
	 * Consumers that hand the result off to a longer-lived structure (e.g. a `CatalogHeader`
	 * persisted via Kryo at some later point, or a seed for a sibling compressor whose iteration
	 * runs after the source lock is released) MUST use {@link #getAtomicSnapshot()} instead, which
	 * returns a memoized immutable copy paired with the current `peakId`.
	 *
	 * Callers that are safe with the live view:
	 *
	 * - reads inside {@link #executeWithWriteAccess(Supplier)} whose downstream iteration finishes
	 *   on the same thread before the session closes (e.g. `new ReadWriteKeyCompressor(seed)`
	 *   inside an `OffsetIndexDescriptor` constructor called under such a session);
	 * - `.size()`-only reads and similar non-iterating peeks;
	 * - serializers that iterate the map inside a write session and do not call back into
	 *   {@link #getId(Comparable)} mid-iteration (verified for `CatalogHeader` /
	 *   `EntityCollectionHeader` headers, whose value-key serializers do not register new keys).
	 */
	@Override
	public @Nonnull
	Map<Integer, Object> getKeys() {
		return this.idToKeyIndex;
	}

	/**
	 * Returns the {@link KeyCompressorSnapshot} pair (memoized keys + current `peakId`) captured
	 * **atomically** under the internal read lock. Required by callers seeding a sibling compressor
	 * (e.g. transactional-layer bootstrap from the trunk) where the new compressor's monotonic
	 * sequence must start strictly above every id in the seed map — reading the two fields without
	 * coordination could leave the new compressor with a sequence that collides with already-seeded
	 * ids.
	 */
	@Nonnull
	public KeyCompressorSnapshot getAtomicSnapshot() {
		this.lock.readLock().lock();
		try {
			Map<Integer, Object> snapshot = this.immutableKeysSnapshot;
			if (snapshot == null) {
				snapshot = Map.copyOf(this.idToKeyIndex);
				this.immutableKeysSnapshot = snapshot;
			}
			return new KeyCompressorSnapshot(snapshot, this.sequence.get());
		} finally {
			this.lock.readLock().unlock();
		}
	}

	/**
	 * Executes `action` while holding the internal write lock. Use this to amortize the
	 * acquisition cost across a burst of {@link #getId(Comparable)} calls — `getId` is reentrant
	 * on the write lock, so calls inside the action pay only a holdCount bump (~5 ns) instead of
	 * a full acquire/release pair (~30-50 ns).
	 *
	 * Outside a session, individual `getId` calls still acquire the write lock per-call; the
	 * session is a pure perf optimization, not a correctness requirement.
	 *
	 * @param action work that may issue many `getId(...)` calls
	 * @param <R>    return type of the action
	 * @return value produced by the action
	 */
	public <R> R executeWithWriteAccess(@Nonnull Supplier<R> action) {
		this.lock.writeLock().lock();
		try {
			return action.get();
		} finally {
			this.lock.writeLock().unlock();
		}
	}

	@Override
	public <T extends Comparable<T>> int getId(@Nonnull T key) {
		this.lock.writeLock().lock();
		try {
			return this.keyToIdIndex.computeIfAbsent(key, o -> {
				Assert.isPremiseValid(
					!(key instanceof String),
					"String keys are not supported by ReadWriteKeyCompressor! Always use specialized classes to avoid conflicts!"
				);
				final int id = this.sequence.incrementAndGet();
				this.idToKeyIndex.put(id, o);
				// invalidate the memoized snapshot — the next snapshot reader will rebuild
				this.immutableKeysSnapshot = null;
				this.dirty.compareAndSet(false, true);
				return id;
			});
		} finally {
			this.lock.writeLock().unlock();
		}
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> OptionalInt getIdIfExists(@Nonnull T key) {
		return Optional.ofNullable(this.keyToIdIndex.get(key))
			.map(OptionalInt::of)
			.orElseGet(OptionalInt::empty);
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> T getKeyForId(int id) {
		final Object key = this.idToKeyIndex.get(id);
		Assert.isPremiseValid(key != null, () -> missingKeyDiagnostic(id));
		//noinspection unchecked
		return (T) key;
	}

	/**
	 * Builds a diagnostic message for a missing id that includes the current compressor state. A miss here is
	 * especially suspicious because ids are assigned via a monotonic sequence and never removed, so either the
	 * caller is asking for an id that was never assigned, or the underlying map has been corrupted.
	 */
	@Nonnull
	private String missingKeyDiagnostic(int id) {
		return "There is no key for id " + id + "! Compressor size=" + this.idToKeyIndex.size() +
			", peak=" + this.sequence.get();
	}

	@Nullable
	@Override
	public <T extends Comparable<T>> T getKeyForIdIfExists(int id) {
		final Object key = this.idToKeyIndex.get(id);
		//noinspection unchecked
		return (T) key;
	}
}
