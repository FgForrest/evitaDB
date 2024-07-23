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
 * Interface allows providing custom implementations to the logic that creates new {@link EntityIndex} instances
 * (allowing for example to create altered forms of the EntityIndex if needed) or removing existing ones.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface IndexMaintainer<K extends IndexKey, T extends Index<K>> {

	/**
	 * Returns existing index for passed `entityIndexKey` or uses `creatorFct` to create new index instance and associate
	 * it with the passed `entityIndexKey`.
	 */
	@Nonnull
	T getOrCreateIndex(@Nonnull K entityIndexKey);

	/**
	 * Returns existing index for passed `entityIndexKey` or returns null.
	 */
	@Nullable
	T getIndexIfExists(@Nonnull K entityIndexKey);

	/**
	 * Removes existing index with passed `entityIndexKey`.
	 *
	 * @throws IllegalArgumentException if no index for passed key exists
	 */
	void removeIndex(@Nonnull K entityIndexKey);

}
