/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.CatalogPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Implementations of this interface provide access to storage parts of particular entity collection stored on the disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface DataStoreReader {

	/**
	 * Counts the number of storage parts of the specified container type.
	 *
	 * @param catalogVersion the version of the catalog the value is read from
	 * @param containerType  the type of the storage part containers
	 * @return the number of storage parts of the specified container type
	 */
	int countStorageParts(long catalogVersion, @Nonnull Class<? extends StoragePart> containerType);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	<T extends StoragePart> T fetch(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	<T extends StoragePart> byte[] fetchBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	<T extends StoragePart, U extends Comparable<U>> T fetch(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	<T extends StoragePart, U extends Comparable<U>> byte[] fetchBinary(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer);

	/**
	 * Returns {@link EntityIndex} by key if it already exists in change set. If the index is no present there
	 * `accessorWhenMissing` is executed to retrieve primary read-only index from the origin collection.
	 */
	@Nullable
	<IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing);

	/**
	 * Returns {@link EntityIndex} by primary key if it already exists in change set. If the index is no present there
	 * `accessorWhenMissing` is executed to retrieve primary read-only index from the origin collection.
	 */
	@Nullable
	<IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(int entityIndexPrimaryKey, @Nonnull IntFunction<I> accessorWhenMissing);

}
