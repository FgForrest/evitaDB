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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * Extends {@link IndexProvider} with mutative operations — index removal and a fail-fast (throwing) primary key
 * lookup. Allows providing custom implementations to the logic that creates new {@link EntityIndex} instances
 * (allowing for example to create altered forms of the EntityIndex if needed) or removing existing ones.
 *
 * The throwing {@link #getIndexByPrimaryKey(int)} is a convenience default method that delegates to
 * {@link #getIndexByPrimaryKeyIfExists(int)} and throws if the result is null. Implementations only need to
 * provide the nullable variant.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface IndexMaintainer<K extends IndexKey, T extends Index<K>> extends IndexProvider<K, T> {

	/**
	 * Retrieves an existing index by its unique storage primary key. Throws if no index with the given key exists.
	 *
	 * This is a convenience default that delegates to {@link #getIndexByPrimaryKeyIfExists(int)} and fails fast
	 * when the index is not found. Implementations that need custom error messages can override this method.
	 *
	 * @param indexPrimaryKey the unique primary key of the index to be retrieved
	 * @return the index associated with the provided primary key, never null
	 * @throws GenericEvitaInternalError if no index with the given primary key exists
	 */
	@Nonnull
	default T getIndexByPrimaryKey(int indexPrimaryKey) {
		final T index = getIndexByPrimaryKeyIfExists(indexPrimaryKey);
		Assert.isPremiseValid(
			index != null,
			() -> new GenericEvitaInternalError(
				"Index for primary key " + indexPrimaryKey + " doesn't exist!"
			)
		);
		return index;
	}

	/**
	 * Removes existing index with passed `entityIndexKey`.
	 *
	 * @throws IllegalArgumentException if no index for passed key exists
	 */
	void removeIndex(@Nonnull K entityIndexKey);

}
