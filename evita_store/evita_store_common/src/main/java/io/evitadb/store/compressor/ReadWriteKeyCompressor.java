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

package io.evitadb.store.compressor;

import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This implementation of {@link KeyCompressor} is used for accessing and creating new mappings between keys and integer
 * ids that are used in persisted (serialized) form to minimize space occupied by the evitaDB records.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ReadWriteKeyCompressor implements KeyCompressor {
	@Serial private static final long serialVersionUID = -791089303429347949L;

	/**
	 * Contains key index extracted from {@link KeyCompressor} that is necessary for
	 * bootstraping {@link KeyCompressor} used for file offset index deserialization.
	 */
	@Getter private final Map<Integer, Object> idToKeyIndex;
	/**
	 * Reverse lookup index to {@link #idToKeyIndex}
	 */
	private final Map<Object, Integer> keyToIdIndex;
	/**
	 * Sequence used for generating new monotonic ids for registered keys.
	 */
	private final AtomicInteger sequence;
	/**
	 * Contains TRUE when there are new keys registered in this instance.
	 */
	private final AtomicBoolean dirty = new AtomicBoolean();

	public ReadWriteKeyCompressor(@Nonnull Map<Integer, Object> keys) {
		int peek = 0;
		this.idToKeyIndex = createHashMap(Math.min(256, keys.size()));
		this.keyToIdIndex = createHashMap(Math.min(256, keys.size()));
		for (Entry<Integer, Object> entry : keys.entrySet()) {
			this.idToKeyIndex.put(entry.getKey(), entry.getValue());
			this.keyToIdIndex.put(entry.getValue(), entry.getKey());
			if (entry.getKey() > peek) {
				peek = entry.getKey();
			}
		}
		this.sequence = new AtomicInteger(peek);
	}

	/**
	 * Method returns TRUE if there were any changes in this instance since last reset or creation.
	 */
	public boolean resetDirtyFlag() {
		return this.dirty.getAndSet(false);
	}

	@Override
	public @Nonnull
	Map<Integer, Object> getKeys() {
		return this.idToKeyIndex;
	}

	@Override
	public <T extends Comparable<T>> int getId(@Nonnull T key) {
		return this.keyToIdIndex.computeIfAbsent(key, o -> {
			Assert.isPremiseValid(
				!(key instanceof String),
				"String keys are not supported by ReadWriteKeyCompressor! Always use specialized classes to avoid conflicts!"
			);
			final int id = this.sequence.incrementAndGet();
			this.idToKeyIndex.put(id, o);
			this.dirty.compareAndSet(false, true);
			return id;
		});
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> OptionalInt getIdIfExists(@Nonnull T key) {
		return Optional.ofNullable(this.keyToIdIndex.get(key))
			.map(OptionalInt::of)
			.orElseGet(OptionalInt::empty);
	}

	@Nonnull
	@Override
	public <T extends Comparable<T>> T getKeyForId(int id) {
		final Object key = this.idToKeyIndex.get(id);
		Assert.notNull(key, "There is no key for id " + id + "!");
		//noinspection unchecked
		return (T) key;
	}

	@Nullable
	@Override
	public <T extends Comparable<T>> T getKeyForIdIfExists(int id) {
		final Object key = this.idToKeyIndex.get(id);
		//noinspection unchecked
		return (T) key;
	}
}
