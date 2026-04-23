/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Common read-access surface for index lookup. Provides methods to retrieve or create indexes by key and to look up
 * indexes by their storage primary key. This interface is the shared super-type of both {@link IndexMaintainer}
 * (which adds mutative operations like removal) and
 * {@link io.evitadb.index.mutation.IndexMutationTarget IndexMutationTarget} (which adds mutation-executor-specific
 * operations like schema retrieval and filter evaluation).
 *
 * Extracting these methods into a common interface eliminates duplicate declarations and ensures a single point of
 * truth for the shared method signatures.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface IndexProvider<K extends IndexKey, T extends Index<K>> {

	/**
	 * Returns existing index for passed `entityIndexKey` or creates a new index instance and associates it with
	 * the passed `entityIndexKey`.
	 *
	 * Use this method instead of {@link #getIndexIfExists(IndexKey)} when the caller intends to mutate the
	 * returned index.
	 *
	 * @param entityIndexKey the key of the index to be retrieved or created
	 * @return the index associated with the provided key, never null
	 */
	@Nonnull
	T getOrCreateIndex(@Nonnull K entityIndexKey);

	/**
	 * Returns the index identified by its storage primary key and registers it for modification tracking so that
	 * its changed storage parts are persisted on the next flush. Returns `null` when no index with the given primary
	 * key exists.
	 *
	 * Use this method instead of {@link #getIndexByPrimaryKeyIfExists(int)} when the caller intends to mutate the
	 * returned index.
	 *
	 * @param indexPrimaryKey the unique storage primary key of the index to be retrieved
	 * @return the index associated with the provided primary key registered for modification, or null if not found
	 */
	@Nonnull
	T getOrCreateIndexByPrimaryKey(int indexPrimaryKey);

	/**
	 * Returns existing index for passed `entityIndexKey` or returns null if no such index exists.
	 *
	 * @param entityIndexKey the key of the index to be retrieved
	 * @return the index associated with the provided key, or null if no index exists
	 */
	@Nullable
	T getIndexIfExists(@Nonnull K entityIndexKey);

	/**
	 * Returns the index identified by its storage primary key, or null if no index with that key exists.
	 *
	 * @param indexPrimaryKey the unique storage primary key of the index to be retrieved
	 * @return the index associated with the provided primary key, or null if not found
	 */
	@Nullable
	T getIndexByPrimaryKeyIfExists(int indexPrimaryKey);

}
