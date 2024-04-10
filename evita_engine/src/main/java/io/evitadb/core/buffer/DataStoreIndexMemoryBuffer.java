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

package io.evitadb.core.buffer;

import io.evitadb.core.EntityCollection;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This class contains all trapped changes in the local memory data structures. The reason for this is the cost of
 * storing indexes with each change to persistent storage - so we collect them in memory first and store them once in
 * {@link #popTrappedUpdates()} method.
 */
@NotThreadSafe
public class DataStoreIndexMemoryBuffer<IK extends IndexKey, I extends Index<IK>> implements DataStoreIndexChanges<IK, I> {
	/**
	 * This map contains index of "dirty" entity indexes - i.e. subset of {@link EntityCollection indexes} that were
	 * modified and not yet persisted.
	 */
	private Map<IK, I> dirtyEntityIndexes = new HashMap<>(64);

	@Override
	@Nonnull
	public Stream<StoragePart> popTrappedUpdates() {
		final Map<IK, I> dirtyEntityIndexes = this.dirtyEntityIndexes;
		this.dirtyEntityIndexes = new HashMap<>(64);
		return dirtyEntityIndexes
			.values()
			.stream()
			.flatMap(it -> it.getModifiedStorageParts().stream());
	}

	@Override
	@Nonnull
	public I getOrCreateIndexForModification(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return dirtyEntityIndexes.computeIfAbsent(
			indexKey, accessorWhenMissing
		);
	}

	@Override
	@Nullable
	public I getIndexIfExists(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return ofNullable(dirtyEntityIndexes.get(indexKey)).orElseGet(() -> accessorWhenMissing.apply(indexKey));
	}

	@Override
	@Nonnull
	public I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		final I dirtyIndexesRemoval = dirtyEntityIndexes.remove(entityIndexKey);
		final I baseIndexesRemoval = removalPropagation.apply(entityIndexKey);
		return ofNullable(dirtyIndexesRemoval).orElse(baseIndexesRemoval);
	}

}
