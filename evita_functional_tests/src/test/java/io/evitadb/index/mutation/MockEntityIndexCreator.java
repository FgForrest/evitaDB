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

package io.evitadb.index.mutation;

import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.index.IndexMaintainer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This mock object is used in tests to provide entity index without necessity to load it from persistent data storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
class MockEntityIndexCreator<K extends IndexKey, T extends Index<K>> implements IndexMaintainer<K, T> {
	private final T index;

	@Nonnull
	@Override
	public T getOrCreateIndex(@Nonnull K entityIndexKey) {
		return this.index;
	}

	@Nullable
	@Override
	public T getIndexIfExists(@Nonnull K entityIndexKey) {
		return this.index;
	}

	@Override
	public void removeIndex(@Nonnull K entityIndexKey) {
		throw new UnsupportedOperationException("Method not supported.");
	}
}
