/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.StoragePartKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface DataStoreIndexChanges<IK extends IndexKey, I extends Index<IK>> {

	/**
	 * Returns set containing {@link StoragePartKey keys} that lead to the data structures in memory that were modified
	 * (are dirty) and needs to be persisted into the persistent storage. This is performance optimization that minimizes
	 * I/O operations for frequently changed data structures such as indexes and these are stored once in a while in
	 * the moments when it has a sense.
	 */
	@Nonnull
	Stream<StoragePart> popTrappedUpdates();

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` lambda and stores into the "dirty" memory before returning.
	 */
	@Nonnull
	I getOrCreateIndexForModification(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing);

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` and returned without adding to "dirty" memory.
	 */
	@Nullable
	I getIndexIfExists(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing);

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 */
	@Nonnull
	I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation);

}
