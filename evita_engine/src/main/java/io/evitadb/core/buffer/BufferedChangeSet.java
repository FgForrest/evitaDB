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

package io.evitadb.core.buffer;

import io.evitadb.core.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.StoragePartKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * DTO contains all trapped changes in this {@link DataStoreTxMemoryBuffer} and represents something like transactional
 * memory when no transaction memory is yet available. The reason for this DTO to exist is the cost of storing indexes
 * with each change to persistent storage.
 */
@NotThreadSafe
public class BufferedChangeSet<IK extends IndexKey, I extends Index<IK>> {

	/**
	 * This map contains compressed ids that were created when {@link StoragePart} was stored to the persistent storage,
	 * but the persistent storage buffer was not yet flushed so that compressed ids are still trapped in non-stored
	 * instance of the {@link KeyCompressor}.
	 *
	 * Data stored in persistent storage are not considered durable until its memory buffer is flushed and stores also
	 * key to file-locations map and propagates itself to the header file.
	 */
	private final Map<Comparable<?>, Long> nonFlushedCompressedId = new ConcurrentHashMap<>();
	/**
	 * This map contains index of "dirty" entity indexes - i.e. subset of {@link EntityCollection indexes} that were
	 * modified and not yet persisted.
	 */
	private final Map<IK, I> dirtyEntityIndexes = new ConcurrentHashMap<>();

	/**
	 * Returns non-flushed compressed id by its key.
	 */
	@Nullable
	public <U extends Comparable<U>> Long getNonFlushedCompressedId(@Nonnull U originalKey) {
		return nonFlushedCompressedId.get(originalKey);
	}

	/**
	 * Associates key with compressed id.
	 */
	public void setNonFlushedCompressedId(@Nonnull Comparable<?> originalKey, long compressedId) {
		nonFlushedCompressedId.put(originalKey, compressedId);
	}

	/**
	 * Returns set containing {@link StoragePartKey keys} that lead to the data structures in memory that were modified
	 * (are dirty) and needs to be persisted into the persistent storage. This is performance optimization that minimizes
	 * I/O operations for frequently changed data structures such as indexes and these are stored once in a while in
	 * the moments when it has a sense.
	 */
	@Nonnull
	public Stream<StoragePart> getTrappedMemTableUpdates() {
		return dirtyEntityIndexes
			.values()
			.stream()
			.flatMap(it -> it.getModifiedStorageParts().stream());
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` lambda and stores into the "dirty" memory before returning.
	 */
	@Nonnull
	public I getOrCreateIndexForModification(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return dirtyEntityIndexes.computeIfAbsent(
			indexKey, accessorWhenMissing
		);
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` and returned without adding to "dirty" memory.
	 */
	@Nullable
	public I getIndexIfExists(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return ofNullable(dirtyEntityIndexes.get(indexKey)).orElseGet(() -> accessorWhenMissing.apply(indexKey));
	}

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 */
	@Nonnull
	public I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		final I dirtyIndexesRemoval = dirtyEntityIndexes.remove(entityIndexKey);
		final I baseIndexesRemoval = removalPropagation.apply(entityIndexKey);
		return ofNullable(dirtyIndexesRemoval).orElse(baseIndexesRemoval);
	}

}
