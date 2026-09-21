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

package io.evitadb.index.mutation.local;

import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * This mock object is used in tests to provide entity index without necessity to load it from persistent data storage.
 *
 * By default it answers **every** key with the one index it was built around, which is enough for a test that only
 * ever asks for the index it passed in. Production code that resolves an index by key and then asserts its type —
 * `ReferenceIndexMutator#seedFromAdvertisedIndexes` reading a `REFERENCED_*_TYPE` key, say — sees that blanket answer
 * as a programming error, correctly, because in the engine a key of that family can only ever hold a
 * {@link io.evitadb.index.ReferencedTypeEntityIndex}. {@link #register} is how such a test tells the mock the truth
 * about one key instead of widening the production assertion to accommodate a test double.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
class MockEntityIndexCreator<K extends IndexKey, T extends Index<K>> implements IndexMaintainer<K, T> {
	private final T index;
	/**
	 * Indexes a test has registered under their real keys. Consulted before the blanket answer, so a key left
	 * unregistered keeps behaving exactly as it did before.
	 */
	@Nonnull private final Map<K, T> registeredIndexes = CollectionUtils.createHashMap(4);

	/**
	 * Registers an index under the key it would really be filed at, so lookups of that key answer truthfully.
	 *
	 * @param entityIndexKey the key the index is filed under
	 * @param registered     the index to answer with, `null` to answer that the key holds nothing
	 */
	void register(@Nonnull K entityIndexKey, @Nullable T registered) {
		this.registeredIndexes.put(entityIndexKey, registered);
	}

	@Nonnull
	@Override
	public T getOrCreateIndex(@Nonnull K entityIndexKey) {
		final T registered = this.registeredIndexes.get(entityIndexKey);
		// a key registered as holding nothing still has to be created on demand, which is what the blanket index
		// stands in for - only `getIndexIfExists` can answer "there is none"
		return registered == null ? this.index : registered;
	}

	@Nullable
	@Override
	public T getIndexIfExists(@Nonnull K entityIndexKey) {
		return this.registeredIndexes.getOrDefault(entityIndexKey, this.index);
	}

	@Nullable
	@Override
	public T getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
		return this.index;
	}

	@Nonnull
	@Override
	public T getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
		return this.index;
	}

	@Override
	public void removeIndex(@Nonnull K entityIndexKey) {
		throw new UnsupportedOperationException("Method not supported.");
	}
}
