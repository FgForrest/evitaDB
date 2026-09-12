/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.spi.store.catalog.persistence.ReferenceNameFilterContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.CollectionUtils;

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Thread bound scope of a single read oriented operation: it de-duplicates storage record reads and adds up
 * what those reads cost.
 *
 * A single query reaches the same storage record repeatedly: an entity referenced from two different reference
 * chains is composed once per chain, and each composition re-reads and re-deserializes the very same body,
 * attribute and reference records. Because the whole execution runs against one pinned catalog version, a record
 * read under a given key cannot change while the query runs - so the second read is guaranteed to produce an equal
 * object and is pure waste.
 *
 * The scope is bound to the executing thread for the duration of the operation and discarded with it, so nothing
 * survives it: this is a de-duplicator, not a cache in the {@link io.evitadb.core.cache} sense, and it deliberately
 * holds no eviction policy beyond {@link #MAX_RECORDS}.
 *
 * It is also where the I/O statistics of the operation are accumulated. Those numbers used to be attributed to the
 * individual decorators the fetch pipeline produced and then reconstructed by walking the reference graph of every
 * returned entity - a walk that had to materialize each entity's filtered reference set to find anything to add up,
 * which cost far more than the addition. Counting them here, at the point where the read actually happens, makes
 * the aggregate two primitive field reads and no traversal at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class StorageAccessScope implements Closeable {
	/**
	 * Upper bound on the number of records held. The records cached here are the raw ingredients of entities the
	 * pipeline is composing anyway, so the cache roughly doubles their footprint for the duration of the query -
	 * acceptable for the page-sized fetches this optimizes, not acceptable without a ceiling for a query that
	 * fetches hundreds of thousands of entities. Once the ceiling is reached the cache stops accepting new records
	 * and degrades to the uncached behaviour, still serving what it already holds.
	 */
	private static final int MAX_RECORDS = 65_536;
	/**
	 * Marker stored for keys that are known NOT to exist, so a missing record is not looked up twice either.
	 */
	private static final Object MISSING = new Object();
	/**
	 * Cache bound to the thread that executes the query. Query execution is single threaded (the fetch pipeline
	 * performs no fan-out), and the read path reaches the storage through a collection-scoped
	 * {@code DataStoreReaderBridge} that has no access to the query context - a scoped binding is what connects
	 * the two without threading a parameter through every fetch signature.
	 */
	private static final ThreadLocal<StorageAccessScope> CURRENT = new ThreadLocal<>();

	private final Map<RecordKey, Object> records = CollectionUtils.createHashMap(512);
	/**
	 * The record the most recent {@link #fetch} answered from {@link #records} instead of going to the storage,
	 * awaiting the {@link #noteRecordRead} the caller issues for it - see there for why this single slot is all the
	 * accounting needs, and why an identity set of everything ever read would defeat {@link #MAX_RECORDS}.
	 *
	 * It never retains anything the scope is not already holding: the record it points at is, by construction, one
	 * {@link #records} has cached.
	 */
	@Nullable private Object recordServedFromScope;
	/**
	 * Number of storage records read within this scope.
	 */
	@Getter private int ioFetchCount;
	/**
	 * Number of Bytes those records occupied, including their record framing overhead.
	 */
	@Getter private int ioFetchedBytes;
	/**
	 * Number of nested scopes that joined this scope; only the outermost one removes it from the thread.
	 */
	private int nesting;

	/**
	 * Binds a cache to the current thread, or joins the one already bound.
	 *
	 * @return the cache to be closed when the scope ends
	 */
	@Nonnull
	public static StorageAccessScope install() {
		final StorageAccessScope existing = CURRENT.get();
		if (existing == null) {
			final StorageAccessScope created = new StorageAccessScope();
			CURRENT.set(created);
			return created;
		} else {
			existing.nesting++;
			return existing;
		}
	}

	/**
	 * Returns the cache bound to the current thread, if any.
	 *
	 * @return the active cache or NULL when the caller is not inside a query execution
	 */
	@Nullable
	public static StorageAccessScope getIfActive() {
		return CURRENT.get();
	}

	private StorageAccessScope() {
	}

	/**
	 * Returns the record for the passed key, loading it through `loader` on the first ask.
	 *
	 * @param owner          the reader the record belongs to - records of different collections share container types
	 *                       and primary keys, so the reader identity is part of the key
	 * @param catalogVersion version of the catalog the record is read at
	 * @param containerType  type of the requested storage part
	 * @param primaryKey     numeric key of the record, or {@link Long#MIN_VALUE} when addressed by `originalKey`
	 * @param originalKey    non-numeric key of the record, NULL when addressed by `primaryKey`
	 * @param loader         performs the actual read on a cache miss
	 * @return the loaded record, may be NULL when no such record exists
	 */
	@Nullable
	public <T extends StoragePart> T fetch(
		@Nonnull Object owner,
		long catalogVersion,
		@Nonnull Class<T> containerType,
		long primaryKey,
		@Nullable Object originalKey,
		@Nonnull Supplier<T> loader
	) {
		// a record read under a reference name filter holds only the references of those names, so it may only be
		// served back to a read that asks for the same ones - the filter is part of the record's identity, not of
		// the way it was obtained (see ReferenceNameFilterContext). Reads of every other container type run with no
		// filter bound and are therefore keyed exactly as before.
		final RecordKey key = new RecordKey(
			owner, catalogVersion, containerType, primaryKey, originalKey,
			ReferenceNameFilterContext.getReferenceNameFilter()
		);
		final Object cached = this.records.get(key);
		if (cached != null) {
			// this answer costs no I/O - remember it so the note the caller is about to make for it is not billed
			this.recordServedFromScope = cached == MISSING ? null : cached;
			//noinspection unchecked
			return cached == MISSING ? null : (T) cached;
		}
		// everything from here on is a genuine read, and so is anything noted before the next fetch answers from
		// the cache again
		this.recordServedFromScope = null;
		final T loaded = loader.get();
		if (this.records.size() < MAX_RECORDS) {
			this.records.put(key, loaded == null ? MISSING : loaded);
		}
		return loaded;
	}

	/**
	 * Records that `record` has been obtained, at the given size. Called from the layer that performs the read and
	 * knows the record framing overhead; a no-op when no scope is bound to the current thread.
	 *
	 * Only reads that actually went to the storage are counted, and the decision is made where the read happens:
	 * {@link #fetch} knows whether it had to call its loader, and a record it answered from {@link #records its own
	 * cache} is parked in {@link #recordServedFromScope} for exactly this call to recognise and skip. Reads that
	 * never pass through the scope at all - binary fetches and reads issued with no scope bound - are physical by
	 * construction and are counted as they come.
	 *
	 * Deciding it here rather than remembering every record ever accounted for is what makes {@link #MAX_RECORDS}
	 * mean something: an identity set of the latter kind grows without a ceiling and pins every storage part and
	 * every raw byte array the query ever touched for the whole execution, which is the exact footprint the ceiling
	 * on {@link #records} exists to bound.
	 *
	 * @param record      the record that was obtained
	 * @param sizeInBytes size it occupied in the storage, including its framing overhead
	 */
	public static void noteRecordRead(@Nonnull Object record, int sizeInBytes) {
		final StorageAccessScope scope = CURRENT.get();
		if (scope == null) {
			return;
		}
		if (scope.recordServedFromScope == record) {
			// the scope answered this one itself - no I/O happened, and the next ask has to be judged on its own
			scope.recordServedFromScope = null;
			return;
		}
		scope.ioFetchCount++;
		scope.ioFetchedBytes += sizeInBytes;
	}

	@Override
	public void close() {
		if (this.nesting > 0) {
			this.nesting--;
		} else {
			CURRENT.remove();
			this.records.clear();
			this.recordServedFromScope = null;
		}
	}

	/**
	 * Identity of a single storage record within one query execution.
	 *
	 * @param owner               reader the record belongs to, compared by identity (readers define no equality)
	 * @param catalogVersion      version the record was read at - one execution normally pins a single version, but
	 *                            that invariant is stated in prose and enforced nowhere, and a nested execution
	 *                            joining this scope widens the window in which it would have to hold
	 * @param containerType       type of the storage part
	 * @param primaryKey          numeric key, {@link Long#MIN_VALUE} when the record is addressed by `originalKey`
	 * @param originalKey         non-numeric key, NULL when the record is addressed by `primaryKey`
	 * @param referenceNameFilter reference names the record was decoded for, NULL when it was decoded whole
	 */
	private record RecordKey(
		@Nonnull Object owner,
		long catalogVersion,
		@Nonnull Class<?> containerType,
		long primaryKey,
		@Nullable Object originalKey,
		@Nullable Set<String> referenceNameFilter
	) {
	}

}
